package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import komm.App;
import komm.api.json.GsonProvider;
import komm.ui.chat.ChatSection;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.ChannelMessageReactionAdd;

public class ChannelMessageReactionAddHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.CHANNEL_MESSAGE_REACTION_ADD;
    }

    @Override
    public void handle(JsonObject payload) {
        ChannelMessageReactionAdd p = gson.fromJson(payload, ChannelMessageReactionAdd.class);

        ChatSection chat = App.getCachedServerPage().getChatSection();

        if (chat == null || p.getMessageId() == null || p.getEmoji() == null) return;
        if (!p.getChannelId().equals(chat.getActiveTextChannelId())) return;

        boolean isSelf = p.getUserId() != null
                && p.getUserId().equals(App.getUser().getUserId());

        chat.addReaction(p.getMessageId(), p.getEmoji(), isSelf);
    }
}
