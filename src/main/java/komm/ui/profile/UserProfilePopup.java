package komm.ui.profile;

import atlantafx.base.theme.Styles;
import io.github.b077as.emojifx.EmojiData;
import io.github.b077as.emojifx.util.TextUtils;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.Window;
import javafx.util.Duration;
import komm.App;
import komm.model.dto.summary.CustomRoleSummary;
import komm.model.dto.summary.FriendSummary;
import komm.model.dto.summary.MainUserSummary;
import komm.model.dto.summary.ServerMemberSummary;
import komm.model.dto.summary.ServerSummary;
import komm.model.dto.summary.UserSummary;
import komm.model.permissions.Permission;
import komm.ui.avatar.AvatarCache;
import komm.ui.avatar.AvatarColor;
import komm.ui.customnodes.BadgeUi;
import komm.ui.modals.AssignRoleModal;
import komm.ui.modals.ConfirmationModal;
import komm.ui.modals.UserProfileModal;
import komm.ui.pages.HomePage;
import komm.ui.pages.ServerPage;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Slf4j
public class UserProfilePopup extends Popup {

    private static final double WIDTH = 280;
    private static final double AVATAR_SIZE = 72;
    private static final double BANNER_H = 52;
    private static final double HEADER_H = BANNER_H + AVATAR_SIZE / 2.0; // 88

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy");

    private static final UserProfilePopup INSTANCE = new UserProfilePopup();

    // ── Mutable UI refs ───────────────────────────────────────────────────────
    private final StackPane avatarInner;
    private final StackPane avatarRing;
    private final Circle avatarStatusDot;
    private final Label usernameLbl;
    private final HBox customStatusBubble;
    private final StackPane customStatusEmojiSlot;
    private final Label customStatusLabel;
    private final VBox contentBox;
    private final VBox loadingBox;
    private final MenuButton actionsMenuBtn;

    private final HBox liveBanner;
    private final Circle liveDot;
    private final FontIcon liveCtaIcon;
    private Timeline liveDotPulse;

    private volatile UUID currentUserId;
    private volatile UUID currentServerId;
    private Runnable onChanged;
    private Runnable pendingJoinLiveAction;

    // ── Background services ───────────────────────────────────────────────────

    private UUID pendingProfileId;

    private final Service<UserSummary> profileLoadService = new Service<>() {
        @Override
        protected Task<UserSummary> createTask() {
            final UUID id = pendingProfileId;
            return new Task<>() {
                @Override
                protected UserSummary call() throws Exception {
                    return App.getServices().hub().getUserService().getUserSummary(id);
                }
            };
        }
    };

    private UserSummary pendingBadgeUser;
    private VBox pendingBadgeContainer;

    private final Service<ServerMemberSummary> roleBadgesService = new Service<>() {
        @Override
        protected Task<ServerMemberSummary> createTask() {
            final UUID userId = pendingBadgeUser.getUserId();
            return new Task<>() {
                @Override
                protected ServerMemberSummary call() throws Exception {
                    try { App.getServices().installation(); } catch (IllegalStateException e) { return null; }
                    return App.getServices().installation()
                            .getInstallationPermissionService().getMember(userId);
                }
            };
        }
    };

    private ThrowingRunnable pendingAction;
    private UUID pendingActionUserId;

    private final Service<UserSummary> actionService = new Service<>() {
        @Override
        protected Task<UserSummary> createTask() {
            final ThrowingRunnable action = pendingAction;
            final UUID userId = pendingActionUserId;
            return new Task<>() {
                @Override
                protected UserSummary call() throws Exception {
                    action.run();
                    return App.getServices().hub().getUserService().getUserSummary(userId);
                }
            };
        }
    };

    public static UserProfilePopup getInstance() {
        return INSTANCE;
    }

    // ─────────────────────────────────────────────────────────────────────────

    private UserProfilePopup() {
        setAutoHide(true);
        setHideOnEscape(true);

        profileLoadService.setOnSucceeded(e -> {
            if (pendingProfileId.equals(currentUserId)) applyUserSummary(profileLoadService.getValue());
        });
        profileLoadService.setOnFailed(e ->  {
            log.error("Failed to load profile for {}: {}", pendingProfileId, profileLoadService.getException().getMessage());
            if (pendingProfileId.equals(currentUserId)) showError();
        });

        roleBadgesService.setOnSucceeded(e -> applyRoleBadges(roleBadgesService.getValue()));
        roleBadgesService.setOnFailed(e ->
                log.debug("Could not load member roles for badge display: {}", roleBadgesService.getException().getMessage()));

        actionService.setOnSucceeded(e -> {
            if (pendingActionUserId.equals(currentUserId)) {
                applyUserSummary(actionService.getValue());
                if (onChanged != null) onChanged.run();
            }
        });
        actionService.setOnFailed(e -> log.error("Profile action failed: {}", actionService.getException().getMessage()));

        double innerSize = AVATAR_SIZE - 6;
        avatarInner = new StackPane();
        avatarInner.setPrefSize(innerSize, innerSize);
        avatarInner.setMinSize(innerSize, innerSize);
        avatarInner.setMaxSize(innerSize, innerSize);

        // Bigger status dot
        avatarStatusDot = new Circle(8.0);
        avatarStatusDot.getStyleClass().add(MainUserSummary.UserStatus.UNKNOWN.getCssClass());
        avatarStatusDot.setStyle("-fx-stroke: -color-bg-popup; -fx-stroke-width: 3;");

        usernameLbl = new Label("...");
        usernameLbl.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        // ── Custom status ────────────────────────────────────────────────────
        // The emoji floats in a small speech bubble next to the avatar (the
        // user "saying" it); the message itself lives in the popup body as a
        // quiet italic line under the presence text.
        customStatusEmojiSlot = new StackPane();
        customStatusEmojiSlot.setMinSize(18, 18);
        customStatusEmojiSlot.setMaxSize(18, 18);

        customStatusLabel = new Label();
        customStatusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted; -fx-font-style: italic;");
        customStatusLabel.setWrapText(true);
        customStatusLabel.setVisible(false);
        customStatusLabel.setManaged(false);

        HBox bubbleBody = new HBox(customStatusEmojiSlot);
        bubbleBody.setAlignment(Pos.CENTER);
        bubbleBody.setPadding(new Insets(6));
        bubbleBody.getStyleClass().add("status-bubble");

        Path bubbleTail = new Path(new MoveTo(7, 0), new LineTo(0, 6), new LineTo(7, 9));
        bubbleTail.getStyleClass().add("status-bubble-tail");
        bubbleTail.setTranslateX(1.5); // overlap the bubble border so they merge into one shape

        customStatusBubble = new HBox(bubbleTail, bubbleBody);
        customStatusBubble.setAlignment(Pos.BOTTOM_LEFT);
        HBox.setMargin(bubbleTail, new Insets(0, 0, 10, 0));
        customStatusBubble.setMaxWidth(Region.USE_PREF_SIZE);
        customStatusBubble.setMaxHeight(Region.USE_PREF_SIZE);
        customStatusBubble.setPickOnBounds(false);
        customStatusBubble.setVisible(false);
        customStatusBubble.setManaged(false);

        contentBox = new VBox();
        contentBox.setFillWidth(true);

        ProgressIndicator pi = new ProgressIndicator();
        pi.setPrefSize(22, 22);
        loadingBox = new VBox(pi);
        loadingBox.setAlignment(Pos.CENTER);
        loadingBox.setPadding(new Insets(10, 0, 12, 0));

        // Actions menu button with dots icon, placed in header
        actionsMenuBtn = new MenuButton();
        actionsMenuBtn.setGraphic(new FontIcon(MaterialDesignD.DOTS_HORIZONTAL));
        actionsMenuBtn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);
        actionsMenuBtn.setVisible(false);

        avatarRing = new StackPane();

        // ── "Join Stream" quick action — shown only when the profile's user is
        // currently screen sharing in the channel the viewer is connected to.
        // One row, two zones: status text on the left, a single chevron on the
        // right — the standard "navigable row" pattern (settings rows, DM list
        // entries), so there's exactly one alignment to read, not several.
        // The whole card is the click target (same pattern as connected-user-card
        // / friend-item rows), colors come entirely from -color-accent-* variables.
        liveDot = new Circle(4);
        liveDot.setStyle("-fx-fill: -color-accent-emphasis;" +
                "-fx-effect: dropshadow(gaussian, -color-accent-emphasis, 5, 0.7, 0, 0);");
        Label liveLbl = new Label("LIVE");
        liveLbl.setStyle("-fx-text-fill: -color-accent-fg; -fx-font-size: 11px; -fx-font-weight: bold;");

        Label sharingLbl = new Label("Screen sharing");
        sharingLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");

        HBox liveInfo = new HBox(6, liveDot, liveLbl, sharingLbl);
        liveInfo.setAlignment(Pos.CENTER_LEFT);

        Region liveSpacer = new Region();
        HBox.setHgrow(liveSpacer, Priority.ALWAYS);

        liveCtaIcon = new FontIcon(Feather.CHEVRON_RIGHT);
        liveCtaIcon.getStyleClass().add("live-share-cta");

        liveBanner = new HBox(8, liveInfo, liveSpacer, liveCtaIcon);
        liveBanner.setAlignment(Pos.CENTER_LEFT);
        liveBanner.setPadding(new Insets(9, 10, 9, 10));
        liveBanner.getStyleClass().add("live-share-card");
        liveBanner.setOnMouseClicked(e -> {
            Runnable action = pendingJoinLiveAction;
            hide();
            if (action != null) action.run();
        });
        liveBanner.setVisible(false);
        liveBanner.setManaged(false);

        getContent().add(buildRoot());
    }

    // ── Root layout ───────────────────────────────────────────────────────────

    private VBox buildRoot() {
        // ── Banner ────────────────────────────────────────────────────────────
        Region banner = new Region();
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.setMinHeight(BANNER_H);
        banner.setMaxHeight(BANNER_H);
        banner.setPrefHeight(BANNER_H);
        banner.setStyle("-fx-background-color: -color-accent-8; -fx-background-radius: 10px 10px 0 0;");

        // ── Avatar ring (disc at bottom-left, overlapping banner) ───────
        avatarRing.getChildren().setAll(avatarInner, avatarStatusDot);
        avatarRing.setPrefSize(AVATAR_SIZE, AVATAR_SIZE);
        avatarRing.setMinSize(AVATAR_SIZE, AVATAR_SIZE);
        avatarRing.setMaxSize(AVATAR_SIZE, AVATAR_SIZE);
        StackPane.setAlignment(avatarStatusDot, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(avatarStatusDot, new Insets(0, 0, 2, 0));

        // ── Header: banner at top, ring at bottom-left, menu btn at bottom-right,
        // status speech bubble floating over the banner edge next to the avatar
        StackPane header = new StackPane(banner, avatarRing, customStatusBubble, actionsMenuBtn);
        header.setMinHeight(HEADER_H);
        header.setPrefHeight(HEADER_H);
        header.setMaxHeight(HEADER_H);
        StackPane.setAlignment(banner, Pos.TOP_LEFT);
        StackPane.setAlignment(avatarRing, Pos.BOTTOM_LEFT);
        StackPane.setMargin(avatarRing, new Insets(0, 0, 0, 12));
        StackPane.setAlignment(actionsMenuBtn, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(actionsMenuBtn, new Insets(0, 8, 4, 0));
        StackPane.setAlignment(customStatusBubble, Pos.BOTTOM_LEFT);
        StackPane.setMargin(customStatusBubble, new Insets(4, 8, 34, 90));
        avatarRing.setStyle("-fx-border-color: -color-bg-default; -fx-border-width: 3px; -fx-border-radius: " + (AVATAR_SIZE / 2.0) + "px; -fx-background-color: -color-bg-default; -fx-background-radius: " + (AVATAR_SIZE / 2.0) + "px;");

        // ── Name + custom status message ──────────────────────────────────────
        // Presence itself is already shown by the dot on the avatar.
        VBox nameSection = new VBox(3, usernameLbl, customStatusLabel);
        nameSection.setPadding(new Insets(6, 12, 8, 12));
        VBox.setMargin(customStatusLabel, new Insets(2, 0, 0, 0));

        Separator sep = new Separator();
        sep.setPadding(new Insets(0, 8, 0, 8));

        contentBox.getChildren().setAll(loadingBox);

        VBox.setMargin(liveBanner, new Insets(0, 12, 8, 12));

        VBox root = new VBox(header, nameSection, liveBanner, sep, contentBox);
        root.setFillWidth(true);
        root.getStyleClass().add("custom-pop-up");
        root.setMinWidth(WIDTH);
        root.setMaxWidth(WIDTH);
        root.setPrefWidth(WIDTH);
        return root;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Show the profile popup in a server context — enables role badges and "Assign Role" action.
     */
    public void show(Window owner, double screenX, double screenY, UUID userId, UUID serverId, Runnable onChanged) {
        if (userId == null) return;
        this.currentServerId = serverId;
        this.pendingJoinLiveAction = null;
        showInternal(owner, screenX, screenY, userId, onChanged);
    }

    /**
     * Show the profile popup in a server context, with a "Join Stream" quick action
     * shown when {@code onJoinLiveStream} is non-null (the profile's user is currently
     * screen sharing in the channel the viewer is connected to).
     */
    public void show(Window owner, double screenX, double screenY, UUID userId, UUID serverId,
                      Runnable onChanged, Runnable onJoinLiveStream) {
        if (userId == null) return;
        this.currentServerId = serverId;
        this.pendingJoinLiveAction = onJoinLiveStream;
        showInternal(owner, screenX, screenY, userId, onChanged);
    }

    /**
     * Show the profile popup near (screenX, screenY) for userId.
     * onChanged is invoked on the FX thread after any relationship change.
     */
    public void show(Window owner, double screenX, double screenY, UUID userId, Runnable onChanged) {
        if (userId == null) return;
        this.currentServerId = null;
        this.pendingJoinLiveAction = null;
        showInternal(owner, screenX, screenY, userId, onChanged);
    }

    private void showInternal(Window owner, double screenX, double screenY, UUID userId, Runnable onChanged) {
        this.currentUserId = userId;
        this.onChanged = onChanged;

        // Reset state for new user
        usernameLbl.setText("...");
        avatarStatusDot.getStyleClass().removeIf(c -> c.startsWith("status-"));
        avatarStatusDot.getStyleClass().add(MainUserSummary.UserStatus.UNKNOWN.getCssClass());
        avatarInner.getChildren().clear();
        customStatusBubble.setVisible(false);
        customStatusBubble.setManaged(false);
        customStatusLabel.setVisible(false);
        customStatusLabel.setManaged(false);
        contentBox.getChildren().setAll(loadingBox);
        actionsMenuBtn.getItems().clear();
        actionsMenuBtn.setVisible(false);

        // ── "Join Stream" banner ──────────────────────────────────────────
        boolean canJoinLive = pendingJoinLiveAction != null;
        liveBanner.setVisible(canJoinLive);
        liveBanner.setManaged(canJoinLive);
        if (canJoinLive) {
            boolean alreadyWatching = App.getCurrentPage() instanceof ServerPage sp
                    && sp.getChatSection().isWatchingStream(userId.toString());
            liveBanner.setDisable(alreadyWatching);
            liveCtaIcon.setIconCode(alreadyWatching ? Feather.CHECK : Feather.CHEVRON_RIGHT);
            startLiveDotPulse();
        } else {
            stopLiveDotPulse();
        }

        // Avatar/username from cache (no network needed)
        AvatarCache.CachedUser cached = App.getAvatarCache().getIfPresent(userId);
        if (cached != null) applyFromCache(cached);

        // Own status is already in memory — apply immediately without a round-trip
        UUID me = App.getUser() != null ? App.getUser().getUserId() : null;
        if (userId.equals(me)) {
            applyStatus(App.getUser().getStatus());
            applyCustomStatus(App.getUser().getStatusEmoji(), App.getUser().getStatusMessage());
        }

        double[] pos = computePosition(screenX, screenY);
        show(owner, pos[0], pos[1]);

        pendingProfileId = userId;
        profileLoadService.restart();
    }

    // ── Data application ──────────────────────────────────────────────────────

    private void applyFromCache(AvatarCache.CachedUser cu) {
        if (cu.username() != null) usernameLbl.setText(cu.username());
        fillAvatar(cu);
    }

    private void applyUserSummary(UserSummary s) {
        if (s == null) { showError(); return; }

        if (s.getUsername() != null) usernameLbl.setText(s.getUsername());
        applyStatus(s.getStatus());
        applyCustomStatus(s.getStatusEmoji(), s.getStatusMessage());

        if (avatarInner.getChildren().isEmpty()) {
            UUID uid = currentUserId;
            App.getAvatarCache().resolve(uid).thenAccept(cu -> {
                if (cu != null) Platform.runLater(() -> {
                    if (uid.equals(currentUserId) && avatarInner.getChildren().isEmpty()) fillAvatar(cu);
                });
            });
        }

        buildContent(s);
    }

    private void applyStatus(MainUserSummary.UserStatus status) {
        if (status == null) return;
        avatarStatusDot.getStyleClass().removeIf(c -> c.startsWith("status-"));
        avatarStatusDot.getStyleClass().add(status.getCssClass());
    }

    private void applyCustomStatus(String statusEmojiUnified, String statusMessage) {
        boolean hasEmoji = statusEmojiUnified != null && !statusEmojiUnified.isBlank();
        boolean hasText = statusMessage != null && !statusMessage.isBlank();

        // Emoji → little speech bubble by the avatar
        customStatusEmojiSlot.getChildren().clear();
        if (hasEmoji) {
            EmojiData.emojiFromCodepoints(statusEmojiUnified).ifPresent(emoji -> {
                List<Node> nodes = TextUtils.convertToTextAndImageNodes(emoji.character(), 18);
                if (nodes.isEmpty()) return;
                Node n = nodes.get(0);
                if (n instanceof ImageView iv) {
                    iv.setFitWidth(18);
                    iv.setFitHeight(18);
                    iv.setPreserveRatio(true);
                    iv.setSmooth(true);
                    customStatusEmojiSlot.getChildren().add(iv);
                } else if (n instanceof Text t) {
                    t.setStyle("-fx-font-size: 18px;");
                    customStatusEmojiSlot.getChildren().add(t);
                }
            });
        }
        boolean showBubble = !customStatusEmojiSlot.getChildren().isEmpty();
        customStatusBubble.setVisible(showBubble);
        customStatusBubble.setManaged(showBubble);

        // Message → subtle italic line under the presence text
        customStatusLabel.setText(hasText ? statusMessage : "");
        customStatusLabel.setVisible(hasText);
        customStatusLabel.setManaged(hasText);
    }

    private void buildContent(UserSummary s) {
        VBox info = new VBox(6);
        info.setPadding(new Insets(8, 14, 10, 14));
        info.setFillWidth(true);

        if (s.getCreatedAt() != null) {
            info.getChildren().add(infoRow("Komm member since", s.getCreatedAt()));
        }

        FriendSummary fs = s.getFriendship();
        if (fs != null && fs.getStatus() == FriendSummary.FriendStatus.ACCEPTED && s.getFriendsSince() != null) {
            info.getChildren().add(infoRow("Friends since", s.getFriendsSince()));
        }

        // Hub profile badges — already part of the fetched summary
        FlowPane hubBadges = BadgeUi.flow(s.getBadges());
        if (hubBadges != null) {
            Label badgesTitle = new Label("BADGES");
            badgesTitle.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle;");
            VBox.setMargin(badgesTitle, new Insets(4, 0, 0, 0));
            info.getChildren().addAll(badgesTitle, hubBadges);
        }

        // Role badges — only shown in server context
        if (currentServerId != null) {
            buildRoleBadgesSection(s, info);
        }

        buildActions(s, fs);

        contentBox.getChildren().setAll(info);
    }

    private void buildRoleBadgesSection(UserSummary s, VBox container) {
        pendingBadgeUser = s;
        pendingBadgeContainer = container;
        roleBadgesService.restart();
    }

    private void applyRoleBadges(ServerMemberSummary member) {
        if (member == null) return;

        List<CustomRoleSummary> allRoles = App.getPermissionManager().getCustomRoles();
        String baseRole = member.getBaseRole();
        List<UUID> customIds = member.getCustomRoleIds();

        if (!pendingBadgeUser.getUserId().equals(currentUserId)) return;
        FlowPane badges = new FlowPane(6, 4);
        badges.setPadding(new Insets(4, 0, 0, 0));

        if (baseRole != null) {
            String color = switch (baseRole) {
                case "OWNER"     -> "-color-accent-9";
                case "ADMIN"     -> "-color-accent-7";
                case "MODERATOR" -> "-color-accent-5";
                default          -> "-color-accent-3";
            };
            badges.getChildren().add(buildBadge(capitalize(baseRole), color, true));
        }

        for (CustomRoleSummary cr : allRoles) {
            if (customIds != null && customIds.contains(cr.getRoleId())) {
                badges.getChildren().add(buildBadge(cr.getRoleName(), cr.getColor(), false));
            }
        }

        if (!badges.getChildren().isEmpty()) {
            Separator sep = new Separator();
            sep.setPadding(new Insets(4, 0, 2, 0));
            // Small title so server roles don't get read as profile badges
            Label rolesTitle = new Label("ROLES");
            rolesTitle.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle;");
            pendingBadgeContainer.getChildren().addAll(sep, rolesTitle, badges);
        }
    }

    private javafx.scene.Node buildBadge(String name, String color, boolean isCssVar) {
        javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(4);
        if (isCssVar) {
            dot.setStyle("-fx-fill: " + color + ";");
        } else {
            try {
                dot.setFill(javafx.scene.paint.Color.web(color != null ? color : "#99AAB5"));
            } catch (Exception e) {
                dot.setStyle("-fx-fill: -color-accent-5;");
            }
        }
        Label lbl = new Label(name);
        lbl.setStyle("-fx-font-size: 11px;");
        HBox badge = new HBox(5, dot, lbl);
        badge.setAlignment(Pos.CENTER_LEFT);
        badge.setPadding(new Insets(2, 8, 2, 6));
        badge.setStyle("-fx-background-color: rgba(255,255,255,0.07); -fx-background-radius: 8;");
        return badge;
    }

    private void buildActions(UserSummary s, FriendSummary fs) {
        actionsMenuBtn.getItems().clear();

        UUID me = App.getUser() != null ? App.getUser().getUserId() : null;
        boolean isSelf = me != null && me.equals(s.getUserId());

        // View Profile — always available
        final UUID profileUserId = s.getUserId();
        final boolean serverCtx = currentServerId != null;
        MenuItem viewProfile = new MenuItem("View Profile", new FontIcon(MaterialDesignA.ACCOUNT_CIRCLE));
        viewProfile.setOnAction(e -> {
            hide();
            App.showModal(new UserProfileModal(profileUserId, serverCtx));
        });
        actionsMenuBtn.getItems().add(viewProfile);

        // Send Message is always offered for other users; the recipient's privacy
        // settings are enforced server-side when the message is actually sent.
        if (!isSelf) {
            actionsMenuBtn.getItems().add(new SeparatorMenuItem());
            addSendMessageItem(s.getUserId(), s.getUsername());
        }

        if (fs == null && !isSelf) {
            String username = s.getUsername();
            MenuItem add = new MenuItem("Add Friend", new FontIcon(MaterialDesignA.ACCOUNT_PLUS));
            add.setOnAction(e -> runAction(() ->
                    App.getServices().hub().getFriendService().sendRequest(username)));
            actionsMenuBtn.getItems().add(add);

        } else if (fs != null && fs.getStatus() == FriendSummary.FriendStatus.PENDING) {
            if (me != null && me.equals(fs.getRequester())) {
                MenuItem cancel = new MenuItem("Cancel Request", new FontIcon(Feather.X));
                cancel.setOnAction(e -> runAction(() ->
                        App.getServices().hub().getFriendService().cancelRequest(fs.getFriendId())));
                actionsMenuBtn.getItems().add(cancel);
            } else {
                MenuItem accept = new MenuItem("Accept", new FontIcon(Feather.CHECK));
                accept.setOnAction(e -> runAction(() ->
                        App.getServices().hub().getFriendService().acceptRequest(fs.getFriendId())));

                MenuItem decline = new MenuItem("Decline", new FontIcon(Feather.X));
                decline.setOnAction(e -> runAction(() ->
                        App.getServices().hub().getFriendService().declineRequest(fs.getFriendId())));

                actionsMenuBtn.getItems().addAll(accept, decline);
            }

        } else if (fs != null && fs.getStatus() == FriendSummary.FriendStatus.ACCEPTED) {
            MenuItem remove = new MenuItem("Remove Friend", new FontIcon(Feather.USER_MINUS));
            remove.setOnAction(e -> App.showModal(new ConfirmationModal(
                    "Remove Friend",
                    "Are you sure you want to remove " + s.getUsername() + " from your friends?",
                    new FontIcon(Feather.USER_MINUS),
                    () -> runAction(() ->
                            App.getServices().hub().getFriendService().removeFriend(fs.getFriendId())))));
            actionsMenuBtn.getItems().add(remove);
        }

        // Assign Role — server context + permission check; self-assignment allowed
        boolean canAssignRoles = currentServerId != null
                && (App.getPermissionManager().has(Permission.EDIT_SERVER_PERMS)
                    || App.getPermissionManager().getMyRole() == ServerSummary.Role.OWNER);
        if (canAssignRoles) {
            var items = actionsMenuBtn.getItems();
            if (!items.isEmpty() && !(items.get(items.size() - 1) instanceof SeparatorMenuItem)) {
                items.add(new SeparatorMenuItem());
            }
            MenuItem assignRole = new MenuItem("Assign Role", new FontIcon(MaterialDesignA.ACCOUNT_EDIT));
            final UUID targetId = s.getUserId();
            assignRole.setOnAction(e -> {
                hide();
                App.showModal(new AssignRoleModal(targetId));
            });
            actionsMenuBtn.getItems().add(assignRole);
        }

        actionsMenuBtn.setVisible(!actionsMenuBtn.getItems().isEmpty());
    }

    private void addSendMessageItem(UUID targetId, String targetName) {
        MenuItem sendMsg = new MenuItem("Send Message", new FontIcon(Feather.MESSAGE_CIRCLE));
        sendMsg.setOnAction(e -> {
            hide();
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
        actionsMenuBtn.getItems().add(sendMsg);
    }

    // ── Live banner ───────────────────────────────────────────────────────────

    @Override
    public void hide() {
        stopLiveDotPulse();
        super.hide();
    }

    private void startLiveDotPulse() {
        if (liveDotPulse != null) return;
        liveDotPulse = new Timeline(
                new KeyFrame(Duration.ZERO,        new KeyValue(liveDot.opacityProperty(), 1.0)),
                new KeyFrame(Duration.millis(700), new KeyValue(liveDot.opacityProperty(), 0.35)),
                new KeyFrame(Duration.millis(1400), new KeyValue(liveDot.opacityProperty(), 1.0)));
        liveDotPulse.setCycleCount(Animation.INDEFINITE);
        liveDotPulse.play();
    }

    private void stopLiveDotPulse() {
        if (liveDotPulse != null) { liveDotPulse.stop(); liveDotPulse = null; }
        liveDot.setOpacity(1.0);
    }

    // ── Avatar ────────────────────────────────────────────────────────────────

    private void fillAvatar(AvatarCache.CachedUser cu) {
        int size = (int) (AVATAR_SIZE - 6);
        avatarInner.getChildren().clear();

        if (cu.avatar() != null && cu.avatar().length > 0) {
            try {
                Image img = new Image(new ByteArrayInputStream(cu.avatar()), size, size, true, true);
                if (!img.isError()) {
                    ImageView iv = new ImageView(img);
                    iv.setFitWidth(size);
                    iv.setFitHeight(size);
                    iv.setPreserveRatio(false);
                    iv.setClip(new Circle(size / 2.0, size / 2.0, size / 2.0));
                    avatarInner.getChildren().add(iv);
                    return;
                }
            } catch (Exception ignored) {}
        }

        String letter = (cu.username() != null && !cu.username().isEmpty())
                ? String.valueOf(cu.username().charAt(0)).toUpperCase() : "?";
        StackPane tile = new StackPane();
        tile.setPrefSize(size, size);
        tile.setMinSize(size, size);
        tile.setMaxSize(size, size);
        tile.setStyle("-fx-background-color: " + AvatarColor.forName(cu.username()) + ";" +
                "-fx-background-radius: " + (size / 2.0) + "px;");
        Text t = new Text(letter);
        t.setFill(Color.WHITE);
        t.setFont(Font.font("System", FontWeight.BOLD, size / 2.5));
        tile.getChildren().add(t);
        avatarInner.getChildren().add(tile);
    }

    // ── Action runner ─────────────────────────────────────────────────────────

    private void runAction(ThrowingRunnable action) {
        pendingAction = action;
        pendingActionUserId = currentUserId;
        actionService.restart();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private HBox infoRow(String label, LocalDateTime value) {
        Label k = new Label(label);
        k.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");
        Label v = new Label(value.format(DATE_FMT));
        v.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox row = new HBox(4, k, sp, v);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.charAt(0) + s.substring(1).toLowerCase();
    }

    private void showError() {
        Label err = new Label("Could not load profile.");
        err.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-danger-fg;");
        VBox box = new VBox(err);
        box.setPadding(new Insets(8, 14, 12, 14));
        contentBox.getChildren().setAll(box);
    }

    private static double[] computePosition(double mouseX, double mouseY) {
        // Use the screen that actually contains the cursor (multi-monitor safe)
        var screens = Screen.getScreensForRectangle(mouseX, mouseY, 1, 1);
        var bounds = (screens.isEmpty() ? Screen.getPrimary() : screens.get(0)).getVisualBounds();

        double estH = 300;

        double x = mouseX + 28;
        if (x + WIDTH > bounds.getMaxX()) x = mouseX - WIDTH - 28;
        x = Math.max(bounds.getMinX() + 4, x);

        double y = mouseY - 80;
        if (y + estH > bounds.getMaxY()) y = bounds.getMaxY() - estH - 8;
        y = Math.max(bounds.getMinY() + 4, y);

        return new double[]{x, y};
    }
}