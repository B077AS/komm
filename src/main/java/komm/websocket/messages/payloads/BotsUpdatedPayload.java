package komm.websocket.messages.payloads;

import komm.model.dto.summary.BotSummary;
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
public class BotsUpdatedPayload {
    private UUID serverId;
    private List<BotSummary> bots;
}
