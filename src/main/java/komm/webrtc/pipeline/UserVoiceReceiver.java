package komm.webrtc.pipeline;

import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import lombok.extern.slf4j.Slf4j;

import javax.sound.sampled.*;

/**
 * Receives decoded PCM frames from a remote participant's mic WebRTC track,
 * applies the listener's personal volume, and plays them through a SourceDataLine.
 *
 * The matching AudioTrack is kept disabled in the hardware ADM (setEnabled(false))
 * so this sink is the sole audio output for that track — no double-playback.
 * One UserVoiceReceiver is created per remote mic track, giving each listener
 * fully independent volume control (0%–200%) over incoming voice audio.
 */
@Slf4j
public class UserVoiceReceiver implements AudioTrackSink, AutoCloseable {

    // Playback line buffer capacity and the jitter cushion we prefill before
    // starting playback. PipeWire's default ALSA buffers are far tighter than
    // old PulseAudio's, so without a cushion the near-empty line underruns on
    // the slightest scheduling jitter — heard as crackly / broken audio.
    private static final int LINE_BUFFER_MS = 250;
    private static final int PREFILL_MS     = 60;

    // Allows boosting above unity (up to 2.0 = 200%).
    private volatile float   volume;
    private volatile boolean active = true;
    private volatile String  outputDeviceName;
    private volatile boolean lineFailed = false;

    private volatile SourceDataLine line;
    private int     prefillBytes;   // guarded by `this`
    private boolean started;        // guarded by `this`

    // Reusable scaling buffer — onData is called from a single WebRTC audio thread.
    private byte[] scaleBuf = new byte[0];

    public UserVoiceReceiver(float initialVolume, String outputDeviceName) {
        this.volume           = Math.max(0f, Math.min(2f, initialVolume));
        this.outputDeviceName = outputDeviceName;
    }

    // ── AudioTrackSink ────────────────────────────────────────────────────────

    @Override
    public void onData(byte[] data, int bitsPerSample, int sampleRate, int channels, int frames) {
        if (!active || lineFailed) return;

        float vol = volume;
        byte[] out = (vol == 1.0f) ? data : scaleInPlace(data, vol);

        synchronized (this) {
            SourceDataLine l = ensureLine(sampleRate, bitsPerSample, channels);
            if (l == null) return;
            try {
                // Non-blocking: skip the frame if the buffer is full rather than
                // stalling the WebRTC audio thread.
                if (l.available() >= out.length) {
                    l.write(out, 0, out.length);
                }
                // Hold playback until the prefill cushion has accumulated, then
                // keep it running. This is what actually prevents the underruns.
                if (!started && (l.getBufferSize() - l.available()) >= prefillBytes) {
                    l.start();
                    started = true;
                }
            } catch (Exception e) {
                // Line died under us (device change / deactivate race) — drop it
                // and let the next frame open a fresh one.
                closeLineQuietly();
            }
        }
    }

    // ── Public controls ───────────────────────────────────────────────────────

    /** Volume in [0.0, 2.0]. 1.0 = 100%, 2.0 = 200%. */
    public void setVolume(float v) {
        this.volume = Math.max(0f, Math.min(2f, v));
    }

    public float getVolume() { return volume; }

    public void setActive(boolean active) {
        this.active = active;
        // Close the line instead of just flushing it: an open line that is left
        // running with no data underruns, and on Linux (ALSA/PulseAudio) playback
        // stays permanently crackly after writes resume. A fresh line is opened
        // lazily on the next frame.
        if (!active) closeLineQuietly();
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
            log.warn("[UserVoiceReceiver] Cannot open output line for {}Hz {}bit", sampleRate, bitsPerSample);
            lineFailed = true;
            return null;
        }
        // Do NOT start() here — onData accumulates the prefill cushion first and
        // starts playback once it is buffered. Cap the cushion to half the buffer
        // in case the mixer honoured a smaller size than requested.
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

    private synchronized void closeLineQuietly() {
        SourceDataLine l = line;
        line = null;
        started = false;
        if (l != null) {
            try { l.stop();  } catch (Exception ignored) {}
            try { l.flush(); } catch (Exception ignored) {}
            try { l.close(); } catch (Exception ignored) {}
        }
    }

    @Override
    public void close() {
        active = false;
        closeLineQuietly();
    }
}
