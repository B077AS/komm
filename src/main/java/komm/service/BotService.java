package komm.service;

import com.google.gson.reflect.TypeToken;
import komm.api.HttpClientWrapper;
import komm.api.auth.TokenManager;
import komm.model.dto.request.BotCreateRequest;
import komm.model.dto.request.BotUpdateRequest;
import komm.model.dto.summary.BotSummary;

import java.lang.reflect.Type;
import java.util.List;
import java.util.UUID;

public class BotService {
    private final HttpClientWrapper httpClient;
    private final TokenManager tokenManager;

    public BotService(HttpClientWrapper httpClient, TokenManager tokenManager) {
        this.httpClient = httpClient;
        this.tokenManager = tokenManager;
    }

    public List<BotSummary> getBots() throws Exception {
        return tokenManager.executeWithRetry(() -> {
            Type listType = new TypeToken<List<BotSummary>>() {}.getType();
            return httpClient.getWithType("/api/bots", tokenManager.getAccessToken(), listType);
        });
    }

    public BotSummary createBot(BotCreateRequest request) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.post("/api/bots", request, tokenManager.getAccessToken(), BotSummary.class));
    }

    public BotSummary updateBot(UUID botId, BotUpdateRequest request) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.put("/api/bots/" + botId, request, tokenManager.getAccessToken(), BotSummary.class));
    }

    public BotSummary assignChannels(UUID botId, List<UUID> channelIds) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.put("/api/bots/" + botId + "/channels", channelIds, tokenManager.getAccessToken(), BotSummary.class));
    }

    public void deleteBot(UUID botId) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.delete("/api/bots/" + botId, tokenManager.getAccessToken());
            return null;
        });
    }
}
