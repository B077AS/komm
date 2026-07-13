package komm.ui.chat;

import javafx.scene.layout.*;
import komm.ui.screenshare.MultiStreamView;
import java.util.List;
import komm.websocket.messages.payloads.MessageReceivedPayload;
import lombok.extern.slf4j.Slf4j;

/**
 * Host pane for the multi-stream grid.
 *
 * <p>Owns a {@link MultiStreamView} and exposes a simple add/remove API to
 * {@link ChatSection}. All per-tile overlay logic (hover effects, pill bar,
 * pop-out) lives inside {@link komm.ui.screenshare.StreamTile}.
 */
@Slf4j
public class StreamSection extends VBox {

    // ── Listener ──────────────────────────────────────────────────────────────

    public interface StreamSectionListener {
        /** Called after the chat drawer is toggled by the header button. */
        void onChatToggleRequested();

        /**
         * Called whenever a stream is removed — either by the user clicking
         * "Leave" on a tile, or by {@link #hideScreenShare()}.
         *
         * @param userId    the user whose stream was removed
         * @param remaining number of streams still active (0 = all gone)
         */
        void onStreamLeft(String userId, int remaining);
    }

    // ── Children ──────────────────────────────────────────────────────────────

    private final StreamSectionListener listener;
    private final MultiStreamView multiStreamView;

    // ── Constructor ───────────────────────────────────────────────────────────

    public StreamSection(StreamSectionListener listener) {
        this.listener = listener;
        VBox.setVgrow(this, Priority.ALWAYS);

        multiStreamView = new MultiStreamView();
        VBox.setVgrow(multiStreamView, Priority.ALWAYS);
        multiStreamView.setOnStreamRemoved((userId, remaining) ->
                listener.onStreamLeft(userId, remaining));

        getChildren().add(multiStreamView);
        setVisible(false);
        setManaged(false);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Adds a new live stream tile. No-op if already watching this user or if
     * the 4-stream limit is reached.
     */
    public void addStream(String userId, String username) {
        multiStreamView.addStream(userId, username);
        if (!isVisible()) {
            setVisible(true);
            setManaged(true);
        }
    }

    /** Removes a single stream tile (leaves others intact). */
    public void removeStream(String userId) {
        multiStreamView.removeStream(userId);
    }

    /**
     * Disposes all active stream tiles and hides this section.
     * No {@link StreamSectionListener} callbacks are fired.
     */
    public void hideScreenShare() {
        multiStreamView.removeAllStreams();
        setVisible(false);
        setManaged(false);
    }

    public boolean hasStream(String userId) {
        return multiStreamView.hasStream(userId);
    }

    public int getStreamCount() {
        return multiStreamView.getStreamCount();
    }

    /**
     * Returns a display title reflecting the current stream set:
     * {@code "Alice's Screen"} for one stream, {@code "N Live Streams"} for
     * multiple.
     */
    public String getHeaderTitle() {
        return multiStreamView.getHeaderTitle();
    }

    /**
     * Notifies the grid that the chat drawer has opened/closed so it adjusts
     * its visible-stream limit (2 when open, 4 when closed).
     */
    public void setChatOpen(boolean open) {
        multiStreamView.setChatOpen(open);
    }

    public List<String> getActiveStreamUserIds() {
        return multiStreamView.getStreamUserIds();
    }

    public void updateViewerCount(String userId, int count) {
        multiStreamView.updateViewerCount(userId, count);
    }

    /** No-op — retained for callers that update spectator counts. */
    public void setSpectatorCount(int count) {}

    /** No-op — retained for interface compatibility. */
    public boolean tryAddPopupMessage(MessageReceivedPayload payload) { return false; }
}
