package komm.service;

import com.google.gson.reflect.TypeToken;

import komm.api.HttpClientWrapper;
import komm.api.auth.TokenManager;
import komm.model.dto.request.ChannelCreateRequest;
import komm.model.dto.request.ChannelUpdateRequest;
import komm.model.dto.summary.ChannelSummary;
import komm.websocket.messages.UserSessionEntry;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ChannelService {
    private final HttpClientWrapper httpClient;
    private final TokenManager tokenManager;

    public ChannelService(HttpClientWrapper httpClient, TokenManager tokenManager) {
        this.httpClient = httpClient;
        this.tokenManager = tokenManager;
    }

    /**
     * Get all channels for a specific server
     */
    public Map<UUID, ChannelSummary> getChannels() throws Exception {

        return tokenManager.executeWithRetry(() -> {
            String endpoint = "/api/channels/list";
            Type channelMapType = new TypeToken<HashMap<UUID, ChannelSummary>>() {
            }.getType();
            return httpClient.getWithType(endpoint, tokenManager.getAccessToken(), channelMapType);
        });
    }

    public ChannelSummary createChannel(ChannelCreateRequest request) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.post("/api/channels", request, tokenManager.getAccessToken(), ChannelSummary.class));
    }

    public ChannelSummary updateChannel(UUID channelId, ChannelUpdateRequest request) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.put("/api/channels/" + channelId, request, tokenManager.getAccessToken(), ChannelSummary.class));
    }

    public void reorderChannels(List<UUID> channelIds) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.put("/api/channels/reorder", channelIds, tokenManager.getAccessToken(), Void.class);
            return null;
        });
    }

    public void deleteChannel(UUID channelId) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.delete("/api/channels/" + channelId, tokenManager.getAccessToken());
            return null;
        });
    }

    public void markChannelRead(UUID channelId) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.put("/api/channels/" + channelId + "/read", null, tokenManager.getAccessToken(), null);
            return null;
        });
    }

    /**
     * Get all users currently connected to a specific channel
     */
    public List<UserSessionEntry> getConnectedUsers(UUID channelId) throws Exception {
        if (channelId == null) {
            throw new IllegalArgumentException("serverId and channelId cannot be null");
        }

        return tokenManager.executeWithRetry(() -> {
            String endpoint = "/api/channels/" + channelId + "/users";
            Type listType = new TypeToken<List<UserSessionEntry>>() {}.getType();
            return httpClient.getWithType(endpoint, tokenManager.getAccessToken(), listType);
        });
    }
}