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
public class UserJoinedChannelPayload {
    UUID channelId;
    UUID userId;
    private boolean micEnabled;
    private boolean speakerEnabled;
    private boolean serverMicEnabled = true;
    private boolean serverSpeakerEnabled = true;
    private boolean pokesEnabled = true;
}