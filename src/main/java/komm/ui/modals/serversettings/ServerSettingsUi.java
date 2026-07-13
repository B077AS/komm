package komm.ui.modals.serversettings;

import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import komm.model.dto.summary.ServerSummary;

/** Small shared UI helpers used across the server-settings tabs. */
public final class ServerSettingsUi {

    private ServerSettingsUi() {}

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

    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.charAt(0) + s.substring(1).toLowerCase();
    }

    public static int roleRank(ServerSummary.Role role) {
        return switch (role) {
            case MEMBER -> 0;
            case MODERATOR -> 1;
            case ADMIN -> 2;
            case OWNER -> 3;
        };
    }
}
