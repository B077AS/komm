package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import komm.App;
import komm.api.json.GsonProvider;
import komm.ui.chat.ChatSection;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.ChannelMessageReactionRemove;

public class ChannelMessageReactionRemoveHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.CHANNEL_MESSAGE_REACTION_REMOVE;
    }

    @Override
    public void handle(JsonObject payload) {
        ChannelMessageReactionRemove p = gson.fromJson(payload, ChannelMessageReactionRemove.class);

        ChatSection chat = App.getCachedServerPage().getChatSection();

        if (chat == null || p.getMessageId() == null || p.getEmoji() == null) return;
        if (!p.getChannelId().equals(chat.getActiveTextChannelId())) return;

        boolean isSelf = p.getUserId() != null
                && p.getUserId().equals(App.getUser().getUserId());

        chat.removeReaction(p.getMessageId(), p.getEmoji(), isSelf);
    }
}
