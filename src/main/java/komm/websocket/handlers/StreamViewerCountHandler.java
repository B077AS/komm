package komm.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import komm.App;
import komm.api.json.GsonProvider;
import komm.ui.pages.ServerPage;
import komm.utils.NotificationSounds;
import komm.websocket.interfaces.WsInboundMessageHandler;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.StreamViewerCountPayload;

public class StreamViewerCountHandler implements WsInboundMessageHandler {

    private final Gson gson = GsonProvider.get();

    /** Last known viewer count for MY OWN stream; used to detect a new watcher joining
     *  (count going up) — a watcher leaving plays no sound, only updates the count. */
    private int lastOwnViewerCount = 0;

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

        boolean isOwnStream = App.getUser() != null
                && App.getUser().getUserId().toString().equals(streamerUserId);
        boolean newWatcher = isOwnStream && count > lastOwnViewerCount;
        if (isOwnStream) lastOwnViewerCount = count;

        Platform.runLater(() -> {
            if (newWatcher) NotificationSounds.play(NotificationSounds.STREAM_WATCH_STARTED, 0.5);

            ServerPage page = App.getCachedServerPage();
            if (page != null) {
                page.getChatSection().updateStreamViewerCount(streamerUserId, count);
            }
        });
    }
}
