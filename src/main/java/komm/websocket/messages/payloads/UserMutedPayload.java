package komm.websocket.messages.payloads;

import lombok.*;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserMutedPayload {
    private UUID userId;
    private boolean micEnabled;
}