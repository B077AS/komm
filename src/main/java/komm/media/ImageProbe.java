package komm.media;

import java.util.Base64;

/** Reads pixel dimensions from an image file's header without decoding it. */
public final class ImageProbe {

    /** How much of a base64 payload {@link #sizeOfBase64} decodes while looking for the header. */
    private static final int BASE64_PREFIX_CHARS = 256 * 1024;

    private ImageProbe() {
    }

    /** @return {width, height} for GIF / PNG / JPEG / BMP, or null if unknown */
    public static int[] size(byte[] d) {
        if (d == null) return null;
        try {
            int n = d.length;
            if (GifDecoder.isGif(d)) {
                return dims(u16le(d, 6), u16le(d, 8));
            }
            if (n >= 24 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
                return dims(i32be(d, 16), i32be(d, 20));
            }
            if (n >= 26 && d[0] == 'B' && d[1] == 'M') {
                return dims(i32le(d, 18), Math.abs(i32le(d, 22)));
            }
            if (n >= 4 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) {
                int p = 2;
                while (p + 9 < n) {
                    if ((d[p] & 0xFF) != 0xFF) {
                        p++;
                        continue;
                    }
                    int marker = d[p + 1] & 0xFF;
                    if (marker == 0xFF) {
                        p++;
                        continue;
                    }
                    if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                        p += 2;
                        continue;
                    }
                    if (marker == 0xD9 || marker == 0xDA) return null;
                    boolean sof = marker >= 0xC0 && marker <= 0xCF
                            && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
                    if (sof) return dims(u16be(d, p + 7), u16be(d, p + 5));
                    p += 2 + u16be(d, p + 2);
                }
            }
        } catch (IndexOutOfBoundsException ignored) {
            // truncated header
        }
        return null;
    }

    /** Same as {@link #size(byte[])} but only decodes the start of a base64 string. */
    public static int[] sizeOfBase64(String b64) {
        if (b64 == null || b64.length() < 16) return null;
        try {
            int chars = Math.min(b64.length(), BASE64_PREFIX_CHARS);
            if (chars < b64.length()) chars -= chars % 4;
            return size(Base64.getDecoder().decode(b64.substring(0, chars)));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int[] dims(int w, int h) {
        return (w > 0 && h > 0) ? new int[]{w, h} : null;
    }

    private static int u16le(byte[] d, int p) {
        return (d[p] & 0xFF) | (d[p + 1] & 0xFF) << 8;
    }

    private static int u16be(byte[] d, int p) {
        return (d[p] & 0xFF) << 8 | (d[p + 1] & 0xFF);
    }

    private static int i32be(byte[] d, int p) {
        return (d[p] & 0xFF) << 24 | (d[p + 1] & 0xFF) << 16 | (d[p + 2] & 0xFF) << 8 | (d[p + 3] & 0xFF);
    }

    private static int i32le(byte[] d, int p) {
        return (d[p] & 0xFF) | (d[p + 1] & 0xFF) << 8 | (d[p + 2] & 0xFF) << 16 | (d[p + 3] & 0xFF) << 24;
    }
}
