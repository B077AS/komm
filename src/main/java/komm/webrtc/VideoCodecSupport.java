package komm.webrtc;

import dev.onvoid.webrtc.media.video.codec.HardwareVideoDecoderFactory;
import dev.onvoid.webrtc.media.video.codec.HardwareVideoEncoderFactory;
import dev.onvoid.webrtc.media.video.codec.VideoCodecInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Creates the video codec factories for the single {@code PeerConnectionFactory} and remembers
 * which codecs this machine encodes on the GPU (probed once per process by webrtc-java).
 *
 * <p>Hardware codecs can be switched off entirely with {@code -Dkomm.video.hardware=false}; the
 * factory is then built with webrtc-java's default (software) codecs, exactly as before.
 */
@Slf4j
public final class VideoCodecSupport {

    private static volatile Set<String> hardwareEncoded = Collections.emptySet();

    private VideoCodecSupport() {}

    public static boolean hardwareEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty("komm.video.hardware"));
    }

    /** Hardware-first encoder factory, or null to keep webrtc-java's default one. */
    public static HardwareVideoEncoderFactory createEncoderFactory() {
        if (!hardwareEnabled()) {
            log.info("[Video] Hardware video codecs disabled (-Dkomm.video.hardware=false)");
            return null;
        }
        try {
            HardwareVideoEncoderFactory f = new HardwareVideoEncoderFactory();
            Set<String> names = new LinkedHashSet<>();
            for (VideoCodecInfo c : f.getHardwareCodecs()) names.add(c.getName().toUpperCase(Locale.ROOT));
            hardwareEncoded = Collections.unmodifiableSet(names);
            log.info("[Video] GPU-encoded codecs on this machine: {}", names.isEmpty() ? "none" : names);
            return f;
        } catch (Throwable t) {
            log.warn("[Video] Hardware encoder factory unavailable — using software encoders: {}", t.toString());
            return null;
        }
    }

    /**
     * Hardware-first decoder factory, or null (the default) to keep webrtc-java's software
     * decoders. Opt in with {@code -Dkomm.video.hardwareDecode=true}: GPU-decoded frames are
     * copied back to system memory, which at screen-share sizes measured slower than software
     * (1080p60 H.264 on Windows: Media Foundation 4.5 ms/frame vs FFmpeg 1.9 ms/frame), and
     * creating the GPU decoders costs memory for the whole session.
     */
    public static HardwareVideoDecoderFactory createDecoderFactory() {
        if (!hardwareEnabled() || !Boolean.getBoolean("komm.video.hardwareDecode")) return null;
        try {
            return new HardwareVideoDecoderFactory();
        } catch (Throwable t) {
            log.warn("[Video] Hardware decoder factory unavailable — using software decoders: {}", t.toString());
            return null;
        }
    }

    public static boolean isHardwareEncoded(String sdpCodecName) {
        return sdpCodecName != null && hardwareEncoded.contains(sdpCodecName.toUpperCase(Locale.ROOT));
    }
}
