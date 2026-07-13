package komm.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;

import komm.Launcher;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AppConfig {
    
    private static AppConfig instance;
    private Properties properties;
    private final Path propertiesFile;
    
    private AppConfig() {
        this.propertiesFile = Launcher.getPropertiesFile();
        this.properties = new Properties();
        loadProperties();
    }
    
    public static AppConfig getInstance() {
        if (instance == null) {
            synchronized (AppConfig.class) {
                if (instance == null) {
                    instance = new AppConfig();
                }
            }
        }
        return instance;
    }

    private void loadProperties() {
        try (InputStream input = AppConfig.class.getClassLoader().getResourceAsStream("app.properties")) {
            if (input != null) {
                properties.load(input);
                log.debug("Loaded properties from classpath: app.properties");
            } else {
                log.error("app.properties not found in classpath");
            }
        } catch (IOException e) {
            log.error("Error loading properties: {}", e.getMessage());
        }
    }
    
    public String getProperty(String key) {
        return properties.getProperty(key);
    }

    public void setProperty(String key, String value) {
        properties.setProperty(key, value);
    }
    
    public String getApiUrl() {
        // A -Dapi.url=... override wins, so testers can point at any hub without rebuilding.
        String override = systemProperty("api.url");
        if (override != null) return override.replaceAll("/+$", "");
        return getProperty("api.url");
    }

    public String getWebSocketUrl() {
        String override = systemProperty("websocket.url");
        if (override != null) return override;
        // With only -Dapi.url given, derive the hub's WebSocket endpoint from it
        // (http -> ws, https -> wss, + /ws) so a single flag repoints the whole client.
        String api = systemProperty("api.url");
        if (api != null) return api.replaceFirst("^http", "ws").replaceAll("/+$", "") + "/ws";
        return getProperty("websocket.url");
    }

    /** The given system property, or null when absent or blank. */
    private static String systemProperty(String key) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? null : value.trim();
    }
    
    public void setApiUrl(String url) {
        setProperty("api.url", url);
    }
    
    public void setWebSocketUrl(String url) {
        setProperty("websocket.url", url);
    }
    
    public void reload() {
        loadProperties();
    }
}
