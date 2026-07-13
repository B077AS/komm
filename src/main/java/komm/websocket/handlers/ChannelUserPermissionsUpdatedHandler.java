package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import komm.App;
import komm.api.json.GsonProvider;
import komm.model.dto.summary.ChannelUserPermissionSummary;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ChannelUserPermissionsUpdatedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.CHANNEL_USER_PERMISSIONS_UPDATED;
    }

    @Override
    public void handle(JsonObject payload) {
        ChannelUserPermissionSummary summary = gson.fromJson(payload, ChannelUserPermissionSummary.class);
        App.getPermissionManager().updateMyChannelUserOverride(summary);
        log.debug("CHANNEL_USER_PERMISSIONS_UPDATED: channelId={}", summary.getChannelId());
    }
}
