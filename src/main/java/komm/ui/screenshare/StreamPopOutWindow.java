package komm.ui.screenshare;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import komm.App;
import komm.ui.utils.WindowsThemeUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.sun.jna.Platform.isWindows;

/**
 * A detached window that renders one user's screen-share stream.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Caller first calls {@link #show()}, which subscribes a fresh
 *       {@link VideoSinkView} to the WebRTC track for {@code userId}.</li>
 *   <li>When the user closes the window the stage's {@code onHiding} handler
 *       unsubscribes, disposes the sink, and fires {@code onClosed} so the
 *       originating {@link StreamTile} can reattach its own sink.</li>
 *   <li>If the owning tile is removed before the window is closed, call
 *       {@link #closeAndCancel()} — this suppresses the reattach callback.</li>
 * </ol>
 */
public class StreamPopOutWindow {

    /**
     * Every currently open pop-out window. Maintained on the FX thread only:
     * added in {@link #show()}, removed by the stage's {@code onHiding} handler,
     * so a window is tracked exactly while it is visible.
     */
    private static final Set<StreamPopOutWindow> OPEN_WINDOWS = new HashSet<>();

    private final Stage stage;
    private final VideoSinkView videoSinkView;
    private final String userId;
    private final Runnable onClosed;

    private boolean cancelled = false;

    public StreamPopOutWindow(String userId, String username, Runnable onClosed) {
        this.userId = userId;
        this.onClosed = onClosed;

        videoSinkView = new VideoSinkView();

        StackPane root = new StackPane(videoSinkView);
        root.setStyle("-fx-background-color: -color-bg-void;");

        Scene scene = new Scene(root, 960, 580);

        stage = new Stage(StageStyle.DECORATED);
        stage.setTitle(username + "'s Screen — Live");
        stage.setMinWidth(480);
        stage.setMinHeight(320);
        stage.setScene(scene);
        // Owning the pop-out to the main window groups them at the OS/window-manager
        // level (e.g. closing together on some platforms, shared taskbar grouping) —
        // App.stop()/setOnCloseRequest is still what guarantees the hard teardown.
        stage.initOwner(App.getPrimaryStage());
        stage.getIcons().addAll(((Stage) App.getStackPane().getScene().getWindow()).getIcons());

        stage.setOnHiding(e -> {
            OPEN_WINDOWS.remove(this);
            App.getWebrtcRoomClient().unsubscribeRemoteVideo(userId);
            videoSinkView.dispose();
            if (!cancelled && onClosed != null) onClosed.run();
        });
    }

    /** Subscribes the video sink and makes the window visible. */
    public void show() {
        OPEN_WINDOWS.add(this);
        App.getWebrtcRoomClient().subscribeToRemoteVideo(userId, videoSinkView);
        if (isWindows()) {
            stage.setOpacity(0);
            stage.show();
            String title = stage.getTitle();
            Thread.ofVirtual().start(() -> {
                WindowsThemeUtil.enableDarkTitleBar(title);
                Platform.runLater(() -> stage.setOpacity(1));
            });
        } else {
            stage.show();
        }
    }

    /**
     * Closes the window without firing the reattach callback.
     * Called when the originating tile is being permanently removed.
     */
    public void closeAndCancel() {
        cancelled = true;
        stage.close();
    }

    /**
     * Closes every open pop-out window showing {@code streamerUserId}'s stream,
     * without firing reattach callbacks. Safety net for when the stream ends and
     * no live {@link StreamTile} owns the window anymore (e.g. the server page
     * was replaced). Must be called on the FX thread.
     */
    public static void closeAllForStreamer(String streamerUserId) {
        for (StreamPopOutWindow w : List.copyOf(OPEN_WINDOWS)) {
            if (w.userId.equals(streamerUserId)) w.closeAndCancel();
        }
    }

    /**
     * Closes every open pop-out window without firing reattach callbacks.
     * Used on teardown paths (logout, server switch, voice disconnect) so no
     * detached stream window is ever left hanging. Must be called on the FX thread.
     */
    public static void closeAll() {
        List.copyOf(OPEN_WINDOWS).forEach(StreamPopOutWindow::closeAndCancel);
    }
}
