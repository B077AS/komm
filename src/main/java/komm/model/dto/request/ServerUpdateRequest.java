package komm.model.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServerUpdateRequest {

    private UUID serverId;
    private String serverName;
    private String description;
    private String avatarBase64;
    private String avatarContentType;
    private String visibility;
    private Integer maxMembers;
}
