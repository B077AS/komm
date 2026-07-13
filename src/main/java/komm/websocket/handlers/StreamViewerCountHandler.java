package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.api.json.GsonProvider;
import komm.ui.pages.ServerPage;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.StreamViewerCountPayload;

public class StreamViewerCountHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    @Override
    public WsMessageType getType() {
        return WsMessageType.STREAM_VIEWER_COUNT;
    }

    @Override
    public void handle(JsonObject payload) {
        StreamViewerCountPayload p = gson.fromJson(payload, StreamViewerCountPayload.class);
        if (p.getStreamerUserId() == null) return;
        String streamerUserId = p.getStreamerUserId().toString();
        int count = p.getCount();

        Platform.runLater(() -> {
            ServerPage page = App.getCachedServerPage();
            if (page != null) {
                page.getChatSection().updateStreamViewerCount(streamerUserId, count);
            }
        });
    }
}
