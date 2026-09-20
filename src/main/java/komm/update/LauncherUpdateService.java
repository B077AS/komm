package komm.update;

import com.sun.jna.Platform;
import komm.Launcher;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies a launcher update the launcher itself already downloaded and
 * staged — this class no longer talks to GitHub at all. {@code UpdateManager}
 * (in the komm-launcher repo) checks for and fetches launcher updates on
 * every start, since checking here would mean the launcher only ever gets
 * updated if the client managed to start successfully first, which isn't a
 * safe thing to depend on.
 *
 * <p>Windows only: the launcher can't overwrite its own currently-loaded
 * {@code app/komm-launcher.jar} (that file is locked for as long as its
 * process is running), so it stages the download at
 * {@code bin/komm-launcher.jar.pending} instead and exits; this runs once the
 * client has started, i.e. strictly after that process is gone, and moves the
 * staged jar into place.
 *
 * <p>Linux needs nothing here: a JVM already running off a path keeps working
 * off its old inode after that path is atomically replaced underneath it
 * (POSIX unlink-while-open), so the launcher's own {@code UpdateManager}
 * writes updates straight to their stable final location, and the launcher
 * picks them up by relaunching into it on its own next start — the client is
 * never involved.
 *
 * <p>Best-effort throughout: any failure is logged and swallowed, since this
 * must never interfere with the client actually running.
 */
@Slf4j
public class LauncherUpdateService {

    public void run() {
        if (!Platform.isWindows()) return; // Linux applies its own updates; see class javadoc
        try {
            Path pending = Launcher.getAppDataDirectory().resolve("bin").resolve("komm-launcher.jar.pending");
            if (!Files.exists(pending)) return;

            Path installRoot = installRootFromJavaHome();
            if (installRoot == null) return;
            Path appDir = installRoot.resolve("app");
            if (!Files.isDirectory(appDir)) {
                // Not a packaged install (e.g. a dev run) — nothing to swap.
                Files.deleteIfExists(pending);
                return;
            }

            Path target = appDir.resolve("komm-launcher.jar");
            Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("Applied staged launcher update at {}", target);

            repointCfgIfNeeded(appDir);
        } catch (Exception e) {
            // Best-effort: a failed launcher self-update must never disrupt the running client.
            log.debug("Applying staged launcher update failed (non-fatal): {}", e.toString());
        }
    }

    private static Path installRootFromJavaHome() {
        Path javaHome = Path.of(System.getProperty("java.home"));
        return javaHome.getParent();
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
