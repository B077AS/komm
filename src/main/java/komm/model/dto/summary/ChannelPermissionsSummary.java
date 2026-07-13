package komm.model.dto.summary;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelPermissionsSummary {
    private UUID channelId;
    private Map<String, RoleOverride> roleOverrides;
    private ChannelUserPermissionSummary myUserOverride;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoleOverride {
        private List<String> allowPermissions;
        private List<String> denyPermissions;
    }
}
