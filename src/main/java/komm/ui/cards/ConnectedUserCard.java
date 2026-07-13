package komm.ui.cards;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.ImagePattern;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import komm.App;
import komm.AppState;
import komm.model.dto.request.BanRequest;
import komm.model.dto.summary.ChannelSummary;
import komm.model.dto.summary.ChannelUserSummary;
import komm.model.permissions.Permission;
import komm.ui.avatar.AvatarColor;
import komm.ui.customnodes.CustomNotification;
import komm.ui.customnodes.UserPingGraphPopup;
import komm.ui.modals.AssignRoleModal;
import komm.ui.modals.SendPokeModal;
import komm.ui.pages.ServerPage;
import komm.ui.chat.ChatSection;
import komm.model.dto.summary.FriendSummary;
import komm.ui.pages.HomePage;
import komm.ui.profile.UserProfilePopup;
import komm.ui.sections.FriendsSection;
import komm.ui.theme.AppTheme;
import komm.ui.theme.ThemeManager;
import komm.websocket.messages.WsMessageType;
import org.kordamp.ikonli.feather.Feather;
import komm.websocket.messages.payloads.DisconnectUserPayload;
import komm.websocket.messages.payloads.ServerDeafenUserPayload;
import komm.websocket.messages.payloads.ServerMuteUserPayload;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;

import java.io.ByteArrayInputStream;
import java.util.UUID;

public class ConnectedUserCard extends HBox {

    private boolean speaking = false;
    private ContextMenu activeContextMenu;

    private final Circle speakingRing;
    private final Circle bg;
    private final FontIcon micIcon;
    private final FontIcon deafIcon;
    private final FontIcon shareIcon;
    private Timeline speakingAnim;

    private Color colorAccent4;

    private StackPane avatarPane;
    private Label avatarInitial;
    private ChangeListener<Number> selfAvatarListener;
    private ChangeListener<AppTheme> themeChangeListener;

    private final ChannelUserSummary user;
    private final ChannelSummary channel;
    private final boolean isSelf;

    // ── Constructors ──────────────────────────────────────────────────────

    public ConnectedUserCard(ChannelUserSummary user, ChannelSummary channel) {
        this(user, channel, false);
    }

    public ConnectedUserCard(ChannelUserSummary user, ChannelSummary channel, boolean isSelf) {
        super(8);
        this.user = user;
        this.isSelf = isSelf;
        this.channel = channel;
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(3, 6, 3, 4));
        getStyleClass().add("connected-user-card");

        speakingRing = new Circle(15);
        speakingRing.setFill(Color.TRANSPARENT);
        speakingRing.setStroke(Color.TRANSPARENT);
        speakingRing.setStrokeWidth(2.5);
        // Show only left and right arcs (each ~quarter circle), hide top and bottom
        double quarterArc = Math.PI * 2 * 15 / 4;
        speakingRing.getStrokeDashArray().setAll(quarterArc, quarterArc);
        speakingRing.setStrokeDashOffset(quarterArc / 2);

        bg = new Circle(12);

        // ── Avatar: image or initial ──────────────────────────────────────
        StackPane avatar = buildAvatar(user);

        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) Platform.runLater(() -> {
                colorAccent4 = loadColorFromCSS("-color-accent-4");
                if (!hasAvatarImage(user)) {
                    bg.setFill(AvatarColor.forNameJfx(user.getUsername()));
                }
            });
        });

        // Re-resolve the accent color on live theme switches — the scene-property
        // listener above only fires on attach/detach, so it misses in-place theme
        // changes while this card stays mounted (e.g. switching theme on the server page).
        themeChangeListener = (obs, oldTheme, newTheme) -> Platform.runLater(() -> {
            colorAccent4 = loadColorFromCSS("-color-accent-4");
            if (speaking) speakingRing.setStroke(colorAccent4);
        });
        ThemeManager.get().themeProperty().addListener(new WeakChangeListener<>(themeChangeListener));

        // ── Name ─────────────────────────────────────────────────────────
        Label nameLabel = new Label(user.getUsername());
        nameLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #b5bac1;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // ── Status icons ─────────────────────────────────────────────────
        shareIcon = makeIcon(MaterialDesignM.MONITOR_SHARE, "Screen sharing");
        shareIcon.getStyleClass().add("custom-accent-icon");
        deafIcon = makeIcon(MaterialDesignH.HEADPHONES_OFF, "Deafened");
        micIcon = makeIcon(MaterialDesignM.MICROPHONE_OFF, "Muted");
        setIconVisible(shareIcon, user.isScreenSharingEnabled());
        refreshMicIcon(user.isMicEnabled(), user.isServerMicEnabled());
        refreshDeafIcon(user.isSpeakerEnabled(), user.isServerSpeakerEnabled());

        HBox iconsBox = new HBox(5, micIcon, deafIcon, shareIcon);
        iconsBox.setAlignment(Pos.CENTER_RIGHT);

        getChildren().addAll(avatar, nameLabel, spacer, iconsBox);

        // ── Context menu ─────────────────────────────────────────────────
        setOnContextMenuRequested(event -> {
            if (activeContextMenu != null) activeContextMenu.hide();
            activeContextMenu = buildContextMenu();
            activeContextMenu.show(this, event.getScreenX(), event.getScreenY());
            event.consume();
        });

        // ── Drag source (move member to another voice channel) ────────────
        // Permission is checked server-side only — cached client state may be stale.
        if (!isSelf) {
            setOnDragDetected(ev -> {
                Dragboard db = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent cc = new ClipboardContent();
                cc.putString("USER:" + user.getUserId());
                db.setContent(cc);
                getStyleClass().add("drag-source");
                ev.consume();
            });
            setOnDragDone(ev -> {
                getStyleClass().remove("drag-source");
                ev.consume();
            });
        }

        // ── Left-click opens profile popup ────────────────────────────────
        setOnMouseClicked(event -> {
            if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                if (activeContextMenu != null) { activeContextMenu.hide(); activeContextMenu = null; }
                java.util.UUID serverId = App.getCachedServerPage() != null
                        ? App.getCachedServerPage().getServer().getServerId() : null;
                boolean isOtherUser = !isSelf && !user.getUserId().equals(App.getUser().getUserId());
                boolean sameChannel = channel.getChannelId() != null &&
                        channel.getChannelId().equals(App.getWebrtcRoomClient().getCurrentChannelId());
                Runnable joinLiveAction = (isOtherUser && user.isScreenSharingEnabled() && sameChannel)
                        ? this::joinLive : null;
                UserProfilePopup.getInstance().show(
                        App.getStackPane().getScene().getWindow(),
                        event.getScreenX(), event.getScreenY(),
                        user.getUserId(), serverId, null, joinLiveAction);
            }
        });

        // ── Live self-avatar refresh ──────────────────────────────────────
        // When the logged-in user changes their own avatar, refresh this card's
        // avatar from the fresh bytes. The strong listener is kept in a field so the
        // weak registration lives as long as the card — surviving navigation away and
        // back, and auto-unregistering when the card is garbage-collected.
        if (isSelf) {
            selfAvatarListener = (obs, oldVal, newVal) -> Platform.runLater(this::refreshSelfAvatar);
            AppState.selfAvatarRevisionProperty().addListener(new WeakChangeListener<>(selfAvatarListener));
        }
    }

    /** Refreshes the avatar in place from the logged-in user's current bytes (self card only). */
    private void refreshSelfAvatar() {
        if (App.getUser() != null) user.setAvatar(App.getUser().getAvatar());
        applyAvatarContent(user);
    }

    // ── Public API ────────────────────────────────────────────────────────

    public String getUsername() { return user.getUsername(); }

    public void setSpeaking(boolean active) {
        if (this.speaking == active) return;
        this.speaking = active;
        if (speakingAnim != null) speakingAnim.stop();
        speakingAnim = new Timeline(new KeyFrame(Duration.millis(150),
                new KeyValue(speakingRing.strokeProperty(),
                        active ? colorAccent4 : Color.TRANSPARENT)));
        speakingAnim.play();
    }

    public void setMuted(boolean micEnabled) {
        user.setMicEnabled(micEnabled);
        refreshMicIcon(micEnabled, user.isServerMicEnabled());
    }

    public void setDeafened(boolean speakerEnabled) {
        user.setSpeakerEnabled(speakerEnabled);
        refreshDeafIcon(speakerEnabled, user.isServerSpeakerEnabled());
    }

    public void setServerMuted(boolean serverMicEnabled) {
        user.setServerMicEnabled(serverMicEnabled);
        refreshMicIcon(user.isMicEnabled(), serverMicEnabled);
    }

    public void setServerDeafened(boolean serverSpeakerEnabled) {
        user.setServerSpeakerEnabled(serverSpeakerEnabled);
        refreshDeafIcon(user.isSpeakerEnabled(), serverSpeakerEnabled);
    }

    private void refreshMicIcon(boolean micEnabled, boolean serverMicEnabled) {
        if (!serverMicEnabled) {
            micIcon.getStyleClass().remove("self-muted-status-icon");
            if (!micIcon.getStyleClass().contains("muted-status-icon"))
                micIcon.getStyleClass().add("muted-status-icon");
            setIconVisible(micIcon, true);
        } else if (!micEnabled) {
            micIcon.getStyleClass().remove("muted-status-icon");
            if (!micIcon.getStyleClass().contains("self-muted-status-icon"))
                micIcon.getStyleClass().add("self-muted-status-icon");
            setIconVisible(micIcon, true);
        } else {
            setIconVisible(micIcon, false);
        }
    }

    private void refreshDeafIcon(boolean speakerEnabled, boolean serverSpeakerEnabled) {
        if (!serverSpeakerEnabled) {
            deafIcon.getStyleClass().remove("self-muted-status-icon");
            if (!deafIcon.getStyleClass().contains("muted-status-icon"))
                deafIcon.getStyleClass().add("muted-status-icon");
            setIconVisible(deafIcon, true);
        } else if (!speakerEnabled) {
            deafIcon.getStyleClass().remove("muted-status-icon");
            if (!deafIcon.getStyleClass().contains("self-muted-status-icon"))
                deafIcon.getStyleClass().add("self-muted-status-icon");
            setIconVisible(deafIcon, true);
        } else {
            setIconVisible(deafIcon, false);
        }
    }

    public void setScreenSharing(boolean screenShareEnabled) {
        user.setScreenSharingEnabled(screenShareEnabled);
        setIconVisible(shareIcon, screenShareEnabled);
    }

    // ── Avatar builder ────────────────────────────────────────────────────

    /**
     * Returns a 30×30 circular avatar backed by the user's image when the
     * {@code avatar} byte array is present and non-empty, falling back to
     * the initial-letter circle otherwise.  The {@link #speakingRing} and
     * {@link #bg} circles are reused so that speaking animation still works
     * regardless of which branch is taken.
     */
    private StackPane buildAvatar(ChannelUserSummary user) {
        avatarPane = new StackPane(speakingRing, bg);
        avatarPane.setMinSize(30, 30);
        avatarPane.setMaxSize(30, 30);
        avatarPane.setAlignment(Pos.CENTER);
        applyAvatarContent(user);
        return avatarPane;
    }

    /**
     * Fills the {@link #bg} circle from the user's avatar bytes, falling back to an
     * initial-letter label. Updates the existing {@link #avatarPane} in place (no node
     * recreation), so it doubles as the refresh path when the avatar changes.
     */
    private void applyAvatarContent(ChannelUserSummary user) {
        bg.setRadius(12);
        if (avatarInitial != null) {
            avatarPane.getChildren().remove(avatarInitial);
            avatarInitial = null;
        }

        if (hasAvatarImage(user)) {
            try {
                Image image = new Image(
                        new ByteArrayInputStream(user.getAvatar()),
                        24, 24,   // requested size (fits inside the 12-radius bg circle)
                        true,     // preserve ratio
                        true      // smooth
                );
                if (!image.isError()) {
                    bg.setFill(new ImagePattern(image));
                    App.getAvatarCache().populate(user.getUserId(), user.getUsername(), user.getAvatar());
                    return;
                }
            } catch (Exception ignored) {
                // Fall through to initial-letter fallback
            }
        }

        // ── Fallback: initial letter ──────────────────────────────────────
        // Still populate the cache (username only) so UserProfilePopup can render the letter avatar.
        App.getAvatarCache().populate(user.getUserId(), user.getUsername(), null);
        bg.setFill(AvatarColor.forNameJfx(user.getUsername()));

        String initial = (user.getUsername() != null && !user.getUsername().isEmpty())
                ? String.valueOf(user.getUsername().charAt(0)).toUpperCase() : "?";
        avatarInitial = new Label(initial);
        avatarInitial.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: white;");
        StackPane.setAlignment(avatarInitial, Pos.CENTER);
        avatarPane.getChildren().add(avatarInitial);
    }

    /** Returns {@code true} when the user has a non-empty avatar byte array. */
    private static boolean hasAvatarImage(ChannelUserSummary user) {
        return user.getAvatar() != null && user.getAvatar().length > 0;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private ContextMenu buildContextMenu() {
        ContextMenu menu = new ContextMenu();
        boolean isOtherUser = !isSelf && !user.getUserId().equals(App.getUser().getUserId());

        // ── Profile ───────────────────────────────────────────────────────
        MenuItem profileItem = new MenuItem("Profile");
        profileItem.setGraphic(new FontIcon((MaterialDesignA.ACCOUNT)));
        profileItem.setOnAction(e -> App.showModal(new komm.ui.modals.UserProfileModal(user.getUserId())));
        menu.getItems().add(profileItem);

        // ── Send Message ──────────────────────────────────────────────────
        // Always offered for other users; the recipient's privacy settings are
        // enforced server-side when the message is actually sent.
        if (isOtherUser) {
            final UUID targetId = user.getUserId();
            final String targetName = user.getUsername();
            MenuItem sendMsgItem = new MenuItem("Send Message", new FontIcon(Feather.MESSAGE_CIRCLE));
            sendMsgItem.setOnAction(e -> {
                if (App.getCurrentPage() instanceof HomePage hp) {
                    hp.navigateToDm(targetId, targetName);
                } else if (App.getCachedHomePage() != null) {
                    App.changePage(App.getCachedHomePage());
                    Platform.runLater(() -> {
                        if (App.getCurrentPage() instanceof HomePage hp) {
                            hp.navigateToDm(targetId, targetName);
                        }
                    });
                }
            });
            menu.getItems().add(1, sendMsgItem);
        }

        // ── User Volume (slider) ──────────────────────────────────────────
        if (isOtherUser) {
            menu.getItems().add(new SeparatorMenuItem());

            float savedVolume = App.getWebrtcRoomClient().getUserVolume(user.getUserId());
            int savedPct = Math.round(savedVolume * 100);

            Label titleLabel = new Label("User Volume");
            titleLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #b5bac1; -fx-font-weight: bold;");

            Label valueLabel = new Label(savedPct + "%");
            valueLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #b5bac1; -fx-min-width: 38px;");
            valueLabel.setAlignment(Pos.CENTER_RIGHT);

            Region spacerH = new Region();
            HBox.setHgrow(spacerH, Priority.ALWAYS);
            HBox header = new HBox(4, titleLabel, spacerH, valueLabel);
            header.setAlignment(Pos.CENTER_LEFT);

            Slider volumeSlider = new Slider(0, 200, savedPct);
            volumeSlider.setPrefWidth(190);
            volumeSlider.setBlockIncrement(5);

            volumeSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
                int pct = newVal.intValue();
                valueLabel.setText(pct + "%");
                App.getWebrtcRoomClient().setUserVolume(user.getUserId(), pct / 100f);
            });

            VBox content = new VBox(4, header, volumeSlider);
            content.setPadding(new Insets(4, 8, 6, 8));
            content.setPrefWidth(210);

            menu.getItems().add(new CustomMenuItem(content, false));
        }

        // ── Assign Role ───────────────────────────────────────────────────
        if (App.getPermissionManager().has(Permission.EDIT_SERVER_PERMS)) {
            menu.getItems().add(new SeparatorMenuItem());
            MenuItem assignRoleItem = new MenuItem("Assign Role");
            assignRoleItem.setGraphic(new FontIcon((MaterialDesignA.ACCOUNT_EDIT)));
            assignRoleItem.setOnAction(e -> App.showModal(new AssignRoleModal(user.getUserId())));
            menu.getItems().add(assignRoleItem);
        }

        // ── Server Mute / Deafen ──────────────────────────────────────────
        boolean canMute   = App.getPermissionManager().has(Permission.MUTE_USERS);
        boolean canDeafen = App.getPermissionManager().has(Permission.DEAFEN_USERS);
        if (canMute || canDeafen) {
            menu.getItems().add(new SeparatorMenuItem());
            if (canMute) {
                CheckBox muteBox = new CheckBox("Server Mute");
                muteBox.setSelected(!user.isServerMicEnabled());
                muteBox.setOnAction(e -> sendServerMute(!muteBox.isSelected()));
                HBox muteRow = new HBox(muteBox);
                muteRow.setPadding(new Insets(1, 4, 1, 4));
                menu.getItems().add(new CustomMenuItem(muteRow, false));
            }
            if (canDeafen) {
                CheckBox deafenBox = new CheckBox("Server Deafen");
                deafenBox.setSelected(!user.isServerSpeakerEnabled());
                deafenBox.setOnAction(e -> sendServerDeafen(!deafenBox.isSelected()));
                HBox deafenRow = new HBox(deafenBox);
                deafenRow.setPadding(new Insets(1, 4, 1, 4));
                menu.getItems().add(new CustomMenuItem(deafenRow, false));
            }
        }

        // ── View Ping ─────────────────────────────────────────────────────
        if (isOtherUser && App.getPermissionManager().has(Permission.CHECK_PING)) {
            menu.getItems().add(new SeparatorMenuItem());
            MenuItem viewPingItem = new MenuItem("View Ping");
            viewPingItem.setGraphic(new FontIcon((MaterialDesignS.SIGNAL)));
            viewPingItem.setOnAction(e -> showUserPingPopup());
            menu.getItems().add(viewPingItem);
        }

        // ── Join Live ─────────────────────────────────────────────────────
        boolean sameChannel = channel.getChannelId() != null &&
                channel.getChannelId().equals(App.getWebrtcRoomClient().getCurrentChannelId());
        if (isOtherUser && user.isScreenSharingEnabled() && sameChannel) {
            menu.getItems().add(new SeparatorMenuItem());
            ServerPage sp = (ServerPage) App.getCurrentPage();
            boolean alreadyWatching = sp.getChatSection().isWatchingStream(user.getUserId().toString());
            MenuItem joinLiveItem = new MenuItem(alreadyWatching ? "Already watching" : "Join Live");
            joinLiveItem.setGraphic(new FontIcon((MaterialDesignM.MONITOR_SHARE)));
            joinLiveItem.setDisable(alreadyWatching);
            joinLiveItem.setOnAction(e -> joinLive());
            menu.getItems().add(joinLiveItem);
        }

        // ── Poke ──────────────────────────────────────────────────────────────
        if (isOtherUser
                && App.getPermissionManager().has(Permission.POKE_USERS)
                && user.isPokesEnabled()) {
            menu.getItems().add(new SeparatorMenuItem());
            MenuItem pokeItem = new MenuItem("Poke");
            pokeItem.setGraphic(new FontIcon(MaterialDesignH.HAND_WAVE));
            pokeItem.setOnAction(e -> App.showModal(new SendPokeModal(user.getUserId(), user.getUsername())));
            menu.getItems().add(pokeItem);
        }

        // ── Moderation actions ────────────────────────────────────────────────
        boolean canDisconnect = isOtherUser && App.getPermissionManager().has(Permission.MOVE_MEMBERS);
        boolean canKick = isOtherUser && App.getPermissionManager().has(Permission.KICK_USERS);
        boolean canBan = isOtherUser && App.getPermissionManager().has(Permission.BAN_USERS);
        if (canDisconnect || canKick || canBan) {
            menu.getItems().add(new SeparatorMenuItem());
            if (canDisconnect) {
                MenuItem disconnectItem = new MenuItem("Disconnect");
                disconnectItem.setGraphic(new FontIcon(MaterialDesignP.PHONE_HANGUP));
                disconnectItem.setOnAction(e -> sendDisconnectUser());
                menu.getItems().add(disconnectItem);
            }
            if (canKick) {
                MenuItem kickItem = new MenuItem("Kick");
                kickItem.setGraphic(new FontIcon(MaterialDesignD.DOOR_OPEN));
                kickItem.setOnAction(e -> kickUser());
                menu.getItems().add(kickItem);
            }
            if (canBan) {
                MenuItem banItem = new MenuItem("Ban");
                banItem.setGraphic(new FontIcon(MaterialDesignA.ACCOUNT_CANCEL));
                banItem.setOnAction(e -> banUser());
                menu.getItems().add(banItem);
            }
        }

        return menu;
    }

    private void sendServerMute(boolean serverMicEnabled) {
        try {
            App.getServices().installation().getWsClient().send(
                    WsMessageType.SERVER_MUTE_USER,
                    ServerMuteUserPayload.builder()
                            .targetUserId(user.getUserId())
                            .serverMicEnabled(serverMicEnabled)
                            .build());
        } catch (Exception ignored) {}
    }

    private void sendServerDeafen(boolean serverSpeakerEnabled) {
        try {
            App.getServices().installation().getWsClient().send(
                    WsMessageType.SERVER_DEAFEN_USER,
                    ServerDeafenUserPayload.builder()
                            .targetUserId(user.getUserId())
                            .serverSpeakerEnabled(serverSpeakerEnabled)
                            .build());
        } catch (Exception ignored) {}
    }

    private void sendDisconnectUser() {
        try {
            App.getServices().installation().getWsClient().send(
                    WsMessageType.DISCONNECT_USER,
                    DisconnectUserPayload.builder()
                            .targetUserId(user.getUserId())
                            .build());
        } catch (Exception ignored) {}
    }

    private void kickUser() {
        UUID serverId = App.getCachedServerPage().getServer().getServerId();
        Service<Void> svc = new Service<>() {
            @Override
            protected Task<Void> createTask() {
                return new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        App.getServices().hub().getHubModerationService()
                                .kickUser(serverId, user.getUserId());
                        return null;
                    }
                };
            }
        };
        svc.setOnFailed(e -> Platform.runLater(() ->
                new CustomNotification("Kick Failed", "Failed to kick user.", new FontIcon(MaterialDesignC.CLOSE)).showNotification()));
        svc.start();
    }

    private void banUser() {
        UUID serverId = App.getCachedServerPage().getServer().getServerId();
        Service<Void> svc = new Service<>() {
            @Override
            protected Task<Void> createTask() {
                return new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        App.getServices().hub().getHubModerationService()
                                .banUser(serverId, BanRequest.builder().userId(user.getUserId()).build());
                        return null;
                    }
                };
            }
        };
        svc.setOnFailed(e -> Platform.runLater(() ->
                new CustomNotification("Ban Failed", "Failed to ban user.", new FontIcon(MaterialDesignC.CLOSE)).showNotification()));
        svc.start();
    }

    /**
     * Opens a real-time ping graph popup for this user.
     * The popup subscribes to {@code USER_PING_UPDATE} messages on show
     * and sends {@code USER_PING_UNSUBSCRIBE} on hide.
     */
    private void showUserPingPopup() {
        UserPingGraphPopup popup = new UserPingGraphPopup(user.getUserId(), user.getUsername());
        javafx.geometry.Bounds bounds = localToScreen(getBoundsInLocal());
        double x = bounds.getMaxX() + 8;
        double y = bounds.getMinY();
        popup.show(App.getStackPane().getScene().getWindow(), x, y);
    }

    /**
     * Called when the user clicks "Join Live" in the context menu.
     * Tells the {@link ChatSection} on the current page to switch into
     * screen-share viewer mode and subscribe to this participant's video.
     */
    private void joinLive() {
        ServerPage serverPage = (ServerPage) App.getCurrentPage();
        ChatSection chatSection = serverPage.getChatSection();
        chatSection.showScreenShare(user.getUserId().toString(), user.getUsername());
    }

    private Color loadColorFromCSS(String cssVariable) {
        Region probe = new Region();
        probe.setStyle("-fx-background-color: " + cssVariable + ";");
        getChildren().add(probe);
        applyCss();
        layout();
        var fills = probe.getBackground().getFills();
        getChildren().remove(probe);
        if (!fills.isEmpty() && fills.get(0).getFill() instanceof Color c) return c;
        return null;
    }

    private FontIcon makeIcon(org.kordamp.ikonli.Ikon code, String tip) {
        FontIcon icon = new FontIcon(code);
        icon.getStyleClass().add("muted-status-icon");
        Tooltip.install(icon, new Tooltip(tip));
        return icon;
    }

    private void setIconVisible(FontIcon icon, boolean visible) {
        icon.setVisible(visible);
        icon.setManaged(visible);
    }
}