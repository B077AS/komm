package komm.websocket.messages.payloads;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.UUID;

@Getter
@AllArgsConstructor
public class FriendRequestCancelledPayload {
    private final UUID friendId;
}