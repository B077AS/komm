package komm.model.dto.summary;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServerMemberSummary {
    private UUID userId;
    private String baseRole;
    private List<UUID> customRoleIds;
}
