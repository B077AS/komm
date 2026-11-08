package komm.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.jna.Platform;
import komm.Launcher;
import komm.utils.AppConfig;
import lombok.extern.slf4j.Slf4j;

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

/**
 * Keeps the launcher that started this client in step with it. Two jobs, both best-effort
 * (failures are logged and swallowed — this must never disturb the running client):
 *
 * <ol>
 *   <li><b>Apply a staged update (Windows).</b> The launcher checks for its own updates on every
 *       start ({@code launcher/.../UpdateManager}). It can't overwrite its own locked
 *       {@code app/komm-launcher.jar}, so it stages the download at
 *       {@code bin/komm-launcher.jar.pending}; this moves it into place once the client runs,
 *       i.e. after the launcher process has exited. (Linux needs no help: the launcher writes
 *       {@code bin/komm-launcher.jar} directly and relaunches into it on its next start.)</li>
 *   <li><b>Catch up a launcher that can't update itself.</b> The launcher now lives in this repo
 *       ({@code launcher/}) and every client release attaches its jars, so launcher version ==
 *       client version. Launchers installed before the move look for updates in the old, deleted
 *       komm-launcher repo and would never update again. When the launcher's version (forwarded
 *       as {@code -Dlauncher.version}) differs from this client's, the launcher jar of this
 *       client's own release is downloaded (SHA-256 checked against GitHub's asset digest) and
 *       installed the same way the launcher would have done it.</li>
 * </ol>
 */
@Slf4j
public class LauncherUpdateService {

    private static final String REPO = System.getProperty("github.client.repo.path", "B077AS/komm");
    private static final String WINDOWS_ASSET = "komm-launcher-windows.jar";
    private static final String LINUX_ASSET = "komm-launcher-linux.jar";

    public void run() {
        if (Platform.isWindows()) applyStagedWindowsUpdate();
        try {
            catchUpLauncher();
        } catch (Exception e) {
            log.debug("Launcher catch-up update failed (non-fatal): {}", e.toString());
        }
    }

    // ── 1. staged Windows update ─────────────────────────────────────────────

    private void applyStagedWindowsUpdate() {
        try {
            Path pending = binDir().resolve("komm-launcher.jar.pending");
            if (!Files.exists(pending)) return;

            Path appDir = windowsAppDir();
            if (appDir == null) {
                // Not a packaged install (e.g. a dev run) — nothing to swap.
                Files.deleteIfExists(pending);
                return;
            }

            Path target = appDir.resolve("komm-launcher.jar");
            Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("Applied staged launcher update at {}", target);

            repointCfgIfNeeded(appDir);
        } catch (Exception e) {
            log.debug("Applying staged launcher update failed (non-fatal): {}", e.toString());
        }
    }

    // ── 2. catch-up from this client's release ───────────────────────────────

    private void catchUpLauncher() throws Exception {
        String launcherVersion = System.getProperty("launcher.version");
        String clientVersion = AppConfig.getInstance().getProperty("client.version");
        if (launcherVersion == null || launcherVersion.isBlank()) return;   // not started by a launcher
        if (clientVersion == null || clientVersion.isBlank() || clientVersion.contains("${")) return;
        if (launcherVersion.equals(clientVersion)) return;                  // in step

        boolean win = Platform.isWindows();
        Path target;
        if (win) {
            Path appDir = windowsAppDir();
            if (appDir == null) return;
            target = appDir.resolve("komm-launcher.jar");
        } else {
            if (System.getenv("APPIMAGE") == null) return; // launcher relaunch only exists inside the AppImage
            target = binDir().resolve("komm-launcher.jar");
        }

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JsonObject asset = findAsset(http, clientVersion, win ? WINDOWS_ASSET : LINUX_ASSET);
        if (asset == null) {
            log.debug("Release {} has no launcher asset yet — skipping launcher catch-up", clientVersion);
            return;
        }

        String url = asset.get("browser_download_url").getAsString();
        HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200 || res.body().length == 0) {
            log.debug("Launcher download failed: HTTP {}", res.statusCode());
            return;
        }
        JsonElement digest = asset.get("digest");
        if (!digestMatches(res.body(), digest == null || digest.isJsonNull() ? null : digest.getAsString())) {
            log.warn("Downloaded launcher failed its integrity check; discarding");
            return;
        }

        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".download");
        Files.write(tmp, res.body());
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception locked) {
            if (!win) throw locked;
            // Launcher still shutting down and holding its jar: stage it for the next start.
            Files.move(tmp, binDir().resolve("komm-launcher.jar.pending"), StandardCopyOption.REPLACE_EXISTING);
            log.info("Launcher {} -> {} staged for the next start", launcherVersion, clientVersion);
            return;
        }
        if (win) repointCfgIfNeeded(target.getParent());
        log.info("Updated launcher {} -> {} at {}", launcherVersion, clientVersion, target);
    }

    /** The named asset of the release tagged with this client's version ("1.2.3" or "v1.2.3"). */
    private static JsonObject findAsset(HttpClient http, String version, String name) throws Exception {
        for (String tag : new String[]{version, "v" + version}) {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(
                            URI.create("https://api.github.com/repos/" + REPO + "/releases/tags/" + tag))
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) continue;
            JsonArray assets = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonArray("assets");
            if (assets == null) return null;
            for (JsonElement a : assets) {
                JsonObject o = a.getAsJsonObject();
                if (name.equals(o.get("name").getAsString())) return o;
            }
            return null;
        }
        return null;
    }

    /** GitHub's {@code sha256:<hex>} asset digest; uploads predating digests are accepted. */
    private static boolean digestMatches(byte[] bytes, String digest) throws Exception {
        if (digest == null || digest.isBlank()) return true;
        String expected = digest.startsWith("sha256:") ? digest.substring(7) : digest;
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return expected.trim().equalsIgnoreCase(actual);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Path binDir() {
        return Launcher.getAppDataDirectory().resolve("bin");
    }

    /** {@code <install>/app} of a packaged Windows install (the runtime is {@code <install>/runtime}). */
    private static Path windowsAppDir() {
        Path installRoot = Path.of(System.getProperty("java.home")).getParent();
        if (installRoot == null) return null;
        Path appDir = installRoot.resolve("app");
        return Files.isDirectory(appDir) ? appDir : null;
    }

    /** {@code Komm.cfg}'s classpath line for the launcher's own jar, jpackage-generated. */
    private static final String CFG_CLASSPATH_PREFIX = "app.classpath=$APPDIR\\";
    private static final String CFG_LAUNCHER_JAR_PREFIX = CFG_CLASSPATH_PREFIX + "komm-launcher-";
    private static final String CFG_LAUNCHER_JAR_FIXED = CFG_CLASSPATH_PREFIX + "komm-launcher.jar";

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
}
