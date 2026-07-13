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
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import komm.App;
import komm.api.HttpStatusException;
import komm.model.dto.summary.FriendSummary;
import komm.ui.avatar.AvatarCache;
import komm.ui.avatar.AvatarColor;
import komm.ui.customnodes.CustomNotification;
import komm.ui.modals.ConfirmationModal;
import komm.ui.pages.HomePage;
import komm.ui.profile.UserProfilePopup;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

@Slf4j
public class FriendsSection extends VBox {

    // ── Tabs ──────────────────────────────────────────────────────────────────
    private enum Tab {FRIENDS, RECEIVED, SENT}

    private Tab currentTab = Tab.FRIENDS;

    // ── Data ──────────────────────────────────────────────────────────────────
    private List<FriendSummary> friends = List.of();
    private List<FriendSummary> received = List.of();
    private List<FriendSummary> sent = List.of();

    // ── UI refs ───────────────────────────────────────────────────────────────
    private VBox listContainer;
    private VBox loadingBox;
    private TextField inviteField;
    private Button tabFriends, tabReceived, tabSent;

    @Getter
    private boolean needsRefresh = false;

    // ── Services ──────────────────────────────────────────────────────────────

    private record AllData(List<FriendSummary> friends,
                           List<FriendSummary> received,
                           List<FriendSummary> sent) {
    }

    /**
     * Loads all three friend lists in one background round-trip.
     */
    private final Service<AllData> loadService = new Service<>() {
        @Override
        protected Task<AllData> createTask() {
            return new Task<>() {
                @Override
                protected AllData call() throws Exception {
                    var fs = App.getServices().hub().getFriendService();
                    return new AllData(fs.getFriends(), fs.getReceivedRequests(), fs.getSentRequests());
                }
            };
        }
    };

    /**
     * Single reusable action service.
     * Caller sets {@code pendingAction} then calls {@code actionService.restart()}.
     */
    private Callable<Void> pendingAction;

    private final Service<Void> actionService = new Service<>() {
        @Override
        protected Task<Void> createTask() {
            return new Task<>() {
                @Override
                protected Void call() throws Exception {
                    pendingAction.call();
                    return null;
                }
            };
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    public FriendsSection() {
        setFillWidth(true);
        setStyle("-fx-background-color: -color-bg-default;");

        VBox mainBox = new VBox(16);
        mainBox.setPadding(new Insets(20));
        mainBox.setFillWidth(true);

        // ── Header row ────────────────────────────────────────────────────────
        Label header = new Label("Friends");
        header.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");

        Button refreshBtn = new Button(null, new FontIcon(Feather.REFRESH_CW));
        refreshBtn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);
        refreshBtn.setTooltip(new Tooltip("Refresh"));
        refreshBtn.setOnAction(e -> reload());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox headerRow = new HBox(8, header, spacer, refreshBtn);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        // ── Invite card ───────────────────────────────────────────────────────
        VBox inviteCard = buildInviteCard();

        // ── Tab bar ───────────────────────────────────────────────────────────
        HBox tabBar = buildTabBar();

        // ── List container ────────────────────────────────────────────────────
        loadingBox = buildLoadingBox();
        listContainer = new VBox(8);
        listContainer.setFillWidth(true);

        mainBox.getChildren().addAll(headerRow, inviteCard, tabBar, listContainer);
        getChildren().add(mainBox);

        // ── Wire load service ─────────────────────────────────────────────────
        loadService.setOnSucceeded(e -> {
            AllData d = loadService.getValue();
            friends = d.friends();
            received = d.received();
            sent = d.sent();
            App.setFriendRequestPending(!received.isEmpty());
            updateTabBadges();
            renderCurrentTab();
        });
        loadService.setOnFailed(e ->
                log.error("Failed to load friend data: {}", loadService.getException().getMessage()));

        // ── Wire action service ───────────────────────────────────────────────
        actionService.setOnSucceeded(e -> reload());
        actionService.setOnFailed(e -> {
            log.error("Friend action failed: {}", actionService.getException().getMessage());
            new CustomNotification(
                    "Action Failed",
                    HttpStatusException.extractMessage(actionService.getException()),
                    new FontIcon(MaterialDesignA.ALERT_CIRCLE_OUTLINE)
            ).showNotification();
        });

        sceneProperty().addListener((obs, old, scene) -> {
            if (scene != null) reload();
        });
    }

    // ─── Dispatch a friend action ─────────────────────────────────────────────

    /**
     * Runs {@code action} on a background thread via the shared actionService,
     * then reloads the list on success. Never blocks the FX thread.
     */
    private void runAction(Callable<Void> action) {
        if (actionService.isRunning()) return;
        pendingAction = action;
        actionService.restart();
    }

    // ─── Invite card ──────────────────────────────────────────────────────────

    private VBox buildInviteCard() {
        VBox card = new VBox(10);
        card.setPadding(new Insets(14));
        card.setStyle(
                "-fx-background-color: -color-bg-subtle;" +
                        "-fx-background-radius: 10px;" +
                        "-fx-border-color: -color-border-default;" +
                        "-fx-border-radius: 10px;"
        );

        Label title = new Label("Add Friend");
        title.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");

        Label hint = new Label("Enter a username to send a friend request.");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");
        hint.setWrapText(true);

        inviteField = new TextField();
        inviteField.setPromptText("Username…");
        inviteField.getStyleClass().add(Styles.SMALL);
        inviteField.setMaxWidth(Double.MAX_VALUE);

        Button sendBtn = new Button("Send Request", new FontIcon(MaterialDesignA.ACCOUNT_PLUS));
        sendBtn.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        sendBtn.setMaxWidth(Double.MAX_VALUE);
        sendBtn.setOnAction(e -> handleSendRequest());
        inviteField.setOnAction(e -> handleSendRequest());

        // Disable send controls while an action is in-flight
        sendBtn.disableProperty().bind(actionService.runningProperty());
        inviteField.disableProperty().bind(actionService.runningProperty());

        card.getChildren().addAll(title, hint, inviteField, sendBtn);
        return card;
    }

    private void handleSendRequest() {
        String username = inviteField.getText() == null ? "" : inviteField.getText().trim();
        if (username.isEmpty()) return;

        runAction(() -> {
            App.getServices().hub().getFriendService().sendRequest(username);
            // Notification must come back on the FX thread
            Platform.runLater(() -> {
                new CustomNotification(
                        "Friend Request Sent",
                        "Your request has been sent to " + username + ".",
                        new FontIcon(Feather.USER_PLUS)
                ).showNotification();
                inviteField.clear();
            });
            return null;
        });
    }

    // ─── Tab bar ──────────────────────────────────────────────────────────────

    private HBox buildTabBar() {
        tabFriends = tabButton("Friends", Tab.FRIENDS);
        tabReceived = tabButton("Received", Tab.RECEIVED);
        tabSent = tabButton("Sent", Tab.SENT);
        setTabActive(tabFriends);

        Region div1 = divider();
        Region div2 = divider();

        HBox bar = new HBox();
        bar.setAlignment(Pos.CENTER);
        bar.setFillHeight(true);
        bar.setStyle(
                "-fx-background-color: -color-bg-subtle;" +
                        "-fx-background-radius: 8px;" +
                        "-fx-border-color: -color-border-default;" +
                        "-fx-border-radius: 8px;" +
                        "-fx-padding: 4px 0;"
        );

        StackPane w1 = new StackPane(tabFriends);
        StackPane w2 = new StackPane(tabReceived);
        StackPane w3 = new StackPane(tabSent);
        w1.setAlignment(Pos.CENTER);
        w2.setAlignment(Pos.CENTER);
        w3.setAlignment(Pos.CENTER);
        HBox.setHgrow(w1, Priority.ALWAYS);
        HBox.setHgrow(w2, Priority.ALWAYS);
        HBox.setHgrow(w3, Priority.ALWAYS);

        bar.getChildren().addAll(w1, div1, w2, div2, w3);
        return bar;
    }

    private Region divider() {
        Region d = new Region();
        d.setPrefSize(1, 16);
        d.setMinSize(1, 16);
        d.setMaxSize(1, 16);
        d.setStyle("-fx-background-color: -color-border-default;");
        return d;
    }

    private Button tabButton(String label, Tab tab) {
        Label nameLbl = new Label(label);
        nameLbl.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
        nameLbl.setMinWidth(Region.USE_PREF_SIZE);

        Label countLbl = new Label("0");
        countLbl.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: -color-fg-muted;");
        countLbl.setMinWidth(Region.USE_PREF_SIZE);

        HBox content = new HBox(6, nameLbl, countLbl);
        content.setAlignment(Pos.CENTER);

        Button btn = new Button();
        btn.setGraphic(content);
        btn.setUserData(countLbl);
        btn.setFocusTraversable(false);
        btn.setMaxWidth(Region.USE_PREF_SIZE);
        btn.setAlignment(Pos.CENTER);
        btn.setPadding(new Insets(0));
        btn.setStyle(tabStyleInactive());

        btn.setOnMouseEntered(e -> {
            if (currentTab != tab) btn.setStyle(tabStyleHover());
        });
        btn.setOnMouseExited(e -> {
            if (currentTab != tab) btn.setStyle(tabStyleInactive());
        });

        btn.setOnAction(e -> {
            if (currentTab == tab) return;
            currentTab = tab;
            setTabInactive(tabFriends);
            setTabInactive(tabReceived);
            setTabInactive(tabSent);
            setTabActive(btn);
            renderCurrentTab();
        });

        return btn;
    }

    private void setTabActive(Button btn) {
        btn.setStyle(tabStyleActive());
        Label count = (Label) btn.getUserData();
        if (count != null)
            count.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: -color-accent-fg;");
    }

    private void setTabInactive(Button btn) {
        btn.setStyle(tabStyleInactive());
        Label count = (Label) btn.getUserData();
        if (count != null)
            count.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: -color-fg-muted;");
    }

    private String tabStyleActive() {
        return "-fx-background-color: -color-bg-default; -fx-background-radius: 20px;" +
                "-fx-border-color: transparent; -fx-padding: 3px 15px; -fx-cursor: hand;";
    }

    private String tabStyleInactive() {
        return "-fx-background-color: transparent; -fx-background-radius: 20px;" +
                "-fx-border-color: transparent; -fx-padding: 3px 15px; -fx-cursor: hand;";
    }

    private String tabStyleHover() {
        return "-fx-background-color: -color-bg-default; -fx-background-radius: 20px;" +
                "-fx-border-color: transparent; -fx-padding: 3px 15px; -fx-cursor: hand;";
    }

    private void updateTabBadges() {
        setBadgeCount(tabFriends, friends.size());
        setBadgeCount(tabReceived, received.size());
        setBadgeCount(tabSent, sent.size());
    }

    private void setBadgeCount(Button btn, int count) {
        Label lbl = (Label) btn.getUserData();
        if (lbl != null) lbl.setText(count > 99 ? "99+" : String.valueOf(count));
    }

    // ─── Rendering ────────────────────────────────────────────────────────────

    private void renderCurrentTab() {
        List<FriendSummary> items = switch (currentTab) {
            case FRIENDS -> friends;
            case RECEIVED -> received;
            case SENT -> sent;
        };

        if (items.isEmpty()) {
            listContainer.getChildren().setAll(buildEmptyBox());
            return;
        }

        VBox rows = new VBox(6);
        rows.setFillWidth(true);
        // Build every row immediately — avatars fill in asynchronously inside buildFriendRow
        for (FriendSummary fs : items) rows.getChildren().add(buildFriendRow(fs));
        listContainer.getChildren().setAll(rows);
    }

    // ─── Friend row ───────────────────────────────────────────────────────────

    private HBox buildFriendRow(FriendSummary fs) {
        UUID otherId = resolveOtherId(fs);

        // Build avatar placeholder synchronously; image fills in when cache resolves
        StackPane avatarNode = buildMiniAvatar(otherId, 38);

        Label nameLbl = new Label(otherId != null ? "…" : "Unknown");
        nameLbl.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");

        Label subLbl = new Label(subtitleFor(fs));
        subLbl.setStyle("-fx-font-size: 10px; -fx-text-fill: -color-fg-muted;");

        VBox nameBox = new VBox(2, nameLbl, subLbl);
        nameBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(nameBox, Priority.ALWAYS);

        // Click on avatar or name opens the profile popup
        if (otherId != null) {
            UUID profileId = otherId;
            avatarNode.setStyle("-fx-cursor: hand;");
            avatarNode.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) {
                    UserProfilePopup.getInstance().show(
                            App.getStackPane().getScene().getWindow(),
                            e.getScreenX(), e.getScreenY(), profileId, this::reload);
                    e.consume();
                }
            });
            nameBox.setStyle("-fx-cursor: hand;");
            nameBox.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) {
                    UserProfilePopup.getInstance().show(
                            App.getStackPane().getScene().getWindow(),
                            e.getScreenX(), e.getScreenY(), profileId, this::reload);
                    e.consume();
                }
            });
        }

        // Single resolve call per row — no duplicate fetches
        if (otherId != null) {
            App.getAvatarCache().resolve(otherId).thenAccept(cu -> {
                if (cu == null) return;
                Platform.runLater(() -> {
                    nameLbl.setText(cu.username());
                    refreshMiniAvatar(avatarNode, cu, 38);
                });
            });
        }

        HBox actions = buildRowActions(fs);

        HBox row = new HBox(10, avatarNode, nameBox, actions);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 12, 10, 12));
        row.getStyleClass().add("friend-card-row");
        return row;
    }

    // ─── Row actions ─────────────────────────────────────────────────────────

    private HBox buildRowActions(FriendSummary fs) {
        HBox box = new HBox(6);
        box.setAlignment(Pos.CENTER_RIGHT);

        switch (currentTab) {
            case RECEIVED -> {
                Button accept = iconTextBtn(Feather.CHECK);
                accept.setStyle(accept.getStyle() + "-fx-background-color: -color-accent-7;");
                accept.setOnAction(e -> runAction(() -> {
                    App.getServices().hub().getFriendService().acceptRequest(fs.getFriendId());
                    return null;
                }));

                Button decline = iconTextBtn(Feather.X);
                decline.setOnAction(e -> runAction(() -> {
                    App.getServices().hub().getFriendService().declineRequest(fs.getFriendId());
                    return null;
                }));

                box.getChildren().addAll(accept, decline);
            }
            case SENT -> {
                Button cancel = new Button(null, new FontIcon(Feather.X));
                cancel.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT, Styles.DANGER, Styles.SMALL);
                cancel.setTooltip(new Tooltip("Cancel request"));
                cancel.setOnAction(e -> runAction(() -> {
                    App.getServices().hub().getFriendService().cancelRequest(fs.getFriendId());
                    return null;
                }));
                box.getChildren().add(cancel);
            }
            case FRIENDS -> {
                Button messageBtn = new Button(null, new FontIcon(Feather.MESSAGE_CIRCLE));
                messageBtn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT, Styles.SMALL);
                messageBtn.setTooltip(new Tooltip("Send Message"));
                messageBtn.setOnAction(e -> {
                    UUID otherId = resolveOtherId(fs);
                    if (otherId == null) return;
                    if (App.getCurrentPage() instanceof HomePage homePage) {
                        UUID finalOtherId = otherId;
                        App.getAvatarCache().resolve(otherId).thenAcceptAsync(cu -> {
                            String resolvedName = cu != null && cu.username() != null ? cu.username() : "…";
                            javafx.application.Platform.runLater(() -> homePage.navigateToDm(finalOtherId, resolvedName));
                        });
                    } else if (App.getCachedHomePage() != null) {
                        App.changePage(App.getCachedHomePage());
                        UUID finalOtherId = otherId;
                        App.getAvatarCache().resolve(otherId).thenAcceptAsync(cu -> {
                            String resolvedName = cu != null && cu.username() != null ? cu.username() : "…";
                            javafx.application.Platform.runLater(() -> {
                                if (App.getCurrentPage() instanceof HomePage hp) {
                                    hp.navigateToDm(finalOtherId, resolvedName);
                                }
                            });
                        });
                    }
                });

                MenuButton optionsBtn = new MenuButton();
                optionsBtn.setGraphic(new FontIcon(MaterialDesignD.DOTS_VERTICAL));
                optionsBtn.setFocusTraversable(false);
                optionsBtn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT, Styles.SMALL);

                MenuItem messageItem = new MenuItem("Send Message", new FontIcon(Feather.MESSAGE_CIRCLE));
                messageItem.setOnAction(messageBtn.getOnAction());

                MenuItem removeItem = new MenuItem("Remove Friend", new FontIcon(Feather.USER_MINUS));
                removeItem.setOnAction(e -> App.showModal(new ConfirmationModal(
                        "Remove Friend",
                        "Are you sure you want to remove this friend?",
                        new FontIcon(Feather.USER_MINUS),
                        () -> runAction(() -> {
                            App.getServices().hub().getFriendService().removeFriend(fs.getFriendId());
                            return null;
                        }))));
                optionsBtn.getItems().addAll(messageItem, new SeparatorMenuItem(), removeItem);

                box.getChildren().add(optionsBtn);
            }
        }
        return box;
    }

    // ─── Mini avatar ─────────────────────────────────────────────────────────

    private StackPane buildMiniAvatar(UUID userId, double size) {
        StackPane pane = new StackPane();
        pane.setPrefSize(size, size);
        pane.setMinSize(size, size);
        pane.setMaxSize(size, size);

        // Fill synchronously if the cache already has this user
        if (userId != null) {
            AvatarCache.CachedUser cached = App.getAvatarCache().getIfPresent(userId);
            if (cached != null) fillInner(pane, cached, size);
        }

        return pane;
    }

    private void refreshMiniAvatar(StackPane pane, AvatarCache.CachedUser cu, double size) {
        fillInner(pane, cu, size);
    }

    private void fillInner(StackPane pane, AvatarCache.CachedUser cu, double size) {
        pane.getChildren().clear();
        if (cu.avatar() != null && cu.avatar().length > 0) {
            try {
                Image img = new Image(new java.io.ByteArrayInputStream(cu.avatar()), size, size, true, true);
                ImageView iv = new ImageView(img);
                iv.setFitWidth(size);
                iv.setFitHeight(size);
                iv.setPreserveRatio(false);
                iv.setClip(new Circle(size / 2.0, size / 2.0, size / 2.0));
                pane.getChildren().add(iv);
                pane.setStyle("-fx-background-color: transparent; -fx-background-radius: " + (size / 2.0) + "px;");
                return;
            } catch (Exception ignored) {
            }
        }
        // Letter tile fallback
        pane.setStyle(
                "-fx-background-color: " + AvatarColor.forName(cu.username()) + ";" +
                        "-fx-background-radius: " + (size / 2.0) + "px;"
        );
        String letter = (cu.username() != null && !cu.username().isEmpty())
                ? String.valueOf(cu.username().charAt(0)).toUpperCase() : "?";
        Text t = new Text(letter);
        t.setFill(Color.WHITE);
        t.setFont(Font.font("System", FontWeight.BOLD, size / 2.5));
        pane.getChildren().add(t);
    }

    // ─── Loading / empty states ───────────────────────────────────────────────

    private VBox buildLoadingBox() {
        ProgressIndicator pi = new ProgressIndicator();
        pi.setPrefSize(32, 32);
        Label lbl = new Label("Loading…");
        lbl.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");
        VBox b = new VBox(8, pi, lbl);
        b.setAlignment(Pos.CENTER);
        b.setPadding(new Insets(24, 0, 24, 0));
        return b;
    }

    private VBox buildEmptyBox() {
        FontIcon icon = new FontIcon(MaterialDesignA.ACCOUNT_CIRCLE_OUTLINE);
        icon.getStyleClass().add("custom-icon-24");
        Label lbl = new Label(switch (currentTab) {
            case FRIENDS -> "No friends yet. Send a request!";
            case RECEIVED -> "No incoming requests.";
            case SENT -> "No outgoing requests.";
        });
        lbl.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");
        VBox b = new VBox(8, icon, lbl);
        b.setAlignment(Pos.CENTER);
        b.setPadding(new Insets(24, 0, 24, 0));
        return b;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private UUID resolveOtherId(FriendSummary fs) {
        UUID me = App.getUser() != null ? App.getUser().getUserId() : null;
        if (me == null) return null;
        return me.equals(fs.getRequester()) ? fs.getAddressee() : fs.getRequester();
    }

    private String subtitleFor(FriendSummary fs) {
        return switch (fs.getStatus()) {
            case ACCEPTED -> "Friend";
            case PENDING -> currentTab == Tab.SENT ? "Request sent" : "Wants to be your friend";
            case BLOCKED -> "Blocked";
        };
    }

    private Button iconTextBtn(Feather icon) {
        Button btn = new Button(null, new FontIcon(icon));
        btn.setFocusTraversable(false);
        btn.getStyleClass().add(Styles.SMALL);
        btn.setStyle("-fx-background-radius: 20px; -fx-padding: 4px 12px; -fx-font-size: 11px;");
        return btn;
    }

    public void reload() {
        needsRefresh = false;
        listContainer.getChildren().setAll(loadingBox);
        if (loadService.isRunning()) return;
        loadService.restart();
    }

    public void markDirty() {
        this.needsRefresh = true;
    }

    /** Returns the current in-memory accepted friends list. May be slightly stale if {@link #isNeedsRefresh()} is true. */
    public List<FriendSummary> getFriends() {
        return friends;
    }

    /**
     * If the list is known stale, kicks off a background reload without resetting the visible UI.
     * Call this from contexts that read {@link #getFriends()} so the next read is guaranteed fresh.
     */
    public void refreshIfStale() {
        if (!needsRefresh || loadService.isRunning()) return;
        needsRefresh = false;
        loadService.restart();
    }
}