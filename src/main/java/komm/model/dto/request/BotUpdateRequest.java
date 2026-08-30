package komm.model.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BotUpdateRequest {
    private String name;
    private String avatarUrl;
    private Boolean enabled;
    private String config;
}
