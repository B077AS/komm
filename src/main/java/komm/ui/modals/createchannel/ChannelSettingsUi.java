package komm.ui.modals.createchannel;

import javafx.scene.control.Label;
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

    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.charAt(0) + s.substring(1).toLowerCase();
    }

    public static void showSaveError(Throwable ex) {
        new CustomNotification("Error", HttpStatusException.extractMessage(ex),
                new FontIcon(MaterialDesignC.CLOSE)).showNotification();
    }
}
