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
        parseDeepLink(args);
        tryRegisterWindowsProtocol();
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
    }

    private static void initializeSystemProperties() {
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