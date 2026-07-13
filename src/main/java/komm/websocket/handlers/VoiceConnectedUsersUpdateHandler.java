package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.api.json.GsonProvider;
import komm.ui.pages.HomePage;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.VoiceConnectedUsersUpdatePayload;

public class VoiceConnectedUsersUpdateHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.VOCIE_CONNECTED_USERS_UPDATE;
    }

    @Override
    public void handle(JsonObject payload) {
        VoiceConnectedUsersUpdatePayload update = gson.fromJson(payload, VoiceConnectedUsersUpdatePayload.class);
        Platform.runLater(() -> {
            HomePage home = App.getCachedHomePage();
            if (home == null) return;
            home.updateActiveUsers(update.getServerId(), update.getActiveUsers());
        });
    }
}