package komm.service;

import komm.api.HttpClientWrapper;
import komm.api.auth.TokenManager;
import komm.model.dto.request.PokeUserRequest;
import komm.model.dto.request.UpdatePokePreferenceRequest;
import komm.model.dto.response.PokePreferenceResponse;

import java.util.UUID;

public class MemberService {

    private final HttpClientWrapper httpClient;
    private final TokenManager tokenManager;

    public MemberService(HttpClientWrapper httpClient, TokenManager tokenManager) {
        this.httpClient = httpClient;
        this.tokenManager = tokenManager;
    }

    public void pokeUser(UUID targetUserId, String message) throws Exception {
        PokeUserRequest req = PokeUserRequest.builder()
                .targetUserId(targetUserId)
                .message(message)
                .build();
        tokenManager.executeWithRetry(() -> {
            httpClient.post("/api/members/poke", req, tokenManager.getAccessToken(), Void.class);
            return null;
        });
    }

    public boolean getMyPokePreference() throws Exception {
        return tokenManager.executeWithRetry(() -> {
            PokePreferenceResponse resp = httpClient.get(
                    "/api/members/me/poke-preference",
                    tokenManager.getAccessToken(),
                    PokePreferenceResponse.class);
            return resp != null && resp.isEnabled();
        });
    }

    public void updateMyPokePreference(boolean enabled) throws Exception {
        UpdatePokePreferenceRequest req = UpdatePokePreferenceRequest.builder()
                .enabled(enabled)
                .build();
        tokenManager.executeWithRetry(() -> {
            httpClient.put("/api/members/me/poke-preference", req, tokenManager.getAccessToken(), Void.class);
            return null;
        });
    }
}
