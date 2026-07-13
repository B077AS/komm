package komm.ui.modals.createchannel;

import atlantafx.base.theme.Styles;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.ImagePattern;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import komm.App;
import komm.model.dto.response.MemberPageResponse;
import komm.model.dto.response.UserStatusDto;
import komm.model.dto.summary.ServerMemberSummary;
import komm.model.permissions.Permission;
import komm.ui.avatar.AvatarCache;
import komm.ui.avatar.AvatarColor;
import komm.ui.customnodes.CustomNotification;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import java.io.ByteArrayInputStream;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "User Exceptions" tab: per-user three-state permission overrides that take precedence over
 * role permissions for this channel. Edit mode only — the nav item stays disabled until the
 * channel actually exists.
 */
public class UserExceptionsChannelTab implements ChannelSettingsTab {

    private record PermOverride(EnumSet<Permission> allow, EnumSet<Permission> deny) {}

    private final ChannelSettingsContext ctx;

    private VBox userExcListView;
    private VBox userExcDetailView;
    private VBox excListBox;
    private VBox availableMembersBox;
    private ScrollPane availableMembersScroll;
    private VBox userExcDetailContent;
    private Label userExcDetailLabel;
    private TextField availableMembersFilterField;
    private UUID selectedExceptionUserId;

    private final Map<UUID, PermOverride> pendingUserExceptions = new LinkedHashMap<>();
    private final Map<UUID, PermOverride> origUserExceptions = new LinkedHashMap<>();
    private final Map<UUID, String> userExceptionNames = new LinkedHashMap<>();

    private final Map<UUID, String> serverMembersCache = new LinkedHashMap<>();
    private boolean membersLoadStarted = false;
    private int membersPage = 0;
    private long membersTotal = 0;
    private boolean membersAllLoaded = false;
    private boolean membersLoading = false;
    private int pendingMembersPage = 0;
    private Map<UUID, String> membersSearchResults = null;
    private final PauseTransition membersSearchDebounce = new PauseTransition(Duration.millis(350));

    private boolean excLoaded = false;
    private boolean userExcDirty = false;
    private final VBox pane;

    private final Service<MemberPageResponse> loadMembersPageService = new Service<>() {
        @Override
        protected Task<MemberPageResponse> createTask() {
            final int page = pendingMembersPage;
            return new Task<>() {
                @Override
                protected MemberPageResponse call() throws Exception {
                    MemberPageResponse resp = App.getServices().hub().getHubModerationService()
                            .getServerMembersPaged(ctx.server().getServerId(), page, 50);
                    if (resp != null && resp.getMembers() != null && !resp.getMembers().isEmpty()) {
                        List<UUID> ids = resp.getMembers().stream()
                                .map(ServerMemberSummary::getUserId)
                                .toList();
                        App.getAvatarCache().resolveAll(ids).join();
                    }
                    return resp;
                }
            };
        }
    };

    private final Service<Map<UUID, PermOverride>> loadUserExcService = new Service<>() {
        @Override
        protected Task<Map<UUID, PermOverride>> createTask() {
            return new Task<>() {
                @Override
                protected Map<UUID, PermOverride> call() throws Exception {
                    var svc = App.getServices().installation().getChannelPermissionService();
                    var exceptions = svc.getChannelUserPermissions(ctx.editChannel().getChannelId());
                    Map<UUID, PermOverride> loaded = new LinkedHashMap<>();
                    if (exceptions != null) {
                        for (var exc : exceptions) {
                            loaded.put(exc.getUserId(), new PermOverride(
                                    Permission.fromNames(exc.getAllowPermissions()),
                                    Permission.fromNames(exc.getDenyPermissions())));
                        }
                    }
                    return loaded;
                }
            };
        }
    };

    private Map<UUID, PermOverride> pendingExcSnapshot;
    private Map<UUID, PermOverride> origExcSnapshot;

    private final Service<Void> saveUserExcService = new Service<>() {
        @Override
        protected Task<Void> createTask() {
            final var snap = pendingExcSnapshot;
            final var origSnap = origExcSnapshot;
            return new Task<>() {
                @Override
                protected Void call() throws Exception {
                    var svc = App.getServices().installation().getChannelPermissionService();
                    UUID channelId = ctx.editChannel().getChannelId();
                    for (Map.Entry<UUID, PermOverride> entry : snap.entrySet()) {
                        UUID uid = entry.getKey();
                        PermOverride cur = entry.getValue();
                        PermOverride orig = origSnap.get(uid);
                        if (orig != null && cur.allow().equals(orig.allow()) && cur.deny().equals(orig.deny())) continue;
                        if (cur.allow().isEmpty() && cur.deny().isEmpty()) {
                            try {
                                svc.deleteChannelUserPermission(channelId, uid);
                            } catch (Exception ignored) {
                            }
                        } else {
                            svc.upsertChannelUserPermission(channelId, uid, cur.allow(), cur.deny());
                        }
                    }
                    for (UUID uid : origSnap.keySet()) {
                        if (!snap.containsKey(uid)) {
                            try {
                                svc.deleteChannelUserPermission(channelId, uid);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    return null;
                }
            };
        }
    };

    public UserExceptionsChannelTab(ChannelSettingsContext ctx) {
        this.ctx = ctx;
        this.pane = buildPane();

        if (ctx.isEditMode()) {
            saveUserExcService.runningProperty().addListener((obs, was, isRunning) -> {
                ctx.setSaving(isRunning);
                ctx.refreshSaveButton();
            });
            saveUserExcService.setOnSucceeded(e -> {
                origUserExceptions.clear();
                pendingExcSnapshot.forEach((k, v) -> origUserExceptions.put(k,
                        new PermOverride(EnumSet.copyOf(v.allow()), EnumSet.copyOf(v.deny()))));
                userExcDirty = false;
                ctx.refreshSaveButton();
                new CustomNotification("Exceptions Saved", "Channel user exceptions have been updated.",
                        new FontIcon(MaterialDesignC.CHECK_CIRCLE_OUTLINE)).showNotification();
            });
            saveUserExcService.setOnFailed(e -> ChannelSettingsUi.showSaveError(saveUserExcService.getException()));
        }
    }

    // ── ChannelSettingsTab ────────────────────────────────────────────────────────

    @Override public String name() { return "User Exceptions"; }
    @Override public String description() { return "Grant or restrict permissions for specific members"; }
    @Override public FontIcon icon() { return new FontIcon(MaterialDesignS.SHIELD_ACCOUNT); }
    @Override public Node getPane() { return pane; }
    @Override public boolean participatesInSave() { return ctx.isEditMode(); }
    @Override public boolean isDirty() { return userExcDirty; }
    @Override public boolean isBusy() { return saveUserExcService.isRunning(); }
    @Override public String saveButtonText() { return "Save Exceptions"; }
    @Override public void save() { handleSaveUserExceptions(); }

    @Override
    public void onShown() {
        if (!ctx.isEditMode()) return;
        if (!excLoaded) {
            excLoaded = true;
            loadUserExceptions();
        }
        loadServerMembersCache();
    }

    // ── Pane ─────────────────────────────────────────────────────────────────────

    private VBox buildPane() {
        // ── Left column: members without exceptions ───────────────────────────
        TextField searchField = new TextField();
        searchField.setPromptText("Search…");
        searchField.setMaxWidth(Double.MAX_VALUE);
        this.availableMembersFilterField = searchField;
        searchField.textProperty().addListener((obs, o, n) -> {
            String q = n == null ? "" : n.trim();
            membersSearchDebounce.setOnFinished(e -> handleMembersSearch(q));
            membersSearchDebounce.playFromStart();
        });

        availableMembersBox = new VBox(2);

        availableMembersScroll = new ScrollPane(availableMembersBox);
        availableMembersScroll.setFitToWidth(true);
        availableMembersScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        availableMembersScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(availableMembersScroll, Priority.ALWAYS);
        availableMembersScroll.vvalueProperty().addListener((obs, ov, nv) -> {
            if (nv.doubleValue() > 0.8 && !membersLoading && !membersAllLoaded)
                loadNextMembersPage();
        });

        VBox leftCol = new VBox(10,
                new VBox(6, ChannelSettingsUi.sectionLabel("MEMBERS"), searchField),
                availableMembersScroll);
        leftCol.setPadding(new Insets(20, 12, 16, 20));
        VBox.setVgrow(availableMembersScroll, Priority.ALWAYS);
        VBox.setVgrow(leftCol, Priority.ALWAYS);

        // ── Right column: exception users ─────────────────────────────────────
        excListBox = new VBox(2);

        ScrollPane rightScroll = new ScrollPane(excListBox);
        rightScroll.setFitToWidth(true);
        rightScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        rightScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(rightScroll, Priority.ALWAYS);

        VBox rightCol = new VBox(10,
                ChannelSettingsUi.sectionLabel("EXCEPTIONS"),
                rightScroll);
        rightCol.setPadding(new Insets(20, 20, 16, 12));
        VBox.setVgrow(rightScroll, Priority.ALWAYS);
        VBox.setVgrow(rightCol, Priority.ALWAYS);

        rightCol.setStyle(
                "-fx-border-color: transparent transparent transparent -color-border-default;" +
                "-fx-border-width: 0 0 0 1;");

        ColumnConstraints cc = new ColumnConstraints();
        cc.setPercentWidth(50);
        cc.setHgrow(Priority.ALWAYS);
        GridPane twoColumns = new GridPane();
        twoColumns.getColumnConstraints().addAll(cc, new ColumnConstraints() {{ setPercentWidth(50); setHgrow(Priority.ALWAYS); }});
        twoColumns.setMaxWidth(Double.MAX_VALUE);
        twoColumns.add(leftCol, 0, 0);
        twoColumns.add(rightCol, 1, 0);
        GridPane.setVgrow(leftCol, Priority.ALWAYS);
        GridPane.setVgrow(rightCol, Priority.ALWAYS);
        VBox.setVgrow(twoColumns, Priority.ALWAYS);

        userExcListView = new VBox(twoColumns);
        userExcListView.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(twoColumns, Priority.ALWAYS);
        VBox.setVgrow(userExcListView, Priority.ALWAYS);

        // ── Detail view: permissions for selected user ────────────────────────
        Button backBtn = new Button(null, new FontIcon(Feather.ARROW_LEFT));
        backBtn.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE, Styles.SMALL);
        backBtn.setFocusTraversable(false);
        backBtn.setOnAction(e -> showExcListView());

        userExcDetailLabel = new Label();
        userExcDetailLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");

        HBox detailHeader = new HBox(8, backBtn, userExcDetailLabel);
        detailHeader.setAlignment(Pos.CENTER_LEFT);
        detailHeader.setPadding(new Insets(14, 20, 10, 14));
        detailHeader.setStyle(
                "-fx-border-color: transparent transparent -color-border-default transparent;" +
                "-fx-border-width: 0 0 1 0;");

        userExcDetailContent = new VBox(2);
        userExcDetailContent.setPadding(new Insets(12, 20, 12, 20));

        ScrollPane detailScroll = new ScrollPane(userExcDetailContent);
        detailScroll.setFitToWidth(true);
        detailScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        detailScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(detailScroll, Priority.ALWAYS);

        userExcDetailView = new VBox(0, detailHeader, detailScroll);
        userExcDetailView.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(detailScroll, Priority.ALWAYS);
        VBox.setVgrow(userExcDetailView, Priority.ALWAYS);
        userExcDetailView.setVisible(false);
        userExcDetailView.setManaged(false);

        // ── Stack ─────────────────────────────────────────────────────────────
        StackPane stack = new StackPane(userExcListView, userExcDetailView);
        stack.setAlignment(Pos.TOP_LEFT);
        VBox.setVgrow(stack, Priority.ALWAYS);

        VBox pane = new VBox(stack);
        pane.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(stack, Priority.ALWAYS);
        VBox.setVgrow(pane, Priority.ALWAYS);

        rebuildExcList();
        rebuildAvailableList("");
        return pane;
    }

    private void showExcListView() {
        selectedExceptionUserId = null;
        userExcDetailView.setVisible(false);
        userExcDetailView.setManaged(false);
        userExcListView.setVisible(true);
        userExcListView.setManaged(true);
    }

    private void showExcDetailView(UUID uid, String name) {
        selectedExceptionUserId = uid;
        userExceptionNames.putIfAbsent(uid, name); // cache name for lazy-add
        userExcDetailLabel.setText(name);
        userExcListView.setVisible(false);
        userExcListView.setManaged(false);
        userExcDetailView.setVisible(true);
        userExcDetailView.setManaged(true);
        rebuildDetailContent();
    }

    // ── Server member loading ─────────────────────────────────────────────────

    private void loadServerMembersCache() {
        if (membersLoadStarted) return;
        membersLoadStarted = true;

        loadMembersPageService.setOnSucceeded(e -> handleMembersPageLoaded(loadMembersPageService.getValue()));
        loadMembersPageService.setOnFailed(e -> {
            membersLoading = false;
            rebuildExcList();
            rebuildAvailableList(availableMembersFilterField != null
                    ? availableMembersFilterField.getText() : "");
        });
        membersPage = 0;
        membersTotal = 0;
        membersAllLoaded = false;
        loadNextMembersPage();
    }

    private void loadNextMembersPage() {
        if (membersLoading || membersAllLoaded) return;
        membersLoading = true;
        pendingMembersPage = membersPage;
        loadMembersPageService.restart();
    }

    private void handleMembersPageLoaded(MemberPageResponse resp) {
        if (resp == null || resp.getMembers() == null || resp.getMembers().isEmpty()) {
            membersAllLoaded = true;
            membersLoading = false;
            rebuildExcList();
            rebuildAvailableList(availableMembersFilterField != null
                    ? availableMembersFilterField.getText() : "");
            return;
        }
        membersTotal = resp.getTotal();
        for (ServerMemberSummary m : resp.getMembers()) {
            UUID uid = m.getUserId();
            AvatarCache.CachedUser cu = App.getAvatarCache().getIfPresent(uid);
            String name = cu != null && cu.username() != null
                    ? cu.username() : uid.toString().substring(0, 8);
            serverMembersCache.put(uid, name);
            if (userExceptionNames.containsKey(uid)) userExceptionNames.put(uid, name);
        }
        membersPage = resp.getPage() + 1;
        membersAllLoaded = serverMembersCache.size() >= membersTotal;
        membersLoading = false;
        rebuildExcList();
        rebuildAvailableList(availableMembersFilterField != null
                ? availableMembersFilterField.getText() : "");
    }

    private void loadUserExceptions() {
        loadUserExcService.setOnSucceeded(e -> {
            Map<UUID, PermOverride> loaded = loadUserExcService.getValue();
            pendingUserExceptions.clear();
            origUserExceptions.clear();
            userExceptionNames.clear();

            loaded.forEach((uid, o) -> {
                pendingUserExceptions.put(uid, new PermOverride(EnumSet.copyOf(o.allow()), EnumSet.copyOf(o.deny())));
                origUserExceptions.put(uid, new PermOverride(EnumSet.copyOf(o.allow()), EnumSet.copyOf(o.deny())));
                // Seed display names from avatar cache; server-member load will fill in the rest
                String name = null;
                var cached = App.getAvatarCache().getIfPresent(uid);
                if (cached != null && cached.username() != null) name = cached.username();
                userExceptionNames.put(uid, name != null ? name : uid.toString());
            });

            rebuildExcList();
            rebuildAvailableList(availableMembersFilterField != null ? availableMembersFilterField.getText() : "");
        });
        loadUserExcService.setOnFailed(e -> loadUserExcService.getException().printStackTrace());
        loadUserExcService.start();
    }

    // ── List rendering ─────────────────────────────────────────────────────────

    private void rebuildExcList() {
        if (excListBox == null) return;
        excListBox.getChildren().clear();

        if (pendingUserExceptions.isEmpty()) {
            Label none = new Label("No exceptions yet.");
            none.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted; -fx-padding: 2 0 0 4;");
            excListBox.getChildren().add(none);
            return;
        }

        for (UUID uid : pendingUserExceptions.keySet()) {
            String name = userExceptionNames.getOrDefault(uid, uid.toString().substring(0, 8));

            StackPane avatarBg = buildAvatarBg(28);
            AvatarCache.CachedUser cached = App.getAvatarCache().getIfPresent(uid);
            if (cached != null) fillAvatarBg(avatarBg, cached, 28);

            Button trashBtn = new Button(null, new FontIcon(MaterialDesignD.DELETE_OUTLINE));
            trashBtn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT, Styles.SMALL);
            trashBtn.setFocusTraversable(false);
            trashBtn.setTooltip(new Tooltip("Remove exception"));
            trashBtn.setOnAction(e -> {
                e.consume();
                if (uid.equals(selectedExceptionUserId)) {
                    selectedExceptionUserId = null;
                    showExcListView();
                }
                pendingUserExceptions.remove(uid);
                userExceptionNames.remove(uid);
                Platform.runLater(() -> {
                    rebuildExcList();
                    rebuildAvailableList(availableMembersFilterField != null
                            ? availableMembersFilterField.getText() : "");
                    updateUserExcDirtyState();
                });
            });

            Label lbl = new Label(name);
            lbl.setStyle("-fx-font-size: 13px;");
            lbl.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(lbl, Priority.ALWAYS);

            HBox row = new HBox(8, avatarBg, lbl, trashBtn);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(6, 8, 6, 10));
            row.setStyle("-fx-background-radius: 6; -fx-cursor: hand;");
            row.setOnMouseEntered(e -> row.setStyle(
                    "-fx-background-color: -color-bg-subtle; -fx-background-radius: 6; -fx-cursor: hand;"));
            row.setOnMouseExited(e -> row.setStyle("-fx-background-radius: 6; -fx-cursor: hand;"));
            row.setOnMouseClicked(e -> showExcDetailView(uid, name));

            excListBox.getChildren().add(row);
        }
    }

    private void handleMembersSearch(String query) {
        if (query.isEmpty()) {
            membersSearchResults = null;
            rebuildAvailableList("");
            return;
        }
        UUID serverId = ctx.server().getServerId();
        UUID myId = App.getUser() != null ? App.getUser().getUserId() : null;
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
            Map<UUID, String> map = new LinkedHashMap<>();
            for (UserStatusDto dto : svc.getValue()) {
                if (myId != null && dto.getUserId().equals(myId)) continue;
                AvatarCache.CachedUser cu = App.getAvatarCache().getIfPresent(dto.getUserId());
                map.put(dto.getUserId(), cu != null && cu.username() != null ? cu.username() : dto.getUserId().toString());
            }
            membersSearchResults = map;
            rebuildAvailableList(query);
        });
        svc.setOnFailed(e -> {
            membersSearchResults = null;
            rebuildAvailableList(availableMembersFilterField != null ? availableMembersFilterField.getText() : "");
        });
        svc.start();
    }

    private void rebuildAvailableList(String filter) {
        if (availableMembersBox == null) return;
        availableMembersBox.getChildren().clear();

        UUID me = App.getUser() != null ? App.getUser().getUserId() : null;
        Map<UUID, String> source = membersSearchResults != null ? membersSearchResults : serverMembersCache;

        if (source.isEmpty()) {
            if (membersSearchResults == null) {
                Label loading = new Label("Loading members…");
                loading.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted; -fx-padding: 2 0 0 4;");
                availableMembersBox.getChildren().add(loading);
            } else {
                Label none = new Label("No members found.");
                none.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted; -fx-padding: 2 0 0 4;");
                availableMembersBox.getChildren().add(none);
            }
            return;
        }

        String lc = membersSearchResults != null ? "" : (filter == null ? "" : filter.toLowerCase());
        boolean any = false;
        for (Map.Entry<UUID, String> entry : source.entrySet()) {
            UUID uid = entry.getKey();
            String name = entry.getValue();
            if (me != null && uid.equals(me)) continue;
            if (pendingUserExceptions.containsKey(uid)) continue;
            if (!lc.isEmpty() && !name.toLowerCase().contains(lc)) continue;

            StackPane avatarBg = buildAvatarBg(28);
            AvatarCache.CachedUser cached = App.getAvatarCache().getIfPresent(uid);
            if (cached != null) fillAvatarBg(avatarBg, cached, 28);

            Label lbl = new Label(name);
            lbl.setStyle("-fx-font-size: 13px;");
            lbl.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(lbl, Priority.ALWAYS);

            HBox row = new HBox(8, avatarBg, lbl);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(6, 8, 6, 10));
            row.setStyle("-fx-background-radius: 6; -fx-cursor: hand;");
            row.setOnMouseEntered(e -> row.setStyle(
                    "-fx-background-color: -color-bg-subtle; -fx-background-radius: 6; -fx-cursor: hand;"));
            row.setOnMouseExited(e -> row.setStyle("-fx-background-radius: 6; -fx-cursor: hand;"));
            row.setOnMouseClicked(e -> showExcDetailView(uid, name));
            availableMembersBox.getChildren().add(row);
            any = true;
        }

        if (!any) {
            String msg = lc.isEmpty() ? "All members have exceptions." : "No members found.";
            Label none = new Label(msg);
            none.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted; -fx-padding: 2 0 0 4;");
            availableMembersBox.getChildren().add(none);
        }
    }

    // ── Avatar helpers ───────────────────────────────────────────────────────────

    private StackPane buildAvatarBg(double size) {
        StackPane bg = new StackPane();
        bg.setPrefSize(size, size);
        bg.setMinSize(size, size);
        bg.setMaxSize(size, size);
        bg.setStyle("-fx-background-color: " + AvatarColor.forName(null) + ";" +
                "-fx-background-radius: " + (size / 2) + "px;");
        Label letter = new Label("?");
        letter.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: white;");
        letter.setMouseTransparent(true);
        letter.setUserData("letter");
        bg.getChildren().add(letter);
        bg.setAlignment(Pos.CENTER);
        return bg;
    }

    private void fillAvatarBg(StackPane bg, AvatarCache.CachedUser cu, double size) {
        Label letter = bg.getChildren().stream()
                .filter(n -> n instanceof Label && "letter".equals(n.getUserData()))
                .map(n -> (Label) n)
                .findFirst().orElse(null);

        if (cu.avatar() != null && cu.avatar().length > 0) {
            try {
                Image img = new Image(new ByteArrayInputStream(cu.avatar()), size, size, true, true);
                if (!img.isError()) {
                    Circle imgCircle = new Circle(size / 2);
                    imgCircle.setFill(new ImagePattern(img));
                    if (letter != null) letter.setVisible(false);
                    bg.getChildren().removeIf(n -> n instanceof Circle);
                    bg.getChildren().add(imgCircle);
                    bg.setStyle("-fx-background-color: transparent; -fx-background-radius: " + (size / 2) + "px;");
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        bg.getChildren().removeIf(n -> n instanceof Circle);
        if (letter != null) {
            String l = (cu.username() != null && !cu.username().isEmpty())
                    ? String.valueOf(cu.username().charAt(0)).toUpperCase() : "?";
            letter.setText(l);
            letter.setVisible(true);
        }
        bg.setStyle("-fx-background-color: " + AvatarColor.forName(cu.username()) + ";" +
                "-fx-background-radius: " + (size / 2) + "px;");
    }

    // ── Detail view (per-user permission rows) ────────────────────────────────

    private void rebuildDetailContent() {
        if (userExcDetailContent == null || selectedExceptionUserId == null) return;
        userExcDetailContent.getChildren().clear();

        // Use actual override if already committed, or all-inherit for preview
        PermOverride masks = pendingUserExceptions.get(selectedExceptionUserId);

        Label hint = new Label(
                "These overrides apply only to this user in this channel, taking precedence over role permissions.");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");
        hint.setWrapText(true);

        javafx.scene.control.Separator sep = new javafx.scene.control.Separator(javafx.geometry.Orientation.HORIZONTAL);
        sep.setPadding(new Insets(4, 0, 4, 0));

        userExcDetailContent.getChildren().addAll(hint, sep);

        UUID uid = selectedExceptionUserId;
        for (Permission perm : ctx.getActiveChannelPerms()) {
            OverrideState state;
            if (masks != null && masks.allow().contains(perm)) state = OverrideState.ALLOW;
            else if (masks != null && masks.deny().contains(perm)) state = OverrideState.DENY;
            else state = OverrideState.INHERIT;
            userExcDetailContent.getChildren().add(buildUserExcPermRow(uid, perm, state));
        }
    }

    private HBox buildUserExcPermRow(UUID uid, Permission perm, OverrideState currentState) {
        Label name = new Label(perm.getDisplayName());
        name.setStyle("-fx-font-size: 12px;");
        Tooltip.install(name, new Tooltip(perm.getDescription()));

        Button inheritBtn = buildSegmentBtn("Inherit", OverrideState.INHERIT, currentState);
        Button allowBtn   = buildSegmentBtn("Allow",   OverrideState.ALLOW,   currentState);
        Button denyBtn    = buildSegmentBtn("Deny",    OverrideState.DENY,    currentState);

        inheritBtn.setStyle(inheritBtn.getStyle() + "-fx-background-radius: 4 0 0 4; -fx-border-radius: 4 0 0 4;");
        allowBtn.setStyle(allowBtn.getStyle()     + "-fx-background-radius: 0; -fx-border-radius: 0; -fx-border-left-width: 0;");
        denyBtn.setStyle(denyBtn.getStyle()       + "-fx-background-radius: 0 4 4 0; -fx-border-radius: 0 4 4 0; -fx-border-left-width: 0;");

        inheritBtn.setOnAction(e -> applyUserExcOverride(uid, perm, OverrideState.INHERIT));
        allowBtn.setOnAction(e   -> applyUserExcOverride(uid, perm, OverrideState.ALLOW));
        denyBtn.setOnAction(e    -> applyUserExcOverride(uid, perm, OverrideState.DENY));

        HBox segmented = new HBox(0, inheritBtn, allowBtn, denyBtn);
        segmented.setAlignment(Pos.CENTER_RIGHT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(10, name, spacer, segmented);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 4, 6, 4));
        return row;
    }

    private Button buildSegmentBtn(String label, OverrideState btnState, OverrideState currentState) {
        Button btn = new Button(label);
        btn.setFocusTraversable(false);
        btn.getStyleClass().add(Styles.SMALL);
        if (btnState == currentState) {
            switch (btnState) {
                case ALLOW -> btn.getStyleClass().add(Styles.SUCCESS);
                case DENY -> btn.getStyleClass().add(Styles.DANGER);
                case INHERIT -> btn.getStyleClass().add(Styles.ACCENT);
            }
        } else {
            btn.getStyleClass().add(Styles.FLAT);
            btn.setStyle("-fx-opacity: 0.60;");
        }
        return btn;
    }

    private void applyUserExcOverride(UUID uid, Permission perm, OverrideState newState) {
        PermOverride existing = pendingUserExceptions.get(uid);
        EnumSet<Permission> allow = existing != null
                ? EnumSet.copyOf(existing.allow()) : EnumSet.noneOf(Permission.class);
        EnumSet<Permission> deny = existing != null
                ? EnumSet.copyOf(existing.deny()) : EnumSet.noneOf(Permission.class);
        allow.remove(perm);
        deny.remove(perm);
        if (newState == OverrideState.ALLOW) allow.add(perm);
        else if (newState == OverrideState.DENY) deny.add(perm);
        pendingUserExceptions.put(uid, new PermOverride(allow, deny));

        // empty override == no exception; remove so dirty-check stays clean
        if (allow.isEmpty() && deny.isEmpty() && !origUserExceptions.containsKey(uid)) {
            pendingUserExceptions.remove(uid);
            userExceptionNames.remove(uid);
            Platform.runLater(() -> {
                rebuildExcList();
                rebuildAvailableList(availableMembersFilterField != null
                        ? availableMembersFilterField.getText() : "");
            });
        } else if (allow.isEmpty() && deny.isEmpty() && origUserExceptions.containsKey(uid)) {
            // Keep in pending so the dirty-check detects the user reset everything to inherit
            // (save will delete the exception server-side)
        } else if (!origUserExceptions.containsKey(uid)) {
            // First real (non-inherit) override for this user — show them in the exceptions list
            Platform.runLater(() -> {
                rebuildExcList();
                rebuildAvailableList(availableMembersFilterField != null
                        ? availableMembersFilterField.getText() : "");
            });
        }
        updateUserExcDirtyState();
        rebuildDetailContent();
    }

    private void updateUserExcDirtyState() {
        boolean dirty = false;
        for (Map.Entry<UUID, PermOverride> entry : pendingUserExceptions.entrySet()) {
            PermOverride orig = origUserExceptions.get(entry.getKey());
            PermOverride cur = entry.getValue();
            if (orig == null || !cur.allow().equals(orig.allow()) || !cur.deny().equals(orig.deny())) {
                dirty = true;
                break;
            }
        }
        if (!dirty) {
            for (UUID uid : origUserExceptions.keySet()) {
                if (!pendingUserExceptions.containsKey(uid)) {
                    dirty = true;
                    break;
                }
            }
        }
        userExcDirty = dirty;
        ctx.refreshSaveButton();
    }

    private void handleSaveUserExceptions() {
        pendingExcSnapshot = new LinkedHashMap<>();
        pendingUserExceptions.forEach((k, v) -> pendingExcSnapshot.put(k,
                new PermOverride(EnumSet.copyOf(v.allow()), EnumSet.copyOf(v.deny()))));
        origExcSnapshot = new LinkedHashMap<>();
        origUserExceptions.forEach((k, v) -> origExcSnapshot.put(k,
                new PermOverride(EnumSet.copyOf(v.allow()), EnumSet.copyOf(v.deny()))));
        saveUserExcService.restart();
    }
}
