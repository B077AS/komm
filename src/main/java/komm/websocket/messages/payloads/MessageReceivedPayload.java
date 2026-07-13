package komm.websocket.messages.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class MessageReceivedPayload {
    private UUID messageId;
    private UUID senderId;
    private UUID channelId;
    private UUID serverId;
    private String content;
    private LocalDateTime sentAt;
    private boolean edited;
    private UUID repliedToId;
    private UUID replyToSenderId;
    private String replyToContent;
    private boolean hasAttachments;
    private String fileName;
    private String fileType;
    private String file64;
    private long fileSize;
    private List<ChannelMessageReactionAdd> reactions;
    private MessageType messageType;
    private String codeLanguage;
    private MessageType replyToMessageType;
    private boolean replyToHasAttachments;
    private String replyToFileName;
    private String replyToFileType;

    public enum MessageType {
        TEXT, GIF, CODE
    }
}
