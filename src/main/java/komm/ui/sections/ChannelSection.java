package komm.ui.sections;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import komm.model.dto.summary.ChannelSummary;
import komm.model.dto.summary.ChannelUserSummary;
import komm.model.dto.summary.ServerSummary;
import komm.ui.avatar.AvatarCache;
import komm.ui.cards.ChannelCard;
import komm.model.dto.summary.InstallationSummary;
import komm.ui.modals.CreateChannelModal;
import komm.ui.modals.CreateDecorationChannelModal;
import komm.ui.modals.CreateInviteModal;
import komm.ui.modals.EditServerModal;
import komm.ui.modals.ServerInfoModal;
import komm.ui.cards.ConnectedUserCard;
import komm.websocket.messages.UserSessionEntry;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.MoveMemberPayload;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

import atlantafx.base.theme.Styles;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import komm.App;
import komm.api.InstallationConnection;
import komm.model.permissions.Permission;
import komm.ui.customnodes.CustomNotification;
import komm.ui.pages.HomePage;
import komm.ui.utils.IconColorUtil;

@Slf4j
public class ChannelSection extends VBox {

    @Getter
    private final ServerSummary server;
    private final VBox channelsContainer;
    private MenuButton menuButton;

    @Getter
    private Map<UUID, ChannelCard> channelBoxes = new HashMap<>();
    private final List<ChannelCard> orderedChannelCards = new ArrayList<>();

    // ── Drag reorder ──────────────────────────────────────────────────────────
    private int draggedIndex;
    private List<UUID> pendingChannelOrder;

    private final Service<Void> saveChannelOrderService = new Service<>() {
        @Override
        protected Task<Void> createTask() {
            return new Task<>() {
                @Override
                protected Void call() throws Exception {
                    App.getServices().installation().getChannelService()
                            .reorderChannels(pendingChannelOrder);
                    return null;
                }
            };
        }
    };

    private final Service<Void> reloadPermissionsService = new Service<>() {
        @Override
        protected Task<Void> createTask() {
            return new Task<>() {
                @Override
                protected Void call() {
                    try {
                        var installConn = App.getServices().installation(); // throws IllegalStateException if not yet connected
                        var permSummary = installConn.getInstallationPermissionService().getServerPermissions();
                        App.getPermissionManager().load(permSummary, server.getRole());

                        var permSvc = installConn.getChannelPermissionService();
                        for (UUID channelId : channelBoxes.keySet()) {
                            ChannelCard c = channelBoxes.get(channelId);
                            if (c != null) {
                                ChannelSummary.ChannelType t = c.getChannel().getChannelType();
                                if (t == ChannelSummary.ChannelType.SPACER
                                        || t == ChannelSummary.ChannelType.DIVIDER
                                        || t == ChannelSummary.ChannelType.TITLE
                                        || t == ChannelSummary.ChannelType.CLOCK) continue;
                            }
                            try {
                                var chPerms = permSvc.getChannelPermissions(channelId);
                                if (chPerms != null) App.getPermissionManager().updateChannelOverrides(chPerms);
                            } catch (Exception ignored) {}
                        }
                    } catch (IllegalStateException ignored) {
                        // Installation not connected yet — loadChannelsService will load permissions once it finishes
                    } catch (Exception e) {
                        App.getPermissionManager().loadDefaults(server.getRole());
                    }
                    return null;
                }
            };
        }
    };

    private final Service<Map<UUID, ChannelCard>> loadChannelsService = new Service<>() {
        @Override
        protected Task<Map<UUID, ChannelCard>> createTask() {
            return new Task<>() {
                @Override
                protected Map<UUID, ChannelCard> call() throws Exception {
                    String ticket = App.getServices().hub().getServerService()
                            .requestServerTicket(server.getServerId());
                    App.getServices().setInstallation(new InstallationConnection(server, ticket));
                    App.getAvatarCache().invalidate();

                    // Load permissions for this server
                    try {
                        var permSummary = App.getServices().installation()
                                .getInstallationPermissionService().getServerPermissions();
                        App.getPermissionManager().load(permSummary, server.getRole());
                    } catch (Exception e) {
                        App.getPermissionManager().loadDefaults(server.getRole());
                    }

                    Map<UUID, ChannelSummary> channelsMap = App.getServices().installation()
                            .getChannelService().getChannels();
                    if (channelsMap == null) return new HashMap<>();

                    var permSvc = App.getServices().installation().getChannelPermissionService();
                    Map<UUID, ChannelCard> result = new HashMap<>();
                    UUID currentVoiceChannelId = App.getWebrtcRoomClient() != null
                            ? App.getWebrtcRoomClient().getCurrentChannelId() : null;
                    for (Map.Entry<UUID, ChannelSummary> entry : channelsMap.entrySet()) {
                        ChannelCard card = new ChannelCard(entry.getValue(), server);

                        ChannelSummary.ChannelType t = entry.getValue().getChannelType();
                        boolean isVoice = t == ChannelSummary.ChannelType.VOICE;
                        boolean isDecoration = t == ChannelSummary.ChannelType.SPACER
                                || t == ChannelSummary.ChannelType.DIVIDER
                                || t == ChannelSummary.ChannelType.TITLE
                                || t == ChannelSummary.ChannelType.CLOCK;

                        // Hydrate unread state from the server. Voice channels only show it if
                        // we're currently connected to that channel's call, mirroring the live rule.
                        if (entry.getValue().isHasUnread()
                                && (!isVoice || entry.getKey().equals(currentVoiceChannelId))) {
                            card.showNotificationDot();
                        }

                        if (!isDecoration) {
                            try {
                                var chPerms = permSvc.getChannelPermissions(entry.getKey());
                                if (chPerms != null) App.getPermissionManager().updateChannelOverrides(chPerms);
                            } catch (Exception ignored) {
                            }
                        }

                        if (isVoice) {
                            List<UserSessionEntry> users = App.getServices().installation()
                                    .getChannelService().getConnectedUsers(entry.getKey());
                            if (users != null) {
                                for (UserSessionEntry user : users) {
                                    ChannelUserSummary summary = App.getServices().hub()
                                            .getUserService().getChannelUserSummary(user.getUserId());
                                    summary.setMicEnabled(user.isMicEnabled());
                                    summary.setSpeakerEnabled(user.isSpeakerEnabled());
                                    summary.setScreenSharingEnabled(user.isScreenSharingEnabled());
                                    summary.setServerMicEnabled(user.isServerMicEnabled());
                                    summary.setServerSpeakerEnabled(user.isServerSpeakerEnabled());
                                    summary.setPokesEnabled(user.isPokesEnabled());
                                    card.addConnectedUser(summary);
                                }
                            }

                        }
                        result.put(entry.getKey(), card);
                    }
                    return result;
                }
            };
        }
    };

    public ChannelSection(ServerSummary server) {
        this.server = server;
        this.setStyle("-fx-background-color: -color-bg-subtle;");
        this.setPrefWidth(240);
        this.setMinWidth(240);

        // Server header
        HBox serverHeader = new HBox(10);
        serverHeader.setMinHeight(48);
        serverHeader.setMaxHeight(48);
        serverHeader.setPadding(new Insets(0, 12, 0, 12));
        serverHeader.setAlignment(Pos.CENTER_LEFT);
        serverHeader.setStyle("-fx-background-color: -color-bg-subtle; -fx-border-color: transparent transparent -color-border-muted transparent; -fx-border-width: 0 0 1px 0;");

        Button homeBtn = new Button(null, new FontIcon(MaterialDesignH.HOME));
        homeBtn.setFocusTraversable(false);
        homeBtn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);
        homeBtn.setOnAction(e -> App.changePage(App.getOrCreateHomePage()));

        Circle homeBadgeDot = new Circle(4);
        homeBadgeDot.setStyle("-fx-fill: -color-accent-emphasis;");
        homeBadgeDot.setMouseTransparent(true);
        homeBadgeDot.visibleProperty().bind(App.dmUnreadProperty().or(App.friendRequestPendingProperty()));
        StackPane homeBtnWrap = new StackPane(homeBtn, homeBadgeDot);
        homeBtnWrap.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE, javafx.scene.layout.Region.USE_PREF_SIZE);
        StackPane.setAlignment(homeBadgeDot, Pos.TOP_RIGHT);
        StackPane.setMargin(homeBadgeDot, new Insets(4, 4, 0, 0));

        Label serverName = new Label(server.getServerName());
        serverName.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        menuButton = new MenuButton();
        menuButton.setGraphic(new FontIcon(MaterialDesignD.DOTS_VERTICAL));
        menuButton.setFocusTraversable(false);
        menuButton.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);

        //buildMenuItems(menuButton);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        serverHeader.getChildren().addAll(homeBtnWrap, serverName, spacer, menuButton);

        // Channels content
        ScrollPane channelScrollPane = new ScrollPane();
        channelScrollPane.setFitToWidth(true);
        channelScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        channelScrollPane.setStyle("-fx-background-color: -color-bg-subtle;");
        VBox.setVgrow(channelScrollPane, Priority.ALWAYS);

        channelsContainer = new VBox(2);
        channelsContainer.setPadding(new Insets(8, 8, 8, 8));

        channelScrollPane.setContent(channelsContainer);

        this.getChildren().addAll(serverHeader, channelScrollPane);

        loadChannelsService.setOnSucceeded(e -> {
            channelBoxes = loadChannelsService.getValue();
            orderedChannelCards.clear();
            orderedChannelCards.addAll(channelBoxes.values());
            orderedChannelCards.sort(Comparator.comparingInt(c -> c.getChannel().getPosition()));
            channelsContainer.getChildren().setAll(orderedChannelCards);
            if (App.getPermissionManager().has(Permission.EDIT_CHANNELS)) {
                for (int i = 0; i < orderedChannelCards.size(); i++) {
                    setupDragAndDrop(orderedChannelCards.get(i), i);
                }
            }
            for (ChannelCard card : channelBoxes.values()) {
                if (card.getChannel().getChannelType() == ChannelSummary.ChannelType.VOICE) {
                    setupUserMoveDrop(card);
                }
            }
            buildMenuItems(menuButton);
            menuButton.setOnShowing(ev -> {
                menuButton.getItems().clear();
                buildMenuItems(menuButton);
            });
        });

        loadChannelsService.setOnFailed(e ->
                log.error("Channel loading failed", loadChannelsService.getException())
        );

        reloadPermissionsService.setOnSucceeded(e -> {
            menuButton.getItems().clear();
            buildMenuItems(menuButton);
            if (App.getCachedHomePage() != null) {
                App.getCachedHomePage().syncServerPermissions(
                        server.getServerId(), App.getPermissionManager().getEffectivePermissions());
            }
        });
    }

    /**
     * Re-fetches permissions from the already-established InstallationConnection.
     * Called when returning to a cached ServerPage after navigating away (e.g. to home),
     * because the PermissionManager is cleared on every server → non-server navigation.
     */
    public void reloadPermissions() {
        reloadPermissionsService.restart();
    }

    public Service<?> getLoadChannelsService() {
        return loadChannelsService;
    }

    public void startLoading() {
        loadChannelsService.start();
    }

    /**
     * Adds a channel card, inserting it at the correct position based on the channel's
     * position field. Used both for newly created channels and for channels restored
     * after a VIEW_CHANNEL permission change.
     */
    public void addChannel(UUID channelId, ChannelCard card) {
        if (channelBoxes.containsKey(channelId)) return; // already visible — ignore duplicate
        channelBoxes.put(channelId, card);
        orderedChannelCards.add(card);
        orderedChannelCards.sort(Comparator.comparingInt(c -> c.getChannel().getPosition()));
        if (App.getPermissionManager().has(Permission.EDIT_CHANNELS)) {
            refreshDragAndDrop(); // rebuilds channelsContainer order + drag handlers
        } else {
            channelsContainer.getChildren().setAll(orderedChannelCards);
        }
        if (card.getChannel().getChannelType() == ChannelSummary.ChannelType.VOICE) {
            setupUserMoveDrop(card);
        }
    }

    /**
     * Removes a channel card. Disconnects voice only if the user is still connected
     * to that channel and isn't currently switching to a different one.
     * Skips the disconnect when a pending join to another channel is in flight — in
     * that case the user has already left on the WS level and disconnecting would
     * tear down the new connection being established.
     */
    public void removeChannel(UUID channelId) {
        ChannelCard card = channelBoxes.remove(channelId);
        if (card != null) {
            orderedChannelCards.remove(card);
            UUID pendingId = ChannelCard.getPendingChannelId();
            if (pendingId == null || channelId.equals(pendingId)) {
                ChannelCard.disconnectIfConnectedTo(channelId);
            }
            channelsContainer.getChildren().remove(card);
            if (App.getPermissionManager().has(Permission.EDIT_CHANNELS)) {
                refreshDragAndDrop();
            }
        }
    }

    /** Applies a channel order received via WebSocket from another client's reorder. */
    public void applyChannelOrder(List<UUID> channelIds) {
        List<ChannelCard> reordered = new ArrayList<>();
        for (UUID id : channelIds) {
            ChannelCard card = channelBoxes.get(id);
            if (card != null) reordered.add(card);
        }
        orderedChannelCards.stream()
                .filter(c -> !reordered.contains(c))
                .forEach(reordered::add);
        orderedChannelCards.clear();
        orderedChannelCards.addAll(reordered);
        for (int i = 0; i < orderedChannelCards.size(); i++) {
            orderedChannelCards.get(i).getChannel().setPosition(i);
        }
        channelsContainer.getChildren().setAll(orderedChannelCards);
        if (App.getPermissionManager().has(Permission.EDIT_CHANNELS)) {
            refreshDragAndDrop();
        }
    }

    // ── Drag and drop ─────────────────────────────────────────────────────────

    private void setupDragAndDrop(ChannelCard card, int idx) {
        card.setOnDragDetected(ev -> {
            Dragboard db = card.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent cc = new ClipboardContent();
            cc.putString(String.valueOf(idx));
            db.setContent(cc);
            draggedIndex = idx;
            card.getStyleClass().add("drag-source");
            ev.consume();
        });
        card.setOnDragOver(ev -> {
            String s = ev.getDragboard().hasString() ? ev.getDragboard().getString() : null;
            if (ev.getGestureSource() != card && s != null && !s.startsWith("USER:"))
                ev.acceptTransferModes(TransferMode.MOVE);
            ev.consume();
        });
        card.setOnDragEntered(ev -> {
            String s = ev.getDragboard().hasString() ? ev.getDragboard().getString() : null;
            if (ev.getGestureSource() != card && s != null && !s.startsWith("USER:"))
                card.getStyleClass().add("drag-target");
            ev.consume();
        });
        card.setOnDragExited(ev -> {
            card.getStyleClass().remove("drag-target");
            ev.consume();
        });
        card.setOnDragDropped(ev -> {
            String s = ev.getDragboard().hasString() ? ev.getDragboard().getString() : null;
            // User-move drops are handled by setupUserMoveDrop via addEventHandler — don't interfere.
            if (s != null && s.startsWith("USER:")) { ev.consume(); return; }
            boolean ok = false;
            if (s != null && !s.isEmpty() && draggedIndex != idx) {
                orderedChannelCards.add(idx, orderedChannelCards.remove(draggedIndex));
                refreshDragAndDrop();
                saveChannelOrder();
                ok = true;
            }
            ev.setDropCompleted(ok);
            ev.consume();
        });
        card.setOnDragDone(ev -> {
            card.getStyleClass().removeAll("drag-source", "drag-target");
            ev.consume();
        });
    }

    private void refreshDragAndDrop() {
        channelsContainer.getChildren().setAll(orderedChannelCards);
        for (int i = 0; i < orderedChannelCards.size(); i++) {
            orderedChannelCards.get(i).getChannel().setPosition(i);
            setupDragAndDrop(orderedChannelCards.get(i), i);
        }
    }

    // ── User move drop target (addEventHandler so it coexists with channel D&D) ──

    private void setupUserMoveDrop(ChannelCard card) {
        card.addEventHandler(DragEvent.DRAG_OVER, ev -> {
            String s = ev.getDragboard().hasString() ? ev.getDragboard().getString() : null;
            if (s != null && s.startsWith("USER:") && ev.getGestureSource() instanceof ConnectedUserCard) {
                ev.acceptTransferModes(TransferMode.MOVE);
                ev.consume();
            }
        });
        card.addEventHandler(DragEvent.DRAG_ENTERED, ev -> {
            String s = ev.getDragboard().hasString() ? ev.getDragboard().getString() : null;
            if (s != null && s.startsWith("USER:") && ev.getGestureSource() instanceof ConnectedUserCard) {
                if (!card.getStyleClass().contains("drag-target"))
                    card.getStyleClass().add("drag-target");
            }
        });
        card.addEventHandler(DragEvent.DRAG_EXITED, ev -> {
            String s = ev.getDragboard().hasString() ? ev.getDragboard().getString() : null;
            if (s != null && s.startsWith("USER:"))
                card.getStyleClass().remove("drag-target");
        });
        card.addEventHandler(DragEvent.DRAG_DROPPED, ev -> {
            String s = ev.getDragboard().hasString() ? ev.getDragboard().getString() : null;
            if (s != null && s.startsWith("USER:")) {
                try {
                    UUID targetUserId = UUID.fromString(s.substring("USER:".length()));
                    sendMoveMember(targetUserId, card.getChannel().getChannelId());
                    ev.setDropCompleted(true);
                } catch (Exception ignored) {
                    ev.setDropCompleted(false);
                }
                ev.consume();
            }
        });
    }

    private void sendMoveMember(UUID targetUserId, UUID destinationChannelId) {
        try {
            App.getServices().installation().getWsClient().send(
                    WsMessageType.MOVE_MEMBER,
                    MoveMemberPayload.builder()
                            .targetUserId(targetUserId)
                            .destinationChannelId(destinationChannelId)
                            .build());
        } catch (Exception ignored) {}
    }

    private void saveChannelOrder() {
        pendingChannelOrder = orderedChannelCards.stream()
                .map(c -> c.getChannel() != null ? c.getChannel().getChannelId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (saveChannelOrderService.isRunning()) return;
        saveChannelOrderService.restart();
    }

    public void refreshPermissionUI() {
        Platform.runLater(() -> {
            menuButton.getItems().clear();
            buildMenuItems(menuButton);
        });
    }

    // ── Menu items ────────────────────────────────────────────────────────────

    private void buildMenuItems(MenuButton menu) {
        boolean canCreateChannels = App.getPermissionManager().has(Permission.CREATE_CHANNELS);
        boolean canEditServer = App.getPermissionManager().has(Permission.VIEW_SERVER_SETTINGS);

        MenuItem serverInfoItem = new MenuItem("Server Info", new FontIcon(MaterialDesignS.SERVER_OUTLINE));
        serverInfoItem.setOnAction(e -> App.showModal(new ServerInfoModal(server)));

        MenuItem notificationsItem = new MenuItem("Notifications", new FontIcon(MaterialDesignB.BELL_OUTLINE));
        notificationsItem.setOnAction(e -> App.showModal(new ServerInfoModal(server, "Notifications")));

        boolean isOnline = server.getStatus() == InstallationSummary.InstallationStatus.ONLINE;

        menu.getItems().addAll(serverInfoItem, notificationsItem);

        if (isOnline && App.getPermissionManager().has(Permission.INVITE_USERS)) {
            MenuItem inviteItem = new MenuItem("Invite to Server",
                    new FontIcon(MaterialDesignA.ACCOUNT_PLUS_OUTLINE));
            inviteItem.setOnAction(e -> App.showModal(new CreateInviteModal(server)));
            menu.getItems().add(0, inviteItem);
        }

        if (canCreateChannels) {
            MenuItem createChannelItem = new MenuItem("Create Channel",
                    new FontIcon(MaterialDesignP.PLUS));
            createChannelItem.setOnAction(e -> App.showModal(new CreateChannelModal(server)));
            MenuItem createDecorationItem = new MenuItem("Create Decoration",
                    new FontIcon(MaterialDesignV.VIEW_GRID_PLUS));
            createDecorationItem.setOnAction(e -> App.showModal(new CreateDecorationChannelModal(server)));
            menu.getItems().add(0, createChannelItem);
            menu.getItems().add(1, createDecorationItem);
            menu.getItems().add(2, new SeparatorMenuItem());
        }

        if (canEditServer) {
            MenuItem editServerItem = new MenuItem("Server Settings", new FontIcon(MaterialDesignC.COG));
            editServerItem.setOnAction(e -> App.showModal(new EditServerModal(server, true)));
            menu.getItems().addAll(new SeparatorMenuItem(), editServerItem);
        }

        MenuItem disconnectItem = new MenuItem("Disconnect", IconColorUtil.colored(MaterialDesignP.PHONE_OFF, "-color-danger-fg", 18));
        disconnectItem.setOnAction(e -> {
            UUID channelId = App.getWebrtcRoomClient().getCurrentChannelId();
            if (channelId != null) {
                ChannelCard.onServerForcedLeave(channelId);
                App.getWebrtcRoomClient().disconnectFromChannel();
            }
            App.getServices().disconnectInstallation();
            App.setCachedServerPage(null);
            App.changePage(App.getOrCreateHomePage());
            new CustomNotification("Disconnected", "You have disconnected from " + server.getServerName() + ".",
                    new FontIcon(MaterialDesignP.PHONE_OFF)).showNotification();
        });
        menu.getItems().addAll(new SeparatorMenuItem(), disconnectItem);
    }
}
