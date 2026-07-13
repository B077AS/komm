package komm.service;

import komm.api.HttpClientWrapper;
import komm.api.auth.TokenManager;
import komm.model.dto.request.ChangeBaseRoleRequest;
import komm.model.dto.request.UpdateServerSettingsRequest;
import komm.model.dto.summary.ServerMemberSummary;
import komm.model.dto.summary.ServerPermissionsSummary;
import komm.model.permissions.Permission;

import java.util.Set;
import java.util.UUID;

public class InstallationPermissionService {

    private final HttpClientWrapper httpClient;
    private final TokenManager tokenManager;

    public InstallationPermissionService(HttpClientWrapper httpClient, TokenManager tokenManager) {
        this.httpClient = httpClient;
        this.tokenManager = tokenManager;
    }

    public ServerPermissionsSummary getServerPermissions() throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.get("/api/permissions/server",
                        tokenManager.getAccessToken(), ServerPermissionsSummary.class));
    }

    public void assignCustomRole(UUID roleId, UUID targetUserId) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.post("/api/permissions/server/custom-roles/" + roleId
                    + "/members/" + targetUserId,
                    null, tokenManager.getAccessToken(), Void.class, 200);
            return null;
        });
    }

    public void removeCustomRole(UUID roleId, UUID targetUserId) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.delete("/api/permissions/server/custom-roles/" + roleId
                    + "/members/" + targetUserId,
                    tokenManager.getAccessToken());
            return null;
        });
    }

    public ServerMemberSummary getMember(UUID targetUserId) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.get("/api/permissions/server/members/" + targetUserId,
                        tokenManager.getAccessToken(), ServerMemberSummary.class));
    }

    public void updateServerSettings(int defaultChannelPanelWidth) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.put("/api/server/settings",
                    UpdateServerSettingsRequest.builder()
                            .defaultChannelPanelWidth(defaultChannelPanelWidth)
                            .build(),
                    tokenManager.getAccessToken(), Void.class);
            return null;
        });
    }

    public void changeBaseRole(UUID targetUserId, String newRole) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.put("/api/permissions/server/members/" + targetUserId + "/role",
                    ChangeBaseRoleRequest.builder()
                            .role(newRole)
                            .build(),
                    tokenManager.getAccessToken(), Void.class);
            return null;
        });
    }
}
