package komm.update;

import com.google.gson.Gson;
import com.sun.jna.Platform;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Checks GitHub, once per run, for a newer launcher than the one that started
 * this client, and swaps it in place in the background if so.
 *
 * <p>The launcher forwards its own version as {@code -Dlauncher.version=...}
 * when it spawns the client — absent entirely on an old/unpatched launcher,
 * which this treats the same as "definitely outdated" (mirrors how the
 * launcher's own {@code UpdateManager} treats a missing client version).
 *
 * <p>The swap itself is passive: on Windows it overwrites
 * {@code <installRoot>/app/komm-launcher.jar} (safe even while this client is
 * running, since the launcher process that loaded that jar already exited
 * before spawning the client); on Linux it overwrites the {@code .AppImage}
 * file at {@code $APPIMAGE} (safe even while the current one is mounted). No
 * UI, no restart prompt — the new version is picked up next time the user
 * launches through the (already-updated) launcher. Best-effort throughout:
 * any failure is logged and swallowed, since this must never interfere with
 * the client actually running.
 */
@Slf4j
public class LauncherUpdateService {

    private static final int INITIAL_DELAY_SECONDS = 5;

    /** Public GitHub repo the launcher is released from — overridable for testing against a fork. */
    private static final String GITHUB_LAUNCHER_OWNER = systemPropertyOr("github.launcher.owner", "B077AS");
    private static final String GITHUB_LAUNCHER_REPO = systemPropertyOr("github.launcher.repo", "komm-launcher");

    private static final String WINDOWS_ASSET_NAME = "komm-launcher-windows.jar";
    private static final String LINUX_ASSET_NAME = "komm-launcher-linux.AppImage";

    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static String systemPropertyOr(String key, String fallback) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private ScheduledExecutorService scheduler;

    public void start() {
        stop();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "launcher-update-check");
            t.setDaemon(true);
            return t;
        });
        scheduler.schedule(this::checkAndSwap, INITIAL_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        scheduler = null;
    }

    private void checkAndSwap() {
        try {
            String currentVersion = System.getProperty("launcher.version");
            boolean windows = Platform.isWindows();
            String assetName = windows ? WINDOWS_ASSET_NAME : LINUX_ASSET_NAME;

            GithubRelease release = fetchLatestRelease();
            if (release == null || release.getTagName() == null) {
                log.debug("No launcher release info from GitHub; skipping update check");
                return;
            }
            String tagName = release.getTagName();
            String latestVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;

            if (latestVersion.equals(currentVersion)) {
                log.debug("Launcher is up to date (version {})", currentVersion);
                return;
            }

            GithubRelease.GithubAsset asset = release.findAsset(assetName);
            if (asset == null || asset.getBrowserDownloadUrl() == null) {
                log.warn("Launcher release {} has no asset named {} yet; skipping swap", latestVersion, assetName);
                return;
            }

            log.info("Launcher update available: {} -> {}", currentVersion, latestVersion);
            byte[] bytes = downloadBinary(asset.getBrowserDownloadUrl());
            if (bytes == null || bytes.length == 0) {
                log.warn("Launcher update download was empty; skipping swap");
                return;
            }

            // GitHub computes and returns this itself for every uploaded asset — no
            // separate checksum file or hub involved.
            String expectedSha256 = asset.getDigest();
            if (expectedSha256 != null && expectedSha256.startsWith("sha256:")) {
                expectedSha256 = expectedSha256.substring("sha256:".length());
            }
            if (expectedSha256 != null && !expectedSha256.isBlank()) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                String actual = HexFormat.of().formatHex(digest.digest(bytes));
                if (!expectedSha256.trim().equalsIgnoreCase(actual)) {
                    log.warn("Downloaded launcher update failed integrity check; skipping swap");
                    return;
                }
            }

            if (windows) {
                swapWindowsLauncherJar(bytes);
            } else {
                swapLinuxAppImage(bytes);
            }
        } catch (Exception e) {
            // Best-effort: a failed launcher self-update must never disrupt the running client.
            log.debug("Launcher update check failed (non-fatal): {}", e.toString());
        }
    }

    /** {@code GET /repos/{owner}/{repo}/releases/latest}. */
    private GithubRelease fetchLatestRelease() throws Exception {
        String url = "https://api.github.com/repos/" + GITHUB_LAUNCHER_OWNER + "/" + GITHUB_LAUNCHER_REPO + "/releases/latest";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("GitHub returned HTTP " + res.statusCode());
        }
        return gson.fromJson(res.body(), GithubRelease.class);
    }

    private byte[] downloadBinary(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Download failed: HTTP " + res.statusCode());
        }
        try (InputStream in = res.body()) {
            return in.readAllBytes();
        }
    }

    /** {@code Komm.cfg}'s classpath line for the launcher's own jar, jpackage-generated. */
    private static final String CFG_CLASSPATH_PREFIX = "app.classpath=$APPDIR\\";
    private static final String CFG_LAUNCHER_JAR_PREFIX = CFG_CLASSPATH_PREFIX + "komm-launcher-";
    private static final String CFG_LAUNCHER_JAR_FIXED = CFG_CLASSPATH_PREFIX + "komm-launcher.jar";

    /** The client always runs from {@code <installRoot>/runtime}; the launcher's
     *  own jar lives alongside it at {@code <installRoot>/app/komm-launcher.jar}. */
    private void swapWindowsLauncherJar(byte[] bytes) throws Exception {
        Path javaHome = Paths.get(System.getProperty("java.home"));
        Path installRoot = javaHome.getParent();
        if (installRoot == null) return;
        Path appDir = installRoot.resolve("app");
        if (!Files.isDirectory(appDir)) {
            // Not a packaged install (e.g. a dev run) — nothing to swap.
            return;
        }
        Path target = appDir.resolve("komm-launcher.jar");
        Path tmp = appDir.resolve("komm-launcher.jar.download");
        Files.write(tmp, bytes);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        log.info("Swapped launcher jar at {}", target);

        repointCfgIfNeeded(appDir);
    }

    /**
     * Installs built before the launcher jar's filename was pinned to a constant
     * have {@code Komm.cfg} pointing at a versioned name (e.g. {@code komm-launcher-0.0.1.jar}) —
     * the swap above always writes {@code komm-launcher.jar}, but {@code Komm.exe} would keep
     * loading the old file unless this repoints it. One-time per install: once
     * {@code Komm.cfg} says {@code komm-launcher.jar}, no line matches the stale prefix and
     * this is a no-op on every future swap.
     */
    private void repointCfgIfNeeded(Path appDir) {
        Path cfg = appDir.resolve("Komm.cfg");
        if (!Files.isRegularFile(cfg)) return;
        try {
            List<String> lines = Files.readAllLines(cfg);
            List<String> updated = new ArrayList<>(lines.size());
            String staleEntry = null;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith(CFG_LAUNCHER_JAR_PREFIX) && trimmed.endsWith(".jar")) {
                    staleEntry = trimmed.substring(CFG_CLASSPATH_PREFIX.length());
                    updated.add(CFG_LAUNCHER_JAR_FIXED);
                } else {
                    updated.add(line);
                }
            }
            if (staleEntry == null) return;

            Path tmpCfg = appDir.resolve("Komm.cfg.new");
            Files.write(tmpCfg, updated);
            Files.move(tmpCfg, cfg, StandardCopyOption.REPLACE_EXISTING);
            log.info("Repointed Komm.cfg from {} to komm-launcher.jar", staleEntry);

            Files.deleteIfExists(appDir.resolve(staleEntry));
        } catch (Exception e) {
            log.warn("Could not repoint Komm.cfg (this install may need a manual reinstall): {}", e.toString());
        }
    }

    private void swapLinuxAppImage(byte[] bytes) throws Exception {
        String appImagePath = System.getenv("APPIMAGE");
        if (appImagePath == null || appImagePath.isBlank()) {
            // Not running from an AppImage (dev run, or a from-source install) — nothing to swap.
            return;
        }
        Path target = Paths.get(appImagePath);
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".download");
        Files.write(tmp, bytes);
        // AppImages must stay executable — a plain write defaults to non-executable permissions.
        tmp.toFile().setExecutable(true, false);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        log.info("Swapped AppImage at {}", target);
    }
}
