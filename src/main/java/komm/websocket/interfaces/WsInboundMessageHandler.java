package komm.websocket.interfaces;

import com.google.gson.JsonObject;
import komm.websocket.messages.WsMessageType;

public interface WsInboundMessageHandler {
    WsMessageType getType();
    void handle(JsonObject payload);
}