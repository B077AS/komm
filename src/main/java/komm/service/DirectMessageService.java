package komm.service;

import com.google.gson.reflect.TypeToken;
import komm.api.HttpClientWrapper;
import komm.api.auth.TokenManager;
import komm.model.dto.response.AttachmentUploadResponse;
import komm.model.dto.summary.ConversationSummary;
import komm.websocket.messages.payloads.DmReceivedPayload;

import java.io.File;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class DirectMessageService {

    private final HttpClientWrapper httpClient;
    private final TokenManager tokenManager;

    public DirectMessageService(HttpClientWrapper httpClient, TokenManager tokenManager) {
        this.httpClient = httpClient;
        this.tokenManager = tokenManager;
    }

    public List<ConversationSummary> getConversations() throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.getList("/api/dm/conversations", tokenManager.getAccessToken(), ConversationSummary.class));
    }

    public List<DmReceivedPayload> getMessages(UUID partnerId, LocalDateTime cursor, int limit) throws Exception {
        if (partnerId == null) throw new IllegalArgumentException("partnerId cannot be null");
        return tokenManager.executeWithRetry(() -> {
            StringBuilder endpoint = new StringBuilder("/api/dm/")
                    .append(partnerId)
                    .append("/messages?limit=").append(limit);
            if (cursor != null) endpoint.append("&before=").append(cursor);
            Type listType = new TypeToken<List<DmReceivedPayload>>() {}.getType();
            return httpClient.getWithType(endpoint.toString(), tokenManager.getAccessToken(), listType);
        });
    }

    public void addReaction(UUID messageId, String emoji) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.put("/api/dm/messages/" + messageId + "/reactions/" + emoji,
                    null, tokenManager.getAccessToken(), null);
            return null;
        });
    }

    public void removeReaction(UUID messageId, String emoji) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.delete("/api/dm/messages/" + messageId + "/reactions/" + emoji,
                    tokenManager.getAccessToken());
            return null;
        });
    }

    public void markConversationRead(UUID partnerId) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.put("/api/dm/" + partnerId + "/read", null, tokenManager.getAccessToken(), null);
            return null;
        });
    }

    public void hideConversation(UUID partnerId) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.delete("/api/dm/" + partnerId, tokenManager.getAccessToken());
            return null;
        });
    }

    public void deleteAllHistory(UUID partnerId) throws Exception {
        tokenManager.executeWithRetry(() -> {
            httpClient.delete("/api/dm/" + partnerId + "/all", tokenManager.getAccessToken());
            return null;
        });
    }

    public AttachmentUploadResponse uploadAttachment(File file, String mimeType) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.postMultipart(
                        "/api/dm/attachments",
                        "file", file, mimeType,
                        tokenManager.getAccessToken(),
                        AttachmentUploadResponse.class, 201));
    }

    public byte[] downloadAttachment(UUID messageId, java.util.function.Consumer<Double> onProgress) throws Exception {
        return tokenManager.executeWithRetry(() ->
                httpClient.downloadBinary("/api/dm/messages/" + messageId + "/attachment",
                        tokenManager.getAccessToken(), onProgress));
    }
}
