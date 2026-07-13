package komm.webrtc.audio;

import com.sun.jna.Platform;

import java.util.function.Consumer;

/**
 * Platform-agnostic contract for system audio loopback capture.
 *
 * <p>Use {@link #isSupported()} to gate the UI and {@link #create()} to obtain
 * the right implementation for the current OS. Each instance is single-use:
 * call {@link #start} once, then {@link #stop}.</p>
 *
 * <p>Implementations deliver 10 ms / 480-sample, 48 kHz mono 16-bit LE PCM
 * frames — exactly the format expected by
 * {@code CustomAudioSource.pushAudio(..., 16, 48_000, 1, 480)}.</p>
 *
 * <ul>
 *   <li><b>Windows 10 build 19041+</b> — {@link SystemAudioLoopbackCapture}:
 *       WASAPI process-loopback EXCLUDE; captures the full system mix minus
 *       Komm's own process tree to avoid echo.</li>
 *   <li><b>Linux (PulseAudio / PipeWire)</b> — {@link LinuxAudioLoopbackCapture}:
 *       PulseAudio Simple API capture. On PipeWire a {@link PipeWirePatchBay}
 *       taps every other application's audio into a dedicated capture sink,
 *       excluding Komm's own output (no echo, like Windows EXCLUDE). On classic
 *       PulseAudio-only systems it falls back to {@code @DEFAULT_MONITOR@},
 *       which includes Komm's playback (route Komm to a separate output device
 *       in pavucontrol to avoid echo).</li>
 * </ul>
 */
public interface AudioLoopbackCapture {

    /**
     * Starts capture on a dedicated daemon thread. Blocks until the backend is
     * open and recording, or throws {@link IllegalStateException} on failure.
     *
     * @param frameCallback receives one {@code byte[960]} per 10 ms
     *                      (480 mono 16-bit LE samples at 48 kHz)
     */
    void start(Consumer<byte[]> frameCallback);

    /** Stops capture and releases all native resources. Safe to call multiple times. */
    void stop();

    /** Returns {@code true} while a capture session is active. */
    boolean isRunning();

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if loopback capture is available on this OS / build.
     * Must be checked before calling {@link #create()}.
     */
    static boolean isSupported() {
        return SystemAudioLoopbackCapture.isSupported()
                || LinuxAudioLoopbackCapture.isSupported();
    }

    /**
     * Returns the correct implementation for the current platform.
     *
     * @throws UnsupportedOperationException if {@link #isSupported()} is false
     */
    static AudioLoopbackCapture create() {
        if (Platform.isWindows()) return new SystemAudioLoopbackCapture();
        if (Platform.isLinux())   return new LinuxAudioLoopbackCapture();
        throw new UnsupportedOperationException(
                "Audio loopback capture is not supported on this platform");
    }
}
