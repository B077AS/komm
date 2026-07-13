package komm.websocket.handlers;

import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.model.dto.summary.ServerSummary;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class MemberRoleUpdatedHandler implements WsInboundMessageHandler {

    @Override
    public WsMessageType getType() {
        return WsMessageType.MEMBER_ROLE_UPDATED;
    }

    @Override
    public void handle(JsonObject payload) {
        String newRoleStr = payload.has("newRole") ? payload.get("newRole").getAsString() : null;
        String serverIdStr = payload.has("serverId") ? payload.get("serverId").getAsString() : null;
        if (newRoleStr == null) return;
        try {
            ServerSummary.Role newRole = ServerSummary.Role.valueOf(newRoleStr);
            App.getPermissionManager().updateMyRole(newRole);
            if (serverIdStr != null) {
                UUID serverId = UUID.fromString(serverIdStr);
                Platform.runLater(() -> App.onMyRoleChanged(serverId, newRole));
            }
            log.debug("MEMBER_ROLE_UPDATED: newRole={}", newRole);
        } catch (IllegalArgumentException e) {
            log.warn("MEMBER_ROLE_UPDATED: unknown role '{}'", newRoleStr);
        }
    }
}
