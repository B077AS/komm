package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.AppState;
import komm.api.json.GsonProvider;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.UserServerMutedPayload;

import java.util.UUID;

public class UserServerMutedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.USER_SERVER_MUTED;
    }

    @Override
    public void handle(JsonObject payload) {
        UserServerMutedPayload msg = gson.fromJson(payload, UserServerMutedPayload.class);
        UUID userId = msg.getUserId();
        boolean serverMicEnabled = msg.isServerMicEnabled();

        boolean isSelf = App.getUser() != null && App.getUser().getUserId().equals(userId);

        Platform.runLater(() -> {
            if (isSelf) {
                AppState.applyServerMicEnabled(serverMicEnabled);
            }

            var serverPage = App.getCachedServerPage();
            if (serverPage == null) return;
            serverPage.getChannelSection().getChannelBoxes().values()
                    .forEach(box -> box.setUserServerMuted(userId, serverMicEnabled));
        });
    }
}
