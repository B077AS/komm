package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.api.json.GsonProvider;
import komm.ui.pages.DirectMessagePage;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.DmReactionRemovedPayload;

public class DmReactionRemovedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.DM_REACTION_REMOVED;
    }

    @Override
    public void handle(JsonObject payload) {
        DmReactionRemovedPayload p = gson.fromJson(payload, DmReactionRemovedPayload.class);
        Platform.runLater(() -> {
            DirectMessagePage page = App.getCachedDirectMessagePage();
            if (page == null) return;
            boolean isSelf = p.getUserId() != null && p.getUserId().equals(App.getUser().getUserId());
            page.getChatSection().removeReaction(p.getMessageId(), p.getConversationPartnerId(), p.getEmoji(), isSelf);
        });
    }
}
