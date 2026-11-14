package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import komm.App;
import komm.api.json.GsonProvider;
import komm.ui.chat.ChatSection;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.PollUpdatedPayload;

public class ChannelPollUpdatedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.CHANNEL_POLL_UPDATED;
    }

    @Override
    public void handle(JsonObject payload) {
        PollUpdatedPayload p = gson.fromJson(payload, PollUpdatedPayload.class);
        if (p == null || p.getMessageId() == null || p.getPoll() == null) return;
        if (App.getCachedServerPage() == null) return;
        ChatSection chat = App.getCachedServerPage().getChatSection();
        if (chat != null) chat.updatePoll(p);
    }
}
