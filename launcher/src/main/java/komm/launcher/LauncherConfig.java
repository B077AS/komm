package komm.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import lombok.extern.slf4j.Slf4j;

/**
 * Reads the bundled {@code app.properties}, mirroring the komm client's
 * {@code AppConfig}. The only value the launcher needs from it is its own
 * {@code launcher.version} — update checks now go straight to GitHub (see
 * {@code update.UpdateManager}) rather than through a configured hub.
 */
@Slf4j
public class LauncherConfig {

    private static LauncherConfig instance;
    private final Properties properties = new Properties();

    private LauncherConfig() {
        loadProperties();
    }

    public static LauncherConfig getInstance() {
        if (instance == null) {
            synchronized (LauncherConfig.class) {
                if (instance == null) {
                    instance = new LauncherConfig();
                }
            }
        }
        return instance;
    }

    private void loadProperties() {
        try (InputStream input = LauncherConfig.class.getClassLoader().getResourceAsStream("app.properties")) {
            if (input != null) {
                properties.load(input);
                log.debug("Loaded launcher properties from classpath: app.properties");
            } else {
                log.error("app.properties not found in classpath");
            }
        } catch (IOException e) {
            log.error("Error loading properties: {}", e.getMessage());
        }
    }

    /** This launcher's own version, forwarded to the client as -Dlauncher.version. */
    public String getLauncherVersion() {
        return properties.getProperty("launcher.version");
    }
}
