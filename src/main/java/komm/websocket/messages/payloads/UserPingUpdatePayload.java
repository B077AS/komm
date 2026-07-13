package komm.websocket.messages.payloads;

import lombok.*;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPingUpdatePayload {
    private UUID targetUserId;
    private int pingMs;
    private int lossPct;
}
