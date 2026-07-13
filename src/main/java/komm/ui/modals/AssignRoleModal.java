package komm.ui.modals;

import atlantafx.base.theme.Styles;
import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import komm.App;
import komm.api.HttpStatusException;
import komm.model.dto.summary.CustomRoleSummary;
import komm.model.dto.summary.ServerMemberSummary;
import komm.model.dto.summary.ServerSummary;
import komm.model.permissions.Permission;
import komm.ui.avatar.AvatarCache;
import komm.ui.avatar.AvatarPreviewWidget;
import komm.ui.customnodes.CustomNotification;
import komm.ui.utils.IconColorUtil;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

import java.util.*;

@Slf4j
public class AssignRoleModal extends HBox {

    private static final double W = 680;
    private static final double H = 520;

    private final UUID targetUserId;
    private final boolean isSelf;
    private ServerMemberSummary memberState;

    private ServerSummary.Role selectedBaseRole;
    private final Set<UUID> selectedCustomRoleIds = new HashSet<>();

    private final ToggleGroup baseRoleToggleGroup = new ToggleGroup();
    private final Map<ServerSummary.Role, RadioButton> baseRoleRadios = new LinkedHashMap<>();
    private final Map<UUID, CheckBox> customRoleCheckboxes = new LinkedHashMap<>();

    private final VBox baseRolesBox   = new VBox(2);
    private final VBox customRolesBox = new VBox(2);
    private final ProgressIndicator progress = new ProgressIndicator();
    private Button saveButton;

    // Left panel widgets
    private StackPane avatarSlot;
    private Label usernameLbl;
    private Label roleTagLbl;

    // Right panel scroll — rebuilt after member state loads
    private ScrollPane customRolesScroll;
    private StackPane tabContent;

    public AssignRoleModal(UUID targetUserId) {
        this.targetUserId = targetUserId;
        this.isSelf = App.getUser() != null && targetUserId.equals(App.getUser().getUserId());

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
        panel.setPrefWidth(240);
        panel.setMinWidth(240);
        panel.setMaxWidth(240);
        panel.setStyle("-fx-background-color: rgba(255,255,255,0.03); -fx-background-radius: 12px 0 0 12px;");

        // Avatar slot — empty until cache resolves
        avatarSlot = new StackPane();
        avatarSlot.setPrefSize(96, 96);
        avatarSlot.setMinSize(96, 96);
        avatarSlot.setMaxSize(96, 96);

        usernameLbl = new Label("Loading…");
        usernameLbl.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        roleTagLbl = new Label("");
        roleTagLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");

        VBox nameAndRole = new VBox(2, usernameLbl, roleTagLbl);
        nameAndRole.setAlignment(Pos.CENTER);

        VBox identity = new VBox(0);
        identity.setAlignment(Pos.CENTER);
        identity.setPadding(new Insets(28, 20, 20, 20));
        VBox.setMargin(avatarSlot, new Insets(0, 0, 20, 0));
        identity.getChildren().addAll(avatarSlot, nameAndRole);

        Separator hDiv = new Separator(Orientation.HORIZONTAL);
        hDiv.setPadding(new Insets(0));

        VBox baseSection = new VBox(0);
        baseSection.setPadding(new Insets(16, 12, 12, 12));

        if (!isSelf) {
            Label baseLabel = sectionLabel("BASE ROLE");
            buildBaseRoleRows();
            baseSection.getChildren().addAll(baseLabel, spacer(8), baseRolesBox);
        } else {
            Label note = new Label("You cannot change your own base role.");
            note.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
            note.setWrapText(true);
            baseSection.getChildren().add(note);
        }

        VBox.setVgrow(baseSection, Priority.ALWAYS);
        panel.getChildren().addAll(identity, hDiv, baseSection);
        return panel;
    }

    // ── Right panel ───────────────────────────────────────────────────────────

    private VBox buildRightPanel() {
        VBox panel = new VBox(0);
        panel.setAlignment(Pos.TOP_LEFT);

        customRolesScroll = buildCustomRolesScroll();

        tabContent = new StackPane();
        tabContent.setAlignment(Pos.TOP_LEFT);
        tabContent.getChildren().add(customRolesScroll);
        VBox.setVgrow(tabContent, Priority.ALWAYS);

        panel.getChildren().addAll(buildHeader(), tabContent, buildFooter());
        return panel;
    }

    private ScrollPane buildCustomRolesScroll() {
        VBox body = new VBox(0);
        body.setFillWidth(true);
        body.setPadding(new Insets(20, 24, 16, 24));

        Label customLabel = sectionLabel("CUSTOM ROLES");
        buildCustomRoleRows();
        body.getChildren().addAll(customLabel, spacer(8), customRolesBox);

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(18, 16, 14, 24));
        header.setStyle("-fx-border-color: transparent transparent -color-border-default transparent; -fx-border-width: 0 0 1 0;");

        Label title = new Label("Assign Roles");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label subtitle = new Label(isSelf ? "Your roles" : "Member permissions");
        subtitle.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button closeBtn = new Button(null, new FontIcon(MaterialDesignC.CLOSE));
        closeBtn.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
        closeBtn.setOnAction(e -> App.closeModal());

        header.getChildren().addAll(new VBox(2, title, subtitle), spacer, closeBtn);
        return header;
    }

    // ── Footer ────────────────────────────────────────────────────────────────

    private HBox buildFooter() {
        HBox footer = new HBox(10);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(12, 24, 14, 24));
        footer.setStyle("-fx-border-color: -color-border-default transparent transparent transparent; -fx-border-width: 1 0 0 0;");

        progress.setMaxSize(16, 16);
        progress.setVisible(false);

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add(Styles.SMALL);
        cancelBtn.setOnAction(e -> App.closeModal());

        saveButton = new Button("Save");
        saveButton.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        saveButton.setDisable(true);
        saveButton.setOnAction(e -> handleSave());

        footer.getChildren().addAll(progress, sp, cancelBtn, saveButton);
        return footer;
    }

    // ── Base role rows ────────────────────────────────────────────────────────

    private void buildBaseRoleRows() {
        baseRolesBox.getChildren().clear();
        baseRoleRadios.clear();

        int myRank = roleRank(App.getPermissionManager().getMyRole());

        for (ServerSummary.Role role : List.of(
                ServerSummary.Role.ADMIN, ServerSummary.Role.MODERATOR, ServerSummary.Role.MEMBER)) {
            if (roleRank(role) >= myRank) continue;
            baseRolesBox.getChildren().add(buildBaseRoleRow(role));
        }
    }

    private HBox buildBaseRoleRow(ServerSummary.Role role) {
        FontIcon icon = new FontIcon(roleIcon(role));

        Label nameLbl = new Label(capitalize(role.name()));
        nameLbl.setStyle("-fx-font-size: 12px;");

        RadioButton radio = new RadioButton();
        radio.setToggleGroup(baseRoleToggleGroup);
        radio.setOnAction(e -> { selectedBaseRole = role; updateSaveState(); });
        baseRoleRadios.put(role, radio);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(8, icon, nameLbl, spacer, radio);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(7, 10, 7, 10));
        row.setStyle("-fx-background-radius: 6;");
        row.setOnMouseClicked(e -> radio.fire());
        row.setOnMouseEntered(e -> row.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 6;"));
        row.setOnMouseExited(e  -> row.setStyle("-fx-background-radius: 6;"));
        return row;
    }

    // ── Custom role rows ──────────────────────────────────────────────────────

    private void buildCustomRoleRows() {
        customRolesBox.getChildren().clear();
        customRoleCheckboxes.clear();

        boolean isOwner = App.getPermissionManager().getMyRole() == ServerSummary.Role.OWNER;

        for (CustomRoleSummary cr : App.getPermissionManager().getCustomRoles()) {
            boolean hasEditPerms = cr.getPermissions() != null
                    && cr.getPermissions().contains(Permission.EDIT_SERVER_PERMS.name());
            if (hasEditPerms && !isOwner) continue;
            customRolesBox.getChildren().add(buildCustomRoleRow(cr));
        }

        if (customRolesBox.getChildren().isEmpty()) {
            customRolesBox.getChildren().add(buildEmptyCustomRolesPlaceholder());
        }
    }

    private HBox buildCustomRoleRow(CustomRoleSummary cr) {
        FontIcon icon = IconColorUtil.roleColorIcon(cr.getColor(), 20);

        Label nameLbl = new Label(cr.getRoleName());
        nameLbl.setStyle("-fx-font-size: 13px;");

        CheckBox checkbox = new CheckBox();
        checkbox.setSelected(selectedCustomRoleIds.contains(cr.getRoleId()));
        checkbox.setOnAction(e -> {
            if (checkbox.isSelected()) selectedCustomRoleIds.add(cr.getRoleId());
            else selectedCustomRoleIds.remove(cr.getRoleId());
            updateSaveState();
        });
        customRoleCheckboxes.put(cr.getRoleId(), checkbox);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(10, icon, nameLbl, spacer, checkbox);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(9, 12, 9, 12));
        row.setStyle("-fx-background-radius: 6;");
        row.setOnMouseClicked(e -> checkbox.fire());
        row.setOnMouseEntered(e -> row.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 6;"));
        row.setOnMouseExited(e  -> row.setStyle("-fx-background-radius: 6;"));
        return row;
    }

    private VBox buildEmptyCustomRolesPlaceholder() {
        FontIcon icon = new FontIcon(MaterialDesignS.SHIELD_OFF_OUTLINE);
        icon.getStyleClass().add("custom-icon-35");

        Label title = new Label("No Custom Roles");
        title.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: -color-fg-muted;");

        Label hint = new Label("This server has no custom roles yet.\nCreate one in Server Settings.");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
        hint.setWrapText(true);
        hint.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        VBox box = new VBox(8, icon, title, hint);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40, 20, 20, 20));
        return box;
    }

    // ── Load data ─────────────────────────────────────────────────────────────

    private void loadData() {
        // Synchronous fast-path — no flicker if user is already cached
        AvatarCache.CachedUser instant = App.getAvatarCache().getIfPresent(targetUserId);
        if (instant != null) {
            injectAvatar(instant);
        } else {
            App.getAvatarCache().resolve(targetUserId)
                    .thenAccept(cached -> Platform.runLater(() -> injectAvatar(cached)));
        }

        // Load member state for role selections
        Service<ServerMemberSummary> loadSvc = new Service<>() {
            @Override
            protected Task<ServerMemberSummary> createTask() {
                return new Task<>() {
                    @Override
                    protected ServerMemberSummary call() throws Exception {
                        return App.getServices().installation()
                                .getInstallationPermissionService().getMember(targetUserId);
                    }
                };
            }
        };
        loadSvc.setOnSucceeded(e -> applyMemberState(loadSvc.getValue()));
        loadSvc.setOnFailed(e ->
                log.error("Failed to load member state for {}: {}", targetUserId, loadSvc.getException().getMessage()));
        loadSvc.start();
    }

    private void injectAvatar(AvatarCache.CachedUser cached) {
        if (cached == null) return;

        usernameLbl.setText(cached.username());

        AvatarPreviewWidget widget = AvatarPreviewWidget.configure()
                .previewSize(96)
                .allowUpload(false)
                .initialBytes(cached.avatar())
                .initialFormat("png")
                .letterFallbackName(
                        (cached.avatar() == null || cached.avatar().length == 0)
                                ? cached.username()
                                : null
                )
                .build();

        avatarSlot.getChildren().setAll(widget);
    }

    // ── Apply member state ────────────────────────────────────────────────────

    private void applyMemberState(ServerMemberSummary member) {
        if (member == null) return;
        this.memberState = member;

        // Show current base role as a subtle tag under the username
        if (member.getBaseRole() != null) {
            roleTagLbl.setText(capitalize(member.getBaseRole()));
        }

        if (!isSelf) {
            try {
                ServerSummary.Role currentBase = ServerSummary.Role.valueOf(member.getBaseRole());
                RadioButton radio = baseRoleRadios.get(currentBase);
                if (radio != null) {
                    radio.setSelected(true);
                    selectedBaseRole = currentBase;
                }
            } catch (IllegalArgumentException ignored) {}
        }

        selectedCustomRoleIds.clear();
        if (member.getCustomRoleIds() != null)
            selectedCustomRoleIds.addAll(member.getCustomRoleIds());

        // Rebuild custom rows now that we know which ones are selected
        buildCustomRoleRows();
        saveButton.setDisable(true);
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    private void handleSave() {
        if (memberState == null) return;

        ServerSummary.Role origBase;
        try { origBase = ServerSummary.Role.valueOf(memberState.getBaseRole()); }
        catch (Exception e) { origBase = ServerSummary.Role.MEMBER; }

        final ServerSummary.Role finalOrigBase = origBase;
        final ServerSummary.Role finalNewBase  = selectedBaseRole;
        final Set<UUID> finalNewCustom = new HashSet<>(selectedCustomRoleIds);
        final Set<UUID> origCustom = memberState.getCustomRoleIds() != null
                ? new HashSet<>(memberState.getCustomRoleIds()) : new HashSet<>();

        saveButton.setDisable(true);
        progress.setVisible(true);

        Service<Void> saveSvc = new Service<>() {
            @Override
            protected Task<Void> createTask() {
                return new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        var permSvc = App.getServices().installation().getInstallationPermissionService();

                        if (!isSelf && finalNewBase != null && finalNewBase != finalOrigBase)
                            permSvc.changeBaseRole(targetUserId, finalNewBase.name());

                        for (UUID id : finalNewCustom)
                            if (!origCustom.contains(id)) permSvc.assignCustomRole(id, targetUserId);
                        for (UUID id : origCustom)
                            if (!finalNewCustom.contains(id)) permSvc.removeCustomRole(id, targetUserId);

                        return null;
                    }
                };
            }
        };
        saveSvc.setOnSucceeded(e -> {
            progress.setVisible(false);
            App.closeModal();
        });
        saveSvc.setOnFailed(e -> {
            Throwable ex = saveSvc.getException();
            progress.setVisible(false);
            saveButton.setDisable(false);
            String msg = ex instanceof SecurityException
                    ? "Permission denied: " + HttpStatusException.extractMessage(ex)
                    : "Error: " + HttpStatusException.extractMessage(ex);
            new CustomNotification("Role Assignment Failed", msg, new FontIcon(MaterialDesignC.CLOSE)).showNotification();
        });
        saveSvc.start();
    }

    private void updateSaveState() {
        if (memberState == null) { saveButton.setDisable(true); return; }

        ServerSummary.Role origBase;
        try { origBase = ServerSummary.Role.valueOf(memberState.getBaseRole()); }
        catch (Exception e) { origBase = ServerSummary.Role.MEMBER; }

        Set<UUID> origCustom = memberState.getCustomRoleIds() != null
                ? new HashSet<>(memberState.getCustomRoleIds()) : new HashSet<>();

        boolean baseChanged   = !isSelf && selectedBaseRole != null && selectedBaseRole != origBase;
        boolean customChanged = !selectedCustomRoleIds.equals(origCustom);
        saveButton.setDisable(!(baseChanged || customChanged));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private org.kordamp.ikonli.Ikon roleIcon(ServerSummary.Role role) {
        return switch (role) {
            case MEMBER    -> MaterialDesignA.ACCOUNT;
            case MODERATOR -> MaterialDesignS.SHIELD_ACCOUNT;
            case ADMIN     -> MaterialDesignS.SHIELD_STAR;
            case OWNER     -> MaterialDesignC.CROWN;
        };
    }

    private Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle;");
        return l;
    }

    private static Region spacer(double h) {
        Region r = new Region();
        r.setMinHeight(h);
        r.setMaxHeight(h);
        return r;
    }

    private int roleRank(ServerSummary.Role role) {
        if (role == null) return 0;
        return switch (role) {
            case MEMBER    -> 0;
            case MODERATOR -> 1;
            case ADMIN     -> 2;
            case OWNER     -> 3;
        };
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.charAt(0) + s.substring(1).toLowerCase();
    }
}