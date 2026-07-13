package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.api.json.GsonProvider;
import komm.ui.pages.ServerPage;
import komm.ui.screenshare.StreamPopOutWindow;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.StreamEndedPayload;

public class StreamEndedHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.STREAM_ENDED;
    }

    @Override
    public void handle(JsonObject payload) {
        StreamEndedPayload p = gson.fromJson(payload, StreamEndedPayload.class);
        if (p.getStreamerUserId() == null) return;
        String streamerUserId = p.getStreamerUserId().toString();

        Platform.runLater(() -> {
            ServerPage page = App.getCachedServerPage();
            if (page != null) {
                page.getChatSection().onStreamerEnded(streamerUserId);
            }
            // Removing the tile above already closes its pop-out window, but if
            // no tile tracks the window anymore (page replaced, state mismatch)
            // this guarantees the detached window never outlives the stream.
            StreamPopOutWindow.closeAllForStreamer(streamerUserId);
        });
    }
}
