package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.api.json.GsonProvider;
import komm.ui.chat.DmChatSection;
import komm.ui.pages.DirectMessagePage;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.DmDeletedPayload;

public class DmDeletedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.DM_DELETED;
    }

    @Override
    public void handle(JsonObject payload) {
        DmDeletedPayload p = gson.fromJson(payload, DmDeletedPayload.class);
        Platform.runLater(() -> {
            DirectMessagePage page = App.getCachedDirectMessagePage();
            if (page != null) page.handleMessageDeleted(p.getMessageId(), p.getConversationPartnerId());
        });
    }
}
