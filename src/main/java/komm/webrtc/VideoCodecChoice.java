package komm.webrtc;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which video codec — and therefore which encoder — a screen share uses.
 *
 * <p>The peer-connection factory always carries webrtc-java's {@code HardwareVideoEncoderFactory}:
 * for every codec it puts the GPU encoder first where the machine has one and falls back to the
 * software encoder of the same codec on its own (no encoder sessions left, driver failure, …).
 * Picking the codec is therefore what picks GPU vs CPU: H.264 / AV1 are only offered where the GPU
 * encodes them (OpenH264 and libaom are far too slow for a real-time 1080p+ share), VP8 / VP9 are
 * the libvpx software encoders.
 *
 * <p>{@link #AUTO} resolves to GPU H.264 when available and to CPU VP8 otherwise, and any saved
 * GPU choice that the current machine can't encode in hardware falls back to CPU VP8.
 */
@Getter
public enum VideoCodecChoice {

    AUTO(null, null, "Auto", false),
    H264("H264", "h264", "H.264", true),
    AV1("AV1", "av1", "AV1", true),
    VP9("VP9", "vp9", "VP9", false),
    VP8("VP8", "vp8", "VP8", false);

    /** Codec name as negotiated in SDP / listed in RTP capabilities. */
    private final String sdpName;
    /** Codec name LiveKit expects in {@code AddTrackRequest.simulcast_codecs}. */
    private final String livekitName;
    private final String displayName;
    /** Only offered where the GPU encodes this codec. */
    private final boolean hardwareOnly;

    VideoCodecChoice(String sdpName, String livekitName, String displayName, boolean hardwareOnly) {
        this.sdpName = sdpName;
        this.livekitName = livekitName;
        this.displayName = displayName;
        this.hardwareOnly = hardwareOnly;
    }

    /** True if this machine encodes this codec on the GPU. */
    public boolean isHardwareEncoded() {
        return sdpName != null && VideoCodecSupport.isHardwareEncoded(sdpName);
    }

    /** The concrete codec a share will use for this choice on this machine (never {@link #AUTO}). */
    public VideoCodecChoice resolve() {
        if (this == AUTO) return H264.isHardwareEncoded() ? H264 : VP8;
        if (hardwareOnly && !isHardwareEncoded()) return VP8;
        return this;
    }

    /** "H.264 · GPU", "VP8 · CPU", or for AUTO "Auto (H.264 · GPU)". */
    public String label() {
        if (this == AUTO) return "Auto (" + resolve().label() + ")";
        return displayName + (isHardwareEncoded() ? " · GPU" : " · CPU");
    }

    /** Choices worth offering on this machine, AUTO first. */
    public static List<VideoCodecChoice> available() {
        List<VideoCodecChoice> out = new ArrayList<>();
        for (VideoCodecChoice c : values()) {
            if (!c.hardwareOnly || c.isHardwareEncoded()) out.add(c);
        }
        return out;
    }

    /** Parses a persisted value; unknown / missing values mean {@link #AUTO}. */
    public static VideoCodecChoice fromSetting(String value) {
        if (value == null || value.isBlank()) return AUTO;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return AUTO;
        }
    }
}
