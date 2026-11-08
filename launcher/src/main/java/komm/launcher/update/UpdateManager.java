package komm.launcher.update;

import com.google.gson.Gson;
import com.sun.jna.Platform;
import komm.launcher.Launcher;
import komm.launcher.LauncherConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Drives the whole launcher sequence on a background thread:
 *
 * <ol>
 *   <li>ask GitHub for the client repo's latest release
 *       ({@code GET api.github.com/repos/{owner}/{repo}/releases/latest}),</li>
 *   <li>compare its tag to the {@code client.version} embedded in the installed
 *       jar's {@code app.properties} — the same self-describing entry the
 *       client's own build filters in,</li>
 *   <li>download + replace the jar if it's missing or stale, verifying it
 *       against the SHA-256 digest GitHub reports for that release asset,</li>
 *   <li>launch the client jar and exit.</li>
 * </ol>
 *
 * <p>Every update is mandatory: once GitHub has announced a newer version, a
 * failed download shows an error instead of falling back to the stale jar. Only
 * when GitHub itself is unreachable does an already-installed jar launch as-is.
 *
 * <p>All progress is reported through {@link Listener}; callbacks fire on the
 * background thread, so the UI marshals them onto the FX thread itself.
 *
 * <p>A scripted {@link #runDemo()} reproduces the same visuals without a network
 * call or a real jar, so the look &amp; feel can be reviewed in isolation.
 */
@Slf4j
public class UpdateManager {

    /** Callbacks for the UI. All fire on the background worker thread. */
    public interface Listener {
        void onPhase(Phase phase);

        /** Determinate download progress, 0..1. */
        void onProgress(double fraction);

        /** Terminal failure; no client was launched. */
        void onError(String message);

        /** The client jar was launched; the launcher should now close. */
        void onLaunched();
    }

    /** Public GitHub repo the client is released from — overridable for testing against a fork. */
    private static final String GITHUB_CLIENT_OWNER = systemPropertyOr("github.client.owner", "B077AS");
    private static final String GITHUB_CLIENT_REPO = systemPropertyOr("github.client.repo", "komm");

    /** Repo the launcher checks for its own updates. The launcher lives in the client's repo
     *  (launcher/) and every client release attaches the launcher jars too, so this is the
     *  same repo as the client; overridable for testing against a fork. */
    private static final String GITHUB_LAUNCHER_OWNER = systemPropertyOr("github.launcher.owner", GITHUB_CLIENT_OWNER);
    private static final String GITHUB_LAUNCHER_REPO = systemPropertyOr("github.launcher.repo", GITHUB_CLIENT_REPO);
    private static final String WINDOWS_LAUNCHER_ASSET = "komm-launcher-windows.jar";
    private static final String LINUX_LAUNCHER_ASSET = "komm-launcher-linux.jar";

    private final LauncherConfig config = LauncherConfig.getInstance();
    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Listener listener;
    private final String[] clientArgs;

    private static String systemPropertyOr(String key, String fallback) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** What {@link #fetchLatest()} resolves a GitHub release down to. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class ResolvedRelease {
        private String version;
        private String downloadUrl;
        private String sha256;
    }

    public UpdateManager(Listener listener, String[] clientArgs) {
        this.listener = listener;
        this.clientArgs = clientArgs == null ? new String[0] : clientArgs;
    }

    // ── Real flow ─────────────────────────────────────────────────────────────

    public void run() {
        BundledClientSeeder.seedIfMissing();
        deleteLegacyVersionFile();
        // Fire-and-forget: the launcher's own update must never delay or block
        // the client update/launch flow below, so it runs on its own thread and
        // is entirely best-effort (see checkAndStageLauncherUpdate's javadoc).
        Thread.ofVirtual().start(this::checkAndStageLauncherUpdate);

        Path jar = Launcher.getClientJar();
        // Flipped once GitHub has announced a version we don't have; from that
        // point on the stale jar is never launched as a fallback.
        boolean updateRequired = false;

        try {
            listener.onPhase(Phase.CHECKING);
            pause(450); // let the phrase land, Discord-style

            ResolvedRelease latest = fetchLatest();
            String localVersion = readVersionFromJar(jar);
            log.info("Local version={}, latest version={}", localVersion, latest.getVersion());

            boolean upToDate = localVersion != null
                    && latest.getVersion() != null
                    && latest.getVersion().equals(localVersion);

            if (upToDate) {
                listener.onPhase(Phase.UP_TO_DATE);
                pause(550);
            } else {
                updateRequired = true;
                downloadAndInstall(latest);
            }

            listener.onPhase(Phase.STARTING);
            pause(350);
            launchClient(jar);
            listener.onLaunched();

        } catch (Exception e) {
            log.warn("Update flow failed: {}", e.toString());
            // GitHub unreachable (offline, GitHub down, rate-limited): run what we
            // already have. A known-stale jar is never launched — the error stays on screen.
            if (!updateRequired && Files.exists(jar)) {
                try {
                    listener.onPhase(Phase.STARTING);
                    pause(300);
                    launchClient(jar);
                    listener.onLaunched();
                    return;
                } catch (Exception launchEx) {
                    log.error("Failed to launch existing jar: {}", launchEx.toString());
                }
            }
            listener.onError(friendlyError(e));
        }
    }

    /** {@code GET /repos/{owner}/{repo}/releases/latest}, raw — shared by the client
     *  check below and the launcher's own self-update check. */
    private GithubRelease fetchLatestRelease(String owner, String repo) throws Exception {
        String url = "https://api.github.com/repos/" + owner + "/" + repo + "/releases/latest";
        log.debug("GET {}", url);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("GitHub returned HTTP " + res.statusCode());
        }
        GithubRelease release = gson.fromJson(res.body(), GithubRelease.class);
        if (release == null || release.getTagName() == null) {
            throw new IllegalStateException("Malformed GitHub release response");
        }
        return release;
    }

    /** Resolved down to the jar asset this launcher wants for the client. */
    private ResolvedRelease fetchLatest() throws Exception {
        GithubRelease release = fetchLatestRelease(GITHUB_CLIENT_OWNER, GITHUB_CLIENT_REPO);
        String tagName = release.getTagName();
        String version = tagName.startsWith("v") ? tagName.substring(1) : tagName;

        // The client's own release workflow names the asset after the tag it was
        // built from, so the expected name is derivable without a second request.
        String expectedAssetName = "komm-" + version + ".jar";
        GithubRelease.GithubAsset asset = release.findAsset(expectedAssetName);
        if (asset == null || asset.getBrowserDownloadUrl() == null) {
            throw new IllegalStateException("Release " + version + " has no asset named " + expectedAssetName + " yet");
        }
        // GitHub computes and returns this itself for every uploaded asset — no
        // separate checksum file needed.
        String sha256 = asset.getDigest();
        if (sha256 != null && sha256.startsWith("sha256:")) {
            sha256 = sha256.substring("sha256:".length());
        }
        return ResolvedRelease.builder()
                .version(version)
                .downloadUrl(asset.getBrowserDownloadUrl())
                .sha256(sha256)
                .build();
    }

    /**
     * Checks GitHub for a newer launcher than this one and stages it for
     * installation. Best-effort throughout — any failure is logged and
     * swallowed, since this must never interfere with the client update/launch
     * flow it runs alongside (see the fire-and-forget call in {@link #run()}).
     *
     * <p>Windows can't overwrite its own currently-loaded {@code app/komm-launcher.jar}
     * — this running process has it locked — so the download is staged at
     * {@code bin/komm-launcher.jar.pending} instead, and applied by the client
     * on its next start, once this launcher process has already exited (see the
     * client's {@code LauncherUpdateService}).
     *
     * <p>Linux has no such lock: a JVM already running off a path keeps working
     * off its old inode after that path is atomically replaced underneath it
     * (POSIX unlink-while-open), so the download goes straight to the stable
     * {@code bin/komm-launcher.jar}; no separate pending file needed. {@link
     * Launcher#main} checks for that file itself on every start and relaunches
     * into it before doing anything else.
     */
    private void checkAndStageLauncherUpdate() {
        try {
            String currentVersion = config.getLauncherVersion();
            GithubRelease release = fetchLatestRelease(GITHUB_LAUNCHER_OWNER, GITHUB_LAUNCHER_REPO);
            String tagName = release.getTagName();
            String latestVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;
            if (latestVersion.equals(currentVersion)) {
                log.debug("Launcher is up to date (version {})", currentVersion);
                return;
            }

            boolean win = Platform.isWindows();
            String assetName = win ? WINDOWS_LAUNCHER_ASSET : LINUX_LAUNCHER_ASSET;
            GithubRelease.GithubAsset asset = release.findAsset(assetName);
            if (asset == null || asset.getBrowserDownloadUrl() == null) {
                log.debug("Launcher release {} has no asset named {} yet", latestVersion, assetName);
                return;
            }

            log.info("Launcher update available: {} -> {}", currentVersion, latestVersion);
            byte[] bytes = downloadBytes(asset.getBrowserDownloadUrl());
            if (bytes.length == 0 || !digestMatches(bytes, asset.getDigest())) {
                log.warn("Downloaded launcher update failed integrity check; discarding");
                return;
            }

            Path bin = Launcher.getBinDirectory();
            Files.createDirectories(bin);
            String targetName = win ? "komm-launcher.jar.pending" : "komm-launcher.jar";
            Path target = bin.resolve(targetName);
            Path tmp = bin.resolve(targetName + ".download");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            log.info(win ? "Staged launcher update at {} for the client to apply"
                          : "Installed launcher update at {} (applied on next start)", target);
        } catch (Exception e) {
            log.debug("Launcher self-update check failed (non-fatal): {}", e.toString());
        }
    }

    private byte[] downloadBytes(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Download failed: HTTP " + res.statusCode());
        }
        try (InputStream in = res.body()) {
            return in.readAllBytes();
        }
    }

    /** True if {@code digestHeader} (GitHub's {@code sha256:<hex>} asset digest) matches
     *  {@code bytes}, or if GitHub didn't report one (older uploads predating this feature). */
    private boolean digestMatches(byte[] bytes, String digestHeader) throws Exception {
        if (digestHeader == null || digestHeader.isBlank()) return true;
        String expected = digestHeader.startsWith("sha256:") ? digestHeader.substring("sha256:".length()) : digestHeader;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String actual = HexFormat.of().formatHex(digest.digest(bytes));
        return expected.trim().equalsIgnoreCase(actual);
    }

    private void downloadAndInstall(ResolvedRelease latest) throws Exception {
        listener.onPhase(Phase.DOWNLOADING);
        listener.onProgress(0);

        Path bin = Launcher.getBinDirectory();
        Files.createDirectories(bin);
        Path tmp = bin.resolve("komm.jar.download");

        HttpRequest req = HttpRequest.newBuilder(URI.create(latest.getDownloadUrl())).GET().build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Download failed: HTTP " + res.statusCode());
        }

        long contentLength = res.headers().firstValueAsLong("Content-Length").orElse(-1);
        MessageDigest digest = newSha256();
        try (InputStream in = res.body();
             var out = Files.newOutputStream(tmp)) {
            byte[] buffer = new byte[1 << 16];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (digest != null) digest.update(buffer, 0, read);
                total += read;
                if (contentLength > 0) {
                    listener.onProgress((double) total / contentLength);
                }
            }
        }
        listener.onProgress(1.0);

        listener.onPhase(Phase.INSTALLING);
        pause(400);
        // The jar must be able to state its own version, or the next startup's
        // comparison would break — a truncated/corrupt download fails here
        // instead of replacing a working install.
        String downloadedVersion = readVersionFromJar(tmp);
        if (downloadedVersion == null) {
            Files.deleteIfExists(tmp);
            throw new IllegalStateException("Downloaded jar is corrupt (no readable version)");
        }
        // Cryptographic check against GitHub's own per-asset digest — skipped only
        // if GitHub didn't report one (older uploads predating this feature).
        String expectedSha256 = latest.getSha256();
        if (expectedSha256 != null && !expectedSha256.isBlank() && digest != null) {
            String actualSha256 = HexFormat.of().formatHex(digest.digest());
            if (!expectedSha256.trim().equalsIgnoreCase(actualSha256)) {
                Files.deleteIfExists(tmp);
                throw new IllegalStateException("Downloaded jar failed integrity check");
            }
        }
        Path jar = Launcher.getClientJar();
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
        deleteExtractedNatives(bin);
        AppsFeaturesVersionUpdater.updateIfInstalled(downloadedVersion);
        log.info("Installed client jar version {} -> {}", downloadedVersion, jar);
    }

    /**
     * The client's JNativeHook extracts its native library next to the jar,
     * named after the jar's Implementation-Version — so every update strands
     * the previous version's copy. Best-effort sweep; the client re-extracts
     * on next start, and a file still loaded by a running client just stays.
     */
    private static void deleteExtractedNatives(Path bin) {
        try (var files = Files.list(bin)) {
            files.filter(f -> f.getFileName().toString().startsWith("JNativeHook-"))
                    .forEach(f -> {
                        try {
                            Files.deleteIfExists(f);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception e) {
            log.debug("Could not clean extracted natives: {}", e.toString());
        }
    }

    private void launchClient(Path jar) throws Exception {
        if (!Files.exists(jar)) {
            throw new IllegalStateException("Client jar not found at " + jar);
        }
        String javaBin = resolveJavaBinary();
        List<String> command = new ArrayList<>();
        command.add(javaBin);
        // Lets the client tell whether the launcher that started it needs updating —
        // absent entirely on an old/unpatched launcher, which the client treats as stale.
        String launcherVersion = config.getLauncherVersion();
        if (launcherVersion != null && !launcherVersion.isBlank()) {
            command.add("-Dlauncher.version=" + launcherVersion);
        }
        command.addAll(ClientJvmOptions.get());
        command.add("-jar");
        command.add(jar.toAbsolutePath().toString());
        // Forward any deep-link args (e.g. komm://invite/...) the launcher was opened with.
        for (String a : clientArgs) command.add(a);

        log.info("Launching client: {}", String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(jar.getParent().toFile())
                .inheritIO();
        ClientJvmOptions.applyEnvironment(pb.environment());
        Process process = pb.start();
        guardAppImageMount(process);
    }

    /** True once the client was started from inside an AppImage; the launcher JVM
     *  must then outlive the window (see {@link #guardAppImageMount}). */
    private static volatile boolean appImageMountGuarded = false;

    public static boolean isAppImageMountGuarded() {
        return appImageMountGuarded;
    }

    /**
     * Running from an AppImage, this process's exit unmounts the FUSE image — and
     * with it the private runtime the client was just launched with. So linger
     * invisibly (the window still closes via {@code Platform.exit()}) on a
     * non-daemon thread until the client exits, then let the JVM die.
     */
    private static void guardAppImageMount(Process client) {
        if (System.getenv("APPIMAGE") == null) return;
        appImageMountGuarded = true;
        Thread keeper = new Thread(() -> {
            try {
                client.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "appimage-mount-keeper");
        keeper.setDaemon(false);
        keeper.start();
        log.info("AppImage detected; keeping the mount alive until the client exits");
    }

    /**
     * Prefer javaw.exe on Windows so the client has no stray console window. Packaged
     * builds carry a rebranded copy of javaw.exe (runtime/bin/Komm.exe, see the
     * installer profile's rebrand-client-exe step) whose version resource reads "Komm"
     * instead of stock javaw.exe's "Java Platform SE binary" — used when present so the
     * client shows up as "Komm" in Task Manager; dev runs fall back to plain javaw.exe.
     */
    private String resolveJavaBinary() {
        Path javaHome = Path.of(System.getProperty("java.home"));
        boolean win = Platform.isWindows();
        if (win) {
            Path branded = javaHome.resolve("bin").resolve("Komm.exe");
            if (Files.exists(branded)) return branded.toAbsolutePath().toString();
        }
        Path candidate = javaHome.resolve("bin").resolve(win ? "javaw.exe" : "java");
        if (Files.exists(candidate)) return candidate.toAbsolutePath().toString();
        return win ? "javaw" : "java";
    }

    /** SHA-256 instance for hashing a download in-flight, or null if unavailable
     *  (every JDK provides it, but the integrity check is best-effort either way). */
    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            log.warn("SHA-256 unavailable, skipping download integrity check: {}", e.toString());
            return null;
        }
    }

    /** Jar entry + property carrying the client's version — the same
     *  self-describing {@code app.properties} the client's build filters in. */
    private static final String PROPERTIES_ENTRY = "app.properties";
    private static final String VERSION_PROPERTY = "client.version";

    /** Version embedded in the given jar, or null if the jar is missing or unreadable. */
    private static String readVersionFromJar(Path jar) {
        if (!Files.exists(jar)) return null;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(PROPERTIES_ENTRY);
            if (entry == null) return null;
            Properties props = new Properties();
            try (InputStream in = zip.getInputStream(entry)) {
                props.load(in);
            }
            String version = props.getProperty(VERSION_PROPERTY);
            return version == null || version.isBlank() ? null : version.trim();
        } catch (Exception e) {
            log.debug("Could not read version from {}: {}", jar, e.toString());
            return null;
        }
    }

    /** Older launcher versions tracked the installed version in a version.txt
     *  next to the jar; the jar is self-describing now, so clean the file up. */
    private static void deleteLegacyVersionFile() {
        try {
            Files.deleteIfExists(Launcher.getBinDirectory().resolve("version.txt"));
        } catch (Exception ignored) {
        }
    }

    private static String friendlyError(Exception e) {
        if (e instanceof java.net.ConnectException
                || e instanceof java.net.http.HttpConnectTimeoutException
                || e instanceof java.net.UnknownHostException) {
            return "Can't reach the update server";
        }
        return "Update failed — please try again";
    }

    // ── Demo flow ─────────────────────────────────────────────────────────────

    /** Scripted sequence so the visuals can be reviewed without a hub or a real jar. */
    public void runDemo() {
        try {
            listener.onPhase(Phase.CHECKING);
            pause(1100);

            listener.onPhase(Phase.DOWNLOADING);
            for (int i = 0; i <= 100; i += 2) {
                listener.onProgress(i / 100.0);
                pause(45);
            }

            listener.onPhase(Phase.INSTALLING);
            pause(1200);

            listener.onPhase(Phase.STARTING);
            pause(1000);

            listener.onPhase(Phase.DONE);
        } catch (Exception e) {
            listener.onError("Demo interrupted");
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
