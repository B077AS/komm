package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.api.json.GsonProvider;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.UserDeafenedPayload;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class UserDeafenedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.USER_DEAFENED;
    }

    @Override
    public void handle(JsonObject payload) {
        UserDeafenedPayload p = gson.fromJson(payload, UserDeafenedPayload.class);
        UUID userId = p.getUserId();

        Platform.runLater(() -> {
            App.getCachedServerPage()
                    .getChannelSection()
                    .getChannelBoxes()
                    .values()
                    .forEach(box -> box.setUserDeafened(userId, p.isSpeakerEnabled()));
        });
    }
}