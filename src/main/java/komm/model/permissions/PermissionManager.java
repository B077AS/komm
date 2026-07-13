package komm.model.permissions;

import komm.model.dto.summary.ChannelPermissionsSummary;
import komm.model.dto.summary.ChannelUserPermissionSummary;
import komm.model.dto.summary.CustomRoleSummary;
import komm.model.dto.summary.ServerPermissionsSummary;
import komm.model.dto.summary.ServerSummary;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class PermissionManager {

    private record ChannelOverride(EnumSet<Permission> allow, EnumSet<Permission> deny) {}

    private volatile ServerSummary.Role myRole = null;

    private final Map<String, EnumSet<Permission>> serverRolePerms = new ConcurrentHashMap<>();
    private final List<CustomRoleSummary> customRoles = new ArrayList<>();
    private final Set<UUID> myCustomRoleIds = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Map<String, ChannelOverride>> channelOverrides = new ConcurrentHashMap<>();
    private final Map<UUID, ChannelOverride> myChannelUserOverrides = new ConcurrentHashMap<>();

    // ── Population ────────────────────────────────────────────────────────────

    public void loadDefaults(ServerSummary.Role role) {
        this.myRole = role;
        serverRolePerms.clear();
        synchronized (this) { customRoles.clear(); }
        myCustomRoleIds.clear();
        channelOverrides.clear();
        myChannelUserOverrides.clear();
        log.debug("PermissionManager loaded with hardcoded defaults for role={}", role);
    }

    public synchronized void load(ServerPermissionsSummary summary, ServerSummary.Role myRole) {
        this.myRole = myRole;
        serverRolePerms.clear();
        if (summary.getRolePermissions() != null) {
            summary.getRolePermissions().forEach((roleName, perms) ->
                    serverRolePerms.put(roleName, Permission.fromNames(perms)));
        }

        customRoles.clear();
        if (summary.getCustomRoles() != null)
            customRoles.addAll(summary.getCustomRoles());

        myCustomRoleIds.clear();
        if (summary.getMyCustomRoleIds() != null)
            myCustomRoleIds.addAll(summary.getMyCustomRoleIds());

        channelOverrides.clear();
        myChannelUserOverrides.clear();
        log.debug("PermissionManager loaded for role={}", myRole);
    }

    public void updateServerPerms(Map<String, List<String>> rolePermissions) {
        serverRolePerms.clear();
        if (rolePermissions != null) {
            rolePermissions.forEach((roleName, perms) ->
                    serverRolePerms.put(roleName, Permission.fromNames(perms)));
        }
        log.debug("PermissionManager: server perms updated");
    }

    public synchronized void upsertCustomRole(CustomRoleSummary role) {
        customRoles.removeIf(r -> r.getRoleId().equals(role.getRoleId()));
        customRoles.add(role);
    }

    public synchronized void removeCustomRole(UUID roleId) {
        customRoles.removeIf(r -> r.getRoleId().equals(roleId));
        myCustomRoleIds.remove(roleId);
    }

    public void assignCustomRole(UUID roleId) { myCustomRoleIds.add(roleId); }
    public void removeCustomRoleAssignment(UUID roleId) { myCustomRoleIds.remove(roleId); }

    public void updateChannelOverrides(ChannelPermissionsSummary summary) {
        if (summary.getRoleOverrides() == null || summary.getRoleOverrides().isEmpty()) {
            channelOverrides.remove(summary.getChannelId());
        } else {
            Map<String, ChannelOverride> converted = new HashMap<>();
            summary.getRoleOverrides().forEach((roleName, override) -> converted.put(roleName,
                    new ChannelOverride(
                            Permission.fromNames(override.getAllowPermissions()),
                            Permission.fromNames(override.getDenyPermissions()))));
            channelOverrides.put(summary.getChannelId(), converted);
        }
        if (summary.getMyUserOverride() != null) {
            updateMyChannelUserOverride(summary.getMyUserOverride());
        }
    }

    public void updateMyRole(ServerSummary.Role newRole) {
        this.myRole = newRole;
        log.debug("PermissionManager: my role updated to {}", newRole);
    }

    public void updateMyChannelUserOverride(ChannelUserPermissionSummary summary) {
        if (summary == null || summary.getChannelId() == null) return;
        List<String> allow = summary.getAllowPermissions();
        List<String> deny  = summary.getDenyPermissions();
        if ((allow == null || allow.isEmpty()) && (deny == null || deny.isEmpty())) {
            myChannelUserOverrides.remove(summary.getChannelId());
        } else {
            myChannelUserOverrides.put(summary.getChannelId(),
                    new ChannelOverride(Permission.fromNames(allow), Permission.fromNames(deny)));
        }
    }

    public void clear() {
        myRole = null;
        serverRolePerms.clear();
        synchronized (this) { customRoles.clear(); }
        myCustomRoleIds.clear();
        channelOverrides.clear();
        myChannelUserOverrides.clear();
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    public boolean has(Permission permission) {
        return effectiveServerPermissions().contains(permission);
    }

    public boolean hasInChannel(UUID channelId, Permission permission) {
        EnumSet<Permission> effective = effectiveServerPermissions();
        Map<String, ChannelOverride> overrides = channelOverrides.get(channelId);
        if (overrides != null && myRole != null) {
            ChannelOverride roleOverride = overrides.get(myRole.name());
            if (roleOverride != null) {
                effective.removeAll(roleOverride.deny());
                effective.addAll(roleOverride.allow());
            }
            for (UUID crId : myCustomRoleIds) {
                ChannelOverride crOverride = overrides.get(crId.toString());
                if (crOverride != null) {
                    effective.removeAll(crOverride.deny());
                    effective.addAll(crOverride.allow());
                }
            }
        }
        ChannelOverride userOverride = myChannelUserOverrides.get(channelId);
        if (userOverride != null) {
            effective.removeAll(userOverride.deny());
            effective.addAll(userOverride.allow());
        }
        return effective.contains(permission);
    }

    public List<String> getEffectivePermissions() {
        return Permission.toNames(effectiveServerPermissions());
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private EnumSet<Permission> effectiveServerPermissions() {
        if (myRole == null) return EnumSet.noneOf(Permission.class);
        if (myRole == ServerSummary.Role.OWNER) return EnumSet.allOf(Permission.class);

        EnumSet<Permission> effective = EnumSet.copyOf(
                serverRolePerms.getOrDefault(myRole.name(), Permission.defaultFor(myRole)));

        synchronized (this) {
            for (CustomRoleSummary cr : customRoles) {
                if (myCustomRoleIds.contains(cr.getRoleId())) {
                    effective.addAll(Permission.fromNames(cr.getPermissions()));
                }
            }
        }
        return effective;
    }

    public ServerSummary.Role getMyRole() { return myRole; }

    public synchronized List<CustomRoleSummary> getCustomRoles() {
        return List.copyOf(customRoles);
    }

    public Map<String, EnumSet<Permission>> getServerRolePerms() {
        return Map.copyOf(serverRolePerms);
    }
}
