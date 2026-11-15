package komm.ui.theme;

import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Window;
import komm.utils.UserSettings;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Owns the scene stylesheet that carries the user's accent and light/dark mode.
 * <p>
 * The user-agent sheet (stock theme + komm-theme.css) holds the dark palette. On top of it every
 * window's scene gets ONE generated {@code .root{...}} sheet: in light mode the neutrals from
 * {@code komm-light.css} followed by the light accent override, in dark mode only the dark accent
 * override. Author-sheet looked-up colours win over the UA sheet's, so swapping that one URL
 * re-themes everything live.
 * <p>
 * Accent is a per-user setting ({@link UserSettings}); the mode is per device
 * ({@code config/theme-mode}) so the login page already opens in the right mode.
 */
@Slf4j
public class ThemeManager {

    private static final ThemeManager INSTANCE = new ThemeManager();

    /** What a themed node needs to know to re-resolve colours it caches. */
    public record Appearance(AppTheme theme, boolean dark) {}

    @Getter
    private AppTheme currentTheme = AppTheme.PURPLE;
    @Getter
    private ThemeMode mode = readSavedMode();
    private String currentThemeUrl = null;

    private final Map<String, String> themeUrls = new HashMap<>();
    private String lightNeutrals;

    /** Fires whenever the accent theme changes. */
    private final ObjectProperty<AppTheme> themeProperty = new SimpleObjectProperty<>(currentTheme);
    /** Effective dark mode (resolves {@link ThemeMode#SYSTEM}). */
    private final ReadOnlyBooleanWrapper dark = new ReadOnlyBooleanWrapper(mode != ThemeMode.LIGHT);
    /** Fires on accent OR mode changes, so already-mounted nodes that cache a resolved CSS colour
     *  (canvases, shape strokes) can refresh without waiting for a scene re-attach. */
    private final ObjectProperty<Appearance> appearance = new SimpleObjectProperty<>(new Appearance(currentTheme, dark.get()));

    private ThemeManager() {}

    public static ThemeManager get() {
        return INSTANCE;
    }

    /** Observable current accent. */
    public ReadOnlyObjectProperty<AppTheme> themeProperty() {
        return themeProperty;
    }

    /** Observable accent + effective mode: listen to this to re-resolve cached colours. */
    public ReadOnlyObjectProperty<Appearance> appearanceProperty() {
        return appearance;
    }

    public ReadOnlyBooleanProperty darkProperty() {
        return dark.getReadOnlyProperty();
    }

    /** Effective mode. Safe to call before {@link #init()} (SYSTEM then needs the FX toolkit running). */
    public boolean isDark() {
        if (mode == ThemeMode.SYSTEM) return systemIsDark();
        return mode == ThemeMode.DARK;
    }

    /** The app window background (-color-bg-default) for the current mode. */
    public Color windowBackground() {
        return isDark() ? Color.web("#161616") : Color.web("#f5f6f8");
    }

    /**
     * Must be called once on the FX thread after the primary stage is shown. Registers a listener
     * so every future window (popups, tooltips, extra stages) receives the current theme sheet,
     * and follows the OS colour scheme while the mode is SYSTEM.
     */
    public void init() {
        dark.set(isDark());
        currentThemeUrl = urlFor(currentTheme, dark.get());

        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    for (Window w : change.getAddedSubList()) {
                        applyThemeToWindow(w);
                    }
                }
            }
        });

        try {
            Platform.getPreferences().colorSchemeProperty().addListener((obs, o, n) -> {
                if (mode == ThemeMode.SYSTEM) refresh();
            });
        } catch (RuntimeException e) {
            log.debug("Platform colour scheme not observable: {}", e.toString());
        }
    }

    /** Apply an accent immediately to all open windows and persist it to UserSettings. */
    public void apply(AppTheme theme) {
        currentTheme = theme;
        themeProperty.set(theme);
        UserSettings.getInstance().setTheme(theme);
        refresh();
        log.debug("Theme applied: {}", theme.getDisplayName());
    }

    /** Switch light/dark/system immediately and persist it for this device. */
    public void applyMode(ThemeMode newMode) {
        mode = newMode;
        saveMode(newMode);
        refresh();
        log.debug("Theme mode applied: {}", newMode);
    }

    /** Load and apply the accent saved in UserSettings (called after login). */
    public void applyFromSettings() {
        currentTheme = UserSettings.getInstance().getTheme();
        themeProperty.set(currentTheme);
        refresh();
    }

    private void refresh() {
        boolean d = isDark();
        mirrorAccentForLauncher(currentTheme);
        Runnable apply = () -> {
            dark.set(d);
            String oldUrl = currentThemeUrl;
            currentThemeUrl = urlFor(currentTheme, d);
            for (Window w : new ArrayList<>(Window.getWindows())) {
                Scene s = w.getScene();
                if (s != null) {
                    swapStylesheet(s, oldUrl, currentThemeUrl);
                    s.setFill(windowBackground());
                }
            }
            appearance.set(new Appearance(currentTheme, d));
        };
        if (Platform.isFxApplicationThread()) apply.run();
        else Platform.runLater(apply);
    }

    private static boolean systemIsDark() {
        try {
            return Platform.getPreferences().getColorScheme() != ColorScheme.LIGHT;
        } catch (RuntimeException e) {
            return true;    // toolkit not up yet / unsupported: Komm's default look
        }
    }

    // ── persistence ───────────────────────────────────────────────────────────

    private static Path modeFile() {
        return komm.Launcher.getConfigDirectory().resolve("theme-mode");
    }

    private static ThemeMode readSavedMode() {
        try {
            Path file = modeFile();
            if (Files.isRegularFile(file)) return ThemeMode.fromSetting(Files.readString(file));
        } catch (IOException | RuntimeException e) {
            log.debug("Could not read theme mode: {}", e.toString());
        }
        return ThemeMode.DARK;
    }

    private static void saveMode(ThemeMode mode) {
        Thread.ofVirtual().start(() -> {
            try {
                Files.writeString(modeFile(), mode.name());
            } catch (IOException e) {
                log.warn("Could not save theme mode: {}", e.toString());
            }
        });
    }

    /**
     * Mirrors the active accent into {@code config/accent} for the launcher, which shows its
     * window before anyone is logged in (accent itself is a per-user setting). Best-effort.
     */
    private static void mirrorAccentForLauncher(AppTheme theme) {
        Thread.ofVirtual().start(() -> {
            try {
                Path file = komm.Launcher.getConfigDirectory().resolve("accent");
                if (Files.isRegularFile(file) && theme.name().equals(Files.readString(file).trim())) return;
                Files.writeString(file, theme.name());
            } catch (IOException e) {
                log.debug("Could not mirror accent for the launcher: {}", e.toString());
            }
        });
    }

    // ── stylesheets ───────────────────────────────────────────────────────────

    private void applyThemeToWindow(Window w) {
        String url = currentThemeUrl;
        if (url == null) return;
        Scene s = w.getScene();
        if (s != null) {
            swapStylesheet(s, null, url);
        } else {
            w.sceneProperty().addListener((obs, old, newScene) -> {
                if (newScene != null) swapStylesheet(newScene, null, currentThemeUrl);
            });
        }
    }

    private static void swapStylesheet(Scene scene, String remove, String add) {
        if (remove != null && !remove.equals(add)) scene.getStylesheets().remove(remove);
        if (add != null && !scene.getStylesheets().contains(add)) {
            scene.getStylesheets().add(add);
        }
    }

    /** Temp sheet for one accent × mode, written on first use and cached. */
    private String urlFor(AppTheme theme, boolean dark) {
        String key = theme.name() + (dark ? ":dark" : ":light");
        if (!dark && lightNeutrals == null) lightNeutrals = loadLightNeutrals();
        return themeUrls.computeIfAbsent(key, k -> {
            String css = (dark ? "" : lightNeutrals + "\n") + ".root{" + theme.getCssOverride(dark) + "}";
            try {
                Path file = Files.createTempFile("komm-theme-" + theme.name().toLowerCase()
                        + (dark ? "-dark-" : "-light-"), ".css");
                file.toFile().deleteOnExit();
                Files.writeString(file, css);
                return file.toUri().toString();
            } catch (IOException e) {
                log.error("Failed to write theme CSS for {}", k, e);
                return null;
            }
        });
    }

    private static String loadLightNeutrals() {
        try (InputStream in = ThemeManager.class.getResourceAsStream("/komm-light.css")) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to read komm-light.css", e);
        }
        return "";
    }
}
