package komm.ui.chat;

import io.github.b077as.emojifx.EmojiData;
import io.github.b077as.emojifx.util.TextUtils;
import javafx.beans.value.ChangeListener;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import javafx.animation.*;
import komm.App;
import komm.model.dto.summary.ChannelSummary;
import komm.model.dto.summary.GifResult;
import komm.model.permissions.Permission;
import komm.ui.attachments.AttachmentDisplayBuilder;
import komm.ui.attachments.AttachmentBarSlot;
import komm.ui.code.CodeBlockView;
import komm.ui.code.CodeDetector;
import komm.ui.code.CodeLanguage;
import komm.ui.customnodes.CustomNotification;
import komm.ui.modals.CodeMessageModal;
import komm.model.dto.summary.ServerSummary;
import komm.ui.emojis.EmojiMessageContent;
import komm.ui.emojis.EmojiMessageItem;
import komm.ui.emojis.EmojiReactionBar;
import komm.websocket.messages.payloads.ChannelMessageEditedPayload;
import komm.websocket.messages.payloads.MessageEditPayload;
import komm.ui.gifs.GifMessageCell;
import komm.ui.sections.ServerMembersSection;
import komm.ui.screenshare.MultiStreamView;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import atlantafx.base.theme.Styles;
import javafx.scene.control.Button;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

import java.text.BreakIterator;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class ChatSection extends VBox {

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final int PAGE_SIZE = 50;
    private static final double PREFETCH_THRESHOLD = 0.20;
    private static final double FRIENDS_SECTION_WIDTH = 240;
    private static final double REPLY_BAR_HEIGHT = 53.0;
    private static final double REPLY_ANIM_MS = 180.0;
    private static final double INPUT_H_PAD = 16.0;

    /**
     * When the stream drawer is open, StreamSection occupies this fraction of
     * the total rootStack height; chatView fills the remaining bottom portion.
     */
    private static final double STREAM_OPEN_RATIO = 0.50;
    private static final double DRAWER_ANIM_MS = 280.0;

    // ── Reply bar ─────────────────────────────────────────────────────────────

    private VBox replyBarSlot;
    private Rectangle replyBarClip;
    private Timeline replyTimeline;
    private MessageReceivedPayload currentReplyTarget;

    // ── Attachment bar ────────────────────────────────────────────────────────

    private AttachmentBarSlot attachmentBarSlot;

    // ── Children ──────────────────────────────────────────────────────────────

    private StreamSection streamSection;

    /**
     * The root layout: a StackPane that holds chatView (bottom layer) and
     * streamSection (top layer). When the drawer is open, streamSection shrinks
     * to the top half, revealing chatView in the bottom half.
     */
    private StackPane rootStack;

    /**
     * Single shared header — switches between CHAT and STREAM mode.
     */
    private ChatHeader chatHeader;

    private VBox chatView;
    private ScrollPane chatScrollPane;
    private StackPane scrollPaneWrapper;
    private Button scrollToBottomBtn;
    private VBox chatMessagesContainer;
    private MessageInputBox messageInputBox;
    private HBox typingRow;
    private VBox welcomeView;

    // ── Drag overlay ──────────────────────────────────────────────────────────

    private StackPane dragOverlay;
    private boolean dragOverlayVisible = false;
    private javafx.animation.PauseTransition dragHideDelay;

    // ── Stream drawer state ───────────────────────────────────────────────────

    /**
     * Whether the chat drawer is currently slid into view while streaming.
     */
    private boolean drawerOpen = false;
    private Timeline drawerTimeline;

    // ── Members sidebar ───────────────────────────────────────────────────────

    private StackPane sidebarSlot;
    private boolean friendsSectionVisible = true;

    // ── State ─────────────────────────────────────────────────────────────────

    private double savedVvalue = -1.0;
    private double savedContentHeight = -1.0;
    private double savedViewportHeight = -1.0;
    private boolean restoringScroll = false;
    private double pendingRestoreVvalue = -1.0;

    @Getter
    private boolean isAtBottom = true;

    // ── Scroll freeze ─────────────────────────────────────────────────────────

    private boolean scrollFrozen = false;
    private double frozenVvalue = -1.0;
    private boolean inScrollCorrection = false;
    private ChangeListener<Number> frozenHeightListener;

    @Getter
    private UUID activeTextChannelId;
    private LocalDateTime oldestMessageTimestamp;
    private volatile boolean isFetchingHistory = false;
    private boolean allHistoryLoaded = false;

    private record FetchResult(UUID channelId, boolean isInitialLoad, Label chip,
                               List<MessageReceivedPayload> messages) {}

    private UUID pendingFetchChannelId;
    private LocalDateTime pendingFetchBefore;
    private boolean pendingFetchIsInitialLoad;
    private Label pendingFetchChip;

    private final Service<FetchResult> fetchService = new Service<>() {
        @Override
        protected Task<FetchResult> createTask() {
            final UUID cid    = pendingFetchChannelId;
            final LocalDateTime before = pendingFetchBefore;
            final boolean initial = pendingFetchIsInitialLoad;
            final Label chip  = pendingFetchChip;
            return new Task<>() {
                @Override
                protected FetchResult call() throws Exception {
                    List<MessageReceivedPayload> page =
                            App.getServices().installation().getMessageService()
                                    .getMessages(cid, before, PAGE_SIZE);
                    if (page != null && !page.isEmpty()) {
                        Set<UUID> senderIds = page.stream()
                                .flatMap(m -> Stream.of(m.getSenderId(), m.getReplyToSenderId()))
                                .filter(Objects::nonNull)
                                .collect(Collectors.toSet());
                        App.getAvatarCache().resolveAll(senderIds).join();
                        App.getAvatarCache().preloadImages(senderIds);
                    }
                    return new FetchResult(cid, initial, chip,
                            page != null ? page : List.of());
                }
            };
        }
    };

    private String pendingReactionMessageId;
    private String pendingReactionEmoji;
    private boolean pendingReactionIsAdd;

    private final Service<Void> reactionService = new Service<>() {
        @Override
        protected Task<Void> createTask() {
            final UUID mid   = UUID.fromString(pendingReactionMessageId);
            final String emoji = pendingReactionEmoji;
            final boolean isAdd = pendingReactionIsAdd;
            return new Task<>() {
                @Override
                protected Void call() throws Exception {
                    if (isAdd) App.getServices().installation().getMessageService().addReaction(mid, emoji);
                    else       App.getServices().installation().getMessageService().removeReaction(mid, emoji);
                    return null;
                }
            };
        }
    };

    private ChangeListener<Number> scrollListener;
    private boolean suppressScrollFetch = false;
    private boolean scrollToBottomPending = false;
    private AnimationTimer scrollBurstTimer;
    private boolean loadHiding = false;

    // ── GIF registry ──────────────────────────────────────────────────────────

    private final Map<UUID, List<GifMessageCell>> gifCellsByMessage = new LinkedHashMap<>();

    // ── Message item map ──────────────────────────────────────────────────────

    private final Map<UUID, EmojiMessageItem> messageItemMap = new LinkedHashMap<>();

    // ── Active edits (guard against opening multiple edit areas per message) ──

    private final Set<UUID> activeEditItems = new HashSet<>();

    // ── Constructor ───────────────────────────────────────────────────────────

    public ChatSection() {
        this(null);
    }

    public ChatSection(ServerSummary server) {
        setStyle("-fx-background-color: -color-bg-default;");
        VBox.setVgrow(this, Priority.ALWAYS);

        // ── Shared header ─────────────────────────────────────────────────────
        chatHeader = new ChatHeader();
        chatHeader.setOnFriendsToggle(this::toggleFriendsPanel);
        chatHeader.setOnChatToggle(this::toggleStreamDrawer);

        // ── Stream section (no header inside) ─────────────────────────────────
        streamSection = new StreamSection(new StreamSection.StreamSectionListener() {
            @Override
            public void onChatToggleRequested() {
                toggleStreamDrawer();
            }

            @Override
            public void onStreamLeft(String userId, int remaining) {
                Platform.runLater(() -> {
                    sendStreamUnwatch(userId);
                    if (remaining == 0) {
                        hideScreenShare();
                    } else {
                        updateStreamHeaderTitle();
                    }
                });
            }
        });

        chatView = createChatView(); // does NOT create a header internally anymore

        /*
         * rootStack: chatView on the BOTTOM layer, streamSection on TOP.
         * The shared chatHeader sits ABOVE rootStack in the outer VBox.
         */
        rootStack = new StackPane();
        StackPane.setAlignment(chatView, Pos.BOTTOM_CENTER);
        StackPane.setAlignment(streamSection, Pos.TOP_CENTER);
        rootStack.getChildren().addAll(chatView, streamSection);

        if (server != null) {
            ServerMembersSection membersSection = new ServerMembersSection(server);
            sidebarSlot = new StackPane(membersSection);
            sidebarSlot.setMaxWidth(FRIENDS_SECTION_WIDTH);
            sidebarSlot.setPrefWidth(FRIENDS_SECTION_WIDTH);
            sidebarSlot.minWidthProperty().bind(sidebarSlot.prefWidthProperty());
            StackPane.setAlignment(membersSection, Pos.TOP_LEFT);
            Rectangle sidebarClip = new Rectangle();
            sidebarClip.widthProperty().bind(sidebarSlot.widthProperty());
            sidebarClip.heightProperty().bind(sidebarSlot.heightProperty());
            sidebarSlot.setClip(sidebarClip);
            rootStack.setMinWidth(0);
            HBox contentRow = new HBox(rootStack, sidebarSlot);
            HBox.setHgrow(rootStack, Priority.ALWAYS);
            VBox.setVgrow(contentRow, Priority.ALWAYS);
            getChildren().addAll(chatHeader, contentRow);
        } else {
            VBox.setVgrow(rootStack, Priority.ALWAYS);
            getChildren().addAll(chatHeader, rootStack);
        }
        installDragDrop();

        fetchService.setOnSucceeded(e -> onFetchSucceeded());
        fetchService.setOnFailed(e -> onFetchFailed());
        reactionService.setOnFailed(e -> log.error(
                pendingReactionIsAdd ? "Failed to add reaction" : "Failed to remove reaction",
                reactionService.getException()));
    }

    // ── Stream drawer (slide animation) ──────────────────────────────────────

    /**
     * Toggles the chat drawer while streaming.
     *
     * <p>Closed → streamSection covers 100% of rootStack (chat hidden).
     * <p>Open   → streamSection covers {@value #STREAM_OPEN_RATIO} of rootStack,
     * chatView is constrained to the remaining bottom portion.
     */
    private void toggleStreamDrawer() {
        if (!streamSection.isVisible()) return;

        drawerOpen = !drawerOpen;

        if (drawerTimeline != null) drawerTimeline.stop();

        double totalH = rootStack.getHeight();
        double targetStreamH = drawerOpen ? totalH * STREAM_OPEN_RATIO : totalH;
        double targetChatH = drawerOpen ? totalH * (1.0 - STREAM_OPEN_RATIO) : totalH;

        double currentStreamH = streamSection.getPrefHeight() >= 0
                ? streamSection.getPrefHeight() : totalH;
        double currentChatH = (chatView.getMaxHeight() >= 0
                && chatView.getMaxHeight() != Double.MAX_VALUE)
                ? chatView.getMaxHeight() : totalH;

        chatView.setMouseTransparent(!drawerOpen);
        streamSection.setChatOpen(drawerOpen);

        drawerTimeline = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(streamSection.prefHeightProperty(), currentStreamH),
                        new KeyValue(streamSection.minHeightProperty(), currentStreamH),
                        new KeyValue(streamSection.maxHeightProperty(), currentStreamH),
                        new KeyValue(chatView.maxHeightProperty(), currentChatH),
                        new KeyValue(chatView.minHeightProperty(), currentChatH),
                        new KeyValue(chatView.prefHeightProperty(), currentChatH)),
                new KeyFrame(Duration.millis(DRAWER_ANIM_MS),
                        new KeyValue(streamSection.prefHeightProperty(), targetStreamH,
                                Interpolator.SPLINE(0.4, 0.0, 0.2, 1.0)),
                        new KeyValue(streamSection.minHeightProperty(), targetStreamH,
                                Interpolator.SPLINE(0.4, 0.0, 0.2, 1.0)),
                        new KeyValue(streamSection.maxHeightProperty(), targetStreamH,
                                Interpolator.SPLINE(0.4, 0.0, 0.2, 1.0)),
                        new KeyValue(chatView.maxHeightProperty(), targetChatH,
                                Interpolator.SPLINE(0.4, 0.0, 0.2, 1.0)),
                        new KeyValue(chatView.minHeightProperty(), targetChatH,
                                Interpolator.SPLINE(0.4, 0.0, 0.2, 1.0)),
                        new KeyValue(chatView.prefHeightProperty(), targetChatH,
                                Interpolator.SPLINE(0.4, 0.0, 0.2, 1.0)))
        );
        drawerTimeline.setOnFinished(e -> {
            drawerTimeline = null;
            // Snap to bottom so the user sees the latest messages when opening
            if (drawerOpen && isAtBottom)
                chatScrollPane.setVvalue(chatScrollPane.getVmax());
        });
        drawerTimeline.play();
    }

    /**
     * Resets stream section to full height (closes drawer) without animation.
     * Called when the stream ends so the layout is clean before the node hides.
     */
    private void resetStreamSectionHeight() {
        if (drawerTimeline != null) {
            drawerTimeline.stop();
            drawerTimeline = null;
        }
        drawerOpen = false;
        streamSection.setChatOpen(false);
        streamSection.setPrefHeight(Region.USE_COMPUTED_SIZE);
        streamSection.setMinHeight(Region.USE_COMPUTED_SIZE);
        streamSection.setMaxHeight(Double.MAX_VALUE);
        chatView.setPrefHeight(Region.USE_COMPUTED_SIZE);
        chatView.setMinHeight(Region.USE_COMPUTED_SIZE);
        chatView.setMaxHeight(Double.MAX_VALUE);
        chatView.setMouseTransparent(false);
    }

    // ── Public channel API ────────────────────────────────────────────────────

    public void setActiveChannel(ChannelSummary channel) {
        removeScrollListeners();

        this.activeTextChannelId = channel.getChannelId();
        markActiveChannelReadNow(activeTextChannelId);
        this.oldestMessageTimestamp = null;
        this.allHistoryLoaded = false;
        this.isFetchingHistory = false;
        this.isAtBottom = true;
        this.suppressScrollFetch = false;
        this.scrollToBottomPending = false;
        this.pendingRestoreVvalue = -1.0;

        Platform.runLater(() -> {
            clearGifRegistry();
            chatMessagesContainer.getChildren().clear();
            messageItemMap.clear();
            activeEditItems.clear();
            messageInputBox.clearReply();
            currentReplyTarget = null;
            collapseReplyBarInstant();
            attachmentBarSlot.clear();
            showChat();
            if (scrollToBottomBtn != null) scrollToBottomBtn.setVisible(false);

            chatHeader.setChannel(channel);
            messageInputBox.setPromptText("Message #" + channel.getChannelName());

            installScrollListeners();
            fetchPage(channel.getChannelId(), null, true);
        });
    }

    public void clearAndShowWelcome() {
        removeScrollListeners();
        Platform.runLater(() -> {
            clearGifRegistry();
            chatMessagesContainer.getChildren().clear();
            activeTextChannelId = null;
            messageInputBox.clearReply();
            attachmentBarSlot.clear();
            showWelcome();
        });
    }

    // ── Screen share ──────────────────────────────────────────────────────────

    /**
     * Adds a live stream tile for {@code userId}. Switches the header to stream
     * mode on the first tile; updates the title for subsequent additions.
     * No-op if already watching this user or if the 4-stream limit is reached.
     */
    public void showScreenShare(String userId, String username) {
        Platform.runLater(() -> {
            if (streamSection.hasStream(userId)) return;

            boolean firstStream = streamSection.getStreamCount() == 0;
            streamSection.addStream(userId, username);
            sendStreamWatch(userId);

            if (firstStream) {
                chatHeader.setMode(ChatHeader.Mode.STREAM, streamSection.getHeaderTitle());
                resetStreamSectionHeight();
                chatView.setMouseTransparent(true);
            } else {
                updateStreamHeaderTitle();
            }
        });
    }

    public void hideScreenShare() {
        Platform.runLater(() -> {
            resetStreamSectionHeight();
            List<String> activeIds = streamSection.getActiveStreamUserIds();
            streamSection.hideScreenShare();
            // Tile disposal above closes owned pop-outs; this catches any window
            // whose owning tile is already gone so none survives the chat view.
            komm.ui.screenshare.StreamPopOutWindow.closeAll();
            chatHeader.setMode(ChatHeader.Mode.CHAT, null);
            chatView.setMouseTransparent(false);
            activeIds.forEach(this::sendStreamUnwatch);
            Platform.runLater(this::rehookAllGifCells);
        });
    }

    public void updateStreamViewerCount(String userId, int count) {
        streamSection.updateViewerCount(userId, count);
    }

    public void onStreamerEnded(String userId) {
        Platform.runLater(() -> {
            if (streamSection.hasStream(userId)) {
                streamSection.removeStream(userId);
            }
        });
    }

    private void sendStreamWatch(String streamerUserId) {
        try {
            App.getServices().installation().getWsClient().send(
                    WsMessageType.STREAM_WATCH,
                    new komm.websocket.messages.payloads.StreamWatchPayload(UUID.fromString(streamerUserId)));
        } catch (Exception ignored) {}
    }

    private void sendStreamUnwatch(String streamerUserId) {
        try {
            App.getServices().installation().getWsClient().send(
                    WsMessageType.STREAM_UNWATCH,
                    new komm.websocket.messages.payloads.StreamWatchPayload(UUID.fromString(streamerUserId)));
        } catch (Exception ignored) {}
    }

    private void updateStreamHeaderTitle() {
        chatHeader.setStreamTitle(streamSection.getHeaderTitle());
    }

    public void setSpectatorCount(int count) {
        streamSection.setSpectatorCount(count);
    }

    /** Returns {@code true} if the given userId is currently being watched. */
    public boolean isWatchingStream(String userId) {
        return streamSection.hasStream(userId);
    }

    /** Returns the number of currently active stream tiles. */
    public int getActiveStreamCount() {
        return streamSection.getStreamCount();
    }

    // ── Inbound message events ────────────────────────────────────────────────

    public void addMessage(MessageReceivedPayload payload) {
        Platform.runLater(() -> {
            if (payload.getChannelId() == null
                    || !payload.getChannelId().equals(activeTextChannelId)) return;

            EmojiMessageItem item = buildMessageItem(payload);
            chatMessagesContainer.getChildren().add(item);
            registerGifsForMessage(payload.getMessageId(), item, true);
            registerCodeBlocksForMessage(item);
            if (isAtBottom) scheduleScrollToBottom();
        });
    }

    public void removeMessage(UUID messageId) {
        Platform.runLater(() -> {
            EmojiMessageItem item = messageItemMap.remove(messageId);
            if (item == null) return;

            disposeAndUnregisterGifsFor(messageId);

            double contentH = chatMessagesContainer.getHeight();
            double viewportH = chatScrollPane.getViewportBounds().getHeight();
            double scrollable = Math.max(1, contentH - viewportH);
            double pixelsBefore = chatScrollPane.getVvalue() * scrollable;

            suppressScrollFetch = true;
            chatScrollPane.setOpacity(0);
            chatMessagesContainer.getChildren().remove(item);

            ChangeListener<Number>[] l = new ChangeListener[1];
            l[0] = (obs, oldH, newH) -> {
                chatMessagesContainer.heightProperty().removeListener(l[0]);
                double newScrollable = Math.max(1, newH.doubleValue() - viewportH);
                chatScrollPane.setVvalue(clamp(pixelsBefore / newScrollable,
                        chatScrollPane.getVmin(), chatScrollPane.getVmax()));
                chatScrollPane.setOpacity(1);
                suppressScrollFetch = false;
            };
            chatMessagesContainer.heightProperty().addListener(l[0]);

            if (currentReplyTarget != null
                    && currentReplyTarget.getMessageId().equals(messageId)) {
                currentReplyTarget = null;
                collapseReplyBar();
            }
        });
    }

    public void addReaction(UUID messageId, String emojiChar, boolean isSelf) {
        Platform.runLater(() -> {
            EmojiMessageItem item = messageItemMap.get(messageId);
            if (item == null) return;
            item.getBubble().getReactionBar().incrementReaction(emojiChar, isSelf);
            if (isAtBottom) scheduleScrollToBottom();
        });
    }

    public void removeReaction(UUID messageId, String emojiChar, boolean isSelf) {
        Platform.runLater(() -> {
            EmojiMessageItem item = messageItemMap.get(messageId);
            if (item != null)
                item.getBubble().getReactionBar().decrementReaction(emojiChar, isSelf);
        });
    }

    // ── Typing ────────────────────────────────────────────────────────────────

    public void onUserTyping(ChannelTypingPayload payload) {
        if (payload.getChannelId() == null
                || !payload.getChannelId().equals(activeTextChannelId)) return;
        UUID userId = payload.getUserId();
        var cached = App.getAvatarCache().getIfPresent(userId);
        if (cached != null) {
            messageInputBox.registerTyping(userId, cached.username());
        } else {
            App.getAvatarCache().resolve(userId).thenAcceptAsync(user ->
                    Platform.runLater(() ->
                            messageInputBox.registerTyping(userId,
                                    user != null ? user.username() : "Someone")));
        }
    }

    // ── Visibility ────────────────────────────────────────────────────────────

    private void showWelcome() {
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
        welcomeView.setVisible(false);
        welcomeView.setManaged(false);
        scrollPaneWrapper.setVisible(true);
        scrollPaneWrapper.setManaged(true);
        typingRow.setManaged(true);
        replyBarSlot.setVisible(true);
        replyBarSlot.setManaged(true);
        messageInputBox.setVisible(true);
        messageInputBox.setManaged(true);
    }

    // ── History fetch ─────────────────────────────────────────────────────────

    private void fetchOlderMessages() {
        if (activeTextChannelId == null || isFetchingHistory || allHistoryLoaded) return;
        fetchPage(activeTextChannelId, oldestMessageTimestamp, false);
    }

    private void fetchPage(UUID channelId, LocalDateTime before, boolean isInitialLoad) {
        isFetchingHistory = true;
        pendingFetchChannelId = channelId;
        pendingFetchBefore = before;
        pendingFetchIsInitialLoad = isInitialLoad;
        pendingFetchChip = buildLoadingChip();
        chatMessagesContainer.getChildren().add(0, pendingFetchChip);
        fetchService.restart();
    }

    private void onFetchSucceeded() {
        FetchResult result = fetchService.getValue();
        if (result == null) return;

        chatMessagesContainer.getChildren().remove(result.chip());

        if (!result.channelId().equals(activeTextChannelId)) {
            isFetchingHistory = false;
            return;
        }

        List<MessageReceivedPayload> page = result.messages();
        if (page.isEmpty()) {
            allHistoryLoaded = true;
            isFetchingHistory = false;
            return;
        }

        Collections.reverse(page);
        List<Node> newNodes = buildMessageNodes(page);

        if (result.isInitialLoad()) loadInitialPage(newNodes, page);
        else prependWithAnchoredScroll(newNodes, page);

        oldestMessageTimestamp = page.get(0).getSentAt();
        if (page.size() < PAGE_SIZE) allHistoryLoaded = true;
        isFetchingHistory = false;
    }

    private void onFetchFailed() {
        log.error("Failed to fetch messages", fetchService.getException());
        chatMessagesContainer.getChildren().remove(pendingFetchChip);
        isFetchingHistory = false;
    }

    // ── Scroll logic ──────────────────────────────────────────────────────────

    private void loadInitialPage(List<Node> newNodes, List<MessageReceivedPayload> page) {
        isAtBottom = true;
        chatMessagesContainer.getChildren().setAll(newNodes);
        for (MessageReceivedPayload msg : page) {
            EmojiMessageItem item = messageItemMap.get(msg.getMessageId());
            if (item != null) {
                registerGifsForMessage(msg.getMessageId(), item, true);
                registerCodeBlocksForMessage(item);
            }
        }
        rehookAllGifCells();
        // Hide the pane until the burst settles: the content height jumps from ~0
        // to full in one pulse, so pinning to the bottom is a visible top→bottom
        // snap. Hiding makes that happen off-screen.
        scheduleScrollToBottom(true);
    }

    private void prependWithAnchoredScroll(List<Node> newNodes,
                                           List<MessageReceivedPayload> page) {
        double contentH = chatMessagesContainer.getHeight();
        double viewportH = chatScrollPane.getViewportBounds().getHeight();
        double scrollable = Math.max(0, contentH - viewportH);
        double pixelsBefore = chatScrollPane.getVvalue() * scrollable;

        for (MessageReceivedPayload msg : page) {
            EmojiMessageItem item = messageItemMap.get(msg.getMessageId());
            if (item != null) registerGifsForMessage(msg.getMessageId(), item, false);
        }

        suppressScrollFetch = true;
        chatScrollPane.setOpacity(0);
        chatMessagesContainer.getChildren().addAll(0, newNodes);

        ChangeListener<Number>[] l = new ChangeListener[1];
        l[0] = (obs, oldH, newH) -> {
            chatMessagesContainer.heightProperty().removeListener(l[0]);
            double addedH = newH.doubleValue() - contentH;
            double newScrollable = Math.max(1, newH.doubleValue() - viewportH);
            chatScrollPane.setVvalue(clamp((pixelsBefore + addedH) / newScrollable,
                    chatScrollPane.getVmin(), chatScrollPane.getVmax()));
            chatScrollPane.setOpacity(1);
            suppressScrollFetch = false;
            Platform.runLater(this::rehookAllGifCells);
        };
        chatMessagesContainer.heightProperty().addListener(l[0]);
    }

    private void scheduleScrollToBottom() {
        scheduleScrollToBottom(false);
    }

    /**
     * Pins the view to the bottom across the next several layout pulses.
     *
     * <p>A single {@code setVvalue(vmax)} is unreliable here: on channel load the
     * viewport height can still be 0 when the content height settles, and JavaFX
     * resets a ScrollPane's {@code vvalue} to the top whenever its content grows
     * taller — so a value set before the content/viewport settle gets discarded.
     * Rather than try to catch the one perfect moment with a height listener (which
     * can miss and then never re-fire), we re-assert the bottom for a short burst of
     * frames until the content height is stable. The burst self-cancels the instant
     * the user scrolls up ({@code isAtBottom} flips false), the channel changes, or
     * scroll is frozen.
     *
     * @param hideUntilSettled hide the scroll pane until the content height has fully
     *        settled. Used on the initial channel load: the content height grows in
     *        several pulses over ~150ms (the messages, then code blocks and avatars
     *        rendering in stages), and each growth makes JavaFX yank the scroll to
     *        the top before the burst re-pins it — a visible top→bottom flicker on
     *        every step. Hiding until everything settles makes the whole sequence
     *        happen off-screen so the messages simply appear already at the bottom.
     *        New messages pass {@code false} — already at the bottom, nothing to mask.
     */
    private void scheduleScrollToBottom(boolean hideUntilSettled) {
        // While the load hide-burst is running it already pins to the bottom every
        // frame, so ignore redundant requests (gif/code resize callbacks, new
        // messages) that would otherwise supersede it and reveal the pane early.
        if (loadHiding && !hideUntilSettled) {
            log.debug("[DBG] schedule(hide={}) IGNORED — loadHiding active", hideUntilSettled);
            return;
        }

        log.debug("[DBG] schedule(hide={}) — isAtBottom={} pending={} loadHiding={} contentH={} viewportH={} vvalue={}",
                hideUntilSettled, isAtBottom, scrollToBottomPending, loadHiding,
                chatMessagesContainer.getHeight(), chatScrollPane.getViewportBounds().getHeight(),
                chatScrollPane.getVvalue());

        if (!isAtBottom || pendingRestoreVvalue >= 0 || restoringScroll || scrollFrozen) {
            log.debug("[DBG] schedule REJECTED by guard");
            if (hideUntilSettled) { chatScrollPane.setOpacity(1); loadHiding = false; }
            return;
        }

        final UUID channelAtSchedule = activeTextChannelId;
        scrollToBottomPending = true;
        if (hideUntilSettled) {
            loadHiding = true;
            chatScrollPane.setOpacity(0);
        }
        if (scrollBurstTimer != null) scrollBurstTimer.stop();

        // Finish once the content height has been unchanged for this long (so async
        // growth from code blocks / images is captured). The load burst waits longer
        // because it also reveals the pane; new-message bursts only need to re-pin
        // briefly. Hard cap so we never spin indefinitely.
        final long settleNanos = hideUntilSettled ? 140_000_000L : 40_000_000L;
        final long capNanos = 600_000_000L;
        final boolean hide = hideUntilSettled;

        scrollBurstTimer = new AnimationTimer() {
            long startNanos = 0;
            long lastChangeNanos = 0;
            double lastH = -1;
            int frame = 0;

            @Override
            public void handle(long now) {
                if (startNanos == 0) { startNanos = now; lastChangeNanos = now; }
                if (!isAtBottom
                        || !Objects.equals(channelAtSchedule, activeTextChannelId)
                        || scrollFrozen) {
                    log.debug("[DBG] burst(hide={}) ABORT frame={} isAtBottom={} frozen={}",
                            hide, frame, isAtBottom, scrollFrozen);
                    finish();
                    return;
                }
                double vmax = chatScrollPane.getVmax();
                double before = chatScrollPane.getVvalue();
                chatScrollPane.setVvalue(vmax);
                double after = chatScrollPane.getVvalue();

                double h = chatMessagesContainer.getHeight();
                double viewportH = chatScrollPane.getViewportBounds().getHeight();
                boolean changed = (h != lastH || viewportH <= 0);
                log.debug("[DBG] burst(hide={}) frame={} contentH={} viewportH={} vvalue {}->{} vmax={} changed={} sinceChange={}ms sinceStart={}ms",
                        hide, frame, h, viewportH, before, after, vmax, changed,
                        (now - lastChangeNanos) / 1_000_000, (now - startNanos) / 1_000_000);
                frame++;
                if (changed) {
                    lastChangeNanos = now;
                    lastH = h;
                }
                if ((viewportH > 0 && now - lastChangeNanos >= settleNanos)
                        || now - startNanos >= capNanos) {
                    log.debug("[DBG] burst(hide={}) FINISH frame={} sinceChange={}ms sinceStart={}ms finalVvalue={}",
                            hide, frame, (now - lastChangeNanos) / 1_000_000,
                            (now - startNanos) / 1_000_000, chatScrollPane.getVvalue());
                    finish();
                }
            }

            private void finish() {
                stop();
                scrollToBottomPending = false;
                chatScrollPane.setOpacity(1);
                loadHiding = false;
                if (scrollBurstTimer == this) scrollBurstTimer = null;
            }
        };
        chatMessagesContainer.requestLayout();
        scrollBurstTimer.start();
    }

    private void installScrollListeners() {
        scrollListener = (obs, oldVal, newVal) -> {
            /*log.debug("[DBG] LISTENER vvalue {}->{} pending={} loadHiding={} isAtBottom={} frozen={} contentH={} viewportH={}",
                    oldVal, newVal, scrollToBottomPending, loadHiding, isAtBottom, scrollFrozen,
                    chatMessagesContainer.getHeight(), chatScrollPane.getViewportBounds().getHeight());*/
            if (scrollFrozen) {
                if (!inScrollCorrection && frozenVvalue >= 0) {
                    inScrollCorrection = true;
                    chatScrollPane.setVvalue(frozenVvalue);
                    inScrollCorrection = false;
                }
                return;
            }
            if (pendingRestoreVvalue >= 0) {
                double delta = Math.abs(newVal.doubleValue() - pendingRestoreVvalue);
                log.debug("[scroll] ChatSection scroll during restore — newVal={} pending={} delta={}", newVal, pendingRestoreVvalue, delta);
                if (delta < 0.005) {
                    isAtBottom = pendingRestoreVvalue >= chatScrollPane.getVmax() - 0.02;
                    pendingRestoreVvalue = -1.0;
                    log.debug("[scroll] ChatSection restore confirmed — isAtBottom={}", isAtBottom);
                    updateScrollToBottomBtn();
                } else {
                    // vvalue was pushed away from target (e.g., by the FX layout reset).
                    // Clear first to prevent re-entry, then re-apply immediately.
                    double target = pendingRestoreVvalue;
                    pendingRestoreVvalue = -1.0;
                    chatScrollPane.setVvalue(target);
                    isAtBottom = target >= chatScrollPane.getVmax() - 0.02;
                    log.debug("[scroll] ChatSection re-applied after layout reset — target={} isAtBottom={}", target, isAtBottom);
                    updateScrollToBottomBtn();
                }
                return;
            }
            if (scrollToBottomPending) {
                // A scroll-to-bottom burst is active. JavaFX resets vvalue toward 0
                // whenever the content grows taller; snap it back to the bottom
                // SYNCHRONOUSLY here (mirrors the scrollFrozen correction) so no
                // intermediate frame is painted off-bottom — that is what removes the
                // flicker. Return before the prefetch check below: the transient low
                // value is a skin artifact, NOT the user scrolling toward the top, so
                // it must not trigger a history fetch (doing so caused a feedback loop
                // of fetch → content grows → reset → fetch).
                if (!inScrollCorrection && newVal.doubleValue() < chatScrollPane.getVmax()) {
                    inScrollCorrection = true;
                    chatScrollPane.setVvalue(chatScrollPane.getVmax());
                    inScrollCorrection = false;
                }
                return;
            }

            double contentH = chatMessagesContainer.getHeight();
            double viewportH = chatScrollPane.getViewportBounds().getHeight();
            if (contentH <= viewportH) {
                isAtBottom = true;
            } else {
                isAtBottom = newVal.doubleValue() >= chatScrollPane.getVmax() - 0.02;
            }
            updateScrollToBottomBtn();

            if (suppressScrollFetch || allHistoryLoaded || isFetchingHistory) return;
            if (newVal.doubleValue() <= PREFETCH_THRESHOLD) fetchOlderMessages();
        };
        chatScrollPane.vvalueProperty().addListener(scrollListener);
    }

    private void removeScrollListeners() {
        if (scrollListener != null) {
            chatScrollPane.vvalueProperty().removeListener(scrollListener);
            scrollListener = null;
        }
        if (scrollBurstTimer != null) {
            scrollBurstTimer.stop();
            scrollBurstTimer = null;
            // The timer's finish() (which restores opacity) won't run when stopped
            // externally, so restore it here in case we stopped mid hide-burst.
            chatScrollPane.setOpacity(1);
            loadHiding = false;
        }
        scrollToBottomPending = false;
    }

    private void updateScrollToBottomBtn() {
        if (isAtBottom) markActiveChannelReadNow(activeTextChannelId);
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

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * vvalue is a proportion of the scrollable range, so restoring the same proportion against
     * content that grew while the channel was away silently scrolls further down than the user
     * actually was — up to the new bottom if they were previously at the (old) bottom. Re-derives
     * the target from the absolute pixel offset the user was really at, so returning lands them
     * exactly where they left off, with any new messages below, out of view until they scroll.
     */
    private static double reanchorTargetVvalue(double target, double oldContentHeight, double oldViewportHeight,
                                                double newContentHeight, double newViewportHeight) {
        if (oldContentHeight < 0 || oldViewportHeight <= 0 || newContentHeight <= oldContentHeight + 0.5) {
            return target;
        }
        double oldRange = Math.max(oldContentHeight - oldViewportHeight, 0);
        double oldScrollY = target * oldRange;
        double newRange = Math.max(newContentHeight - newViewportHeight, 0);
        return newRange > 0 ? clamp(oldScrollY / newRange, 0.0, 1.0) : 0.0;
    }

    /**
     * Clears the unread dot for {@code channelId} and tells the server it's read. Called
     * immediately on a fresh channel selection, and — for a channel that was already active —
     * once the caller has determined it's actually been seen (no scrollbar, or scrolled to bottom).
     */
    private void markActiveChannelReadNow(UUID channelId) {
        if (channelId == null) return;
        var serverPage = App.getCachedServerPage();
        if (serverPage == null || serverPage.getChatSection() != this || App.getCurrentPage() != serverPage) return;

        var channelCard = serverPage.getChannelSection().getChannelBoxes().get(channelId);
        if (channelCard == null || !channelCard.hasNotificationDot()) return;

        channelCard.clearNotificationDot();
        Thread.ofVirtual().start(() -> {
            try {
                App.getServices().installation().getChannelService().markChannelRead(channelId);
            } catch (Exception e) {
                log.warn("Failed to mark channel {} as read: {}", channelId, e.getMessage());
            }
        });
    }

    // ── GIF registry ──────────────────────────────────────────────────────────

    private void registerGifsForMessage(UUID messageId, EmojiMessageItem item,
                                        boolean withScrollCallback) {
        List<GifMessageCell> cells = new ArrayList<>();
        collectGifCells(item, cells);
        if (cells.isEmpty()) return;

        gifCellsByMessage.put(messageId, cells);

        if (withScrollCallback) {
            final UUID channelAtRegister = activeTextChannelId;
            for (GifMessageCell cell : cells) {
                cell.setOnSizeCommitted(() -> {
                    if (isAtBottom
                            && Objects.equals(channelAtRegister, activeTextChannelId))
                        scheduleScrollToBottom();
                });
            }
        }
    }

    private void disposeAndUnregisterGifsFor(UUID messageId) {
        List<GifMessageCell> cells = gifCellsByMessage.remove(messageId);
        if (cells != null) cells.forEach(GifMessageCell::dispose);
    }

    private void clearGifRegistry() {
        gifCellsByMessage.values().forEach(cells -> cells.forEach(GifMessageCell::dispose));
        gifCellsByMessage.clear();
    }

    private void rehookAllGifCells() {
        gifCellsByMessage.values().forEach(cells -> cells.forEach(GifMessageCell::rehookScrollPane));
    }

    private void collectGifCells(Node node, List<GifMessageCell> out) {
        if (node instanceof GifMessageCell cell) {
            out.add(cell);
        } else if (node instanceof javafx.scene.Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) collectGifCells(child, out);
        }
    }

    // ── Code block scroll anchoring ───────────────────────────────────────────

    /**
     * Re-anchors the scroll to the bottom once a code block realises its final
     * height (RichTextFX lays out a pulse later). Mirrors the GIF size callback.
     */
    private void registerCodeBlocksForMessage(EmojiMessageItem item) {
        List<CodeBlockView> blocks = new ArrayList<>();
        collectCodeBlocks(item, blocks);
        if (blocks.isEmpty()) return;
        final UUID channelAtRegister = activeTextChannelId;
        for (CodeBlockView block : blocks) {
            block.setOnResize(() -> {
                if (isAtBottom && Objects.equals(channelAtRegister, activeTextChannelId))
                    scheduleScrollToBottom();
            });
        }
    }

    private void collectCodeBlocks(Node node, List<CodeBlockView> out) {
        if (node instanceof CodeBlockView block) {
            out.add(block);
        } else if (node instanceof javafx.scene.Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) collectCodeBlocks(child, out);
        }
    }

    // ── Drag-and-drop ─────────────────────────────────────────────────────────

    private void installDragDrop() {
        dragHideDelay = new javafx.animation.PauseTransition(Duration.millis(80));
        dragHideDelay.setOnFinished(e -> hideDragOverlay());

        setOnDragOver(e -> {
            if (e.getDragboard().hasFiles() && activeTextChannelId != null) {
                e.acceptTransferModes(TransferMode.COPY);
                dragHideDelay.stop();
                showDragOverlay();
            }
            e.consume();
        });

        setOnDragExited(e -> {
            dragHideDelay.playFromStart();
            e.consume();
        });

        setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            boolean success = false;
            if (db.hasFiles() && activeTextChannelId != null) {
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
            title.setStyle("""
                    -fx-font-size: 22px;
                    -fx-font-weight: bold;
                    -fx-text-fill: -color-fg-default;
                    """);

            Label subtitle = new Label("Files will be added to your message");
            subtitle.setStyle("""
                    -fx-font-size: 13px;
                    -fx-text-fill: -color-fg-muted;
                    """);

            VBox textBlock = new VBox(4, title, subtitle);
            textBlock.setAlignment(Pos.CENTER);

            VBox card = new VBox(20, icon, textBlock);
            card.setAlignment(Pos.CENTER);
            card.setPadding(new Insets(48, 72, 48, 72));
            card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            card.setStyle("""
                    -fx-background-color: -color-bg-default;
                    -fx-background-radius: 16;
                    -fx-border-color: -color-accent-emphasis;
                    -fx-border-width: 2;
                    -fx-border-radius: 16;
                    -fx-border-style: dashed;
                    """);

            dragOverlay = new StackPane(card);
            dragOverlay.setAlignment(Pos.CENTER);
            dragOverlay.setStyle("-fx-background-color: rgba(10, 8, 8, 0.75);");
            dragOverlay.setMouseTransparent(true);
        }

        if (!App.getStackPane().getChildren().contains(dragOverlay)) {
            App.getStackPane().getChildren().add(dragOverlay);

            dragOverlay.setOpacity(0);
            FadeTransition fadeIn = new FadeTransition(Duration.millis(120), dragOverlay);
            fadeIn.setFromValue(0);
            fadeIn.setToValue(1);
            fadeIn.play();

            Node card = dragOverlay.getChildren().get(0);
            card.setScaleX(0.93);
            card.setScaleY(0.93);
            ScaleTransition scaleIn = new ScaleTransition(Duration.millis(180), card);
            scaleIn.setToX(1.0);
            scaleIn.setToY(1.0);
            scaleIn.setInterpolator(Interpolator.SPLINE(0.2, 0, 0.2, 1));
            scaleIn.play();
        }
    }

    private void hideDragOverlay() {
        if (!dragOverlayVisible) return;
        dragOverlayVisible = false;

        FadeTransition fadeOut = new FadeTransition(Duration.millis(120), dragOverlay);
        fadeOut.setFromValue(1);
        fadeOut.setToValue(0);
        fadeOut.setOnFinished(e -> App.getStackPane().getChildren().remove(dragOverlay));
        fadeOut.play();
    }

    // ── Node builders ─────────────────────────────────────────────────────────

    private List<Node> buildMessageNodes(List<MessageReceivedPayload> msgs) {
        List<Node> nodes = new ArrayList<>(msgs.size());
        for (MessageReceivedPayload m : msgs) nodes.add(buildMessageItem(m));
        return nodes;
    }

    private EmojiMessageItem buildMessageItem(MessageReceivedPayload msg) {
        boolean isOwn = msg.getSenderId() != null
                && msg.getSenderId().equals(App.getUser().getUserId());
        EmojiMessageItem item = EmojiMessageItem.of(msg, isOwn);

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
        item.getBubble().setOnAddReaction((bubble, emoji) -> {
            if (emoji == null) {
                messageInputBox.getEmojiPicker().setOnEmojiSelected(picked -> {
                    sendAddReactionMessage(msg.getMessageId(), picked);
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
                sendAddReactionMessage(msg.getMessageId(), emoji);
            }
        });

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

    // ── Reply bar ─────────────────────────────────────────────────────────────

    private void openReplyBar(MessageReceivedPayload target) {
        if (replyTimeline != null) {
            replyTimeline.stop();
            replyTimeline = null;
        }
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
                        new KeyValue(replyBarSlot.prefHeightProperty(), REPLY_BAR_HEIGHT,
                                Interpolator.SPLINE(0.4, 0, 0.2, 1)),
                        new KeyValue(replyBarSlot.maxHeightProperty(), REPLY_BAR_HEIGHT,
                                Interpolator.SPLINE(0.4, 0, 0.2, 1)),
                        new KeyValue(replyBarSlot.minHeightProperty(), REPLY_BAR_HEIGHT,
                                Interpolator.SPLINE(0.4, 0, 0.2, 1)),
                        new KeyValue(bar.opacityProperty(), 1.0, Interpolator.EASE_OUT)));
        replyTimeline.setOnFinished(e -> {
            replyTimeline = null;
            syncAttachmentBarCorners();
        });
        replyTimeline.play();
        messageInputBox.refreshInputRowRadius();
    }

    private void collapseReplyBar() {
        if (replyTimeline != null) {
            replyTimeline.stop();
            replyTimeline = null;
        }
        messageInputBox.clearReply();
        if (replyBarSlot.getPrefHeight() <= 0) {
            replyBarSlot.getChildren().clear();
            return;
        }

        double fromH = replyBarSlot.getPrefHeight();
        List<KeyValue> s = new ArrayList<>(List.of(
                new KeyValue(replyBarSlot.prefHeightProperty(), fromH),
                new KeyValue(replyBarSlot.maxHeightProperty(), fromH),
                new KeyValue(replyBarSlot.minHeightProperty(), fromH)));
        List<KeyValue> e = new ArrayList<>(List.of(
                new KeyValue(replyBarSlot.prefHeightProperty(), 0.0,
                        Interpolator.SPLINE(0.4, 0, 0.2, 1)),
                new KeyValue(replyBarSlot.maxHeightProperty(), 0.0,
                        Interpolator.SPLINE(0.4, 0, 0.2, 1)),
                new KeyValue(replyBarSlot.minHeightProperty(), 0.0,
                        Interpolator.SPLINE(0.4, 0, 0.2, 1))));
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
        if (replyTimeline != null) {
            replyTimeline.stop();
            replyTimeline = null;
        }
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
        accent.setArcWidth(3);
        accent.setArcHeight(3);
        accent.setStyle("-fx-fill: -color-accent-8;");

        VBox content = new VBox(1);
        content.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(content, Priority.ALWAYS);

        String senderName = target.getSenderId() != null
                && target.getSenderId().equals(App.getUser().getUserId())
                ? "yourself" : "…";
        Label replyingTo = new Label("Replying to " + senderName);
        replyingTo.setStyle("""
                -fx-font-size: 12px;
                -fx-font-weight: bold;
                -fx-text-fill: -color-accent-emphasis;
                """);

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
            Label gifLabel = new Label(preview);
            gifLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");
            textBox.getChildren().add(gifLabel);
        } else {
            textBox.getChildren().addAll(TextUtils.convertToTextAndImageNodes(preview, 12));
        }
        content.getChildren().addAll(replyingTo, textBox);

        Button dismiss = new Button(null, new FontIcon(Feather.X));
        dismiss.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        dismiss.setFocusTraversable(false);
        dismiss.setOnAction(ev -> {
            currentReplyTarget = null;
            collapseReplyBar();
        });

        bar.getChildren().addAll(accent, content, dismiss);

        if (target.getSenderId() != null) {
            var cached = App.getAvatarCache().getIfPresent(target.getSenderId());
            if (cached != null && cached.username() != null) {
                replyingTo.setText("Replying to " + cached.username());
            } else {
                App.getAvatarCache().resolve(target.getSenderId()).thenAcceptAsync(user ->
                        Platform.runLater(() -> {
                            if (user != null && user.username() != null
                                    && currentReplyTarget == target)
                                replyingTo.setText("Replying to " + user.username());
                        }));
            }
        }
        return bar;
    }

    // ── Outbound ──────────────────────────────────────────────────────────────

    private void handleSubmit(String text, MessageReceivedPayload replyTarget) {
        List<AttachmentBarSlot.PendingAttachment> attachments =
                new ArrayList<>(attachmentBarSlot.getAttachments());

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
        if (message.isEmpty() || message.length() > 2000 || activeTextChannelId == null) return;
        MessageSentPayload.MessageSentPayloadBuilder builder =
                MessageSentPayload.builder()
                        .channelId(activeTextChannelId)
                        .content(message)
                        .messageType(MessageReceivedPayload.MessageType.TEXT);
        if (replyTarget != null) builder.repliedToId(replyTarget.getMessageId());
        App.getServices().installation().getWsClient()
                .send(WsMessageType.CHANNEL_MESSAGE_SENT, builder.build());
    }

    private void sendAttachments(String text,
                                 MessageReceivedPayload replyTarget,
                                 List<AttachmentBarSlot.PendingAttachment> attachments) {
        if (activeTextChannelId == null) return;
        UUID channelId = activeTextChannelId;
        Thread.ofVirtual().start(() -> {
            boolean first = true;
            for (AttachmentBarSlot.PendingAttachment att : attachments) {
                try {
                    var uploaded = App.getServices().installation().getMessageService()
                            .uploadAttachment(channelId, att.file(), att.mimeType());
                    String content = first && text != null && !text.isBlank() ? text.trim() : "";
                    MessageSentPayload.MessageSentPayloadBuilder builder =
                            MessageSentPayload.builder()
                                    .channelId(channelId)
                                    .content(content)
                                    .hasAttachments(true)
                                    .attachmentId(uploaded.getAttachmentId());
                    if (first && replyTarget != null)
                        builder.repliedToId(replyTarget.getMessageId());
                    App.getServices().installation().getWsClient()
                            .send(WsMessageType.CHANNEL_MESSAGE_SENT, builder.build());
                    first = false;
                } catch (Exception e) {
                    log.error("Failed to upload attachment '{}': {}", att.fileName(), e.getMessage(), e);
                }
            }
        });
    }

    // ── Code messages ───────────────────────────────────────────────────────

    /** Opens the code editor to compose a new code message. */
    private void openCodeComposer(String prefill) {
        if (activeTextChannelId == null) return;
        CodeLanguage guessed = prefill != null
                ? CodeDetector.guessLanguage(prefill) : CodeLanguage.PLAIN_TEXT;
        MessageReceivedPayload reply = currentReplyTarget;
        App.showModal(new CodeMessageModal(prefill, guessed, false, (code, language) -> {
            sendCode(code, language, reply);
            if (currentReplyTarget != null) {
                currentReplyTarget = null;
                collapseReplyBar();
            }
        }));
    }

    /** Opens the code editor pre-filled to edit an existing code message. */
    private void openCodeEditor(MessageReceivedPayload msg) {
        App.showModal(new CodeMessageModal(msg.getContent(),
                CodeLanguage.fromString(msg.getCodeLanguage()), true,
                (code, language) -> sendEditCode(msg.getMessageId(), code, language)));
    }

    private void sendCode(String code, CodeLanguage language, MessageReceivedPayload replyTarget) {
        if (code == null || code.isBlank()
                || code.length() > CodeMessageModal.MAX_LENGTH || activeTextChannelId == null) return;
        MessageSentPayload.MessageSentPayloadBuilder builder = MessageSentPayload.builder()
                .channelId(activeTextChannelId)
                .content(code)
                .messageType(MessageReceivedPayload.MessageType.CODE)
                .codeLanguage(language.name());
        if (replyTarget != null) builder.repliedToId(replyTarget.getMessageId());
        App.getServices().installation().getWsClient()
                .send(WsMessageType.CHANNEL_MESSAGE_SENT, builder.build());
    }

    private void sendEditCode(UUID messageId, String content, CodeLanguage language) {
        if (content == null || content.isBlank() || content.length() > CodeMessageModal.MAX_LENGTH) return;
        App.getServices().installation().getWsClient()
                .send(WsMessageType.CHANNEL_MESSAGE_EDIT,
                        MessageEditPayload.builder()
                                .messageId(messageId)
                                .content(content)
                                .codeLanguage(language.name())
                                .build());
    }

    private void sendGif(GifResult gif) {
        if (activeTextChannelId == null) return;
        String url = gif.getFullUrl() != null && !gif.getFullUrl().isBlank()
                ? gif.getFullUrl() : gif.getFullMp4Url();
        App.getServices().installation().getWsClient().send(
                WsMessageType.CHANNEL_MESSAGE_SENT,
                MessageSentPayload.builder()
                        .channelId(activeTextChannelId)
                        .content(url)
                        .messageType(MessageReceivedPayload.MessageType.GIF)
                        .build());
    }

    private void sendTypingEvent() {
        if (activeTextChannelId == null) return;
        App.getServices().installation().getWsClient()
                .send(WsMessageType.CHANNEL_TYPING_INDICATOR,
                        ChannelTypingPayload.builder()
                                .channelId(activeTextChannelId).build());
    }

    private void sendDeleteMessage(UUID messageId) {
        App.getServices().installation().getWsClient()
                .send(WsMessageType.CHANNEL_MESSAGE_DELETE,
                        MessageDeletePayload.builder().messageId(messageId).build());
    }

    private void sendEditMessage(UUID messageId, String newContent) {
        App.getServices().installation().getWsClient()
                .send(WsMessageType.CHANNEL_MESSAGE_EDIT,
                        MessageEditPayload.builder()
                                .messageId(messageId)
                                .content(newContent)
                                .build());
    }

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

        double viewportH = chatScrollPane.getViewportBounds().getHeight();
        double contentH  = chatMessagesContainer.getHeight();
        double scrollable = Math.max(1, contentH - viewportH);
        double pixelsBefore = chatScrollPane.getVvalue() * scrollable;
        boolean wasAtBottom = isAtBottom;

        MessageEditBox editBox = new MessageEditBox(msg.getContent() != null ? msg.getContent() : "", msg.isHasAttachments());

        editBox.setOnSave(trimmed -> {
            if (!trimmed.equals(msg.getContent())) sendEditMessage(msg.getMessageId(), trimmed);
        });
        editBox.setOnDismiss(() ->
                cancelEditMode(msg.getMessageId(), item, editBox, oldBubble, contentCol));

        suppressScrollFetch = true;
        oldBubble.setVisible(false);
        oldBubble.setManaged(false);
        contentCol.getChildren().add(bubbleIdx, editBox);

        editBox.activate();

        ChangeListener<Number>[] l = new ChangeListener[1];
        l[0] = (obs, oldH, newH) -> {
            chatMessagesContainer.heightProperty().removeListener(l[0]);
            double newScrollable = Math.max(1, newH.doubleValue() - viewportH);
            if (wasAtBottom) {
                chatScrollPane.setVvalue(chatScrollPane.getVmax());
            } else {
                chatScrollPane.setVvalue(clamp(pixelsBefore / newScrollable,
                        chatScrollPane.getVmin(), chatScrollPane.getVmax()));
            }
            suppressScrollFetch = false;
        };
        chatMessagesContainer.heightProperty().addListener(l[0]);
    }

    private void cancelEditMode(UUID messageId, EmojiMessageItem item, VBox wrapper,
                                EmojiMessageContent oldBubble, VBox contentCol) {
        activeEditItems.remove(messageId);

        double viewportH  = chatScrollPane.getViewportBounds().getHeight();
        double contentH   = chatMessagesContainer.getHeight();
        double scrollable = Math.max(1, contentH - viewportH);
        double pixelsBefore = chatScrollPane.getVvalue() * scrollable;

        suppressScrollFetch = true;
        contentCol.getChildren().remove(wrapper);
        oldBubble.setVisible(true);
        oldBubble.setManaged(true);

        ChangeListener<Number>[] l = new ChangeListener[1];
        l[0] = (obs, oldH, newH) -> {
            chatMessagesContainer.heightProperty().removeListener(l[0]);
            double newScrollable = Math.max(1, newH.doubleValue() - viewportH);
            chatScrollPane.setVvalue(clamp(pixelsBefore / newScrollable,
                    chatScrollPane.getVmin(), chatScrollPane.getVmax()));
            suppressScrollFetch = false;
        };
        chatMessagesContainer.heightProperty().addListener(l[0]);
    }

    public void updateMessage(ChannelMessageEditedPayload p) {
        Platform.runLater(() -> {
            EmojiMessageItem item = messageItemMap.get(p.getMessageId());
            if (item == null) return;

            MessageReceivedPayload originalPayload = item.getPayload();
            VBox contentCol = item.getContentCol();
            EmojiMessageContent oldBubble = item.getBubble();

            // Only update text-type messages (not GIFs)
            if (originalPayload.getMessageType() == MessageReceivedPayload.MessageType.GIF) return;

            int bubbleIdx = contentCol.getChildren().indexOf(oldBubble);
            if (bubbleIdx < 0) return;

            EmojiMessageContent newBubble;
            if (originalPayload.getMessageType() == MessageReceivedPayload.MessageType.CODE) {
                String lang = p.getCodeLanguage() != null
                        ? p.getCodeLanguage() : originalPayload.getCodeLanguage();
                newBubble = EmojiMessageContent.ofCode(p.getContent(), lang);
                originalPayload.setCodeLanguage(lang);
            } else {
                newBubble = EmojiMessageContent.of(p.getContent());
            }
            VBox.setMargin(newBubble, new Insets(0, 0, 0, -EmojiMessageContent.H_PAD));

            boolean isOwn = originalPayload.getSenderId() != null
                    && originalPayload.getSenderId().equals(App.getUser().getUserId());

            newBubble.setDeleteVisibleSupplier(() -> isOwn || App.getPermissionManager().has(Permission.DELETE_OTHERS_MSGS));
            newBubble.setOnDelete(() -> sendDeleteMessage(originalPayload.getMessageId()));
            newBubble.setOnReply(bubble -> openReplyBar(originalPayload));
            newBubble.setOnAllPopupsClosed(item::onAllPopupsClosed);
            if (isOwn) {
                newBubble.setEditVisible(true);
                newBubble.setOnEdit(() -> startEditMode(originalPayload, item));
            }
            newBubble.setOnAddReaction((bubble, emoji) -> {
                if (emoji == null) {
                    messageInputBox.getEmojiPicker().setOnEmojiSelected(picked -> {
                        sendAddReactionMessage(originalPayload.getMessageId(), picked);
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
                    sendAddReactionMessage(originalPayload.getMessageId(), emoji);
                }
            });

            // Detach the new bubble's bar (mirrors buildMessageItem), then swap out
            // the old detached bar so only one reaction row exists after the edit.
            EmojiReactionBar newBar = newBubble.detachReactionBar();
            VBox.setMargin(newBar, new Insets(0, 0, 0, 0));

            EmojiReactionBar existingBar = null;
            for (Node n : contentCol.getChildren()) {
                if (n instanceof EmojiReactionBar rb) { existingBar = rb; break; }
            }
            if (existingBar != null) {
                newBar.getReactions().putAll(existingBar.getReactions());
                contentCol.getChildren().remove(existingBar);
            }
            newBar.setOnReactionAdded(emojiChar ->
                    EmojiData.emojiFromUnicodeString(emojiChar).ifPresent(emoji ->
                            sendAddReactionMessage(originalPayload.getMessageId(), emoji)));
            newBar.setOnReactionRemoved(emojiChar ->
                    EmojiData.emojiFromUnicodeString(emojiChar).ifPresent(emoji ->
                            sendRemoveReactionMessage(originalPayload.getMessageId(), emoji)));
            newBar.setOnPickerRequested(coords -> {
                messageInputBox.getEmojiPicker().setOnEmojiSelected(emoji -> {
                    sendAddReactionMessage(originalPayload.getMessageId(), emoji);
                    messageInputBox.getEmojiPicker().hide();
                });
                messageInputBox.getEmojiPicker().show(
                        item.getScene().getWindow(), coords[0], coords[1]);
            });

            originalPayload.setContent(p.getContent());
            originalPayload.setEdited(true);

            contentCol.getChildren().set(bubbleIdx, newBubble);
            contentCol.getChildren().add(newBar);
            item.setBubble(newBubble);
            item.setEdited(true);

            if (!newBar.getReactions().isEmpty()) {
                newBar.rebuildWithoutAnimation();
            }
        });
    }

    private void sendAddReactionMessage(UUID messageId,
                                        io.github.b077as.emojifx.Emoji emoji) {
        pendingReactionMessageId = messageId.toString();
        pendingReactionEmoji = emoji.getUnified();
        pendingReactionIsAdd = true;
        reactionService.restart();
    }

    private void sendRemoveReactionMessage(UUID messageId,
                                           io.github.b077as.emojifx.Emoji emoji) {
        pendingReactionMessageId = messageId.toString();
        pendingReactionEmoji = emoji.getUnified();
        pendingReactionIsAdd = false;
        reactionService.restart();
    }

    // ── Misc helpers ──────────────────────────────────────────────────────────

    private Label buildLoadingChip() {
        Label chip = new Label("Loading messages…");
        chip.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;" +
                "-fx-padding: 4 12 4 12; -fx-background-color: -color-bg-subtle;" +
                "-fx-background-radius: 10px;");
        chip.setMaxWidth(Double.MAX_VALUE);
        chip.setAlignment(Pos.CENTER);
        VBox.setMargin(chip, new Insets(4, 16, 4, 16));
        return chip;
    }

    public static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
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

    // ── View construction ─────────────────────────────────────────────────────

    /**
     * Builds the chat content area (scroll pane + input row).
     * No header is created here — the header lives at the ChatSection level.
     */
    private VBox createChatView() {
        VBox container = new VBox();
        VBox.setVgrow(container, Priority.ALWAYS);

        chatScrollPane = new ScrollPane();
        chatScrollPane.setFitToWidth(true);
        chatScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        chatScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);
        chatScrollPane.setStyle("-fx-background-color: -color-bg-default;");

        chatMessagesContainer = new VBox(12);
        chatMessagesContainer.setPadding(new Insets(16, 16, 8, 16));
        chatMessagesContainer.setAlignment(Pos.BOTTOM_LEFT);
        chatScrollPane.setContent(chatMessagesContainer);

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
            chatScrollPane.setVvalue(chatScrollPane.getVmax());
        });

        scrollPaneWrapper = new StackPane(chatScrollPane, scrollToBottomBtn);
        StackPane.setAlignment(scrollToBottomBtn, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(scrollToBottomBtn, new Insets(0, 24, 14, 0));
        VBox.setVgrow(scrollPaneWrapper, Priority.ALWAYS);

        // ── Reply bar slot ────────────────────────────────────────────────────
        replyBarSlot = new VBox();
        replyBarSlot.setMinHeight(0);
        replyBarSlot.setPrefHeight(0);
        replyBarSlot.setMaxHeight(0);
        replyBarSlot.setPadding(new Insets(0, INPUT_H_PAD, 0, INPUT_H_PAD));
        replyBarClip = new Rectangle(0, 0);
        replyBarSlot.setClip(replyBarClip);
        replyBarSlot.widthProperty().addListener(
                (o, ov, w) -> replyBarClip.setWidth(w.doubleValue()));
        replyBarSlot.prefHeightProperty().addListener(
                (o, ov, h) -> replyBarClip.setHeight(h.doubleValue()));

        // ── Attachment bar slot ───────────────────────────────────────────────
        attachmentBarSlot = new AttachmentBarSlot(errorMsg -> {
            log.warn("Attachment error: {}", errorMsg);
            Platform.runLater(() -> {
                CustomNotification notification = new CustomNotification(
                        "Attachment Error",
                        errorMsg,
                        new FontIcon(MaterialDesignC.CANCEL));
                notification.showNotification();
            });
        });
        attachmentBarSlot.setOnChanged(() ->
                Platform.runLater(messageInputBox::refreshInputRowRadius));

        welcomeView = createWelcomeView();

        // ── Message input ─────────────────────────────────────────────────────
        messageInputBox = new MessageInputBox(new MessageInputBox.MessageInputListener() {
            @Override
            public void onMessageSubmit(String text, MessageReceivedPayload replyTarget) {
                handleSubmit(text, replyTarget);
                if (currentReplyTarget != null) {
                    currentReplyTarget = null;
                    collapseReplyBar();
                }
            }

            @Override
            public void onTyping() {
                sendTypingEvent();
            }

            @Override
            public void onCodeRequested(String prefill) {
                openCodeComposer(prefill);
            }
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

        // Initially hidden
        scrollPaneWrapper.setVisible(false);
        scrollPaneWrapper.setManaged(false);
        typingRow.setVisible(false);
        typingRow.setManaged(false);
        replyBarSlot.setVisible(false);
        replyBarSlot.setManaged(false);
        messageInputBox.setVisible(false);
        messageInputBox.setManaged(false);

        container.getChildren().addAll(
                welcomeView,
                scrollPaneWrapper,
                typingRow,
                replyBarSlot,
                attachmentBarSlot,
                messageInputBox);
        return container;
    }

    private VBox createWelcomeView() {
        VBox view = new VBox(12);
        view.setAlignment(Pos.CENTER);
        VBox.setVgrow(view, Priority.ALWAYS);
        view.setStyle("-fx-background-color: -color-bg-default;");
        Label icon = new Label("#");
        icon.setStyle("-fx-font-size: 72px; -fx-font-weight: bold;" +
                "-fx-text-fill: -color-accent-emphasis; -fx-opacity: 0.12;");
        Label title = new Label("Welcome to the server");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;" +
                "-fx-text-fill: -color-fg-default;");
        Label subtitle = new Label("Select a channel on the left to start chatting.");
        subtitle.setStyle("-fx-font-size: 13px; -fx-text-fill: -color-fg-subtle;");
        view.getChildren().addAll(icon, title, subtitle);
        return view;
    }

    // ── Friends panel toggle ──────────────────────────────────────────────────

    private void toggleFriendsPanel() {
        if (sidebarSlot == null) return;
        friendsSectionVisible = !friendsSectionVisible;
        Timeline tl = new Timeline();
        if (friendsSectionVisible) {
            tl.getKeyFrames().addAll(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(sidebarSlot.prefWidthProperty(), 0)),
                    new KeyFrame(Duration.millis(250),
                            new KeyValue(sidebarSlot.prefWidthProperty(), FRIENDS_SECTION_WIDTH, Interpolator.EASE_BOTH)));
        } else {
            tl.getKeyFrames().addAll(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(sidebarSlot.prefWidthProperty(), FRIENDS_SECTION_WIDTH)),
                    new KeyFrame(Duration.millis(250),
                            new KeyValue(sidebarSlot.prefWidthProperty(), 0, Interpolator.EASE_BOTH)));
        }
        tl.play();
    }

    // ── Misc ──────────────────────────────────────────────────────────────────

    private void syncAttachmentBarCorners() {
        boolean replyOpen = replyBarSlot.getPrefHeight() > 1.0;
        attachmentBarSlot.setTopCornersRounded(!replyOpen);
    }

    // ── Scroll freeze / save / restore ────────────────────────────────────────

    public void freezeScroll() {
        if (activeTextChannelId == null) return;
        frozenVvalue = chatScrollPane.getVvalue();
        scrollFrozen = true;

        frozenHeightListener = (obs, oldH, newH) -> {
            if (!scrollFrozen || isAtBottom) return;
            double dH = newH.doubleValue() - oldH.doubleValue();
            if (Math.abs(dH) < 0.5) return;
            double viewportH = chatScrollPane.getViewportBounds().getHeight();
            double oldScrollable = Math.max(1.0, oldH.doubleValue() - viewportH);
            double pixelPos = frozenVvalue * oldScrollable;
            double newScrollable = Math.max(1.0, newH.doubleValue() - viewportH);
            frozenVvalue = clamp(pixelPos / newScrollable,
                    chatScrollPane.getVmin(), chatScrollPane.getVmax());
            inScrollCorrection = true;
            chatScrollPane.setVvalue(frozenVvalue);
            inScrollCorrection = false;
        };
        chatMessagesContainer.heightProperty().addListener(frozenHeightListener);
    }

    public void unfreezeScroll() {
        scrollFrozen = false;
        if (frozenHeightListener != null) {
            chatMessagesContainer.heightProperty().removeListener(frozenHeightListener);
            frozenHeightListener = null;
        }
        double v = frozenVvalue;
        frozenVvalue = -1.0;
        if (v >= 0 && activeTextChannelId != null) {
            chatScrollPane.setVvalue(v);
            isAtBottom = v >= chatScrollPane.getVmax() - 0.02;
        }
    }

    public void saveScrollPosition() {
        if (activeTextChannelId != null) {
            savedVvalue = chatScrollPane.getVvalue();
            savedContentHeight = chatMessagesContainer.getHeight();
            savedViewportHeight = chatScrollPane.getViewportBounds().getHeight();
            isAtBottom = savedVvalue >= chatScrollPane.getVmax() - 0.02;
            log.debug("[scroll] ChatSection.saveScrollPosition channel={} vvalue={} contentH={} viewportH={}",
                    activeTextChannelId, savedVvalue, savedContentHeight, savedViewportHeight);
        } else {
            log.debug("[scroll] ChatSection.saveScrollPosition skipped — no active channel");
        }
    }

    public void restoreScrollNow() {
        if (savedVvalue < 0 || activeTextChannelId == null) return;
        double v = savedVvalue;
        savedVvalue = -1.0;
        chatScrollPane.setVvalue(v);
        isAtBottom = v >= chatScrollPane.getVmax() - 0.02;
    }

    public double getSavedScrollVvalue() {
        return savedVvalue;
    }

    public void directSetVvalue(double v) {
        if (activeTextChannelId == null || v < 0) return;
        chatScrollPane.setVvalue(v);
        isAtBottom = v >= chatScrollPane.getVmax() - 0.02;
    }

    public void notifyPageResumed() {
        if (activeTextChannelId == null) {
            log.debug("[scroll] ChatSection.notifyPageResumed skipped — no active channel");
            return;
        }

        // Unlike a fresh selection, resuming to a channel that was already active does NOT mark
        // it read immediately: messages may have arrived while this page wasn't visible (e.g. the
        // user was on the Home page), and they haven't actually seen those yet. The logic below
        // marks it read right away only if there's no scrollbar (everything already visible), and
        // otherwise waits for the scroll listener to confirm the user has scrolled to the bottom.

        // The container may have been detached from the scene (e.g. user was on the Home page)
        // while messages were appended to it, so its layout bounds are stale until a fresh pass
        // runs. Force one synchronously so the height check below reflects reality, not whatever
        // it measured before the last batch of messages arrived.
        chatScrollPane.applyCss();
        chatScrollPane.layout();

        double currentViewportH = chatScrollPane.getViewportBounds().getHeight();
        double newContentHeight = chatMessagesContainer.getHeight();

        // No scrollbar needed (few enough messages to fit the viewport) means every message is
        // already fully visible — there is no scroll gesture the user could perform to "confirm"
        // they've seen it, so treat it as read immediately instead of waiting on a vvalue change
        // event that may never fire (setting vvalue to a value it already holds fires no event).
        if (currentViewportH > 0 && newContentHeight <= currentViewportH) {
            savedVvalue = -1.0;
            savedContentHeight = -1.0;
            savedViewportHeight = -1.0;
            pendingRestoreVvalue = -1.0;
            isAtBottom = true;
            updateScrollToBottomBtn();
            rehookAllGifCells();
            log.debug("[scroll] ChatSection.notifyPageResumed — content fits viewport, marking at-bottom immediately");
            return;
        }

        if (savedVvalue < 0) {
            log.debug("[scroll] ChatSection.notifyPageResumed skipped — savedVvalue={} channel={}", savedVvalue, activeTextChannelId);
            return;
        }

        double target = savedVvalue;
        double oldContentHeight = savedContentHeight;
        double oldViewportHeight = savedViewportHeight;
        savedVvalue = -1.0;
        savedContentHeight = -1.0;
        savedViewportHeight = -1.0;

        if (currentViewportH > 0) {
            target = reanchorTargetVvalue(target, oldContentHeight, oldViewportHeight, newContentHeight, currentViewportH);
        }
        log.debug("[scroll] ChatSection.notifyPageResumed channel={} target={} viewportHeightNow={}", activeTextChannelId, target, currentViewportH);

        if (currentViewportH > 0) {
            if (target < 0.005) {
                // Restoring to top: layout will reset vvalue to 0 anyway and no change event
                // will fire (already 0), so there is nothing to re-apply.
                isAtBottom = false;
                pendingRestoreVvalue = -1.0;
                log.debug("[scroll] ChatSection restore-to-top confirmed immediately");
            } else {
                // Non-zero target: when the page is re-shown the FX layout pass resets vvalue
                // to 0. The scroll listener catches that deviation and re-applies the target.
                pendingRestoreVvalue = target;
                isAtBottom = false;
                log.debug("[scroll] ChatSection restore pending — scroll listener will re-apply on layout reset");
            }
        } else {
            pendingRestoreVvalue = target;
            isAtBottom = false;
            ChangeListener<javafx.geometry.Bounds>[] l = new ChangeListener[1];
            l[0] = (obs, oldBounds, newBounds) -> {
                if (newBounds.getHeight() <= 0) return;
                chatScrollPane.viewportBoundsProperty().removeListener(l[0]);
                log.debug("[scroll] ChatSection viewportBounds listener fired — restoring vvalue={}", pendingRestoreVvalue);
                double liveContentHeight = chatMessagesContainer.getHeight();
                if (liveContentHeight <= newBounds.getHeight()) {
                    // Content fits after all (e.g. window grew, or few messages) — nothing to
                    // scroll to, and setting the same vvalue again wouldn't fire a change event.
                    pendingRestoreVvalue = -1.0;
                    isAtBottom = true;
                    updateScrollToBottomBtn();
                    return;
                }
                pendingRestoreVvalue = reanchorTargetVvalue(
                        pendingRestoreVvalue, oldContentHeight, oldViewportHeight, liveContentHeight, newBounds.getHeight());
                chatScrollPane.setVvalue(pendingRestoreVvalue);
            };
            chatScrollPane.viewportBoundsProperty().addListener(l[0]);
        }
        rehookAllGifCells();
    }
}