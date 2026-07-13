package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.api.json.GsonProvider;
import komm.ui.pages.DirectMessagePage;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.DmReactionAddedPayload;

public class DmReactionAddedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.DM_REACTION_ADDED;
    }

    @Override
    public void handle(JsonObject payload) {
        DmReactionAddedPayload p = gson.fromJson(payload, DmReactionAddedPayload.class);
        Platform.runLater(() -> {
            DirectMessagePage page = App.getCachedDirectMessagePage();
            if (page == null) return;
            boolean isSelf = p.getUserId() != null && p.getUserId().equals(App.getUser().getUserId());
            page.getChatSection().addReaction(p.getMessageId(), p.getConversationPartnerId(), p.getEmoji(), isSelf);
        });
    }
}
