package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import komm.App;
import komm.api.json.GsonProvider;
import komm.model.dto.summary.CustomRoleSummary;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CustomRoleCreatedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.CUSTOM_ROLE_CREATED;
    }

    @Override
    public void handle(JsonObject payload) {
        CustomRoleSummary role = gson.fromJson(payload, CustomRoleSummary.class);
        App.getPermissionManager().upsertCustomRole(role);
        log.debug("CUSTOM_ROLE_CREATED: roleId={}", role.getRoleId());
    }
}