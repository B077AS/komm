package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import komm.api.json.GsonProvider;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.MemberLeftPayload;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MemberLeftHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.MEMBER_LEFT;
    }

    @Override
    public void handle(JsonObject payload) {
        MemberLeftPayload p = gson.fromJson(payload, MemberLeftPayload.class);
        if (p == null) return;
        log.debug("MEMBER_LEFT received — userId={} left serverId={}", p.getUserId(), p.getServerId());
    }
}
