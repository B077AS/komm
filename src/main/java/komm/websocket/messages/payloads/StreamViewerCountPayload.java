package komm.websocket.messages.payloads;

import lombok.*;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamViewerCountPayload {
    private UUID streamerUserId;
    private int count;
}
