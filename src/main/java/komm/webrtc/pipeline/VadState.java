package komm.webrtc.pipeline;

/**
 * Persistent LSTM hidden state for Silero VAD.
 * Must be carried across consecutive audio frames — the model is stateful.
 */
public class VadState {

    /** h tensor: shape [2, 1, 64] — LSTM hidden state. */
    public float[][][] h = new float[2][1][64];

    /** c tensor: shape [2, 1, 64] — LSTM cell state. */
    public float[][][] c = new float[2][1][64];

    /** Last computed speech probability (0.0 – 1.0). */
    public volatile float speechProbability = 0.0f;

    /** True while speech is considered active (including hangover period). */
    public volatile boolean isSpeechActive = false;

    /** Hangover counter in 16 kHz samples remaining before deactivation. */
    public int hangoverSamplesLeft = 0;

    public void reset() {
        h = new float[2][1][64];
        c = new float[2][1][64];
        speechProbability = 0.0f;
        isSpeechActive = false;
        hangoverSamplesLeft = 0;
    }
}
