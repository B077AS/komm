package komm.service;

import komm.Launcher;
import komm.api.HttpClientWrapper;
import komm.api.auth.TokenManager;
import komm.model.dto.request.SoundboardEditRequest;
import komm.model.dto.request.SoundboardUploadRequest;
import komm.model.dto.summary.SoundboardSummary;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Client access to the installation's soundboard REST API ({@code /api/soundboards}).
 * The list returns both SERVER-scope sounds and the caller's PERSONAL sounds.
 * Downloaded files are cached on disk under {@code data/soundboards/server/} keyed by
 * the soundboard id (ids are unique per upload, so the cache never goes stale).
 */
@Slf4j
public class SoundboardService {

    private final HttpClientWrapper httpClient;
    private final TokenManager tokenManager;

    public SoundboardService(HttpClientWrapper httpClient, TokenManager tokenManager) {
        this.httpClient = httpClient;
        this.tokenManager = tokenManager;
    }

    public List<SoundboardSummary> list() throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.getList("/api/soundboards", tokenManager.getAccessToken(), SoundboardSummary.class));
    }

    public SoundboardSummary upload(int slotIndex, String name, String emoji, String fileName,
                                    String fileType, byte[] bytes) throws Exception {
        SoundboardUploadRequest req = SoundboardUploadRequest.builder()
                .slotIndex(slotIndex)
                .name(name != null ? name : "")
                .emoji(emoji)
                .fileName(fileName != null ? fileName : "sound")
                .fileType(fileType != null ? fileType : "application/octet-stream")
                .contentBase64(Base64.getEncoder().encodeToString(bytes))
                .build();
        return tokenManager.executeWithRetry(() ->
                httpClient.post("/api/soundboards", req, tokenManager.getAccessToken(), SoundboardSummary.class));
    }

    /**
     * Edits a server sound. Pass {@code bytes == null} to keep the existing audio
     * file; when bytes are given, the server replaces the file and returns a
     * summary with a new soundboard id.
     */
    public SoundboardSummary edit(UUID soundboardId, String name, String emoji,
                                  String fileName, String fileType, byte[] bytes) throws Exception {
        SoundboardEditRequest req = SoundboardEditRequest.builder()
                .name(name != null ? name : "")
                .emoji(emoji)
                .fileName(bytes != null ? fileName : null)
                .fileType(bytes != null ? fileType : null)
                .contentBase64(bytes != null ? Base64.getEncoder().encodeToString(bytes) : null)
                .build();
        return tokenManager.executeWithRetry(() ->
                httpClient.patch("/api/soundboards/" + soundboardId, req,
                        tokenManager.getAccessToken(), SoundboardSummary.class));
    }

    public void delete(UUID soundboardId) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.delete("/api/soundboards/" + soundboardId, tokenManager.getAccessToken());
            return null;
        });
    }

    public byte[] downloadFile(UUID soundboardId) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.downloadBinary("/api/soundboards/" + soundboardId + "/file",
                        tokenManager.getAccessToken(), null));
    }

    /**
     * Returns a local file path for the given soundboard id, downloading and caching
     * it on first use. Decoding probes the file content, so no extension is needed.
     */
    public Path cachedFileById(UUID soundboardId) throws Exception {
        Path dir = Launcher.getSoundboardDirectory().resolve("server");
        Files.createDirectories(dir);
        Path cached = dir.resolve(soundboardId.toString());
        if (Files.exists(cached) && Files.size(cached) > 0) {
            return cached;
        }
        byte[] bytes = downloadFile(soundboardId);
        Files.write(cached, bytes);
        return cached;
    }
}
