package komm.webrtc.pipeline;

import ai.onnxruntime.*;
import komm.Launcher;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Silero VAD (Voice Activity Detection) powered by ONNX Runtime.
 *
 * Runs at 48 kHz. Each 20 ms frame (960 samples) is 3:1 decimated to
 * 320 samples at 16 kHz before being fed to the Silero model, which requires
 * exactly 16 kHz input. Samples accumulate in a window buffer until 512 are
 * ready for a single inference pass (~0.5 ms on modern CPUs).
 *
 * The LSTM hidden states h/c are carried across frames (stateful model).
 * A configurable hangover period prevents clipping the end of words.
 *
 * The model (silero_vad.onnx) is bundled directly in the JAR under
 * /silero_vad.onnx and loaded from the classpath at startup.
 *
 * If the model fails to load VAD falls back to "always transmit" so voice
 * still works — the user just won't benefit from bandwidth savings.
 *
 * Ported from Java-vad reference project, adapted for Komm.
 */
@Slf4j
public class VadProcessor implements AutoCloseable {

    public static final int VAD_WINDOW_SIZE = 512;
    public static final int VAD_SAMPLE_RATE = 16_000;

    private static final String CLASSPATH_MODEL = "/silero_vad.onnx";

    private OrtEnvironment ortEnv;
    private OrtSession     vadSession;
    private boolean        modelLoaded = false;

    private final VadState state = new VadState();

    // Accumulator for 3:1 decimated samples (audio frames are 320, VAD needs 512)
    private final float[] accumulator  = new float[VAD_WINDOW_SIZE];
    private int           accumulatorPos = 0;

    private volatile boolean enabled        = true;
    private volatile double  threshold      = 0.5;
    private volatile int     hangoverSamples = (int)(0.3 * VAD_SAMPLE_RATE); // 300 ms default

    // Offset hysteresis: once the gate is open, a window only needs to score
    // threshold − 0.15 to keep it open. Matches Silero's reference VADIterator,
    // which uses the same offset so probability dips mid-sentence don't flap
    // the gate at high thresholds.
    private static final double OFFSET_HYSTERESIS = 0.15;

    // Onset gate: require this many consecutive above-threshold inference windows
    // before opening the transmit gate.  One inference window ≈ 32 ms at 16 kHz.
    // 2 windows = ~64 ms — long enough that a single transient can't open the gate,
    // short enough that word onsets aren't clipped.  The energy floor (onsetMinRms)
    // is the primary click filter; this is just the temporal backstop.
    private volatile int onsetCountRequired     = 2;
    private          int consecutiveHighInferences = 0;

    // Minimum RMS energy a frame must have (post-AGC, float [-1,1]) before its
    // Silero score counts toward the onset counter.  Provides a backstop when AGC
    // hasn't had time to fully ramp a brief transient up to full target level.
    // ~0.01 ≈ −40 dBFS — well below normal speech but above desk-transmitted clicks.
    private volatile float onsetMinRms = 0.01f;

    public volatile String statusMessage = "VAD not initialized";

    public VadProcessor(boolean enabled, double threshold) {
        this.enabled   = enabled;
        this.threshold = threshold;
        loadModel();
    }

    // ── Model loading ─────────────────────────────────────────────────────────

    private void loadModel() {
        try {
            Path modelPath = ensureModelOnDisk();
            if (modelPath == null) {
                statusMessage = "Model missing from JAR";
                return;
            }
            ortEnv = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(1);
            vadSession  = ortEnv.createSession(modelPath.toString(), opts);
            modelLoaded = true;
            statusMessage = "Silero VAD ready";
            log.info("[VAD] Silero model loaded from {}", modelPath);
        } catch (Exception e) {
            log.error("[VAD] Failed to load ONNX model: {}", e.getMessage(), e);
            statusMessage = "Load failed: " + e.getMessage();
        }
    }

    /**
     * Returns a path to silero_vad.onnx on disk, extracting it from the
     * bundled classpath resource if it isn't already in the data directory.
     */
    private Path ensureModelOnDisk() {
        Path target = Launcher.getDataDirectory().resolve("models").resolve("silero_vad.onnx");

        if (Files.exists(target) && isValidModel(target)) {
            log.debug("[VAD] Model already on disk at {}", target);
            return target;
        }

        try (InputStream is = getClass().getResourceAsStream(CLASSPATH_MODEL)) {
            if (is == null) {
                log.error("[VAD] Bundled model not found on classpath at {}", CLASSPATH_MODEL);
                return null;
            }
            Files.createDirectories(target.getParent());
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("[VAD] Model extracted from JAR to {}", target);
            return target;
        } catch (Exception e) {
            log.error("[VAD] Failed to extract model from JAR: {}", e.getMessage(), e);
            return null;
        }
    }

    private boolean isValidModel(Path path) {
        try { return Files.size(path) > 1024; } catch (Exception e) { return false; }
    }

    // ── Processing ────────────────────────────────────────────────────────────

    /**
     * Process a 960-sample (20 ms @ 48 kHz) float frame.
     * Decimates 3:1 to 16 kHz, accumulates until 512 samples are ready,
     * then runs a Silero inference pass.
     *
     * @param frame  float[960] normalized to [-1, 1] at 48 kHz
     * @return true if speech should be transmitted (VAD active or in hangover)
     */
    public boolean processFrame(float[] frame) {
        if (!enabled) {
            state.isSpeechActive    = true;
            state.speechProbability = 1.0f;
            return true;
        }
        if (!modelLoaded) {
            // Model not ready yet — pass everything through so voice still works
            state.isSpeechActive    = true;
            state.speechProbability = 1.0f;
            return true;
        }

        // 3:1 average decimation: 960 samples @ 48 kHz → 320 samples @ 16 kHz
        float[] downsampled = downsample3to1(frame);

        boolean inferenceFired = false;
        for (float s : downsampled) {
            accumulator[accumulatorPos++] = s;
            if (accumulatorPos >= VAD_WINDOW_SIZE) {
                runInference();
                accumulatorPos = 0;
                inferenceFired = true;
            }
        }

        // Gate decisions happen only at inference boundaries — not every frame.
        // A transient click fires the model for one window then immediately drops
        // back; real speech keeps the counter climbing.
        //
        // Closed → opening: require consecutive windows with probability ≥ threshold
        // AND frame RMS ≥ onsetMinRms. The energy floor exists because mouse clicks
        // are brief — even desk-conducted, AGC doesn't have time to ramp them up, so
        // post-AGC RMS stays well below normal speech. It is deliberately checked
        // only while the gate is CLOSED: it's a click backstop, not a speech gate,
        // and quiet mid-sentence stretches must not be able to cut an open gate.
        //
        // Open → staying open: probability ≥ threshold − OFFSET_HYSTERESIS refreshes
        // the hangover; the counter is retired so the hangover alone decides closure.
        if (inferenceFired) {
            if (state.isSpeechActive) {
                consecutiveHighInferences = 0;
                // Clamp the sustain threshold to half the onset threshold: a plain
                // (threshold − 0.15) goes to zero or below near the slider's minimum,
                // which would refresh the hangover on every window — even silence —
                // and latch the gate open permanently after the first sound.
                double sustainThreshold = Math.max(threshold - OFFSET_HYSTERESIS, threshold * 0.5);
                if (state.speechProbability >= sustainThreshold) {
                    state.hangoverSamplesLeft = hangoverSamples;
                }
            } else {
                float frameRms = rms(frame);
                if (state.speechProbability >= threshold && frameRms >= onsetMinRms) {
                    consecutiveHighInferences++;
                } else {
                    consecutiveHighInferences = 0;
                }
            }
        }

        // Onset gate: open only after N consecutive high-probability inference
        // windows.  Hangover is counted in 16 kHz samples (320 per frame).
        boolean speaking;
        if (consecutiveHighInferences >= onsetCountRequired) {
            state.hangoverSamplesLeft = hangoverSamples;
            speaking = true;
        } else if (state.hangoverSamplesLeft > 0) {
            state.hangoverSamplesLeft -= downsampled.length;
            speaking = true;
        } else {
            speaking = false;
        }
        state.isSpeechActive = speaking;
        return speaking;
    }

    private static float rms(float[] frame) {
        double sum = 0.0;
        for (float s : frame) sum += (double) s * s;
        return (float) Math.sqrt(sum / frame.length);
    }

    private static float[] downsample3to1(float[] in) {
        int outLen = in.length / 3;
        float[] out = new float[outLen];
        for (int i = 0; i < outLen; i++) {
            out[i] = (in[3 * i] + in[3 * i + 1] + in[3 * i + 2]) / 3.0f;
        }
        return out;
    }

    private void runInference() {
        try (OnnxTensor inputTensor = createInputTensor();
             OnnxTensor hTensor     = OnnxTensor.createTensor(ortEnv, state.h);
             OnnxTensor cTensor     = OnnxTensor.createTensor(ortEnv, state.c);
             OnnxTensor srTensor    = createSrTensor()) {

            Map<String, OnnxTensor> feeds = new HashMap<>();
            feeds.put("input", inputTensor);
            feeds.put("h",     hTensor);
            feeds.put("c",     cTensor);
            feeds.put("sr",    srTensor);

            try (OrtSession.Result result = vadSession.run(feeds)) {
                float[][] probArray = (float[][]) result.get("output").get().getValue();
                state.speechProbability = probArray[0][0];

                state.h = (float[][][]) result.get("hn").get().getValue();
                state.c = (float[][][]) result.get("cn").get().getValue();
            }
        } catch (OrtException e) {
            log.warn("[VAD] Inference error: {}", e.getMessage());
        }
    }

    private OnnxTensor createInputTensor() throws OrtException {
        float[][] data = new float[1][VAD_WINDOW_SIZE];
        System.arraycopy(accumulator, 0, data[0], 0, VAD_WINDOW_SIZE);
        return OnnxTensor.createTensor(ortEnv, data);
    }

    private OnnxTensor createSrTensor() throws OrtException {
        return OnnxTensor.createTensor(ortEnv,
                LongBuffer.wrap(new long[]{ VAD_SAMPLE_RATE }), new long[]{ 1 });
    }

    // ── Config ────────────────────────────────────────────────────────────────

    public void setEnabled(boolean enabled)     { this.enabled = enabled; }

    public void setThreshold(double threshold) {
        boolean stricter = threshold > this.threshold;
        this.threshold = threshold;
        // Raising the threshold drops the gate latch immediately: without this, an
        // already-open gate keeps transmitting through its hangover/hysteresis and the
        // slider feels dead until the room happens to go quiet. Speech re-opens the
        // gate within ~64 ms if it clears the new threshold, so the cut is barely
        // audible — but the setting takes effect the moment the user moves the slider.
        if (stricter) {
            state.hangoverSamplesLeft = 0;
            consecutiveHighInferences = 0;
        }
    }
    public void setHangoverMs(int hangoverMs)   {
        this.hangoverSamples = (int)(hangoverMs / 1000.0 * VAD_SAMPLE_RATE);
    }
    /** Minimum consecutive above-threshold inference windows before speech gate opens (~32 ms each). */
    public void setOnsetCount(int count)        { this.onsetCountRequired = Math.max(1, count); }
    /**
     * Minimum post-AGC frame RMS (float [-1,1]) required before an above-threshold
     * Silero window counts toward the onset gate.
     * Default 0.01 (~−40 dBFS).  Lower = more sensitive; raise to filter louder clicks.
     */
    public void setOnsetMinRms(float minRms)    { this.onsetMinRms = Math.max(0f, minRms); }

    public boolean isEnabled()           { return enabled; }
    public boolean isModelLoaded()       { return modelLoaded; }
    public float   getSpeechProbability(){ return state.speechProbability; }
    public boolean isSpeechActive()      { return state.isSpeechActive; }

    public void resetState() { state.reset(); accumulatorPos = 0; consecutiveHighInferences = 0; }

    @Override
    public void close() {
        try { if (vadSession != null) vadSession.close(); } catch (Exception ignored) {}
        try { if (ortEnv    != null) ortEnv.close();     } catch (Exception ignored) {}
    }
}
