package komm.model.dto.response;

import komm.model.dto.summary.MainUserSummary.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserStatusDto {
    private UUID userId;
    private UserStatus status;
}
