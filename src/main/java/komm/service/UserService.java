package komm.service;

import komm.api.HttpClientWrapper;
import komm.api.auth.TokenManager;
import komm.model.dto.request.AvStateUpdateRequest;
import komm.model.dto.request.RedeemBadgeTokenRequest;
import komm.model.dto.request.RegisterRequest;
import komm.model.dto.request.StatusUpdateRequest;
import komm.model.dto.request.UserUpdateRequest;
import komm.model.dto.response.RegisterInfoResponse;
import komm.model.dto.response.RegisterResponse;
import komm.model.dto.response.SuccessResponse;
import komm.model.dto.response.UserBatchCacheResponse;
import komm.model.dto.response.UserCacheResponse;
import komm.model.dto.response.UserLookupResponse;
import komm.model.dto.response.UserStatusDto;
import komm.model.dto.summary.BadgeSummary;
import komm.model.dto.summary.ChannelUserSummary;
import komm.model.dto.summary.MainUserSummary;
import komm.model.dto.summary.MainUserSummary.UserStatus;
import komm.model.dto.summary.UserSummary;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class UserService {
    private final HttpClientWrapper httpClient;
    private final TokenManager tokenManager;

    public UserService(HttpClientWrapper httpClient, TokenManager tokenManager) {
        this.httpClient = httpClient;
        this.tokenManager = tokenManager;
    }

    /**
     * Register a new user
     */
    public RegisterResponse register(RegisterRequest request) throws Exception {
        return httpClient.post("/api/auth/register", request, null, RegisterResponse.class, 201);
    }

    /**
     * Ask the hub whether registration currently requires a beta key (closed beta)
     */
    public RegisterInfoResponse getRegisterInfo() throws Exception {
        return httpClient.get("/api/auth/register-info", null, RegisterInfoResponse.class);
    }

    /**
     * Get the currently logged in user's information
     */
    public MainUserSummary getCurrentUser() throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.get("/api/users/me", tokenManager.getAccessToken(), MainUserSummary.class));
    }

    /**
     * Update the currently logged in user's profile
     */
    public MainUserSummary updateUser(UserUpdateRequest updatedUser) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.put("/api/users/me", updatedUser, tokenManager.getAccessToken(), MainUserSummary.class));
    }

    /**
     * Update only the current user's status
     */
    public MainUserSummary updateStatus(UserStatus status) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.patch("/api/users/me/status", new StatusUpdateRequest(status),
                        tokenManager.getAccessToken(), MainUserSummary.class));
    }

    /**
     * Persist the current mic/speaker toggles to the hub (read back at next login)
     */
    public SuccessResponse updateAvState(boolean micEnabled, boolean speakerEnabled) throws Exception {
        AvStateUpdateRequest request = AvStateUpdateRequest.builder()
                .micEnabled(micEnabled)
                .speakerEnabled(speakerEnabled)
                .build();
        return tokenManager.executeWithRetry(() ->
                httpClient.patch("/api/users/me/av-state", request,
                        tokenManager.getAccessToken(), SuccessResponse.class));
    }

    /**
     * Get a user's avatar by their ID
     */
    public UserCacheResponse getUserAvatar(UUID userId) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.get("/api/users/" + userId + "/avatar", tokenManager.getAccessToken(), UserCacheResponse.class));
    }

    /**
     * Get a user's public profile summary (status, dates, friendship).
     * Endpoint: GET /api/users/{userId}/summary
     */
    public UserSummary getUserSummary(UUID userId) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.get("/api/users/" + userId + "/summary",
                        tokenManager.getAccessToken(), UserSummary.class));
    }

    /**
     * Get a user's channel summary by their ID
     */
    public UserLookupResponse getUserByUsername(String username) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.get("/api/users/by-username/" + username,
                        tokenManager.getAccessToken(), UserLookupResponse.class));
    }

    /**
     * Redeem a badge token and get back the awarded badge.
     * Endpoint: POST /api/badges/redeem
     */
    public BadgeSummary redeemBadgeToken(String token) throws Exception {
        RedeemBadgeTokenRequest request = RedeemBadgeTokenRequest.builder()
                .token(token)
                .build();
        return tokenManager.executeWithRetry(() ->
                httpClient.post("/api/badges/redeem", request,
                        tokenManager.getAccessToken(), BadgeSummary.class));
    }

    public ChannelUserSummary getChannelUserSummary(UUID userId) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.get("/api/users/channel-user/" + userId, tokenManager.getAccessToken(), ChannelUserSummary.class));
    }

    public List<UserStatusDto> getUsersBatch(List<UUID> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return List.of();
        String idsParam = ids.stream().map(UUID::toString).collect(Collectors.joining(","));
        return tokenManager.executeWithRetry(() ->
                httpClient.getList("/api/users/batch?ids=" + idsParam,
                        tokenManager.getAccessToken(), UserStatusDto.class));
    }

    public List<UserBatchCacheResponse> getBatchAvatars(List<UUID> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return List.of();
        String idsParam = ids.stream().map(UUID::toString).collect(Collectors.joining(","));
        return tokenManager.executeWithRetry(() ->
                httpClient.getList("/api/users/batch-avatars?ids=" + idsParam,
                        tokenManager.getAccessToken(), UserBatchCacheResponse.class));
    }
}