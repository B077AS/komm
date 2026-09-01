package komm.ui.chat;

import io.github.b077as.emojifx.EmojiData;
import io.github.b077as.emojifx.util.TextUtils;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import komm.App;
import komm.model.dto.summary.GifResult;
import komm.ui.attachments.AttachmentBarSlot;
import komm.ui.attachments.AttachmentDisplayBuilder;
import komm.ui.chat.virtual.VirtualMessageList;
import komm.ui.code.CodeDetector;
import komm.ui.code.CodeLanguage;
import komm.ui.customnodes.CustomNotification;
import komm.ui.modals.CodeMessageModal;
import komm.ui.emojis.EmojiMessageContent;
import komm.ui.emojis.EmojiMessageItem;
import komm.ui.emojis.EmojiReactionBar;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import atlantafx.base.theme.Styles;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

import java.text.BreakIterator;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The DM conversation view. The message surface is a {@link VirtualMessageList}
 * (Flowless-backed): only the message subtrees near the viewport are ever in the
 * scene graph, so memory does not grow with conversation length. Bottom-anchoring,
 * follow-on-new-message, near-top history prefetch, and page-navigation anchoring
 * are all handled inside {@code VirtualMessageList}; this class only feeds it
 * payloads and keeps the model in step for cells that are currently scrolled out.
 */
@Slf4j
public class DmChatSection extends VBox {

    private static final int PAGE_SIZE = 50;
    private static final double REPLY_BAR_HEIGHT = 53.0;
    private static final double REPLY_ANIM_MS = 180.0;
    private static final double INPUT_H_PAD = 16.0;

    // ── Reply bar ─────────────────────────────────────────────────────────────
    private VBox replyBarSlot;
    private Rectangle replyBarClip;
    private Timeline replyTimeline;
    private MessageReceivedPayload currentReplyTarget;

    // ── Attachment bar ────────────────────────────────────────────────────────
    private AttachmentBarSlot attachmentBarSlot;

    // ── Layout ────────────────────────────────────────────────────────────────
    private DmChatHeader chatHeader;
    private VBox chatView;
    private StackPane scrollPaneWrapper;
    private Button scrollToBottomBtn;
    private VirtualMessageList virtualList;
    private MessageInputBox messageInputBox;
    private HBox typingRow;
    private VBox welcomeView;

    // ── Drag overlay ──────────────────────────────────────────────────────────
    private StackPane dragOverlay;
    private boolean dragOverlayVisible = false;
    private PauseTransition dragHideDelay;

    // ── State ─────────────────────────────────────────────────────────────────
    @Getter
    private UUID activeConversationPartnerId;
    private LocalDateTime oldestMessageTimestamp;
    private volatile boolean isFetchingHistory = false;
    private boolean allHistoryLoaded = false;
    private boolean isAtBottom = true;

    // ── Message map (currently-realized cells only) ───────────────────────────
    private final Map<UUID, EmojiMessageItem> messageItemMap = new LinkedHashMap<>();
    private final Set<UUID> activeEditItems = new HashSet<>();

    // ── Background services ───────────────────────────────────────────────────
    private UUID pendingFetchPartnerId;
    private LocalDateTime pendingFetchBefore;
    private boolean pendingFetchIsInitialLoad;

    private record FetchResult(UUID partnerId, boolean isInitialLoad,
                                List<DmReceivedPayload> messages) {}

    private final Service<FetchResult> fetchService = new Service<>() {
        @Override
        protected Task<FetchResult> createTask() {
            final UUID pid = pendingFetchPartnerId;
            final LocalDateTime before = pendingFetchBefore;
            final boolean initial = pendingFetchIsInitialLoad;
            return new Task<>() {
                @Override
                protected FetchResult call() throws Exception {
                    List<DmReceivedPayload> messages = App.getServices().hub()
                            .getDirectMessageService().getMessages(pid, before, PAGE_SIZE);
                    if (messages != null && !messages.isEmpty()) {
                        Set<UUID> senderIds = messages.stream()
                                .flatMap(m -> Stream.of(m.getSenderId(), m.getReplyToSenderId()))
                                .filter(Objects::nonNull)
                                .collect(Collectors.toSet());
                        App.getAvatarCache().resolveAll(senderIds).join();
                        App.getAvatarCache().preloadImages(senderIds);
                    }
                    return new FetchResult(pid, initial,
                            messages != null ? messages : List.of());
                }
            };
        }
    };

    private String pendingReactionMessageId;
    private String pendingReactionEmoji;

    private final Service<Void> reactionService = new Service<>() {
        @Override
        protected Task<Void> createTask() {
            final UUID mid = UUID.fromString(pendingReactionMessageId);
            final String emoji = pendingReactionEmoji;
            final boolean isAdd = pendingReactionIsAdd;
            return new Task<>() {
                @Override
                protected Void call() throws Exception {
                    if (isAdd) App.getServices().hub().getDirectMessageService().addReaction(mid, emoji);
                    else App.getServices().hub().getDirectMessageService().removeReaction(mid, emoji);
                    return null;
                }
            };
        }
    };
    private boolean pendingReactionIsAdd;

    // ── Constructor ───────────────────────────────────────────────────────────

    public DmChatSection() {
        setStyle("-fx-background-color: -color-bg-default;");
        VBox.setVgrow(this, Priority.ALWAYS);

        chatHeader = new DmChatHeader();
        chatHeader.setVisible(false);
        chatHeader.setManaged(false);
        chatView = createChatView();
        VBox.setVgrow(chatView, Priority.ALWAYS);

        fetchService.setOnSucceeded(e -> onFetchSucceeded());
        fetchService.setOnFailed(e -> onFetchFailed());
        reactionService.setOnFailed(e -> log.error("DM reaction failed", reactionService.getException()));

        getChildren().addAll(chatHeader, chatView);
        installDragDrop();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setActiveConversation(UUID partnerId, String partnerUsername) {
        this.activeConversationPartnerId = partnerId;
        this.oldestMessageTimestamp = null;
        this.allHistoryLoaded = false;
        this.isFetchingHistory = false;
        this.isAtBottom = true;

        Platform.runLater(() -> {
            virtualList.clear();
            messageItemMap.clear();
            activeEditItems.clear();
            messageInputBox.clearReply();
            currentReplyTarget = null;
            collapseReplyBarInstant();
            attachmentBarSlot.clear();
            showChat();
            if (scrollToBottomBtn != null) scrollToBottomBtn.setVisible(false);

            chatHeader.setPartner(partnerId, partnerUsername);
            messageInputBox.setPromptText("Message @" + partnerUsername);

            fetchPage(partnerId, null, true);
        });
    }

    public void onConversationCleared(UUID partnerId) {
        if (activeConversationPartnerId != null && activeConversationPartnerId.equals(partnerId)) {
            clearAndShowWelcome();
        }
    }

    public void clearAndShowWelcome() {
        Platform.runLater(() -> {
            virtualList.clear();
            messageItemMap.clear();
            activeConversationPartnerId = null;
            messageInputBox.clearReply();
            attachmentBarSlot.clear();
            showWelcome();
        });
    }

    // ── Inbound message events ────────────────────────────────────────────────

    public void addMessage(DmReceivedPayload payload) {
        Platform.runLater(() -> {
            if (activeConversationPartnerId == null) return;
            UUID partner = getPartnerFromPayload(payload);
            if (!activeConversationPartnerId.equals(partner)) return;

            virtualList.appendMessage(adapt(payload));
        });
    }

    public boolean isActive(UUID partnerId) {
        return partnerId != null && partnerId.equals(activeConversationPartnerId);
    }

    public void removeMessage(UUID messageId, UUID conversationPartnerId) {
        removeMessage(messageId, conversationPartnerId, null);
    }

    public void removeMessage(UUID messageId, UUID conversationPartnerId,
                              java.util.function.Consumer<MessageReceivedPayload> onLastChanged) {
        Platform.runLater(() -> {
            if (activeConversationPartnerId == null
                    || !activeConversationPartnerId.equals(conversationPartnerId)) return;
            if (!virtualList.containsId(messageId)) return;

            boolean wasLast = virtualList.isLastId(messageId);
            virtualList.removeById(messageId);
            messageItemMap.remove(messageId);

            if (currentReplyTarget != null && currentReplyTarget.getMessageId().equals(messageId)) {
                currentReplyTarget = null;
                collapseReplyBar();
            }
            if (wasLast && onLastChanged != null) onLastChanged.accept(virtualList.lastPayload());
        });
    }

    public void updateMessage(DmEditedPayload p) {
        Platform.runLater(() -> {
            if (activeConversationPartnerId == null
                    || !activeConversationPartnerId.equals(p.getConversationPartnerId())) return;

            MessageReceivedPayload target = virtualList.payload(p.getMessageId());
            if (target == null
                    || target.getMessageType() == MessageReceivedPayload.MessageType.GIF) return;

            target.setContent(p.getContent());
            target.setEdited(true);
            if (target.getMessageType() == MessageReceivedPayload.MessageType.CODE
                    && p.getCodeLanguage() != null) {
                target.setCodeLanguage(p.getCodeLanguage());
            }
            virtualList.refreshId(p.getMessageId());
        });
    }

    public void addReaction(UUID messageId, UUID conversationPartnerId, String emojiChar, boolean isSelf) {
        Platform.runLater(() -> {
            if (activeConversationPartnerId == null
                    || !activeConversationPartnerId.equals(conversationPartnerId)) return;
            applyReactionToModel(virtualList.payload(messageId), emojiChar, isSelf, true);
            EmojiMessageItem live = messageItemMap.get(messageId);
            if (live != null) live.getBubble().getReactionBar().incrementReaction(emojiChar, isSelf);
        });
    }

    public void removeReaction(UUID messageId, UUID conversationPartnerId, String emojiChar, boolean isSelf) {
        Platform.runLater(() -> {
            if (activeConversationPartnerId == null
                    || !activeConversationPartnerId.equals(conversationPartnerId)) return;
            applyReactionToModel(virtualList.payload(messageId), emojiChar, isSelf, false);
            EmojiMessageItem live = messageItemMap.get(messageId);
            if (live != null) live.getBubble().getReactionBar().decrementReaction(emojiChar, isSelf);
        });
    }

    /**
     * Keeps a message payload's reaction list in step with a reaction event so a
     * virtualized cell that is currently off-screen shows the right reactions when
     * it is next rebuilt. Mirrors the grouping logic in {@link #buildMessageItem}.
     */
    private void applyReactionToModel(MessageReceivedPayload p, String emojiChar, boolean isSelf, boolean add) {
        if (p == null) return;
        List<ChannelMessageReactionAdd> list = p.getReactions();
        if (list == null) {
            list = new ArrayList<>();
            p.setReactions(list);
        }
        UUID myId = App.getUser() != null ? App.getUser().getUserId() : null;
        if (add) {
            list.add(ChannelMessageReactionAdd.builder()
                    .messageId(p.getMessageId())
                    .userId(isSelf ? myId : null)
                    .emoji(emojiChar)
                    .build());
            return;
        }
        for (int i = list.size() - 1; i >= 0; i--) {
            ChannelMessageReactionAdd r = list.get(i);
            boolean rSelf = myId != null && myId.equals(r.getUserId());
            if (emojiChar.equals(r.getEmoji()) && rSelf == isSelf) {
                list.remove(i);
                return;
            }
        }
        for (int i = list.size() - 1; i >= 0; i--) {
            if (emojiChar.equals(list.get(i).getEmoji())) {
                list.remove(i);
                return;
            }
        }
    }

    public void onPartnerTyping(DmTypingPayload payload) {
        if (activeConversationPartnerId == null
                || !activeConversationPartnerId.equals(payload.getSenderId())) return;
        var cached = App.getAvatarCache().getIfPresent(payload.getSenderId());
        if (cached != null) {
            messageInputBox.registerTyping(payload.getSenderId(), cached.username());
        } else {
            App.getAvatarCache().resolve(payload.getSenderId()).thenAcceptAsync(user ->
                    Platform.runLater(() ->
                            messageInputBox.registerTyping(payload.getSenderId(),
                                    user != null ? user.username() : "…")));
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private MessageReceivedPayload adapt(DmReceivedPayload dm) {
        // Mutable on purpose: applyReactionToModel() mutates this list in place so a
        // virtualized cell rebuilt after a reaction event shows the right reactions.
        List<ChannelMessageReactionAdd> reactions = dm.getReactions() == null ? null
                : dm.getReactions().stream()
                        .map(r -> ChannelMessageReactionAdd.builder()
                                .messageId(r.getMessageId())
                                .userId(r.getUserId())
                                .emoji(r.getEmoji())
                                .build())
                        .collect(Collectors.toCollection(ArrayList::new));
        return MessageReceivedPayload.builder()
                .messageId(dm.getMessageId())
                .senderId(dm.getSenderId())
                .channelId(activeConversationPartnerId)
                .content(dm.getContent())
                .sentAt(dm.getSentAt())
                .edited(dm.isEdited())
                .repliedToId(dm.getRepliedToId())
                .replyToSenderId(dm.getReplyToSenderId())
                .replyToContent(dm.getReplyToContent())
                .replyToMessageType(dm.getReplyToMessageType())
                .replyToHasAttachments(dm.isReplyToHasAttachments())
                .replyToFileName(dm.getReplyToFileName())
                .replyToFileType(dm.getReplyToFileType())
                .hasAttachments(dm.isHasAttachments())
                .fileName(dm.getFileName())
                .fileType(dm.getFileType())
                .file64(dm.getFile64())
                .fileSize(dm.getFileSize())
                .reactions(reactions)
                .messageType(dm.getMessageType())
                .codeLanguage(dm.getCodeLanguage())
                .build();
    }

    private UUID getPartnerFromPayload(DmReceivedPayload p) {
        UUID myId = App.getUser() != null ? App.getUser().getUserId() : null;
        if (myId == null) return null;
        return myId.equals(p.getSenderId()) ? p.getRecipientId() : p.getSenderId();
    }

    // ── Visibility ────────────────────────────────────────────────────────────

    private void showWelcome() {
        chatHeader.setVisible(false);
        chatHeader.setManaged(false);
        welcomeView.setVisible(true);
        welcomeView.setManaged(true);
        scrollPaneWrapper.setVisible(false);
        scrollPaneWrapper.setManaged(false);
        typingRow.setManaged(false);
        replyBarSlot.setVisible(false);
        replyBarSlot.setManaged(false);
        attachmentBarSlot.setVisible(false);
        attachmentBarSlot.setManaged(false);
        messageInputBox.setVisible(false);
        messageInputBox.setManaged(false);
    }

    private void showChat() {
        chatHeader.setVisible(true);
        chatHeader.setManaged(true);
        welcomeView.setVisible(false);
        welcomeView.setManaged(false);
        scrollPaneWrapper.setVisible(true);
        scrollPaneWrapper.setManaged(true);
        typingRow.setManaged(true);
        replyBarSlot.setVisible(true);
        replyBarSlot.setManaged(true);
        messageInputBox.setVisible(true);
        messageInputBox.setManaged(true);
        Platform.runLater(messageInputBox::focusInput);
    }

    // ── History fetch ─────────────────────────────────────────────────────────

    private void fetchOlderMessages() {
        if (activeConversationPartnerId == null || isFetchingHistory || allHistoryLoaded) return;
        fetchPage(activeConversationPartnerId, oldestMessageTimestamp, false);
    }

    private void fetchPage(UUID partnerId, LocalDateTime before, boolean isInitialLoad) {
        isFetchingHistory = true;
        pendingFetchPartnerId = partnerId;
        pendingFetchBefore = before;
        pendingFetchIsInitialLoad = isInitialLoad;
        fetchService.restart();
    }

    private void onFetchSucceeded() {
        FetchResult result = fetchService.getValue();

        // result is null when the service was restarted before this task's callback fired —
        // a newer fetch is already in flight, so leave all state untouched.
        if (result == null) return;

        // Stale result: belongs to a different partner than the one currently open.
        // Don't touch isFetchingHistory — the real fetch for the current partner is still running.
        if (!result.partnerId().equals(activeConversationPartnerId)) return;

        List<DmReceivedPayload> page = result.messages();
        if (page.isEmpty()) {
            allHistoryLoaded = true;
            isFetchingHistory = false;
            return;
        }

        Collections.reverse(page);
        List<MessageReceivedPayload> adapted = page.stream().map(this::adapt).toList();

        if (result.isInitialLoad()) virtualList.setInitial(adapted);
        else virtualList.prepend(adapted);

        oldestMessageTimestamp = page.get(0).getSentAt();
        if (page.size() < PAGE_SIZE) allHistoryLoaded = true;
        isFetchingHistory = false;
    }

    private void onFetchFailed() {
        log.error("Failed to fetch DM messages", fetchService.getException());
        isFetchingHistory = false;
    }

    // ── Scroll-to-bottom button ───────────────────────────────────────────────

    private void updateScrollToBottomBtn() {
        if (scrollToBottomBtn == null) return;
        boolean show = !isAtBottom;
        if (show == scrollToBottomBtn.isVisible()) return;
        if (show) {
            scrollToBottomBtn.setVisible(true);
            FadeTransition ft = new FadeTransition(Duration.millis(120), scrollToBottomBtn);
            ft.setFromValue(0);
            ft.setToValue(0.4);
            ft.play();
        } else {
            FadeTransition ft = new FadeTransition(Duration.millis(120), scrollToBottomBtn);
            ft.setFromValue(scrollToBottomBtn.getOpacity());
            ft.setToValue(0);
            ft.setOnFinished(e -> scrollToBottomBtn.setVisible(false));
            ft.play();
        }
    }

    // ── Drag-and-drop ─────────────────────────────────────────────────────────

    private void installDragDrop() {
        dragHideDelay = new PauseTransition(Duration.millis(80));
        dragHideDelay.setOnFinished(e -> hideDragOverlay());

        setOnDragOver(e -> {
            if (e.getDragboard().hasFiles() && activeConversationPartnerId != null) {
                e.acceptTransferModes(TransferMode.COPY);
                dragHideDelay.stop();
                showDragOverlay();
            }
            e.consume();
        });
        setOnDragExited(e -> { dragHideDelay.playFromStart(); e.consume(); });
        setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            boolean success = false;
            if (db.hasFiles() && activeConversationPartnerId != null) {
                dragHideDelay.stop();
                hideDragOverlay();
                messageInputBox.addFilesToAttachmentBar(db.getFiles());
                success = true;
            } else {
                dragHideDelay.stop();
                hideDragOverlay();
            }
            e.setDropCompleted(success);
            e.consume();
        });
    }

    private void showDragOverlay() {
        if (dragOverlayVisible) return;
        dragOverlayVisible = true;
        if (dragOverlay == null) {
            FontIcon icon = new FontIcon(Feather.UPLOAD);
            icon.getStyleClass().add("custom-icon-35-emphasis");
            Label title = new Label("Drop your files");
            title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");
            Label subtitle = new Label("Files will be added to your message");
            subtitle.setStyle("-fx-font-size: 13px; -fx-text-fill: -color-fg-muted;");
            VBox textBlock = new VBox(4, title, subtitle);
            textBlock.setAlignment(Pos.CENTER);
            VBox card = new VBox(20, icon, textBlock);
            card.setAlignment(Pos.CENTER);
            card.setPadding(new Insets(48, 72, 48, 72));
            card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            card.setStyle("-fx-background-color: -color-bg-default; -fx-background-radius: 16; -fx-border-color: -color-accent-emphasis; -fx-border-width: 2; -fx-border-radius: 16; -fx-border-style: dashed;");
            dragOverlay = new StackPane(card);
            dragOverlay.setAlignment(Pos.CENTER);
            dragOverlay.setStyle("-fx-background-color: rgba(10, 8, 8, 0.75);");
            dragOverlay.setMouseTransparent(true);
        }
        if (!App.getStackPane().getChildren().contains(dragOverlay)) {
            App.getStackPane().getChildren().add(dragOverlay);
            dragOverlay.setOpacity(0);
            FadeTransition fadeIn = new FadeTransition(Duration.millis(120), dragOverlay);
            fadeIn.setFromValue(0); fadeIn.setToValue(1); fadeIn.play();
            Node card = dragOverlay.getChildren().get(0);
            card.setScaleX(0.93); card.setScaleY(0.93);
            ScaleTransition scaleIn = new ScaleTransition(Duration.millis(180), card);
            scaleIn.setToX(1.0); scaleIn.setToY(1.0);
            scaleIn.setInterpolator(Interpolator.SPLINE(0.2, 0, 0.2, 1));
            scaleIn.play();
        }
    }

    private void hideDragOverlay() {
        if (!dragOverlayVisible) return;
        dragOverlayVisible = false;
        FadeTransition fadeOut = new FadeTransition(Duration.millis(120), dragOverlay);
        fadeOut.setFromValue(1); fadeOut.setToValue(0);
        fadeOut.setOnFinished(e -> App.getStackPane().getChildren().remove(dragOverlay));
        fadeOut.play();
    }

    // ── Node builder (VirtualMessageList cell factory) ────────────────────────

    private EmojiMessageItem buildMessageItem(MessageReceivedPayload msg) {
        boolean isOwn = msg.getSenderId() != null
                && msg.getSenderId().equals(App.getUser().getUserId());
        EmojiMessageItem item = EmojiMessageItem.ofDm(msg, isOwn);

        EmojiReactionBar bar = item.getBubble().detachReactionBar();
        VBox.setMargin(bar, new Insets(0, 0, 0, 0));

        if (msg.isHasAttachments()) {
            Node attachNode = AttachmentDisplayBuilder.buildAttachmentDisplay(msg);
            if (attachNode != null) {
                VBox.setMargin(attachNode, new Insets(4, 0, 0, 0));
                item.getContentCol().getChildren().add(attachNode);
            }
        }

        item.getContentCol().getChildren().add(bar);

        item.getBubble().setOnDelete(() -> sendDeleteMessage(msg.getMessageId()));
        item.getBubble().setOnReply(bubble -> openReplyBar(msg));
        item.getBubble().setOnAllPopupsClosed(item::onAllPopupsClosed);

        boolean canEdit = isOwn && msg.getMessageType() != MessageReceivedPayload.MessageType.GIF;
        if (canEdit) {
            item.getBubble().setEditVisible(true);
            item.getBubble().setOnEdit(() -> startEditMode(msg, item));
        }

        item.getBubble().setOnAddReaction((bubble, emoji) ->
                handleAddReactionRequest(msg.getMessageId(), bubble, item, emoji));

        if (msg.getReactions() != null && !msg.getReactions().isEmpty()) {
            Map<String, long[]> grouped = new LinkedHashMap<>();
            for (ChannelMessageReactionAdd r : msg.getReactions()) {
                grouped.compute(r.getEmoji(), (k, v) -> {
                    if (v == null) v = new long[]{0, 0};
                    v[0]++;
                    if (App.getUser().getUserId().equals(r.getUserId())) v[1] = 1;
                    return v;
                });
            }
            grouped.forEach((emojiUnicode, data) ->
                    EmojiData.emojiFromCodepoints(emojiUnicode).ifPresent(emoji ->
                            bar.setReaction(emoji.character(), (int) data[0], data[1] == 1)));
            bar.rebuildWithoutAnimation();
        }

        bar.setOnReactionAdded(emojiChar ->
                EmojiData.emojiFromUnicodeString(emojiChar).ifPresent(emoji ->
                        sendAddReactionMessage(msg.getMessageId(), emoji)));
        bar.setOnReactionRemoved(emojiChar ->
                EmojiData.emojiFromUnicodeString(emojiChar).ifPresent(emoji ->
                        sendRemoveReactionMessage(msg.getMessageId(), emoji)));
        bar.setOnPickerRequested(coords -> {
            messageInputBox.getEmojiPicker().setOnEmojiSelected(emoji -> {
                sendAddReactionMessage(msg.getMessageId(), emoji);
                messageInputBox.getEmojiPicker().hide();
            });
            messageInputBox.getEmojiPicker().show(
                    item.getScene().getWindow(), coords[0], coords[1]);
        });

        messageItemMap.put(msg.getMessageId(), item);
        return item;
    }

    private void handleAddReactionRequest(UUID messageId, EmojiMessageContent bubble,
                                           EmojiMessageItem item, io.github.b077as.emojifx.Emoji emoji) {
        if (emoji == null) {
            messageInputBox.getEmojiPicker().setOnEmojiSelected(picked -> {
                sendAddReactionMessage(messageId, picked);
                messageInputBox.getEmojiPicker().hide();
            });
            javafx.geometry.Bounds b = bubble.localToScreen(bubble.getBoundsInLocal());
            if (b != null) {
                double x = b.getMinX();
                double y = b.getMinY() - 444 - 4;
                if (y < 0) y = b.getMaxY() + 4;
                messageInputBox.getEmojiPicker().show(item.getScene().getWindow(), x, y);
            }
        } else {
            sendAddReactionMessage(messageId, emoji);
        }
    }

    // ── Reply bar ─────────────────────────────────────────────────────────────

    private void openReplyBar(MessageReceivedPayload target) {
        if (replyTimeline != null) { replyTimeline.stop(); replyTimeline = null; }
        currentReplyTarget = target;
        messageInputBox.setReplyTarget(target);
        syncAttachmentBarCorners();

        replyBarSlot.getChildren().setAll(buildReplyBarWidget(target));
        HBox bar = (HBox) replyBarSlot.getChildren().get(0);
        bar.setOpacity(0);

        double fromH = replyBarSlot.getPrefHeight();
        replyTimeline = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(replyBarSlot.prefHeightProperty(), fromH),
                        new KeyValue(replyBarSlot.maxHeightProperty(), fromH),
                        new KeyValue(replyBarSlot.minHeightProperty(), fromH),
                        new KeyValue(bar.opacityProperty(), 0.0)),
                new KeyFrame(Duration.millis(REPLY_ANIM_MS),
                        new KeyValue(replyBarSlot.prefHeightProperty(), REPLY_BAR_HEIGHT, Interpolator.SPLINE(0.4, 0, 0.2, 1)),
                        new KeyValue(replyBarSlot.maxHeightProperty(), REPLY_BAR_HEIGHT, Interpolator.SPLINE(0.4, 0, 0.2, 1)),
                        new KeyValue(replyBarSlot.minHeightProperty(), REPLY_BAR_HEIGHT, Interpolator.SPLINE(0.4, 0, 0.2, 1)),
                        new KeyValue(bar.opacityProperty(), 1.0, Interpolator.EASE_OUT)));
        replyTimeline.setOnFinished(e -> { replyTimeline = null; syncAttachmentBarCorners(); });
        replyTimeline.play();
        messageInputBox.refreshInputRowRadius();
    }

    private void collapseReplyBar() {
        if (replyTimeline != null) { replyTimeline.stop(); replyTimeline = null; }
        messageInputBox.clearReply();
        if (replyBarSlot.getPrefHeight() <= 0) { replyBarSlot.getChildren().clear(); return; }

        double fromH = replyBarSlot.getPrefHeight();
        List<KeyValue> s = new ArrayList<>(List.of(
                new KeyValue(replyBarSlot.prefHeightProperty(), fromH),
                new KeyValue(replyBarSlot.maxHeightProperty(), fromH),
                new KeyValue(replyBarSlot.minHeightProperty(), fromH)));
        List<KeyValue> e = new ArrayList<>(List.of(
                new KeyValue(replyBarSlot.prefHeightProperty(), 0.0, Interpolator.SPLINE(0.4, 0, 0.2, 1)),
                new KeyValue(replyBarSlot.maxHeightProperty(), 0.0, Interpolator.SPLINE(0.4, 0, 0.2, 1)),
                new KeyValue(replyBarSlot.minHeightProperty(), 0.0, Interpolator.SPLINE(0.4, 0, 0.2, 1))));
        if (!replyBarSlot.getChildren().isEmpty()) {
            HBox bar = (HBox) replyBarSlot.getChildren().get(0);
            s.add(new KeyValue(bar.opacityProperty(), bar.getOpacity()));
            e.add(new KeyValue(bar.opacityProperty(), 0.0, Interpolator.EASE_IN));
        }
        replyTimeline = new Timeline(
                new KeyFrame(Duration.ZERO, s.toArray(new KeyValue[0])),
                new KeyFrame(Duration.millis(REPLY_ANIM_MS), e.toArray(new KeyValue[0])));
        replyTimeline.setOnFinished(ev -> {
            replyBarSlot.getChildren().clear();
            replyTimeline = null;
            messageInputBox.refreshInputRowRadius();
            syncAttachmentBarCorners();
        });
        replyTimeline.play();
    }

    private void collapseReplyBarInstant() {
        if (replyTimeline != null) { replyTimeline.stop(); replyTimeline = null; }
        messageInputBox.clearReply();
        replyBarSlot.getChildren().clear();
        replyBarSlot.setPrefHeight(0);
        replyBarSlot.setMaxHeight(0);
        replyBarSlot.setMinHeight(0);
        syncAttachmentBarCorners();
    }

    private HBox buildReplyBarWidget(MessageReceivedPayload target) {
        HBox bar = new HBox(10);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefHeight(REPLY_BAR_HEIGHT);
        bar.setMinHeight(REPLY_BAR_HEIGHT);
        bar.setMaxHeight(REPLY_BAR_HEIGHT);
        bar.setPadding(new Insets(8, 12, 8, 12));
        bar.setStyle("-fx-background-color: -color-bg-subtle;" +
                "-fx-background-radius: 8px 8px 0 0;" +
                "-fx-border-color: transparent transparent -color-border-muted transparent;" +
                "-fx-border-width: 0 0 1px 0;");

        javafx.scene.shape.Rectangle accent = new javafx.scene.shape.Rectangle(3, 30);
        accent.setArcWidth(3); accent.setArcHeight(3);
        accent.setStyle("-fx-fill: -color-accent-8;");

        VBox content = new VBox(1);
        content.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(content, Priority.ALWAYS);

        String senderName = target.getSenderId() != null
                && target.getSenderId().equals(App.getUser().getUserId()) ? "yourself" : "…";
        Label replyingTo = new Label("Replying to " + senderName);
        replyingTo.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: -color-accent-emphasis;");

        String preview;
        if (target.getMessageType() == MessageReceivedPayload.MessageType.GIF) {
            preview = "GIF";
        } else if (target.getMessageType() == MessageReceivedPayload.MessageType.CODE) {
            preview = "Code snippet";
        } else {
            preview = target.getContent();
            if ((preview == null || preview.isBlank()) && target.isHasAttachments())
                preview = target.getFileName() != null ? target.getFileName() : "Attachment";
            if (preview == null || preview.isBlank()) preview = "Message deleted";
            preview = truncate(preview.replaceAll("\\R", " ").strip(), 100);
        }

        HBox textBox = new HBox();
        textBox.setAlignment(Pos.CENTER_LEFT);
        if (target.getMessageType() == MessageReceivedPayload.MessageType.GIF) {
            textBox.getChildren().add(new Label(preview));
        } else {
            textBox.getChildren().addAll(TextUtils.convertToTextAndImageNodes(preview, 12));
        }
        content.getChildren().addAll(replyingTo, textBox);

        Button dismiss = new Button(null, new FontIcon(Feather.X));
        dismiss.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        dismiss.setFocusTraversable(false);
        dismiss.setOnAction(ev -> { currentReplyTarget = null; collapseReplyBar(); });

        bar.getChildren().addAll(accent, content, dismiss);

        if (target.getSenderId() != null) {
            var cached = App.getAvatarCache().getIfPresent(target.getSenderId());
            if (cached != null && cached.username() != null) {
                replyingTo.setText("Replying to " + cached.username());
            } else {
                App.getAvatarCache().resolve(target.getSenderId()).thenAcceptAsync(user ->
                        Platform.runLater(() -> {
                            if (user != null && user.username() != null && currentReplyTarget == target)
                                replyingTo.setText("Replying to " + user.username());
                        }));
            }
        }
        return bar;
    }

    // ── Outbound ──────────────────────────────────────────────────────────────

    private void handleSubmit(String text, MessageReceivedPayload replyTarget) {
        List<AttachmentBarSlot.PendingAttachment> attachments = new ArrayList<>(attachmentBarSlot.getAttachments());
        if (attachments.isEmpty()) {
            sendMessage(text, replyTarget);
        } else {
            sendAttachments(text, replyTarget, attachments);
            attachmentBarSlot.clear();
            messageInputBox.refreshInputRowRadius();
        }
    }

    private void sendMessage(String text, MessageReceivedPayload replyTarget) {
        String message = text == null ? "" : text.trim();
        if (message.isEmpty() || message.length() > 2000 || activeConversationPartnerId == null) return;
        DmSentPayload.DmSentPayloadBuilder builder = DmSentPayload.builder()
                .recipientId(activeConversationPartnerId)
                .content(message)
                .messageType(MessageReceivedPayload.MessageType.TEXT);
        if (replyTarget != null) builder.repliedToId(replyTarget.getMessageId());
        if (App.getHubWsClient() != null)
            App.getHubWsClient().send(WsMessageType.DM_SENT, builder.build());
    }

    private void sendAttachments(String text, MessageReceivedPayload replyTarget,
                                  List<AttachmentBarSlot.PendingAttachment> attachments) {
        if (activeConversationPartnerId == null) return;
        UUID partnerId = activeConversationPartnerId;
        Thread.ofVirtual().start(() -> {
            boolean first = true;
            for (AttachmentBarSlot.PendingAttachment att : attachments) {
                try {
                    var uploaded = App.getServices().hub().getDirectMessageService()
                            .uploadAttachment(att.file(), att.mimeType());
                    String content = first && text != null && !text.isBlank() ? text.trim() : "";
                    DmSentPayload.DmSentPayloadBuilder builder = DmSentPayload.builder()
                            .recipientId(partnerId)
                            .content(content)
                            .hasAttachments(true)
                            .attachmentId(uploaded.getAttachmentId());
                    if (first && replyTarget != null)
                        builder.repliedToId(replyTarget.getMessageId());
                    if (App.getHubWsClient() != null)
                        App.getHubWsClient().send(WsMessageType.DM_SENT, builder.build());
                    first = false;
                } catch (Exception e) {
                    log.error("Failed to upload DM attachment '{}': {}", att.fileName(), e.getMessage(), e);
                }
            }
        });
    }

    // ── Code messages ───────────────────────────────────────────────────────

    private void openCodeComposer(String prefill) {
        if (activeConversationPartnerId == null) return;
        CodeLanguage guessed = prefill != null
                ? CodeDetector.guessLanguage(prefill) : CodeLanguage.PLAIN_TEXT;
        MessageReceivedPayload reply = currentReplyTarget;
        App.showModal(new CodeMessageModal(prefill, guessed, false, (code, language) -> {
            sendCode(code, language, reply);
            if (currentReplyTarget != null) { currentReplyTarget = null; collapseReplyBar(); }
        }));
    }

    private void openCodeEditor(MessageReceivedPayload msg) {
        App.showModal(new CodeMessageModal(msg.getContent(),
                CodeLanguage.fromString(msg.getCodeLanguage()), true,
                (code, language) -> sendEditCode(msg.getMessageId(), code, language)));
    }

    private void sendCode(String code, CodeLanguage language, MessageReceivedPayload replyTarget) {
        if (code == null || code.isBlank()
                || code.length() > CodeMessageModal.MAX_LENGTH || activeConversationPartnerId == null) return;
        DmSentPayload.DmSentPayloadBuilder builder = DmSentPayload.builder()
                .recipientId(activeConversationPartnerId)
                .content(code)
                .messageType(MessageReceivedPayload.MessageType.CODE)
                .codeLanguage(language.name());
        if (replyTarget != null) builder.repliedToId(replyTarget.getMessageId());
        if (App.getHubWsClient() != null)
            App.getHubWsClient().send(WsMessageType.DM_SENT, builder.build());
    }

    private void sendEditCode(UUID messageId, String content, CodeLanguage language) {
        if (content == null || content.isBlank() || content.length() > CodeMessageModal.MAX_LENGTH) return;
        if (App.getHubWsClient() != null)
            App.getHubWsClient().send(WsMessageType.DM_EDIT,
                    DmEditedPayload.builder()
                            .messageId(messageId)
                            .content(content)
                            .codeLanguage(language.name())
                            .build());
    }

    private void sendGif(GifResult gif) {
        if (activeConversationPartnerId == null) return;
        String url = gif.getFullUrl() != null && !gif.getFullUrl().isBlank()
                ? gif.getFullUrl() : gif.getFullMp4Url();
        if (App.getHubWsClient() != null)
            App.getHubWsClient().send(WsMessageType.DM_SENT, DmSentPayload.builder()
                    .recipientId(activeConversationPartnerId)
                    .content(url)
                    .messageType(MessageReceivedPayload.MessageType.GIF)
                    .build());
    }

    private void sendTypingEvent() {
        if (activeConversationPartnerId == null) return;
        if (App.getHubWsClient() != null)
            App.getHubWsClient().send(WsMessageType.DM_TYPING,
                    DmTypingPayload.builder().recipientId(activeConversationPartnerId).build());
    }

    private void sendDeleteMessage(UUID messageId) {
        if (App.getHubWsClient() != null)
            App.getHubWsClient().send(WsMessageType.DM_DELETE,
                    DmDeletedPayload.builder().messageId(messageId).build());
    }

    private void sendEditMessage(UUID messageId, String newContent) {
        if (App.getHubWsClient() != null)
            App.getHubWsClient().send(WsMessageType.DM_EDIT,
                    DmEditedPayload.builder().messageId(messageId).content(newContent).build());
    }

    private void sendAddReactionMessage(UUID messageId, io.github.b077as.emojifx.Emoji emoji) {
        pendingReactionMessageId = messageId.toString();
        pendingReactionEmoji = emoji.getUnified();
        pendingReactionIsAdd = true;
        reactionService.restart();
    }

    private void sendRemoveReactionMessage(UUID messageId, io.github.b077as.emojifx.Emoji emoji) {
        pendingReactionMessageId = messageId.toString();
        pendingReactionEmoji = emoji.getUnified();
        pendingReactionIsAdd = false;
        reactionService.restart();
    }

    // ── Edit mode ─────────────────────────────────────────────────────────────

    private void startEditMode(MessageReceivedPayload msg, EmojiMessageItem item) {
        if (msg.getMessageType() == MessageReceivedPayload.MessageType.GIF) return;
        if (msg.getMessageType() == MessageReceivedPayload.MessageType.CODE) {
            openCodeEditor(msg);
            return;
        }
        if (activeEditItems.contains(msg.getMessageId())) return;

        VBox contentCol = item.getContentCol();
        EmojiMessageContent oldBubble = item.getBubble();
        int bubbleIdx = contentCol.getChildren().indexOf(oldBubble);
        if (bubbleIdx < 0) return;

        activeEditItems.add(msg.getMessageId());

        MessageEditBox editBox = new MessageEditBox(
                msg.getContent() != null ? msg.getContent() : "", msg.isHasAttachments());
        editBox.setOnSave(trimmed -> {
            if (!trimmed.equals(msg.getContent())) sendEditMessage(msg.getMessageId(), trimmed);
        });
        editBox.setOnDismiss(() -> {
            activeEditItems.remove(msg.getMessageId());
            contentCol.getChildren().remove(editBox);
            oldBubble.setVisible(true);
            oldBubble.setManaged(true);
        });
        oldBubble.setVisible(false);
        oldBubble.setManaged(false);
        contentCol.getChildren().add(bubbleIdx, editBox);
        editBox.activate();
        // Flowless re-lays-out on the height change; no scroll math needed.
    }

    // ── Misc helpers ──────────────────────────────────────────────────────────

    private static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text == null ? "" : text;
        BreakIterator bi = BreakIterator.getCharacterInstance();
        bi.setText(text);
        int lastBoundary = 0, count = 0;
        while (count < maxChars - 3) {
            int next = bi.next();
            if (next == BreakIterator.DONE) return text;
            lastBoundary = next;
            count++;
        }
        return text.substring(0, lastBoundary) + "...";
    }

    private void syncAttachmentBarCorners() {
        boolean replyOpen = replyBarSlot.getPrefHeight() > 1.0;
        attachmentBarSlot.setTopCornersRounded(!replyOpen);
    }

    // ── View construction ─────────────────────────────────────────────────────

    private VBox createChatView() {
        VBox container = new VBox();
        VBox.setVgrow(container, Priority.ALWAYS);

        virtualList = new VirtualMessageList();
        virtualList.setMessageNodeFactory(this::buildMessageItem);
        virtualList.setOnNearTop(this::fetchOlderMessages);
        virtualList.setOnCellRetired((id, node) -> messageItemMap.remove(id, node));
        virtualList.setOnAtBottomChanged(atBottom -> {
            isAtBottom = atBottom;
            updateScrollToBottomBtn();
        });

        scrollToBottomBtn = new Button();
        FontIcon chevronIcon = new FontIcon(Feather.CHEVRON_DOWN);
        chevronIcon.setIconSize(14);
        scrollToBottomBtn.setGraphic(chevronIcon);
        scrollToBottomBtn.getStyleClass().addAll(Styles.BUTTON_CIRCLE, Styles.ACCENT);
        scrollToBottomBtn.setOpacity(0.4);
        scrollToBottomBtn.setVisible(false);
        scrollToBottomBtn.setOnAction(e -> {
            isAtBottom = true;
            updateScrollToBottomBtn();
            virtualList.scrollToBottom();
        });

        scrollPaneWrapper = new StackPane(virtualList, scrollToBottomBtn);
        StackPane.setAlignment(scrollToBottomBtn, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(scrollToBottomBtn, new Insets(0, 24, 14, 0));
        VBox.setVgrow(scrollPaneWrapper, Priority.ALWAYS);

        replyBarSlot = new VBox();
        replyBarSlot.setMinHeight(0);
        replyBarSlot.setPrefHeight(0);
        replyBarSlot.setMaxHeight(0);
        replyBarSlot.setPadding(new Insets(0, INPUT_H_PAD, 0, INPUT_H_PAD));
        replyBarClip = new Rectangle(0, 0);
        replyBarSlot.setClip(replyBarClip);
        replyBarSlot.widthProperty().addListener((o, ov, w) -> replyBarClip.setWidth(w.doubleValue()));
        replyBarSlot.prefHeightProperty().addListener((o, ov, h) -> replyBarClip.setHeight(h.doubleValue()));

        attachmentBarSlot = new AttachmentBarSlot(errorMsg -> {
            log.warn("Attachment error: {}", errorMsg);
            Platform.runLater(() -> new CustomNotification(
                    "Attachment Error", errorMsg, new FontIcon(MaterialDesignC.CANCEL)).showNotification());
        });
        attachmentBarSlot.setMaxFileBytes(10L * 1024 * 1024);
        attachmentBarSlot.setOnChanged(() -> Platform.runLater(messageInputBox::refreshInputRowRadius));

        welcomeView = createWelcomeView();

        messageInputBox = new MessageInputBox(new MessageInputBox.MessageInputListener() {
            @Override
            public void onMessageSubmit(String text, MessageReceivedPayload replyTarget) {
                handleSubmit(text, replyTarget);
                if (currentReplyTarget != null) { currentReplyTarget = null; collapseReplyBar(); }
            }
            @Override
            public void onTyping() { sendTypingEvent(); }
            @Override
            public void onCodeRequested(String prefill) { openCodeComposer(prefill); }
        });
        messageInputBox.setAttachmentBarSlot(attachmentBarSlot);
        messageInputBox.getGifPicker().setOnGifSelected(this::sendGif);

        typingRow = messageInputBox.getTypingIndicatorRow();
        VBox.setMargin(typingRow, new Insets(0, INPUT_H_PAD, 4, INPUT_H_PAD));

        scrollPaneWrapper.maxHeightProperty().bind(
                container.heightProperty()
                        .subtract(typingRow.heightProperty())
                        .subtract(replyBarSlot.prefHeightProperty())
                        .subtract(attachmentBarSlot.prefHeightProperty())
                        .subtract(messageInputBox.heightProperty()));

        scrollPaneWrapper.setVisible(false);
        scrollPaneWrapper.setManaged(false);
        typingRow.setVisible(false);
        typingRow.setManaged(false);
        replyBarSlot.setVisible(false);
        replyBarSlot.setManaged(false);
        messageInputBox.setVisible(false);
        messageInputBox.setManaged(false);

        container.getChildren().addAll(welcomeView, scrollPaneWrapper, typingRow, replyBarSlot,
                attachmentBarSlot, messageInputBox);
        return container;
    }

    private VBox createWelcomeView() {
        VBox view = new VBox(12);
        view.setAlignment(Pos.CENTER);
        VBox.setVgrow(view, Priority.ALWAYS);
        view.setStyle("-fx-background-color: -color-bg-default;");
        FontIcon icon = new FontIcon(Feather.MESSAGE_CIRCLE);
        icon.getStyleClass().add("custom-icon-72");
        icon.setOpacity(0.12);
        Label title = new Label("Your messages");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");
        Label subtitle = new Label("Select a conversation or start one from your friends list.");
        subtitle.setStyle("-fx-font-size: 13px; -fx-text-fill: -color-fg-subtle;");
        view.getChildren().addAll(icon, title, subtitle);
        return view;
    }

    // ── Scroll save/restore (page navigation Home ↔ DM) ───────────────────────

    /** No-op: opening a modal doesn't mutate the list, so there is nothing to rebase. */
    public void freezeScroll() { }

    /** No-op: see {@link #freezeScroll()}. */
    public void unfreezeScroll() { }

    public void saveScrollPosition() {
        if (activeConversationPartnerId != null) virtualList.saveAnchor();
    }

    public void notifyPageResumed() {
        if (activeConversationPartnerId != null) virtualList.restoreAnchor();
        chatHeader.refreshStatus();
    }
}
