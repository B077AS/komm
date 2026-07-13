package komm.ui.theme;

import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.stage.Window;
import komm.utils.UserSettings;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;

@Slf4j
public class ThemeManager {

    private static final ThemeManager INSTANCE = new ThemeManager();

    @Getter
    private AppTheme currentTheme = AppTheme.PURPLE;
    private String currentThemeUrl = null;

    private final Map<AppTheme, String> themeUrls = new EnumMap<>(AppTheme.class);

    /** Fires whenever the accent theme changes, so already-mounted nodes that cache a
     *  resolved CSS color (e.g. canvases, shape strokes) can refresh without waiting for
     *  a scene re-attach. */
    private final ObjectProperty<AppTheme> themeProperty = new SimpleObjectProperty<>(currentTheme);

    private ThemeManager() {}

    public static ThemeManager get() {
        return INSTANCE;
    }

    /** Observable current theme — add a listener to react to live theme changes. */
    public ReadOnlyObjectProperty<AppTheme> themeProperty() {
        return themeProperty;
    }

    /**
     * Must be called once on the FX thread after the primary stage is shown.
     * Pre-generates temp CSS files for every theme and registers a listener so
     * every future window (popups, tooltips, extra stages) automatically receives
     * the current theme stylesheet.
     */
    public void init() {
        generateTempCssFiles();

        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    for (Window w : change.getAddedSubList()) {
                        applyThemeToWindow(w);
                    }
                }
            }
        });
    }

    /** Apply a theme immediately to all open windows and persist it to UserSettings. */
    public void apply(AppTheme theme) {
        currentTheme = theme;
        applyToAllWindows(theme);
        themeProperty.set(theme);
        UserSettings.getInstance().setTheme(theme);
        log.debug("Theme applied: {}", theme.getDisplayName());
    }

    /** Load and apply the theme saved in UserSettings (called after login). */
    public void applyFromSettings() {
        AppTheme theme = UserSettings.getInstance().getTheme();
        currentTheme = theme;
        applyToAllWindows(theme);
        themeProperty.set(theme);
    }

    private void applyToAllWindows(AppTheme theme) {
        Runnable apply = () -> {
            String oldUrl = currentThemeUrl;
            currentThemeUrl = themeUrls.get(theme);
            for (Window w : new ArrayList<>(Window.getWindows())) {
                Scene s = w.getScene();
                if (s != null) swapStylesheet(s, oldUrl, currentThemeUrl);
            }
        };
        if (Platform.isFxApplicationThread()) apply.run();
        else Platform.runLater(apply);
    }

    private void applyThemeToWindow(Window w) {
        String url = currentThemeUrl;
        if (url == null) return;
        Scene s = w.getScene();
        if (s != null) {
            swapStylesheet(s, null, url);
        } else {
            w.sceneProperty().addListener((obs, old, newScene) -> {
                if (newScene != null) swapStylesheet(newScene, null, url);
            });
        }
    }

    private static void swapStylesheet(Scene scene, String remove, String add) {
        if (remove != null) scene.getStylesheets().remove(remove);
        if (add != null && !scene.getStylesheets().contains(add)) {
            scene.getStylesheets().add(add);
        }
    }

    private void generateTempCssFiles() {
        for (AppTheme theme : AppTheme.values()) {
            try {
                Path file = Files.createTempFile("komm-theme-" + theme.name().toLowerCase() + "-", ".css");
                file.toFile().deleteOnExit();
                Files.writeString(file, ".root{" + theme.getCssOverride() + "}");
                themeUrls.put(theme, file.toUri().toString());
            } catch (IOException e) {
                log.error("Failed to write theme CSS for {}", theme.getDisplayName(), e);
            }
        }
        currentThemeUrl = themeUrls.get(currentTheme);
    }
}
