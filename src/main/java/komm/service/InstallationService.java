package komm.service;

import com.google.gson.reflect.TypeToken;

import komm.api.HttpClientWrapper;
import komm.api.auth.TokenManager;
import komm.model.dto.request.CreateInstallationRequest;
import komm.model.dto.request.JoinInstallationRequest;
import komm.model.dto.summary.InstallationAccessTokenSummary;
import komm.model.dto.summary.InstallationDetailSummary;
import komm.model.dto.summary.InstallationSummary;
import komm.model.dto.summary.ServerSummary;

import java.lang.reflect.Type;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public class InstallationService {

    private final HttpClientWrapper httpClient;
    private final TokenManager tokenManager;

    public InstallationService(HttpClientWrapper httpClient, TokenManager tokenManager) {
        this.httpClient = httpClient;
        this.tokenManager = tokenManager;
    }

    /**
     * Get all installations for the current user
     */
    public List<InstallationSummary> getUserInstallations() throws Exception {
        return tokenManager.executeWithRetry(() -> {
            Type listType = new TypeToken<List<InstallationSummary>>(){}.getType();
            return httpClient.getWithType("/api/installations/list", tokenManager.getAccessToken(), listType);
        });
    }

    /**
     * Create a new installation
     */
    public UUID createInstallation(CreateInstallationRequest createInstallationRequest) throws Exception {
        return tokenManager.executeWithRetry(() -> 
            httpClient.post("/api/installations/create", createInstallationRequest, tokenManager.getAccessToken(), UUID.class, 201)
        );
    }

    public InstallationDetailSummary getInstallationDetails(UUID installationId) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.get("/api/installations/" + installationId,
                        tokenManager.getAccessToken(), InstallationDetailSummary.class)
        );
    }

    public List<ServerSummary> getInstallationServers(UUID installationId) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.getList("/api/installations/" + installationId + "/servers",
                        tokenManager.getAccessToken(), ServerSummary.class)
        );
    }

    /**
     * Download installation activation token as base64 string
     */
    public String downloadInstallationToken(UUID installationId) throws Exception {
        return tokenManager.executeWithRetry(() -> {
            String endpoint = "/installations/token?installationId=" + installationId;
            return httpClient.downloadText(endpoint, tokenManager.getAccessToken());
        });
    }

    /**
     * Download installation ZIP file as byte array with progress tracking
     */
    public byte[] downloadInstallationZip(UUID installationId, Consumer<Double> progressCallback) throws Exception {
        return tokenManager.executeWithRetry(() -> {
            String endpoint = "/installations/zip?installationId=" + installationId;
            return httpClient.downloadBinary(endpoint, tokenManager.getAccessToken(), progressCallback);
        });
    }

    public byte[] downloadInstallationJar(UUID installationId, Consumer<Double> progressCallback) throws Exception {
        return tokenManager.executeWithRetry(() -> {
            String endpoint = "/api/installations/jar?installationId=" + installationId;
            return httpClient.downloadBinary(endpoint, tokenManager.getAccessToken(), progressCallback);
        });
    }

    public void deleteInstallation(UUID installationId) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.delete("/api/installations/" + installationId, tokenManager.getAccessToken());
            return null;
        });
    }

    public InstallationAccessTokenSummary generateAccessToken(UUID installationId) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.post("/api/installations/" + installationId + "/tokens", null,
                        tokenManager.getAccessToken(), InstallationAccessTokenSummary.class, 201)
        );
    }

    public List<InstallationAccessTokenSummary> getAccessTokens(UUID installationId) throws Exception {
        return tokenManager.executeWithRetry(() -> {
            Type listType = new TypeToken<List<InstallationAccessTokenSummary>>(){}.getType();
            return httpClient.getWithType("/api/installations/" + installationId + "/tokens",
                    tokenManager.getAccessToken(), listType);
        });
    }

    public void deleteAccessToken(UUID installationId, UUID tokenId) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.delete("/api/installations/" + installationId + "/tokens/" + tokenId,
                    tokenManager.getAccessToken());
            return null;
        });
    }

    public String joinViaToken(String code) throws Exception {
        return tokenManager.executeWithRetry(() -> {
            var response = httpClient.post("/api/installations/join",
                    JoinInstallationRequest.builder().code(code).build(),
                    tokenManager.getAccessToken(), java.util.Map.class, 200);
            return response != null ? (String) response.get("installationName") : null;
        });
    }
}