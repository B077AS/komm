package komm.model.dto.request;

import komm.model.dto.summary.MainUserSummary.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatusUpdateRequest {
    private UserStatus status;
}
