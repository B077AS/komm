package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.api.json.GsonProvider;
import komm.model.dto.summary.ChannelUserSummary;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.UserJoinedChannelPayload;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class UserJoinedChannelHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.USER_JOINED_CHANNEL;
    }

    @Override
    public void handle(JsonObject payload) {
        UserJoinedChannelPayload joinPayload = gson.fromJson(payload, UserJoinedChannelPayload.class);
        UUID userId = joinPayload.getUserId();
        UUID channelId = joinPayload.getChannelId();

        boolean isSelf = App.getUser() != null && App.getUser().getUserId().equals(userId);

        App.getServices().getExecutor().submit(() -> {
            try {
                ChannelUserSummary user = App.getServices().hub().getUserService().getChannelUserSummary(userId);
                user.setMicEnabled(joinPayload.isMicEnabled());
                user.setSpeakerEnabled(joinPayload.isSpeakerEnabled());
                user.setServerMicEnabled(joinPayload.isServerMicEnabled());
                user.setServerSpeakerEnabled(joinPayload.isServerSpeakerEnabled());
                user.setPokesEnabled(joinPayload.isPokesEnabled());
                // A user who just (re)joined this channel can't already be screen sharing —
                // sharing only starts after joining, via an explicit USER_SCREEN_SHARE broadcast.
                // When moved between channels the live track is torn down (correctly), but the
                // hub summary may still report it as enabled, leaving a stale live indicator on
                // the new card. Force it off; a genuine concurrent share-start is still applied
                // via the pending screen-share state in ChannelCard.addConnectedUser().
                user.setScreenSharingEnabled(false);

                Platform.runLater(() -> {
                    if (isSelf) {
                        komm.AppState.applyServerMicEnabled(joinPayload.isServerMicEnabled());
                        komm.AppState.applyServerSpeakerEnabled(joinPayload.isServerSpeakerEnabled());
                    }

                    var card = App.getCachedServerPage()
                            .getChannelSection()
                            .getChannelBoxes()
                            .get(channelId);
                    if (card == null) return;
                    card.addConnectedUser(user);
                    if (isSelf) card.onJoinConfirmed();
                });

            } catch (Exception e) {
                log.error("Failed to fetch channel user summary for userId={}: {}", userId, e.getMessage(), e);
            }
        });
    }
}