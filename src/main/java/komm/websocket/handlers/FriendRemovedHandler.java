package komm.websocket.handlers;

import komm.websocket.interfaces.WsInboundMessageHandler;

import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.ui.pages.HomePage;
import komm.websocket.messages.WsMessageType;

public class FriendRemovedHandler implements WsInboundMessageHandler {

    @Override
    public WsMessageType getType() {
        return WsMessageType.FRIEND_REMOVED;
    }

    @Override
    public void handle(JsonObject payload) {
        Platform.runLater(() -> {
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