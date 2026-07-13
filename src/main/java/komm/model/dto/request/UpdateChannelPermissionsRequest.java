package komm.model.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateChannelPermissionsRequest {
    private List<String> allowPermissions;
    private List<String> denyPermissions;
}
