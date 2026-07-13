package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.AppState;
import komm.api.json.GsonProvider;
import komm.model.dto.summary.MainUserSummary.UserStatus;
import komm.ui.customnodes.PokeNotificationWindow;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.PokeReceivedPayload;

public class PokeReceivedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.USER_POKED;
    }

    @Override
    public void handle(JsonObject payload) {
        if (AppState.userStatusProperty().get() == UserStatus.DO_NOT_DISTURB) return;
        if (!AppState.isPokesEnabled()) return;

        PokeReceivedPayload p = gson.fromJson(payload, PokeReceivedPayload.class);

        App.getAvatarCache().resolve(p.getSenderUserId()).thenAccept(cu -> {
            String username = (cu != null && cu.username() != null)
                    ? cu.username()
                    : p.getSenderUsername();
            Platform.runLater(() ->
                    new PokeNotificationWindow(p.getSenderUserId(), username, p.getMessage()).show()
            );
        });
    }
}
