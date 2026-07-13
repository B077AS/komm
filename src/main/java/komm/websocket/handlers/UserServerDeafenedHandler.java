package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.AppState;
import komm.api.json.GsonProvider;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.UserServerDeafenedPayload;

import java.util.UUID;

public class UserServerDeafenedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.USER_SERVER_DEAFENED;
    }

    @Override
    public void handle(JsonObject payload) {
        UserServerDeafenedPayload msg = gson.fromJson(payload, UserServerDeafenedPayload.class);
        UUID userId = msg.getUserId();
        boolean serverSpeakerEnabled = msg.isServerSpeakerEnabled();

        boolean isSelf = App.getUser() != null && App.getUser().getUserId().equals(userId);

        Platform.runLater(() -> {
            if (isSelf) {
                AppState.applyServerSpeakerEnabled(serverSpeakerEnabled);
            }

            var serverPage = App.getCachedServerPage();
            if (serverPage == null) return;
            serverPage.getChannelSection().getChannelBoxes().values()
                    .forEach(box -> box.setUserServerDeafened(userId, serverSpeakerEnabled));
        });
    }
}
