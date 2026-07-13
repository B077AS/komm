package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.api.json.GsonProvider;
import komm.ui.cards.ChannelCard;
import komm.ui.pages.ServerPage;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.ChannelUpdatedPayload;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ChannelUpdatedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.CHANNEL_UPDATED;
    }

    @Override
    public void handle(JsonObject payload) {
        ChannelUpdatedPayload p = gson.fromJson(payload, ChannelUpdatedPayload.class);

        Platform.runLater(() -> {
            ServerPage serverPage = App.getCachedServerPage();
            if (serverPage == null) return;
            var channelSection = serverPage.getChannelSection();
            if (channelSection == null) return;
            ChannelCard card = channelSection.getChannelBoxes().get(p.getChannelId());
            if (card != null) card.updateChannelInfo(p.getChannelName(), p.getIcon());
        });
    }
}
