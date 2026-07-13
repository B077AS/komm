package komm.ui.modals;

import atlantafx.base.theme.Styles;
import io.github.b077as.emojifx.EmojiData;
import io.github.b077as.emojifx.util.TextUtils;
import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
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
import komm.App;
import komm.model.dto.summary.CustomRoleSummary;
import komm.model.dto.summary.FriendSummary;
import komm.model.dto.summary.MainUserSummary;
import komm.model.dto.summary.ServerMemberSummary;
import komm.model.dto.summary.UserSummary;
import komm.ui.avatar.AvatarCache;
import komm.ui.avatar.AvatarColor;
import komm.ui.customnodes.BadgeUi;
import komm.ui.customnodes.CustomNotification;
import komm.ui.pages.HomePage;
import komm.ui.utils.IconColorUtil;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

import java.io.ByteArrayInputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Slf4j
public class UserProfileModal extends HBox {

    private static final double W = 560;
    private static final double H = 400;
    private static final int AVATAR_SIZE = 100;
    private static final double BORDER_W = 2.5;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy");

    private final UUID targetUserId;
    private final boolean isSelf;
    private final boolean inServerContext;

    private StackPane avatarSlot;
    private Circle statusDot;
    private Tooltip statusTooltip;
    private Label usernameLbl;
    private HBox customStatusBubble;
    private StackPane customStatusEmojiSlot;
    private Label customStatusLabel;
    private MenuButton actionsMenu;
    private StackPane tabContent;

    public UserProfileModal(UUID targetUserId) {
        this(targetUserId, false);
    }

    public UserProfileModal(UUID targetUserId, boolean inServerContext) {
        this.targetUserId = targetUserId;
        this.isSelf = App.getUser() != null && targetUserId.equals(App.getUser().getUserId());
        this.inServerContext = inServerContext;

        getStyleClass().add("custom-modal");
        setMinSize(W, H);
        setMaxSize(W, H);
        setPrefSize(W, H);
        setSpacing(0);
        setAlignment(Pos.TOP_LEFT);

        VBox left = buildLeftPanel();
        Separator divider = new Separator(Orientation.VERTICAL);
        divider.setPadding(new Insets(0));
        VBox right = buildRightPanel();
        HBox.setHgrow(right, Priority.ALWAYS);

        getChildren().addAll(left, divider, right);
        loadData();
    }

    // ── Left panel ────────────────────────────────────────────────────────────

    private VBox buildLeftPanel() {
        VBox panel = new VBox(0);
        panel.setPrefWidth(210);
        panel.setMinWidth(210);
        panel.setMaxWidth(210);
        panel.setStyle("-fx-background-color: rgba(255,255,255,0.03); -fx-background-radius: 12px 0 0 12px;");

        // Avatar slot — styled as accent ring from the start
        avatarSlot = new StackPane();
        avatarSlot.setPrefSize(AVATAR_SIZE, AVATAR_SIZE);
        avatarSlot.setMinSize(AVATAR_SIZE, AVATAR_SIZE);
        avatarSlot.setMaxSize(AVATAR_SIZE, AVATAR_SIZE);
        applyRingStyle(null);

        // Status dot pulled onto the circle's edge (bottom-right of the square
        // bounds is outside a round avatar); wording stays as a tooltip.
        statusDot = new Circle(9);
        statusDot.getStyleClass().add(MainUserSummary.UserStatus.UNKNOWN.getCssClass());
        statusDot.setStyle("-fx-stroke: -color-bg-overlay; -fx-stroke-width: 3;");
        statusTooltip = new Tooltip("Unknown");
        Tooltip.install(statusDot, statusTooltip);

        StackPane avatarWrapper = new StackPane(avatarSlot, statusDot);
        avatarWrapper.setMaxSize(AVATAR_SIZE, AVATAR_SIZE);
        StackPane.setAlignment(statusDot, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(statusDot, new Insets(0, 2, 5, 0));

        // Custom status emoji — small speech bubble docked at the avatar's
        // top-right, same language as the profile popup.
        customStatusEmojiSlot = new StackPane();
        customStatusEmojiSlot.setMinSize(18, 18);
        customStatusEmojiSlot.setMaxSize(18, 18);

        HBox bubbleBody = new HBox(customStatusEmojiSlot);
        bubbleBody.setAlignment(Pos.CENTER);
        bubbleBody.setPadding(new Insets(6));
        bubbleBody.getStyleClass().add("status-bubble");

        Path bubbleTail = new Path(new MoveTo(7, 0), new LineTo(0, 6), new LineTo(7, 9));
        bubbleTail.getStyleClass().add("status-bubble-tail");
        bubbleTail.setTranslateX(1.5);

        customStatusBubble = new HBox(bubbleTail, bubbleBody);
        customStatusBubble.setAlignment(Pos.BOTTOM_LEFT);
        HBox.setMargin(bubbleTail, new Insets(0, 0, 9, 0));
        customStatusBubble.setMaxWidth(Region.USE_PREF_SIZE);
        customStatusBubble.setMaxHeight(Region.USE_PREF_SIZE);
        customStatusBubble.setPickOnBounds(false);
        customStatusBubble.setVisible(false);
        customStatusBubble.setManaged(false);

        StackPane avatarArea = new StackPane(avatarWrapper, customStatusBubble);
        avatarArea.setMaxWidth(Double.MAX_VALUE);
        StackPane.setAlignment(avatarWrapper, Pos.CENTER);
        StackPane.setAlignment(customStatusBubble, Pos.TOP_RIGHT);
        StackPane.setMargin(customStatusBubble, new Insets(0, 6, 0, 0));

        usernameLbl = new Label("Loading…");
        usernameLbl.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        usernameLbl.setWrapText(true);
        usernameLbl.setAlignment(Pos.CENTER);
        usernameLbl.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        // Custom status message — quiet italic "tagline" under the name
        customStatusLabel = new Label();
        customStatusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted; -fx-font-style: italic;");
        customStatusLabel.setWrapText(true);
        customStatusLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        customStatusLabel.setMaxWidth(170);
        customStatusLabel.setVisible(false);
        customStatusLabel.setManaged(false);

        VBox nameBlock = new VBox(4, usernameLbl, customStatusLabel);
        nameBlock.setAlignment(Pos.CENTER);

        VBox identity = new VBox(12, avatarArea, nameBlock);
        identity.setAlignment(Pos.CENTER);
        identity.setPadding(new Insets(24, 16, 18, 16));

        Separator hSep = new Separator(Orientation.HORIZONTAL);
        hSep.setPadding(new Insets(0));

        VBox nav = buildTabNav();
        VBox.setVgrow(nav, Priority.ALWAYS);

        actionsMenu = new MenuButton();
        actionsMenu.setGraphic(new FontIcon(MaterialDesignD.DOTS_HORIZONTAL));
        actionsMenu.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);
        actionsMenu.setVisible(false);

        panel.getChildren().addAll(identity, hSep, nav);
        return panel;
    }

    private void applyRingStyle(String bgColor) {
        double r = AVATAR_SIZE / 2.0;
        String bg = (bgColor != null) ? bgColor : "-color-bg-default";
        avatarSlot.setStyle(
                "-fx-border-color: -color-accent-7;" +
                "-fx-border-width: " + BORDER_W + "px;" +
                "-fx-border-radius: " + r + "px;" +
                "-fx-background-radius: " + r + "px;" +
                "-fx-background-color: " + bg + ";");
    }

    private VBox buildTabNav() {
        VBox nav = new VBox(2);
        nav.setPadding(new Insets(10, 8, 8, 8));

        Label navLabel = new Label("INFO");
        navLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle; -fx-padding: 0 0 4 10;");

        VBox generalItem = buildNavItem(new FontIcon(MaterialDesignI.INFORMATION_OUTLINE), "General");
        generalItem.getStyleClass().add("nav-active");

        nav.getChildren().addAll(navLabel, generalItem);
        return nav;
    }

    private VBox buildNavItem(FontIcon icon, String label) {
        Label lbl = new Label(label);
        lbl.getStyleClass().add("nav-label");
        HBox row = new HBox(10, icon, lbl);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox item = new VBox(row);
        item.setAlignment(Pos.CENTER_LEFT);
        item.setPadding(new Insets(9, 12, 9, 12));
        item.getStyleClass().add("nav-item");
        return item;
    }

    // ── Right panel ───────────────────────────────────────────────────────────

    private VBox buildRightPanel() {
        ProgressIndicator pi = new ProgressIndicator();
        pi.setPrefSize(22, 22);
        VBox loadingBox = new VBox(pi);
        loadingBox.setAlignment(Pos.CENTER);
        loadingBox.setPadding(new Insets(60, 0, 0, 0));

        tabContent = new StackPane(loadingBox);
        tabContent.setAlignment(Pos.TOP_LEFT);
        VBox.setVgrow(tabContent, Priority.ALWAYS);

        VBox panel = new VBox(0);
        panel.setAlignment(Pos.TOP_LEFT);
        panel.getChildren().addAll(buildHeader(), tabContent);
        return panel;
    }

    private HBox buildHeader() {
        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(18, 16, 14, 24));
        header.setStyle("-fx-border-color: transparent transparent -color-border-default transparent; -fx-border-width: 0 0 1 0;");

        Label title = new Label("User Profile");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button closeBtn = new Button(null, new FontIcon(MaterialDesignC.CLOSE));
        closeBtn.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        closeBtn.setOnAction(e -> App.closeModal());

        header.getChildren().addAll(title, actionsMenu, spacer, closeBtn);
        return header;
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private void loadData() {
        AvatarCache.CachedUser instant = App.getAvatarCache().getIfPresent(targetUserId);
        if (instant != null) {
            applyAvatarFromCache(instant);
        } else {
            App.getAvatarCache().resolve(targetUserId)
                    .thenAccept(cu -> Platform.runLater(() -> applyAvatarFromCache(cu)));
        }

        Service<UserSummary> svc = new Service<>() {
            @Override protected Task<UserSummary> createTask() {
                return new Task<>() {
                    @Override protected UserSummary call() throws Exception {
                        return App.getServices().hub().getUserService().getUserSummary(targetUserId);
                    }
                };
            }
        };
        svc.setOnSucceeded(e -> applyUserSummary(svc.getValue()));
        svc.setOnFailed(e -> Platform.runLater(this::showError));
        svc.start();
    }

    private void applyAvatarFromCache(AvatarCache.CachedUser cu) {
        if (cu == null) return;
        if (cu.username() != null) usernameLbl.setText(cu.username());
        fillAvatarSlot(cu.avatar(), cu.username());
    }

    private void fillAvatarSlot(byte[] bytes, String username) {
        avatarSlot.getChildren().clear();

        if (bytes != null && bytes.length > 0) {
            try {
                Image img = new Image(new ByteArrayInputStream(bytes), AVATAR_SIZE, AVATAR_SIZE, true, true);
                if (!img.isError()) {
                    applyRingStyle(null);
                    ImageView iv = new ImageView(img);
                    iv.setFitWidth(AVATAR_SIZE);
                    iv.setFitHeight(AVATAR_SIZE);
                    iv.setPreserveRatio(false);
                    iv.setClip(new Circle(AVATAR_SIZE / 2.0, AVATAR_SIZE / 2.0, AVATAR_SIZE / 2.0 - BORDER_W));
                    avatarSlot.getChildren().add(iv);
                    return;
                }
            } catch (Exception ignored) {}
        }

        String letter = (username != null && !username.isEmpty())
                ? String.valueOf(username.charAt(0)).toUpperCase() : "?";
        applyRingStyle(AvatarColor.forName(username));
        Text t = new Text(letter);
        t.setFill(Color.WHITE);
        t.setFont(Font.font("System", FontWeight.BOLD, AVATAR_SIZE / 2.5));
        avatarSlot.getChildren().add(t);
    }

    private void applyUserSummary(UserSummary s) {
        if (s == null) { showError(); return; }

        if (s.getUsername() != null) usernameLbl.setText(s.getUsername());

        if (s.getStatus() != null) {
            statusDot.getStyleClass().removeIf(c -> c.startsWith("status-"));
            statusDot.getStyleClass().add(s.getStatus().getCssClass());
            statusTooltip.setText(s.getStatus().getValue());
        }
        applyCustomStatus(s.getStatusEmoji(), s.getStatusMessage());

        tabContent.getChildren().setAll(buildGeneralTab(s));
        buildActionsMenu(s, s.getFriendship());
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

        // Message → subtle italic line under the username
        customStatusLabel.setText(hasText ? statusMessage : "");
        customStatusLabel.setVisible(hasText);
        customStatusLabel.setManaged(hasText);
    }

    // ── General tab ───────────────────────────────────────────────────────────

    private ScrollPane buildGeneralTab(UserSummary s) {
        VBox body = new VBox(0);
        body.setFillWidth(true);
        body.setPadding(new Insets(18, 24, 16, 24));

        FriendSummary fs = s.getFriendship();

        // Dates side by side — the modal is a single wide column now
        HBox datesRow = new HBox(48);
        if (s.getCreatedAt() != null) {
            datesRow.getChildren().add(new VBox(4,
                    sectionLabel("MEMBER SINCE"), infoValue(s.getCreatedAt().format(DATE_FMT))));
        }
        if (fs != null && fs.getStatus() == FriendSummary.FriendStatus.ACCEPTED && s.getFriendsSince() != null) {
            datesRow.getChildren().add(new VBox(4,
                    sectionLabel("FRIENDS SINCE"), infoValue(s.getFriendsSince().format(DATE_FMT))));
        }
        if (!datesRow.getChildren().isEmpty()) {
            body.getChildren().addAll(datesRow, spacer(14));
        }

        FlowPane badgeFlow = BadgeUi.flow(s.getBadges());
        if (badgeFlow != null) {
            body.getChildren().addAll(sectionLabel("BADGES"), spacer(8), badgeFlow, spacer(14));
        }

        if (inServerContext) {
            body.getChildren().addAll(new Separator(), spacer(14), sectionLabel("ROLES"), spacer(8));
            VBox rolesBox = new VBox(4);
            body.getChildren().add(rolesBox);
            loadRoleRows(s, rolesBox);
        }

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        return scroll;
    }

    private void loadRoleRows(UserSummary s, VBox container) {
        Service<ServerMemberSummary> svc = new Service<>() {
            @Override protected Task<ServerMemberSummary> createTask() {
                return new Task<>() {
                    @Override protected ServerMemberSummary call() throws Exception {
                        try { App.getServices().installation(); } catch (IllegalStateException e) { return null; }
                        return App.getServices().installation()
                                .getInstallationPermissionService().getMember(s.getUserId());
                    }
                };
            }
        };
        svc.setOnSucceeded(e -> {
            ServerMemberSummary member = svc.getValue();
            if (member == null) return;

            FlowPane flow = new FlowPane(6, 6);

            String baseRole = member.getBaseRole();
            if (baseRole != null) {
                FontIcon icon = new FontIcon(baseRoleIcon(baseRole));
                icon.getStyleClass().add("custom-accent-icon");
                flow.getChildren().add(buildRolePill(capitalize(baseRole), icon));
            }

            List<CustomRoleSummary> allRoles = App.getPermissionManager().getCustomRoles();
            List<UUID> memberCustomIds = member.getCustomRoleIds();
            if (memberCustomIds != null) {
                for (CustomRoleSummary cr : allRoles) {
                    if (memberCustomIds.contains(cr.getRoleId())) {
                        flow.getChildren().add(buildRolePill(cr.getRoleName(), IconColorUtil.roleColorIcon(cr.getColor(), 18)));
                    }
                }
            }

            if (flow.getChildren().isEmpty()) {
                Label none = new Label("No roles assigned");
                none.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
                container.getChildren().setAll(none);
            } else {
                container.getChildren().setAll(flow);
            }
        });
        svc.setOnFailed(e -> log.debug("Could not load member roles: {}", svc.getException().getMessage()));
        svc.start();
    }

    private HBox buildRolePill(String name, FontIcon icon) {
        Label lbl = new Label(name);
        lbl.setStyle("-fx-font-size: 11px;");
        HBox pill = new HBox(6, icon, lbl);
        pill.setAlignment(Pos.CENTER_LEFT);
        pill.setPadding(new Insets(4, 10, 4, 8));
        pill.setStyle("-fx-background-color: rgba(255,255,255,0.07); -fx-background-radius: 6;");
        return pill;
    }

    private org.kordamp.ikonli.Ikon baseRoleIcon(String baseRole) {
        return switch (baseRole) {
            case "OWNER"     -> MaterialDesignC.CROWN;
            case "ADMIN"     -> MaterialDesignS.SHIELD_STAR;
            case "MODERATOR" -> MaterialDesignS.SHIELD_ACCOUNT;
            default          -> MaterialDesignA.ACCOUNT;
        };
    }

    // ── Actions menu ──────────────────────────────────────────────────────────

    private void buildActionsMenu(UserSummary s, FriendSummary fs) {
        actionsMenu.getItems().clear();
        if (isSelf) { actionsMenu.setVisible(false); return; }

        UUID me = App.getUser() != null ? App.getUser().getUserId() : null;

        // Send Message is always available — whether the recipient actually accepts
        // the message is enforced server-side by their privacy settings at send time.
        addSendMessageItem(s.getUserId(), s.getUsername());

        if (fs == null) {
            MenuItem add = new MenuItem("Add Friend", new FontIcon(MaterialDesignA.ACCOUNT_PLUS));
            add.setOnAction(e -> runAction(
                    () -> App.getServices().hub().getFriendService().sendRequest(s.getUsername()),
                    "Friend request sent!"));
            actionsMenu.getItems().add(add);

        } else if (fs.getStatus() == FriendSummary.FriendStatus.PENDING) {
            if (me != null && me.equals(fs.getRequester())) {
                MenuItem cancel = new MenuItem("Cancel Request", new FontIcon(Feather.X));
                cancel.setOnAction(e -> runAction(
                        () -> App.getServices().hub().getFriendService().cancelRequest(fs.getFriendId()),
                        null));
                actionsMenu.getItems().add(cancel);
            } else {
                MenuItem accept = new MenuItem("Accept Request", new FontIcon(Feather.CHECK));
                accept.setOnAction(e -> runAction(
                        () -> App.getServices().hub().getFriendService().acceptRequest(fs.getFriendId()),
                        null));
                MenuItem decline = new MenuItem("Decline", new FontIcon(Feather.X));
                decline.setOnAction(e -> runAction(
                        () -> App.getServices().hub().getFriendService().declineRequest(fs.getFriendId()),
                        null));
                actionsMenu.getItems().addAll(accept, decline);
            }

        } else if (fs.getStatus() == FriendSummary.FriendStatus.ACCEPTED) {
            MenuItem remove = new MenuItem("Remove Friend", new FontIcon(Feather.USER_MINUS));
            remove.setOnAction(e -> App.showModal(new ConfirmationModal(
                    "Remove Friend",
                    "Are you sure you want to remove " + s.getUsername() + " from your friends?",
                    new FontIcon(Feather.USER_MINUS),
                    () -> runAction(
                            () -> App.getServices().hub().getFriendService().removeFriend(fs.getFriendId()),
                            null))));
            actionsMenu.getItems().add(remove);
        }

        actionsMenu.setVisible(!actionsMenu.getItems().isEmpty());
    }

    private void addSendMessageItem(UUID targetId, String targetName) {
        MenuItem sendMsg = new MenuItem("Send Message", new FontIcon(Feather.MESSAGE_CIRCLE));
        sendMsg.setOnAction(e -> {
            App.closeModal();
            if (App.getCurrentPage() instanceof HomePage hp) {
                hp.navigateToDm(targetId, targetName);
            } else if (App.getCachedHomePage() != null) {
                App.changePage(App.getCachedHomePage());
                Platform.runLater(() -> {
                    if (App.getCurrentPage() instanceof HomePage hp) hp.navigateToDm(targetId, targetName);
                });
            }
        });
        actionsMenu.getItems().add(sendMsg);
    }

    private void runAction(ThrowingRunnable action, String successMsg) {
        actionsMenu.setDisable(true);
        Service<Void> svc = new Service<>() {
            @Override protected Task<Void> createTask() {
                return new Task<>() {
                    @Override protected Void call() throws Exception {
                        action.run();
                        return null;
                    }
                };
            }
        };
        svc.setOnSucceeded(e -> {
            actionsMenu.setDisable(false);
            if (successMsg != null)
                new CustomNotification("Success", successMsg, new FontIcon(MaterialDesignC.CHECK)).showNotification();
            loadData();
        });
        svc.setOnFailed(e -> {
            actionsMenu.setDisable(false);
            new CustomNotification("Action Failed", "Something went wrong. Please try again.", new FontIcon(MaterialDesignC.CLOSE)).showNotification();
        });
        svc.start();
    }

    private void showError() {
        Label err = new Label("Could not load profile.");
        err.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-danger-fg;");
        VBox box = new VBox(err);
        box.setPadding(new Insets(20, 24, 20, 24));
        tabContent.getChildren().setAll(box);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle;");
        return l;
    }

    private Label infoValue(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 13px;");
        return l;
    }

    private static Region spacer(double h) {
        Region r = new Region();
        r.setMinHeight(h);
        r.setMaxHeight(h);
        return r;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.charAt(0) + s.substring(1).toLowerCase();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
