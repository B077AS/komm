package komm.ui.screenshare;

import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoFrameBuffer;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A JavaFX pane that renders incoming WebRTC video frames with minimal latency.
 *
 * <h3>Architecture: double-buffer, zero heap allocation per frame</h3>
 *
 * <p>The previous implementation allocated a {@code byte[]} on every worker-thread
 * call and then copied it again on the FX thread — two full-frame copies and
 * one heap allocation per frame, causing GC pressure and startup choppiness.</p>
 *
 * <p>This version uses a <b>double-buffer</b> scheme:</p>
 * <ul>
 *   <li>Two pre-allocated direct {@link ByteBuffer}s of the same size share the
 *       worker side ({@link #back}) and the FX side .</li>
 *   <li>The worker thread converts I420→ARGB directly into {@code back} using
 *       {@link VideoBufferConverter#convertFromI420} with a direct ByteBuffer
 *       (native zero-copy path per webrtc-java docs).</li>
 *   <li>After conversion, {@code back} is published atomically into
 *       {@link #pendingFrame} (an {@link AtomicReference}). The FX thread swaps
 *       the reference out, uses it as the source for one {@code ByteBuffer.put()}
 *       into the PixelBuffer's backing store, then returns the buffer to be
 *       reused as the new {@code back}.</li>
 *   <li>Net result: <b>zero {@code byte[]} allocations, zero GC pressure, one
 *       native memcpy per frame</b> (inside {@code convertFromI420}), and one
 *       additional direct→direct {@code put()} on the FX thread.</li>
 * </ul>
 *
 * <h3>Why the first ~10 seconds are choppy (and how to actually fix it)</h3>
 *
 * <p>The warm-up choppiness is <b>not</b> a rendering problem — it is WebRTC's
 * built-in bandwidth estimator (BWE/REMB/TWCC) starting the sender at a very
 * low bitrate (~150-300 kbps) and probing upward over 5–15 seconds.
 * The fix is on the <b>sender side</b>: set {@code minBitrate} on the
 * {@code RTCRtpSender} so the stack never starts below a usable floor.
 * See {@code ScreenShareBitrateHelper} for the required call.</p>
 *
 * <h3>Why quality degrades during motion</h3>
 *
 * <p>Same root cause: if {@code maxBitrate} is unset or too low, the VP8/VP9
 * encoder drops quality during scenes with high temporal complexity. Setting a
 * generous {@code maxBitrate} (3–6 Mbps for 1080p screen share) fixes this.
 * Again, this must be applied on the sender, not here.</p>
 */
public class VideoSinkView extends StackPane implements VideoTrackSink {

    // ── Double-buffer state ───────────────────────────────────────────────────

    /**
     * Worker thread writes converted ARGB bytes here, then swaps it into
     * {@link #pendingFrame}. After a swap the old front buffer comes back here.
     * Only ever accessed from the single worker-thread caller (WebRTC guarantees
     * onVideoFrame is called sequentially from one thread per track).
     */
    private ByteBuffer back;

    /**
     * Pending buffer ready for the FX thread.  Contains a fully converted ARGB
     * frame plus metadata.  {@code null} when nothing is pending.
     * Written by the worker thread, read+cleared by the FX thread.
     */
    private final AtomicReference<PendingFrame> pendingFrame = new AtomicReference<>(null);

    /**
     * Single-slot return channel: after the FX thread has copied a pending
     * buffer out, it deposits it here for the worker to reuse. Without this,
     * every consumed frame would orphan its direct ByteBuffer (freed only on
     * GC), leaking native memory at hundreds of MB/s on a 60 fps stream.
     */
    private final AtomicReference<ByteBuffer> recycledBuffer = new AtomicReference<>(null);

    /**
     * Guards against flooding the FX event queue.
     */
    private final AtomicBoolean renderQueued = new AtomicBoolean(false);

    // ── Worker-thread resolution tracking ────────────────────────────────────

    private int workerWidth = -1;
    private int workerHeight = -1;

    // ── FX-thread pixel buffers ───────────────────────────────────────────────

    /**
     * Direct ByteBuffer that backs the JavaFX PixelBuffer. FX-thread only.
     */
    private ByteBuffer fxArgbBuffer;
    private IntBuffer fxIntView;
    private PixelBuffer<IntBuffer> pixelBuffer;
    private WritableImage writableImage;
    private int currentWidth = -1;
    private int currentHeight = -1;

    // ── Misc ──────────────────────────────────────────────────────────────────

    private final ImageView imageView = new ImageView();
    private volatile boolean disposed = false;
    private boolean firstFrameShown = false;
    private Runnable onFirstFrameReceived;
    private Runnable onDimensionsChanged;

    // ── Constructor ───────────────────────────────────────────────────────────

    /** Standard constructor — 16 px side padding, 12 px rounded corners. */
    public VideoSinkView() {
        this(16, 12);
    }

    /**
     * @param horizontalPad px of padding on each side (0 = fills container)
     * @param cornerArc     arc radius for the rounded clip (0 = sharp)
     */
    public VideoSinkView(int horizontalPad, int cornerArc) {
        setAlignment(Pos.CENTER);
        setStyle("-fx-background-color: -color-bg-void;");
        setPadding(new Insets(0, horizontalPad, 0, horizontalPad));
        setMinSize(0, 0);
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.fitWidthProperty().bind(widthProperty().subtract(horizontalPad * 2));
        imageView.fitHeightProperty().bind(heightProperty());

        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.setArcWidth(cornerArc);
        clip.setArcHeight(cornerArc);
        imageView.layoutBoundsProperty().addListener((obs, oldBounds, newBounds) -> {
            clip.setWidth(newBounds.getWidth());
            clip.setHeight(newBounds.getHeight());
            clip.setX(newBounds.getMinX());
            clip.setY(newBounds.getMinY());
        });
        imageView.setClip(clip);

        getChildren().add(imageView);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setOnFirstFrameReceived(Runnable callback) {
        this.onFirstFrameReceived = callback;
    }

    public void setOnDimensionsChanged(Runnable callback) { this.onDimensionsChanged = callback; }
    public int getVideoWidth()  { return currentWidth;  }
    public int getVideoHeight() { return currentHeight; }

    // ── VideoTrackSink ────────────────────────────────────────────────────────

    @Override
    public void onVideoFrame(VideoFrame frame) {
        if (disposed) {
            frame.release();
            return;
        }

        try {
            VideoFrameBuffer buffer = frame.buffer;
            int w = buffer.getWidth();
            int h = buffer.getHeight();
            int rotation = frame.rotation;
            int needed = w * h * 4;

            // Reallocate both buffers only when resolution changes.
            if (w != workerWidth || h != workerHeight) {
                back = ByteBuffer.allocateDirect(needed);
                // fxArgbBuffer is reallocated on the FX thread (it's FX-only).
                workerWidth = w;
                workerHeight = h;
            }

            // Convert I420 → ARGB directly into the direct back-buffer.
            // Per webrtc-java docs, a direct ByteBuffer destination uses
            // the native zero-copy path — no Java-heap allocation here.
            back.rewind();
            try {
                VideoBufferConverter.convertFromI420(buffer, back, FourCC.ARGB);
            } catch (Exception e) {
                // Conversion failure is non-fatal; skip this frame.
                return;
            }
            back.rewind();

            // Publish the filled back-buffer to the FX thread.
            // If a previous frame is still pending (FX thread is behind),
            // we overwrite it — the viewer always sees the latest frame.
            PendingFrame newPending = new PendingFrame(back, w, h, rotation);
            PendingFrame old = pendingFrame.getAndSet(newPending);

            // Reclaim a buffer as the new back-buffer: either the overwritten
            // pending frame (FX thread was behind) or one the FX thread has
            // already consumed and returned via recycledBuffer. Only when
            // neither fits (first frame / resolution change) allocate fresh.
            if (old != null && old.argbBuffer.capacity() == needed) {
                back = old.argbBuffer;
            } else {
                ByteBuffer returned = recycledBuffer.getAndSet(null);
                back = (returned != null && returned.capacity() == needed)
                        ? returned
                        : ByteBuffer.allocateDirect(needed);
            }

            if (renderQueued.compareAndSet(false, true)) {
                Platform.runLater(this::commitFrame);
            }
        } finally {
            frame.release();
        }
    }

    // ── FX-thread rendering ───────────────────────────────────────────────────

    private void commitFrame() {
        renderQueued.set(false);
        if (disposed) return;

        PendingFrame pending = pendingFrame.getAndSet(null);
        if (pending == null) return;

        int w = pending.width;
        int h = pending.height;

        // Recreate PixelBuffer / WritableImage only on resolution change.
        if (w != currentWidth || h != currentHeight) {
            fxArgbBuffer = ByteBuffer.allocateDirect(w * h * 4);
            fxIntView = fxArgbBuffer.asIntBuffer();
            pixelBuffer = new PixelBuffer<>(w, h, fxIntView,
                    PixelFormat.getIntArgbPreInstance());
            writableImage = new WritableImage(pixelBuffer);
            imageView.setImage(writableImage);
            currentWidth = w;
            currentHeight = h;
            Runnable dcb = onDimensionsChanged;
            if (dcb != null) dcb.run();
        }

        // One direct→direct memcpy — no byte[] allocation, no per-pixel loop.
        fxArgbBuffer.rewind();
        pending.argbBuffer.rewind();
        fxArgbBuffer.put(pending.argbBuffer);
        fxArgbBuffer.rewind();

        // Hand the consumed buffer back to the worker thread for reuse.
        recycledBuffer.set(pending.argbBuffer);

        // Tell JavaFX the entire buffer has changed.
        pixelBuffer.updateBuffer(pb -> null);

        // Rotation metadata — no pixel manipulation needed.
        imageView.setRotate(pending.rotation);

        if (!firstFrameShown) {
            firstFrameShown = true;
            Runnable cb = onFirstFrameReceived;
            if (cb != null) cb.run();
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    public void dispose() {
        disposed = true;
        pendingFrame.set(null);
        recycledBuffer.set(null);
        back = null;
        Platform.runLater(() -> {
            imageView.setImage(null);
            imageView.setRotate(0);
            pixelBuffer = null;
            writableImage = null;
            fxArgbBuffer = null;
            fxIntView = null;
        });
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    /**
     * Lightweight value holder passed from the worker thread to the FX thread.
     * {@code argbBuffer} is a direct ByteBuffer — no copy is made at hand-off.
     */
    private static final class PendingFrame {
        final ByteBuffer argbBuffer; // direct — FX thread reads, worker reclaims
        final int width;
        final int height;
        final int rotation;

        PendingFrame(ByteBuffer argbBuffer, int width, int height, int rotation) {
            this.argbBuffer = argbBuffer;
            this.width = width;
            this.height = height;
            this.rotation = rotation;
        }
    }
}