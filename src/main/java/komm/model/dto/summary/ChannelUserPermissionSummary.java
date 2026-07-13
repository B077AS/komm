package komm.model.dto.summary;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChannelUserPermissionSummary {
    private UUID channelId;
    private UUID userId;
    private List<String> allowPermissions;
    private List<String> denyPermissions;
}
