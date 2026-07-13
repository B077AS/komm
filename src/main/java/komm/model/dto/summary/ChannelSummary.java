package komm.model.dto.summary;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelSummary {

    private UUID channelId;
    private UUID serverId;
    private String channelName;
    private ChannelType channelType;
    private String description;
    private int position;
    private String icon;
    private boolean hasUnread;

    public enum ChannelType {
        TEXT,
        VOICE,
        SPACER,
        DIVIDER,
        TITLE,
        CLOCK
    }
}
