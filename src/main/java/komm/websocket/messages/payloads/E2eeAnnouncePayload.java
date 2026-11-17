package komm.websocket.messages.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Voice E2EE: a call member's ephemeral X25519 public key. Outbound only {@code publicKey}
 * is set; the installation stamps {@code channelId} / {@code userId} when relaying it.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class E2eeAnnouncePayload {
    private UUID channelId;
    private UUID userId;
    /** Base64 X.509-encoded X25519 public key. */
    private String publicKey;
}
