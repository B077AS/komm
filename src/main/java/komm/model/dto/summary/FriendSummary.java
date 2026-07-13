package komm.model.dto.summary;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FriendSummary {

    private UUID friendId;
    private UUID requester;
    private UUID addressee;
    private FriendStatus status;

    public enum FriendStatus {
        PENDING,
        ACCEPTED,
        BLOCKED
    }
}
