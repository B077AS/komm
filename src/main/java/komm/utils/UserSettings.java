package komm.utils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import komm.Launcher;
import komm.ui.theme.AppTheme;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class UserSettings {

    private Path settingsPath;
    private Properties properties;
    private static UserSettings instance;
    private static final String INPUT_DEVICE_KEY      = "audio.input.device";
    private static final String OUTPUT_DEVICE_KEY     = "audio.output.device";
    private static final String VAD_ENABLED_KEY       = "audio.vad.enabled";
    private static final String VAD_SENSITIVITY_KEY   = "audio.vad.sensitivity";   // Silero probability threshold 0.0–1.0
    private static final String VAD_NOISE_GATE_KEY    = "audio.vad.noisegate.db";  // dBFS −60…−20, default −40
    private static final String NOISE_SUPPRESSION_KEY = "audio.noise.suppression";
    private static final String ECHO_CANCELLATION_KEY = "audio.echo.cancellation";
    private static final String AGC_ENABLED_KEY        = "audio.agc.enabled";
    private static final String SOUNDBOARD_VOLUME_KEY  = "audio.soundboard.volume";   // 0.0–1.0, default 1.0
    private static final String USER_VOLUME_KEY_PREFIX   = "audio.user.volume.";        // 0.0–2.0, default 1.0
    private static final String STREAM_VOLUME_KEY_PREFIX = "audio.stream.volume.";       // 0.0–2.0, default 1.0
    private static final String SERVER_SPLIT_KEY_PREFIX = "ui.server.split.";           // pixel width of channel panel
    private static final String DM_SPLIT_KEY            = "ui.dm.split";                // pixel width of DM sidebar, default 300px
    private static final String NOTIFICATIONS_DM_KEY    = "notifications.dm.enabled";   // default true
    private static final String THEME_KEY               = "ui.theme";
    private static final String SCREENSHARE_RESOLUTION_KEY = "screenshare.quality.resolution";
    private static final String SCREENSHARE_FPS_KEY        = "screenshare.quality.fps";
    
    /**
     * {@code null} path means "pre-login defaults only" — reads return defaults, saves are no-ops.
     */
    private UserSettings(Path path) {
        properties = new Properties();
        settingsPath = path;
        if (path != null) load();
    }

    /**
     * Called once after login. Replaces the singleton with a per-user instance
     * backed by {@code config/usersettings-<uuid>.properties}.
     * Any settings read before this (e.g. during WebRTC engine init) used defaults
     * and will now be re-read from the user's file.
     */
    public static void init(UUID userId) {
        Path path = Launcher.getConfigDirectory()
                .resolve("usersettings-" + userId + ".properties");
        instance = new UserSettings(path);
        log.debug("UserSettings initialised for user {}", userId);
    }

    /**
     * Returns the current instance. Before {@link #init} is called (e.g. during WebRTC
     * engine startup) a defaults-only instance is returned automatically — settings reads
     * return defaults and saves are silently skipped until a user is logged in.
     */
    public static UserSettings getInstance() {
        if (instance == null) {
            log.debug("UserSettings accessed before login — using in-memory defaults");
            instance = new UserSettings(null);
        }
        return instance;
    }
    
    private void load() {
        try {
            if (Files.exists(settingsPath)) {
                try (InputStream input = new FileInputStream(settingsPath.toFile())) {
                    properties.load(input);
                    log.debug("User settings loaded from: {}", settingsPath);
                }
            } else {
                log.debug("No existing settings file found, using defaults");
            }
        } catch (IOException e) {
            log.error("Error loading user settings: {}", e.getMessage());
            //e.printStackTrace();
        }
    }

    public void save() {
        if (settingsPath == null) {
            log.debug("UserSettings.save() skipped — no user logged in yet");
            return;
        }
        try {
            Files.createDirectories(settingsPath.getParent());
            try (OutputStream output = new FileOutputStream(settingsPath.toFile())) {
                properties.store(output, "Komm User Settings");
                log.debug("User settings saved to: {}", settingsPath);
            }
        } catch (IOException e) {
            log.error("Error saving user settings: {}", e.getMessage());
            //e.printStackTrace();
        }
    }
    
    // Audio device settings
    public void setInputDevice(String deviceName) {
        if (deviceName != null) {
            String current = properties.getProperty(INPUT_DEVICE_KEY);
            if (!deviceName.equals(current)) {
                properties.setProperty(INPUT_DEVICE_KEY, deviceName);
                save();
            }
        }
    }
    
    public String getInputDevice() {
        return properties.getProperty(INPUT_DEVICE_KEY, "Default");
    }
    
    public void setOutputDevice(String deviceName) {
        if (deviceName != null) {
            String current = properties.getProperty(OUTPUT_DEVICE_KEY);
            if (!deviceName.equals(current)) {
                properties.setProperty(OUTPUT_DEVICE_KEY, deviceName);
                save();
            }
        }
    }
    
    public String getOutputDevice() {
        return properties.getProperty(OUTPUT_DEVICE_KEY, "Default");
    }
    
    // VAD settings
    public void setVadEnabled(boolean enabled) {
        String newValue = String.valueOf(enabled);
        String current = properties.getProperty(VAD_ENABLED_KEY);
        if (!newValue.equals(current)) {
            properties.setProperty(VAD_ENABLED_KEY, newValue);
            save();
        }
    }
    
    public boolean isVadEnabled() {
        return Boolean.parseBoolean(properties.getProperty(VAD_ENABLED_KEY, "true"));
    }
    
    // VAD noise gate — minimum post-AGC input level (dBFS) required to open the
    // transmit gate. Onset-only click backstop; never cuts speech in progress.
    // Default −40 dBFS: well below quiet speech, above desk-conducted mouse clicks.
    public float getVadNoiseGateDb() {
        return Float.parseFloat(properties.getProperty(VAD_NOISE_GATE_KEY, "-40.0"));
    }

    public void setVadNoiseGateDb(float db) {
        String v = String.valueOf(db);
        if (!v.equals(properties.getProperty(VAD_NOISE_GATE_KEY))) {
            properties.setProperty(VAD_NOISE_GATE_KEY, v);
            save();
        }
    }

    // VAD sensitivity — Silero speech probability threshold in [0.0, 1.0].
    // Lower = more sensitive (catches whispers), higher = stricter (loud speech only).
    // Default 0.5 matches Silero's recommended starting point.
    public float getVadSensitivity() {
        return Float.parseFloat(properties.getProperty(VAD_SENSITIVITY_KEY, "0.5"));
    }

    public void setVadSensitivity(float value) {
        String v = String.valueOf(value);
        if (!v.equals(properties.getProperty(VAD_SENSITIVITY_KEY))) {
            properties.setProperty(VAD_SENSITIVITY_KEY, v);
            save();
        }
    }

    // Audio processing
    public boolean isNoiseSuppression() {
        return Boolean.parseBoolean(properties.getProperty(NOISE_SUPPRESSION_KEY, "true"));
    }

    public void setNoiseSuppression(boolean enabled) {
        String v = String.valueOf(enabled);
        if (!v.equals(properties.getProperty(NOISE_SUPPRESSION_KEY))) {
            properties.setProperty(NOISE_SUPPRESSION_KEY, v);
            save();
        }
    }

    public boolean isEchoCancellation() {
        return Boolean.parseBoolean(properties.getProperty(ECHO_CANCELLATION_KEY, "true"));
    }

    public void setEchoCancellation(boolean enabled) {
        String v = String.valueOf(enabled);
        if (!v.equals(properties.getProperty(ECHO_CANCELLATION_KEY))) {
            properties.setProperty(ECHO_CANCELLATION_KEY, v);
            save();
        }
    }

    public boolean isAgcEnabled() {
        return Boolean.parseBoolean(properties.getProperty(AGC_ENABLED_KEY, "true"));
    }

    public void setAgcEnabled(boolean enabled) {
        String v = String.valueOf(enabled);
        if (!v.equals(properties.getProperty(AGC_ENABLED_KEY))) {
            properties.setProperty(AGC_ENABLED_KEY, v);
            save();
        }
    }

    // Soundboard volume — applied to the local monitor.
    // 0.0 = silent, 1.0 = full volume.
    public float getSoundboardVolume() {
        return Float.parseFloat(properties.getProperty(SOUNDBOARD_VOLUME_KEY, "1.0"));
    }

    public void setSoundboardVolume(float value) {
        String v = String.valueOf(value);
        if (!v.equals(properties.getProperty(SOUNDBOARD_VOLUME_KEY))) {
            properties.setProperty(SOUNDBOARD_VOLUME_KEY, v);
            save();
        }
    }

    // Per-user voice volume — listener-local only, 0.0 = silent, 2.0 = 200%.
    public float getUserVolume(UUID userId) {
        return Float.parseFloat(properties.getProperty(USER_VOLUME_KEY_PREFIX + userId, "1.0"));
    }

    public void setUserVolume(UUID userId, float volume) {
        String key = USER_VOLUME_KEY_PREFIX + userId;
        String v = String.valueOf(volume);
        if (!v.equals(properties.getProperty(key))) {
            properties.setProperty(key, v);
            save();
        }
    }

    // Per-stream audio volume — listener-local only, 0.0 = silent, 2.0 = 200%.
    public float getStreamVolume(UUID userId) {
        return Float.parseFloat(properties.getProperty(STREAM_VOLUME_KEY_PREFIX + userId, "1.0"));
    }

    public void setStreamVolume(UUID userId, float volume) {
        String key = STREAM_VOLUME_KEY_PREFIX + userId;
        String v = String.valueOf(volume);
        if (!v.equals(properties.getProperty(key))) {
            properties.setProperty(key, v);
            save();
        }
    }

    // Per-server channel panel width
    public double getServerSplitPosition(UUID serverId, Integer serverDefault) {
        String val = properties.getProperty(SERVER_SPLIT_KEY_PREFIX + serverId);
        if (val != null) return Double.parseDouble(val);
        if (serverDefault != null && serverDefault > 0) return serverDefault.doubleValue();
        return 240.0;
    }

    public void setServerSplitPosition(UUID serverId, double pixelWidth) {
        String key = SERVER_SPLIT_KEY_PREFIX + serverId;
        String v = String.valueOf(pixelWidth);
        if (!v.equals(properties.getProperty(key))) {
            properties.setProperty(key, v);
            save();
        }
    }

    public double getDmSplitPosition() {
        return Double.parseDouble(properties.getProperty(DM_SPLIT_KEY, "300.0"));
    }

    public void setDmSplitPosition(double pixelWidth) {
        String v = String.valueOf(pixelWidth);
        if (!v.equals(properties.getProperty(DM_SPLIT_KEY))) {
            properties.setProperty(DM_SPLIT_KEY, v);
            save();
        }
    }

    // Notification settings
    public boolean isDmNotificationsEnabled() {
        return Boolean.parseBoolean(properties.getProperty(NOTIFICATIONS_DM_KEY, "true"));
    }

    public void setDmNotificationsEnabled(boolean enabled) {
        String v = String.valueOf(enabled);
        if (!v.equals(properties.getProperty(NOTIFICATIONS_DM_KEY))) {
            properties.setProperty(NOTIFICATIONS_DM_KEY, v);
            save();
        }
    }

    // Keybinding settings — stored as comma-separated JNativeHook VC_ key codes
    private static final String KEYBINDING_MUTE_KEYS   = "keybinding.mute.keys";
    private static final String KEYBINDING_DEAFEN_KEYS = "keybinding.deafen.keys";

    public int[] getKeybindingMuteKeys() {
        return parseKeys(properties.getProperty(KEYBINDING_MUTE_KEYS, ""));
    }

    public void setKeybindingMuteKeys(int[] keys) {
        setKeys(KEYBINDING_MUTE_KEYS, keys);
    }

    public int[] getKeybindingDeafenKeys() {
        return parseKeys(properties.getProperty(KEYBINDING_DEAFEN_KEYS, ""));
    }

    public void setKeybindingDeafenKeys(int[] keys) {
        setKeys(KEYBINDING_DEAFEN_KEYS, keys);
    }

    private static int[] parseKeys(String s) {
        if (s == null || s.isBlank()) return new int[0];
        try {
            String[] parts = s.split(",");
            int[] result = new int[parts.length];
            for (int i = 0; i < parts.length; i++) result[i] = Integer.parseInt(parts[i].trim());
            return result;
        } catch (NumberFormatException e) {
            return new int[0];
        }
    }

    private void setKeys(String key, int[] keys) {
        String v = (keys == null || keys.length == 0) ? "" :
                java.util.Arrays.stream(keys).mapToObj(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(","));
        if (!v.equals(properties.getProperty(key, ""))) {
            properties.setProperty(key, v);
            save();
        }
    }

    // Theme
    public AppTheme getTheme() {
        String saved = properties.getProperty(THEME_KEY, AppTheme.PURPLE.name());
        try {
            return AppTheme.valueOf(saved);
        } catch (IllegalArgumentException e) {
            return AppTheme.PURPLE;
        }
    }

    public void setTheme(AppTheme theme) {
        set(THEME_KEY, theme.name());
    }

    // Screen share quality
    public int getScreenShareResolution() {
        return Integer.parseInt(properties.getProperty(SCREENSHARE_RESOLUTION_KEY, "720"));
    }

    public void setScreenShareResolution(int height) {
        set(SCREENSHARE_RESOLUTION_KEY, String.valueOf(height));
    }

    public int getScreenShareFps() {
        return Integer.parseInt(properties.getProperty(SCREENSHARE_FPS_KEY, "30"));
    }

    public void setScreenShareFps(int fps) {
        set(SCREENSHARE_FPS_KEY, String.valueOf(fps));
    }

    // Generic getters/setters
    public String get(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }
    
    public void set(String key, String value) {
        String current = properties.getProperty(key);
        if (!value.equals(current)) {
            properties.setProperty(key, value);
            save();
        }
    }
}