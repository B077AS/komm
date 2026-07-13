package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.api.json.GsonProvider;
import komm.ui.cards.ChannelCard;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.ChannelJoinDeniedPayload;

public class ChannelJoinDeniedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.CHANNEL_JOIN_DENIED;
    }

    @Override
    public void handle(JsonObject payload) {
        ChannelJoinDeniedPayload denied = gson.fromJson(payload, ChannelJoinDeniedPayload.class);
        Platform.runLater(() -> ChannelCard.notifyJoinDenied(denied.getReason()));
    }
}
