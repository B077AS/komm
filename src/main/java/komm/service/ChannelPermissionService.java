package komm.service;

import komm.api.HttpClientWrapper;
import komm.api.auth.TokenManager;
import komm.model.dto.request.UpdateChannelPermissionsRequest;
import komm.model.dto.summary.ChannelPermissionsSummary;
import komm.model.dto.summary.ChannelUserPermissionSummary;
import komm.model.permissions.Permission;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public class ChannelPermissionService {

    private final HttpClientWrapper httpClient;
    private final TokenManager tokenManager;

    public ChannelPermissionService(HttpClientWrapper httpClient, TokenManager tokenManager) {
        this.httpClient = httpClient;
        this.tokenManager = tokenManager;
    }

    public ChannelPermissionsSummary getChannelPermissions(UUID channelId) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.get("/api/permissions/channels/" + channelId,
                        tokenManager.getAccessToken(), ChannelPermissionsSummary.class));
    }

    public void updateChannelRolePermission(UUID channelId, String role,
                                             Set<Permission> allow, Set<Permission> deny) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.put("/api/permissions/channels/" + channelId + "/roles/" + role,
                    UpdateChannelPermissionsRequest.builder()
                            .allowPermissions(Permission.toNames(allow))
                            .denyPermissions(Permission.toNames(deny))
                            .build(),
                    tokenManager.getAccessToken(), Void.class);
            return null;
        });
    }

    public void deleteChannelRolePermission(UUID channelId, String role) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.delete("/api/permissions/channels/" + channelId + "/roles/" + role,
                    tokenManager.getAccessToken());
            return null;
        });
    }

    public List<ChannelUserPermissionSummary> getChannelUserPermissions(UUID channelId) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.getList("/api/permissions/channels/" + channelId + "/users",
                        tokenManager.getAccessToken(), ChannelUserPermissionSummary.class));
    }

    public void upsertChannelUserPermission(UUID channelId, UUID targetUserId,
                                             Set<Permission> allow, Set<Permission> deny) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.put("/api/permissions/channels/" + channelId + "/users/" + targetUserId,
                    UpdateChannelPermissionsRequest.builder()
                            .allowPermissions(Permission.toNames(allow))
                            .denyPermissions(Permission.toNames(deny))
                            .build(),
                    tokenManager.getAccessToken(), Void.class);
            return null;
        });
    }

    public void updateChannelCustomRolePermission(UUID channelId, UUID customRoleId,
                                                   Set<Permission> allow, Set<Permission> deny) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.put("/api/permissions/channels/" + channelId + "/custom-roles/" + customRoleId,
                    UpdateChannelPermissionsRequest.builder()
                            .allowPermissions(Permission.toNames(allow))
                            .denyPermissions(Permission.toNames(deny))
                            .build(),
                    tokenManager.getAccessToken(), Void.class);
            return null;
        });
    }

    public void deleteChannelCustomRolePermission(UUID channelId, UUID customRoleId) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.delete("/api/permissions/channels/" + channelId + "/custom-roles/" + customRoleId,
                    tokenManager.getAccessToken());
            return null;
        });
    }

    public void deleteChannelUserPermission(UUID channelId, UUID targetUserId) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.delete("/api/permissions/channels/" + channelId + "/users/" + targetUserId,
                    tokenManager.getAccessToken());
            return null;
        });
    }
}
