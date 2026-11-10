package komm.media;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Streaming GIF decoder: holds the compressed file plus ONE composited canvas and decodes
 * a frame at a time ({@link #nextFrame()}), instead of materialising every frame up front
 * the way {@code javafx.scene.image.Image} does (frames x width x height x 4 bytes — tens to
 * hundreds of MB for an ordinary reaction GIF).
 *
 * <p>Not thread-safe; one caller at a time. Truncated / corrupt data never throws out of
 * {@link #nextFrame()} — it just ends the stream early.
 */
public final class GifDecoder {

    /** Upper bound for the logical screen; anything bigger is refused. */
    public static final int MAX_PIXELS = 16_000_000;

    private static final int MAX_CODES = 4096;

    private final ByteBuffer data;
    private final int length;
    private final byte[] block = new byte[256];
    private final int width;
    private final int height;
    private final int[] globalTable;
    private final int firstBlockPos;

    /** Composited ARGB frame; alpha is only ever 0x00 or 0xFF, so it is also valid premultiplied. */
    private final int[] canvas;

    private int pos;
    private int frameIndex;
    private int delayMs;

    // Graphic Control Extension state for the upcoming frame
    private int gceDispose;
    private int gceDelayCs;
    private boolean gceTransparent;
    private int gceTransparentIndex;

    // previous frame, for its disposal
    private boolean hasPrev;
    private int prevDispose;
    private int prevX, prevY, prevW, prevH;
    private int[] restoreBuf;

    // LZW state, reused across frames
    private final short[] prefix = new short[MAX_CODES];
    private final byte[] suffix = new byte[MAX_CODES];
    private final byte[] pixelStack = new byte[MAX_CODES + 1];
    private final int[] localTable = new int[256];
    private byte[] indexBuf = new byte[0];

    public static boolean isGif(byte[] d) {
        return d != null && isGif(ByteBuffer.wrap(d));
    }

    public static boolean isGif(ByteBuffer d) {
        return d != null && d.limit() >= 13
                && d.get(0) == 'G' && d.get(1) == 'I' && d.get(2) == 'F' && d.get(3) == '8'
                && (d.get(4) == '7' || d.get(4) == '9') && d.get(5) == 'a';
    }

    /**
     * @throws IllegalArgumentException not a GIF, or its logical screen is empty / over {@link #MAX_PIXELS}
     */
    public GifDecoder(byte[] data) {
        this(data == null ? null : ByteBuffer.wrap(data));
    }

    /**
     * @param data the whole file; only absolute reads are used, so the buffer (e.g. a memory-mapped
     *             file) can be shared between decoders
     */
    public GifDecoder(ByteBuffer data) {
        if (!isGif(data)) throw new IllegalArgumentException("not a GIF");
        this.data = data;
        this.length = data.limit();
        this.width = u16(6);
        this.height = u16(8);
        if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
            throw new IllegalArgumentException("unsupported GIF size " + width + "x" + height);
        }
        int packed = data.get(10) & 0xFF;
        int p = 13;
        if ((packed & 0x80) != 0) {
            int n = 2 << (packed & 7);
            if (p + n * 3 > length) throw new IllegalArgumentException("truncated GIF");
            globalTable = new int[256];
            Arrays.fill(globalTable, 0xFF000000);
            readTable(p, n, globalTable);
            p += n * 3;
        } else {
            globalTable = null;
        }
        this.firstBlockPos = p;
        this.pos = p;
        this.canvas = new int[width * height];
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** The composited frame after the last successful {@link #nextFrame()}. */
    public int[] canvas() {
        return canvas;
    }

    /** How long the current frame stays up, in ms (browser convention: 0-10 ms means 100 ms). */
    public int delayMs() {
        return delayMs;
    }

    /** Frames decoded since construction / the last {@link #rewind()}. */
    public int frameIndex() {
        return frameIndex;
    }

    /** Back to before the first frame, with a cleared canvas. */
    public void rewind() {
        pos = firstBlockPos;
        frameIndex = 0;
        hasPrev = false;
        resetGce();
        Arrays.fill(canvas, 0);
    }

    /**
     * Composites the next frame onto {@link #canvas()}.
     *
     * @return false at the end of the stream (the canvas is then unchanged)
     */
    public boolean nextFrame() {
        try {
            while (pos < length) {
                int block = data.get(pos++) & 0xFF;
                if (block == 0x2C) {
                    if (pos + 9 > length) break;
                    if (hasPrev) disposePrevious();
                    readImage();
                    return true;
                } else if (block == 0x21) {
                    int label = data.get(pos++) & 0xFF;
                    if (label == 0xF9) readGraphicControl();
                    else skipSubBlocks();
                } else if (block == 0x3B) {
                    break;
                } else if (block != 0x00) {
                    break; // garbage — stop rather than misparse
                }
            }
        } catch (IndexOutOfBoundsException truncated) {
            // fall through: end of stream
        }
        pos = length;
        return false;
    }

    // ── block parsing ─────────────────────────────────────────────────────────

    private void readGraphicControl() {
        int size = data.get(pos++) & 0xFF;
        if (size >= 4) {
            int packed = data.get(pos) & 0xFF;
            gceDispose = (packed >> 2) & 7;
            gceTransparent = (packed & 1) != 0;
            gceDelayCs = u16(pos + 1);
            gceTransparentIndex = data.get(pos + 3) & 0xFF;
        }
        pos += size;
        skipSubBlocks();
    }

    private void skipSubBlocks() {
        while (pos < length) {
            int n = data.get(pos++) & 0xFF;
            if (n == 0) return;
            pos += n;
        }
    }

    private void resetGce() {
        gceDispose = 0;
        gceDelayCs = 0;
        gceTransparent = false;
        gceTransparentIndex = 0;
    }

    private void disposePrevious() {
        if (prevW <= 0 || prevH <= 0) return;
        if (prevDispose == 2) {
            for (int y = prevY; y < prevY + prevH; y++) {
                int row = y * width;
                Arrays.fill(canvas, row + prevX, row + prevX + prevW, 0);
            }
        } else if (prevDispose == 3 && restoreBuf != null) {
            for (int y = 0; y < prevH; y++) {
                System.arraycopy(restoreBuf, y * prevW, canvas, (prevY + y) * width + prevX, prevW);
            }
        }
    }

    private void readImage() {
        int ix = u16(pos), iy = u16(pos + 2), iw = u16(pos + 4), ih = u16(pos + 6);
        int packed = data.get(pos + 8) & 0xFF;
        pos += 9;
        boolean interlaced = (packed & 0x40) != 0;

        int[] table = globalTable;
        if ((packed & 0x80) != 0) {
            int n = 2 << (packed & 7);
            Arrays.fill(localTable, 0xFF000000);
            readTable(pos, Math.min(n, Math.max(0, (length - pos) / 3)), localTable);
            pos += n * 3;
            table = localTable;
        } else if (table == null) {
            Arrays.fill(localTable, 0xFF000000);
            table = localTable;
        }

        // visible part of the frame
        int cx = Math.min(ix, width), cy = Math.min(iy, height);
        int cw = Math.max(0, Math.min(ix + iw, width) - cx);
        int ch = Math.max(0, Math.min(iy + ih, height) - cy);

        int decoded = 0;
        long npixLong = (long) iw * ih;
        if (npixLong > 0 && npixLong <= 4L * MAX_PIXELS) {
            int npix = (int) npixLong;
            if (indexBuf.length < npix) indexBuf = new byte[npix];
            decoded = decodeLzw(npix);
        } else {
            pos++; // LZW minimum code size
            skipSubBlocks();
        }

        if (gceDispose == 3 && cw > 0 && ch > 0) {
            if (restoreBuf == null || restoreBuf.length < cw * ch) restoreBuf = new int[cw * ch];
            for (int y = 0; y < ch; y++) {
                System.arraycopy(canvas, (cy + y) * width + cx, restoreBuf, y * cw, cw);
            }
        }

        if (decoded > 0 && cw > 0) {
            boolean transparent = gceTransparent;
            int tIdx = gceTransparentIndex;
            int pass = 0, inc = 8, line = 0;
            for (int i = 0; i < ih && i * iw < decoded; i++) {
                int destRow;
                if (interlaced) {
                    while (line >= ih && pass < 3) {
                        pass++;
                        if (pass == 1) { line = 4; inc = 8; }
                        else if (pass == 2) { line = 2; inc = 4; }
                        else { line = 1; inc = 2; }
                    }
                    destRow = line;
                    line += inc;
                } else {
                    destRow = i;
                }
                int y = iy + destRow;
                if (destRow >= ih || y >= height) continue;
                int src = i * iw;
                int n = Math.min(cw, decoded - src);
                int dst = y * width + cx;
                if (transparent) {
                    for (int k = 0; k < n; k++) {
                        int idx = indexBuf[src + k] & 0xFF;
                        if (idx != tIdx) canvas[dst + k] = table[idx];
                    }
                } else {
                    for (int k = 0; k < n; k++) {
                        canvas[dst + k] = table[indexBuf[src + k] & 0xFF];
                    }
                }
            }
        }

        hasPrev = true;
        prevDispose = gceDispose;
        prevX = cx;
        prevY = cy;
        prevW = cw;
        prevH = ch;
        int ms = gceDelayCs * 10;
        delayMs = ms <= 10 ? 100 : ms;
        frameIndex++;
        resetGce();
    }

    /** Decodes one image's LZW stream into {@link #indexBuf}; leaves {@link #pos} after its terminator. */
    private int decodeLzw(int npix) {
        final ByteBuffer d = data;
        final int len = length;
        final byte[] blk = block;
        int bp = 0;
        int minCodeSize = d.get(pos++) & 0xFF;
        if (minCodeSize < 1 || minCodeSize > 11) {
            skipSubBlocks();
            return 0;
        }
        final int clear = 1 << minCodeSize;
        final int eoi = clear + 1;
        int available = clear + 2;
        int oldCode = -1;
        int codeSize = minCodeSize + 1;
        int codeMask = (1 << codeSize) - 1;
        for (int c = 0; c < clear; c++) {
            prefix[c] = 0;
            suffix[c] = (byte) c;
        }

        int datum = 0, bits = 0, count = 0, first = 0, top = 0, pi = 0;
        boolean terminated = false;
        final byte[] out = indexBuf;

        while (pi < npix) {
            if (top == 0) {
                if (bits < codeSize) {
                    if (count == 0) {
                        if (pos >= len) { terminated = true; break; }
                        count = d.get(pos++) & 0xFF;
                        if (count == 0) { terminated = true; break; }
                        if (pos + count > len) count = len - pos; // truncated file
                        if (count <= 0) { terminated = true; break; }
                        d.get(pos, blk, 0, count);
                        pos += count;
                        bp = 0;
                    }
                    datum |= (blk[bp++] & 0xFF) << bits;
                    bits += 8;
                    count--;
                    continue;
                }
                int code = datum & codeMask;
                datum >>>= codeSize;
                bits -= codeSize;

                if (code > available || code == eoi) break;
                if (code == clear) {
                    codeSize = minCodeSize + 1;
                    codeMask = (1 << codeSize) - 1;
                    available = clear + 2;
                    oldCode = -1;
                    continue;
                }
                if (oldCode == -1) {
                    if (code >= clear) break;
                    pixelStack[top++] = suffix[code];
                    oldCode = code;
                    first = code;
                    continue;
                }
                int inCode = code;
                if (code == available) {
                    pixelStack[top++] = (byte) first;
                    code = oldCode;
                }
                while (code > clear) {
                    pixelStack[top++] = suffix[code];
                    code = prefix[code] & 0xFFFF;
                }
                first = suffix[code] & 0xFF;
                pixelStack[top++] = (byte) first;
                if (available < MAX_CODES) {
                    prefix[available] = (short) oldCode;
                    suffix[available] = (byte) first;
                    available++;
                    if ((available & codeMask) == 0 && available < MAX_CODES) {
                        codeSize++;
                        codeMask += available;
                    }
                }
                oldCode = inCode;
            }
            top--;
            out[pi++] = pixelStack[top];
        }

        if (!terminated) {
            skipSubBlocks();
        }
        return pi;
    }

    private void readTable(int at, int entries, int[] into) {
        for (int i = 0; i < entries && i < 256; i++) {
            int o = at + i * 3;
            into[i] = 0xFF000000 | (data.get(o) & 0xFF) << 16 | (data.get(o + 1) & 0xFF) << 8 | (data.get(o + 2) & 0xFF);
        }
    }

    private int u16(int at) {
        return (data.get(at) & 0xFF) | (data.get(at + 1) & 0xFF) << 8;
    }
}
