package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.api.json.GsonProvider;
import komm.model.dto.summary.ServerPermissionsSummary;
import komm.ui.pages.ServerPage;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class ServerPermissionsUpdatedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.SERVER_PERMISSIONS_UPDATED;
    }

    @Override
    public void handle(JsonObject payload) {
        ServerPermissionsSummary summary = gson.fromJson(payload, ServerPermissionsSummary.class);
        if (summary.getRolePermissions() == null) return;
        App.getPermissionManager().updateServerPerms(summary.getRolePermissions());
        UUID serverId = summary.getServerId();
        if (serverId != null) {
            Platform.runLater(() -> {
                java.util.List<String> effective = App.getPermissionManager().getEffectivePermissions();
                if (!effective.isEmpty() && App.getCachedHomePage() != null) {
                    App.getCachedHomePage().syncServerPermissions(serverId, effective);
                }
                ServerPage sp = App.getCachedServerPage();
                if (sp != null && sp.getServer().getServerId().equals(serverId)) {
                    sp.getChannelSection().refreshPermissionUI();
                    sp.getChannelSection().reloadPermissions();
                }
            });
        }
        log.debug("SERVER_PERMISSIONS_UPDATED applied");
    }

}
