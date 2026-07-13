package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.api.json.GsonProvider;
import komm.model.dto.summary.ChannelSummary;
import komm.ui.cards.ChannelCard;
import komm.ui.pages.ServerPage;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.ChannelCreatedPayload;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ChannelCreatedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.CHANNEL_CREATED;
    }

    @Override
    public void handle(JsonObject payload) {
        ChannelCreatedPayload p = gson.fromJson(payload, ChannelCreatedPayload.class);

        ChannelSummary summary = ChannelSummary.builder()
                .channelId(p.getChannelId())
                .serverId(p.getServerId())
                .channelName(p.getChannelName())
                .channelType(p.getChannelType())
                .description(p.getDescription())
                .position(p.getPosition())
                .icon(p.getIcon())
                .build();

        Platform.runLater(() -> {
            ServerPage serverPage = App.getCachedServerPage();
            if (serverPage == null) return;
            var channelSection = serverPage.getChannelSection();
            if (channelSection == null) return;
            ChannelCard card = new ChannelCard(summary, serverPage.getServer());
            channelSection.addChannel(p.getChannelId(), card);
        });
    }
}
