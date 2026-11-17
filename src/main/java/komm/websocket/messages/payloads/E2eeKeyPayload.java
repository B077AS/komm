package komm.websocket.messages.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Voice E2EE: one member's SFrame media key, sealed for one other member (X25519 → HKDF →
 * AES-256-GCM, see {@code komm.webrtc.e2ee.KeySealer}). The installation stamps
 * {@code channelId} / {@code fromUserId}; the rest is opaque to it.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class E2eeKeyPayload {
    private UUID channelId;
    private UUID fromUserId;
    private UUID toUserId;
    /** Sender's base64 X25519 public key. */
    private String publicKey;
    /** Sender's SFrame participant id for this call (0..2^32-1). */
    private long participantId;
    /** Key generation, 0..255. */
    private int generation;
    /** Base64 AES-GCM nonce. */
    private String nonce;
    /** Base64 AES-GCM ciphertext of the media key. */
    private String sealedKey;
}
