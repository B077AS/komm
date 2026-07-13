package komm.ui.modals.serversettings;

import atlantafx.base.controls.ToggleSwitch;
import atlantafx.base.theme.Styles;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import komm.App;
import komm.api.HttpStatusException;
import komm.model.dto.summary.CustomRoleSummary;
import komm.model.dto.summary.ServerPermissionsSummary;
import komm.model.dto.summary.ServerSummary;
import komm.model.permissions.Permission;
import komm.model.permissions.PermissionManager;
import komm.ui.customnodes.ColorPickerPopup;
import komm.ui.customnodes.CustomNotification;
import komm.ui.modals.ConfirmationModal;
import komm.ui.utils.IconColorUtil;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static komm.ui.modals.serversettings.ServerSettingsUi.*;

/** "Permissions" tab: base-role toggles and custom roles. */
public class PermissionsTab implements ServerSettingsTab {

    // ── Permission categories ──────────────────────────────────────────────────
    private static final Map<String, List<Permission>> PERM_CATEGORIES;

    static {
        PERM_CATEGORIES = new LinkedHashMap<>();
        PERM_CATEGORIES.put("Server Management", List.of(Permission.DELETE_SERVER, Permission.EDIT_SERVER_INFO, Permission.EDIT_SERVER_PERMS, Permission.VIEW_SERVER_SETTINGS));
        PERM_CATEGORIES.put("Channels", List.of(Permission.CREATE_CHANNELS, Permission.DELETE_CHANNELS, Permission.EDIT_CHANNELS, Permission.EDIT_CHANNEL_PERMS));
        PERM_CATEGORIES.put("Member Management", List.of(Permission.BAN_USERS, Permission.KICK_USERS, Permission.MUTE_USERS, Permission.DEAFEN_USERS, Permission.INVITE_USERS, Permission.DELETE_INVITES));
        PERM_CATEGORIES.put("Interaction", List.of(Permission.POKE_USERS, Permission.CHECK_PING));
        PERM_CATEGORIES.put("Messaging", List.of(Permission.SEND_MESSAGES, Permission.SEND_GIFS, Permission.SEND_ATTACHMENTS, Permission.ADD_REACTIONS, Permission.DELETE_OTHERS_MSGS));
        PERM_CATEGORIES.put("Voice", List.of(Permission.JOIN_VOICE, Permission.SCREEN_SHARE, Permission.MOVE_MEMBERS));
        PERM_CATEGORIES.put("Soundboard", List.of(Permission.USE_SOUNDBOARD, Permission.MANAGE_SERVER_SOUNDBOARD));
    }

    private final ServerSettingsContext ctx;
    private final ServerSummary serverDetails;
    private final PermissionManager localPermManager;

    private final Map<ServerSummary.Role, EnumSet<Permission>> workingBitmasks = new EnumMap<>(ServerSummary.Role.class);
    private final Map<ServerSummary.Role, EnumSet<Permission>> origWorkingBitmasks = new EnumMap<>(ServerSummary.Role.class);
    private final Map<UUID, EnumSet<Permission>> customWorkingBitmasks = new LinkedHashMap<>();

    private final StackPane root = new StackPane();
    private StackPane permRightArea;
    private VBox permRoleListBox;
    private VBox permSelectedRoleBtn;

    public PermissionsTab(ServerSettingsContext ctx) {
        this.ctx = ctx;
        this.serverDetails = ctx.serverDetails();
        this.localPermManager = ctx.localPermManager();

        root.setAlignment(Pos.TOP_LEFT);
        VBox.setVgrow(root, Priority.ALWAYS);

        initWorkingBitmasks();
        root.getChildren().setAll(buildContent());
    }

    // ── ServerSettingsTab ──────────────────────────────────────────────────────

    @Override public String name() { return "Permissions"; }
    @Override public String description() { return "Control what roles and members are allowed to do"; }
    @Override public FontIcon icon() { return new FontIcon(MaterialDesignS.SHIELD_ACCOUNT); }
    @Override public Node getPane() { return root; }
    @Override public boolean participatesInSave() { return true; }
    @Override public String saveButtonText() { return "Save Permissions"; }

    @Override
    public boolean isDirty() {
        for (ServerSummary.Role role : List.of(
                ServerSummary.Role.ADMIN, ServerSummary.Role.MODERATOR, ServerSummary.Role.MEMBER)) {
            EnumSet<Permission> cur = workingBitmasks.getOrDefault(role, Permission.defaultFor(role));
            EnumSet<Permission> orig = origWorkingBitmasks.getOrDefault(role, Permission.defaultFor(role));
            if (!cur.equals(orig)) return true;
        }
        return false;
    }

    @Override
    public void save() {
        handleSavePermissions();
    }

    /**
     * Apply freshly-loaded permissions from the hub. The shell loads {@code localPermManager} from the same
     * summary before calling this.
     */
    public void applyFreshPermissions(ServerPermissionsSummary summary) {
        if (summary == null) return;

        if (summary.getRolePermissions() != null) {
            summary.getRolePermissions().forEach((roleName, perms) -> {
                try {
                    workingBitmasks.put(ServerSummary.Role.valueOf(roleName), Permission.fromNames(perms));
                } catch (IllegalArgumentException ignored) {
                }
            });
        }
        workingBitmasks.put(ServerSummary.Role.OWNER, EnumSet.allOf(Permission.class));
        origWorkingBitmasks.clear();
        workingBitmasks.forEach((role, perms) -> origWorkingBitmasks.put(role, EnumSet.copyOf(perms)));

        customWorkingBitmasks.clear();
        if (summary.getCustomRoles() != null) {
            summary.getCustomRoles().forEach(cr ->
                    customWorkingBitmasks.put(cr.getRoleId(), Permission.fromNames(cr.getPermissions())));
        }

        refreshPermissionsPane(summary);
    }

    // ── Working bitmask init ───────────────────────────────────────────────────

    private void initWorkingBitmasks() {
        for (ServerSummary.Role role : ServerSummary.Role.values()) {
            workingBitmasks.put(role, Permission.defaultFor(role));
        }
        for (CustomRoleSummary cr : localPermManager.getCustomRoles()) {
            customWorkingBitmasks.put(cr.getRoleId(), Permission.fromNames(cr.getPermissions()));
        }
    }

    private void refreshPermissionsPane(ServerPermissionsSummary summary) {
        if (permRoleListBox == null || permRightArea == null) return;

        permRoleListBox.getChildren().clear();

        Label rolesLabel = new Label("BASE ROLES");
        rolesLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle; -fx-padding: 0 0 4px 10px;");
        permRoleListBox.getChildren().add(rolesLabel);

        int callerRank = roleRank(serverDetails.getRole());
        List<ServerSummary.Role> visibleBaseRoles = java.util.Arrays.stream(new ServerSummary.Role[]{
                        ServerSummary.Role.ADMIN, ServerSummary.Role.MODERATOR, ServerSummary.Role.MEMBER})
                .filter(r -> roleRank(r) < callerRank)
                .collect(java.util.stream.Collectors.toList());
        VBox firstRoleBtn = null;
        ServerSummary.Role firstRole = null;
        for (ServerSummary.Role role : visibleBaseRoles) {
            VBox btn = buildRoleBtn(capitalize(role.name()));
            btn.setOnMouseClicked(e -> selectRole(btn, role));
            permRoleListBox.getChildren().add(btn);
            if (firstRoleBtn == null) { firstRoleBtn = btn; firstRole = role; }
        }

        List<CustomRoleSummary> customRoles = summary.getCustomRoles() != null
                ? summary.getCustomRoles() : List.of();

        Runnable addAction = canEditPerms() ? this::showCreateCustomRoleForm : null;
        permRoleListBox.getChildren().add(buildRoleListSeparator("CUSTOM ROLES", addAction));

        for (CustomRoleSummary cr : customRoles) {
            VBox btn = buildCustomRoleBtn(cr.getRoleName(), cr.getColor());
            btn.setOnMouseClicked(e -> selectCustomRole(btn, cr));
            permRoleListBox.getChildren().add(btn);
        }

        permSelectedRoleBtn = null;
        if (firstRoleBtn != null) selectRole(firstRoleBtn, firstRole);
    }

    // ── Pane ───────────────────────────────────────────────────────────────────

    private boolean canEditPerms() {
        if (!ctx.canViewPermsTab()) return false;
        if (serverDetails.getRole() == ServerSummary.Role.OWNER) return true;
        return localPermManager.has(Permission.EDIT_SERVER_PERMS);
    }

    private HBox buildContent() {
        permRoleListBox = new VBox(2);
        permRoleListBox.setPrefWidth(200);
        permRoleListBox.setMinWidth(200);
        permRoleListBox.setMaxWidth(200);
        permRoleListBox.setPadding(new Insets(12, 4, 12, 8));

        Label rolesLabel = new Label("BASE ROLES");
        rolesLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle; -fx-padding: 0 0 4px 10px;");
        permRoleListBox.getChildren().add(rolesLabel);

        int callerRank = roleRank(serverDetails.getRole());
        List<ServerSummary.Role> visibleBaseRoles = java.util.Arrays.stream(new ServerSummary.Role[]{
                        ServerSummary.Role.ADMIN, ServerSummary.Role.MODERATOR, ServerSummary.Role.MEMBER})
                .filter(r -> roleRank(r) < callerRank)
                .collect(java.util.stream.Collectors.toList());
        for (ServerSummary.Role role : visibleBaseRoles) {
            VBox btn = buildRoleBtn(capitalize(role.name()));
            btn.setOnMouseClicked(e -> selectRole(btn, role));
            permRoleListBox.getChildren().add(btn);
        }

        List<CustomRoleSummary> customRoles = localPermManager.getCustomRoles();
        Runnable addAction = canEditPerms() ? this::showCreateCustomRoleForm : null;
        permRoleListBox.getChildren().add(buildRoleListSeparator("CUSTOM ROLES", addAction));

        for (CustomRoleSummary cr : customRoles) {
            VBox btn = buildCustomRoleBtn(cr.getRoleName(), cr.getColor());
            btn.setOnMouseClicked(e -> selectCustomRole(btn, cr));
            permRoleListBox.getChildren().add(btn);
        }

        permRightArea = new StackPane();
        permRightArea.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(permRightArea, Priority.ALWAYS);

        if (!visibleBaseRoles.isEmpty()) {
            VBox firstBtn = (VBox) permRoleListBox.getChildren().get(1);
            selectRole(firstBtn, visibleBaseRoles.get(0));
        }

        Separator vertSep = new Separator(Orientation.VERTICAL);
        vertSep.setPadding(Insets.EMPTY);

        HBox outer = new HBox(0, permRoleListBox, vertSep, permRightArea);
        outer.setMaxWidth(Double.MAX_VALUE);
        outer.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(outer, Priority.ALWAYS);
        return outer;
    }

    private void rebuildAndShowPermissionsPane() {
        root.getChildren().setAll(buildContent());
    }

    private void selectRole(VBox btn, ServerSummary.Role role) {
        if (permSelectedRoleBtn != null) setRoleNavInactive(permSelectedRoleBtn);
        setRoleNavActive(btn);
        permSelectedRoleBtn = btn;
        permRightArea.getChildren().setAll(buildBaseRolePanel(role));
    }

    private void selectCustomRole(VBox btn, CustomRoleSummary cr) {
        if (permSelectedRoleBtn != null) setRoleNavInactive(permSelectedRoleBtn);
        setRoleNavActive(btn);
        permSelectedRoleBtn = btn;
        permRightArea.getChildren().setAll(buildCustomRolePanel(cr));
    }

    private void setRoleNavActive(VBox item) {
        item.getStyleClass().remove("nav-inactive");
        if (!item.getStyleClass().contains("nav-active")) item.getStyleClass().add("nav-active");
    }

    private void setRoleNavInactive(VBox item) {
        item.getStyleClass().remove("nav-active");
        if (!item.getStyleClass().contains("nav-inactive")) item.getStyleClass().add("nav-inactive");
    }

    private VBox buildRoleBtn(String name) {
        Label lbl = new Label(name);
        lbl.getStyleClass().add("nav-label");
        VBox item = new VBox(lbl);
        item.setAlignment(Pos.CENTER_LEFT);
        item.setPadding(new Insets(8, 12, 8, 12));
        item.getStyleClass().add("nav-item");
        return item;
    }

    private VBox buildCustomRoleBtn(String name, String color) {
        FontIcon icon = IconColorUtil.roleColorIcon(color, 20);
        Label lbl = new Label(name);
        lbl.getStyleClass().add("nav-label");

        HBox row = new HBox(6, icon, lbl);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinHeight(0);

        VBox item = new VBox(row);
        item.setAlignment(Pos.CENTER);
        item.setPadding(new Insets(8, 12, 8, 12));
        item.getStyleClass().add("nav-item");
        return item;
    }

    private Node buildRoleListSeparator(String label, Runnable onAdd) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle; -fx-padding: 0 0 0 10px;");

        VBox box = new VBox();
        box.setPadding(new Insets(10, 0, 2, 0));

        if (onAdd != null) {
            Button addBtn = new Button(null, new FontIcon(MaterialDesignP.PLUS));
            addBtn.setFocusTraversable(false);
            addBtn.getStyleClass().addAll(Styles.FLAT, Styles.BUTTON_CIRCLE);
            addBtn.setPadding(new Insets(2));
            ((FontIcon) addBtn.getGraphic()).setIconSize(12);
            addBtn.setOnAction(e -> onAdd.run());

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            HBox row = new HBox(lbl, spacer, addBtn);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(0, 4, 0, 0));
            box.getChildren().add(row);
        } else {
            box.getChildren().add(lbl);
        }

        return box;
    }

    // ── Base role panel (toggles) ──────────────────────────────────────────────

    private VBox buildBaseRolePanel(ServerSummary.Role role) {
        boolean callerIsOwner = serverDetails.getRole() == ServerSummary.Role.OWNER;
        boolean isOwnRole = !callerIsOwner && (role == serverDetails.getRole());

        Label roleTitle = new Label(capitalize(role.name()));
        roleTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        HBox header = new HBox(8, roleTitle, headerSpacer);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(14, 16, 12, 20));
        header.setStyle(
                "-fx-border-color: transparent transparent -color-border-default transparent;" +
                "-fx-border-width: 0 0 1 0;");

        if (role != ServerSummary.Role.OWNER && canEditPerms() && !isOwnRole) {
            Button resetBtn = new Button("Reset to Default");
            resetBtn.setFocusTraversable(false);
            resetBtn.getStyleClass().add(Styles.SMALL);
            resetBtn.setOnAction(e -> {
                EnumSet<Permission> def = Permission.defaultFor(role);
                workingBitmasks.put(role, def);
                origWorkingBitmasks.put(role, EnumSet.copyOf(def));
                resetRoleOnServer(role);
                permRightArea.getChildren().setAll(buildBaseRolePanel(role));
                ctx.refreshSaveButton();
            });
            header.getChildren().add(resetBtn);
        }

        VBox content = new VBox(16);
        content.setPadding(new Insets(16, 20, 20, 20));

        if (role == ServerSummary.Role.OWNER) {
            Label icon = new Label("👑");
            icon.setStyle("-fx-font-size: 28px;");
            Label msg = new Label("The server owner has all permissions and they cannot be changed.");
            msg.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-fg-muted;");
            msg.setWrapText(true);
            VBox center = new VBox(10, icon, msg);
            center.setAlignment(Pos.CENTER);
            center.setPadding(new Insets(40, 20, 0, 20));
            content.getChildren().add(center);
        } else {
            EnumSet<Permission> currentPerms = workingBitmasks.getOrDefault(role, Permission.defaultFor(role));
            boolean canEdit = canEditPerms();
            EnumSet<Permission> callerBasePerms = callerIsOwner ? EnumSet.allOf(Permission.class)
                    : workingBitmasks.getOrDefault(serverDetails.getRole(), Permission.defaultFor(serverDetails.getRole()));

            if (isOwnRole) {
                Label notice = new Label("You cannot edit your own base role permissions.");
                notice.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-subtle;");
                notice.setWrapText(true);
                content.getChildren().add(notice);
            }

            for (Map.Entry<String, List<Permission>> entry : PERM_CATEGORIES.entrySet()) {
                VBox catBox = new VBox(6);
                Label catLabel = new Label(entry.getKey());
                catLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle; -fx-padding: 0 0 2px 0;");
                catBox.getChildren().add(catLabel);

                for (Permission perm : entry.getValue()) {
                    boolean locked = isOwnRole
                            || (!callerIsOwner && !callerBasePerms.contains(perm))
                            || (perm == Permission.VIEW_SERVER_SETTINGS && !callerIsOwner && roleRank(role) > roleRank(serverDetails.getRole()));
                    HBox row = buildToggleRow(perm, currentPerms, canEdit && !locked, (isOn) -> {
                        EnumSet<Permission> perms = workingBitmasks.computeIfAbsent(role,
                                r -> Permission.defaultFor(r));
                        if (isOn) perms.add(perm); else perms.remove(perm);
                        ctx.refreshSaveButton();
                    });
                    catBox.getChildren().add(row);
                }
                content.getChildren().add(catBox);
            }
        }

        ScrollPane scroll = wrapScroll(content);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox outer = new VBox(0, header, scroll);
        VBox.setVgrow(outer, Priority.ALWAYS);
        return outer;
    }

    // ── Custom role panel ──────────────────────────────────────────────────────

    private VBox buildCustomRolePanel(CustomRoleSummary cr) {
        boolean canEdit = canEditPerms();
        boolean callerIsOwner = serverDetails.getRole() == ServerSummary.Role.OWNER;
        EnumSet<Permission> callerBasePerms = callerIsOwner ? EnumSet.allOf(Permission.class)
                : workingBitmasks.getOrDefault(serverDetails.getRole(), Permission.defaultFor(serverDetails.getRole()));
        EnumSet<Permission> currentPerms = customWorkingBitmasks.getOrDefault(cr.getRoleId(), Permission.fromNames(cr.getPermissions()));

        TextField nameField = new TextField(cr.getRoleName());
        nameField.setMaxWidth(Double.MAX_VALUE);
        nameField.setEditable(canEdit);
        if (!canEdit) nameField.setStyle("-fx-opacity:0.75;");

        ColorPickerPopup colorPicker = new ColorPickerPopup(safeColor(cr.getColor(), "#99AAB5"));

        Rectangle colorSwatch = new Rectangle(28, 28);
        colorSwatch.setArcWidth(6);
        colorSwatch.setArcHeight(6);
        colorSwatch.fillProperty().bind(colorPicker.colorProperty());

        if (canEdit) {
            colorSwatch.setStyle("-fx-cursor: hand;");
            colorSwatch.setOnMouseClicked(e -> {
                if (colorPicker.isShowing()) colorPicker.hide();
                else colorPicker.show(colorSwatch);
            });
        } else {
            colorSwatch.setOpacity(0.6);
        }

        Label roleTitle = new Label(cr.getRoleName());
        roleTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        HBox header = new HBox(8, roleTitle, headerSpacer);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(14, 16, 12, 20));
        header.setStyle(
                "-fx-border-color: transparent transparent -color-border-default transparent;" +
                "-fx-border-width: 0 0 1 0;");

        if (canEdit) {
            boolean canDeleteThisRole = callerIsOwner || callerBasePerms.containsAll(currentPerms);

            if (canDeleteThisRole) {
                Button deleteBtn = new Button(null, new FontIcon(MaterialDesignD.DELETE_OUTLINE));
                deleteBtn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT, Styles.SMALL);
                deleteBtn.setFocusTraversable(false);
                deleteBtn.setTooltip(new Tooltip("Delete role"));
                deleteBtn.setOnAction(e -> App.showModal(new ConfirmationModal(
                        "Delete Role",
                        "Delete \"" + cr.getRoleName() + "\"? This cannot be undone.",
                        new FontIcon(MaterialDesignD.DELETE_ALERT),
                        () -> deleteCustomRole(cr))));
                header.getChildren().add(deleteBtn);
            }

            Button saveBtn = new Button(null, new FontIcon(MaterialDesignC.CHECK));
            saveBtn.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT, Styles.SMALL, Styles.ACCENT);
            saveBtn.setFocusTraversable(false);
            saveBtn.setTooltip(new Tooltip("Save role"));
            saveBtn.setOnAction(e -> {
                Color c = colorPicker.getColor();
                String hex = String.format("#%02X%02X%02X",
                        (int) (c.getRed() * 255),
                        (int) (c.getGreen() * 255),
                        (int) (c.getBlue() * 255));
                saveCustomRole(cr, nameField.getText().trim(), hex,
                        customWorkingBitmasks.getOrDefault(cr.getRoleId(), Permission.fromNames(cr.getPermissions())));
            });

            header.getChildren().add(saveBtn);
        }

        VBox content = new VBox(16);
        content.setPadding(new Insets(16, 20, 20, 20));

        content.getChildren().add(new VBox(4, new Label("Role Name") {{
            setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle;");
        }}, nameField));
        content.getChildren().add(new VBox(4, new Label("Color") {{
            setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle;");
        }}, colorSwatch));
        content.getChildren().add(new Separator(Orientation.HORIZONTAL));

        for (Map.Entry<String, List<Permission>> entry : PERM_CATEGORIES.entrySet()) {
            VBox catBox = new VBox(6);
            Label catLabel = new Label(entry.getKey());
            catLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: -color-fg-subtle; -fx-padding: 0 0 2px 0;");
            catBox.getChildren().add(catLabel);
            for (Permission perm : entry.getValue()) {
                boolean locked = !callerIsOwner && !callerBasePerms.contains(perm);
                HBox row = buildToggleRow(perm, currentPerms, canEdit && !locked, (isOn) -> {
                    EnumSet<Permission> perms = customWorkingBitmasks.computeIfAbsent(cr.getRoleId(),
                            id -> Permission.fromNames(cr.getPermissions()));
                    if (isOn) perms.add(perm); else perms.remove(perm);
                });
                catBox.getChildren().add(row);
            }
            content.getChildren().add(catBox);
        }

        ScrollPane scroll = wrapScroll(content);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox outer = new VBox(0, header, scroll);
        VBox.setVgrow(outer, Priority.ALWAYS);
        return outer;
    }

    // ── Create custom role form ────────────────────────────────────────────────

    private void showCreateCustomRoleForm() {
        if (permSelectedRoleBtn != null) setRoleNavInactive(permSelectedRoleBtn);
        permSelectedRoleBtn = null;

        VBox content = new VBox(16);
        content.setPadding(new Insets(16, 20, 20, 20));

        Label title = new Label("New Custom Role");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        TextField nameField = new TextField();
        nameField.setMaxWidth(Double.MAX_VALUE);
        nameField.setPromptText("e.g. VIP, Streamer");

        ColorPickerPopup colorPicker = new ColorPickerPopup(Color.web("#99AAB5"));

        Rectangle colorSwatch = new Rectangle(28, 28);
        colorSwatch.setArcWidth(6);
        colorSwatch.setArcHeight(6);
        colorSwatch.setStyle("-fx-cursor: hand;");
        colorSwatch.fillProperty().bind(colorPicker.colorProperty());
        colorSwatch.setOnMouseClicked(e -> {
            if (colorPicker.isShowing()) colorPicker.hide();
            else colorPicker.show(colorSwatch);
        });

        ComboBox<String> baseRoleBox = new ComboBox<>();
        int callerRank = roleRank(serverDetails.getRole());
        List.of(ServerSummary.Role.ADMIN, ServerSummary.Role.MODERATOR, ServerSummary.Role.MEMBER).stream()
                .filter(r -> roleRank(r) <= callerRank)
                .map(Enum::name)
                .forEach(baseRoleBox.getItems()::add);
        baseRoleBox.getSelectionModel().selectLast();
        baseRoleBox.setMaxWidth(Double.MAX_VALUE);

        Button createBtn = new Button("Create Role");
        createBtn.getStyleClass().addAll(Styles.ACCENT, Styles.SMALL);
        createBtn.setFocusTraversable(false);
        createBtn.setOnAction(e -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) {
                nameField.requestFocus();
                return;
            }
            Color c = colorPicker.getColor();
            String hex = String.format("#%02X%02X%02X",
                    (int) (c.getRed() * 255),
                    (int) (c.getGreen() * 255),
                    (int) (c.getBlue() * 255));
            createCustomRole(name, hex, baseRoleBox.getValue());
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add(Styles.SMALL);
        cancelBtn.setFocusTraversable(false);
        cancelBtn.setOnAction(e -> {
            colorPicker.hide();
            if (permRoleListBox.getChildren().size() > 1) {
                VBox firstBase = (VBox) permRoleListBox.getChildren().get(1);
                ServerSummary.Role firstVisible = java.util.Arrays.stream(new ServerSummary.Role[]{
                                ServerSummary.Role.ADMIN, ServerSummary.Role.MODERATOR, ServerSummary.Role.MEMBER})
                        .filter(r -> roleRank(r) < callerRank)
                        .findFirst().orElse(null);
                if (firstVisible != null) selectRole(firstBase, firstVisible);
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        content.getChildren().addAll(
                title,
                new VBox(4, sectionLabel("Role Name"), nameField),
                new VBox(4, sectionLabel("Color"), colorSwatch),
                new VBox(4, sectionLabel("Base Role"), baseRoleBox),
                new HBox(8, spacer, cancelBtn, createBtn));

        ScrollPane scroll = wrapScroll(content);
        permRightArea.getChildren().setAll(scroll);
    }

    private Color safeColor(String hex, String fallback) {
        try {
            return Color.web(hex != null ? hex : fallback);
        } catch (IllegalArgumentException e) {
            return Color.web(fallback);
        }
    }

    // ── Toggle row builder ─────────────────────────────────────────────────────

    @FunctionalInterface
    private interface ToggleChangeHandler {
        void onChange(boolean isOn);
    }

    private HBox buildToggleRow(Permission perm, Set<Permission> current, boolean editable,
                                ToggleChangeHandler onChange) {
        ToggleSwitch ts = new ToggleSwitch();
        ts.setFocusTraversable(false);

        boolean[] updating = {true};
        ts.setSelected(current.contains(perm));
        updating[0] = false;

        ts.setDisable(!editable);

        ts.selectedProperty().addListener((obs, wasOn, isOn) -> {
            if (updating[0]) return;
            onChange.onChange(isOn);
        });

        Label name = new Label(perm.getDisplayName());
        name.setStyle("-fx-font-size: 12px;");

        Tooltip tip = new Tooltip(perm.getDescription());
        Tooltip.install(name, tip);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(10, name, spacer, ts);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 0, 4, 0));
        return row;
    }

    // ── API calls (background threads) ─────────────────────────────────────────

    private void saveRolePermission(ServerSummary.Role role, Set<Permission> permissions) {
        App.getServices().getExecutor().submit(() -> {
            try {
                App.getServices().hub().getHubPermissionService()
                        .updateRolePermission(serverDetails.getServerId(), role.name(), permissions);
            } catch (Exception ex) {
                Platform.runLater(() ->
                        new CustomNotification("Save Failed", HttpStatusException.extractMessage(ex),
                                new FontIcon(MaterialDesignL.LAN_DISCONNECT))
                                .showNotification());
            }
        });
    }

    private void resetRoleOnServer(ServerSummary.Role role) {
        App.getServices().getExecutor().submit(() -> {
            try {
                App.getServices().hub().getHubPermissionService()
                        .resetRoleToDefault(serverDetails.getServerId(), role.name());
            } catch (Exception ex) {
                Platform.runLater(() ->
                        new CustomNotification("Reset Failed", HttpStatusException.extractMessage(ex),
                                new FontIcon(MaterialDesignL.LAN_DISCONNECT))
                                .showNotification());
            }
        });
    }

    private void saveCustomRole(CustomRoleSummary cr, String name, String color, Set<Permission> permissions) {
        App.getServices().getExecutor().submit(() -> {
            try {
                App.getServices().hub().getHubPermissionService()
                        .updateCustomRole(serverDetails.getServerId(), cr.getRoleId(), name, color, permissions);
                Platform.runLater(() -> {
                    new CustomNotification("Role Saved", "Role settings have been updated.",
                            new FontIcon(MaterialDesignC.CHECK_CIRCLE_OUTLINE))
                            .showNotification();
                    updateCustomRoleNavBtn(cr, name, color);
                });
            } catch (Exception ex) {
                Platform.runLater(() ->
                        new CustomNotification("Save Failed", HttpStatusException.extractMessage(ex),
                                new FontIcon(MaterialDesignL.LAN_DISCONNECT))
                                .showNotification());
            }
        });
    }

    private void updateCustomRoleNavBtn(CustomRoleSummary cr, String name, String color) {
        if (permRoleListBox == null || permSelectedRoleBtn == null) return;
        int idx = permRoleListBox.getChildren().indexOf(permSelectedRoleBtn);
        if (idx < 0) return;
        VBox newBtn = buildCustomRoleBtn(name, color);
        newBtn.setOnMouseClicked(e -> selectCustomRole(newBtn, cr));
        setRoleNavActive(newBtn);
        permRoleListBox.getChildren().set(idx, newBtn);
        permSelectedRoleBtn = newBtn;
    }

    private void deleteCustomRole(CustomRoleSummary cr) {
        App.getServices().getExecutor().submit(() -> {
            try {
                App.getServices().hub().getHubPermissionService()
                        .deleteCustomRole(serverDetails.getServerId(), cr.getRoleId());
                Platform.runLater(() -> {
                    new CustomNotification("Role Deleted", "The role has been removed.",
                            new FontIcon(MaterialDesignC.CHECK_CIRCLE_OUTLINE))
                            .showNotification();
                    localPermManager.removeCustomRole(cr.getRoleId());
                    customWorkingBitmasks.remove(cr.getRoleId());
                    rebuildAndShowPermissionsPane();
                });
            } catch (Exception ex) {
                Platform.runLater(() ->
                        new CustomNotification("Delete Failed", HttpStatusException.extractMessage(ex),
                                new FontIcon(MaterialDesignL.LAN_DISCONNECT))
                                .showNotification());
            }
        });
    }

    private void createCustomRole(String name, String color, String baseRole) {
        EnumSet<Permission> basePerms = Permission.defaultFor(ServerSummary.Role.valueOf(baseRole));
        App.getServices().getExecutor().submit(() -> {
            try {
                App.getServices().hub().getHubPermissionService()
                        .createCustomRole(serverDetails.getServerId(), name, color, basePerms);
                Platform.runLater(() -> {
                    new CustomNotification("Role Created", "The new role has been added.",
                            new FontIcon(MaterialDesignC.CHECK_CIRCLE_OUTLINE))
                            .showNotification();
                    ctx.reloadPermissions();
                });
            } catch (Exception ex) {
                Platform.runLater(() ->
                        new CustomNotification("Create Failed", HttpStatusException.extractMessage(ex),
                                new FontIcon(MaterialDesignL.LAN_DISCONNECT))
                                .showNotification());
            }
        });
    }

    private void handleSavePermissions() {
        for (ServerSummary.Role role : List.of(
                ServerSummary.Role.ADMIN, ServerSummary.Role.MODERATOR, ServerSummary.Role.MEMBER)) {
            EnumSet<Permission> cur = workingBitmasks.getOrDefault(role, Permission.defaultFor(role));
            EnumSet<Permission> orig = origWorkingBitmasks.getOrDefault(role, Permission.defaultFor(role));
            if (!cur.equals(orig)) saveRolePermission(role, cur);
        }
        workingBitmasks.forEach((role, perms) -> origWorkingBitmasks.put(role, EnumSet.copyOf(perms)));
        ctx.refreshSaveButton();
        new CustomNotification("Permissions Saved", "Server permissions have been updated.",
                new FontIcon(MaterialDesignC.CHECK_CIRCLE_OUTLINE)).showNotification();
    }
}
