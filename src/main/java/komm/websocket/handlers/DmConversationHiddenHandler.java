package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.api.json.GsonProvider;
import komm.ui.pages.DirectMessagePage;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.DmConversationHiddenPayload;

public class DmConversationHiddenHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.DM_CONVERSATION_HIDDEN;
    }

    @Override
    public void handle(JsonObject payload) {
        DmConversationHiddenPayload p = gson.fromJson(payload, DmConversationHiddenPayload.class);
        Platform.runLater(() -> {
            DirectMessagePage page = App.getCachedDirectMessagePage();
            if (page == null) {
                // DM page never opened — clear any pending unread badge for this partner.
                App.removePendingDmUnreadPartner(p.getPartnerId());
                return;
            }
            page.getSidebarSection().removeConversation(p.getPartnerId());
            page.getChatSection().onConversationCleared(p.getPartnerId());
        });
    }
}
