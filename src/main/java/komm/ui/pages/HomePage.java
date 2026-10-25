package komm.ui.pages;

import atlantafx.base.theme.Styles;
import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import komm.App;
import komm.model.dto.summary.InstallationSummary;
import komm.model.dto.summary.ServerSummary;
import komm.ui.cards.InstallationCard;
import komm.ui.cards.ServerCard;
import komm.ui.modals.ConfirmationModal;
import komm.ui.modals.CreateInstallationModal;
import komm.ui.utils.IconColorUtil;
import komm.ui.modals.CreateServerModal;
import komm.ui.modals.DownloadInstallationModal;
import komm.ui.modals.JoinViaInviteModal;
import komm.ui.customnodes.CustomNotification;
import komm.ui.chat.DmChatSection;
import komm.ui.sections.FriendsSection;
import komm.ui.sections.ProfileSection;
import komm.ui.utils.SidePanel;
import komm.utils.UserSettings;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

import java.util.*;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Home: one page with the direct messages as the main area and the user's servers /
 * installations in a slim "spaces" column on the left (a switch flips the column between
 * the two). Profile and friends slide in from the right.
 */
@Slf4j
public class HomePage extends StackPane {

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final double PROFILE_WIDTH = 380.0;
    private static final double SPACES_MIN_WIDTH = 230.0;
    private static final double SPACES_MAX_WIDTH = 480.0;
    private static final double TAB_STRIP_HEIGHT = 48.0;
    private static final double HEADER_HEIGHT = 48.0;

    // ── Data ──────────────────────────────────────────────────────────────────
    private Map<UUID, ServerSummary> serverMap;
    private List<InstallationSummary> installationsList;
    private List<ServerCard> allServerCards = new ArrayList<>();
    private List<InstallationCard> allInstallationCards = new ArrayList<>();

    // ── Filtered cards ────────────────────────────────────────────────────────
    private List<ServerCard> filteredServerCards = new ArrayList<>();
    private List<InstallationCard> filteredInstallationCards = new ArrayList<>();

    // ── Layout nodes ──────────────────────────────────────────────────────────
    private final VBox spacesList = new VBox(2);
    private VBox loadingNode;
    private VBox errorNode;
    private Node emptyServersNode;
    private Node noResultsServersNode;
    private Node emptyInstallsNode;
    private Node noResultsInstallsNode;

    // ── Spaces column controls ────────────────────────────────────────────────
    private ToggleButton serversToggle;
    private ToggleButton installsToggle;
    private Button createBtn;
    private Button joinBtn;
    private Label countLabel;
    private TextField searchField;
    private Circle friendsBadgeDot;

    // ── Side panels ───────────────────────────────────────────────────────────
    @Getter
    private final ProfileSection profileSection = new ProfileSection();
    @Getter
    private final FriendsSection friendsSection = new FriendsSection();
    private SidePanel profilePanel;
    private SidePanel friendsPanel;
    private Button toggleProfileButton;
    private Button toggleFriendsButton;

    // ── Direct messages ───────────────────────────────────────────────────────
    private final DirectMessagePage dmPage;

    // ── Drag ──────────────────────────────────────────────────────────────────
    private int draggedIndex;

    // ── State ─────────────────────────────────────────────────────────────────
    private ViewMode currentViewMode = ViewMode.SERVERS;
    private boolean hasError = false;
    private final Set<UUID> connectingServers = new HashSet<>();
    private ServerSummary pendingConnectServer;
    private Runnable pendingClearSpinner;
    private String searchQuery = "";

    private enum ViewMode {SERVERS, INSTALLATIONS}

    // ── record to carry both fetch results together ───────────────────────────
    private record LoadResult(Map<UUID, ServerSummary> servers, List<InstallationSummary> installations) {
    }

    // ── Services ──────────────────────────────────────────────────────────────

    private final Service<LoadResult> loadDataService = new Service<>() {
        @Override
        protected Task<LoadResult> createTask() {
            return new Task<>() {
                @Override
                protected LoadResult call() throws Exception {
                    Map<UUID, ServerSummary> servers = App.getServices().hub().getServerService().getUserServers();
                    List<InstallationSummary> installations = App.getServices().hub().getInstallationService().getUserInstallations();
                    return new LoadResult(servers, installations);
                }
            };
        }
    };

    private final Service<ServerPage> connectServerService = new Service<>() {
        @Override
        protected Task<ServerPage> createTask() {
            return new Task<>() {
                @Override
                protected ServerPage call() {
                    return new ServerPage(pendingConnectServer);
                }
            };
        }
    };

    private List<UUID> pendingServerOrder;
    private final Service<Void> saveOrderService = new Service<>() {
        @Override
        protected Task<Void> createTask() {
            return new Task<>() {
                @Override
                protected Void call() throws Exception {
                    App.getServices().hub().getServerService().updateServerOrder(pendingServerOrder);
                    return null;
                }
            };
        }
    };

    private Runnable postLoadAction = null;

    // ─────────────────────────────────────────────────────────────────────────
    public HomePage() {

        dmPage = new DirectMessagePage();
        App.setCachedDirectMessagePage(dmPage);

        BorderPane root = new BorderPane();
        root.setTop(buildHeader());
        root.setCenter(buildSplit(buildSpacesColumn()));

        // ── Side panels ───────────────────────────────────────────────────────
        profilePanel = new SidePanel(PROFILE_WIDTH, HEADER_HEIGHT, buildScrollPane(profileSection));
        friendsPanel = new SidePanel(PROFILE_WIDTH, HEADER_HEIGHT, buildScrollPane(friendsSection));

        // ── Outside-click dismissal ───────────────────────────────────────────
        addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            Node focused = getScene() != null ? getScene().getFocusOwner() : null;
            if (focused == searchField) {
                Node current = (Node) e.getTarget();
                boolean onSearchField = false;
                while (current != null) {
                    if (current == searchField) {
                        onSearchField = true;
                        break;
                    }
                    current = current.getParent();
                }
                if (!onSearchField) this.requestFocus();
            }
            if (profilePanel.isOpen() && !profilePanel.getRoot().isMouseTransparent()) {
                Bounds btnBounds = toggleProfileButton.localToScene(toggleProfileButton.getBoundsInLocal());
                if (profilePanel.isOutside(e.getSceneX(), e.getSceneY())) {
                    profilePanel.close(toggleProfileButton);
                    if (btnBounds.contains(e.getSceneX(), e.getSceneY())) e.consume();
                }
            }
            if (friendsPanel.isOpen() && !friendsPanel.getRoot().isMouseTransparent()) {
                Bounds btnBounds = toggleFriendsButton.localToScene(toggleFriendsButton.getBoundsInLocal());
                if (friendsPanel.isOutside(e.getSceneX(), e.getSceneY())) {
                    friendsPanel.close(toggleFriendsButton);
                    if (btnBounds.contains(e.getSceneX(), e.getSceneY())) e.consume();
                }
            }
        });

        getChildren().addAll(root, friendsPanel.getRoot(), profilePanel.getRoot());

        // The DMs live on this page, so every time Home is put back on screen (e.g. after
        // leaving a server) refresh the conversation list and restore the chat's scroll anchor.
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) return;
            Platform.runLater(() -> {
                dmPage.reload();
                dmPage.getChatSection().notifyPageResumed();
                dmPage.onBecameVisible();
            });
        });

        // ── Loading / error / empty nodes ─────────────────────────────────────
        loadingNode = buildLoadingNode();
        errorNode = buildErrorNode();
        emptyServersNode = buildEmptyState(MaterialDesignC.CONTROLLER, "No servers yet", "Create one or join with an invite.");
        noResultsServersNode = buildEmptyState(MaterialDesignC.CONTROLLER, "No servers found", "Try a different search.");
        emptyInstallsNode = buildEmptyState(MaterialDesignS.SERVER, "No installations yet", "Set up one to host your own servers.");
        noResultsInstallsNode = buildEmptyState(MaterialDesignS.SERVER, "No installations found", "Try a different search.");

        // ── Wire loadDataService ──────────────────────────────────────────────
        loadDataService.setOnSucceeded(e -> {
            LoadResult result = loadDataService.getValue();
            serverMap = result.servers();
            installationsList = result.installations();
            refreshServerCards();
            refreshInstallationCards();
            renderSpaces();
            if (postLoadAction != null) {
                postLoadAction.run();
                postLoadAction = null;
            }
        });
        loadDataService.setOnFailed(e -> {
            log.error("Error while loading servers/installations: {}", loadDataService.getException().getMessage());
            showError();
        });

        // ── Wire connectServerService ─────────────────────────────────────────
        connectServerService.setOnSucceeded(evt -> {
            ServerSummary connectedServer = pendingConnectServer;
            Runnable clearSpinner = pendingClearSpinner;
            ServerPage page = connectServerService.getValue();
            App.setCachedServerPage(page);
            Service<?> svc = page.getChannelSection().getLoadChannelsService();
            svc.stateProperty().addListener((obs, oldState, newState) -> {
                if (newState == Worker.State.SUCCEEDED) {
                    connectingServers.remove(connectedServer.getServerId());
                    clearSpinner.run();
                    App.changePage(page);
                } else if (newState == Worker.State.FAILED || newState == Worker.State.CANCELLED) {
                    connectingServers.remove(connectedServer.getServerId());
                    clearSpinner.run();
                    App.setCachedServerPage(null);
                    log.error("Failed to load server channels", svc.getException());
                    new CustomNotification(
                            "Connection Failed",
                            "Failed to connect to the server.",
                            new FontIcon(MaterialDesignC.CLOSE_CIRCLE_OUTLINE)
                    ).showNotification();
                }
            });
            page.getChannelSection().startLoading();
        });
        connectServerService.setOnFailed(evt -> {
            connectingServers.remove(pendingConnectServer.getServerId());
            pendingClearSpinner.run();
            App.setCachedServerPage(null);
            log.error("Failed to connect to server", connectServerService.getException());
            new CustomNotification(
                    "Failed to connect to server.",
                    new FontIcon(MaterialDesignC.CLOSE_CIRCLE_OUTLINE)
            ).showNotification();
        });

        // ── Wire saveOrderService ─────────────────────────────────────────────
        saveOrderService.setOnFailed(e ->
                log.error("Error while updating server order: {}", saveOrderService.getException().getMessage())
        );

    }

    public void startLoading() {
        spacesList.getChildren().setAll(loadingNode);
        loadDataService.start();
        dmPage.reload();
        checkDmUnreadOnStartup();
        checkFriendRequestPendingOnStartup();
    }

    private void checkDmUnreadOnStartup() {
        Thread.ofVirtual().start(() -> {
            try {
                var convos = App.getServices().hub().getDirectMessageService().getConversations();
                if (convos != null && convos.stream().anyMatch(komm.model.dto.summary.ConversationSummary::isHasUnread)) {
                    Platform.runLater(() -> App.setDmUnread(true));
                }
            } catch (Exception e) {
                log.warn("Failed to check DM unread status on startup: {}", e.getMessage());
            }
        });
    }

    private void checkFriendRequestPendingOnStartup() {
        Thread.ofVirtual().start(() -> {
            try {
                var received = App.getServices().hub().getFriendService().getReceivedRequests();
                if (received != null && !received.isEmpty()) {
                    Platform.runLater(() -> App.setFriendRequestPending(true));
                }
            } catch (Exception e) {
                log.warn("Failed to check pending friend requests on startup: {}", e.getMessage());
            }
        });
    }

    public void syncServerPermissions(UUID serverId, List<String> effectivePermissions) {
        if (serverMap == null || !serverMap.containsKey(serverId)) return;
        serverMap.get(serverId).setEffectivePermissions(effectivePermissions);
        refreshServerCards();
        renderSpaces();
    }

    public void syncServerRole(UUID serverId, ServerSummary.Role newRole) {
        if (serverMap == null || !serverMap.containsKey(serverId)) return;
        serverMap.get(serverId).setRole(newRole);
    }

    /** Remove a server from the list (e.g. after it has been deleted). Call on the FX thread. */
    public void removeServer(UUID serverId) {
        if (serverMap == null || serverMap.remove(serverId) == null) return;
        refreshServerCards();
        renderSpaces();
    }

    /** Remove an installation and all its servers from the list. Call on the FX thread. */
    public void removeInstallation(UUID installationId) {
        if (serverMap != null) {
            boolean anyRemoved = serverMap.values().removeIf(s -> installationId.equals(s.getInstallationId()));
            if (anyRemoved) refreshServerCards();
        }
        if (installationsList != null) {
            boolean removed = installationsList.removeIf(i -> installationId.equals(i.getInstallationId()));
            if (removed) refreshInstallationCards();
        }
        renderSpaces();
    }

    // ─── ScrollPane wrapper ───────────────────────────────────────────────────

    private ScrollPane buildScrollPane(Node content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        sp.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(sp, Priority.ALWAYS);
        return sp;
    }

    // ─── Header ───────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        Label brand = new Label("komm.");
        brand.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        toggleFriendsButton = iconBtn(Feather.USERS, "Friends");
        toggleFriendsButton.setOnAction(e -> {
            friendsPanel.toggle(toggleFriendsButton, profilePanel);
            if (friendsPanel.isOpen()) {
                if (friendsSection.isNeedsRefresh()) friendsSection.reload();
            }
        });

        friendsBadgeDot = new Circle(4);
        friendsBadgeDot.setStyle("-fx-fill: -color-accent-emphasis;");
        friendsBadgeDot.visibleProperty().bind(App.friendRequestPendingProperty());
        friendsBadgeDot.setMouseTransparent(true);

        StackPane friendsBtnWrap = new StackPane(toggleFriendsButton, friendsBadgeDot);
        StackPane.setAlignment(friendsBadgeDot, Pos.TOP_RIGHT);
        StackPane.setMargin(friendsBadgeDot, new Insets(2, 2, 0, 0));

        toggleProfileButton = iconBtn(MaterialDesignC.COG_OUTLINE, "Profile & settings");
        toggleProfileButton.setOnAction(e -> profilePanel.toggle(toggleProfileButton, friendsPanel));

        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 16, 10, 20));
        header.setMinHeight(HEADER_HEIGHT);
        header.setMaxHeight(HEADER_HEIGHT);
        header.setStyle(
                "-fx-background-color: -color-sidebar-header;" +
                        "-fx-border-color: transparent transparent -color-border-default transparent;" +
                        "-fx-border-width: 0 0 1px 0;"
        );
        header.getChildren().addAll(brand, spacer, friendsBtnWrap, toggleProfileButton);
        return header;
    }

    // ─── Spaces column (servers / installations) ──────────────────────────────

    private VBox buildSpacesColumn() {
        // Servers | Installations switch: two equal-width tabs, same tab-strip look as the DM sidebar header
        ToggleGroup modeGroup = new ToggleGroup();
        serversToggle = modeToggle("Servers", MaterialDesignA.ACCOUNT_GROUP_OUTLINE, modeGroup);
        installsToggle = modeToggle("Installations", MaterialDesignS.SERVER, modeGroup);
        serversToggle.setSelected(true);
        modeGroup.selectedToggleProperty().addListener((obs, old, neo) -> {
            if (neo == null) {
                old.setSelected(true);
                return;
            }
            setViewMode(neo == serversToggle ? ViewMode.SERVERS : ViewMode.INSTALLATIONS);
        });
        GridPane switcher = new GridPane();
        for (int i = 0; i < 2; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(50);
            cc.setHgrow(Priority.ALWAYS);
            switcher.getColumnConstraints().add(cc);
        }
        switcher.add(serversToggle, 0, 0);
        switcher.add(installsToggle, 1, 0);
        switcher.setMinHeight(TAB_STRIP_HEIGHT);
        switcher.setMaxHeight(TAB_STRIP_HEIGHT);
        switcher.setStyle("-fx-background-color: -color-bg-subtle;"
                + "-fx-border-color: transparent transparent -color-border-default transparent; -fx-border-width: 0 0 1px 0;");

        searchField = buildSearchField("Search servers…", q -> {
            searchQuery = q;
            applyFilters();
        });

        countLabel = new Label("SERVERS");
        countLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle; -fx-padding: 4px 0 0 4px;");

        Button refreshBtn = new Button(null, new FontIcon(MaterialDesignR.REFRESH));
        ((FontIcon) refreshBtn.getGraphic()).setIconSize(13);
        refreshBtn.setFocusTraversable(false);
        refreshBtn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);
        refreshBtn.setTooltip(new Tooltip("Refresh"));
        refreshBtn.setStyle("-fx-padding: 2px;");
        refreshBtn.setOnAction(e -> refreshCurrentView());

        Region countSpacer = new Region();
        HBox.setHgrow(countSpacer, Priority.ALWAYS);
        HBox countRow = new HBox(countLabel, countSpacer, refreshBtn);
        countRow.setAlignment(Pos.CENTER_LEFT);

        spacesList.setFillWidth(true);
        spacesList.setPadding(new Insets(0, 0, 8, 0));
        ScrollPane scroll = new ScrollPane(spacesList);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        createBtn = new Button("Create", new FontIcon(Feather.PLUS));
        createBtn.setFocusTraversable(false);
        createBtn.getStyleClass().addAll(Styles.ACCENT, Styles.BUTTON_OUTLINED, Styles.SMALL, "sidebar-action-btn");
        createBtn.setMaxWidth(Double.MAX_VALUE);
        createBtn.setOnAction(e -> {
            if (currentViewMode == ViewMode.SERVERS) App.showModal(new CreateServerModal());
            else App.showModal(new CreateInstallationModal());
        });

        joinBtn = new Button("Join", new FontIcon(MaterialDesignA.ACCOUNT_MULTIPLE_PLUS));
        joinBtn.setFocusTraversable(false);
        joinBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.SMALL, "sidebar-action-btn");
        joinBtn.setMaxWidth(Double.MAX_VALUE);
        joinBtn.setOnAction(e -> {
            if (currentViewMode == ViewMode.INSTALLATIONS) App.showModal(new komm.ui.modals.JoinInstallationModal());
            else App.showModal(new JoinViaInviteModal());
        });
        HBox.setHgrow(createBtn, Priority.ALWAYS);
        HBox.setHgrow(joinBtn, Priority.ALWAYS);
        HBox actions = new HBox(6, createBtn, joinBtn);

        VBox body = new VBox(10, searchField, countRow, scroll, actions);
        body.setPadding(new Insets(12));
        VBox.setVgrow(body, Priority.ALWAYS);

        VBox column = new VBox(switcher, body);
        column.setMinWidth(SPACES_MIN_WIDTH);
        column.setMaxWidth(SPACES_MAX_WIDTH);
        column.setStyle("-fx-background-color: -color-bg-subtle;");
        return column;
    }

    /** Resizable spaces column | DMs; the column's pixel width is remembered across launches. */
    private SplitPane buildSplit(Region spaces) {
        SplitPane split = new SplitPane(spaces, dmPage);
        SplitPane.setResizableWithParent(spaces, false);
        split.widthProperty().addListener((obs, oldW, newW) -> {
            if (newW.doubleValue() == 0) return;
            if (oldW.doubleValue() == 0) {
                // Read lazily: this page is built during login, before the per-user settings file is loaded.
                double savedPx = Math.max(SPACES_MIN_WIDTH, Math.min(SPACES_MAX_WIDTH, UserSettings.getInstance().getSpacesWidth()));
                split.setDividerPosition(0, savedPx / newW.doubleValue());
            } else {
                double px = split.getDividerPositions()[0] * oldW.doubleValue();
                Platform.runLater(() -> split.setDividerPosition(0, px / newW.doubleValue()));
            }
        });
        // Save when a drag on THIS split's divider ends. Not split.lookup(".split-pane-divider"):
        // the DM page below holds a second SplitPane, and a lookup from here can hit its divider.
        split.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            Node n = e.getTarget() instanceof Node t ? t : null;
            boolean onDivider = false;
            while (n != null && !(n instanceof SplitPane)) {
                if (n.getStyleClass().contains("split-pane-divider")) onDivider = true;
                n = n.getParent();
            }
            if (!onDivider || n != split) return;
            double px = split.getDividerPositions()[0] * split.getWidth();
            if (px > 0) UserSettings.getInstance().setSpacesWidth(px);
        });
        return split;
    }

    /** Tab with an accent underline when selected. */
    private ToggleButton modeToggle(String text, org.kordamp.ikonli.Ikon iconCode, ToggleGroup group) {
        ToggleButton btn = new ToggleButton(text);
        btn.setGraphicTextGap(7);
        btn.setToggleGroup(group);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setMaxHeight(Double.MAX_VALUE);
        btn.setFocusTraversable(false);
        GridPane.setHgrow(btn, Priority.ALWAYS);
        GridPane.setVgrow(btn, Priority.ALWAYS);
        GridPane.setFillWidth(btn, true);
        GridPane.setFillHeight(btn, true);
        Runnable restyle = () -> {
            btn.setGraphic(IconColorUtil.colored(iconCode, btn.isSelected() ? "-color-accent-emphasis" : "-color-fg-muted", 15));
            btn.setStyle(
                "-fx-background-color: " + (btn.isHover() ? "-color-bg-middle" : "transparent") + "; -fx-background-radius: 0; -fx-cursor: hand;" +
                        "-fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 0;" +
                        "-fx-border-radius: 0; -fx-border-width: 0 0 2px 0;" +
                        (btn.isSelected()
                                ? "-fx-text-fill: -color-fg-default; -fx-border-color: transparent transparent -color-accent-emphasis transparent;"
                                : "-fx-text-fill: -color-fg-muted; -fx-border-color: transparent;"));
        };
        btn.selectedProperty().addListener((obs, was, is) -> restyle.run());
        btn.hoverProperty().addListener((obs, was, is) -> restyle.run());
        restyle.run();
        return btn;
    }

    private void setViewMode(ViewMode mode) {
        if (currentViewMode == mode) return;
        currentViewMode = mode;
        syncModeControls();
        applyFilters();
    }

    /** Brings the switch, prompt text and Join label in line with {@link #currentViewMode}. */
    private void syncModeControls() {
        boolean servers = currentViewMode == ViewMode.SERVERS;
        if (servers != serversToggle.isSelected()) (servers ? serversToggle : installsToggle).setSelected(true);
        searchField.setPromptText(servers ? "Search servers…" : "Search installations…");
        
    }

    private TextField buildSearchField(String prompt, Consumer<String> onSearch) {
        TextField tf = new TextField() {
            @Override
            public void requestFocus() {
                if (isFocused()) return;
                if (getScene() != null && getScene().getFocusOwner() == this) return;
                if (isPressed()) {
                    super.requestFocus();
                }
            }
        };
        tf.setPromptText(prompt);
        tf.getStyleClass().add(Styles.SMALL);
        tf.setMaxWidth(Double.MAX_VALUE);
        tf.textProperty().addListener((obs, old, val) -> onSearch.accept(val == null ? "" : val.trim()));
        return tf;
    }

    // ─── Filter / search ──────────────────────────────────────────────────────

    private void applyFilters() {
        String q = searchQuery.toLowerCase();

        filteredServerCards.clear();
        for (ServerCard c : allServerCards) {
            if (c.getServerSummary() == null) continue;
            if (!q.isEmpty() && !c.getServerSummary().getServerName().toLowerCase().contains(q)) continue;
            filteredServerCards.add(c);
        }

        filteredInstallationCards.clear();
        for (InstallationCard c : allInstallationCards) {
            if (c.getInstallation() == null) continue;
            if (!q.isEmpty() && !c.getInstallation().getInstallationName().toLowerCase().contains(q)) continue;
            filteredInstallationCards.add(c);
        }

        renderSpaces();
    }

    private void updateCountLabel() {
        if (countLabel == null) return;
        boolean servers = currentViewMode == ViewMode.SERVERS;
        int shown = servers ? filteredServerCards.size() : filteredInstallationCards.size();
        int all = servers ? allServerCards.size() : allInstallationCards.size();
        String noun = servers ? "SERVERS" : "INSTALLATIONS";
        countLabel.setText(shown == all ? noun + " · " + all : noun + " · " + shown + " OF " + all);
    }

    // ─── Server connect (async, non-blocking) ────────────────────────────────

    private void connectToServer(ServerSummary server, Runnable showSpinner, Runnable clearSpinner) {
        if (connectingServers.contains(server.getServerId())) return;

        // If the page is already cached and fully loaded, navigate instantly.
        ServerPage existing = App.getCachedServerPage();
        if (existing != null && existing.getServer().getServerId().equals(server.getServerId())) {
            Service<?> svc = existing.getChannelSection().getLoadChannelsService();
            Worker.State state = svc.getState();
            if (state == Worker.State.SUCCEEDED || state == Worker.State.FAILED
                    || state == Worker.State.CANCELLED) {
                App.changePage(existing);
                return;
            }
            // Already in progress — attach the spinner to it.
            connectingServers.add(server.getServerId());
            showSpinner.run();
            svc.stateProperty().addListener((obs, oldState, newState) -> {
                if (newState == Worker.State.SUCCEEDED || newState == Worker.State.FAILED
                        || newState == Worker.State.CANCELLED) {
                    connectingServers.remove(server.getServerId());
                    clearSpinner.run();
                    App.changePage(existing);
                }
            });
            return;
        }

        Runnable doConnect = () -> {
            App.disconnectFromVoice();
            connectingServers.add(server.getServerId());
            showSpinner.run();
            pendingConnectServer = server;
            pendingClearSpinner = clearSpinner;
            connectServerService.restart();
        };

        if (existing != null) {
            FontIcon icon = new FontIcon(MaterialDesignS.SWAP_HORIZONTAL);
            icon.setIconSize(40);
            App.showModal(new ConfirmationModal(
                    "Switch Server",
                    "You are currently connected to \"" + existing.getServer().getServerName() + "\". Joining a new server will disconnect you from it.",
                    icon,
                    () -> {
                        App.closeModal();
                        doConnect.run();
                    }
            ));
        } else {
            doConnect.run();
        }
    }

    // ─── Spaces list ──────────────────────────────────────────────────────────

    /** Rebuilds the list in the spaces column for the current mode. */
    private void renderSpaces() {
        updateCountLabel();
        if (hasError) {
            spacesList.getChildren().setAll(errorNode);
            return;
        }
        if (serverMap == null) return; // first load still running; the loading node stays

        if (currentViewMode == ViewMode.SERVERS) {
            if (filteredServerCards.isEmpty()) {
                spacesList.getChildren().setAll(allServerCards.isEmpty() ? emptyServersNode : noResultsServersNode);
                return;
            }
            for (ServerCard card : filteredServerCards) {
                setupDragAndDrop(card, allServerCards.indexOf(card));
                card.setConnectAction(() -> connectToServer(
                        card.getServerSummary(), card::showConnecting, card::clearConnecting));
            }
            spacesList.getChildren().setAll(filteredServerCards);
        } else {
            if (filteredInstallationCards.isEmpty()) {
                spacesList.getChildren().setAll(allInstallationCards.isEmpty() ? emptyInstallsNode : noResultsInstallsNode);
                return;
            }
            spacesList.getChildren().setAll(filteredInstallationCards);
        }
    }

    // ─── Empty state ──────────────────────────────────────────────────────────

    private Node buildEmptyState(org.kordamp.ikonli.Ikon iconCode, String title, String subtitle) {
        FontIcon icon = IconColorUtil.colored(iconCode, "-color-accent-emphasis", 40);
        icon.setOpacity(0.18);

        Label titleLbl = new Label(title);
        titleLbl.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");

        Label subtitleLbl = new Label(subtitle);
        subtitleLbl.setStyle("-fx-font-size: 11.5px; -fx-text-fill: -color-fg-subtle;");
        subtitleLbl.setWrapText(true);
        subtitleLbl.setMaxWidth(200);
        subtitleLbl.setAlignment(Pos.CENTER);
        subtitleLbl.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        VBox box = new VBox(8, icon, titleLbl, subtitleLbl);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(28, 8, 8, 8));
        return box;
    }

    // ─── Loading / error nodes ────────────────────────────────────────────────

    private VBox buildLoadingNode() {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(32, 32);
        spinner.setMaxSize(32, 32);
        Label lbl = new Label("Loading…");
        lbl.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");
        VBox box = new VBox(10, spinner, lbl);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(32, 0, 0, 0));
        return box;
    }

    private VBox buildErrorNode() {
        FontIcon icon = IconColorUtil.colored(MaterialDesignL.LAN_DISCONNECT, "-color-accent-emphasis", 40);
        icon.setOpacity(0.18);
        Label title = new Label("Failed to load data");
        title.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");
        Label subtitle = new Label("Check your connection and try again.");
        subtitle.setStyle("-fx-font-size: 11.5px; -fx-text-fill: -color-fg-muted;");
        subtitle.setWrapText(true);
        VBox box = new VBox(8, icon, title, subtitle);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(28, 8, 8, 8));
        return box;
    }

    // ─── Data loading ─────────────────────────────────────────────────────────

    public void refreshCurrentView() {
        if (loadDataService.isRunning()) return;
        hasError = false;
        spacesList.getChildren().setAll(loadingNode);
        loadDataService.restart();
    }

    public void refreshInstallationsView() {
        if (loadDataService.isRunning()) return;
        currentViewMode = ViewMode.INSTALLATIONS;
        syncModeControls();
        hasError = false;
        spacesList.getChildren().setAll(loadingNode);
        loadDataService.restart();
    }

    public void refreshAndShowDownload(UUID installationId) {
        if (loadDataService.isRunning()) return;
        currentViewMode = ViewMode.INSTALLATIONS;
        syncModeControls();
        hasError = false;
        spacesList.getChildren().setAll(loadingNode);
        postLoadAction = () -> {
            DownloadInstallationModal newModal = new DownloadInstallationModal(installationId);
            App.showModal(newModal);
            new CustomNotification("Installation Created",
                    "Your installation is ready. Download the JAR to get started.",
                    new FontIcon(MaterialDesignS.SERVER)).showNotification();
        };
        loadDataService.restart();
    }

    private void refreshServerCards() {
        allServerCards.clear();
        serverMap.entrySet().stream()
                .sorted((a, b) -> {
                    Integer o1 = a.getValue().getDisplayOrder();
                    Integer o2 = b.getValue().getDisplayOrder();
                    if (o1 == null && o2 == null) return 0;
                    if (o1 == null) return 1;
                    if (o2 == null) return -1;
                    return o1.compareTo(o2);
                })
                .forEach(e -> allServerCards.add(new ServerCard(e.getValue(), true)));
        applyFilters();
    }

    private void refreshInstallationCards() {
        allInstallationCards.clear();
        installationsList.forEach(i -> allInstallationCards.add(new InstallationCard(i, true)));
        applyFilters();
    }

    public List<ServerSummary> getServersForInstallation(UUID installationId) {
        if (serverMap == null) return List.of();
        return serverMap.values().stream()
                .filter(s -> installationId.equals(s.getInstallationId()))
                .toList();
    }

    // ─── Direct messages ──────────────────────────────────────────────────────

    /**
     * The DM chat section on this page. The DMs are always part of Home, so this is
     * non-null whenever Home is the page on screen; {@code App.showModal} uses it to
     * reach the DM scroll.
     */
    public DmChatSection getActiveDmChatSection() {
        return dmPage.getChatSection();
    }

    public void navigateToDm(UUID partnerId, String partnerUsername) {
        if (profilePanel.isOpen()) profilePanel.close(toggleProfileButton);
        if (friendsPanel.isOpen()) friendsPanel.close(toggleFriendsButton);
        dmPage.reload();
        dmPage.openConversation(partnerId, partnerUsername);
        dmPage.onBecameVisible();
    }

    private void showError() {
        hasError = true;
        spacesList.getChildren().setAll(errorNode);
    }

    // ─── Drag and drop (server cards used internally for ordering) ────────────

    private void setupDragAndDrop(ServerCard card, int idx) {
        if (idx < 0) return;
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
            if (ev.getGestureSource() != card && ev.getDragboard().hasString())
                ev.acceptTransferModes(TransferMode.MOVE);
            ev.consume();
        });
        card.setOnDragEntered(ev -> {
            if (ev.getGestureSource() != card) card.getStyleClass().add("drag-target");
            ev.consume();
        });
        card.setOnDragExited(ev -> {
            card.getStyleClass().remove("drag-target");
            ev.consume();
        });
        card.setOnDragDropped(ev -> {
            boolean ok = false;
            if (ev.getDragboard().hasString() && draggedIndex != idx) {
                allServerCards.add(idx, allServerCards.remove(draggedIndex));
                applyFilters();
                saveServerOrder();
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

    public void updateActiveUsers(UUID serverId, int activeUsers) {
        allServerCards.stream()
                .filter(c -> c.getServerSummary() != null && serverId.equals(c.getServerSummary().getServerId()))
                .findFirst()
                .ifPresent(c -> c.updateActiveUsers(activeUsers));

    }

    private void saveServerOrder() {
        pendingServerOrder = allServerCards.stream()
                .map(c -> c.getServerSummary() != null ? c.getServerSummary().getServerId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (saveOrderService.isRunning()) return;
        saveOrderService.restart();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Button iconBtn(org.kordamp.ikonli.Ikon icon, String tooltip) {
        Button btn = new Button(null, new FontIcon(icon));
        btn.setFocusTraversable(false);
        btn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT, Styles.SMALL);
        btn.setTooltip(new Tooltip(tooltip));
        return btn;
    }

    // ─── Friends badge ────────────────────────────────────────────────────────

    public void incrementFriendsBadge() {
        App.setFriendRequestPending(true);
    }

    public boolean isFriendsPanelOpen() {
        return friendsPanel != null && friendsPanel.isOpen();
    }
}
