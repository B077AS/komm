package komm.ui.modals.usersettings;

import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;

/** Shared UI helpers used across the user-settings tabs. */
public final class UserSettingsUi {

    private UserSettingsUi() {}

    public static ScrollPane wrapScroll(Region content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        return scroll;
    }

    public static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: -color-fg-muted;");
        return l;
    }

    public static Region spacer(double height) {
        Region r = new Region();
        r.setMinHeight(height);
        r.setMaxHeight(height);
        return r;
    }
}
