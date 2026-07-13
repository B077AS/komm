package komm.websocket.handlers;

import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.ui.cards.ChannelCard;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class ForcedVoiceDisconnectHandler implements WsInboundMessageHandler {

    @Override
    public WsMessageType getType() {
        return WsMessageType.FORCED_VOICE_DISCONNECT;
    }

    @Override
    public void handle(JsonObject payload) {
        Platform.runLater(() -> {
            UUID channelId = App.getWebrtcRoomClient().getCurrentChannelId();
            if (channelId == null) return;

            ChannelCard.onServerForcedLeave(channelId);

            // Leave stream-viewer mode (disposes tiles and closes any pop-out
            // stream windows) — the WebRTC teardown below kills the video feed,
            // so nothing must be left showing a dead stream.
            var page = App.getCachedServerPage();
            if (page != null && page.getChatSection().getActiveStreamCount() > 0) {
                page.getChatSection().hideScreenShare();
            }

            App.getWebrtcRoomClient().disconnectFromChannel();
        });

        log.info("FORCED_VOICE_DISCONNECT received — disconnecting from voice");
    }
}
