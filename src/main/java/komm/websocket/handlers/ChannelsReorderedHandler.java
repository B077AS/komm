package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.api.json.GsonProvider;
import komm.ui.pages.ServerPage;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.ChannelsReorderedPayload;

public class ChannelsReorderedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.CHANNELS_REORDERED;
    }

    @Override
    public void handle(JsonObject payload) {
        ChannelsReorderedPayload p = gson.fromJson(payload, ChannelsReorderedPayload.class);
        if (p.getChannelIds() == null) return;

        Platform.runLater(() -> {
            ServerPage serverPage = App.getCachedServerPage();
            if (serverPage == null) return;
            var channelSection = serverPage.getChannelSection();
            if (channelSection == null) return;
            channelSection.applyChannelOrder(p.getChannelIds());
        });
    }
}
