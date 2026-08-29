package komm.model.dto.summary;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BotSummary {
    private UUID botId;
    private UUID serverId;
    private BotType botType;
    private String name;
    private String avatarUrl;
    private boolean enabled;
    private String config;
    private List<UUID> channelIds;

    public enum BotType {
        ANIME_WAIFU
    }
}
