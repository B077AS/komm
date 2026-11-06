package komm.webrtc.pipeline;

import dev.onvoid.webrtc.media.audio.StandaloneVoiceActivityDetector;
import lombok.extern.slf4j.Slf4j;

/**
 * Voice activity gate built on webrtc-java's stock {@link StandaloneVoiceActivityDetector}
 * (WebRTC's classic GMM speech detector, boolean per 10 ms).
 *
 * <h3>Why this detector, fed this way</h3>
 * <ul>
 *   <li>The pitch-based probability estimator and Silero were both tried and rejected: the
 *       estimator flagged only ~15% of real speech frames on a quiet mic, Silero needed the
 *       post-RNNoise signal and its own click workarounds.</li>
 *   <li>The detector must see audio from <em>before</em> noise suppression (RNNoise output
 *       confuses it). The pipeline feeds it the AEC output through a side-chain AGC that only
 *       exists for detection, so a quiet mic still reaches a level it handles.</li>
 *   <li>Keystrokes: RNNoise removes them from the cleaned signal (post-AGC about −65..−85 dBFS)
 *       but keeps speech (−22..−35), so the cleaned frame must carry real signal both to open
 *       the gate and to keep it open.</li>
 * </ul>
 *
 * <h3>Slider</h3>
 * One slider, four coordinated effects (see {@link #applySliderProfile}): detector
 * aggressiveness, onset length, how many 10 ms halves of a frame must be speech, and a loudness
 * floor on the RAW mic level (the only signal that still reflects how loudly you talk once AGC
 * has normalised everything).
 *
 * <p>If the native detector cannot be created the gate is bypassed (always transmit) so voice
 * still works. Called only from the audio pipeline thread, except the setters.
 */
@Slf4j
public class VadProcessor implements AutoCloseable {

    private static final int   CHUNK            = 480;      // 10 ms @ 48 kHz
    private static final int   WARMUP_CHUNKS    = 5;        // detector state settling after (re)start
    private static final int   FRAME_16K        = 320;      // hangover is counted in 16 kHz samples
    private static final int   HANGOVER_RATE    = 16_000;
    private static final float DEAD_MIC_RMS     = 0.0008f;  // ≈ −62 dBFS raw: no mic signal at all
    private static final float SUSTAIN_MIN_RMS  = 0.003f;   // ≈ −50 dBFS post-AGC: must carry signal to hold the gate

    private StandaloneVoiceActivityDetector detector;
    private int detectorCalls = 0;
    private final short[] chunkBuf = new short[CHUNK];

    private volatile boolean enabled = true;
    private volatile double  threshold = 0.5;
    private volatile int     hangoverSamples = (int) (0.3 * HANGOVER_RATE);   // 300 ms

    // Slider profile (derived from threshold).
    private volatile int    onsetCountRequired = 3;
    private volatile double gateLevel          = 0.5;     // share of a frame's chunks that must be speech
    private volatile float  rawFloorRms        = 0.01f;   // slider loudness floor, raw mic
    // Post-AGC floor for opening the gate (the "noise gate" user setting, −40 dBFS default).
    private volatile float  onsetMinRms        = 0.01f;

    // Gate state.
    private int     consecutiveHigh = 0;
    private int     hangoverLeft    = 0;
    private boolean speechActive    = false;
    private volatile float speechProbability = 0f;

    public volatile String statusMessage = "VAD not initialized";

    public VadProcessor(boolean enabled, double threshold) {
        this.enabled   = enabled;
        this.threshold = threshold;
        try {
            detector = new StandaloneVoiceActivityDetector(modeFor(threshold));
            applySliderProfile(threshold);
            statusMessage = "WebRTC VAD ready";
            log.info("[VAD] WebRTC standalone VAD ready, mode {}", modeFor(threshold));
        } catch (Throwable t) {
            statusMessage = "Load failed: " + t.getMessage();
            log.error("[VAD] WebRTC VAD failed to initialise — voice gate bypassed: {}", t.getMessage(), t);
        }
    }

    // ── Slider mapping ────────────────────────────────────────────────────────

    private static StandaloneVoiceActivityDetector.Mode modeFor(double t) {
        if (t < 0.35) return StandaloneVoiceActivityDetector.Mode.QUALITY;
        if (t < 0.60) return StandaloneVoiceActivityDetector.Mode.LOW_BITRATE;
        if (t < 0.80) return StandaloneVoiceActivityDetector.Mode.AGGRESSIVE;
        return StandaloneVoiceActivityDetector.Mode.VERY_AGGRESSIVE;
    }

    /**
     *  - detector aggressiveness (mode): QUALITY .. VERY_AGGRESSIVE
     *  - onset length: 2 / 3 / 4 frames (40 / 60 / 80 ms; the pipeline pre-roll is 80 ms so nothing is clipped)
     *  - strict end (&gt; 0.75): both 10 ms halves of a frame must be speech, not just one
     *  - loudness floor on the RAW mic level of the onset frame: −70 dBFS (slider min) .. −40 dBFS (max),
     *    about −56 dBFS at the 0.5 default — what makes "speak up to open" tangible
     */
    private void applySliderProfile(double t) {
        detector.setMode(modeFor(t));
        onsetCountRequired = t < 0.30 ? 2 : (t < 0.70 ? 3 : 4);
        gateLevel          = t > 0.75 ? 1.0 : 0.5;
        double clamped     = Math.max(0.10, Math.min(0.95, t));
        double db          = -70.0 + (clamped - 0.10) / 0.85 * 30.0;
        rawFloorRms        = (float) Math.pow(10.0, db / 20.0);
    }

    // ── Processing ────────────────────────────────────────────────────────────

    /**
     * Process one 960-sample (20 ms @ 48 kHz) frame.
     *
     * @param frame         post-AGC frame, float [-1,1] (cleaned signal: level checks)
     * @param detectorFrame what the detector analyses: AEC output after the side-chain AGC
     * @param rawRms        RMS of the raw mic frame, before AEC/NS/AGC
     * @return true if audio should be transmitted (speech or hangover)
     */
    public boolean processFrame(float[] frame, float[] detectorFrame, float rawRms) {
        if (!enabled || detector == null) {
            speechActive      = true;
            speechProbability = 1.0f;
            return true;
        }

        float p = detect(detectorFrame, rawRms);
        speechProbability = p;
        float frameRms = rms(frame);

        if (speechActive) {
            consecutiveHigh = 0;
            if (p >= 0.5 && frameRms >= SUSTAIN_MIN_RMS) hangoverLeft = hangoverSamples;
        } else {
            boolean levelOk = rawRms >= rawFloorRms && frameRms >= onsetMinRms;
            consecutiveHigh = (p >= gateLevel && levelOk) ? consecutiveHigh + 1 : 0;
        }

        boolean speaking;
        if (consecutiveHigh >= onsetCountRequired) {
            hangoverLeft = hangoverSamples;
            speaking = true;
        } else if (hangoverLeft > 0) {
            hangoverLeft -= FRAME_16K;
            speaking = true;
        } else {
            speaking = false;
        }
        speechActive = speaking;
        return speaking;
    }

    /** Share of the frame's 10 ms chunks flagged as speech (0, 0.5, 1); 0 on a dead mic / warm-up. */
    private float detect(float[] frame, float rawRms) {
        try {
            int hits = 0, chunks = 0;
            for (int off = 0; off + CHUNK <= frame.length; off += CHUNK) {
                for (int i = 0; i < CHUNK; i++) {
                    int v = Math.round(frame[off + i] * 32768f);
                    chunkBuf[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
                }
                // Always feed the detector: its noise model is adaptive.
                if (detector.isSpeech(chunkBuf, 48_000)) hits++;
                detectorCalls++;
                chunks++;
            }
            if (rawRms < DEAD_MIC_RMS || detectorCalls <= WARMUP_CHUNKS || chunks == 0) return 0f;
            return (float) hits / chunks;
        } catch (Exception e) {
            log.warn("[VAD] detector error: {}", e.getMessage());
            return 0f;
        }
    }

    private static float rms(float[] frame) {
        double sum = 0.0;
        for (float s : frame) sum += (double) s * s;
        return (float) Math.sqrt(sum / frame.length);
    }

    // ── Config ────────────────────────────────────────────────────────────────

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public void setThreshold(double threshold) {
        boolean stricter = threshold > this.threshold;
        this.threshold = threshold;
        if (detector != null) {
            try { applySliderProfile(threshold); } catch (Exception ignored) {}
        }
        // Raising the strictness drops the gate latch immediately so the slider takes effect the
        // moment it moves instead of after the hangover; speech re-opens it within ~60 ms.
        if (stricter) {
            hangoverLeft    = 0;
            consecutiveHigh = 0;
        }
    }

    public void setHangoverMs(int hangoverMs) {
        this.hangoverSamples = (int) (hangoverMs / 1000.0 * HANGOVER_RATE);
    }

    /**
     * Minimum post-AGC frame RMS (float [-1,1]) required to OPEN the gate (the "noise gate"
     * setting). Default 0.01 (≈ −40 dBFS). Checked only while the gate is closed.
     */
    public void setOnsetMinRms(float minRms) { this.onsetMinRms = Math.max(0f, minRms); }

    public boolean isEnabled()            { return enabled; }
    public boolean isModelLoaded()        { return detector != null; }
    public float   getSpeechProbability() { return speechProbability; }
    public boolean isSpeechActive()       { return speechActive; }

    public void resetState() {
        consecutiveHigh   = 0;
        hangoverLeft      = 0;
        speechActive      = false;
        speechProbability = 0f;
        detectorCalls     = 0;
        if (detector != null) {
            try { detector.reset(); } catch (Exception ignored) {}
        }
    }

    @Override
    public void close() {
        try { if (detector != null) detector.dispose(); } catch (Exception ignored) {}
        detector = null;
    }
}
