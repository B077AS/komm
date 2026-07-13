package komm.websocket.messages.payloads;

import lombok.*;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserScreenSharePayload {
    private UUID userId;
    private boolean sharing;
    private boolean audioEnabled;
}