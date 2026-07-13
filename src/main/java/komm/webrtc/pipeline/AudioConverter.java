package komm.webrtc.pipeline;

/**
 * Stateless PCM conversion helpers used across the audio pipeline.
 * Ported from Java-vad reference project.
 */
public final class AudioConverter {

    private AudioConverter() {}

    /** Normalize short[] → float[] in range [-1, +1]. */
    public static float[] shortToFloat(short[] input) {
        float[] out = new float[input.length];
        for (int i = 0; i < input.length; i++) {
            out[i] = input[i] / 32768.0f;
        }
        return out;
    }

    /** Convert float[] [-1, +1] → short[] with saturation clamping. */
    public static short[] floatToShort(float[] input) {
        short[] out = new short[input.length];
        for (int i = 0; i < input.length; i++) {
            float s = Math.max(-1.0f, Math.min(1.0f, input[i]));
            out[i] = (short) (s * 32767.0f);
        }
        return out;
    }

    /** Pack short[] → little-endian byte[]. */
    public static byte[] shortToBytes(short[] input) {
        byte[] out = new byte[input.length * 2];
        for (int i = 0; i < input.length; i++) {
            out[i * 2]     = (byte) (input[i] & 0xFF);
            out[i * 2 + 1] = (byte) ((input[i] >> 8) & 0xFF);
        }
        return out;
    }

    /** Unpack little-endian byte[] → short[]. */
    public static short[] bytesToShort(byte[] input) {
        short[] out = new short[input.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (short) ((input[i * 2] & 0xFF) | ((input[i * 2 + 1] & 0xFF) << 8));
        }
        return out;
    }

    /** RMS level of a raw short frame (result in range 0–1). */
    public static float rms(short[] frame) {
        double sum = 0;
        for (short s : frame) {
            double n = s / 32768.0;
            sum += n * n;
        }
        return (float) Math.sqrt(sum / frame.length);
    }

    /** RMS level of a normalized float frame. */
    public static float rms(float[] frame) {
        double sum = 0;
        for (float s : frame) sum += (double) s * s;
        return (float) Math.sqrt(sum / frame.length);
    }
}
