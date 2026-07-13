package komm.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InviteSummary {
    private UUID inviteLinkId;
    private String code;
    private UUID creatorId;
    private String creatorUsername;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private int uses;
}
