package komm.webrtc.pipeline;

import dev.onvoid.webrtc.media.audio.AudioProcessing;
import dev.onvoid.webrtc.media.audio.AudioProcessingConfig;
import dev.onvoid.webrtc.media.audio.AudioProcessingStreamConfig;
import lombok.extern.slf4j.Slf4j;

/**
 * Wraps WebRTC AEC3 via webrtc-java AudioProcessing.
 *
 * Note: for full AEC effectiveness the render (speaker playback) signal should be fed via
 * processRenderFrame() before each mic processCapture(). In Komm's current architecture the
 * speaker output is rendered by the hardware ADM, so we cannot easily intercept that signal.
 * AEC3 still provides some suppression through its internal model even without explicit render
 * frames — users with speakers will see reduced echo, users with headphones are unaffected.
 *
 * Processes in two 10 ms sub-frames as required by the WebRTC APM (each call to
 * processStream/processReverseStream must be exactly 10 ms of audio).
 *
 * Ported from Java-vad reference project.
 */
@Slf4j
public class AecProcessor implements AutoCloseable {

    private static final int SAMPLE_RATE  = 48_000;
    private static final int NUM_CHANNELS = 1;
    /** 10 ms @ 48 kHz × 1 channel × 2 bytes/sample */
    private static final int HALF_BYTES   = 480 * 2;

    private AudioProcessing apm;
    private final AudioProcessingStreamConfig streamCfg =
            new AudioProcessingStreamConfig(SAMPLE_RATE, NUM_CHANNELS);

    private volatile boolean enabled = true;

    public AecProcessor() {
        initialize();
    }

    private void initialize() {
        try {
            AudioProcessingConfig cfg = new AudioProcessingConfig();
            cfg.echoCanceller.enabled = true;
            apm = new AudioProcessing();
            apm.applyConfig(cfg);
            log.info("[AEC] WebRTC AEC3 initialized");
        } catch (Exception e) {
            log.error("[AEC] Failed to initialize AEC3: {}", e.getMessage(), e);
            apm = null;
        }
    }

    /**
     * Process a 960-sample (20 ms @ 48 kHz) capture frame through AEC3.
     * Internally split into two 10 ms sub-frames as required by the WebRTC APM.
     */
    public void processCapture(short[] input, short[] output) {
        if (!enabled || apm == null) {
            System.arraycopy(input, 0, output, 0, input.length);
            return;
        }
        try {
            byte[] bytes  = AudioConverter.shortToBytes(input);
            byte[] result = new byte[bytes.length];

            byte[] half0 = new byte[HALF_BYTES], half1 = new byte[HALF_BYTES];
            byte[] out0  = new byte[HALF_BYTES], out1  = new byte[HALF_BYTES];
            System.arraycopy(bytes, 0,          half0, 0, HALF_BYTES);
            System.arraycopy(bytes, HALF_BYTES, half1, 0, HALF_BYTES);

            apm.processStream(half0, streamCfg, streamCfg, out0);
            apm.processStream(half1, streamCfg, streamCfg, out1);

            System.arraycopy(out0, 0, result, 0,          HALF_BYTES);
            System.arraycopy(out1, 0, result, HALF_BYTES, HALF_BYTES);

            short[] s = AudioConverter.bytesToShort(result);
            System.arraycopy(s, 0, output, 0, s.length);
        } catch (Exception e) {
            log.warn("[AEC] processCapture error: {}", e.getMessage());
            System.arraycopy(input, 0, output, 0, input.length);
        }
    }

    /**
     * Feed a render (speaker playback) frame to AEC3 as echo reference.
     * Call this with decoded remote audio frames just before they reach the speaker
     * if the render signal can be intercepted at the application level.
     */
    public void processRenderFrame(short[] renderFrame) {
        if (!enabled || apm == null) return;
        try {
            byte[] bytes = AudioConverter.shortToBytes(renderFrame);
            byte[] half0 = new byte[HALF_BYTES], half1 = new byte[HALF_BYTES];
            System.arraycopy(bytes, 0,          half0, 0, HALF_BYTES);
            System.arraycopy(bytes, HALF_BYTES, half1, 0, HALF_BYTES);
            apm.processReverseStream(half0, streamCfg, streamCfg, half0);
            apm.processReverseStream(half1, streamCfg, streamCfg, half1);
        } catch (Exception e) {
            log.warn("[AEC] processRenderFrame error: {}", e.getMessage());
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (apm == null) return;
        try {
            AudioProcessingConfig cfg = new AudioProcessingConfig();
            cfg.echoCanceller.enabled = enabled;
            apm.applyConfig(cfg);
        } catch (Exception e) {
            log.warn("[AEC] Failed to apply config: {}", e.getMessage());
        }
    }

    public boolean isEnabled() { return enabled; }

    @Override
    public void close() {
        if (apm != null) {
            try { apm.dispose(); } catch (Exception ignored) {}
            apm = null;
        }
    }
}
