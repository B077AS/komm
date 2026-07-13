package komm.api.auth;

import komm.api.HttpClientWrapper;
import komm.model.dto.request.LoginRequest;
import komm.model.dto.request.ResendVerificationRequest;
import komm.model.dto.request.VerifyEmailRequest;
import komm.model.dto.response.AuthResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class HubAuth {

    private final HttpClientWrapper httpClient;
    private final TokenManager tokenManager;

    public void login(String username, String password) throws Exception {
        LoginRequest loginRequest = LoginRequest.builder()
                .username(username)
                .password(password)
                .build();

        try {
            AuthResponse authResponse = httpClient.post("/api/auth/login", loginRequest, null, AuthResponse.class);
            tokenManager.setTokens(authResponse.getAccessToken(), authResponse.getRefreshToken());
            log.info("Login successful for user: {}", username);
        } catch (Exception e) {
            throw new RuntimeException("Login failed: " + e.getMessage(), e);
        }
    }

    public void verifyEmail(String email, String code) throws Exception {
        VerifyEmailRequest request = VerifyEmailRequest.builder()
                .email(email)
                .code(code)
                .build();
        httpClient.post("/api/auth/verify-email", request, null, AuthResponse.class);
        log.info("Email verified for: {}", email);
    }

    public void resendVerification(String email) throws Exception {
        ResendVerificationRequest request = ResendVerificationRequest.builder()
                .email(email)
                .build();
        httpClient.post("/api/auth/resend-verification", request, null, Void.class);
        log.info("Verification resend requested for: {}", email);
    }

    public void logout() {
        tokenManager.clearTokens();
        log.info("Logged out successfully");
    }
}
