package komm.model.dto.request;

import komm.model.dto.summary.MainUserSummary;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserUpdateRequest {

    private String username;
    private MainUserSummary.UserStatus status;
    private String statusMessage;
    private String statusEmoji;
    private String avatar;
    private String avatarImageFormat;
    private MainUserSummary.DmPrivacy dmPrivacy;
}
