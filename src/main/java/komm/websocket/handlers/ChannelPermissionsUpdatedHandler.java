package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import komm.App;
import komm.api.json.GsonProvider;
import komm.model.dto.summary.ChannelPermissionsSummary;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ChannelPermissionsUpdatedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.CHANNEL_PERMISSIONS_UPDATED;
    }

    @Override
    public void handle(JsonObject payload) {
        ChannelPermissionsSummary summary = gson.fromJson(payload, ChannelPermissionsSummary.class);
        App.getPermissionManager().updateChannelOverrides(summary);
        log.debug("CHANNEL_PERMISSIONS_UPDATED: channelId={}", summary.getChannelId());
    }
}
