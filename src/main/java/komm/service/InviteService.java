package komm.service;

import komm.api.HttpClientWrapper;
import komm.api.auth.TokenManager;
import komm.model.dto.request.CreateInviteRequest;
import komm.model.dto.response.InviteLinkResponse;
import komm.model.dto.response.InviteSummary;
import komm.model.dto.response.SuccessResponse;

import java.util.List;
import java.util.UUID;

public class InviteService {

    private final HttpClientWrapper httpClient;
    private final TokenManager tokenManager;

    public InviteService(HttpClientWrapper httpClient, TokenManager tokenManager) {
        this.httpClient = httpClient;
        this.tokenManager = tokenManager;
    }

    public InviteLinkResponse createInvite(UUID serverId, Integer expiresInHours) throws Exception {
        CreateInviteRequest req = CreateInviteRequest.builder()
                .serverId(serverId)
                .expiresInHours(expiresInHours)
                .build();
        return tokenManager.executeWithRetry(() ->
                httpClient.post("/api/invites", req, tokenManager.getAccessToken(),
                        InviteLinkResponse.class, 201)
        );
    }

    public InviteLinkResponse getInviteInfo(String code) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.get("/api/invites/" + code + "/info", tokenManager.getAccessToken(),
                        InviteLinkResponse.class)
        );
    }

    public SuccessResponse joinViaInvite(String code) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.post("/api/invites/" + code + "/join", null,
                        tokenManager.getAccessToken(), SuccessResponse.class)
        );
    }

    public List<InviteSummary> getServerInvites(UUID serverId) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.getList("/api/invites/server/" + serverId,
                        tokenManager.getAccessToken(), InviteSummary.class)
        );
    }

    public void deleteInvite(UUID inviteLinkId) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.delete("/api/invites/" + inviteLinkId, tokenManager.getAccessToken());
            return null;
        });
    }
}
