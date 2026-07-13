package komm.model.dto.request;

import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServerCreateRequest {
	
	private String serverName;
    private UUID installationId;
    private String avatarBase64;
    private String avatarContentType;
}
