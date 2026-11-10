package komm.media;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * An {@link ImageView} that plays an animated GIF from its compressed bytes.
 *
 * <p>Memory is one canvas + one texture per view no matter how many frames the GIF has
 * (see {@link GifDecoder}); frames are decoded just in time on a shared background pool and
 * blitted into a {@link PixelBuffer} on the FX thread.
 *
 * <p>The view manages itself: it only decodes while it is in a scene whose window is showing
 * and not minimised, and it drops its buffers as soon as it leaves the scene — so a cell that
 * scrolls out of a virtualized list costs nothing even if nobody calls {@link #dispose()}.
 * All methods are FX-thread only.
 */
public class GifView extends ImageView {

    private static final AtomicInteger THREAD_NO = new AtomicInteger();
    private static final ScheduledExecutorService EXEC = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "gif-anim-" + THREAD_NO.incrementAndGet());
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    /** How often a view whose window is hidden / minimised checks whether it may resume. */
    private static final long IDLE_POLL_MS = 400;

    private final ByteBuffer data;
    private final int srcW, srcH;
    /** Nearest-neighbour subsampling step for GIFs far bigger than they are displayed. */
    private final int step;
    private final int outW, outH;

    private boolean playing = true;
    private boolean disposed;
    private Run current;

    private IntBuffer out;
    private PixelBuffer<IntBuffer> pixelBuffer;

    private Runnable onFirstFrame;
    private Consumer<Throwable> onError;

    /** One uninterrupted playback; replaced whenever the view leaves and re-enters a scene. */
    private final class Run {
        GifDecoder decoder;
        long nextDueNanos;
        boolean shownAny;
        boolean parked;
    }

    /**
     * @param data        the GIF file (heap or memory-mapped; only read with absolute gets)
     * @param maxDisplayW largest logical width this view will be shown at (0 = never subsample)
     * @param maxDisplayH largest logical height this view will be shown at (0 = never subsample)
     * @throws IllegalArgumentException if {@code data} is not a usable GIF
     */
    public GifView(byte[] data, double maxDisplayW, double maxDisplayH) {
        this(ByteBuffer.wrap(data), maxDisplayW, maxDisplayH);
    }

    public GifView(ByteBuffer data, double maxDisplayW, double maxDisplayH) {
        int[] dims = GifDecoder.isGif(data)
                ? new int[]{(data.get(6) & 0xFF) | (data.get(7) & 0xFF) << 8, (data.get(8) & 0xFF) | (data.get(9) & 0xFF) << 8}
                : null;
        if (dims == null
                || (long) dims[0] * dims[1] > GifDecoder.MAX_PIXELS || dims[0] <= 0 || dims[1] <= 0) {
            throw new IllegalArgumentException("not a usable GIF");
        }
        this.data = data;
        this.srcW = dims[0];
        this.srcH = dims[1];
        int s = 1;
        if (maxDisplayW > 0 && maxDisplayH > 0) {
            double scale = MediaImages.renderScale();
            s = (int) Math.floor(Math.min(srcW / (maxDisplayW * scale), srcH / (maxDisplayH * scale)));
        }
        this.step = Math.max(1, s);
        this.outW = Math.max(1, srcW / step);
        this.outH = Math.max(1, srcH / step);

        setPreserveRatio(true);
        setSmooth(true);
        sceneProperty().addListener((o, a, b) -> sync());
        sync();
    }

    public int getSourceWidth() {
        return srcW;
    }

    public int getSourceHeight() {
        return srcH;
    }

    /** Pauses on the current frame / resumes. Unlike leaving the scene this keeps the picture. */
    public void setPlaying(boolean playing) {
        if (this.playing == playing) return;
        this.playing = playing;
        Run r = current;
        if (playing && r != null && r.parked) {
            r.parked = false;
            EXEC.execute(() -> decode(r));
        }
    }

    public void setOnFirstFrame(Runnable r) {
        this.onFirstFrame = r;
    }

    public void setOnError(Consumer<Throwable> c) {
        this.onError = c;
    }

    /** Stops for good and frees the buffers. */
    public void dispose() {
        disposed = true;
        onFirstFrame = null;
        onError = null;
        sync();
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    private void sync() {
        boolean want = !disposed && getScene() != null;
        if (want && current == null) {
            Run r = new Run();
            current = r;
            EXEC.execute(() -> decode(r));
        } else if (!want && current != null) {
            current = null; // an in-flight decode finishes on its own decoder and is dropped
            setImage(null);
            pixelBuffer = null;
            out = null;
        }
    }

    /** Background: produce the next frame on the run's canvas, then hand it to the FX thread when due. */
    private void decode(Run r) {
        try {
            if (r.decoder == null) r.decoder = new GifDecoder(data);
            GifDecoder dec = r.decoder;
            if (!dec.nextFrame()) {
                if (dec.frameIndex() <= 1) {
                    // single image (or nothing decodable): nothing to animate
                    boolean empty = dec.frameIndex() == 0;
                    r.decoder = null;
                    if (empty && !r.shownAny) {
                        Platform.runLater(() -> fail(r, new IllegalStateException("GIF has no frames")));
                    }
                    return;
                }
                dec.rewind();
                if (!dec.nextFrame()) return;
            }
        } catch (Throwable t) {
            Platform.runLater(() -> fail(r, t));
            return;
        }
        long wait = r.nextDueNanos - System.nanoTime();
        if (r.shownAny && wait > 1_000_000) {
            EXEC.schedule(() -> Platform.runLater(() -> present(r)), wait, TimeUnit.NANOSECONDS);
        } else {
            Platform.runLater(() -> present(r));
        }
    }

    private void present(Run r) {
        if (r != current) return;
        GifDecoder dec = r.decoder;
        if (dec == null) return;

        if (pixelBuffer == null) {
            out = ByteBuffer.allocateDirect(outW * outH * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
            pixelBuffer = new PixelBuffer<>(outW, outH, out, PixelFormat.getIntArgbPreInstance());
            setImage(new WritableImage(pixelBuffer));
        }
        int[] canvas = dec.canvas();
        if (step == 1) {
            out.put(0, canvas, 0, outW * outH);
        } else {
            for (int y = 0; y < outH; y++) {
                int src = y * step * srcW;
                int dst = y * outW;
                for (int x = 0; x < outW; x++) out.put(dst + x, canvas[src + x * step]);
            }
        }
        pixelBuffer.updateBuffer(pb -> null);

        r.nextDueNanos = System.nanoTime() + dec.delayMs() * 1_000_000L;
        if (!r.shownAny) {
            r.shownAny = true;
            Runnable cb = onFirstFrame;
            if (cb != null) cb.run();
        }
        advance(r);
    }

    private void advance(Run r) {
        if (r != current) return;
        if (!playing) {
            r.parked = true;
            return;
        }
        if (!windowVisible()) {
            EXEC.schedule(() -> Platform.runLater(() -> advance(r)), IDLE_POLL_MS, TimeUnit.MILLISECONDS);
            return;
        }
        EXEC.execute(() -> decode(r));
    }

    private boolean windowVisible() {
        Scene s = getScene();
        if (s == null) return false;
        Window w = s.getWindow();
        if (w == null || !w.isShowing()) return false;
        return !(w instanceof Stage st && st.isIconified());
    }

    private void fail(Run r, Throwable t) {
        if (r != current) return;
        Consumer<Throwable> cb = onError;
        if (cb != null) cb.accept(t);
    }
}
