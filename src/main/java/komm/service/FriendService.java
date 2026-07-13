package komm.service;

import komm.api.HttpClientWrapper;
import komm.api.auth.TokenManager;
import komm.model.dto.summary.FriendSummary;

import java.util.List;
import java.util.UUID;

public class FriendService {

    private final HttpClientWrapper httpClient;
    private final TokenManager tokenManager;

    public FriendService(HttpClientWrapper httpClient, TokenManager tokenManager) {
        this.httpClient = httpClient;
        this.tokenManager = tokenManager;
    }

    /**
     * Get all accepted friends for the current user.
     */
    public List<FriendSummary> getFriends() throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.getList("/api/friends", tokenManager.getAccessToken(), FriendSummary.class));
    }

    /**
     * Get all pending requests the current user has sent (outgoing).
     */
    public List<FriendSummary> getSentRequests() throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.getList("/api/friends/requests/sent", tokenManager.getAccessToken(), FriendSummary.class));
    }

    /**
     * Get all pending requests the current user has received (incoming).
     */
    public List<FriendSummary> getReceivedRequests() throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.getList("/api/friends/requests/received", tokenManager.getAccessToken(), FriendSummary.class));
    }

    /**
     * Send a friend request to a user by their username.
     */
    public FriendSummary sendRequest(String username) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.post("/api/friends/request/" + username, null,
                        tokenManager.getAccessToken(), FriendSummary.class, 200));
    }

    /**
     * Accept an incoming friend request.
     */
    public FriendSummary acceptRequest(UUID friendId) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.patch("/api/friends/" + friendId + "/accept", null,
                        tokenManager.getAccessToken(), FriendSummary.class));
    }

    /**
     * Decline an incoming friend request.
     */
    public FriendSummary declineRequest(UUID friendId) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.patch("/api/friends/" + friendId + "/decline", null,
                        tokenManager.getAccessToken(), FriendSummary.class));
    }

    /**
     * Cancel a previously sent friend request.
     */
    public void cancelRequest(UUID friendId) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.delete("/api/friends/" + friendId + "/cancel", tokenManager.getAccessToken());
            return null;
        });
    }

    /**
     * Remove an accepted friend.
     */
    public void removeFriend(UUID friendId) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.delete("/api/friends/" + friendId, tokenManager.getAccessToken());
            return null;
        });
    }
}