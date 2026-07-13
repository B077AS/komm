package komm.api.auth;

import komm.api.HttpClientWrapper;
import komm.api.HttpStatusException;
import komm.model.dto.response.AuthResponse;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public class TokenManager {

    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile boolean isRefreshing = false;

    private final HttpClientWrapper httpClient;

    public TokenManager(HttpClientWrapper httpClient) {
        this.httpClient = httpClient;
    }

    public void refreshAccessToken() throws Exception {
        if (refreshToken == null) {
            throw new IllegalStateException("No refresh token available");
        }

        try {
            AuthResponse authResponse = httpClient.post("/api/auth/refresh", null, refreshToken, AuthResponse.class);
            this.accessToken = authResponse.getAccessToken();
            this.refreshToken = authResponse.getRefreshToken();
            log.debug("Tokens refreshed successfully");
        } catch (HttpStatusException e) {
            // Server explicitly rejected the refresh token — it's actually invalid, so drop it.
            if (e.getStatusCode() == 401 || e.getStatusCode() == 403) {
                this.accessToken = null;
                this.refreshToken = null;
            }
            throw new RuntimeException("Token refresh failed: " + e.getMessage(), e);
        } catch (Exception e) {
            // Network-level failure (hub unreachable, timed out, etc.) — the refresh token is
            // still valid, keep it so reconnects can succeed once the hub comes back.
            throw new RuntimeException("Token refresh failed: " + e.getMessage(), e);
        }
    }

    public <T> T executeWithRetry(RequestSupplier<T> requestSupplier) throws Exception {
        try {
            return requestSupplier.get();
        } catch (HttpStatusException e) {
            if (e.isExpiredToken()) {
                synchronized (this) {
                    if (refreshToken == null) {
                        throw new RuntimeException("Session expired. Please login again.");
                    }
                    if (!isRefreshing) {
                        try {
                            isRefreshing = true;
                            refreshAccessToken();
                        } catch (Exception refreshError) {
                            // refreshAccessToken() already cleared tokens if the server actually
                            // rejected them; on a network failure they're left intact for retry.
                            throw new RuntimeException("Session expired. Please login again.", refreshError);
                        } finally {
                            isRefreshing = false;
                        }
                    }
                    try {
                        return requestSupplier.get();
                    } catch (Exception retryError) {
                        throw new RuntimeException("Request failed after token refresh.", retryError);
                    }
                }
            }
            throw e;
        }
    }

    public void setTokens(String accessToken, String refreshToken) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
    }

    public boolean isLoggedIn() {
        return accessToken != null;
    }

    public void clearTokens() {
        this.accessToken = null;
        this.refreshToken = null;
    }

    @FunctionalInterface
    public interface RequestSupplier<T> {
        T get() throws Exception;
    }
}