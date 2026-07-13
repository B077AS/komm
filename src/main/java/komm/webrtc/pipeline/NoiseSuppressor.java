package komm.webrtc.pipeline;

import de.maxhenkel.rnnoise4j.Denoiser;
import dev.onvoid.webrtc.media.audio.AudioProcessing;
import dev.onvoid.webrtc.media.audio.AudioProcessingConfig;
import dev.onvoid.webrtc.media.audio.AudioProcessingStreamConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Noise suppression with two backends:
 *  1. RNNoise (via rnnoise4j) — primary, 48 kHz native, bundles native library
 *  2. WebRTC NS              — fallback when rnnoise4j fails to load
 *
 * RNNoise processes 480-sample (10 ms) frames; our 960-sample (20 ms) pipeline
 * frame is split into two halves before inference.
 *
 * A dedicated inference thread decouples CPU-bound denoising from the real-time
 * audio capture thread. The ring buffers introduce ~20 ms of additional latency
 * (one frame) on startup, then run in steady state.
 *
 * Ported from Java-vad reference project.
 */
@Slf4j
public class NoiseSuppressor implements AutoCloseable {

    private static final int SAMPLE_RATE   = 48_000;
    private static final int FRAME_SIZE    = 960;    // 20 ms @ 48 kHz
    private static final int RNNOISE_FRAME = 480;    // 10 ms — RNNoise native frame size
    private static final int HALF_BYTES    = 480 * 2;

    // ── RNNoise primary backend ───────────────────────────────────────────────
    private Denoiser denoiser;
    private boolean  rnnoiseLoaded = false;

    // ── WebRTC NS fallback ────────────────────────────────────────────────────
    private AudioProcessing fallbackApm;
    private final AudioProcessingStreamConfig streamCfg =
            new AudioProcessingStreamConfig(SAMPLE_RATE, 1);

    // ── Async inference ───────────────────────────────────────────────────────
    private final RingBuffer    inputRing  = new RingBuffer(FRAME_SIZE * 16);
    private final RingBuffer    outputRing = new RingBuffer(FRAME_SIZE * 16);
    private final AtomicBoolean running    = new AtomicBoolean(false);
    private Thread inferenceThread;

    // Holds the most-recent valid NS output frame (float, length FRAME_SIZE).
    // Used as a fallback when the inference thread hasn't produced a new result yet,
    // so raw (unfiltered) audio never reaches VAD — which would let clicks through.
    private float[] lastNsOutput = new float[FRAME_SIZE]; // zeros until first result

    private volatile boolean enabled      = true;
    private volatile boolean useRNNoise   = true;
    private volatile boolean rnnoiseFailed = false;

    /** Human-readable status shown in settings UI. */
    public volatile String backendStatus = "Initializing…";

    public NoiseSuppressor(boolean enabled) {
        this.enabled = enabled;
        initFallbackApm();
        tryLoadRNNoise();
        startInferenceThread();
    }

    // ── Initialization ────────────────────────────────────────────────────────

    private void initFallbackApm() {
        try {
            AudioProcessingConfig cfg = new AudioProcessingConfig();
            cfg.noiseSuppression.enabled = true;
            cfg.noiseSuppression.level   = AudioProcessingConfig.NoiseSuppression.Level.HIGH;
            fallbackApm = new AudioProcessing();
            fallbackApm.applyConfig(cfg);
            log.info("[NS] WebRTC NS fallback initialized");
        } catch (Exception e) {
            log.error("[NS] Failed to initialize WebRTC NS fallback: {}", e.getMessage(), e);
        }
    }

    private void tryLoadRNNoise() {
        try {
            denoiser = new Denoiser();
            rnnoiseLoaded = true;
            backendStatus = "RNNoise (native, 48 kHz)";
            log.info("[NS] RNNoise loaded — frame size {} samples", denoiser.getFrameSize());
        } catch (Exception e) {
            log.warn("[NS] RNNoise unavailable ({}), using WebRTC NS fallback", e.getMessage());
            backendStatus = "WebRTC NS (RNNoise unavailable)";
            rnnoiseFailed = true;
        }
    }

    private void startInferenceThread() {
        running.set(true);
        inferenceThread = new Thread(this::inferenceLoop, "komm-ns-inference");
        inferenceThread.setDaemon(true);
        inferenceThread.setPriority(Thread.NORM_PRIORITY);
        inferenceThread.start();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Submit a 960-sample (20 ms @ 48 kHz) frame for noise suppression.
     * Non-blocking: writes to input ring, reads last processed output.
     * Introduces ~1 frame (20 ms) startup latency, then runs in steady state.
     */
    public void processFrame(short[] input, short[] output) {
        if (!enabled) {
            System.arraycopy(input, 0, output, 0, input.length);
            return;
        }

        inputRing.write(AudioConverter.shortToFloat(input));

        float[] processed = new float[FRAME_SIZE];
        int read = outputRing.read(processed, 0, FRAME_SIZE);
        if (read < FRAME_SIZE) {
            // Inference hasn't caught up yet (startup lag or GC pause).
            // NEVER fall back to raw input: a loud click would bypass NS and
            // reach VAD unfiltered, potentially leaking it into the stream.
            // Hold the last valid NS output instead — a 20 ms audio "hold" is
            // inaudible whereas an unfiltered click is clearly audible.
            short[] s = AudioConverter.floatToShort(lastNsOutput);
            System.arraycopy(s, 0, output, 0, s.length);
        } else {
            // Cache this frame so the next catch-up fallback uses clean audio.
            System.arraycopy(processed, 0, lastNsOutput, 0, FRAME_SIZE);
            short[] s = AudioConverter.floatToShort(processed);
            System.arraycopy(s, 0, output, 0, s.length);
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (fallbackApm != null) {
            try {
                AudioProcessingConfig cfg = new AudioProcessingConfig();
                cfg.noiseSuppression.enabled = enabled;
                cfg.noiseSuppression.level   = AudioProcessingConfig.NoiseSuppression.Level.HIGH;
                fallbackApm.applyConfig(cfg);
            } catch (Exception e) {
                log.warn("[NS] Failed to apply fallback config: {}", e.getMessage());
            }
        }
    }

    public boolean isEnabled()       { return enabled; }
    public boolean isRNNoiseActive() { return rnnoiseLoaded && !rnnoiseFailed; }

    // ── Inference loop ────────────────────────────────────────────────────────

    private void inferenceLoop() {
        float[] frameBuf = new float[FRAME_SIZE];
        while (running.get()) {
            try {
                int read = inputRing.readBlocking(frameBuf, 0, FRAME_SIZE, 100);
                if (read < FRAME_SIZE) continue;

                float[] processed = isRNNoiseActive()
                        ? runRNNoise(frameBuf)
                        : runWebRtcNs(frameBuf);
                outputRing.write(processed);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[NS] Inference loop error: {}", e.getMessage(), e);
                outputRing.write(frameBuf);
            }
        }
    }

    private float[] runRNNoise(float[] frame) {
        short[] pcm = AudioConverter.floatToShort(frame);

        short[] half0 = new short[RNNOISE_FRAME];
        short[] half1 = new short[RNNOISE_FRAME];
        System.arraycopy(pcm, 0,            half0, 0, RNNOISE_FRAME);
        System.arraycopy(pcm, RNNOISE_FRAME, half1, 0, RNNOISE_FRAME);

        denoiser.denoiseInPlace(half0);
        denoiser.denoiseInPlace(half1);

        short[] out = new short[FRAME_SIZE];
        System.arraycopy(half0, 0, out, 0,             RNNOISE_FRAME);
        System.arraycopy(half1, 0, out, RNNOISE_FRAME, RNNOISE_FRAME);
        return AudioConverter.shortToFloat(out);
    }

    private float[] runWebRtcNs(float[] frame) {
        if (fallbackApm == null) return frame;
        try {
            byte[] bytes  = AudioConverter.shortToBytes(AudioConverter.floatToShort(frame));
            byte[] result = new byte[bytes.length];

            byte[] half0 = new byte[HALF_BYTES], half1 = new byte[HALF_BYTES];
            byte[] out0  = new byte[HALF_BYTES], out1  = new byte[HALF_BYTES];
            System.arraycopy(bytes, 0,          half0, 0, HALF_BYTES);
            System.arraycopy(bytes, HALF_BYTES, half1, 0, HALF_BYTES);

            fallbackApm.processStream(half0, streamCfg, streamCfg, out0);
            fallbackApm.processStream(half1, streamCfg, streamCfg, out1);

            System.arraycopy(out0, 0, result, 0,          HALF_BYTES);
            System.arraycopy(out1, 0, result, HALF_BYTES, HALF_BYTES);

            return AudioConverter.shortToFloat(AudioConverter.bytesToShort(result));
        } catch (Exception e) {
            log.warn("[NS] WebRTC NS fallback error: {}", e.getMessage());
            return frame;
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    @Override
    public void close() {
        running.set(false);
        if (inferenceThread != null) {
            inferenceThread.interrupt();
            try { inferenceThread.join(2000); } catch (InterruptedException ignored) {}
        }
        if (denoiser != null) {
            try { denoiser.close(); } catch (Exception ignored) {}
        }
        if (fallbackApm != null) {
            try { fallbackApm.dispose(); } catch (Exception ignored) {}
        }
    }
}
