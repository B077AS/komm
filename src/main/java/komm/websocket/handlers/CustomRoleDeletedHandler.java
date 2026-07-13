package komm.websocket.handlers;

import com.google.gson.JsonObject;
import komm.App;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class CustomRoleDeletedHandler implements WsInboundMessageHandler {

    @Override
    public WsMessageType getType() {
        return WsMessageType.CUSTOM_ROLE_DELETED;
    }

    @Override
    public void handle(JsonObject payload) {
        UUID roleId = UUID.fromString(payload.get("roleId").getAsString());
        App.getPermissionManager().removeCustomRole(roleId);
        log.debug("CUSTOM_ROLE_DELETED: roleId={}", roleId);
    }
}