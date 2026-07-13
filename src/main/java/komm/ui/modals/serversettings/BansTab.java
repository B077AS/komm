package komm.ui.modals.serversettings;

import atlantafx.base.theme.Styles;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.ImagePattern;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import komm.App;
import komm.model.dto.request.BanRequest;
import komm.model.dto.response.MemberPageResponse;
import komm.model.dto.response.UserLookupResponse;
import komm.model.dto.response.UserStatusDto;
import komm.model.dto.summary.BannedUserSummary;
import komm.model.dto.summary.ServerMemberSummary;
import komm.model.dto.summary.ServerSummary;
import komm.ui.avatar.AvatarCache;
import komm.ui.avatar.AvatarColor;
import komm.ui.customnodes.CustomNotification;
import komm.ui.modals.ConfirmationModal;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static komm.ui.modals.serversettings.ServerSettingsUi.sectionLabel;

/** "Bans" tab: ban/unban members, ban by username, and the banned-users list. */
@Slf4j
public class BansTab implements ServerSettingsTab {

    private final ServerSummary serverDetails;
    private final ScrollPane pane;

    private VBox banListBox;
    private VBox banMemberBox;
    private ScrollPane banMemberScroll;
    private TextField banSearchField;
    private final Map<UUID, String> serverMembersForBan = new LinkedHashMap<>();
    private boolean banTabLoaded = false;
    private int banMembersPage = 0;
    private long banMembersTotal = 0;
    private boolean banMembersAllLoaded = false;
    private boolean banMembersLoading = false;
    private int pendingBanMembersPage = 0;
    private Map<UUID, String> banSearchResults = null;
    private final PauseTransition banSearchDebounce = new PauseTransition(Duration.millis(350));

    private final Service<List<BannedUserSummary>> loadBansService = new Service<>() {
        @Override
        protected Task<List<BannedUserSummary>> createTask() {
            return new Task<>() {
                @Override
                protected List<BannedUserSummary> call() throws Exception {
                    return App.getServices().hub().getHubModerationService()
                            .getBannedUsers(serverDetails.getServerId());
                }
            };
        }
    };

    private final Service<MemberPageResponse> loadBanMembersPageService = new Service<>() {
        @Override
        protected Task<MemberPageResponse> createTask() {
            final int page = pendingBanMembersPage;
            return new Task<>() {
                @Override
                protected MemberPageResponse call() throws Exception {
                    MemberPageResponse resp = App.getServices().hub().getHubModerationService()
                            .getServerMembersPaged(serverDetails.getServerId(), page, 50);
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

    public BansTab(ServerSettingsContext ctx) {
        this.serverDetails = ctx.serverDetails();
        this.pane = buildBannedUsersPane();
    }

    // ── ServerSettingsTab ──────────────────────────────────────────────────────

    @Override public String name() { return "Bans"; }
    @Override public String description() { return "Manage banned users and lift bans"; }
    @Override public FontIcon icon() { return new FontIcon(MaterialDesignA.ACCOUNT_CANCEL_OUTLINE); }
    @Override public Node getPane() { return pane; }
    @Override public void onShown() { loadBanTabData(); }

    // ── Pane ───────────────────────────────────────────────────────────────────

    private ScrollPane buildBannedUsersPane() {
        Label banSection = sectionLabel("BAN A MEMBER");

        Label hint = new Label("Search for a server member or paste a User ID.");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        hint.setWrapText(true);

        banSearchField = new TextField();
        banSearchField.setPromptText("Search by username…");
        banSearchField.getStyleClass().add(Styles.SMALL);
        banSearchField.setMaxWidth(Double.MAX_VALUE);
        banSearchField.textProperty().addListener((obs, ov, nv) -> {
            String q = nv == null ? "" : nv.trim();
            banSearchDebounce.setOnFinished(e -> handleBanMemberSearch(q));
            banSearchDebounce.playFromStart();
        });

        banMemberBox = new VBox(2);

        banMemberScroll = new ScrollPane(banMemberBox);
        banMemberScroll.setFitToWidth(true);
        banMemberScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        banMemberScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(banMemberScroll, Priority.ALWAYS);
        banMemberScroll.vvalueProperty().addListener((obs, ov, nv) -> {
            if (nv.doubleValue() > 0.8 && !banMembersLoading && !banMembersAllLoaded)
                loadNextBanMembersPage();
        });

        Label externalLabel = sectionLabel("BAN BY USERNAME");
        Label externalHint = new Label("Ban someone who hasn't joined yet.");
        externalHint.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");

        TextField externalUsernameField = new TextField();
        externalUsernameField.setPromptText("Enter exact username…");
        externalUsernameField.getStyleClass().add(Styles.SMALL);
        externalUsernameField.setMaxWidth(Double.MAX_VALUE);

        ProgressIndicator externalSpinner = new ProgressIndicator();
        externalSpinner.setPrefSize(16, 16);
        externalSpinner.setMaxSize(16, 16);
        externalSpinner.setVisible(false);
        externalSpinner.setManaged(false);

        FontIcon banIcon = new FontIcon(MaterialDesignA.ACCOUNT_CANCEL_OUTLINE);
        banIcon.getStyleClass().add("custom-icon-14-white");

        Button externalBanBtn = new Button("Ban", banIcon);
        externalBanBtn.getStyleClass().addAll(Styles.SMALL);
        externalBanBtn.setFocusTraversable(false);
        externalBanBtn.setOnAction(e -> {
            String query = externalUsernameField.getText().trim();
            if (query.isEmpty()) return;
            externalBanBtn.setDisable(true);
            externalSpinner.setVisible(true);
            externalSpinner.setManaged(true);
            Service<UserLookupResponse> lookupSvc = new Service<>() {
                @Override
                protected Task<UserLookupResponse> createTask() {
                    return new Task<>() {
                        @Override
                        protected UserLookupResponse call() throws Exception {
                            return App.getServices().hub().getUserService().getUserByUsername(query);
                        }
                    };
                }
            };
            lookupSvc.setOnSucceeded(ev -> {
                UserLookupResponse result = lookupSvc.getValue();
                externalSpinner.setVisible(false);
                externalSpinner.setManaged(false);
                externalBanBtn.setDisable(false);
                if (result == null || result.getUserId() == null) {
                    externalUsernameField.setStyle("-fx-border-color: -color-danger-fg;");
                    return;
                }
                UUID myId = App.getUser() != null ? App.getUser().getUserId() : null;
                if (myId != null && result.getUserId().equals(myId)) {
                    new CustomNotification("Cannot Ban Yourself",
                            "You cannot ban yourself from this server.",
                            new FontIcon(MaterialDesignA.ACCOUNT_CANCEL_OUTLINE)).showNotification();
                    return;
                }
                if (result.getUserId().equals(serverDetails.getOwnerId())) {
                    new CustomNotification("Cannot Ban Server Owner",
                            "The server owner cannot be banned.",
                            new FontIcon(MaterialDesignA.ACCOUNT_CANCEL_OUTLINE)).showNotification();
                    return;
                }
                externalUsernameField.clear();
                externalUsernameField.setStyle("");
                App.showModal(new ConfirmationModal(
                        "Ban " + result.getUsername(),
                        "Ban " + result.getUsername() + "? They will not be able to join this server.",
                        new FontIcon(MaterialDesignA.ACCOUNT_CANCEL_OUTLINE),
                        () -> executeBan(result.getUserId(), result.getUsername())));
            });
            lookupSvc.setOnFailed(ev -> {
                externalSpinner.setVisible(false);
                externalSpinner.setManaged(false);
                externalBanBtn.setDisable(false);
                externalUsernameField.setStyle("-fx-border-color: -color-danger-fg;");
            });
            lookupSvc.start();
        });
        externalUsernameField.textProperty().addListener((obs, ov, nv) ->
                externalUsernameField.setStyle(""));
        externalUsernameField.setOnAction(externalBanBtn.getOnAction());

        HBox externalRow = new HBox(8, externalUsernameField, externalSpinner, externalBanBtn);
        externalRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(externalUsernameField, Priority.ALWAYS);

        Separator extSep = new Separator(Orientation.HORIZONTAL);
        VBox.setMargin(extSep, new Insets(4, 0, 4, 0));

        VBox leftCol = new VBox(10,
                new VBox(6, banSection, hint, banSearchField),
                banMemberScroll,
                extSep,
                new VBox(8, externalLabel, externalHint, externalRow));
        leftCol.setPadding(new Insets(20, 12, 16, 20));
        VBox.setVgrow(banMemberScroll, Priority.ALWAYS);
        VBox.setVgrow(leftCol, Priority.ALWAYS);

        Label bannedSection = sectionLabel("BANNED USERS");

        banListBox = new VBox(2);

        Label loadingLbl = new Label("Loading…");
        loadingLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");
        banListBox.getChildren().add(loadingLbl);

        ScrollPane rightScroll = new ScrollPane(banListBox);
        rightScroll.setFitToWidth(true);
        rightScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        rightScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(rightScroll, Priority.ALWAYS);

        VBox rightCol = new VBox(10, bannedSection, rightScroll);
        rightCol.setPadding(new Insets(20, 20, 16, 12));
        rightCol.setStyle(
                "-fx-border-color: transparent transparent transparent -color-border-default;" +
                        "-fx-border-width: 0 0 0 1;");
        VBox.setVgrow(rightScroll, Priority.ALWAYS);
        VBox.setVgrow(rightCol, Priority.ALWAYS);

        javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
        cc.setPercentWidth(50);
        cc.setHgrow(Priority.ALWAYS);
        javafx.scene.layout.ColumnConstraints cc2 = new javafx.scene.layout.ColumnConstraints();
        cc2.setPercentWidth(50);
        cc2.setHgrow(Priority.ALWAYS);

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.getColumnConstraints().addAll(cc, cc2);
        grid.setMaxWidth(Double.MAX_VALUE);
        grid.add(leftCol, 0, 0);
        grid.add(rightCol, 1, 0);
        javafx.scene.layout.GridPane.setVgrow(leftCol, Priority.ALWAYS);
        javafx.scene.layout.GridPane.setVgrow(rightCol, Priority.ALWAYS);

        VBox wrapper = new VBox(grid);
        wrapper.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(grid, Priority.ALWAYS);
        VBox.setVgrow(wrapper, Priority.ALWAYS);

        ScrollPane outer = new ScrollPane(wrapper);
        outer.setFitToWidth(true);
        outer.setFitToHeight(true);
        outer.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        outer.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        return outer;
    }

    private void loadBanTabData() {
        if (banTabLoaded) return;
        banTabLoaded = true;

        loadBansService.setOnSucceeded(e -> {
            List<BannedUserSummary> bans = loadBansService.getValue();
            rebuildBanList(bans == null ? List.of() : bans);
        });
        loadBansService.setOnFailed(e ->
                banListBox.getChildren().setAll(buildBanEmptyLabel("Failed to load bans.")));
        loadBansService.start();

        loadBanMembersPageService.setOnSucceeded(e -> handleBanMembersPageLoaded(loadBanMembersPageService.getValue()));
        loadBanMembersPageService.setOnFailed(e -> {
            log.error("Failed to load ban members page: {}",
                    loadBanMembersPageService.getException() != null
                            ? loadBanMembersPageService.getException().getMessage() : "unknown");
            banMembersLoading = false;
        });
        serverMembersForBan.clear();
        banMembersPage = 0;
        banMembersTotal = 0;
        banMembersAllLoaded = false;
        loadNextBanMembersPage();
    }

    private void loadNextBanMembersPage() {
        if (banMembersLoading || banMembersAllLoaded) return;
        banMembersLoading = true;
        pendingBanMembersPage = banMembersPage;
        loadBanMembersPageService.restart();
    }

    private void handleBanMembersPageLoaded(MemberPageResponse resp) {
        if (resp == null || resp.getMembers() == null || resp.getMembers().isEmpty()) {
            banMembersAllLoaded = true;
            banMembersLoading = false;
            rebuildBanMemberList(banSearchField != null ? banSearchField.getText() : "");
            return;
        }
        banMembersTotal = resp.getTotal();
        for (ServerMemberSummary m : resp.getMembers()) {
            UUID uid = m.getUserId();
            AvatarCache.CachedUser cu = App.getAvatarCache().getIfPresent(uid);
            serverMembersForBan.put(uid, cu != null && cu.username() != null
                    ? cu.username() : uid.toString().substring(0, 8));
        }
        banMembersPage = resp.getPage() + 1;
        banMembersAllLoaded = serverMembersForBan.size() >= banMembersTotal;
        banMembersLoading = false;
        rebuildBanMemberList(banSearchField != null ? banSearchField.getText() : "");
    }

    private void rebuildBanList(List<BannedUserSummary> bans) {
        if (banListBox == null) return;
        banListBox.getChildren().clear();
        if (bans.isEmpty()) {
            banListBox.getChildren().add(buildBanEmptyLabel("No banned users."));
            return;
        }
        for (BannedUserSummary ban : bans) {
            banListBox.getChildren().add(buildBanRow(ban));
        }
    }

    private BorderPane buildBanRow(BannedUserSummary ban) {
        StackPane avatarBg = buildBanAvatarBg(32);
        AvatarCache.CachedUser cached = App.getAvatarCache().getIfPresent(ban.getUserId());
        if (cached != null) fillBanAvatarBg(avatarBg, cached, 32);

        Label nameLbl = new Label(cached != null && cached.username() != null
                ? cached.username() : "…");
        nameLbl.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");

        Label reasonLbl = new Label(ban.getReason() != null && !ban.getReason().isBlank()
                ? ban.getReason() : "No reason provided");
        reasonLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");

        VBox textBox = new VBox(2, nameLbl, reasonLbl);
        textBox.setAlignment(Pos.CENTER_LEFT);

        HBox left = new HBox(10, avatarBg, textBox);
        left.setAlignment(Pos.CENTER_LEFT);

        Button unbanBtn = new Button("Unban", new FontIcon(MaterialDesignA.ACCOUNT_CHECK_OUTLINE));
        unbanBtn.getStyleClass().addAll(Styles.SMALL, Styles.FLAT);
        unbanBtn.setFocusTraversable(false);
        unbanBtn.setOnAction(e -> executeUnban(ban.getUserId(), nameLbl.getText()));

        BorderPane row = new BorderPane();
        row.setLeft(left);
        row.setRight(unbanBtn);
        row.setPadding(new Insets(8, 6, 8, 6));
        BorderPane.setAlignment(left, Pos.CENTER_LEFT);
        BorderPane.setAlignment(unbanBtn, Pos.CENTER_RIGHT);

        App.getAvatarCache().resolve(ban.getUserId()).thenAccept(cu -> {
            if (cu == null) return;
            Platform.runLater(() -> {
                nameLbl.setText(cu.username());
                fillBanAvatarBg(avatarBg, cu, 32);
            });
        });

        return row;
    }

    private void handleBanMemberSearch(String query) {
        if (query.isEmpty()) {
            banSearchResults = null;
            rebuildBanMemberList("");
            return;
        }
        UUID serverId = serverDetails.getServerId();
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
            UUID ownerId = serverDetails.getOwnerId();
            for (UserStatusDto dto : svc.getValue()) {
                if (myId != null && dto.getUserId().equals(myId)) continue;
                if (ownerId != null && dto.getUserId().equals(ownerId)) continue;
                AvatarCache.CachedUser cu = App.getAvatarCache().getIfPresent(dto.getUserId());
                map.put(dto.getUserId(), cu != null && cu.username() != null ? cu.username() : dto.getUserId().toString());
            }
            banSearchResults = map;
            rebuildBanMemberList(query);
        });
        svc.setOnFailed(e -> {
            banSearchResults = null;
            rebuildBanMemberList(banSearchField != null ? banSearchField.getText() : "");
        });
        svc.start();
    }

    private void rebuildBanMemberList(String filter) {
        if (banMemberBox == null) return;
        banMemberBox.getChildren().clear();

        Map<UUID, String> source = banSearchResults != null ? banSearchResults : serverMembersForBan;

        if (source.isEmpty()) {
            if (banSearchResults == null && !banTabLoaded) return;
            banMemberBox.getChildren().add(buildBanEmptyLabel(
                    banSearchResults == null ? "Loading members…" : "No members found."));
            return;
        }

        String lc = banSearchResults != null ? "" : (filter == null ? "" : filter.trim().toLowerCase());
        boolean any = false;
        for (Map.Entry<UUID, String> entry : source.entrySet()) {
            UUID uid = entry.getKey();
            String name = entry.getValue();
            if (!lc.isEmpty() && !name.toLowerCase().contains(lc)) continue;
            banMemberBox.getChildren().add(buildMemberPickerRow(uid, name));
            any = true;
        }

        if (!any && !lc.isEmpty()) {
            banMemberBox.getChildren().add(buildBanEmptyLabel("No members found."));
        }
    }

    private BorderPane buildMemberPickerRow(UUID uid, String name) {
        StackPane avatarBg = buildBanAvatarBg(28);
        AvatarCache.CachedUser cached = App.getAvatarCache().getIfPresent(uid);
        if (cached != null) fillBanAvatarBg(avatarBg, cached, 28);

        Label nameLbl = new Label(name);
        nameLbl.setStyle("-fx-font-size: 13px;");

        HBox left = new HBox(8, avatarBg, nameLbl);
        left.setAlignment(Pos.CENTER_LEFT);

        Button banBtn = new Button(null, new FontIcon(MaterialDesignA.ACCOUNT_CANCEL_OUTLINE));
        banBtn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT, Styles.SMALL);
        banBtn.setFocusTraversable(false);
        banBtn.setTooltip(new Tooltip("Ban this user"));
        banBtn.setOnAction(e -> {
            e.consume();
            String displayName = nameLbl.getText();
            App.showModal(new ConfirmationModal(
                    "Ban " + displayName,
                    "Are you sure you want to ban " + displayName + "? They will no longer be able to join this server.",
                    new FontIcon(MaterialDesignA.ACCOUNT_CANCEL_OUTLINE),
                    () -> executeBan(uid, displayName)));
        });

        BorderPane row = new BorderPane();
        row.setLeft(left);
        row.setRight(banBtn);
        row.setPadding(new Insets(6, 6, 6, 6));
        BorderPane.setAlignment(left, Pos.CENTER_LEFT);
        BorderPane.setAlignment(banBtn, Pos.CENTER_RIGHT);

        return row;
    }

    private void executeBan(UUID userId, String username) {
        Service<Void> svc = new Service<>() {
            @Override
            protected Task<Void> createTask() {
                return new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        App.getServices().hub().getHubModerationService()
                                .banUser(serverDetails.getServerId(), BanRequest.builder().userId(userId).build());
                        return null;
                    }
                };
            }
        };
        svc.setOnSucceeded(e -> {
            serverMembersForBan.remove(userId);
            rebuildBanMemberList(banSearchField != null ? banSearchField.getText() : "");
            new CustomNotification("User Banned", username + " has been banned from this server.",
                    new FontIcon(MaterialDesignA.ACCOUNT_CANCEL_OUTLINE)).showNotification();
            refreshBanList();
        });
        svc.setOnFailed(e -> new CustomNotification("Ban Failed", "Could not ban " + username + ".",
                new FontIcon(MaterialDesignC.CLOSE)).showNotification());
        svc.start();
    }

    private void executeUnban(UUID userId, String username) {
        Service<Void> svc = new Service<>() {
            @Override
            protected Task<Void> createTask() {
                return new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        App.getServices().hub().getHubModerationService()
                                .unbanUser(serverDetails.getServerId(), userId);
                        return null;
                    }
                };
            }
        };
        svc.setOnSucceeded(e -> {
            new CustomNotification("User Unbanned", username + " has been unbanned.",
                    new FontIcon(MaterialDesignA.ACCOUNT_CHECK_OUTLINE)).showNotification();
            refreshBanList();
        });
        svc.setOnFailed(e -> new CustomNotification("Unban Failed", "Could not unban " + username + ".",
                new FontIcon(MaterialDesignC.CLOSE)).showNotification());
        svc.start();
    }

    private void refreshBanList() {
        loadBansService.reset();
        loadBansService.setOnSucceeded(e -> {
            List<BannedUserSummary> bans = loadBansService.getValue();
            rebuildBanList(bans == null ? List.of() : bans);
        });
        loadBansService.start();
    }

    // ── Avatar helpers ─────────────────────────────────────────────────────────

    private StackPane buildBanAvatarBg(double size) {
        StackPane bg = new StackPane();
        bg.setPrefSize(size, size);
        bg.setMinSize(size, size);
        bg.setMaxSize(size, size);
        bg.setStyle("-fx-background-color: " + AvatarColor.forName(null) + ";" +
                "-fx-background-radius: " + (size / 2) + "px;");
        Label letter = new Label("?");
        letter.setStyle("-fx-font-size: " + (int) (size * 0.38) + "px; -fx-font-weight: bold; -fx-text-fill: white;");
        letter.setMouseTransparent(true);
        letter.setUserData("letter");
        bg.getChildren().add(letter);
        bg.setAlignment(Pos.CENTER);
        return bg;
    }

    private void fillBanAvatarBg(StackPane bg, AvatarCache.CachedUser cu, double size) {
        Label letter = bg.getChildren().stream()
                .filter(n -> n instanceof Label && "letter".equals(n.getUserData()))
                .map(n -> (Label) n).findFirst().orElse(null);
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

    private Label buildBanEmptyLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted; -fx-padding: 4 0 4 4;");
        return l;
    }
}
