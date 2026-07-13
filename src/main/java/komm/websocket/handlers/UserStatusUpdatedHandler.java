package komm.websocket.handlers;

import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.AppState;
import komm.model.dto.summary.MainUserSummary.UserStatus;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class UserStatusUpdatedHandler implements WsInboundMessageHandler {

    @Override
    public WsMessageType getType() {
        return WsMessageType.USER_STATUS_UPDATED;
    }

    @Override
    public void handle(JsonObject payload) {
        if (!payload.has("status") || payload.get("status").isJsonNull()) return;
        try {
            UserStatus status = UserStatus.valueOf(payload.get("status").getAsString());
            Platform.runLater(() -> AppState.userStatusProperty().set(status));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown user status received: {}", payload.get("status").getAsString());
        }
    }
}
