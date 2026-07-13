package komm.service;

import com.google.gson.reflect.TypeToken;

import komm.api.HttpClientWrapper;
import komm.api.auth.TokenManager;
import komm.model.dto.request.ServerCreateRequest;
import komm.model.dto.request.ServerUpdateRequest;
import komm.model.dto.response.SuccessResponse;
import komm.model.dto.summary.InstallationSummary;
import komm.model.dto.summary.ServerSummary;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;

public class ServerService {
    private final HttpClientWrapper httpClient;
    private final TokenManager tokenManager;

    public ServerService(HttpClientWrapper httpClient, TokenManager tokenManager) {
        this.httpClient = httpClient;
        this.tokenManager = tokenManager;
    }

    /**
     * Get all servers the current user is a member of
     */
    public Map<UUID, ServerSummary> getUserServers() throws Exception {
        return tokenManager.executeWithRetry(() -> {
            Type mapType = new TypeToken<Map<UUID, ServerSummary>>(){}.getType();
            return httpClient.getWithType("/api/servers/list", tokenManager.getAccessToken(), mapType);
        });
    }

    /**
     * Create a new server
     */
    public ServerSummary createServer(ServerCreateRequest serverCreateRequest) throws Exception {
        return tokenManager.executeWithRetry(() -> 
            httpClient.post("/api/servers", serverCreateRequest, tokenManager.getAccessToken(), ServerSummary.class, 201)
        );
    }

    /**
     * Request deletion of a server. The hub authorizes (DELETE_SERVER, proxied to the installation),
     * hides it from members' lists and tells the installation to purge its data in the background.
     */
    public void deleteServer(UUID serverId) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.delete("/api/servers/" + serverId, tokenManager.getAccessToken());
            return null;
        });
    }

    /**
     * Update the display order for user's servers
     */
    public boolean updateServerOrder(List<UUID> serverIds) throws Exception {
        return tokenManager.executeWithRetry(() -> {
            httpClient.put("/api/servers/reorder", serverIds, tokenManager.getAccessToken(), Void.class);
            return true;
        });
    }

    /**
     * Update an existing server (owner only)
     */
    public SuccessResponse updateServer(ServerUpdateRequest request) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.put("/api/servers/" + request.getServerId(), request,
                        tokenManager.getAccessToken(), SuccessResponse.class)
        );
    }

    /**
     * Update channel notification settings for the current user on a specific server
     */
    public void updateChannelNotifications(UUID serverId, boolean enabled) throws Exception {
        tokenManager.executeWithRetry(() -> {
            Map<String, Boolean> body = new HashMap<>();
            body.put("channelNotificationsEnabled", enabled);
            httpClient.patch("/api/servers/" + serverId + "/notifications", body,
                    tokenManager.getAccessToken(), Void.class);
            return null;
        });
    }

    public void leaveServer(UUID serverId) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.delete("/api/servers/" + serverId + "/leave", tokenManager.getAccessToken());
            return null;
        });
    }

    public InstallationSummary.InstallationStatus getServerStatus(UUID serverId) throws Exception {
        return tokenManager.executeWithRetry(() -> {
            String raw = httpClient.get("/api/servers/" + serverId + "/status",
                    tokenManager.getAccessToken(), String.class);
            try {
                return InstallationSummary.InstallationStatus.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return InstallationSummary.InstallationStatus.UNKNOWN;
            }
        });
    }

    /**
     * Request a one-time ticket to connect to the installation hosting this server
     */
    public String requestServerTicket(UUID serverId) throws Exception {
        SuccessResponse response = tokenManager.executeWithRetry(() ->
                httpClient.post("/api/servers/" + serverId + "/ticket", null,
                        tokenManager.getAccessToken(), SuccessResponse.class, 200)
        );
        return (String) response.getData();
    }
}