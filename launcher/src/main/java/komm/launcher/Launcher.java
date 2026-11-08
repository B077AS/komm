package komm.launcher;

import com.sun.jna.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Entry point for the Komm launcher.
 *
 * <p>Mirrors the komm client's {@code Launcher}: it resolves the same per-OS app
 * data directory ({@code %APPDATA%/Komm} on Windows), sets the logging system
 * properties logback.xml reads, then hands control to the JavaFX application.
 *
 * <p>The launcher owns the installed client jar under {@link #getBinDirectory()}.
 * On startup it checks the hub for a newer version, downloads/replaces the jar if
 * needed, and finally launches it.
 */
public class Launcher {

    private static final String APP_NAME = "Komm";

    /** Same root directory the komm client uses, so config/logs/data are shared. */
    public static Path getAppDataDirectory() {
        String userHome = System.getProperty("user.home");

        if (Platform.isWindows()) {
            String appData = System.getenv("APPDATA");
            if (appData != null) return Paths.get(appData, APP_NAME);
            return Paths.get(userHome, "AppData", "Roaming", APP_NAME);
        } else {
            return Paths.get(userHome, ".config", APP_NAME);
        }
    }

    /** Where the launcher stores the installed client jar. */
    public static Path getBinDirectory() {
        return getAppDataDirectory().resolve("bin");
    }

    public static Path getClientJar() {
        return getBinDirectory().resolve("komm.jar");
    }

    public static Path getLogsDirectory() {
        return getAppDataDirectory().resolve("logs");
    }

    public static void main(String[] args) {
        // macOS is not a supported target (no packaging, no protocol registration) — bail out immediately.
        if (Platform.isMac()) {
            System.exit(1);
        }
        if (relaunchIfNewerLauncherStaged(args)) {
            return; // this process's job is done; the relaunched one takes over
        }
        initializeSystemProperties();
        initializeDirectories();
        // Off the critical path: the UI shouldn't wait on reg.exe.
        Thread.ofVirtual().start(ProtocolRegistrar::registerIfPossible);
        LauncherApp.appStart(args);
    }

    /**
     * Linux/AppImage only: {@code UpdateManager} stages a newer launcher jar
     * directly at {@code bin/komm-launcher.jar} when one's available (safe to
     * overwrite even while a process has the old one open — see its javadoc).
     * If that file exists and isn't the jar this process is already running
     * from, relaunch into it — on the same bundled runtime, just newer code —
     * before doing anything else, so the update takes effect this run instead
     * of waiting for the next one.
     *
     * @return true if a relaunch was spawned (the caller should return immediately)
     */
    private static boolean relaunchIfNewerLauncherStaged(String[] args) {
        if (System.getenv("APPIMAGE") == null) return false; // only meaningful inside a mounted AppImage
        try {
            Path staged = getBinDirectory().resolve("komm-launcher.jar");
            if (!Files.exists(staged)) return false;

            Path running = currentJarPath();
            if (running == null || running.equals(staged)) return false; // dev run, or already running from it

            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-cp");
            command.add(staged.toAbsolutePath().toString());
            command.add(Launcher.class.getName());
            command.addAll(Arrays.asList(args));

            // Blocking here is fine — this runs before any UI exists. The mount
            // this process (and the runtime it's using) lives inside must stay
            // up until the relaunched process, and everything it later spawns
            // (up to and including the client), is fully done — same reasoning
            // as UpdateManager's own AppImage mount guard, just one hop earlier.
            Process process = new ProcessBuilder(command).inheritIO().start();
            int exitCode = process.waitFor();
            System.exit(exitCode);
            return true; // unreachable, but keeps the method's intent clear
        } catch (Exception e) {
            return false; // fall through and run this (older) jar as normal
        }
    }

    /** The jar this process is currently running from, or null in a dev run
     *  (classes directory instead of a jar) or if it can't be determined. */
    private static Path currentJarPath() {
        try {
            var source = Launcher.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return null;
            Path location = Path.of(source.getLocation().toURI());
            return Files.isRegularFile(location) ? location : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void initializeSystemProperties() {
        System.setProperty("slf4j.provider", "ch.qos.logback.classic.spi.LogbackServiceProvider");
        System.setProperty("komm.logDir", getLogsDirectory().toAbsolutePath().toString());
    }

    private static void initializeDirectories() {
        createDirectory(getAppDataDirectory());
        createDirectory(getBinDirectory());
        createDirectory(getLogsDirectory());
    }

    private static void createDirectory(Path path) {
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
        } catch (IOException ignored) {
        }
    }
}
