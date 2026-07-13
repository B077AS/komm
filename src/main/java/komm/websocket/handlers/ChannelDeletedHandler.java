package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.api.json.GsonProvider;
import komm.ui.pages.ServerPage;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.ChannelDeletedPayload;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class ChannelDeletedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.CHANNEL_DELETED;
    }

    @Override
    public void handle(JsonObject payload) {
        ChannelDeletedPayload p = gson.fromJson(payload, ChannelDeletedPayload.class);
        UUID deletedId = p.getChannelId();

        Platform.runLater(() -> {
            ServerPage serverPage = App.getCachedServerPage();
            if (serverPage == null) return;

            // If this was the active text/voice channel, clear the chat view
            var chatSection = serverPage.getChatSection();
            if (chatSection != null) {
                UUID activeId = chatSection.getActiveTextChannelId();
                if (deletedId.equals(activeId)) {
                    chatSection.clearAndShowWelcome();
                }
            }

            var channelSection = serverPage.getChannelSection();
            if (channelSection != null) {
                channelSection.removeChannel(deletedId);
            }
        });
    }
}
