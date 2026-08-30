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
public class VoiceTokenPayload {
    private String livekitUrl;
    private String token;
    /** The channel this token was issued for; null from older servers. */
    private UUID channelId;
}