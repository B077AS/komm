package komm.websocket.messages.payloads;

import lombok.Data;

import java.util.UUID;

@Data
public class ChannelDeletedPayload {
    private UUID channelId;
    private UUID serverId;
}
