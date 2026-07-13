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
public class DmEditedPayload {

    private UUID messageId;
    private String content;
    private UUID conversationPartnerId;
    private String codeLanguage;
}
