package komm.ui.pages;

import atlantafx.base.theme.Styles;
import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
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
import komm.ui.modals.CreateInviteModal;
import komm.ui.modals.CreateServerModal;
import komm.ui.modals.DownloadInstallationModal;
import komm.ui.modals.EditServerModal;
import komm.ui.modals.JoinViaInviteModal;
import komm.ui.customnodes.CustomNotification;
import komm.ui.chat.DmChatSection;
import komm.ui.pages.DirectMessagePage;
import komm.ui.sections.FriendsSection;
import komm.ui.sections.ProfileSection;
import komm.ui.utils.SidePanel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

import java.util.*;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
public class HomePage extends StackPane {

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final double PROFILE_WIDTH = 380.0;
    private static final double SIDEBAR_WIDTH = 200.0;
    private static final double HEADER_HEIGHT = 96.0;

    // ── Data ──────────────────────────────────────────────────────────────────
    private Map<UUID, ServerSummary> serverMap;
    private List<InstallationSummary> installationsList;
    private List<ServerCard> allServerCards = new ArrayList<>();
    private List<InstallationCard> allInstallationCards = new ArrayList<>();

    // ── Filtered cards ────────────────────────────────────────────────────────
    private List<ServerCard> filteredServerCards = new ArrayList<>();
    private List<InstallationCard> filteredInstallationCards = new ArrayList<>();

    // ── Layout nodes ──────────────────────────────────────────────────────────
    private StackPane centerContainer = new StackPane();
    private VBox loadingNode;
    private VBox errorNode;
    private Node emptyInstallsNode;
    private Node noResultsInstallsNode;

    // ── Sidebar nav items ─────────────────────────────────────────────────────
    private VBox navServers;
    private VBox navInstallations;
    private VBox navMessages;
    private Label pageTitleLabel;

    // ── Action strip ──────────────────────────────────────────────────────────
    private HBox actionStrip;
    private Button joinBtn;
    private ToggleGroup serverFilterGroup = new ToggleGroup();
    private ToggleGroup installFilterGroup = new ToggleGroup();
    private HBox serverFilterChips;
    private HBox installFilterChips;
    private StackPane filterChipSlot;
    private Label countLabel;
    private Circle friendsBadgeDot;
    private Circle dmNavDot;

    // ── Side panels ───────────────────────────────────────────────────────────
    @Getter
    private final ProfileSection profileSection = new ProfileSection();
    @Getter
    private final FriendsSection friendsSection = new FriendsSection();
    private SidePanel profilePanel;
    private SidePanel friendsPanel;
    private Button toggleProfileButton;
    private Button toggleFriendsButton;

    // ── Drag ──────────────────────────────────────────────────────────────────
    private int draggedIndex;

    // ── State ─────────────────────────────────────────────────────────────────
    private ViewMode currentViewMode = ViewMode.SERVERS;
    private boolean hasError = false;
    private final Set<UUID> connectingServers = new HashSet<>();
    private ServerSummary pendingConnectServer;
    private Runnable pendingClearSpinner;

    // ── Active filter labels ──────────────────────────────────────────────────
    private String activeServerFilter = "ALL";
    private String activeInstallFilter = "ALL";

    // ── Search queries ────────────────────────────────────────────────────────
    private String serverSearchQuery = "";
    private String installSearchQuery = "";
    private TextField serverSearchField;
    private TextField installSearchField;

    private enum ViewMode {SERVERS, INSTALLATIONS, MESSAGES}

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

        BorderPane root = new BorderPane();
        root.setLeft(buildSidebar());

        BorderPane main = new BorderPane();
        main.setTop(buildHeader());

        actionStrip = buildActionStrip();

        BorderPane innerMain = new BorderPane();
        innerMain.setTop(actionStrip);
        innerMain.setCenter(centerContainer);

        main.setCenter(innerMain);
        root.setCenter(main);

        // ── Side panels ───────────────────────────────────────────────────────
        profilePanel = new SidePanel(PROFILE_WIDTH, HEADER_HEIGHT, buildScrollPane(profileSection));
        friendsPanel = new SidePanel(PROFILE_WIDTH, HEADER_HEIGHT, buildScrollPane(friendsSection));

        // ── Outside-click dismissal ───────────────────────────────────────────
        addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            Node focused = getScene() != null ? getScene().getFocusOwner() : null;
            if (focused == serverSearchField || focused == installSearchField) {
                Node current = (Node) e.getTarget();
                boolean onSearchField = false;
                while (current != null) {
                    if (current == serverSearchField || current == installSearchField) {
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

        // ── Loading / error / empty nodes ─────────────────────────────────────
        loadingNode = buildLoadingNode();
        errorNode = buildErrorNode();
        emptyInstallsNode = buildEmptyState(MaterialDesignS.SERVER, "No installations yet", "Set up an installation to host your own servers.");
        noResultsInstallsNode = buildEmptyState(MaterialDesignS.SERVER, "No installations found", "Try a different search term or filter.");

        // ── Wire loadDataService ──────────────────────────────────────────────
        loadDataService.setOnSucceeded(e -> {
            LoadResult result = loadDataService.getValue();
            serverMap = result.servers();
            installationsList = result.installations();
            refreshServerCards();
            refreshInstallationCards();
            showListView();
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
        loadDataService.start();
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
    }

    public void syncServerRole(UUID serverId, ServerSummary.Role newRole) {
        if (serverMap == null || !serverMap.containsKey(serverId)) return;
        serverMap.get(serverId).setRole(newRole);
    }

    /** Remove a server from the list (e.g. after it has been deleted). Call on the FX thread. */
    public void removeServer(UUID serverId) {
        if (serverMap == null || serverMap.remove(serverId) == null) return;
        refreshServerCards();
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

    // ─── Sidebar ──────────────────────────────────────────────────────────────

    private VBox buildSidebar() {
        VBox sidebar = new VBox(4);
        sidebar.setPrefWidth(SIDEBAR_WIDTH);
        sidebar.setMinWidth(SIDEBAR_WIDTH);
        sidebar.setMaxWidth(SIDEBAR_WIDTH);
        sidebar.setPadding(new Insets(0, 8, 16, 8));
        sidebar.setStyle(
                "-fx-background-color: -color-bg-subtle;" +
                        "-fx-border-color: transparent -color-border-default transparent transparent;" +
                        "-fx-border-width: 0 1px 0 0;"
        );

        HBox brandRow = new HBox();
        brandRow.setAlignment(Pos.CENTER_LEFT);
        brandRow.setPadding(new Insets(16, 8, 20, 10));
        Label brand = new Label("komm.");
        brand.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");
        brandRow.getChildren().add(brand);

        Label sectionLbl = new Label("VIEWS");
        sectionLbl.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle; -fx-padding: 0 0 4px 10px;");

        navServers = buildNavItem(new FontIcon(MaterialDesignC.CONTROLLER), "Servers", ViewMode.SERVERS);
        navInstallations = buildNavItem(new FontIcon(MaterialDesignS.SERVER), "Installations", ViewMode.INSTALLATIONS);
        setNavActive(navServers);
        setNavInactive(navInstallations);

        Separator msgDivider = new Separator();
        msgDivider.setPadding(new Insets(4, 8, 4, 8));

        navMessages = buildNavItem(new FontIcon(Feather.MESSAGE_CIRCLE), "Messages", ViewMode.MESSAGES);
        setNavInactive(navMessages);

        dmNavDot = new Circle(5);
        dmNavDot.setStyle("-fx-fill: -color-accent-emphasis;");
        dmNavDot.setVisible(false);
        dmNavDot.setMouseTransparent(true);
        StackPane navMsgWrap = new StackPane(navMessages, dmNavDot);
        StackPane.setAlignment(dmNavDot, Pos.CENTER_RIGHT);
        StackPane.setMargin(dmNavDot, new Insets(0, 12, 0, 0));

        App.dmUnreadProperty().addListener((obs, old, newVal) -> {
            if (dmNavDot != null) dmNavDot.setVisible(newVal && currentViewMode != ViewMode.MESSAGES);
        });

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        String versionStr = komm.utils.AppConfig.getInstance().getProperty("client.version");
        Label version = new Label("v" + (versionStr != null && !versionStr.isBlank() ? versionStr : "dev"));
        version.setStyle("-fx-font-size: 10px; -fx-text-fill: -color-fg-subtle; -fx-padding: 0 0 0 10px;");

        sidebar.getChildren().addAll(brandRow, sectionLbl, navServers, navInstallations,
                msgDivider, navMsgWrap, spacer, version);
        return sidebar;
    }

    private VBox buildNavItem(FontIcon icon, String text, ViewMode mode) {
        Label lbl = new Label(text);
        lbl.getStyleClass().add("nav-label");
        HBox row = new HBox(10, icon, lbl);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox item = new VBox(row);
        item.setAlignment(Pos.CENTER_LEFT);
        item.setPadding(new Insets(9, 12, 9, 12));
        item.getStyleClass().add("nav-item");
        item.setOnMouseClicked(e -> {
            if (currentViewMode != mode) {
                if (currentViewMode == ViewMode.MESSAGES && App.getCachedDirectMessagePage() != null)
                    App.getCachedDirectMessagePage().getChatSection().saveScrollPosition();
                currentViewMode = mode;
                setNavActive(item);
                if (mode == ViewMode.SERVERS) {
                    setNavInactive(navInstallations);
                    setNavInactive(navMessages);
                } else if (mode == ViewMode.INSTALLATIONS) {
                    setNavInactive(navServers);
                    setNavInactive(navMessages);
                } else {
                    setNavInactive(navServers);
                    setNavInactive(navInstallations);
                }
                switchView();
            }
        });
        return item;
    }

    private void setNavActive(VBox item) {
        item.getStyleClass().remove("nav-inactive");
        if (!item.getStyleClass().contains("nav-active")) item.getStyleClass().add("nav-active");
    }

    private void setNavInactive(VBox item) {
        item.getStyleClass().remove("nav-active");
        if (!item.getStyleClass().contains("nav-inactive")) item.getStyleClass().add("nav-inactive");
    }

    // ─── Header ───────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        pageTitleLabel = new Label("Servers");
        pageTitleLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");

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

        toggleProfileButton = iconBtn(Feather.SETTINGS, "Settings");
        toggleProfileButton.setOnAction(e -> profilePanel.toggle(toggleProfileButton, friendsPanel));

        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 16, 10, 20));
        header.setMinHeight(48);
        header.setMaxHeight(48);
        header.setStyle(
                "-fx-background-color: -color-bg-subtle;" +
                        "-fx-border-color: transparent transparent -color-border-default transparent;" +
                        "-fx-border-width: 0 0 1px 0;"
        );
        header.getChildren().addAll(pageTitleLabel, spacer, friendsBtnWrap, toggleProfileButton);
        return header;
    }

    // ─── Action strip ─────────────────────────────────────────────────────────

    private HBox buildActionStrip() {
        HBox createBtnGroup = buildCreateButtonGroup();

        Separator rule = new Separator(Orientation.VERTICAL);
        rule.setMaxHeight(20);

        countLabel = new Label("0 servers");
        countLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted; -fx-padding: 0 12px 0 0;");

        serverSearchField = buildSearchField("Search servers…", q -> {
            serverSearchQuery = q;
            applyServerFilter();
        });
        installSearchField = buildSearchField("Search installations…", q -> {
            installSearchQuery = q;
            applyInstallFilter();
        });
        HBox.setHgrow(serverSearchField, Priority.ALWAYS);
        HBox.setHgrow(installSearchField, Priority.ALWAYS);

        StackPane searchSlot = new StackPane(serverSearchField);
        searchSlot.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(searchSlot, Priority.ALWAYS);

        serverFilterChips = buildServerFilterChips();
        installFilterChips = buildInstallFilterChips();

        filterChipSlot = new StackPane(serverFilterChips);
        filterChipSlot.setAlignment(Pos.CENTER_RIGHT);

        Separator refreshDivider = new Separator(Orientation.VERTICAL);
        refreshDivider.setMaxHeight(20);
        //HBox.setMargin(refreshDivider, new Insets(0, 4, 0, 10));

        Button refreshBtn = new Button(null, new FontIcon(MaterialDesignR.REFRESH));
        ((FontIcon) refreshBtn.getGraphic()).setIconSize(13);
        refreshBtn.setFocusTraversable(false);
        refreshBtn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);
        refreshBtn.setTooltip(new Tooltip("Refresh"));
        refreshBtn.setStyle("-fx-padding: 4px;");
        refreshBtn.setOnAction(e -> refreshCurrentView());

        HBox strip = new HBox(0, createBtnGroup, rule, countLabel, searchSlot, filterChipSlot, refreshDivider, refreshBtn);
        strip.setAlignment(Pos.CENTER_LEFT);
        strip.setPadding(new Insets(8, 16, 8, 14));
        strip.setMinHeight(48);
        strip.setMaxHeight(48);
        strip.setStyle(
                "-fx-background-color: -color-bg-subtle;" +
                        "-fx-border-color: transparent transparent -color-border-default transparent;" +
                        "-fx-border-width: 0 0 1px 0;"
        );
        strip.setUserData(new Object[]{countLabel, searchSlot});
        return strip;
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
        tf.setStyle("-fx-background-radius: 20px; -fx-padding: 5px 12px; -fx-font-size: 12px;");
        tf.textProperty().addListener((obs, old, val) -> onSearch.accept(val == null ? "" : val.trim()));
        return tf;
    }

    private HBox buildCreateButtonGroup() {
        FontIcon createIcon = new FontIcon(Feather.PLUS);
        createIcon.setIconSize(14);
        Button createBtn = new Button("Create new", createIcon);
        createBtn.setFocusTraversable(false);
        createBtn.getStyleClass().addAll(Styles.ACCENT, Styles.BUTTON_OUTLINED, Styles.SMALL);
        createBtn.setStyle("-fx-background-radius: 20px; -fx-padding: 6px 16px 6px 12px; -fx-font-size: 12px;");
        createBtn.setOnAction(e -> {
            if (currentViewMode == ViewMode.SERVERS) App.showModal(new CreateServerModal());
            else App.showModal(new CreateInstallationModal());
        });

        FontIcon joinIcon = new FontIcon(MaterialDesignA.ACCOUNT_MULTIPLE_PLUS);
        joinIcon.setIconSize(14);
        joinBtn = new Button("Join existing", joinIcon);
        joinBtn.setFocusTraversable(false);
        joinBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.SMALL);
        joinBtn.setStyle("-fx-background-radius: 20px; -fx-padding: 6px 16px 6px 12px; -fx-font-size: 12px;");
        joinBtn.setOnAction(e -> {
            if (currentViewMode == ViewMode.INSTALLATIONS) App.showModal(new komm.ui.modals.JoinInstallationModal());
            else App.showModal(new JoinViaInviteModal());
        });
        joinBtn.setVisible(true);
        joinBtn.setManaged(true);

        HBox box = new HBox(8, createBtn, joinBtn);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private HBox buildServerFilterChips() {
        HBox row = new HBox(6);
        row.setAlignment(Pos.CENTER_RIGHT);
        row.setPadding(new Insets(0, 0, 0, 10));
        ToggleButton all = filterChip("All", "ALL", serverFilterGroup);
        ToggleButton owner = filterChip("Owner", "OWNER", serverFilterGroup);
        ToggleButton member = filterChip("Member", "MEMBER", serverFilterGroup);
        all.setSelected(true);
        serverFilterGroup.selectedToggleProperty().addListener((obs, old, neo) -> {
            if (neo == null) {
                all.setSelected(true);
                return;
            }
            activeServerFilter = (String) neo.getUserData();
            applyServerFilter();
        });
        row.getChildren().addAll(all, owner, member);
        return row;
    }

    private HBox buildInstallFilterChips() {
        HBox row = new HBox(6);
        row.setAlignment(Pos.CENTER_RIGHT);
        row.setPadding(new Insets(0, 0, 0, 10));
        ToggleButton all = filterChip("All", "ALL", installFilterGroup);
        ToggleButton online = filterChip("Online", InstallationSummary.InstallationStatus.ONLINE.name(), installFilterGroup);
        ToggleButton offline = filterChip("Offline", InstallationSummary.InstallationStatus.OFFLINE.name(), installFilterGroup);
        ToggleButton unverified = filterChip("Unverified", InstallationSummary.InstallationStatus.NOT_VERIFIED.name(), installFilterGroup);
        all.setSelected(true);
        installFilterGroup.selectedToggleProperty().addListener((obs, old, neo) -> {
            if (neo == null) {
                all.setSelected(true);
                return;
            }
            activeInstallFilter = (String) neo.getUserData();
            applyInstallFilter();
        });
        row.getChildren().addAll(all, online, offline, unverified);
        return row;
    }

    private ToggleButton filterChip(String text, String value, ToggleGroup group) {
        ToggleButton btn = new ToggleButton(text);
        btn.setToggleGroup(group);
        btn.setUserData(value);
        btn.setFocusTraversable(false);
        btn.getStyleClass().add(Styles.SMALL);
        btn.setStyle("-fx-background-radius: 20px; -fx-padding: 4px 12px; -fx-font-size: 11px; -fx-cursor: hand;");
        btn.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
            if (isSelected) {
                if (!btn.getStyleClass().contains(Styles.ACCENT)) btn.getStyleClass().add(Styles.ACCENT);
            } else btn.getStyleClass().remove(Styles.ACCENT);
        });
        return btn;
    }

    // ─── Filter / search ──────────────────────────────────────────────────────

    private void applyServerFilter() {
        filteredServerCards.clear();
        String q = serverSearchQuery.toLowerCase();
        for (ServerCard c : allServerCards) {
            if (c.getServerSummary() == null) continue;
            if (!"ALL".equals(activeServerFilter) &&
                    !c.getServerSummary().getRole().toString().equalsIgnoreCase(activeServerFilter)) continue;
            if (!q.isEmpty() && !c.getServerSummary().getServerName().toLowerCase().contains(q)) continue;
            filteredServerCards.add(c);
        }
        updateCountLabel();
        if (!hasError && !centerContainer.getChildren().contains(loadingNode)) showListView();
    }

    private void applyInstallFilter() {
        filteredInstallationCards.clear();
        String q = installSearchQuery.toLowerCase();
        for (InstallationCard c : allInstallationCards) {
            if (c.getInstallation() == null) continue;
            if (!"ALL".equals(activeInstallFilter) &&
                    !c.getInstallation().getStatus().toString().equalsIgnoreCase(activeInstallFilter)) continue;
            if (!q.isEmpty() && !c.getInstallation().getInstallationName().toLowerCase().contains(q)) continue;
            filteredInstallationCards.add(c);
        }
        updateCountLabel();
        if (!hasError && !centerContainer.getChildren().contains(loadingNode)) showListView();
    }

    private void updateCountLabel() {
        if (countLabel == null) return;
        List<?> filtered = currentViewMode == ViewMode.SERVERS ? filteredServerCards : filteredInstallationCards;
        List<?> all = currentViewMode == ViewMode.SERVERS ? allServerCards : allInstallationCards;
        int fCount = (int) filtered.stream().filter(c -> c instanceof ServerCard sc
                ? sc.getServerSummary() != null : ((InstallationCard) c).getInstallation() != null).count();
        int aCount = (int) all.stream().filter(c -> c instanceof ServerCard sc
                ? sc.getServerSummary() != null : ((InstallationCard) c).getInstallation() != null).count();
        String noun = currentViewMode == ViewMode.SERVERS ? "servers" : "installations";
        countLabel.setText(fCount == aCount
                ? fCount + " " + noun
                : fCount + " of " + aCount + " shown");
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

    // ─── List view ────────────────────────────────────────────────────────────

    private void showListView() {
        if (currentViewMode == ViewMode.SERVERS) {
            centerContainer.getChildren().setAll(buildServerCardList());
        } else {
            centerContainer.getChildren().setAll(buildInstallListContent());
        }
    }

    // ─── Server card list (original layout) ──────────────────────────────────

    private Node buildServerCardList() {
        VBox list = new VBox(8);
        list.setFillWidth(true);
        list.setMaxWidth(LIST_MAX_WIDTH);

        if (filteredServerCards.isEmpty()) {
            boolean isFiltered = !allServerCards.isEmpty();
            Node emptyNode = isFiltered
                    ? buildEmptyState(MaterialDesignC.CONTROLLER, "No servers found", "Try a different search term or filter.")
                    : buildEmptyState(MaterialDesignC.CONTROLLER, "No servers yet", "Create your first server or join an existing one.");
            StackPane wrapper = new StackPane(emptyNode);
            wrapper.setAlignment(Pos.CENTER);
            VBox.setVgrow(wrapper, Priority.ALWAYS);
            list.getChildren().add(wrapper);
        } else {
            for (ServerCard card : filteredServerCards) {
                setupDragAndDrop(card, allServerCards.indexOf(card));
                card.setConnectAction(() -> connectToServer(
                        card.getServerSummary(), card::showConnecting, card::clearConnecting));
                list.getChildren().add(card);
            }
        }

        StackPane centering = new StackPane(list);
        centering.setAlignment(Pos.TOP_CENTER);
        centering.setPadding(new Insets(filteredServerCards.isEmpty() ? 0 : 16, 16, 16, 16));

        ScrollPane scroll = new ScrollPane(centering);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(filteredServerCards.isEmpty());
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        return scroll;
    }
    // ─── Installation list content (unchanged layout) ─────────────────────────

    private static final double LIST_MAX_WIDTH = 880.0;

    private Node buildInstallListContent() {
        List<?> cards = filteredInstallationCards;

        VBox list = new VBox(8);
        list.setFillWidth(true);
        list.setMaxWidth(LIST_MAX_WIDTH);

        if (cards.isEmpty()) {
            boolean isFiltered = !allInstallationCards.isEmpty();
            Node emptyNode = isFiltered ? noResultsInstallsNode : emptyInstallsNode;
            StackPane emptyWrapper = new StackPane(emptyNode);
            emptyWrapper.setAlignment(Pos.CENTER);
            VBox.setVgrow(emptyWrapper, Priority.ALWAYS);
            list.getChildren().add(emptyWrapper);
        } else {
            for (Object card : cards) list.getChildren().add((Node) card);
        }

        StackPane centering = new StackPane(list);
        centering.setAlignment(Pos.TOP_CENTER);
        centering.setPadding(new Insets(cards.isEmpty() ? 0 : 16, 16, 16, 16));

        ScrollPane scroll = new ScrollPane(centering);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(cards.isEmpty());
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        return scroll;
    }

    // ─── Empty state ──────────────────────────────────────────────────────────

    private Node buildEmptyState(org.kordamp.ikonli.Ikon iconCode, String title, String subtitle) {
        FontIcon icon = new FontIcon(iconCode);
        icon.getStyleClass().add("custom-icon-72");
        icon.setOpacity(0.12);

        Label titleLbl = new Label(title);
        titleLbl.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");

        Label subtitleLbl = new Label(subtitle);
        subtitleLbl.setStyle("-fx-font-size: 13px; -fx-text-fill: -color-fg-subtle;");
        subtitleLbl.setWrapText(true);
        subtitleLbl.setMaxWidth(300);
        subtitleLbl.setAlignment(Pos.CENTER);

        VBox box = new VBox(14, icon, titleLbl, subtitleLbl);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(60));
        return box;
    }


    // ─── Loading / error nodes ────────────────────────────────────────────────

    private VBox buildLoadingNode() {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(48, 48);
        spinner.getStyleClass().add(Styles.LARGE);
        Label lbl = new Label("Loading…");
        lbl.setStyle("-fx-font-size: 13px; -fx-text-fill: -color-fg-muted;");
        VBox box = new VBox(14, spinner, lbl);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private VBox buildErrorNode() {
        FontIcon icon = new FontIcon(MaterialDesignL.LAN_DISCONNECT);
        icon.getStyleClass().add("custom-icon-72");
        icon.setOpacity(0.12);
        Label title = new Label("Failed to load data");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");
        Label subtitle = new Label("Check your connection and try again.");
        subtitle.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");
        VBox box = new VBox(12, icon, title, subtitle);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    // ─── Data loading ─────────────────────────────────────────────────────────

    public void refreshCurrentView() {
        if (loadDataService.isRunning()) return;
        hasError = false;
        centerContainer.getChildren().setAll(loadingNode);
        loadDataService.restart();
    }

    public void refreshInstallationsView() {
        if (loadDataService.isRunning()) return;
        currentViewMode = ViewMode.INSTALLATIONS;
        setNavActive(navInstallations);
        setNavInactive(navServers);
        setNavInactive(navMessages);
        pageTitleLabel.setText("Installations");
        hasError = false;
        centerContainer.getChildren().setAll(loadingNode);
        loadDataService.restart();
    }

    public void refreshAndShowDownload(UUID installationId) {
        if (loadDataService.isRunning()) return;
        currentViewMode = ViewMode.INSTALLATIONS;
        setNavActive(navInstallations);
        setNavInactive(navServers);
        pageTitleLabel.setText("Installations");
        hasError = false;
        centerContainer.getChildren().setAll(loadingNode);
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
                .forEach(e -> allServerCards.add(new ServerCard(e.getValue())));
        applyServerFilter();
    }

    private void refreshInstallationCards() {
        allInstallationCards.clear();
        installationsList.forEach(i -> allInstallationCards.add(new InstallationCard(i)));
        applyInstallFilter();
    }

    public List<ServerSummary> getServersForInstallation(UUID installationId) {
        if (serverMap == null) return List.of();
        return serverMap.values().stream()
                .filter(s -> installationId.equals(s.getInstallationId()))
                .toList();
    }

    // ─── View switching ───────────────────────────────────────────────────────

    private void switchView() {
        if (profilePanel.isOpen()) profilePanel.close(toggleProfileButton);
        if (friendsPanel.isOpen()) friendsPanel.close(toggleFriendsButton);

        if (currentViewMode == ViewMode.MESSAGES) {
            if (dmNavDot != null) dmNavDot.setVisible(false);
            pageTitleLabel.setText("Messages");
            actionStrip.setVisible(false);
            actionStrip.setManaged(false);
            DirectMessagePage dmPage = getOrCreateDirectMessagePage();
            dmPage.reload();
            centerContainer.getChildren().setAll(dmPage);
            dmPage.getChatSection().notifyPageResumed();
            dmPage.onBecameVisible();
            return;
        }

        if (dmNavDot != null) dmNavDot.setVisible(App.isDmUnread());

        actionStrip.setVisible(true);
        actionStrip.setManaged(true);

        pageTitleLabel.setText(currentViewMode == ViewMode.SERVERS ? "Servers" : "Installations");

        boolean isServers = currentViewMode == ViewMode.SERVERS;
        joinBtn.setVisible(true);
        joinBtn.setManaged(true);
        joinBtn.setText(isServers ? "Join existing" : "Join installation");
        filterChipSlot.getChildren().setAll(isServers ? serverFilterChips : installFilterChips);

        if (actionStrip.getUserData() instanceof Object[] ud && ud[1] instanceof StackPane searchSlot) {
            searchSlot.getChildren().setAll(isServers ? serverSearchField : installSearchField);
        }

        updateCountLabel();

        if (hasError) {
            centerContainer.getChildren().setAll(errorNode);
            return;
        }

        showListView();
    }

    /**
     * The DM chat section currently on screen, or {@code null} if the messages
     * view isn't active. {@code DirectMessagePage} is embedded inside this
     * HomePage (not a top-level page), so callers like {@code App.showModal} reach
     * the DM scroll through here.
     */
    public DmChatSection getActiveDmChatSection() {
        if (currentViewMode == ViewMode.MESSAGES && App.getCachedDirectMessagePage() != null)
            return App.getCachedDirectMessagePage().getChatSection();
        return null;
    }

    private DirectMessagePage getOrCreateDirectMessagePage() {
        if (App.getCachedDirectMessagePage() == null) {
            App.setCachedDirectMessagePage(new DirectMessagePage());
        }
        return App.getCachedDirectMessagePage();
    }

    public void navigateToDm(UUID partnerId, String partnerUsername) {
        if (currentViewMode != ViewMode.MESSAGES) {
            currentViewMode = ViewMode.MESSAGES;
            setNavActive(navMessages);
            setNavInactive(navServers);
            setNavInactive(navInstallations);
            actionStrip.setVisible(false);
            actionStrip.setManaged(false);
            pageTitleLabel.setText("Messages");
            if (dmNavDot != null) dmNavDot.setVisible(false);
        }
        DirectMessagePage dmPage = getOrCreateDirectMessagePage();
        dmPage.reload();
        centerContainer.getChildren().setAll(dmPage);
        dmPage.openConversation(partnerId, partnerUsername);
        dmPage.onBecameVisible();
    }

    private void showError() {
        hasError = true;
        centerContainer.getChildren().setAll(errorNode);
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
                applyServerFilter();
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

    private Button iconBtn(Feather icon, String tooltip) {
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
