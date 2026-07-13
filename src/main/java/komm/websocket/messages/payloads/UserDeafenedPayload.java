package komm.websocket.messages.payloads;

import lombok.*;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDeafenedPayload {
    private UUID userId;
    private boolean speakerEnabled;
}