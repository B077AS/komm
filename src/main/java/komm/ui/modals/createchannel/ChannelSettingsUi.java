package komm.ui.modals.createchannel;

import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import komm.api.HttpStatusException;
import komm.ui.customnodes.CustomNotification;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

/** Shared UI helpers used across the channel-settings tabs. */
public final class ChannelSettingsUi {

    private ChannelSettingsUi() {}

    public static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle;");
        return l;
    }

    /**
     * Rounded accent tile showing the channel's current icon (the picked one, else the voice/text
     * default), following {@code ctx} live. {@code sizeClass} ("large" / "small") sets the glyph size in CSS.
     */
    public static StackPane channelTile(ChannelSettingsContext ctx, double size, String sizeClass) {
        FontIcon glyph = new FontIcon(ctx.shownIcon().getValue());
        ctx.shownIcon().addListener((o, a, b) -> glyph.setIconCode(b));
        StackPane tile = new StackPane(glyph);
        tile.getStyleClass().addAll("channel-tile", sizeClass);
        tile.setMinSize(size, size);
        tile.setPrefSize(size, size);
        tile.setMaxSize(size, size);
        return tile;
    }

    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.charAt(0) + s.substring(1).toLowerCase();
    }

    public static void showSaveError(Throwable ex) {
        new CustomNotification("Error", HttpStatusException.extractMessage(ex),
                new FontIcon(MaterialDesignC.CLOSE)).showNotification();
    }
}
