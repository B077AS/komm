package komm.service;

import komm.api.HttpClientWrapper;
import komm.api.auth.TokenManager;
import komm.model.dto.request.BanRequest;
import komm.model.dto.response.MemberPageResponse;
import komm.model.dto.response.MemberStatusPageResponse;
import komm.model.dto.response.UserStatusDto;
import komm.model.dto.summary.BannedUserSummary;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public class HubModerationService {

    private final HttpClientWrapper httpClient;
    private final TokenManager tokenManager;

    public HubModerationService(HttpClientWrapper httpClient, TokenManager tokenManager) {
        this.httpClient = httpClient;
        this.tokenManager = tokenManager;
    }

    public MemberPageResponse getServerMembersPaged(UUID serverId, int page, int size) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.get("/api/moderation/" + serverId + "/members/paged?page=" + page + "&size=" + size,
                        tokenManager.getAccessToken(), MemberPageResponse.class));
    }

    public List<UserStatusDto> getOnlineServerMembers(UUID serverId) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.getList("/api/moderation/" + serverId + "/members/online",
                        tokenManager.getAccessToken(), UserStatusDto.class));
    }

    public MemberStatusPageResponse getOfflineServerMembers(UUID serverId, int page, int size) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.get("/api/moderation/" + serverId + "/members/offline?page=" + page + "&size=" + size,
                        tokenManager.getAccessToken(), MemberStatusPageResponse.class));
    }

    public List<UserStatusDto> searchMembers(UUID serverId, String query) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        return tokenManager.executeWithRetry(() ->
                httpClient.getList("/api/moderation/" + serverId + "/members/search?q=" + encoded,
                        tokenManager.getAccessToken(), UserStatusDto.class));
    }

    public List<BannedUserSummary> getBannedUsers(UUID serverId) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.getList("/api/moderation/" + serverId + "/bans",
                        tokenManager.getAccessToken(), BannedUserSummary.class));
    }

    public void banUser(UUID serverId, BanRequest request) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.post("/api/moderation/" + serverId + "/bans", request,
                    tokenManager.getAccessToken(), Void.class);
            return null;
        });
    }

    public void unbanUser(UUID serverId, UUID userId) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.delete("/api/moderation/" + serverId + "/bans/" + userId,
                    tokenManager.getAccessToken());
            return null;
        });
    }

    public void kickUser(UUID serverId, UUID userId) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.post("/api/moderation/" + serverId + "/kicks/" + userId,
                    null, tokenManager.getAccessToken(), Void.class);
            return null;
        });
    }
}
