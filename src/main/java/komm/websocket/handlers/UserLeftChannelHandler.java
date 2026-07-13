package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.AppState;
import komm.api.json.GsonProvider;
import komm.ui.cards.ChannelCard;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.UserLeftChannelPayload;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class UserLeftChannelHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.USER_LEFT_CHANNEL;
    }

    @Override
    public void handle(JsonObject payload) {
        UserLeftChannelPayload leavePayload = gson.fromJson(payload, UserLeftChannelPayload.class);
        UUID userId = leavePayload.getUserId();
        UUID channelId = leavePayload.getChannelId();

        boolean isSelf = App.getUser() != null && App.getUser().getUserId().equals(userId);

        // A user leaving the voice channel must silence the soundboards they triggered:
        //  • if it's us, stop everything we're hearing locally;
        //  • otherwise, stop just that user's sounds (and clear their ring).
        if (isSelf) {
            if (App.getWebrtcRoomClient() != null) App.getWebrtcRoomClient().stopSoundboards();
        } else {
            if (App.getWebrtcRoomClient() != null) App.getWebrtcRoomClient().stopSoundboardsBy(userId);
        }

        Platform.runLater(() -> {
            if (isSelf) {
                AppState.resetServerAudioState();
                // Server forced us out of this channel (e.g. we joined another one).
                // Update our card's connected state without touching WebRTC.
                ChannelCard.onServerForcedLeave(channelId);
            } else {
                var card = App.getCachedServerPage().getChannelSection().getChannelBoxes().get(channelId);
                if (card != null) card.removeConnectedUser(userId);
            }
        });
    }
}