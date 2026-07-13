package komm.ui.modals.installationsettings;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.shape.Circle;
import komm.model.dto.summary.InstallationSummary;

public final class InstallationSettingsUi {

    private InstallationSettingsUi() {}

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

    public static HBox buildProtocolBadge(String protocol) {
        boolean isTcp = "TCP".equalsIgnoreCase(protocol);
        String bgColor = isTcp ? "-color-accent-subtle" : "-color-warning-subtle";
        String fgColor = isTcp ? "-color-accent-fg" : "-color-warning-fg";

        Label lbl = new Label(protocol.toUpperCase());
        lbl.setStyle("-fx-font-size: 9px; -fx-font-weight: bold; -fx-text-fill: " + fgColor + ";");

        HBox badge = new HBox(lbl);
        badge.setAlignment(Pos.CENTER);
        badge.setPadding(new Insets(2, 6, 2, 6));
        badge.setMaxWidth(Region.USE_PREF_SIZE);
        badge.setStyle("-fx-background-color: " + bgColor + "; -fx-background-radius: 4px;");
        return badge;
    }

    public static HBox buildStatusBadge(InstallationSummary.InstallationStatus status) {
        String dotCssClass, labelText, bgColor;
        switch (status != null ? status : InstallationSummary.InstallationStatus.UNKNOWN) {
            case ONLINE       -> { dotCssClass = "server-status-online";       labelText = "Online";       bgColor = "-color-success-subtle"; }
            case OFFLINE      -> { dotCssClass = "server-status-offline";      labelText = "Offline";      bgColor = "-color-danger-subtle";  }
            case NOT_VERIFIED -> { dotCssClass = "server-status-not-verified"; labelText = "Not Verified"; bgColor = "-color-warning-subtle"; }
            default           -> { dotCssClass = "server-status-unknown";      labelText = "Unknown";      bgColor = "-color-neutral-subtle"; }
        }
        Circle dot = new Circle(3);
        dot.getStyleClass().add(dotCssClass);
        Label lbl = new Label(labelText);
        lbl.setStyle("-fx-font-size: 9.5px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");

        HBox badge = new HBox(5, dot, lbl);
        badge.setAlignment(Pos.CENTER);
        badge.setPadding(new Insets(2, 9, 2, 9));
        badge.setMaxWidth(Region.USE_PREF_SIZE);
        badge.setStyle("-fx-background-color: " + bgColor + "; -fx-background-radius: 4px;");
        return badge;
    }
}
