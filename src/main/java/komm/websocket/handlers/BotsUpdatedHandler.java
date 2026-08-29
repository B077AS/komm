package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import komm.App;
import komm.api.json.GsonProvider;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.BotsUpdatedPayload;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BotsUpdatedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.BOTS_UPDATED;
    }

    @Override
    public void handle(JsonObject payload) {
        BotsUpdatedPayload p = gson.fromJson(payload, BotsUpdatedPayload.class);
        App.getBotRoster().reset(p.getBots());
    }
}
