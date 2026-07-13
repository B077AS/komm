package komm.ui.sections;

import atlantafx.base.controls.CustomTextField;
import javafx.application.Platform;
import javafx.concurrent.ScheduledService;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
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
import komm.AppState;
import komm.api.InstallationConnection;
import komm.model.dto.response.MemberStatusPageResponse;
import komm.model.dto.response.UserStatusDto;
import komm.model.dto.summary.MainUserSummary.UserStatus;
import komm.model.dto.summary.ServerSummary;
import komm.ui.avatar.AvatarCache;
import komm.ui.avatar.AvatarColor;
import komm.ui.profile.UserProfilePopup;
import komm.websocket.messages.WsMessageType;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;

import javafx.animation.PauseTransition;
import javafx.util.Duration;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
public class ServerMembersSection extends VBox {

    public static final double SECTION_WIDTH = 240;
    private static final int PAGE_SIZE = 50;
    private static final double AVATAR_SIZE = 32;
    private static final Duration STATUS_RECONCILE_INTERVAL = Duration.minutes(3);
    private static final int STATUS_BATCH_CHUNK_SIZE = 200;

    private final ServerSummary server;

    private final Map<UUID, MemberEntry> memberMap = new ConcurrentHashMap<>();

    // Online members are loaded all at once; offline are paginated
    private int onlineCount = 0;
    private int offlinePage = 0;
    private long offlineTotal = 0;
    private boolean offlineAllLoaded = false;
    private boolean isLoading = false;

    private javafx.beans.value.ChangeListener<UserStatus> ownStatusListener;
    private javafx.beans.value.ChangeListener<Number> selfAvatarRevisionListener;

    private final VBox membersListBox = new VBox(2);
    private final ScrollPane scrollPane = new ScrollPane();
    private CustomTextField searchField;
    private List<MemberEntry> searchResults = null;
    private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(350));

    // ── Result records ────────────────────────────────────────────────────────

    private record OnlineLoadResult(List<UserStatusDto> members) {}
    private record OfflinePageResult(int page, MemberStatusPageResponse resp) {}

    // ── Services ──────────────────────────────────────────────────────────────

    private final Service<OnlineLoadResult> loadOnlineService = new Service<>() {
        @Override
        protected Task<OnlineLoadResult> createTask() {
            return new Task<>() {
                @Override
                protected OnlineLoadResult call() throws Exception {
                    List<UserStatusDto> members = App.getServices().hub()
                            .getHubModerationService()
                            .getOnlineServerMembers(server.getServerId());
                    if (members == null) members = List.of();
                    if (!members.isEmpty()) {
                        List<UUID> ids = members.stream().map(UserStatusDto::getUserId).toList();
                        App.getAvatarCache().resolveAll(ids).join();
                    }
                    return new OnlineLoadResult(members);
                }
            };
        }
    };

    private int pendingOfflinePage = 0;
    private int pendingOfflineSize = PAGE_SIZE;

    private final Service<OfflinePageResult> loadOfflineService = new Service<>() {
        @Override
        protected Task<OfflinePageResult> createTask() {
            final int page = pendingOfflinePage;
            final int size = pendingOfflineSize;
            return new Task<>() {
                @Override
                protected OfflinePageResult call() throws Exception {
                    MemberStatusPageResponse resp = App.getServices().hub()
                            .getHubModerationService()
                            .getOfflineServerMembers(server.getServerId(), page, size);
                    if (resp != null && resp.getMembers() != null && !resp.getMembers().isEmpty()) {
                        List<UUID> ids = resp.getMembers().stream().map(UserStatusDto::getUserId).toList();
                        App.getAvatarCache().resolveAll(ids).join();
                    }
                    return new OfflinePageResult(page, resp);
                }
            };
        }
    };

    // Periodic safety-net reconciliation: WS status pushes can be missed (dropped
    // frame, reconnect gap, handler swap), so re-fetch the status of everyone
    // currently loaded in memberMap on a timer instead of trusting WS alone.
    private final ScheduledService<List<UserStatusDto>> statusReconcileService = new ScheduledService<>() {
        @Override
        protected Task<List<UserStatusDto>> createTask() {
            List<UUID> ids = List.copyOf(memberMap.keySet());
            return new Task<>() {
                @Override
                protected List<UserStatusDto> call() throws Exception {
                    if (ids.isEmpty()) return List.of();
                    List<UserStatusDto> merged = new ArrayList<>();
                    for (int i = 0; i < ids.size(); i += STATUS_BATCH_CHUNK_SIZE) {
                        List<UUID> chunk = ids.subList(i, Math.min(i + STATUS_BATCH_CHUNK_SIZE, ids.size()));
                        List<UserStatusDto> result = App.getServices().hub()
                                .getUserService().getUsersBatch(chunk);
                        if (result != null) merged.addAll(result);
                    }
                    return merged;
                }
            };
        }
    };

    // ── Inner model ───────────────────────────────────────────────────────────

    private static class MemberEntry {
        UUID userId;
        UserStatus status;

        MemberEntry(UUID userId, UserStatus status) {
            this.userId = userId;
            this.status = status;
        }
    }

    public ServerMembersSection(ServerSummary server) {
        this.server = server;

        setPrefWidth(SECTION_WIDTH);
        setMaxWidth(SECTION_WIDTH);
        setMinWidth(SECTION_WIDTH);
        setStyle("-fx-background-color: -color-bg-subtle;" +
                "-fx-border-color: transparent transparent transparent -color-border-default;" +
                "-fx-border-width: 0 0 0 1px;");

        loadOnlineService.setOnSucceeded(e -> handleOnlineLoaded(loadOnlineService.getValue()));
        loadOnlineService.setOnFailed(e -> {
            log.error("Failed to load online members: {}",
                    loadOnlineService.getException() != null ? loadOnlineService.getException().getMessage() : "unknown");
            isLoading = false;
            renderList();
        });

        loadOfflineService.setOnSucceeded(e -> handleOfflinePageLoaded(loadOfflineService.getValue()));
        loadOfflineService.setOnFailed(e -> {
            log.error("Failed to load offline members page {}: {}",
                    pendingOfflinePage,
                    loadOfflineService.getException() != null ? loadOfflineService.getException().getMessage() : "unknown");
            isLoading = false;
        });

        statusReconcileService.setPeriod(STATUS_RECONCILE_INTERVAL);
        statusReconcileService.setDelay(STATUS_RECONCILE_INTERVAL);
        statusReconcileService.setRestartOnFailure(true);
        statusReconcileService.setOnSucceeded(e -> {
            List<UserStatusDto> results = statusReconcileService.getValue();
            if (results == null || results.isEmpty()) return;
            boolean changed = false;
            for (UserStatusDto dto : results) {
                MemberEntry entry = memberMap.get(dto.getUserId());
                if (entry != null && entry.status != dto.getStatus()) {
                    entry.status = dto.getStatus();
                    changed = true;
                }
            }
            if (changed) renderList();
        });
        statusReconcileService.setOnFailed(e -> log.warn("Status reconcile failed: {}",
                statusReconcileService.getException() != null
                        ? statusReconcileService.getException().getMessage() : "unknown"));

        buildUi();

        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) onAttached();
            else onDetached();
        });
    }

    private void buildUi() {
        HBox searchBar = new HBox();
        searchBar.setMinHeight(48);
        searchBar.setMaxHeight(48);
        searchBar.setAlignment(Pos.CENTER);
        searchBar.setPadding(new Insets(0, 10, 0, 10));
        searchBar.setStyle("-fx-border-color: transparent transparent -color-border-muted transparent;" +
                "-fx-border-width: 0 0 1px 0;");

        searchField = new CustomTextField();
        searchField.setPromptText("Search members");
        searchField.setRight(new FontIcon(MaterialDesignM.MAGNIFY));
        searchField.setFocusTraversable(false);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((obs, old, nv) -> {
            String q = nv == null ? "" : nv.trim();
            searchDebounce.setOnFinished(e -> handleSearchQuery(q));
            searchDebounce.playFromStart();
        });
        searchBar.getChildren().add(searchField);

        membersListBox.setPadding(new Insets(8, 8, 8, 8));
        membersListBox.setFillWidth(true);

        scrollPane.setContent(membersListBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        scrollPane.vvalueProperty().addListener((obs, old, nv) -> {
            if (nv.doubleValue() > 0.8 && !isLoading && !offlineAllLoaded) loadNextOfflinePage();
        });

        getChildren().addAll(searchBar, scrollPane);
    }

    private void onAttached() {
        registerWsHandlers();
        ownStatusListener = (obs, oldVal, newVal) -> {
            if (newVal == null || App.getUser() == null) return;
            UUID myId = App.getUser().getUserId();
            MemberEntry entry = memberMap.get(myId);
            if (entry != null) {
                entry.status = newVal;
                renderList();
            }
        };
        AppState.userStatusProperty().addListener(ownStatusListener);
        selfAvatarRevisionListener = (obs, oldVal, newVal) -> Platform.runLater(this::renderList);
        AppState.selfAvatarRevisionProperty().addListener(selfAvatarRevisionListener);
        if (memberMap.isEmpty()) reload();
        else renderList();
        statusReconcileService.restart();
    }

    private void onDetached() {
        unregisterWsHandlers();
        if (ownStatusListener != null) {
            AppState.userStatusProperty().removeListener(ownStatusListener);
            ownStatusListener = null;
        }
        if (selfAvatarRevisionListener != null) {
            AppState.selfAvatarRevisionProperty().removeListener(selfAvatarRevisionListener);
            selfAvatarRevisionListener = null;
        }
        if (loadOnlineService.isRunning()) loadOnlineService.cancel();
        if (loadOfflineService.isRunning()) loadOfflineService.cancel();
        statusReconcileService.cancel();
    }

    private void registerWsHandlers() {
        try {
            InstallationConnection conn = App.getServices().installation();
            conn.getWsClient().register(WsMessageType.MEMBER_JOINED, payload -> {
                try {
                    UUID userId = UUID.fromString(payload.get("userId").getAsString());
                    App.getAvatarCache().resolve(userId);
                    CompletableFuture.runAsync(() -> {
                        try {
                            List<UserStatusDto> result = App.getServices().hub()
                                    .getUserService().getUsersBatch(List.of(userId));
                            UserStatus status = (result != null && !result.isEmpty())
                                    ? result.get(0).getStatus() : UserStatus.OFFLINE;
                            memberMap.put(userId, new MemberEntry(userId, status));
                        } catch (Exception ex) {
                            memberMap.put(userId, new MemberEntry(userId, UserStatus.OFFLINE));
                        }
                        Platform.runLater(this::renderList);
                    });
                } catch (Exception ex) {
                    log.warn("MEMBER_JOINED parse error: {}", ex.getMessage());
                }
            });
            conn.getWsClient().register(WsMessageType.USER_BANNED, payload -> {
                try {
                    memberMap.remove(UUID.fromString(payload.get("userId").getAsString()));
                    Platform.runLater(this::renderList);
                } catch (Exception ex) {
                    log.warn("USER_BANNED parse error: {}", ex.getMessage());
                }
            });
            conn.getWsClient().register(WsMessageType.USER_KICKED, payload -> {
                try {
                    memberMap.remove(UUID.fromString(payload.get("userId").getAsString()));
                    Platform.runLater(this::renderList);
                } catch (Exception ex) {
                    log.warn("USER_KICKED parse error: {}", ex.getMessage());
                }
            });
            conn.getWsClient().register(WsMessageType.MEMBER_LEFT, payload -> {
                try {
                    memberMap.remove(UUID.fromString(payload.get("userId").getAsString()));
                    Platform.runLater(this::renderList);
                } catch (Exception ex) {
                    log.warn("MEMBER_LEFT parse error: {}", ex.getMessage());
                }
            });
        } catch (IllegalStateException ignored) {
        }

        App.getHubWsClient().register(WsMessageType.MEMBER_STATUS_UPDATED, payload -> {
            try {
                UUID userId = UUID.fromString(payload.get("userId").getAsString());
                UserStatus status = UserStatus.valueOf(payload.get("status").getAsString());
                MemberEntry entry = memberMap.get(userId);
                if (entry != null) {
                    entry.status = status;
                    Platform.runLater(this::renderList);
                }
            } catch (Exception ex) {
                log.warn("MEMBER_STATUS_UPDATED parse error: {}", ex.getMessage());
            }
        });
    }

    private void unregisterWsHandlers() {
        try {
            InstallationConnection conn = App.getServices().installation();
            conn.getWsClient().unregister(WsMessageType.MEMBER_JOINED);
            conn.getWsClient().unregister(WsMessageType.USER_BANNED);
            conn.getWsClient().unregister(WsMessageType.USER_KICKED);
            conn.getWsClient().unregister(WsMessageType.MEMBER_LEFT);
        } catch (IllegalStateException ignored) {
        }
        App.getHubWsClient().unregister(WsMessageType.MEMBER_STATUS_UPDATED);
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    public void reload() {
        memberMap.clear();
        onlineCount = 0;
        offlinePage = 0;
        offlineTotal = 0;
        offlineAllLoaded = false;
        isLoading = false;
        membersListBox.getChildren().clear();
        if (loadOnlineService.isRunning()) loadOnlineService.cancel();
        if (loadOfflineService.isRunning()) loadOfflineService.cancel();
        loadOnline();
    }

    private void loadOnline() {
        if (isLoading) return;
        isLoading = true;
        loadOnlineService.restart();
    }

    private void handleOnlineLoaded(OnlineLoadResult result) {
        List<UserStatusDto> members = result.members();
        onlineCount = members.size();
        for (UserStatusDto m : members) {
            memberMap.put(m.getUserId(), new MemberEntry(m.getUserId(), m.getStatus()));
        }
        isLoading = false;
        // If online < PAGE_SIZE, immediately start loading offline to fill up to PAGE_SIZE
        if (onlineCount < PAGE_SIZE && !offlineAllLoaded) {
            loadOfflineMembers(0, PAGE_SIZE - onlineCount);
        } else {
            renderList();
        }
    }

    private void loadOfflineMembers(int page, int size) {
        if (isLoading) return;
        isLoading = true;
        pendingOfflinePage = page;
        pendingOfflineSize = Math.max(1, size);
        loadOfflineService.restart();
    }

    private void loadNextOfflinePage() {
        loadOfflineMembers(offlinePage, PAGE_SIZE);
    }

    private void handleOfflinePageLoaded(OfflinePageResult result) {
        if (result.resp() != null && result.resp().getMembers() != null) {
            for (UserStatusDto m : result.resp().getMembers()) {
                memberMap.put(m.getUserId(), new MemberEntry(m.getUserId(), m.getStatus()));
            }
            offlineTotal = result.resp().getTotal();
            offlinePage = result.page() + 1;
            // Next scroll-triggered load always uses full PAGE_SIZE
            pendingOfflineSize = PAGE_SIZE;
        }
        long offlineLoaded = (long) memberMap.size() - onlineCount;
        offlineAllLoaded = result.resp() == null
                || result.resp().getMembers() == null
                || result.resp().getMembers().isEmpty()
                || offlineLoaded >= offlineTotal;
        isLoading = false;
        renderList();
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private void handleSearchQuery(String query) {
        if (query.isEmpty()) {
            searchResults = null;
            renderList();
            return;
        }
        UUID serverId = server.getServerId();
        Service<List<UserStatusDto>> svc = new Service<>() {
            @Override
            protected Task<List<UserStatusDto>> createTask() {
                return new Task<>() {
                    @Override
                    protected List<UserStatusDto> call() throws Exception {
                        List<UserStatusDto> results = App.getServices().hub()
                                .getHubModerationService().searchMembers(serverId, query);
                        if (results != null && !results.isEmpty()) {
                            App.getAvatarCache().resolveAll(
                                    results.stream().map(UserStatusDto::getUserId).toList()).join();
                        }
                        return results != null ? results : List.of();
                    }
                };
            }
        };
        svc.setOnSucceeded(e -> {
            searchResults = svc.getValue().stream()
                    .map(dto -> new MemberEntry(dto.getUserId(), dto.getStatus()))
                    .toList();
            renderList();
        });
        svc.setOnFailed(e -> {
            searchResults = null;
            renderList();
        });
        svc.start();
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private void renderList() {
        boolean inSearch = searchResults != null;
        List<MemberEntry> filtered;

        if (inSearch) {
            filtered = searchResults;
        } else {
            String query = searchField != null ? searchField.getText().toLowerCase(Locale.ROOT).trim() : "";
            filtered = memberMap.values().stream()
                    .filter(e -> {
                        if (query.isEmpty()) return true;
                        String name = App.getAvatarCache().getUsername(e.userId);
                        return name != null && name.toLowerCase(Locale.ROOT).contains(query);
                    })
                    .sorted(Comparator.comparing(e -> {
                        String name = App.getAvatarCache().getUsername(e.userId);
                        return name != null ? name.toLowerCase(Locale.ROOT) : "";
                    }))
                    .toList();
        }

        Map<UserStatus, List<MemberEntry>> grouped = filtered.stream()
                .collect(Collectors.groupingBy(e -> normalizeGroup(e.status)));

        List<UserStatus> groupOrder = List.of(
                UserStatus.ONLINE, UserStatus.AWAY, UserStatus.DO_NOT_DISTURB, UserStatus.OFFLINE);

        List<javafx.scene.Node> nodes = new ArrayList<>();
        for (UserStatus group : groupOrder) {
            List<MemberEntry> members = grouped.getOrDefault(group, List.of());
            if (members.isEmpty()) continue;
            nodes.add(buildGroupHeader(group, members.size()));
            for (MemberEntry entry : members) nodes.add(buildMemberRow(entry));
        }

        if (!inSearch && !offlineAllLoaded && !isLoading) {
            Label hint = new Label("Scroll to load more…");
            hint.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");
            hint.setPadding(new Insets(6, 8, 4, 8));
            nodes.add(hint);
        }

        membersListBox.getChildren().setAll(nodes);
    }

    private UserStatus normalizeGroup(UserStatus status) {
        if (status == null) return UserStatus.OFFLINE;
        return switch (status) {
            case ONLINE -> UserStatus.ONLINE;
            case AWAY -> UserStatus.AWAY;
            case DO_NOT_DISTURB -> UserStatus.DO_NOT_DISTURB;
            default -> UserStatus.OFFLINE;
        };
    }

    private Label buildGroupHeader(UserStatus group, int count) {
        String text = switch (group) {
            case ONLINE -> "ONLINE — " + count;
            case AWAY -> "AWAY — " + count;
            case DO_NOT_DISTURB -> "DO NOT DISTURB — " + count;
            default -> "OFFLINE — " + count;
        };
        Label lbl = new Label(text);
        lbl.setPadding(new Insets(10, 8, 4, 8));
        lbl.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: -color-fg-muted;");
        return lbl;
    }

    private HBox buildMemberRow(MemberEntry entry) {
        AvatarCache.CachedUser cached = App.getAvatarCache().getIfPresent(entry.userId);
        String displayName = cached != null && cached.username() != null ? cached.username() : "…";

        StackPane avatarWithDot = buildAvatarWithDot(entry, cached);
        StackPane avatarPane = (StackPane) avatarWithDot.getChildren().get(0);

        Label nameLbl = new Label(displayName);
        nameLbl.setStyle("-fx-font-size: 13px;");
        HBox.setHgrow(nameLbl, Priority.ALWAYS);

        HBox row = new HBox(10);
        row.setPadding(new Insets(5, 8, 5, 8));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("friend-item");
        row.getChildren().addAll(avatarWithDot, nameLbl);

        // resolve() is a cache-hit after resolveAll(); runs fast
        App.getAvatarCache().resolve(entry.userId).thenAccept(cu -> {
            if (cu == null) return;
            javafx.application.Platform.runLater(() -> {
                nameLbl.setText(cu.username() != null ? cu.username() : "…");
                fillAvatar(avatarPane, cu, AVATAR_SIZE);
            });
        });

        UUID userId = entry.userId;
        UUID serverId = server.getServerId();
        row.setStyle("-fx-cursor: hand;");
        row.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                UserProfilePopup.getInstance().show(
                        App.getStackPane().getScene().getWindow(),
                        e.getScreenX(), e.getScreenY(),
                        userId, serverId, () -> {});
                e.consume();
            }
        });

        return row;
    }

    private StackPane buildAvatarWithDot(MemberEntry entry, AvatarCache.CachedUser cached) {
        double size = AVATAR_SIZE;

        StackPane avatarPane = new StackPane();
        avatarPane.setPrefSize(size, size);
        avatarPane.setMinSize(size, size);
        avatarPane.setMaxSize(size, size);
        fillAvatar(avatarPane, cached, size);

        UserStatus effectiveStatus = entry.status != null ? entry.status : UserStatus.OFFLINE;
        Circle statusDot = new Circle(4.5);
        statusDot.getStyleClass().addAll("status-dot", effectiveStatus.getCssClass());
        statusDot.setStyle("-fx-stroke: -color-bg-subtle; -fx-stroke-width: 2;");

        StackPane container = new StackPane(avatarPane, statusDot);
        container.setMinSize(size + 4, size + 4);
        container.setMaxSize(size + 4, size + 4);
        StackPane.setAlignment(statusDot, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(statusDot, new Insets(0, 0, 1, 0));

        return container;
    }

    private void fillAvatar(StackPane pane, AvatarCache.CachedUser cu, double size) {
        pane.getChildren().clear();
        if (cu != null && cu.avatar() != null && cu.avatar().length > 0) {
            try {
                Image img = new Image(new ByteArrayInputStream(cu.avatar()), size, size, true, true);
                if (!img.isError()) {
                    ImageView iv = new ImageView(img);
                    iv.setFitWidth(size);
                    iv.setFitHeight(size);
                    iv.setPreserveRatio(false);
                    iv.setClip(new Circle(size / 2, size / 2, size / 2));
                    pane.getChildren().add(iv);
                    pane.setStyle("-fx-background-color: transparent; -fx-background-radius: " + (size / 2) + "px;");
                    return;
                }
            } catch (Exception ignored) {}
        }
        String name = cu != null ? cu.username() : null;
        String letter = (name != null && !name.isEmpty()) ? String.valueOf(name.charAt(0)).toUpperCase() : "?";
        Text t = new Text(letter);
        t.setFill(Color.WHITE);
        t.setFont(Font.font("System", FontWeight.BOLD, size / 2.5));
        pane.getChildren().add(t);
        pane.setStyle("-fx-background-color: " + AvatarColor.forName(name) + ";" +
                "-fx-background-radius: " + (size / 2) + "px;");
    }
}
