package komm.ui.cards;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import komm.ui.customnodes.ClockCanvas;
import komm.model.dto.summary.ChannelSummary;
import komm.model.dto.summary.ServerSummary;
import komm.ui.modals.ConfirmationModal;
import komm.ui.modals.CreateDecorationChannelModal;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import komm.ui.customnodes.CustomNotification;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import atlantafx.base.theme.Styles;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import komm.App;
import komm.model.dto.summary.ChannelUserSummary;
import komm.model.permissions.Permission;
import komm.ui.modals.CreateChannelModal;

@Slf4j
public class ChannelCard extends VBox {

    private static final PseudoClass SELECTED_PSEUDO_CLASS = PseudoClass.getPseudoClass("selected");

    /**
     * The one card whose messages are currently shown. Only ever one at a time.
     */
    private static ChannelCard currentlySelected = null;

    /**
     * The one voice channel card currently connected via WebRTC.
     */
    private static ChannelCard currentlyConnected = null;

    /**
     * The card currently waiting for a server join confirmation.
     */
    private static ChannelCard currentlyPending = null;

    private boolean isSelected = false;
    private boolean isDecoration = false;
    private ContextMenu activeContextMenu;
    private Label titleDecorationLabel;
    private javafx.scene.shape.Circle notificationDot;

    private static final long SPEAKING_LINGER_MS = 350;
    private static final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "speaking-debounce");
                t.setDaemon(true);
                return t;
            });
    private final Map<UUID, ScheduledFuture<?>> speakingOffTimers = new HashMap<>();

    private ServerSummary server;
    @Getter private ChannelSummary channel;
    private Button connectButton;
    private HBox actionsBox;
    private ProgressIndicator pendingSpinner;
    private boolean isConnected = false;

    private HBox channelHeader;
    private FontIcon channelIconNode;
    private Label channelNameLabel;

    private VBox connectedUsersBox;
    private HBox connectedUsersWrapper;
    private Map<UUID, ConnectedUserCard> userCards = new HashMap<>();

    /**
     * Pending audio/screen-share state that arrived via WS before the ConnectedUserCard
     * was created (i.e. before the async HTTP fetch in UserJoinedChannelHandler completed).
     * Applied immediately when the card is finally added in {@link #addConnectedUser}.
     * All accesses happen on the JavaFX application thread.
     */
    private final Map<UUID, Boolean> pendingMicState              = new HashMap<>();
    private final Map<UUID, Boolean> pendingSpeakerState          = new HashMap<>();
    private final Map<UUID, Boolean> pendingScreenShareState      = new HashMap<>();
    private final Map<UUID, Boolean> pendingServerMicState        = new HashMap<>();
    private final Map<UUID, Boolean> pendingServerSpeakerState    = new HashMap<>();

    private static final double HEADER_HEIGHT = 34.0;
    private static final double SEPARATOR_X = 16.0;

    public ChannelCard(ChannelSummary channel, ServerSummary server) {
        this.channel = channel;
        this.server = server;
        this.setSpacing(0);
        this.getStyleClass().add("channel-box");

        ChannelSummary.ChannelType type = channel.getChannelType();
        if (type == ChannelSummary.ChannelType.SPACER
                || type == ChannelSummary.ChannelType.DIVIDER
                || type == ChannelSummary.ChannelType.TITLE
                || type == ChannelSummary.ChannelType.CLOCK) {
            isDecoration = true;
            getStyleClass().add("decoration-channel");
            buildDecorationUI();
            return;
        }

        boolean isVoice = channel.getChannelType().equals(ChannelSummary.ChannelType.VOICE);

        // ── Channel header ────────────────────────────────────────────────
        channelHeader = new HBox(7);
        channelHeader.setMinHeight(HEADER_HEIGHT);
        channelHeader.setMaxHeight(HEADER_HEIGHT);
        channelHeader.setPadding(new Insets(0, 6, 0, 8));
        channelHeader.setAlignment(Pos.CENTER_LEFT);

        channelIconNode = resolveChannelIcon(channel, isVoice);

        channelNameLabel = new Label(channel.getChannelName());
        channelNameLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: -color-fg-muted;");

        Label typeDivider = new Label("|");
        typeDivider.setStyle("-fx-font-size: 9px; -fx-text-fill: -color-accent-emphasis; -fx-padding: 2 0 0 0;");

        Label typeTag = new Label(isVoice ? "voice" : "text");
        typeTag.setStyle("-fx-font-size: 9px; -fx-text-fill: -color-fg-subtle; -fx-padding: 2 0 0 0;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        actionsBox = new HBox(4);
        actionsBox.setAlignment(Pos.CENTER_RIGHT);
        actionsBox.setMinWidth(26);
        actionsBox.setMaxHeight(24);
        HBox.setHgrow(actionsBox, Priority.NEVER);

        if (isVoice) {
            createConnectButton();
            actionsBox.getChildren().add(connectButton);
        }

        channelHeader.getChildren().addAll(channelIconNode, channelNameLabel, typeDivider, typeTag, spacer, actionsBox);

        // ── Clicking the header row selects this channel's messages;
        //    double left-clicking an unconnected voice channel joins it ───
        channelHeader.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (channel.getChannelType() == ChannelSummary.ChannelType.VOICE && !isConnected) {
                if (e.getClickCount() == 2) attemptJoinVoiceChannel();
                return;
            }
            if (isSelected) return;

            App.getCachedServerPage().getChatSection().setActiveChannel(channel);
            markSelected(true);
        });

        // ── Right-click context menu on the header ────────────────────────
        channelHeader.setOnContextMenuRequested(e -> {
            boolean canEdit = App.getPermissionManager().has(Permission.EDIT_CHANNELS);
            boolean canDelete = App.getPermissionManager().has(Permission.DELETE_CHANNELS);
            if (!canEdit && !canDelete) return;

            if (activeContextMenu != null) activeContextMenu.hide();
            activeContextMenu = new ContextMenu();
            if (canEdit) {
                MenuItem editItem = new MenuItem("Edit Channel", new FontIcon(Feather.EDIT_2));
                editItem.setOnAction(evt -> App.showModal(new CreateChannelModal(server, channel)));
                activeContextMenu.getItems().add(editItem);
            }
            if (canDelete) {
                if (canEdit) activeContextMenu.getItems().add(new SeparatorMenuItem());
                MenuItem deleteItem = new MenuItem("Delete Channel", new FontIcon(Feather.TRASH_2));
                deleteItem.setOnAction(evt -> {
                    App.showModal(new ConfirmationModal("Delete Channel",
                            "Delete #" + channel.getChannelName() + "?\n"+
                            "This action cannot be undone. All messages in this channel will be lost.",
                            new FontIcon(MaterialDesignD.DELETE_ALERT),
                            this::deleteChannel));
                });
                activeContextMenu.getItems().add(deleteItem);
            }
            activeContextMenu.show(channelHeader, e.getScreenX(), e.getScreenY());
            e.consume();
        });

        // ── Vertical separator line ───────────────────────────────────────
        Region separator = new Region();
        separator.setPrefWidth(1.5);
        separator.setMinWidth(1.5);
        separator.setMaxWidth(1.5);
        separator.setStyle("-fx-background-color: rgba(255,255,255,0.10); -fx-background-radius: 2;");
        VBox.setMargin(separator, new Insets(2, 0, 4, 0));

        // ── Connected-users list ──────────────────────────────────────────
        connectedUsersBox = new VBox(1);
        connectedUsersBox.setPadding(new Insets(2, 6, 6, 6));
        HBox.setHgrow(connectedUsersBox, Priority.ALWAYS);

        // ── Wrapper: separator + users ────────────────────────────────────
        connectedUsersWrapper = new HBox(6, separator, connectedUsersBox);
        connectedUsersWrapper.setPadding(new Insets(0, 0, 6, SEPARATOR_X));
        connectedUsersWrapper.managedProperty().bind(connectedUsersWrapper.visibleProperty());
        connectedUsersWrapper.setVisible(false);

        this.getChildren().addAll(channelHeader, connectedUsersWrapper);
    }

    // ── Decoration card ───────────────────────────────────────────────────────

    private void buildDecorationUI() {
        this.setMinHeight(36);
        this.setMaxHeight(36);
        this.setPrefHeight(36);
        this.setMaxWidth(Double.MAX_VALUE);

        ChannelSummary.ChannelType type = channel.getChannelType();

        if (type == ChannelSummary.ChannelType.DIVIDER) {
            HBox container = new HBox();
            container.setAlignment(Pos.CENTER);
            container.setPadding(new Insets(0, 12, 0, 12));
            container.setMaxWidth(Double.MAX_VALUE);
            VBox.setVgrow(container, Priority.ALWAYS);
            Region line = new Region();
            line.setPrefHeight(1.5);
            line.setMaxHeight(1.5);
            line.setStyle("-fx-background-color: -color-border-muted;");
            HBox.setHgrow(line, Priority.ALWAYS);
            container.getChildren().add(line);
            this.getChildren().add(container);
        } else if (type == ChannelSummary.ChannelType.TITLE) {
            HBox container = new HBox(8);
            container.setAlignment(Pos.CENTER);
            container.setPadding(new Insets(0, 12, 0, 12));
            container.setMaxWidth(Double.MAX_VALUE);
            VBox.setVgrow(container, Priority.ALWAYS);
            Region leftLine = new Region();
            leftLine.setPrefHeight(1.5);
            leftLine.setMaxHeight(1.5);
            leftLine.setStyle("-fx-background-color: -color-border-muted;");
            HBox.setHgrow(leftLine, Priority.ALWAYS);
            titleDecorationLabel = new Label(channel.getChannelName());
            titleDecorationLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
            titleDecorationLabel.setMinWidth(Region.USE_PREF_SIZE);
            Region rightLine = new Region();
            rightLine.setPrefHeight(1.5);
            rightLine.setMaxHeight(1.5);
            rightLine.setStyle("-fx-background-color: -color-border-muted;");
            HBox.setHgrow(rightLine, Priority.ALWAYS);
            container.getChildren().addAll(leftLine, titleDecorationLabel, rightLine);
            this.getChildren().add(container);
        }
        // SPACER: no children — just empty 36px

        if (type == ChannelSummary.ChannelType.CLOCK) {
            this.setMinHeight(38);
            this.setMaxHeight(38);
            this.setPrefHeight(38);

            ClockCanvas clock = new ClockCanvas();
            HBox container = new HBox(clock);
            container.setAlignment(Pos.CENTER);
            container.setMaxWidth(Double.MAX_VALUE);
            VBox.setVgrow(container, Priority.ALWAYS);
            this.getChildren().add(container);
        }

        this.setOnContextMenuRequested(e -> {
            if (!App.getPermissionManager().has(Permission.EDIT_CHANNELS)) return;
            if (activeContextMenu != null) activeContextMenu.hide();
            activeContextMenu = new ContextMenu();
            if (type == ChannelSummary.ChannelType.TITLE) {
                MenuItem editItem = new MenuItem("Edit Title", new FontIcon(Feather.EDIT_2));
                editItem.setOnAction(evt -> App.showModal(new CreateDecorationChannelModal(server, channel)));
                activeContextMenu.getItems().add(editItem);
                activeContextMenu.getItems().add(new SeparatorMenuItem());
            }
            String label = type == ChannelSummary.ChannelType.SPACER ? "Delete Spacer"
                    : type == ChannelSummary.ChannelType.DIVIDER ? "Delete Divider"
                    : type == ChannelSummary.ChannelType.CLOCK ? "Delete Clock"
                    : "Delete Title";
            MenuItem deleteItem = new MenuItem(label, new FontIcon(Feather.TRASH_2));
            deleteItem.setOnAction(evt -> App.showModal(new ConfirmationModal(
                    "Delete Decoration",
                    "Delete this decoration element? This action cannot be undone.",
                    new FontIcon(MaterialDesignD.DELETE_ALERT),
                    this::deleteChannel)));
            activeContextMenu.getItems().add(deleteItem);
            activeContextMenu.show(this, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    // ── Selected state ────────────────────────────────────────────────────

    private static FontIcon resolveChannelIcon(ChannelSummary ch, boolean isVoice) {
        if (ch.getIcon() != null && !ch.getIcon().isBlank()) {
            try {
                return new FontIcon(ch.getIcon());
            } catch (Exception ignored) {
            }
        }
        return isVoice ? new FontIcon(MaterialDesignM.MICROPHONE) : new FontIcon(Feather.HASH);
    }

    /**
     * Marks this card as the one whose messages are currently shown,
     * deselecting whatever was selected before.
     */
    public void markSelected(boolean selected) {
        if (isDecoration) return;
        if (selected) {
            if (currentlySelected != null && currentlySelected != this) {
                currentlySelected.isSelected = false;
                currentlySelected.pseudoClassStateChanged(SELECTED_PSEUDO_CLASS, false);
                currentlySelected.refreshNameStyle();
            }
            currentlySelected = this;
            // Not cleared here: ChatSection.setActiveChannel() (called just before this) already
            // clears the dot and tells the server the channel is read, since selecting a channel
            // counts as viewing it. Clearing it a second time here would be redundant.
        } else {
            if (currentlySelected == this) currentlySelected = null;
        }
        isSelected = selected;
        pseudoClassStateChanged(SELECTED_PSEUDO_CLASS, selected);
        refreshNameStyle();
    }

    public void showNotificationDot() {
        if (isDecoration || actionsBox == null) return;
        if (notificationDot != null && actionsBox.getChildren().contains(notificationDot)) return;
        notificationDot = new javafx.scene.shape.Circle(3.5);
        notificationDot.setStyle("-fx-fill: -color-accent-emphasis;");
        HBox.setMargin(notificationDot, new Insets(0, 5, 0, 2));
        boolean isVoice = channel.getChannelType() == ChannelSummary.ChannelType.VOICE;
        if (isVoice) {
            actionsBox.getChildren().add(0, notificationDot);
        } else {
            actionsBox.getChildren().add(notificationDot);
        }
    }

    public void clearNotificationDot() {
        if (notificationDot != null && actionsBox != null) {
            actionsBox.getChildren().remove(notificationDot);
            notificationDot = null;
        }
    }

    public boolean hasNotificationDot() {
        return notificationDot != null;
    }

    private void refreshNameStyle() {
        if (isDecoration) return;
        boolean highlight = isConnected || isSelected;
        channelNameLabel.setStyle(
                "-fx-font-size: 14px; -fx-text-fill: "
                        + (highlight ? "-color-fg-default;" : "-color-fg-muted;"));
    }

    // ── Live update ───────────────────────────────────────────────────────

    /**
     * Updates the channel's display name and icon in-place.
     * Called when a CHANNEL_UPDATED WS message is received.
     */
    public void updateChannelInfo(String newName, String newIcon) {
        if (isDecoration) {
            if (newName != null && channel.getChannelType() == ChannelSummary.ChannelType.TITLE) {
                channel = ChannelSummary.builder()
                        .channelId(channel.getChannelId())
                        .serverId(channel.getServerId())
                        .channelName(newName)
                        .channelType(channel.getChannelType())
                        .description(channel.getDescription())
                        .position(channel.getPosition())
                        .icon(channel.getIcon())
                        .build();
                Platform.runLater(() -> {
                    if (titleDecorationLabel != null) titleDecorationLabel.setText(newName);
                });
            }
            return;
        }
        boolean isVoice = channel.getChannelType() == ChannelSummary.ChannelType.VOICE;
        channel = ChannelSummary.builder()
                .channelId(channel.getChannelId())
                .serverId(channel.getServerId())
                .channelName(newName != null ? newName : channel.getChannelName())
                .channelType(channel.getChannelType())
                .description(channel.getDescription())
                .position(channel.getPosition())
                .icon(newIcon)
                .build();

        Platform.runLater(() -> {
            channelNameLabel.setText(channel.getChannelName());
            FontIcon newIconNode = resolveChannelIcon(channel, isVoice);
            int idx = channelHeader.getChildren().indexOf(channelIconNode);
            if (idx >= 0) channelHeader.getChildren().set(idx, newIconNode);
            channelIconNode = newIconNode;
        });
    }

    // ── Connect button ────────────────────────────────────────────────────

    private void createConnectButton() {
        connectButton = new Button();
        connectButton.setFocusTraversable(false);
        connectButton.setGraphic(new FontIcon(MaterialDesignP.PHONE_PLUS));
        connectButton.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);
        connectButton.setOpacity(0.0);
        connectButton.setMinSize(24, 24);
        connectButton.setMaxSize(24, 24);
        connectButton.setPrefSize(24, 24);

        channelHeader.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_ENTERED, e -> {
            if (!isConnected && currentlyPending != this) connectButton.setOpacity(0.75);
        });
        channelHeader.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_EXITED, e -> {
            if (!isConnected && currentlyPending != this) connectButton.setOpacity(0.0);
        });

        connectButton.setOnAction(e -> {
            if (!isConnected) {
                attemptJoinVoiceChannel();
            } else {
                disconnectFromVoiceChannel();
            }
        });
    }

    private void attemptJoinVoiceChannel() {
        if (!App.getPermissionManager().hasInChannel(channel.getChannelId(), Permission.JOIN_VOICE)) {
            new CustomNotification(
                    "Permission Denied",
                    "You don't have permission to join this channel.",
                    new FontIcon(MaterialDesignC.CLOSE))
                    .showNotification();
            return;
        }
        // Explicitly leave the current voice channel before joining a new one so
        // the server receives CHANNEL_LEAVE and can enforce VIEW_CHANNEL on the
        // channel being left. connectToChannel tears down WebRTC internally but
        // does not send the app-level leave message.
        if (currentlyConnected != null) {
            currentlyConnected.disconnectFromVoiceChannel();
        }
        App.webrtcRoomClient.connectToChannel(
                server.getIpAddress() + ":" + server.getSignalPort(),
                server.getServerId(),
                channel.getChannelId()
        );
        onJoinPending();
    }

    private void disconnectFromVoiceChannel() {
        try {
            App.webrtcRoomClient.disconnectFromChannel();
            updateConnectedState(false);
            removeConnectedUser(App.getUser().getUserId());
            speakingOffTimers.values().forEach(f -> f.cancel(false));
            speakingOffTimers.clear();
            userCards.values().forEach(c -> c.setSpeaking(false));
            var chatSection = App.getCachedServerPage().getChatSection();
            if (chatSection.getActiveStreamCount() > 0) {
                chatSection.hideScreenShare();
            }
            UUID activeId = chatSection.getActiveTextChannelId();
            if (activeId == null || activeId.equals(channel.getChannelId())) {
                chatSection.clearAndShowWelcome();
                markSelected(false);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateConnectedState(boolean connected) {
        isConnected = connected;
        if (connected) {
            currentlyConnected = this;
        } else if (currentlyConnected == this) {
            currentlyConnected = null;
        }
        connectButton.setDisable(false);
        if (connected) {
            connectButton.setGraphic(new FontIcon(MaterialDesignP.PHONE_HANGUP));
            connectButton.setOpacity(1.0);
        } else {
            connectButton.setGraphic(new FontIcon(MaterialDesignP.PHONE_PLUS));
            connectButton.setOpacity(0.0);
        }
        refreshNameStyle();
    }

    // ── Context menu actions ──────────────────────────────────────────────

    private void deleteChannel() {
        Service<Void> svc = new Service<>() {
            @Override
            protected Task<Void> createTask() {
                return new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        App.getServices().installation().getChannelService()
                                .deleteChannel(channel.getChannelId());
                        return null;
                    }
                };
            }
        };
        svc.setOnSucceeded(e -> new CustomNotification("Channel Deleted", "The channel has been removed.", new FontIcon(MaterialDesignC.CHECK)).showNotification());
        svc.setOnFailed(e -> log.error("Failed to delete channel", svc.getException()));
        svc.start();
    }

    // ── Public user-management API ────────────────────────────────────────

    public void addConnectedUser(ChannelUserSummary userDTO) {
        if (!userCards.containsKey(userDTO.getUserId())) {
            UUID uid = userDTO.getUserId();
            // Apply any pending mute/deafen/screen-share state that arrived via WS
            // before the async HTTP fetch in UserJoinedChannelHandler completed.
            Boolean mic = pendingMicState.remove(uid);
            Boolean spk = pendingSpeakerState.remove(uid);
            Boolean scr = pendingScreenShareState.remove(uid);
            Boolean srvMic = pendingServerMicState.remove(uid);
            Boolean srvSpk = pendingServerSpeakerState.remove(uid);
            if (mic != null) userDTO.setMicEnabled(mic);
            if (spk != null) userDTO.setSpeakerEnabled(spk);
            if (scr != null) userDTO.setScreenSharingEnabled(scr);
            if (srvMic != null) userDTO.setServerMicEnabled(srvMic);
            if (srvSpk != null) userDTO.setServerSpeakerEnabled(srvSpk);

            boolean isSelf = App.getUser() != null && App.getUser().getUserId().equals(uid);
            ConnectedUserCard card = new ConnectedUserCard(userDTO, this.channel, isSelf);
            userCards.put(uid, card);
            String newName = userDTO.getUsername() == null ? "" : userDTO.getUsername().toLowerCase();
            int insertIdx = connectedUsersBox.getChildren().size();
            for (int i = 0; i < connectedUsersBox.getChildren().size(); i++) {
                if (connectedUsersBox.getChildren().get(i) instanceof ConnectedUserCard existing) {
                    String existName = existing.getUsername() == null ? "" : existing.getUsername().toLowerCase();
                    if (newName.compareTo(existName) < 0) { insertIdx = i; break; }
                }
            }
            connectedUsersBox.getChildren().add(insertIdx, card);
            connectedUsersWrapper.setVisible(true);
        }
    }

    public void onJoinPending() {
        currentlyPending = this;
        pendingSpinner = new ProgressIndicator();
        pendingSpinner.setPrefSize(16, 16);
        pendingSpinner.setMaxSize(16, 16);
        int idx = channelHeader.getChildren().indexOf(actionsBox);
        if (idx >= 0) channelHeader.getChildren().set(idx, pendingSpinner);
    }

    private void clearPendingSpinner() {
        if (pendingSpinner != null) {
            int idx = channelHeader.getChildren().indexOf(pendingSpinner);
            if (idx >= 0) channelHeader.getChildren().set(idx, actionsBox);
            pendingSpinner = null;
        }
    }

    public void onJoinConfirmed() {
        currentlyPending = null;
        clearPendingSpinner();
        updateConnectedState(true);
        App.getCachedServerPage().getChatSection().setActiveChannel(channel);
        markSelected(true);
    }

    public void onJoinDenied(String reason) {
        currentlyPending = null;
        clearPendingSpinner();
        connectButton.setOpacity(0.0);
        new CustomNotification(
                "Join Failed",
                reason != null ? reason : "Failed to join channel.",
                new FontIcon(MaterialDesignC.CLOSE))
                .showNotification();
    }

    public static void notifyJoinDenied(String reason) {
        if (currentlyPending != null) {
            ChannelCard card = currentlyPending;
            Platform.runLater(() -> card.onJoinDenied(reason));
        }
    }

    /**
     * Called when the server forces us out of this channel (e.g. we joined a different one).
     * Updates UI state without touching the WebRTC connection, since a new room is being established.
     */
    public static void onServerForcedLeave(UUID channelId) {
        if (currentlyConnected == null || !channelId.equals(currentlyConnected.channel.getChannelId())) return;
        ChannelCard card = currentlyConnected;
        card.speakingOffTimers.values().forEach(f -> f.cancel(false));
        card.speakingOffTimers.clear();
        card.userCards.values().forEach(c -> c.setSpeaking(false));
        card.updateConnectedState(false);
        if (App.getUser() != null) card.removeConnectedUser(App.getUser().getUserId());
    }

    public void removeConnectedUser(UUID userId) {
        ConnectedUserCard card = userCards.remove(userId);
        // Also evict any buffered state for this user
        pendingMicState.remove(userId);
        pendingSpeakerState.remove(userId);
        pendingScreenShareState.remove(userId);
        pendingServerMicState.remove(userId);
        pendingServerSpeakerState.remove(userId);
        if (card != null) {
            connectedUsersBox.getChildren().remove(card);
            if (userCards.isEmpty()) connectedUsersWrapper.setVisible(false);
        }
    }

    public void clearConnectedUsers() {
        userCards.clear();
        connectedUsersBox.getChildren().clear();
        connectedUsersWrapper.setVisible(false);
        pendingMicState.clear();
        pendingSpeakerState.clear();
        pendingScreenShareState.clear();
        pendingServerMicState.clear();
        pendingServerSpeakerState.clear();
    }

    public void setSpeaking(UUID userId, boolean active) {
        ConnectedUserCard card = userCards.get(userId);
        if (card == null) return;
        if (active) {
            ScheduledFuture<?> pending = speakingOffTimers.remove(userId);
            if (pending != null) pending.cancel(false);
            Platform.runLater(() -> card.setSpeaking(true));
        } else {
            if (!speakingOffTimers.containsKey(userId)) {
                ScheduledFuture<?> future = scheduler.schedule(() -> {
                    speakingOffTimers.remove(userId);
                    Platform.runLater(() -> card.setSpeaking(false));
                }, SPEAKING_LINGER_MS, TimeUnit.MILLISECONDS);
                speakingOffTimers.put(userId, future);
            }
        }
    }

    public void setUserMuted(UUID userId, boolean micEnabled) {
        ConnectedUserCard card = userCards.get(userId);
        if (card != null) {
            card.setMuted(micEnabled);
        } else {
            // Card not created yet — buffer the state; applied in addConnectedUser().
            pendingMicState.put(userId, micEnabled);
        }
    }

    public void setUserDeafened(UUID userId, boolean speakerEnabled) {
        ConnectedUserCard card = userCards.get(userId);
        if (card != null) {
            card.setDeafened(speakerEnabled);
        } else {
            pendingSpeakerState.put(userId, speakerEnabled);
        }
    }

    public void setUserScreenSharing(UUID userId, boolean screenSharing) {
        ConnectedUserCard card = userCards.get(userId);
        if (card != null) {
            card.setScreenSharing(screenSharing);
        } else {
            pendingScreenShareState.put(userId, screenSharing);
        }
    }

    public void setUserServerMuted(UUID userId, boolean serverMicEnabled) {
        ConnectedUserCard card = userCards.get(userId);
        if (card != null) {
            card.setServerMuted(serverMicEnabled);
        } else {
            pendingServerMicState.put(userId, serverMicEnabled);
        }
    }

    public void setUserServerDeafened(UUID userId, boolean serverSpeakerEnabled) {
        ConnectedUserCard card = userCards.get(userId);
        if (card != null) {
            card.setServerDeafened(serverSpeakerEnabled);
        } else {
            pendingServerSpeakerState.put(userId, serverSpeakerEnabled);
        }
    }

    public static UUID getConnectedChannelId() {
        return currentlyConnected != null ? currentlyConnected.channel.getChannelId() : null;
    }

    /** Disconnects from voice if currently connected to the given channel (e.g. on deletion). */
    public static void disconnectIfConnectedTo(UUID channelId) {
        if (currentlyConnected != null
                && channelId.equals(currentlyConnected.channel.getChannelId())) {
            currentlyConnected.disconnectFromVoiceChannel();
        }
    }

    /** Returns the channel ID of the join that is currently pending confirmation, or null. */
    public static UUID getPendingChannelId() {
        return currentlyPending != null ? currentlyPending.channel.getChannelId() : null;
    }

}
