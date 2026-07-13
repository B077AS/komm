package komm.service;

import komm.api.HttpClientWrapper;
import komm.api.auth.TokenManager;
import komm.model.dto.request.CreateCustomRoleRequest;
import komm.model.dto.request.UpdateCustomRoleRequest;
import komm.model.dto.request.UpdateRolePermissionsRequest;
import komm.model.dto.summary.CustomRoleSummary;
import komm.model.dto.summary.ServerPermissionsSummary;
import komm.model.permissions.Permission;

import java.util.Set;
import java.util.UUID;

public class HubPermissionService {

    private final HttpClientWrapper httpClient;
    private final TokenManager tokenManager;

    public HubPermissionService(HttpClientWrapper httpClient, TokenManager tokenManager) {
        this.httpClient = httpClient;
        this.tokenManager = tokenManager;
    }

    public ServerPermissionsSummary getServerPermissions(UUID serverId) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.get("/api/permissions/" + serverId + "/server",
                        tokenManager.getAccessToken(), ServerPermissionsSummary.class));
    }

    public void updateRolePermission(UUID serverId, String role, Set<Permission> permissions) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.put("/api/permissions/" + serverId + "/roles/" + role,
                    UpdateRolePermissionsRequest.builder()
                            .permissions(Permission.toNames(permissions))
                            .build(),
                    tokenManager.getAccessToken(), Void.class);
            return null;
        });
    }

    public void resetRoleToDefault(UUID serverId, String role) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.post("/api/permissions/" + serverId + "/roles/" + role + "/reset",
                    null, tokenManager.getAccessToken(), Void.class);
            return null;
        });
    }

    public CustomRoleSummary createCustomRole(UUID serverId, String roleName, String color, Set<Permission> permissions) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.post("/api/permissions/" + serverId + "/custom-roles",
                        CreateCustomRoleRequest.builder()
                                .roleName(roleName)
                                .color(color)
                                .permissions(Permission.toNames(permissions))
                                .build(),
                        tokenManager.getAccessToken(), CustomRoleSummary.class, 201));
    }

    public CustomRoleSummary updateCustomRole(UUID serverId, UUID roleId, String roleName, String color, Set<Permission> permissions) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.put("/api/permissions/" + serverId + "/custom-roles/" + roleId,
                        UpdateCustomRoleRequest.builder()
                                .roleName(roleName)
                                .color(color)
                                .permissions(Permission.toNames(permissions))
                                .build(),
                        tokenManager.getAccessToken(), CustomRoleSummary.class));
    }

    public void deleteCustomRole(UUID serverId, UUID roleId) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.delete("/api/permissions/" + serverId + "/custom-roles/" + roleId,
                    tokenManager.getAccessToken());
            return null;
        });
    }
}
