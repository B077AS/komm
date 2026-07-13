package komm.ui.utils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;

/**
 * Colors {@link FontIcon}s with arbitrary colors (hex literals or theme
 * variables like {@code -color-accent-fg}) by injecting a tiny generated
 * stylesheet as a {@code data:text/css;base64,...} URI on the icon's parent.
 *
 * <p>Plain {@code setStyle("-fx-icon-color: ...")} can't resolve theme
 * variables on a {@code FontIcon}, so every dynamically-colored icon in the
 * app needs to go through a real stylesheet instead — this class is the one
 * place that does it.
 */
public final class IconColorUtil {

    /** Fallback color used for role icons when no color is set. */
    public static final String DEFAULT_ROLE_COLOR = "#99AAB5";

    private static final Map<String, String> URI_CACHE = new ConcurrentHashMap<>();

    private IconColorUtil() {}

    /** Creates a new icon and colors it. */
    public static FontIcon colored(Ikon ikon, String color, int size) {
        FontIcon icon = new FontIcon(ikon);
        apply(icon, color, size);
        return icon;
    }

    /** Creates a {@code MaterialDesignA.ACCOUNT_CIRCLE} icon colored with a role's color, falling back to {@link #DEFAULT_ROLE_COLOR} when blank. */
    public static FontIcon roleColorIcon(String hexColor, int size) {
        return colored(MaterialDesignA.ACCOUNT_CIRCLE, safeRoleColor(hexColor), size);
    }

    /** Returns {@code hexColor}, or {@link #DEFAULT_ROLE_COLOR} when it's null/blank. */
    public static String safeRoleColor(String hexColor) {
        return (hexColor != null && !hexColor.isBlank()) ? hexColor : DEFAULT_ROLE_COLOR;
    }

    /**
     * Colors an existing icon by attaching a generated stylesheet to its
     * parent as soon as it's attached to the scene graph.
     */
    public static void apply(FontIcon icon, String color, int size) {
        String key = color + ":" + size;
        String cls = "dyn-icon-" + Integer.toHexString(key.hashCode());
        String uri = URI_CACHE.computeIfAbsent(key, k -> {
            String css = "." + cls + "{-fx-icon-color:" + color + ";-fx-icon-size:" + size + "px;}";
            return "data:text/css;base64," + Base64.getEncoder().encodeToString(css.getBytes(StandardCharsets.UTF_8));
        });
        icon.getStyleClass().add(cls);
        icon.parentProperty().addListener((obs, old, parent) -> {
            if (parent != null && !parent.getStylesheets().contains(uri))
                parent.getStylesheets().add(uri);
        });
    }
}
