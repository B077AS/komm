package komm.websocket.handlers;

import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class CustomRoleMemberAssignedHandler implements WsInboundMessageHandler {

    @Override
    public WsMessageType getType() {
        return WsMessageType.CUSTOM_ROLE_MEMBER_ASSIGNED;
    }

    @Override
    public void handle(JsonObject payload) {
        UUID roleId = UUID.fromString(payload.get("roleId").getAsString());
        UUID targetUserId = UUID.fromString(payload.get("targetUserId").getAsString());
        if (App.getUser() != null && App.getUser().getUserId().equals(targetUserId)) {
            App.getPermissionManager().assignCustomRole(roleId);
            UUID serverId = payload.has("serverId") ? UUID.fromString(payload.get("serverId").getAsString()) : null;
            if (serverId != null) Platform.runLater(() -> App.onPermissionsChanged(serverId));
        }
    }
}