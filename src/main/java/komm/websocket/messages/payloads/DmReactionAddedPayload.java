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
public class DmReactionAddedPayload {

    private UUID messageId;
    private UUID userId;
    private String emoji;
    private UUID conversationPartnerId;
}
