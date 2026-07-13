package komm.model.dto.request;

import komm.model.dto.summary.ChannelSummary.ChannelType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelCreateRequest {
    private UUID serverId;
    private String channelName;
    private ChannelType channelType;
    private String icon;
}
