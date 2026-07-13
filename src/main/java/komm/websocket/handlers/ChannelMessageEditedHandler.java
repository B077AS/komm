package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import komm.App;
import komm.api.json.GsonProvider;
import komm.ui.chat.ChatSection;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.ChannelMessageEditedPayload;

public class ChannelMessageEditedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.CHANNEL_MESSAGE_EDITED;
    }

    @Override
    public void handle(JsonObject payload) {
        ChannelMessageEditedPayload p = gson.fromJson(payload, ChannelMessageEditedPayload.class);
        ChatSection chat = App.getCachedServerPage().getChatSection();
        if (chat != null) {
            chat.updateMessage(p);
        }
    }
}
