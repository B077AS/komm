package komm.service;

import com.google.gson.reflect.TypeToken;
import komm.api.HttpClientWrapper;
import komm.api.auth.TokenManager;
import komm.model.dto.response.AttachmentUploadResponse;
import komm.websocket.messages.payloads.MessageReceivedPayload;

import java.io.File;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class MessageService {

    private final HttpClientWrapper httpClient;
    private final TokenManager tokenManager;

    public MessageService(HttpClientWrapper httpClient, TokenManager tokenManager) {
        this.httpClient = httpClient;
        this.tokenManager = tokenManager;
    }

    /**
     * Fetches up to {@code limit} messages before {@code cursor}.
     * Pass {@code null} for cursor to get the latest messages.
     * Results are ordered newest-first — reverse before rendering.
     */
    public List<MessageReceivedPayload> getMessages(UUID channelId,
                                                    LocalDateTime cursor,
                                                    int limit) throws Exception {
        if (channelId == null) throw new IllegalArgumentException("channelId cannot be null");

        return tokenManager.executeWithRetry(() -> {
            StringBuilder endpoint = new StringBuilder("/api/messages/")
                    .append(channelId)
                    .append("?limit=").append(limit);
            if (cursor != null) {
                endpoint.append("&before=").append(cursor);
            }
            Type listType = new TypeToken<List<MessageReceivedPayload>>() {}.getType();
            return httpClient.getWithType(endpoint.toString(),
                    tokenManager.getAccessToken(), listType);
        });
    }

    /**
     * Adds a reaction emoji to the given message.
     * Corresponds to PUT /api/messages/{messageId}/reactions/{emoji}
     */
    public void addReaction(UUID messageId, String emoji) throws Exception {
        if (messageId == null) throw new IllegalArgumentException("messageId cannot be null");
        if (emoji == null || emoji.isBlank()) throw new IllegalArgumentException("emoji cannot be blank");

        tokenManager.executeWithRetry(() -> {
            String endpoint = "/api/messages/" + messageId + "/reactions/" + emoji;
            httpClient.put(endpoint, null, tokenManager.getAccessToken(), null);
            return null;
        });
    }

    /**
     * Removes the current user's reaction emoji from the given message.
     * Corresponds to DELETE /api/messages/{messageId}/reactions/{emoji}
     */
    public void removeReaction(UUID messageId, String emoji) throws Exception {
        if (messageId == null) throw new IllegalArgumentException("messageId cannot be null");
        if (emoji == null || emoji.isBlank()) throw new IllegalArgumentException("emoji cannot be blank");

        tokenManager.executeWithRetry(() -> {
            String endpoint = "/api/messages/" + messageId + "/reactions/" + emoji;
            httpClient.delete(endpoint, tokenManager.getAccessToken());
            return null;
        });
    }

    public AttachmentUploadResponse uploadAttachment(UUID channelId, File file, String mimeType) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.postMultipart(
                        "/api/messages/channels/" + channelId + "/attachments",
                        "file", file, mimeType,
                        tokenManager.getAccessToken(),
                        AttachmentUploadResponse.class, 201));
    }

    /**
     * Downloads the attachment for the given message as raw bytes.
     * Corresponds to GET /api/messages/{messageId}/attachment
     */
    public byte[] downloadAttachmentWithProgress(UUID messageId,
                                                 java.util.function.Consumer<Double> onProgress) throws Exception {
        if (messageId == null) throw new IllegalArgumentException("messageId cannot be null");

        return tokenManager.executeWithRetry(() -> {
            String endpoint = "/api/messages/" + messageId + "/attachment";
            return httpClient.downloadBinary(endpoint, tokenManager.getAccessToken(), onProgress);
        });
    }
}