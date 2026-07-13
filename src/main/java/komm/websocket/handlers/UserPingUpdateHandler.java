package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import komm.api.json.GsonProvider;
import komm.utils.UserPingHistory;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.UserPingUpdatePayload;
import lombok.extern.slf4j.Slf4j;

/**
 * Receives {@code USER_PING_UPDATE} frames and forwards the RTT sample to
 * {@link UserPingHistory} so open {@code UserPingGraphPopup} windows can redraw.
 */
@Slf4j
public class UserPingUpdateHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.USER_PING_UPDATE;
    }

    @Override
    public void handle(JsonObject payload) {
        UserPingUpdatePayload update = gson.fromJson(payload, UserPingUpdatePayload.class);
        if (update.getTargetUserId() == null) return;
        UserPingHistory.record(update.getTargetUserId().toString(), update.getPingMs(), update.getLossPct());
        log.debug("USER_PING_UPDATE: userId={} pingMs={} lossPct={}", update.getTargetUserId(), update.getPingMs(), update.getLossPct());
    }
}
