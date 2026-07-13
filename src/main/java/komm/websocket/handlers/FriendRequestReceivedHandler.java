package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.api.json.GsonProvider;
import komm.ui.customnodes.CustomNotification;
import komm.ui.pages.HomePage;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.FriendRequestPayload;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

public class FriendRequestReceivedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.FRIEND_REQUEST;
    }

    @Override
    public void handle(JsonObject payload) {
        FriendRequestPayload p = gson.fromJson(payload, FriendRequestPayload.class);

        Platform.runLater(() -> {
            // Show notification
            new CustomNotification(
                    "Friend Request",
                    p.getRequesterUsername() + " wants to be your friend.",
                    new FontIcon(Feather.USER_PLUS)
            ).showNotification();

            App.setFriendRequestPending(true);

            // Update friends section
            HomePage home = App.getCachedHomePage();
            if (home == null) return;

            if (home.isFriendsPanelOpen()) {
                home.getFriendsSection().reload();
            } else {
                home.getFriendsSection().markDirty();
            }
        });
    }
}