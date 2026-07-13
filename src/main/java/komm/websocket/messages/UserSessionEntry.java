package komm.websocket.messages;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserSessionEntry {
    private UUID userId;
    private UUID serverId;
    private UUID channelId;
    private boolean micEnabled;
    private boolean speakerEnabled;
    private boolean screenSharingEnabled;
    private boolean serverMicEnabled = true;
    private boolean serverSpeakerEnabled = true;
    private boolean pokesEnabled = true;
}