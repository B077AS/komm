package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.api.json.GsonProvider;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.UserScreenSharePayload;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class UserScreenShareHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.USER_SCREEN_SHARE;
    }

    @Override
    public void handle(JsonObject payload) {
        UserScreenSharePayload p = gson.fromJson(payload, UserScreenSharePayload.class);
        UUID userId = p.getUserId();

        Platform.runLater(() -> {
            App.getCachedServerPage()
                    .getChannelSection()
                    .getChannelBoxes()
                    .values()
                    .forEach(box -> box.setUserScreenSharing(userId, p.isSharing()));
        });
    }
}
