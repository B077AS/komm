package komm.model.dto.summary;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomRoleSummary {
    private UUID roleId;
    private UUID serverId;
    private String roleName;
    private String color;
    private String baseRole;
    private List<String> permissions;
    private int position;
}
