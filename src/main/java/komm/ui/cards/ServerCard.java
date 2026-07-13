package komm.ui.cards;

import atlantafx.base.theme.Styles;
import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.util.Duration;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import komm.App;
import komm.model.dto.summary.InstallationSummary;
import komm.model.dto.summary.ServerSummary;
import komm.model.permissions.Permission;
import komm.ui.cards.ChannelCard;
import komm.ui.customnodes.CustomNotification;
import komm.ui.modals.ConfirmationModal;
import komm.ui.modals.CreateInviteModal;
import komm.ui.modals.EditServerModal;
import komm.ui.pages.HomePage;
import komm.ui.pages.ServerPage;
import komm.ui.utils.IconColorUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;
import komm.ui.avatar.AvatarColor;

@Slf4j
public class ServerCard extends HBox {

    @Getter
    private final ServerSummary serverSummary;

    private static final int AVATAR_SIZE = 62;
    private static final double AVATAR_R  = AVATAR_SIZE / 2.0;
    private static final int ROW_HEIGHT   = 90;

    private Label activeUsersValue;
    private MenuButton optionsBtn;
    private ProgressIndicator connectingSpinner;
    private HBox statsArea;
    private MenuItem connectMenuItem;
    private Runnable connectAction;

    public ServerCard(ServerSummary serverSummary) {
        this.serverSummary = serverSummary;
        setMaxWidth(Double.MAX_VALUE);
        setMinHeight(ROW_HEIGHT);
        setMaxHeight(ROW_HEIGHT);
        setPrefHeight(ROW_HEIGHT);
        getStyleClass().add("server-row");
        initialize();
    }

    private void initialize() {
        setAlignment(Pos.CENTER_LEFT);
        setFillHeight(false);   // prevent children from stretching to row height
        setPadding(new Insets(0, 10, 0, 16));
        setSpacing(0);

        // ── Avatar ────────────────────────────────────────────────────────────
        StackPane avatar = buildAvatar();
        HBox.setMargin(avatar, new Insets(0, 16, 0, 0));

        // ── Identity column (name + status badge) ────────────────────────────
        VBox identity = new VBox(5);
        identity.setAlignment(Pos.CENTER_LEFT);
        identity.setMinWidth(180);
        HBox.setHgrow(identity, Priority.ALWAYS);

        Label nameLabel = new Label(serverSummary.getServerName());
        nameLabel.setStyle(
                "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;"
        );
        nameLabel.setMaxWidth(Double.MAX_VALUE);

        HBox statusBadge = buildStatusBadge();

        identity.getChildren().addAll(nameLabel, statusBadge);

        // ── Stats cells (ONLINE + MEMBERS) ────────────────────────────────────
        statsArea = new HBox(0);
        statsArea.setAlignment(Pos.CENTER);

        VBox onlineCell  = buildStatCell("ONLINE",  String.valueOf(serverSummary.getActiveUsers()));
        activeUsersValue = (Label) onlineCell.getChildren().get(0); // value label is first

        Separator statDivider = vDivider();

        VBox membersCell = buildStatCell("MEMBERS", String.valueOf(serverSummary.getTotalMembers()));

        statsArea.getChildren().addAll(onlineCell, statDivider, membersCell);

        // ── Options menu ──────────────────────────────────────────────────────
        optionsBtn = createOptionsMenu();
        HBox.setMargin(optionsBtn, new Insets(0, 0, 0, 4));

        getChildren().addAll(avatar, identity, statsArea, optionsBtn);
        setupInteractions();
    }

    // ── Avatar ────────────────────────────────────────────────────────────────

    private StackPane buildAvatar() {
        byte[] bytes = serverSummary.getAvatarBytes();
        return (bytes != null && bytes.length > 0) ? buildImageAvatar(bytes) : buildLetterAvatar();
    }

    private StackPane buildImageAvatar(byte[] bytes) {
        StackPane pane = avatarShell();
        Circle clip = new Circle(AVATAR_R, AVATAR_R, AVATAR_R);
        Image img = new Image(new ByteArrayInputStream(bytes), AVATAR_SIZE, AVATAR_SIZE, true, true);
        ImageView iv = new ImageView(img);
        iv.setFitWidth(AVATAR_SIZE);
        iv.setFitHeight(AVATAR_SIZE);
        iv.setPreserveRatio(false);
        iv.setClip(clip);
        pane.getChildren().add(iv);
        return pane;
    }

    private StackPane buildLetterAvatar() {
        StackPane pane = avatarShell();
        String name   = serverSummary.getServerName().trim();
        pane.setStyle(pane.getStyle() + " -fx-background-color: " + AvatarColor.forName(name) + ";");
        String letter = !name.isEmpty() ? name.substring(0, 1).toUpperCase() : "?";
        Text t = new Text(letter);
        t.setFill(Color.WHITE);
        t.setFont(Font.font("System", FontWeight.BOLD, 22));
        pane.getChildren().add(t);
        return pane;
    }

    private StackPane avatarShell() {
        StackPane pane = new StackPane();
        pane.setMinSize(AVATAR_SIZE, AVATAR_SIZE);
        pane.setMaxSize(AVATAR_SIZE, AVATAR_SIZE);
        pane.setPrefSize(AVATAR_SIZE, AVATAR_SIZE);
        pane.setStyle(
                "-fx-background-radius: " + AVATAR_R + "px;" +
                "-fx-border-color: -color-border-default;" +
                "-fx-border-width: 1.5px;" +
                "-fx-border-radius: " + AVATAR_R + "px;"
        );
        return pane;
    }

    // ── Role badge ────────────────────────────────────────────────────────────

    private HBox buildRoleBadge() {
        String role = serverSummary.getRole().toString();
        String bgColor, fgColor;
        switch (role) {
            case "OWNER" -> { bgColor = "-color-accent-subtle";  fgColor = "-color-accent-fg";  }
            case "ADMIN" -> { bgColor = "-color-warning-subtle"; fgColor = "-color-warning-fg"; }
            default      -> { bgColor = "-color-neutral-subtle"; fgColor = "-color-fg-muted";   }
        }
        HBox badge = new HBox();
        badge.setAlignment(Pos.CENTER);
        badge.setPadding(new Insets(2, 8, 2, 8));
        badge.setMaxWidth(Region.USE_PREF_SIZE);
        badge.setStyle("-fx-background-color: " + bgColor + "; -fx-background-radius: 3px;");
        Label lbl = new Label(formatRole(role));
        lbl.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: " + fgColor + ";");
        badge.getChildren().add(lbl);
        return badge;
    }

    // ── Status badge ──────────────────────────────────────────────────────────

    private HBox buildStatusBadge() {
        String status = resolveStatus();
        String dotCssClass, labelText, bgColor;
        switch (status) {
            case "ONLINE"  -> { dotCssClass = "server-status-online";  labelText = "Online";  bgColor = "-color-success-subtle"; }
            case "OFFLINE" -> { dotCssClass = "server-status-offline"; labelText = "Offline"; bgColor = "-color-danger-subtle";  }
            default        -> { dotCssClass = "server-status-unknown"; labelText = "Unknown"; bgColor = "-color-neutral-subtle"; }
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

    // ── Stat cell ─────────────────────────────────────────────────────────────

    private VBox buildStatCell(String labelText, String valueText) {
        Label value = new Label(valueText);
        value.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");

        Label label = new Label(labelText);
        label.setStyle("-fx-font-size: 8.5px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle;");

        VBox cell = new VBox(2, value, label);
        cell.setAlignment(Pos.CENTER);
        cell.setMinWidth(76);
        cell.setPadding(new Insets(0, 12, 0, 12));
        return cell;
    }

    // ── Divider ───────────────────────────────────────────────────────────────

    private Separator vDivider() {
        Separator s = new Separator(Orientation.VERTICAL);
        s.setMaxHeight(34);
        s.setOpacity(0.7);
        return s;
    }

    // ── Options menu ──────────────────────────────────────────────────────────

    private MenuButton createOptionsMenu() {
        MenuButton btn = new MenuButton();
        btn.setGraphic(new FontIcon(MaterialDesignD.DOTS_VERTICAL));
        btn.setFocusTraversable(false);
        btn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);

        boolean isOnline = serverSummary.getStatus() == InstallationSummary.InstallationStatus.ONLINE;

        connectMenuItem = new MenuItem("Connect to Server");
        connectMenuItem.setGraphic(new FontIcon(MaterialDesignL.LOGIN));
        connectMenuItem.setDisable(!isOnline);
        connectMenuItem.setOnAction(e -> {
            if (connectAction != null) connectAction.run();
            else App.changePage(App.getOrCreateServerPage(serverSummary));
        });

        List<String> perms = serverSummary.getEffectivePermissions();

        if (isOnline && perms != null && perms.contains(Permission.INVITE_USERS.name())) {
            MenuItem inviteItem = new MenuItem("Invite to Server");
            inviteItem.setGraphic(new FontIcon(MaterialDesignA.ACCOUNT_PLUS_OUTLINE));
            inviteItem.setOnAction(e -> App.showModal(new CreateInviteModal(serverSummary)));
            btn.getItems().add(inviteItem);
        }

        if (isOnline && perms != null && perms.contains(Permission.VIEW_SERVER_SETTINGS.name())) {
            MenuItem settingsItem = new MenuItem("Server Settings");
            settingsItem.setGraphic(new FontIcon(MaterialDesignC.COG));
            settingsItem.setOnAction(e -> App.showModal(new EditServerModal(serverSummary, true)));
            btn.getItems().add(settingsItem);
        }

        SeparatorMenuItem connectSeparator = new SeparatorMenuItem();
        SeparatorMenuItem disconnectSeparator = new SeparatorMenuItem();
        MenuItem disconnectItem = new MenuItem("Disconnect",
                IconColorUtil.colored(MaterialDesignP.PHONE_OFF, "-color-danger-fg", 18));
        disconnectItem.setOnAction(e -> {
            UUID channelId = App.getWebrtcRoomClient().getCurrentChannelId();
            if (channelId != null) {
                ChannelCard.onServerForcedLeave(channelId);
                App.getWebrtcRoomClient().disconnectFromChannel();
            }
            App.getServices().disconnectInstallation();
            App.setCachedServerPage(null);
            new CustomNotification("Disconnected", "You have disconnected from " + serverSummary.getServerName() + ".",
                    new FontIcon(MaterialDesignP.PHONE_OFF)).showNotification();
        });

        SeparatorMenuItem leaveSeparator = new SeparatorMenuItem();
        MenuItem leaveItem = serverSummary.getRole() != ServerSummary.Role.OWNER
                ? new MenuItem("Leave Server", IconColorUtil.colored(MaterialDesignD.DOOR_OPEN, "-color-danger-fg", 18))
                : null;
        if (leaveItem != null) {
            leaveItem.setOnAction(e -> App.showModal(new ConfirmationModal(
                    "Leave Server",
                    "Are you sure you want to leave " + serverSummary.getServerName() + "? You'll need a new invite to rejoin.",
                    new FontIcon(MaterialDesignD.DOOR_OPEN),
                    () -> handleLeaveServer(leaveItem)
            )));
        }

        btn.setOnShowing(e -> {
            ServerPage cached = App.getCachedServerPage();
            boolean connected = cached != null && serverSummary.getServerId().equals(cached.getServer().getServerId());

            boolean hasConnect = btn.getItems().contains(connectMenuItem);
            boolean hasDisconnect = btn.getItems().contains(disconnectItem);
            boolean hasLeave = leaveItem != null && btn.getItems().contains(leaveItem);

            connectMenuItem.setText(connected ? "Go to Server" : "Connect to Server");

            if (!hasConnect) {
                if (!btn.getItems().isEmpty()) btn.getItems().add(connectSeparator);
                btn.getItems().add(connectMenuItem);
            }
            if (connected && !hasDisconnect) {
                btn.getItems().addAll(disconnectSeparator, disconnectItem);
            } else if (!connected && hasDisconnect) {
                btn.getItems().removeAll(disconnectSeparator, disconnectItem);
            }
            if (leaveItem != null && !hasLeave) {
                btn.getItems().addAll(leaveSeparator, leaveItem);
            }
        });

        return btn;
    }

    // ── Interactions ──────────────────────────────────────────────────────────

    private void setupInteractions() {
        boolean isOnline = serverSummary.getStatus() == InstallationSummary.InstallationStatus.ONLINE;
        setCursor(isOnline ? javafx.scene.Cursor.HAND : javafx.scene.Cursor.DEFAULT);
        setOnMouseClicked(e -> {
            if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            if (!isOnline) return;
            if (connectingSpinner != null) return;
            if (connectAction != null) connectAction.run();
            else App.changePage(App.getOrCreateServerPage(serverSummary));
        });
        setOnMouseEntered(e -> { if (isOnline) animateCard(1.03, 1.0); });
        setOnMouseExited(e -> {
            if (optionsBtn.isShowing()) return;
            animateCard(1.0, 1.0);
        });
        optionsBtn.showingProperty().addListener((obs, wasShowing, isShowing) -> {
            if (isShowing) {
                getStyleClass().add("menu-open");
            } else {
                getStyleClass().remove("menu-open");
                if (!isHover()) animateCard(1.0, 1.0);
            }
        });
    }

    private void animateCard(double scale, double opacity) {
        ScaleTransition st = new ScaleTransition(Duration.millis(150), this);
        st.setToX(scale);
        st.setToY(scale);
        st.play();
        FadeTransition ft = new FadeTransition(Duration.millis(150), this);
        ft.setToValue(opacity);
        ft.play();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setConnectAction(Runnable action) {
        this.connectAction = action;
    }

    public void showConnecting() {
        if (connectingSpinner != null) return;
        connectingSpinner = new ProgressIndicator();
        connectingSpinner.setPrefSize(16, 16);
        connectingSpinner.setMaxSize(16, 16);
        HBox.setMargin(connectingSpinner, new Insets(0, 12, 0, 12));
        int idx = getChildren().indexOf(statsArea);
        if (idx >= 0) getChildren().add(idx, connectingSpinner);
    }

    public void clearConnecting() {
        if (connectingSpinner == null) return;
        getChildren().remove(connectingSpinner);
        connectingSpinner = null;
    }

    public void updateActiveUsers(int count) {
        if (serverSummary != null) serverSummary.setActiveUsers(count);
        if (activeUsersValue != null) activeUsersValue.setText(String.valueOf(count));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void handleLeaveServer(MenuItem leaveItem) {
        leaveItem.setDisable(true);
        UUID serverId = serverSummary.getServerId();

        Service<Void> leaveService = new Service<>() {
            @Override
            protected Task<Void> createTask() {
                return new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        App.getServices().hub().getServerService().leaveServer(serverId);
                        return null;
                    }
                };
            }
        };

        leaveService.setOnSucceeded(ev -> {
            ServerPage sp = App.getCachedServerPage();
            if (sp != null && serverId.equals(sp.getServer().getServerId())) {
                UUID channelId = App.getWebrtcRoomClient().getCurrentChannelId();
                if (channelId != null) {
                    ChannelCard.onServerForcedLeave(channelId);
                    App.getWebrtcRoomClient().disconnectFromChannel();
                }
                App.getServices().disconnectInstallation();
                App.setCachedServerPage(null);
                App.changePage(App.getOrCreateHomePage());
            }
            HomePage homePage = App.getCachedHomePage();
            if (homePage != null) homePage.removeServer(serverId);
            new CustomNotification("Left Server",
                    "You have left \"" + serverSummary.getServerName() + "\".",
                    new FontIcon(MaterialDesignD.DOOR_OPEN)).showNotification();
        });

        leaveService.setOnFailed(ev -> {
            leaveItem.setDisable(false);
            log.error("Failed to leave server {}: {}", serverId, leaveService.getException().getMessage());
            new CustomNotification("Leave Server",
                    "Something went wrong. Please try again.",
                    new FontIcon(MaterialDesignA.ALERT_CIRCLE_OUTLINE)).showNotification();
        });

        leaveService.start();
    }

    private String resolveStatus() {
        try {
            Object s = serverSummary.getStatus();
            if (s != null) {
                String v = s.toString().toUpperCase();
                if (v.equals("ONLINE") || v.equals("OFFLINE")) return v;
            }
        } catch (Exception ignored) {}
        return "UNKNOWN";
    }

    private String formatRole(String role) {
        return switch (role) {
            case "OWNER"  -> "Owner";
            case "ADMIN"  -> "Admin";
            case "MEMBER" -> "Member";
            default       -> role;
        };
    }
}
