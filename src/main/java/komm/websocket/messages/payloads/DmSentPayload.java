package komm.websocket.messages.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DmSentPayload {

    private UUID recipientId;
    private String content;
    private UUID repliedToId;
    private boolean hasAttachments;
    private UUID attachmentId;
    private MessageReceivedPayload.MessageType messageType;
    private String codeLanguage;
}
