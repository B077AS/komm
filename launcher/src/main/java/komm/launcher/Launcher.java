package komm.launcher;

import com.sun.jna.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point for the Komm launcher: the small window that runs before the client.
 *
 * <p>The jpack stub that started it (Komm.exe / the AppImage) owns the installation. The launcher asks
 * the stub for updates, shows the download, and hands over to the client entry; see
 * {@code update.UpdateManager}. It resolves the same per-OS app data directory as the client
 * ({@code %APPDATA%/Komm} on Windows) so logs and the accent colour are shared, and sets the logging
 * system properties logback.xml reads.
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

    public static Path getLogsDirectory() {
        return getAppDataDirectory().resolve("logs");
    }

    public static void main(String[] args) {
        // macOS is not a supported target (no packaging, no protocol registration) - bail out immediately.
        if (Platform.isMac()) {
            System.exit(1);
        }
        initializeSystemProperties();
        initializeDirectories();
        // Off the critical path: the UI shouldn't wait on reg.exe.
        Thread.ofVirtual().start(ProtocolRegistrar::registerIfPossible);
        LauncherApp.appStart(args);
    }

    private static void initializeSystemProperties() {
        System.setProperty("slf4j.provider", "ch.qos.logback.classic.spi.LogbackServiceProvider");
        System.setProperty("komm.logDir", getLogsDirectory().toAbsolutePath().toString());
    }

    private static void initializeDirectories() {
        createDirectory(getAppDataDirectory());
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
