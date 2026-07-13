package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.api.json.GsonProvider;
import komm.model.dto.summary.ServerSummary;
import komm.ui.cards.ChannelCard;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.ForcedChannelJoinPayload;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class ForcedChannelJoinHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.FORCED_CHANNEL_JOIN;
    }

    @Override
    public void handle(JsonObject payload) {
        ForcedChannelJoinPayload forced = gson.fromJson(payload, ForcedChannelJoinPayload.class);
        UUID newChannelId = forced.getChannelId();

        Platform.runLater(() -> {
            if (App.getCachedServerPage() == null) return;

            UUID oldChannelId = App.getWebrtcRoomClient().getCurrentChannelId();
            if (oldChannelId != null) {
                ChannelCard.onServerForcedLeave(oldChannelId);
            }

            // Streams being watched belong to the channel we are leaving — dispose
            // the tiles and close any pop-out stream windows before reconnecting.
            if (App.getCachedServerPage().getChatSection().getActiveStreamCount() > 0) {
                App.getCachedServerPage().getChatSection().hideScreenShare();
            }

            ServerSummary server = App.getCachedServerPage().getServer();

            ChannelCard newCard = App.getCachedServerPage()
                    .getChannelSection().getChannelBoxes().get(newChannelId);
            if (newCard != null) newCard.onJoinPending();

            // connectToChannel tears down existing WebRTC (soundboard, screen share, PCs) internally
            // and reconnects once the server responds with CHANNEL_TOKEN.
            App.webrtcRoomClient.connectToChannel(
                    server.getIpAddress() + ":" + server.getSignalPort(),
                    server.getServerId(),
                    newChannelId
            );

            log.info("FORCED_CHANNEL_JOIN: moving to channelId={} moved by userId={}", newChannelId, forced.getMovedByUserId());
        });
    }
}
