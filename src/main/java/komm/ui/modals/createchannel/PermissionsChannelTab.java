package komm.ui.modals.createchannel;

import atlantafx.base.theme.Styles;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import komm.App;
import komm.model.dto.summary.ServerSummary;
import komm.model.permissions.Permission;
import komm.ui.customnodes.CustomNotification;
import komm.ui.utils.IconColorUtil;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * "Permissions" tab: per-role (base + custom) three-state permission overrides for this channel.
 * Available in both create and edit mode — in create mode the overrides are applied right after
 * the channel is created (see {@code CreateChannelModal#applyPendingOverrides}); in edit mode this
 * tab saves independently via the shared footer Save button.
 */
public class PermissionsChannelTab implements ChannelSettingsTab {

    private final ChannelSettingsContext ctx;

    private VBox permRoleListBox;
    private VBox permHeaderArea;
    private VBox permContentArea;
    private VBox permSelectedRoleBtn;
    private int baseRoleNavItemCount = 0;

    private ServerSummary.Role selectedPermRole = ServerSummary.Role.MEMBER;
    private UUID selectedCustomPermRoleId = null;

    private final Map<ServerSummary.Role, Map<Permission, OverrideState>> pendingOverrides
            = new EnumMap<>(ServerSummary.Role.class);
    private Map<ServerSummary.Role, Map<Permission, OverrideState>> origOverrides
            = new EnumMap<>(ServerSummary.Role.class);
    private final Map<UUID, Map<Permission, OverrideState>> pendingCustomRoleOverrides
            = new LinkedHashMap<>();
    private Map<UUID, Map<Permission, OverrideState>> origCustomRoleOverrides
            = new LinkedHashMap<>();

    private boolean permsDirty = false;
    private boolean permsLoaded = false;
    private final VBox pane;

    private final AtomicReference<Map<UUID, Map<Permission, OverrideState>>> loadedCustomRoleOverrides
            = new AtomicReference<>(new LinkedHashMap<>());

    private final Service<Map<ServerSummary.Role, Map<Permission, OverrideState>>> loadPermsService
            = new Service<>() {
        @Override
        protected Task<Map<ServerSummary.Role, Map<Permission, OverrideState>>> createTask() {
            return new Task<>() {
                @Override
                protected Map<ServerSummary.Role, Map<Permission, OverrideState>> call() throws Exception {
                    var svc = App.getServices().installation().getChannelPermissionService();
                    var summary = svc.getChannelPermissions(ctx.editChannel().getChannelId());
                    Map<ServerSummary.Role, Map<Permission, OverrideState>> loaded
                            = new EnumMap<>(ServerSummary.Role.class);
                    Map<UUID, Map<Permission, OverrideState>> customLoaded = new LinkedHashMap<>();
                    if (summary != null && summary.getRoleOverrides() != null) {
                        summary.getRoleOverrides().forEach((roleStr, override) -> {
                            Map<Permission, OverrideState> map = new EnumMap<>(Permission.class);
                            EnumSet<Permission> allowSet = Permission.fromNames(override.getAllowPermissions());
                            EnumSet<Permission> denySet = Permission.fromNames(override.getDenyPermissions());
                            for (Permission perm : ChannelSettingsContext.ALL_CHANNEL_PERMS) {
                                if (allowSet.contains(perm)) map.put(perm, OverrideState.ALLOW);
                                else if (denySet.contains(perm)) map.put(perm, OverrideState.DENY);
                            }
                            if (map.isEmpty()) return;
                            try {
                                loaded.put(ServerSummary.Role.valueOf(roleStr), map);
                            } catch (IllegalArgumentException e) {
                                try { customLoaded.put(UUID.fromString(roleStr), map); }
                                catch (IllegalArgumentException ignored) {}
                            }
                        });
                    }
                    loadedCustomRoleOverrides.set(customLoaded);
                    return loaded;
                }
            };
        }
    };

    private Map<ServerSummary.Role, Map<Permission, OverrideState>> pendingPermsSnapshot;
    private Map<ServerSummary.Role, Map<Permission, OverrideState>> origPermsSnapshot;
    private Map<UUID, Map<Permission, OverrideState>> pendingCustomPermsSnapshot;
    private Map<UUID, Map<Permission, OverrideState>> origCustomPermsSnapshot;

    private final Service<Void> savePermsService = new Service<>() {
        @Override
        protected Task<Void> createTask() {
            final var snap = pendingPermsSnapshot;
            final var origSnap = origPermsSnapshot;
            final var customSnap = pendingCustomPermsSnapshot;
            final var origCustomSnap = origCustomPermsSnapshot;
            final int myRank = roleRank(App.getPermissionManager().getMyRole());
            return new Task<>() {
                @Override
                protected Void call() throws Exception {
                    var svc = App.getServices().installation().getChannelPermissionService();
                    UUID channelId = ctx.editChannel().getChannelId();
                    // Base roles
                    for (ServerSummary.Role role : List.of(
                            ServerSummary.Role.MEMBER,
                            ServerSummary.Role.MODERATOR,
                            ServerSummary.Role.ADMIN)) {
                        if (roleRank(role) >= myRank) continue;
                        Map<Permission, OverrideState> overrideMap = snap.getOrDefault(role, Map.of());
                        Map<Permission, OverrideState> origMap = origSnap.getOrDefault(role, Map.of());
                        boolean changed = false;
                        for (Permission perm : ChannelSettingsContext.ALL_CHANNEL_PERMS) {
                            if (overrideMap.getOrDefault(perm, OverrideState.INHERIT)
                                    != origMap.getOrDefault(perm, OverrideState.INHERIT)) {
                                changed = true; break;
                            }
                        }
                        if (!changed) continue;
                        EnumSet<Permission> allow = EnumSet.noneOf(Permission.class);
                        EnumSet<Permission> deny = EnumSet.noneOf(Permission.class);
                        for (Permission perm : ChannelSettingsContext.ALL_CHANNEL_PERMS) {
                            OverrideState state = overrideMap.getOrDefault(perm, OverrideState.INHERIT);
                            if (state == OverrideState.ALLOW) allow.add(perm);
                            else if (state == OverrideState.DENY) deny.add(perm);
                        }
                        if (allow.isEmpty() && deny.isEmpty()) {
                            try { svc.deleteChannelRolePermission(channelId, role.name()); }
                            catch (Exception ignored) {}
                        } else {
                            svc.updateChannelRolePermission(channelId, role.name(), allow, deny);
                        }
                    }
                    // Custom roles
                    Set<UUID> allCustomIds = new HashSet<>();
                    allCustomIds.addAll(customSnap.keySet());
                    allCustomIds.addAll(origCustomSnap.keySet());
                    for (UUID crId : allCustomIds) {
                        Map<Permission, OverrideState> overrideMap = customSnap.getOrDefault(crId, Map.of());
                        Map<Permission, OverrideState> origMap = origCustomSnap.getOrDefault(crId, Map.of());
                        boolean changed = false;
                        for (Permission perm : ChannelSettingsContext.ALL_CHANNEL_PERMS) {
                            if (overrideMap.getOrDefault(perm, OverrideState.INHERIT)
                                    != origMap.getOrDefault(perm, OverrideState.INHERIT)) {
                                changed = true; break;
                            }
                        }
                        if (!changed) continue;
                        EnumSet<Permission> allow = EnumSet.noneOf(Permission.class);
                        EnumSet<Permission> deny = EnumSet.noneOf(Permission.class);
                        for (Permission perm : ChannelSettingsContext.ALL_CHANNEL_PERMS) {
                            OverrideState state = overrideMap.getOrDefault(perm, OverrideState.INHERIT);
                            if (state == OverrideState.ALLOW) allow.add(perm);
                            else if (state == OverrideState.DENY) deny.add(perm);
                        }
                        if (allow.isEmpty() && deny.isEmpty()) {
                            try { svc.deleteChannelCustomRolePermission(channelId, crId); }
                            catch (Exception ignored) {}
                        } else {
                            svc.updateChannelCustomRolePermission(channelId, crId, allow, deny);
                        }
                    }
                    return null;
                }
            };
        }
    };

    public PermissionsChannelTab(ChannelSettingsContext ctx) {
        this.ctx = ctx;
        this.pane = buildPane();

        if (ctx.isEditMode()) {
            savePermsService.runningProperty().addListener((obs, was, isRunning) -> {
                ctx.setSaving(isRunning);
                ctx.refreshSaveButton();
            });
            savePermsService.setOnSucceeded(e -> {
                origOverrides = deepCopyOverrides(pendingOverrides);
                origCustomRoleOverrides = deepCopyCustomOverrides(pendingCustomRoleOverrides);
                permsDirty = false;
                ctx.refreshSaveButton();
                new CustomNotification("Permissions Saved", "Channel permissions have been updated.",
                        new FontIcon(MaterialDesignC.CHECK_CIRCLE_OUTLINE)).showNotification();
            });
            savePermsService.setOnFailed(e -> ChannelSettingsUi.showSaveError(savePermsService.getException()));
        }
    }

    // ── ChannelSettingsTab ────────────────────────────────────────────────────────

    @Override public String name() { return "Permissions"; }
    @Override public String description() { return "Override role permissions for this channel"; }
    @Override public FontIcon icon() { return new FontIcon(MaterialDesignS.SHIELD_LOCK_OUTLINE); }
    @Override public Node getPane() { return pane; }
    @Override public boolean participatesInSave() { return ctx.isEditMode(); }
    @Override public boolean isDirty() { return permsDirty; }
    @Override public boolean isBusy() { return savePermsService.isRunning() || loadPermsService.isRunning(); }
    @Override public String saveButtonText() { return "Save Permissions"; }
    @Override public void save() { handleSavePermissions(); }

    @Override
    public void onShown() {
        refreshCustomRoleNavItems();
        if (ctx.isEditMode() && !permsLoaded) {
            permsLoaded = true;
            loadEditChannelPermissions();
        }
    }

    // ── Create-mode accessors (read by the shell to apply overrides post-creation) ─

    public Map<ServerSummary.Role, Map<Permission, OverrideState>> getPendingOverrides() { return pendingOverrides; }
    public Map<UUID, Map<Permission, OverrideState>> getPendingCustomRoleOverrides() { return pendingCustomRoleOverrides; }

    // ── Pane ─────────────────────────────────────────────────────────────────────

    private VBox buildPane() {
        permRoleListBox = new VBox(2);
        permRoleListBox.setPrefWidth(200);
        permRoleListBox.setMinWidth(200);
        permRoleListBox.setMaxWidth(200);
        permRoleListBox.setPadding(new Insets(8, 8, 8, 8));

        Label rolesLabel = new Label("ROLES");
        rolesLabel.setStyle(
                "-fx-font-size: 10px; -fx-font-weight: bold;" +
                        "-fx-text-fill: -color-fg-subtle; -fx-padding: 0 0 4px 10px;");
        permRoleListBox.getChildren().add(rolesLabel);

        int myRank = roleRank(App.getPermissionManager().getMyRole());
        VBox firstRoleBtn = null;
        ServerSummary.Role firstRole = null;

        for (ServerSummary.Role role : List.of(
                ServerSummary.Role.ADMIN, ServerSummary.Role.MODERATOR, ServerSummary.Role.MEMBER)) {
            if (roleRank(role) >= myRank) continue;
            VBox btn = buildRoleNavItem(ChannelSettingsUi.capitalize(role.name()));
            btn.setOnMouseClicked(e -> selectPermRole(role, btn));
            permRoleListBox.getChildren().add(btn);
            if (firstRoleBtn == null) {
                firstRoleBtn = btn;
                firstRole = role;
            }
        }

        baseRoleNavItemCount = permRoleListBox.getChildren().size();

        permHeaderArea = new VBox();
        permHeaderArea.setPadding(new Insets(12, 16, 0, 16));

        permContentArea = new VBox(2);
        permContentArea.setPadding(new Insets(4, 16, 12, 16));

        ScrollPane scroll = new ScrollPane(permContentArea);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox rightSide = new VBox(0, permHeaderArea, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        HBox.setHgrow(rightSide, Priority.ALWAYS);

        Separator sep = new Separator(Orientation.VERTICAL);
        sep.setPadding(Insets.EMPTY);

        HBox body = new HBox(0, permRoleListBox, sep, rightSide);
        VBox.setVgrow(body, Priority.ALWAYS);

        VBox pane = new VBox(body);
        pane.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(pane, Priority.ALWAYS);

        if (firstRoleBtn != null && firstRole != null) selectPermRole(firstRole, firstRoleBtn);
        return pane;
    }

    private void refreshCustomRoleNavItems() {
        if (permRoleListBox == null) return;
        // Clear everything past the base role items
        while (permRoleListBox.getChildren().size() > baseRoleNavItemCount)
            permRoleListBox.getChildren().remove(baseRoleNavItemCount);

        List<komm.model.dto.summary.CustomRoleSummary> customRoles =
                App.getPermissionManager().getCustomRoles();
        if (customRoles.isEmpty()) return;

        Label customLabel = new Label("CUSTOM ROLES");
        customLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: bold;" +
                "-fx-text-fill: -color-fg-subtle; -fx-padding: 8px 0 4px 10px;");
        permRoleListBox.getChildren().add(customLabel);

        for (komm.model.dto.summary.CustomRoleSummary cr : customRoles) {
            VBox btn = buildCustomRoleNavItem(cr.getRoleName(), cr.getColor());
            btn.setOnMouseClicked(e -> selectCustomPermRole(cr.getRoleId(), btn));
            permRoleListBox.getChildren().add(btn);
        }
    }

    private int roleRank(ServerSummary.Role role) {
        if (role == null) return 0;
        return switch (role) {
            case MEMBER -> 0;
            case MODERATOR -> 1;
            case ADMIN -> 2;
            case OWNER -> 3;
        };
    }

    private VBox buildRoleNavItem(String name) {
        Label lbl = new Label(name);
        lbl.getStyleClass().add("nav-label");
        VBox item = new VBox(lbl);
        item.setAlignment(Pos.CENTER_LEFT);
        item.setPadding(new Insets(8, 12, 8, 12));
        item.getStyleClass().add("nav-item");
        return item;
    }

    private VBox buildCustomRoleNavItem(String name, String color) {
        FontIcon icon = IconColorUtil.roleColorIcon(color, 20);
        Label lbl = new Label(name);
        lbl.getStyleClass().add("nav-label");
        HBox row = new HBox(6, icon, lbl);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox item = new VBox(row);
        item.setAlignment(Pos.CENTER);
        item.setPadding(new Insets(8, 12, 8, 12));
        item.getStyleClass().add("nav-item");
        return item;
    }

    private void selectCustomPermRole(UUID roleId, VBox btn) {
        if (permSelectedRoleBtn != null) {
            permSelectedRoleBtn.getStyleClass().remove("nav-active");
            if (!permSelectedRoleBtn.getStyleClass().contains("nav-inactive"))
                permSelectedRoleBtn.getStyleClass().add("nav-inactive");
        }
        btn.getStyleClass().remove("nav-inactive");
        if (!btn.getStyleClass().contains("nav-active"))
            btn.getStyleClass().add("nav-active");
        permSelectedRoleBtn = btn;
        selectedPermRole = null;
        selectedCustomPermRoleId = roleId;
        refreshPermContent();
    }

    private void selectPermRole(ServerSummary.Role role, VBox btn) {
        if (permSelectedRoleBtn != null) {
            permSelectedRoleBtn.getStyleClass().remove("nav-active");
            if (!permSelectedRoleBtn.getStyleClass().contains("nav-inactive"))
                permSelectedRoleBtn.getStyleClass().add("nav-inactive");
        }
        btn.getStyleClass().remove("nav-inactive");
        if (!btn.getStyleClass().contains("nav-active"))
            btn.getStyleClass().add("nav-active");
        permSelectedRoleBtn = btn;
        selectedPermRole = role;
        selectedCustomPermRoleId = null;
        refreshPermContent();
    }

    private void refreshPermContent() {
        if (permContentArea == null) return;
        permContentArea.getChildren().clear();

        Map<Permission, OverrideState> roleOverrides;
        if (selectedCustomPermRoleId != null) {
            roleOverrides = pendingCustomRoleOverrides.computeIfAbsent(
                    selectedCustomPermRoleId, k -> new EnumMap<>(Permission.class));
        } else {
            roleOverrides = pendingOverrides.computeIfAbsent(
                    selectedPermRole, k -> new EnumMap<>(Permission.class));
        }

        if (permHeaderArea != null) {
            permHeaderArea.getChildren().clear();
            Label hint = new Label(
                    "Permissions set to 'Inherit' follow the role's server-level defaults. " +
                            "Use Allow or Deny to explicitly override them for this channel only.");
            hint.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");
            hint.setWrapText(true);
            Separator sep = new Separator(Orientation.HORIZONTAL);
            sep.setPadding(new Insets(8, 0, 4, 0));
            permHeaderArea.getChildren().addAll(hint, sep);
        }

        for (Permission perm : ctx.getActiveChannelPerms()) {
            OverrideState state = roleOverrides.getOrDefault(perm, OverrideState.INHERIT);
            permContentArea.getChildren().add(buildPermRow(perm, state, roleOverrides));
        }
    }

    // ── Three-state permission row ────────────────────────────────────────────

    private HBox buildPermRow(Permission perm, OverrideState currentState,
                              Map<Permission, OverrideState> roleOverrides) {
        Label name = new Label(perm.getDisplayName());
        name.setStyle("-fx-font-size: 12px;");
        Tooltip.install(name, new Tooltip(perm.getDescription()));

        Button inheritBtn = buildSegmentBtn("Inherit", OverrideState.INHERIT, currentState);
        Button allowBtn = buildSegmentBtn("Allow", OverrideState.ALLOW, currentState);
        Button denyBtn = buildSegmentBtn("Deny", OverrideState.DENY, currentState);

        inheritBtn.setStyle(inheritBtn.getStyle() + "-fx-background-radius: 4 0 0 4; -fx-border-radius: 4 0 0 4;");
        allowBtn.setStyle(allowBtn.getStyle() + "-fx-background-radius: 0; -fx-border-radius: 0; -fx-border-left-width: 0;");
        denyBtn.setStyle(denyBtn.getStyle() + "-fx-background-radius: 0 4 4 0; -fx-border-radius: 0 4 4 0; -fx-border-left-width: 0;");

        inheritBtn.setOnAction(e -> applyOverride(perm, OverrideState.INHERIT, roleOverrides));
        allowBtn.setOnAction(e -> applyOverride(perm, OverrideState.ALLOW, roleOverrides));
        denyBtn.setOnAction(e -> applyOverride(perm, OverrideState.DENY, roleOverrides));

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

    private void applyOverride(Permission perm, OverrideState newState,
                               Map<Permission, OverrideState> roleOverrides) {
        OverrideState current = roleOverrides.getOrDefault(perm, OverrideState.INHERIT);
        if (newState == OverrideState.INHERIT || current == newState) {
            roleOverrides.put(perm, OverrideState.INHERIT);
        } else {
            roleOverrides.put(perm, newState);
        }
        if (ctx.isEditMode()) updatePermsDirtyState();
        refreshPermContent();
    }

    // ── Edit-mode dirty state / load / save ─────────────────────────────────────

    private void updatePermsDirtyState() {
        permsDirty = !overridesEqual(pendingOverrides, origOverrides)
                  || !customOverridesEqual(pendingCustomRoleOverrides, origCustomRoleOverrides);
        ctx.refreshSaveButton();
    }

    private boolean overridesEqual(
            Map<ServerSummary.Role, Map<Permission, OverrideState>> a,
            Map<ServerSummary.Role, Map<Permission, OverrideState>> b) {
        Set<ServerSummary.Role> allRoles = new HashSet<>();
        allRoles.addAll(a.keySet());
        allRoles.addAll(b.keySet());
        for (ServerSummary.Role role : allRoles) {
            Map<Permission, OverrideState> aMap = a.getOrDefault(role, Map.of());
            Map<Permission, OverrideState> bMap = b.getOrDefault(role, Map.of());
            for (Permission perm : ChannelSettingsContext.ALL_CHANNEL_PERMS) {
                if (aMap.getOrDefault(perm, OverrideState.INHERIT) != bMap.getOrDefault(perm, OverrideState.INHERIT))
                    return false;
            }
        }
        return true;
    }

    private Map<ServerSummary.Role, Map<Permission, OverrideState>> deepCopyOverrides(
            Map<ServerSummary.Role, Map<Permission, OverrideState>> source) {
        Map<ServerSummary.Role, Map<Permission, OverrideState>> copy = new EnumMap<>(ServerSummary.Role.class);
        source.forEach((role, map) -> copy.put(role, new EnumMap<>(map)));
        return copy;
    }

    private Map<UUID, Map<Permission, OverrideState>> deepCopyCustomOverrides(
            Map<UUID, Map<Permission, OverrideState>> source) {
        Map<UUID, Map<Permission, OverrideState>> copy = new LinkedHashMap<>();
        source.forEach((id, map) -> copy.put(id, new EnumMap<>(map)));
        return copy;
    }

    private boolean customOverridesEqual(
            Map<UUID, Map<Permission, OverrideState>> a,
            Map<UUID, Map<Permission, OverrideState>> b) {
        Set<UUID> allIds = new HashSet<>();
        allIds.addAll(a.keySet());
        allIds.addAll(b.keySet());
        for (UUID id : allIds) {
            Map<Permission, OverrideState> aMap = a.getOrDefault(id, Map.of());
            Map<Permission, OverrideState> bMap = b.getOrDefault(id, Map.of());
            for (Permission perm : ChannelSettingsContext.ALL_CHANNEL_PERMS) {
                if (aMap.getOrDefault(perm, OverrideState.INHERIT)
                        != bMap.getOrDefault(perm, OverrideState.INHERIT))
                    return false;
            }
        }
        return true;
    }

    private void loadEditChannelPermissions() {
        loadPermsService.setOnSucceeded(e -> {
            Map<ServerSummary.Role, Map<Permission, OverrideState>> loaded = loadPermsService.getValue();
            pendingOverrides.clear();
            loaded.forEach((role, map) -> pendingOverrides.put(role, new EnumMap<>(map)));
            origOverrides = deepCopyOverrides(pendingOverrides);

            Map<UUID, Map<Permission, OverrideState>> customLoaded = loadedCustomRoleOverrides.get();
            pendingCustomRoleOverrides.clear();
            customLoaded.forEach((id, map) -> pendingCustomRoleOverrides.put(id, new EnumMap<>(map)));
            origCustomRoleOverrides = deepCopyCustomOverrides(pendingCustomRoleOverrides);

            refreshPermContent();
        });
        loadPermsService.setOnFailed(e -> loadPermsService.getException().printStackTrace());
        loadPermsService.start();
    }

    private void handleSavePermissions() {
        pendingPermsSnapshot = deepCopyOverrides(pendingOverrides);
        origPermsSnapshot = deepCopyOverrides(origOverrides);
        pendingCustomPermsSnapshot = deepCopyCustomOverrides(pendingCustomRoleOverrides);
        origCustomPermsSnapshot = deepCopyCustomOverrides(origCustomRoleOverrides);
        savePermsService.restart();
    }
}
