package komm.websocket.messages.payloads;

import komm.model.dto.summary.ChannelSummary;
import lombok.Data;

import java.util.UUID;

@Data
public class ChannelCreatedPayload {
    private UUID channelId;
    private UUID serverId;
    private String channelName;
    private ChannelSummary.ChannelType channelType;
    private String description;
    private int position;
    private String icon;
}
