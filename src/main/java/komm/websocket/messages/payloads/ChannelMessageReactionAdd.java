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
public class ChannelMessageReactionAdd {
    private UUID messageId;
    private UUID serverId;
    private UUID channelId;
    private UUID userId;
    private String emoji;
}

