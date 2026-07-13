package komm.webrtc.pipeline;

import dev.onvoid.webrtc.media.audio.AudioProcessing;
import dev.onvoid.webrtc.media.audio.AudioProcessingConfig;
import dev.onvoid.webrtc.media.audio.AudioProcessingStreamConfig;
import lombok.extern.slf4j.Slf4j;

/**
 * Wraps WebRTC AGC2 (Adaptive Gain Control) via webrtc-java AudioProcessing.
 *
 * Uses adaptiveDigital mode which works well with high-end microphones that already
 * have flat frequency response and good SNR — it normalizes levels without the
 * analog-style gain pumping of older AGC implementations.
 *
 * Splits 20 ms frames into two 10 ms halves as required by the WebRTC APM.
 *
 * Ported from Java-vad reference project.
 */
@Slf4j
public class AgcProcessor implements AutoCloseable {

    private static final int SAMPLE_RATE  = 48_000;
    private static final int NUM_CHANNELS = 1;
    /** 10 ms @ 48 kHz × 1 channel × 2 bytes/sample */
    private static final int HALF_BYTES   = 480 * 2;

    private AudioProcessing apm;
    private final AudioProcessingStreamConfig streamCfg =
            new AudioProcessingStreamConfig(SAMPLE_RATE, NUM_CHANNELS);

    private volatile boolean enabled = true;

    public AgcProcessor(boolean enabled) {
        this.enabled = enabled;
        initialize();
    }

    private void initialize() {
        try {
            AudioProcessingConfig cfg = new AudioProcessingConfig();
            cfg.gainControl.enabled = true;
            cfg.gainControl.adaptiveDigital.enabled = true;
            apm = new AudioProcessing();
            apm.applyConfig(cfg);
            log.info("[AGC] WebRTC AGC initialized (adaptiveDigital enabled)");
        } catch (Exception e) {
            log.error("[AGC] Failed to initialize AGC: {}", e.getMessage(), e);
            apm = null;
        }
    }

    /**
     * Process a 960-sample (20 ms @ 48 kHz) capture frame through AGC.
     * Internally split into two 10 ms sub-frames as required by the WebRTC APM.
     */
    public void processFrame(short[] input, short[] output) {
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
            log.warn("[AGC] processFrame error: {}", e.getMessage());
            System.arraycopy(input, 0, output, 0, input.length);
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (apm == null) return;
        try {
            AudioProcessingConfig cfg = new AudioProcessingConfig();
            cfg.gainControl.enabled = enabled;
            cfg.gainControl.adaptiveDigital.enabled = enabled;
            apm.applyConfig(cfg);
        } catch (Exception e) {
            log.warn("[AGC] Failed to apply config: {}", e.getMessage());
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
