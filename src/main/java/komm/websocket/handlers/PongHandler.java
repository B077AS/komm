package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import komm.api.json.GsonProvider;
import komm.utils.PingHistory;
import komm.utils.PingLossTracker;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.PingPayload;

public class PongHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.PONG;
    }

    @Override
    public void handle(JsonObject payload) {
        PingPayload pong = gson.fromJson(payload, PingPayload.class);
        long now = System.currentTimeMillis();
        int rtt = (int) (now - pong.getTimestamp());
        PingLossTracker.onPongReceived(now);
        PingHistory.record(rtt);
    }
}
