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
public class ServerPermissionsSummary {
    private UUID serverId;
    private Map<String, List<String>> rolePermissions;
    private List<CustomRoleSummary> customRoles;
    private List<UUID> myCustomRoleIds;
}
