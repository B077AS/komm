package komm.websocket.messages.payloads;

import lombok.Data;

import java.util.UUID;

@Data
public class ChannelUpdatedPayload {
    private UUID channelId;
    private UUID serverId;
    private String channelName;
    private String icon;
}
