package komm;

import com.sun.jna.Platform;
import lombok.Getter;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Launcher {

    private static final String APP_NAME = "Komm";
    private static final String PROPERTIES_FILE = "komm.conf";

    @Getter
    private static String pendingInviteCode;

    public static void clearPendingInviteCode() {
        pendingInviteCode = null;
    }

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

    public static Path getDataDirectory() {
        return getAppDataDirectory().resolve("data");
    }

    public static Path getEmojiDirectory() {
        return getDataDirectory().resolve("emoji");
    }

    /** Personal soundboard sounds + index; server-wide downloads cached under {@code server/}. */
    public static Path getSoundboardDirectory() {
        return getDataDirectory().resolve("soundboards");
    }

    /** Bundled notification sounds, extracted from the jar at startup (JavaFX Media can't read jar: URLs). */
    public static Path getSoundsDirectory() {
        return getDataDirectory().resolve("sounds");
    }


    public static Path getConfigDirectory() {
        return getAppDataDirectory().resolve("config");
    }

    public static Path getLogsDirectory() {
        return getAppDataDirectory().resolve("logs");
    }

    public static Path getCredentialsFile() {
        return getConfigDirectory().resolve("credentials");
    }

    public static Path getPropertiesFile() {
        return getConfigDirectory().resolve(PROPERTIES_FILE);
    }

    public static void main(String[] args) {
        // macOS is not a supported target — bail out immediately.
        if (Platform.isMac()) {
            System.exit(1);
        }
        /*System.setProperty("prism.lcdtext", "true");
        System.setProperty("prism.text", "t2k");*/
        initializeSystemProperties();
        initializeDirectories();
        // webrtc-java 0.20+ routes exceptions thrown inside native callbacks (sinks, observers,
        // capture callbacks) to the thread's uncaught handler — log them or they vanish.
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                org.slf4j.LoggerFactory.getLogger("komm.Uncaught")
                        .error("Uncaught exception on thread {}", t.getName(), e));
        parseDeepLink(args);
        tryRegisterWindowsProtocol();
        Thread.ofVirtual().start(komm.update.AppsFeaturesVersionUpdater::updateIfInstalled);
        App.appStart(args);
    }

    private static void parseDeepLink(String[] args) {
        if (args == null || args.length == 0) return;
        String arg = args[0];
        if (!arg.startsWith("komm://")) return;
        // komm://invite/CODE
        try {
            URI uri = new URI(arg);
            String path = uri.getPath(); // e.g. /CODE
            String host = uri.getHost(); // e.g. "invite"
            if ("invite".equals(host) && path != null && path.length() > 1) {
                pendingInviteCode = path.substring(1); // strip leading "/"
            }
        } catch (Exception ignored) {}
    }

    static void tryRegisterWindowsProtocol() {
        if (!Platform.isWindows()) return;
        // Started by the jpack stub: the launcher registers komm:// to the stub itself (JPACK_EXE), so
        // links go through the update check and the client gets its JVM options. Overwriting that with
        // a bare `javaw -jar` here would bypass both.
        if (System.getenv("JPACK_EXE") != null) return;
        try {
            Path jarPath = Path.of(Launcher.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!jarPath.toString().endsWith(".jar")) return; // dev mode — skip

            String cmd = "\"javaw.exe\" -jar \"" + jarPath.toAbsolutePath() + "\" \"%1\"";
            runReg("add", "HKCU\\SOFTWARE\\Classes\\komm", "/ve", "/d", "URL:Komm Protocol", "/f");
            runReg("add", "HKCU\\SOFTWARE\\Classes\\komm", "/v", "URL Protocol", "/d", "", "/f");
            runReg("add", "HKCU\\SOFTWARE\\Classes\\komm\\shell\\open\\command", "/ve", "/d", cmd, "/f");
        } catch (Exception ignored) {}
    }

    private static void runReg(String... args) throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "reg.exe";
        System.arraycopy(args, 0, cmd, 1, args.length);
        new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
                .waitFor();
    }

    private static void initializeDirectories() {
        createDirectory(getAppDataDirectory());
        createDirectory(getEmojiDirectory());
        createDirectory(getDataDirectory());
        createDirectory(getSoundboardDirectory());
        createDirectory(getSoundboardDirectory().resolve("server"));
        createDirectory(getSoundboardDirectory().resolve("personal"));
        createDirectory(getSoundsDirectory());
        createDirectory(getConfigDirectory());
        createDirectory(getLogsDirectory());
        createDirectory(getNativesDirectory());
        deleteStaleNatives();
    }

    /**
     * Where JNativeHook unpacks its native library. By default it unpacks next to the jar, which is
     * read-only inside an AppImage's mount (and would be wiped by every update swap anywhere else),
     * so it is pointed at the app data directory instead ({@code jnativehook.lib.path}).
     */
    public static Path getNativesDirectory() {
        return getDataDirectory().resolve("natives");
    }

    /**
     * JNativeHook names its library after the jar's Implementation-Version, so every update strands
     * the previous version's copy. Best-effort sweep of everything but the current one; a file still
     * loaded by another running Komm just stays.
     */
    private static void deleteStaleNatives() {
        String version = Launcher.class.getPackage().getImplementationVersion();
        if (version == null || version.isBlank()) return; // IDE run: no versioned jar, nothing is extracted
        try (var files = Files.list(getNativesDirectory())) {
            files.filter(f -> f.getFileName().toString().startsWith("JNativeHook-")
                            && !f.getFileName().toString().startsWith("JNativeHook-" + version + "."))
                    .forEach(f -> {
                        try {
                            Files.deleteIfExists(f);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private static void initializeSystemProperties() {
        System.setProperty("jnativehook.lib.path", getNativesDirectory().toAbsolutePath().toString());
        System.setProperty("slf4j.provider", "ch.qos.logback.classic.spi.LogbackServiceProvider");
        System.setProperty("komm.logDir", getLogsDirectory().toAbsolutePath().toString());
        System.setProperty("komm.hsErrFile", getLogsDirectory().resolve("hs_err_pid%p.log").toAbsolutePath().toString());
    }

    private static void createDirectory(Path path) {
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
        } catch (IOException e) {
        }
    }
}