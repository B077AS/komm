package komm.media;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.stage.Screen;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Decoding + in-memory caching of the still images shown in chat.
 *
 * <ul>
 *   <li>Images are decoded at the size they are displayed (times the screen scale), never at
 *       their native resolution — a 4000x3000 photo shown in a 320x240 box is ~0.3 MB, not 48.</li>
 *   <li>Decoded images live in one byte-budgeted LRU, so scrolling back and forth reuses them
 *       instead of decoding again, and the total is bounded no matter how much history is loaded.</li>
 *   <li>Pixel sizes are remembered separately (tiny), so a re-created cell knows its final
 *       height immediately even when its image has to be reloaded.</li>
 * </ul>
 */
public final class MediaImages {

    /** Budget for decoded stills kept around for reuse (images on screen are held by their views anyway). */
    private static final long CACHE_BUDGET_BYTES = 48L * 1024 * 1024;
    private static final int MAX_REMEMBERED_SIZES = 8192;

    private static final AtomicInteger THREAD_NO = new AtomicInteger();
    /** Shared pool for media downloads / disk reads / decodes. */
    public static final ExecutorService LOADER = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "media-loader-" + THREAD_NO.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private record Cached(Image image, long bytes) {
    }

    private static final LinkedHashMap<String, Cached> CACHE = new LinkedHashMap<>(64, 0.75f, true);
    private static long cacheBytes;

    private static final Map<String, int[]> SIZES = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, int[]> eldest) {
            return size() > MAX_REMEMBERED_SIZES;
        }
    };

    private static volatile double renderScale = -1;

    private MediaImages() {
    }

    // ── decoded-image cache ───────────────────────────────────────────────────

    public static Image cached(String key) {
        synchronized (CACHE) {
            Cached c = CACHE.get(key);
            return c == null ? null : c.image();
        }
    }

    public static void cache(String key, Image image) {
        if (image == null) return;
        long bytes = Math.max(1024, (long) (image.getWidth() * image.getHeight() * 4));
        if (bytes > CACHE_BUDGET_BYTES / 4) return; // too big to be worth pinning
        synchronized (CACHE) {
            Cached old = CACHE.put(key, new Cached(image, bytes));
            if (old != null) cacheBytes -= old.bytes();
            cacheBytes += bytes;
            Iterator<Map.Entry<String, Cached>> it = CACHE.entrySet().iterator();
            while (cacheBytes > CACHE_BUDGET_BYTES && it.hasNext()) {
                Map.Entry<String, Cached> e = it.next();
                if (e.getKey().equals(key)) continue;
                cacheBytes -= e.getValue().bytes();
                it.remove();
            }
        }
    }

    /** Cached image for {@code key}, creating (and caching) it with {@code loader} on a miss. */
    public static Image cachedOrCreate(String key, Supplier<Image> loader) {
        Image img = cached(key);
        if (img == null) {
            img = loader.get();
            cache(key, img);
        }
        return img;
    }

    public static void evict(String key) {
        synchronized (CACHE) {
            Cached old = CACHE.remove(key);
            if (old != null) cacheBytes -= old.bytes();
        }
    }

    // ── remembered pixel sizes ────────────────────────────────────────────────

    /** @return {width, height} previously seen for this media key, or null */
    public static int[] knownSize(String key) {
        synchronized (SIZES) {
            return SIZES.get(key);
        }
    }

    public static void rememberSize(String key, int w, int h) {
        if (w <= 0 || h <= 0) return;
        synchronized (SIZES) {
            SIZES.put(key, new int[]{w, h});
        }
    }

    // ── decoding ──────────────────────────────────────────────────────────────

    /**
     * Decodes a still image no larger than needed to show it in a {@code fitW x fitH} logical
     * box on the sharpest attached screen. Never upscales. Blocking — not for the FX thread.
     */
    public static Image decodeToFit(byte[] bytes, double fitW, double fitH) throws IOException {
        double scale = renderScale();
        double boxW = fitW * scale, boxH = fitH * scale;
        int[] size = ImageProbe.size(bytes);
        double reqW = boxW, reqH = boxH;
        if (size != null && size[0] <= boxW && size[1] <= boxH) {
            reqW = 0; // already small enough: keep native pixels
            reqH = 0;
        }
        OneShotStream in = new OneShotStream(bytes);
        Image img = new Image(in, reqW, reqH, true, true);
        in.release();
        if (img.isError()) {
            Throwable ex = img.getException();
            throw new IOException(ex != null ? ex.getMessage() : "undecodable image", ex);
        }
        return img;
    }

    /**
     * {@code javafx.scene.image.Image} keeps a reference to the stream it was built from for
     * as long as the image lives — with a plain ByteArrayInputStream that pins the whole
     * compressed file behind every decoded thumbnail. This one lets go of its array.
     */
    private static final class OneShotStream extends ByteArrayInputStream {
        private static final byte[] EMPTY = new byte[0];

        OneShotStream(byte[] bytes) {
            super(bytes);
        }

        void release() {
            buf = EMPTY;
            pos = 0;
            count = 0;
            mark = 0;
        }
    }

    /** Largest output scale among the screens (1..2); cached after the first FX-thread call. */
    public static double renderScale() {
        double s = renderScale;
        if (s > 0) return s;
        if (!Platform.isFxApplicationThread()) return 1.0;
        s = 1.0;
        try {
            for (Screen sc : Screen.getScreens()) {
                s = Math.max(s, Math.max(sc.getOutputScaleX(), sc.getOutputScaleY()));
            }
        } catch (RuntimeException ignored) {
        }
        renderScale = s = Math.min(2.0, s);
        return s;
    }
}
