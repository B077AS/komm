package komm.ui.modals.installationsettings;

import atlantafx.base.theme.Styles;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import komm.App;
import komm.api.HttpStatusException;
import komm.model.dto.summary.InstallationSummary;
import komm.model.dto.summary.ServerSummary;
import komm.ui.avatar.AvatarColor;
import komm.ui.customnodes.CustomNotification;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import java.io.ByteArrayInputStream;
import java.util.List;

import static komm.ui.modals.installationsettings.InstallationSettingsUi.buildStatusBadge;
import static komm.ui.modals.installationsettings.InstallationSettingsUi.wrapScroll;

public class HostedServersTab implements InstallationSettingsTab {

    private static final int AVATAR_SIZE = 40;

    private final InstallationSettingsContext ctx;
    private final VBox listContainer = new VBox(8);
    private boolean loaded = false;
    private final ScrollPane pane;

    public HostedServersTab(InstallationSettingsContext ctx) {
        this.ctx = ctx;
        this.pane = buildPane();
    }

    @Override public String   name()    { return "Hosted Servers"; }
    @Override public String   description() { return "See which servers run on this installation"; }
    @Override public FontIcon icon()    { return new FontIcon(MaterialDesignF.FORMAT_LIST_BULLETED_SQUARE); }
    @Override public Node     getPane() { return pane; }

    @Override
    public void onShown() {
        if (loaded) return;
        loaded = true;
        List<ServerSummary> servers = App.getOrCreateHomePage()
                .getServersForInstallation(ctx.installation().getInstallationId());
        populateList(servers);
    }

    // ── Build pane ────────────────────────────────────────────────────────────

    private ScrollPane buildPane() {
        VBox content = new VBox(0);
        content.setPadding(new Insets(16, 20, 16, 20));
        content.setAlignment(Pos.TOP_LEFT);
        content.getChildren().add(listContainer);
        return wrapScroll(content);
    }

    // ── Populate ──────────────────────────────────────────────────────────────

    private void populateList(List<ServerSummary> servers) {
        listContainer.getChildren().clear();
        if (servers.isEmpty()) {
            listContainer.getChildren().add(emptyLabel("No servers found for this installation"));
            return;
        }
        for (ServerSummary server : servers) {
            listContainer.getChildren().add(buildServerRow(server));
        }
    }

    // ── Row ───────────────────────────────────────────────────────────────────

    private HBox buildServerRow(ServerSummary server) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setFillHeight(false);
        row.setPadding(new Insets(10, 12, 10, 12));
        row.setStyle(
                "-fx-background-color: -color-bg-subtle;" +
                "-fx-border-color: -color-border-default;" +
                "-fx-border-radius: 8; -fx-background-radius: 8;"
        );

        StackPane avatar = buildServerAvatar(server);

        Label nameLabel = new Label(server.getServerName());
        nameLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");

        String memberText = server.getTotalMembers() == 1 ? "1 member" : server.getTotalMembers() + " members";
        Label membersLabel = new Label(memberText);
        membersLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");

        VBox identity = new VBox(2, nameLabel, membersLabel);
        identity.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(identity, Priority.ALWAYS);

        HBox badgeSlot = new HBox();
        badgeSlot.setAlignment(Pos.CENTER);
        badgeSlot.getChildren().add(buildStatusBadge(server.getStatus()));

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(14, 14);

        Button refreshBtn = new Button(null, new FontIcon(MaterialDesignR.REFRESH));
        ((FontIcon) refreshBtn.getGraphic()).setIconSize(13);
        refreshBtn.setFocusTraversable(false);
        refreshBtn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);
        refreshBtn.setStyle("-fx-padding: 4px;");
        Tooltip.install(refreshBtn, new Tooltip("Check connection status"));

        refreshBtn.setOnAction(e -> {
            refreshBtn.setDisable(true);
            badgeSlot.getChildren().setAll(spinner);
            Thread.ofVirtual().start(() -> {
                try {
                    InstallationSummary.InstallationStatus status =
                            App.getServices().hub().getServerService().getServerStatus(server.getServerId());
                    Platform.runLater(() -> {
                        badgeSlot.getChildren().setAll(buildStatusBadge(status));
                        refreshBtn.setDisable(false);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        badgeSlot.getChildren().setAll(buildStatusBadge(InstallationSummary.InstallationStatus.UNKNOWN));
                        refreshBtn.setDisable(false);
                        new CustomNotification("Status Check Failed", HttpStatusException.extractMessage(ex),
                                new FontIcon(MaterialDesignL.LAN_DISCONNECT)).showNotification();
                    });
                }
            });
        });

        row.getChildren().addAll(avatar, identity, badgeSlot, refreshBtn);
        return row;
    }

    // ── Avatar ────────────────────────────────────────────────────────────────

    private StackPane buildServerAvatar(ServerSummary server) {
        StackPane pane = new StackPane();
        double r = AVATAR_SIZE / 2.0;
        pane.setMinSize(AVATAR_SIZE, AVATAR_SIZE);
        pane.setMaxSize(AVATAR_SIZE, AVATAR_SIZE);
        pane.setPrefSize(AVATAR_SIZE, AVATAR_SIZE);
        String baseStyle = "-fx-background-radius: " + r + "px;" +
                "-fx-border-color: -color-border-default;" +
                "-fx-border-width: 1.5px;" +
                "-fx-border-radius: " + r + "px;";

        byte[] bytes = server.getAvatarBytes();
        if (bytes != null && bytes.length > 0) {
            pane.setStyle(baseStyle);
            Circle clip = new Circle(r, r, r);
            Image img = new Image(new ByteArrayInputStream(bytes), AVATAR_SIZE, AVATAR_SIZE, true, true);
            ImageView iv = new ImageView(img);
            iv.setFitWidth(AVATAR_SIZE);
            iv.setFitHeight(AVATAR_SIZE);
            iv.setPreserveRatio(false);
            iv.setClip(clip);
            pane.getChildren().add(iv);
        } else {
            String name = server.getServerName() != null ? server.getServerName().trim() : "?";
            pane.setStyle(baseStyle + " -fx-background-color: " + AvatarColor.forName(name) + ";");
            String letter = !name.isEmpty() ? name.substring(0, 1).toUpperCase() : "?";
            Text t = new Text(letter);
            t.setFill(Color.WHITE);
            t.setFont(Font.font("System", FontWeight.BOLD, 15));
            pane.getChildren().add(t);
        }
        return pane;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Label emptyLabel(String text) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 13px;");
        lbl.setPadding(new Insets(24, 0, 0, 0));
        return lbl;
    }
}
