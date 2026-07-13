package komm.ui.sections;

import atlantafx.base.theme.Styles;
import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import io.github.b077as.emojifx.util.TextUtils;
import komm.App;
import komm.model.dto.summary.ConversationSummary;
import komm.ui.pages.DirectMessagePage;
import komm.ui.avatar.AvatarCache;
import komm.ui.avatar.AvatarColor;
import komm.ui.customnodes.CustomNotification;
import komm.ui.modals.ConfirmationModal;
import komm.ui.modals.StartConversationModal;
import komm.websocket.messages.payloads.DmReceivedPayload;
import komm.websocket.messages.payloads.MessageReceivedPayload;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

@Slf4j
public class DirectMessageSidebarSection extends VBox {

    private static final double WIDTH = 240.0;

    private final VBox conversationList = new VBox(2);
    private final List<ConversationSummary> conversations = new ArrayList<>();
    private final Set<UUID> unreadPartners = new HashSet<>();
    private BiConsumer<UUID, String> onConversationSelected;
    private UUID selectedPartnerId;

    private final Service<List<ConversationSummary>> loadService = new Service<>() {
        @Override
        protected Task<List<ConversationSummary>> createTask() {
            return new Task<>() {
                @Override
                protected List<ConversationSummary> call() throws Exception {
                    List<ConversationSummary> convos = App.getServices().hub()
                            .getDirectMessageService().getConversations();
                    if (convos != null && !convos.isEmpty()) {
                        List<UUID> ids = convos.stream()
                                .map(ConversationSummary::getPartnerId)
                                .distinct()
                                .toList();
                        App.getAvatarCache().resolveAll(ids).join();
                    }
                    return convos;
                }
            };
        }
    };

    private UUID pendingHidePartnerId;
    private final Service<Void> hideService = new Service<>() {
        @Override
        protected Task<Void> createTask() {
            final UUID pid = pendingHidePartnerId;
            return new Task<>() {
                @Override
                protected Void call() throws Exception {
                    App.getServices().hub().getDirectMessageService().hideConversation(pid);
                    return null;
                }
            };
        }
    };

    private UUID pendingDeleteAllPartnerId;
    private final Service<Void> deleteAllService = new Service<>() {
        @Override
        protected Task<Void> createTask() {
            final UUID pid = pendingDeleteAllPartnerId;
            return new Task<>() {
                @Override
                protected Void call() throws Exception {
                    App.getServices().hub().getDirectMessageService().deleteAllHistory(pid);
                    return null;
                }
            };
        }
    };

    public DirectMessageSidebarSection() {
        setPrefWidth(WIDTH);
        setMinWidth(WIDTH);
        setStyle("-fx-background-color: -color-bg-subtle;" +
                "-fx-border-color: transparent -color-border-default transparent transparent;" +
                "-fx-border-width: 0 1px 0 0;");

        // ── Header ────────────────────────────────────────────────────────────
        Label header = new Label("Chats");
        header.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");

        FontIcon newChatIcon = new FontIcon(Feather.PLUS);
        newChatIcon.setIconSize(10);
        Button newChatBtn = new Button(null, newChatIcon);
        newChatBtn.setFocusTraversable(false);
        newChatBtn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);
        newChatBtn.setStyle("-fx-padding: 4px;");
        newChatBtn.setTooltip(new javafx.scene.control.Tooltip("New message"));
        newChatBtn.setOnAction(e -> App.showModal(new StartConversationModal(onConversationSelected)));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox headerRow = new HBox(8, header, spacer, newChatBtn);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.setPadding(new Insets(10, 8, 8, 16));

        // ── Conversation list ─────────────────────────────────────────────────
        conversationList.setFillWidth(true);
        conversationList.setPadding(new Insets(0, 6, 8, 6));

        ScrollPane scroll = new ScrollPane(conversationList);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(headerRow, scroll);

        loadService.setOnSucceeded(e -> {
            conversations.clear();
            conversations.addAll(loadService.getValue());
            conversations.sort((a, b) -> {
                LocalDateTime at = a.getLastMessageSentAt();
                LocalDateTime bt = b.getLastMessageSentAt();
                if (at == null && bt == null) return 0;
                if (at == null) return 1;
                if (bt == null) return -1;
                return bt.compareTo(at);
            });
            // Seed unread from server-side flag, then layer on any in-session arrivals
            conversations.stream()
                    .filter(ConversationSummary::isHasUnread)
                    .map(ConversationSummary::getPartnerId)
                    .forEach(unreadPartners::add);
            unreadPartners.addAll(App.getAndClearPendingDmUnreadPartners());
            if (!unreadPartners.isEmpty()) App.setDmUnread(true);
            renderConversations();
        });
        loadService.setOnFailed(e ->
                log.error("Failed to load conversations: {}", loadService.getException().getMessage()));

        hideService.setOnSucceeded(e ->
                new CustomNotification("Conversation Deleted", "This conversation has been removed from your list.",
                        new FontIcon(MaterialDesignC.CHECK)).showNotification());
        hideService.setOnFailed(e -> {
            log.error("Failed to hide conversation", hideService.getException());
            new CustomNotification("Delete Failed", "Failed to delete conversation.",
                    new FontIcon(MaterialDesignD.DELETE_ALERT)).showNotification();
        });

        deleteAllService.setOnSucceeded(e ->
                new CustomNotification("History Deleted", "Conversation history has been cleared for both participants.",
                        new FontIcon(MaterialDesignC.CHECK)).showNotification());
        deleteAllService.setOnFailed(e -> {
            log.error("Failed to delete all history", deleteAllService.getException());
            new CustomNotification("Delete Failed", "Failed to delete conversation history.",
                    new FontIcon(MaterialDesignD.DELETE_ALERT)).showNotification();
        });
    }

    public void setOnConversationSelected(BiConsumer<UUID, String> callback) {
        this.onConversationSelected = callback;
    }

    public void reload() {
        if (loadService.isRunning()) return;
        loadService.restart();
    }

    public void setSelectedPartner(UUID partnerId) {
        this.selectedPartnerId = partnerId;
        boolean wasUnread = partnerId != null && unreadPartners.remove(partnerId);
        if (wasUnread) {
            if (unreadPartners.isEmpty()) App.setDmUnread(false);
            renderConversations();
            callMarkRead(partnerId);
        } else {
            refreshSelection();
        }
    }

    public void clearUnreadForSelected() {
        if (selectedPartnerId == null) return;
        boolean wasUnread = unreadPartners.remove(selectedPartnerId);
        if (wasUnread) {
            if (unreadPartners.isEmpty()) App.setDmUnread(false);
            renderConversations();
            callMarkRead(selectedPartnerId);
        }
    }

    private void callMarkRead(UUID partnerId) {
        Thread.ofVirtual().start(() -> {
            try {
                App.getServices().hub().getDirectMessageService().markConversationRead(partnerId);
            } catch (Exception e) {
                log.warn("Failed to mark conversation as read: {}", e.getMessage());
            }
        });
    }

    public void onMessageReceived(DmReceivedPayload payload) {
        UUID myId = App.getUser() != null ? App.getUser().getUserId() : null;
        if (myId == null) return;
        UUID partnerId = myId.equals(payload.getSenderId()) ? payload.getRecipientId() : payload.getSenderId();

        // Track unread: mark if not the currently-open conversation (or page not visible)
        if (!myId.equals(payload.getSenderId())) {
            DirectMessagePage dmPage = App.getCachedDirectMessagePage();
            boolean pageVisible = dmPage != null && dmPage.getScene() != null;
            if (!pageVisible || !partnerId.equals(selectedPartnerId)) {
                unreadPartners.add(partnerId);
            }
        }

        boolean exists = conversations.stream().anyMatch(c -> c.getPartnerId().equals(partnerId));
        if (!exists) {
            // New conversation — reload the full list
            reload();
            return;
        }

        // Update last message preview in existing conversation
        for (ConversationSummary c : conversations) {
            if (c.getPartnerId().equals(partnerId)) {
                String preview = payload.getMessageType() != null
                        && payload.getMessageType().name().equals("GIF") ? "GIF"
                        : payload.getMessageType() != null
                        && payload.getMessageType().name().equals("CODE") ? "Code snippet"
                        : (payload.isHasAttachments() ? "Attachment" : truncate(payload.getContent(), 60));
                c.setLastMessageContent(preview);
                c.setLastMessageSentAt(payload.getSentAt());
                c.setLastMessageIsOwn(myId.equals(payload.getSenderId()));
                break;
            }
        }

        // Move this conversation to top
        conversations.sort((a, b) -> {
            LocalDateTime at = a.getLastMessageSentAt();
            LocalDateTime bt = b.getLastMessageSentAt();
            if (at == null && bt == null) return 0;
            if (at == null) return 1;
            if (bt == null) return -1;
            return bt.compareTo(at);
        });
        renderConversations();
    }

    // ── Ensure a conversation row exists (used when navigating from friends) ──

    public void ensureConversation(UUID partnerId, String partnerUsername) {
        boolean exists = conversations.stream().anyMatch(c -> c.getPartnerId().equals(partnerId));
        if (!exists) {
            // Add a pending row (no messages yet — won't persist until a message is sent)
            conversations.add(0, ConversationSummary.builder()
                    .partnerId(partnerId)
                    .partnerUsername(partnerUsername)
                    .lastMessageContent("")
                    .lastMessageSentAt(null)
                    .lastMessageIsOwn(false)
                    .build());
            renderConversations();
        }
    }

    // ── Remove a pending conversation if no message was sent ──────────────────

    public void removePendingConversationIfEmpty(UUID partnerId) {
        conversations.removeIf(c -> c.getPartnerId().equals(partnerId)
                && c.getLastMessageSentAt() == null
                && !partnerId.equals(selectedPartnerId));
        renderConversations();
    }

    // ── Update preview after a message deletion ───────────────────────────────

    public void onMessageDeleted(UUID partnerId, MessageReceivedPayload newLast) {
        UUID myId = App.getUser() != null ? App.getUser().getUserId() : null;
        for (ConversationSummary c : conversations) {
            if (!c.getPartnerId().equals(partnerId)) continue;
            if (newLast != null) {
                String preview = newLast.getMessageType() == MessageReceivedPayload.MessageType.GIF ? "GIF"
                        : newLast.getMessageType() == MessageReceivedPayload.MessageType.CODE ? "Code snippet"
                        : newLast.isHasAttachments() ? "Attachment"
                        : truncate(newLast.getContent(), 60);
                c.setLastMessageContent(preview);
                c.setLastMessageSentAt(newLast.getSentAt());
                c.setLastMessageIsOwn(myId != null && myId.equals(newLast.getSenderId()));
            } else {
                c.setLastMessageContent("");
                c.setLastMessageSentAt(null);
            }
            break;
        }
        renderConversations();
    }

    // ── Remove a conversation from the sidebar ────────────────────────────────

    public void removeConversation(UUID partnerId) {
        conversations.removeIf(c -> c.getPartnerId().equals(partnerId));
        if (partnerId.equals(selectedPartnerId)) selectedPartnerId = null;
        // Drop any unread state for this partner — if it was the last unread
        // conversation, clear the global DM badge (messages tab + home icon dots).
        if (unreadPartners.remove(partnerId) && unreadPartners.isEmpty()) {
            App.setDmUnread(false);
        }
        renderConversations();
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private void renderConversations() {
        Platform.runLater(() -> {
            conversationList.getChildren().clear();
            if (conversations.isEmpty()) {
                Label empty = new Label("No messages yet");
                empty.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");
                empty.setPadding(new Insets(16, 12, 16, 12));
                conversationList.getChildren().add(empty);
                return;
            }
            for (ConversationSummary c : conversations) conversationList.getChildren().add(buildRow(c));
        });
    }

    private VBox buildRow(ConversationSummary c) {
        boolean selected = c.getPartnerId().equals(selectedPartnerId);

        StackPane avatar = buildAvatar(c.getPartnerId(), 36);

        Label nameLbl = new Label(c.getPartnerUsername() != null ? c.getPartnerUsername() : "…");
        nameLbl.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");
        nameLbl.setMaxWidth(Double.MAX_VALUE);

        String previewText = truncate(c.getLastMessageContent() != null ? c.getLastMessageContent() : "", 20);
        HBox previewBox = new HBox(2);
        previewBox.setAlignment(Pos.CENTER_LEFT);
        previewBox.setMaxWidth(150);
        previewBox.setMinHeight(16);
        previewBox.setPrefHeight(16);
        previewBox.setMouseTransparent(true);
        var previewNodes = TextUtils.convertToTextAndImageNodes(previewText, 11);
        for (var node : previewNodes) {
            if (node instanceof Text t) t.setStyle("-fx-fill: -color-fg-muted; -fx-font-size: 11px;");
        }
        previewBox.getChildren().addAll(previewNodes);

        VBox textBox = new VBox(1, nameLbl, previewBox);
        textBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        Label timeLbl = new Label();
        if (c.getLastMessageSentAt() != null) {
            timeLbl.setText(c.getLastMessageSentAt().format(DateTimeFormatter.ofPattern("HH:mm")));
        }
        timeLbl.setStyle("-fx-font-size: 10px; -fx-text-fill: -color-fg-muted;");

        Circle unreadDot = new Circle(4);
        unreadDot.setStyle("-fx-fill: -color-accent-emphasis;");
        unreadDot.setMouseTransparent(true);
        unreadDot.setVisible(unreadPartners.contains(c.getPartnerId()));

        VBox rightBox = new VBox(2);
        rightBox.setAlignment(Pos.CENTER_RIGHT);
        rightBox.getChildren().addAll(timeLbl, unreadDot);

        HBox inner = new HBox(8, avatar, textBox, rightBox);
        inner.setAlignment(Pos.CENTER_LEFT);
        inner.setPadding(new Insets(8, 8, 8, 8));

        VBox row = new VBox(inner);
        row.setFillWidth(true);
        applyRowStyle(row, selected);

        row.setOnMouseEntered(e -> { if (!c.getPartnerId().equals(selectedPartnerId)) applyRowStyle(row, true); });
        row.setOnMouseExited(e -> { if (!c.getPartnerId().equals(selectedPartnerId)) applyRowStyle(row, false); });
        row.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && onConversationSelected != null)
                onConversationSelected.accept(c.getPartnerId(), c.getPartnerUsername());
        });
        row.setUserData(c.getPartnerId());

        String displayName = c.getPartnerUsername() != null ? c.getPartnerUsername() : "this user";

        MenuItem deleteForMeItem = new MenuItem("Delete for me");
        deleteForMeItem.setGraphic(new FontIcon(MaterialDesignD.DELETE_OUTLINE));
        deleteForMeItem.setOnAction(e -> App.showModal(new ConfirmationModal(
                "Delete for me",
                "This will remove the conversation with @" + displayName + " from your view only. They will still see all messages.",
                new FontIcon(MaterialDesignD.DELETE_OUTLINE),
                () -> doHideConversation(c.getPartnerId()))));

        MenuItem deleteAllItem = new MenuItem("Delete all history");
        deleteAllItem.setGraphic(new FontIcon(MaterialDesignD.DELETE_FOREVER));
        deleteAllItem.setOnAction(e -> App.showModal(new ConfirmationModal(
                "Delete all history",
                "This will permanently erase every message between you and @" + displayName + " for both of you. This cannot be undone.",
                new FontIcon(MaterialDesignD.DELETE_FOREVER),
                () -> doDeleteAllHistory(c.getPartnerId()))));

        ContextMenu contextMenu = new ContextMenu(deleteForMeItem, deleteAllItem);
        row.setOnContextMenuRequested(e -> contextMenu.show(row, e.getScreenX(), e.getScreenY()));

        // Resolve avatar and name async
        App.getAvatarCache().resolve(c.getPartnerId()).thenAccept(cu -> {
            if (cu == null) return;
            Platform.runLater(() -> {
                nameLbl.setText(cu.username());
                refreshAvatar(avatar, cu, 36);
            });
        });

        return row;
    }

    private void applyRowStyle(VBox row, boolean highlighted) {
        row.setStyle(highlighted
                ? "-fx-background-color: -color-accent-subtle; -fx-background-radius: 8px; -fx-cursor: hand;"
                : "-fx-background-color: transparent; -fx-background-radius: 8px; -fx-cursor: hand;");
    }

    private void refreshSelection() {
        conversationList.getChildren().forEach(node -> {
            if (node instanceof VBox row && row.getUserData() instanceof UUID id) {
                applyRowStyle(row, id.equals(selectedPartnerId));
            }
        });
    }

    // ── Avatar helpers ────────────────────────────────────────────────────────

    private StackPane buildAvatar(UUID userId, double size) {
        StackPane pane = new StackPane();
        pane.setPrefSize(size, size);
        pane.setMinSize(size, size);
        pane.setMaxSize(size, size);
        pane.setStyle("-fx-background-color: " + AvatarColor.forName(null) + ";" +
                "-fx-background-radius: " + (size / 2) + "px;");

        if (userId != null) {
            AvatarCache.CachedUser cached = App.getAvatarCache().getIfPresent(userId);
            if (cached != null) fillAvatar(pane, cached, size);
            else {
                String letter = "?";
                Text t = new Text(letter);
                t.setFill(Color.WHITE);
                t.setFont(Font.font("System", FontWeight.BOLD, size / 2.5));
                pane.getChildren().setAll(t);
            }
        }
        return pane;
    }

    private void refreshAvatar(StackPane pane, AvatarCache.CachedUser cu, double size) {
        fillAvatar(pane, cu, size);
    }

    private void fillAvatar(StackPane pane, AvatarCache.CachedUser cu, double size) {
        pane.getChildren().clear();
        if (cu.avatar() != null && cu.avatar().length > 0) {
            try {
                Image img = new Image(new java.io.ByteArrayInputStream(cu.avatar()), size, size, true, true);
                ImageView iv = new ImageView(img);
                iv.setFitWidth(size);
                iv.setFitHeight(size);
                iv.setPreserveRatio(false);
                iv.setClip(new Circle(size / 2, size / 2, size / 2));
                pane.getChildren().add(iv);
                pane.setStyle("-fx-background-color: transparent; -fx-background-radius: " + (size / 2) + "px;");
                return;
            } catch (Exception ignored) {}
        }
        String letter = (cu.username() != null && !cu.username().isEmpty())
                ? String.valueOf(cu.username().charAt(0)).toUpperCase() : "?";
        Text t = new Text(letter);
        t.setFill(Color.WHITE);
        t.setFont(Font.font("System", FontWeight.BOLD, size / 2.5));
        pane.getChildren().add(t);
        pane.setStyle("-fx-background-color: " + AvatarColor.forName(cu.username()) + ";" +
                "-fx-background-radius: " + (size / 2) + "px;");
    }

    private void doHideConversation(UUID partnerId) {
        if (hideService.isRunning()) return;
        pendingHidePartnerId = partnerId;
        hideService.restart();
    }

    private void doDeleteAllHistory(UUID partnerId) {
        if (deleteAllService.isRunning()) return;
        pendingDeleteAllPartnerId = partnerId;
        deleteAllService.restart();
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
