package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import komm.App;
import komm.api.json.GsonProvider;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.AvatarUpdatedPayload;
import lombok.extern.slf4j.Slf4j;

/**
 * A user changed their avatar. We simply evict our cached copy so the next
 * time that user needs to be drawn it is refetched fresh. Nothing is downloaded
 * here — clients that aren't currently showing this user never refetch.
 */
@Slf4j
public class UserAvatarUpdatedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.USER_AVATAR_UPDATED;
    }

    @Override
    public void handle(JsonObject payload) {
        AvatarUpdatedPayload p = gson.fromJson(payload, AvatarUpdatedPayload.class);
        if (p == null || p.getUserId() == null) return;
        App.getAvatarCache().evict(p.getUserId());
    }
}
