package komm.websocket.messages.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Pushed by the hub when a DM we tried to send was rejected by the recipient's
 * privacy settings. Carries a ready-to-display title + description.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DmSendRejectedPayload {
    private UUID recipientId;
    private String title;
    private String description;
}
