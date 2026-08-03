package komm.update;

import com.sun.jna.Platform;
import komm.api.HttpClientWrapper;
import komm.utils.AppConfig;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Checks the hub, once per run, for a newer launcher than the one that started
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
            String os = Platform.isWindows() ? "windows" : "linux";

            HttpClientWrapper http = new HttpClientWrapper(AppConfig.getInstance().getApiUrl());
            LauncherVersionResponse latest = http.get("/api/launcher/latest?os=" + os, null, LauncherVersionResponse.class);
            if (latest == null || latest.getVersion() == null || latest.getDownloadUrl() == null) {
                log.debug("No launcher version info from hub; skipping update check");
                return;
            }

            if (latest.getVersion().equals(currentVersion)) {
                log.debug("Launcher is up to date (version {})", currentVersion);
                return;
            }

            log.info("Launcher update available: {} -> {}", currentVersion, latest.getVersion());
            byte[] bytes = http.downloadBinary("/api/launcher/download?os=" + os, null, null);
            if (bytes == null || bytes.length == 0) {
                log.warn("Launcher update download was empty; skipping swap");
                return;
            }

            if (latest.getSha256() != null && !latest.getSha256().isBlank()) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                String actual = HexFormat.of().formatHex(digest.digest(bytes));
                if (!latest.getSha256().trim().equalsIgnoreCase(actual)) {
                    log.warn("Downloaded launcher update failed integrity check; skipping swap");
                    return;
                }
            }

            if (Platform.isWindows()) {
                swapWindowsLauncherJar(bytes);
            } else {
                swapLinuxAppImage(bytes);
            }
        } catch (Exception e) {
            // Best-effort: a failed launcher self-update must never disrupt the running client.
            log.debug("Launcher update check failed (non-fatal): {}", e.toString());
        }
    }

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
