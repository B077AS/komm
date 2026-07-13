package komm.webrtc.pipeline;

import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import lombok.extern.slf4j.Slf4j;

import javax.sound.sampled.*;

/**
 * Receives decoded PCM frames from a remote participant's soundboard WebRTC track,
 * applies the listener's personal volume, and plays them through a SourceDataLine.
 *
 * The matching AudioTrack is kept disabled in the hardware ADM (setEnabled(false))
 * so this sink is the sole audio output for that track — no double-playback.
 * One SoundboardReceiver is created per remote soundboard track, giving each
 * listener fully independent volume control over incoming soundboard audio.
 */
@Slf4j
public class SoundboardReceiver implements AudioTrackSink, AutoCloseable {

    // Playback line buffer capacity and the jitter cushion we prefill before
    // starting playback — see UserVoiceReceiver for the rationale (PipeWire's
    // tight default ALSA buffers underrun without a cushion).
    private static final int LINE_BUFFER_MS = 250;
    private static final int PREFILL_MS     = 60;

    private volatile float   volume;
    private volatile boolean active = true;
    private volatile String  outputDeviceName;
    private volatile boolean lineFailed = false;

    private volatile SourceDataLine line;
    private volatile int     prefillBytes;
    private volatile boolean started;

    // Reusable scaling buffer — onData is called from a single WebRTC audio thread.
    private byte[] scaleBuf = new byte[0];

    public SoundboardReceiver(float initialVolume, String outputDeviceName) {
        this.volume           = Math.max(0f, Math.min(1f, initialVolume));
        this.outputDeviceName = outputDeviceName;
    }

    // ── AudioTrackSink ────────────────────────────────────────────────────────

    @Override
    public void onData(byte[] data, int bitsPerSample, int sampleRate, int channels, int frames) {
        if (!active || lineFailed) return;

        SourceDataLine l = ensureLine(sampleRate, bitsPerSample, channels);
        if (l == null) return;

        float vol = volume;
        byte[] out = (vol >= 1.0f) ? data : scaleInPlace(data, vol);

        // Non-blocking: skip the frame if the buffer is full rather than stalling
        // the WebRTC audio thread.
        if (l.available() >= out.length) {
            l.write(out, 0, out.length);
        }
        // Hold playback until the prefill cushion is buffered, then keep it going.
        if (!started && (l.getBufferSize() - l.available()) >= prefillBytes) {
            l.start();
            started = true;
        }
    }

    // ── Public controls ───────────────────────────────────────────────────────

    public void setVolume(float v) {
        this.volume = Math.max(0f, Math.min(1f, v));
    }

    public void setActive(boolean active) {
        this.active = active;
        if (!active) {
            SourceDataLine l = line;
            if (l != null) l.flush();
            // Buffer was flushed empty — re-accumulate the cushion before resuming.
            started = false;
        }
    }

    public void setOutputDevice(String deviceName) {
        this.outputDeviceName = deviceName;
        lineFailed = false;
        closeLineQuietly();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private byte[] scaleInPlace(byte[] data, float vol) {
        if (scaleBuf.length < data.length) scaleBuf = new byte[data.length];
        for (int i = 0; i + 1 < data.length; i += 2) {
            short s = (short) ((data[i] & 0xFF) | ((data[i + 1] & 0xFF) << 8));
            int scaled = Math.round(s * vol);
            if (scaled > Short.MAX_VALUE)  scaled = Short.MAX_VALUE;
            if (scaled < Short.MIN_VALUE)  scaled = Short.MIN_VALUE;
            scaleBuf[i]     = (byte) (scaled & 0xFF);
            scaleBuf[i + 1] = (byte) ((scaled >> 8) & 0xFF);
        }
        return scaleBuf;
    }

    private synchronized SourceDataLine ensureLine(int sampleRate, int bitsPerSample, int channels) {
        SourceDataLine l = line;
        if (l != null && l.isOpen()) return l;
        if (lineFailed) return null;

        Mixer.Info preferred = resolveOutputMixer(outputDeviceName);
        AudioFormat fmt = new AudioFormat(sampleRate, bitsPerSample, channels, true, false);
        int bytesPerMs  = Math.max(1, sampleRate * channels * (bitsPerSample / 8) / 1000);
        int bufferBytes = bytesPerMs * LINE_BUFFER_MS;
        l = tryOpen(preferred, fmt, bufferBytes);

        if (l == null) {
            log.warn("[SoundboardReceiver] Cannot open output line for {}Hz {}bit", sampleRate, bitsPerSample);
            lineFailed = true;
            return null;
        }
        // Do NOT start() here — onData prefills the cushion first, then starts.
        prefillBytes = Math.min(bytesPerMs * PREFILL_MS, l.getBufferSize() / 2);
        started = false;
        line = l;
        return l;
    }

    private static SourceDataLine tryOpen(Mixer.Info preferred, AudioFormat fmt, int bufferBytes) {
        DataLine.Info fmtInfo = new DataLine.Info(SourceDataLine.class, fmt);
        Line.Info     anyInfo = new Line.Info(SourceDataLine.class);

        if (preferred != null) {
            SourceDataLine l = openFrom(AudioSystem.getMixer(preferred), fmtInfo, anyInfo, fmt, bufferBytes);
            if (l != null) return l;
        }
        for (Line.Info di : new Line.Info[]{fmtInfo, anyInfo}) {
            try { SourceDataLine l = (SourceDataLine) AudioSystem.getLine(di); openWithBuffer(l, fmt, bufferBytes); return l; }
            catch (Exception ignored) {}
        }
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            Mixer mx = AudioSystem.getMixer(mi);
            if (mx.getSourceLineInfo().length == 0) continue;
            SourceDataLine l = openFrom(mx, fmtInfo, anyInfo, fmt, bufferBytes);
            if (l != null) return l;
        }
        return null;
    }

    private static SourceDataLine openFrom(Mixer mx, DataLine.Info fmtInfo, Line.Info anyInfo, AudioFormat fmt, int bufferBytes) {
        for (Line.Info di : new Line.Info[]{fmtInfo, anyInfo}) {
            try { SourceDataLine l = (SourceDataLine) mx.getLine(di); openWithBuffer(l, fmt, bufferBytes); return l; }
            catch (Exception ignored) {}
        }
        return null;
    }

    /** Opens the line with an explicit buffer size, falling back to the default if rejected. */
    private static void openWithBuffer(SourceDataLine l, AudioFormat fmt, int bufferBytes) throws LineUnavailableException {
        try { l.open(fmt, bufferBytes); }
        catch (IllegalArgumentException e) { l.open(fmt); }
    }

    private static Mixer.Info resolveOutputMixer(String name) {
        if (name == null || "Default".equals(name)) return null;
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (info.getName().equals(name)
                    && AudioSystem.getMixer(info).getSourceLineInfo().length > 0) {
                return info;
            }
        }
        return null;
    }

    private void closeLineQuietly() {
        SourceDataLine l = line;
        line = null;
        started = false;
        if (l != null) { try { l.close(); } catch (Exception ignored) {} }
    }

    @Override
    public void close() {
        active = false;
        closeLineQuietly();
    }
}
