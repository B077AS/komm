package komm.api.auth;

import komm.App;
import komm.api.HttpClientWrapper;
import komm.model.dto.request.LoginInstallationRequest;
import komm.model.dto.response.AuthResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class InstallationAuth {

    private final HttpClientWrapper httpClient;
    private final TokenManager tokenManager;

    public void login(String ticket) throws Exception {
        try {
            AuthResponse authResponse = httpClient.post(
                    "/api/auth/login",
                    new LoginInstallationRequest(ticket),
                    null,
                    AuthResponse.class,
                    200
            );
            tokenManager.setTokens(authResponse.getAccessToken(), authResponse.getRefreshToken());
            log.info("Installation authenticated successfully for userId={}", App.getUser().getUserId());
        } catch (Exception e) {
            throw new RuntimeException("Installation login failed: " + e.getMessage(), e);
        }
    }
}