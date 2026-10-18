package komm.webrtc.pipeline;

import lombok.extern.slf4j.Slf4j;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Local soundboard player. Soundboards are NOT transmitted through the voice
 * track — every client (the person who triggered the sound and everyone else in
 * the channel) decodes and plays its own copy here, at its own volume. This is
 * what gives each listener an independent per-listener volume.
 *
 * Playback is gated by {@link #setMonitorMuted}, driven by the deafen state:
 * a deafened user neither hears nor plays incoming soundboards.
 *
 * Each active sound carries a {@code key}; replaying the same key restarts that
 * sound (no overlap), while different keys mix together and overlap freely.
 */
@Slf4j
public class SoundboardMixer implements AutoCloseable {

    private static final int FRAME = 960; // 20 ms @ 48 kHz mono

    private static final class Playback {
        final String key;
        final short[] pcm;
        int cursor;
        Playback(String key, short[] pcm) { this.key = key; this.pcm = pcm; }
    }

    private final CopyOnWriteArrayList<Playback> playbacks = new CopyOnWriteArrayList<>();

    private volatile float volume = 1.0f;       // 0.0–1.0, this listener's preference
    private volatile boolean monitorMuted = false;
    // Called with each mixed 20 ms frame (1920 bytes, little-endian mono 48 kHz s16).
    // Used by WebrtcRoomClient to push soundboard audio into the publisher PC track.
    private volatile java.util.function.Consumer<byte[]> publishCallback;

    private volatile String  outputDeviceName;
    private volatile boolean lineFailed = false;
    private volatile boolean running    = true;
    private SourceDataLine line;
    private final Thread playThread;

    public SoundboardMixer() {
        playThread = new Thread(this::playLoop, "komm-soundboard-player");
        playThread.setDaemon(true);
        playThread.start();
    }

    // ── Public control ─────────────────────────────────────────────────────────

    /**
     * Begin playing a decoded mono-48 kHz sound locally. Replaying the same
     * {@code key} restarts that sound; different keys overlap. No-op while deafened.
     */
    public void play(String key, short[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        playbacks.removeIf(p -> p.key.equals(key));
        playbacks.add(new Playback(key, pcm));
    }

    /** Stop every sound currently playing. */
    public void stopAll() {
        playbacks.clear();
        SourceDataLine l = line;
        if (l != null) l.flush();
    }

    /** Stop only the sounds whose key has the given prefix (e.g. all sounds a user triggered). */
    public void stopByKeyPrefix(String prefix) {
        playbacks.removeIf(p -> p.key.startsWith(prefix));
    }

    public void setVolume(float v) {
        this.volume = Math.max(0f, Math.min(1.0f, v));
    }

    public void setPublishCallback(java.util.function.Consumer<byte[]> cb) {
        this.publishCallback = cb;
    }

    /** Driven by the deafen state: deafened users don't hear/play soundboards. */
    public void setMonitorMuted(boolean muted) {
        this.monitorMuted = muted;
        if (muted) {
            // Flush the output buffer for immediate silence, but keep playbacks so
            // the cursor positions are preserved and sounds resume on undeafen.
            SourceDataLine l = line;
            if (l != null) l.flush();
        }
    }

    public void setOutputDevice(String deviceName) {
        this.outputDeviceName = deviceName;
        lineFailed = false;
        SourceDataLine l = line;
        line = null;
        if (l != null) { try { l.close(); } catch (Exception ignored) {} }
    }

    // ── Playback thread ──────────────────────────────────────────────────────────

    private void playLoop() {
        short[] frame    = new short[FRAME]; // scaled by sender's volume — for local SourceDataLine
        short[] rawFrame = new short[FRAME]; // always at full amplitude  — for WebRTC transmission
        byte[]  bytes    = new byte[FRAME * 2];
        byte[]  rawBytes = new byte[FRAME * 2];
        while (running) {
            try {
                if (playbacks.isEmpty() || monitorMuted) {
                    Thread.sleep(8);
                    continue;
                }
                SourceDataLine l = ensureLine();
                if (l == null) { Thread.sleep(20); continue; }

                java.util.Arrays.fill(frame,    (short) 0);
                java.util.Arrays.fill(rawFrame, (short) 0);
                float vol = volume;
                for (Playback p : playbacks) {
                    int remaining = p.pcm.length - p.cursor;
                    int n = Math.min(FRAME, remaining);
                    for (int i = 0; i < n; i++) {
                        short sample = p.pcm[p.cursor + i];
                        int mixed    = frame[i]    + (int)(sample * vol);
                        int rawMixed = rawFrame[i] + sample;
                        frame[i]    = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, mixed));
                        rawFrame[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, rawMixed));
                    }
                    p.cursor += n;
                    if (p.cursor >= p.pcm.length) playbacks.remove(p);
                }

                for (int i = 0; i < FRAME; i++) {
                    bytes[i * 2]       = (byte) (frame[i]    & 0xFF);
                    bytes[i * 2 + 1]   = (byte) ((frame[i]   >> 8) & 0xFF);
                    rawBytes[i * 2]    = (byte) (rawFrame[i]  & 0xFF);
                    rawBytes[i * 2 + 1]= (byte) ((rawFrame[i] >> 8) & 0xFF);
                }
                l.write(bytes, 0, bytes.length); // blocking write provides pacing — plays at sender's volume
                java.util.function.Consumer<byte[]> cb = publishCallback;
                if (cb != null) cb.accept(rawBytes); // transmit at full amplitude; each receiver scales independently
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("[Soundboard] Playback error: {}", e.getMessage());
                playbacks.clear();
            }
        }
    }

    private SourceDataLine ensureLine() {
        SourceDataLine l = line;
        if (l != null && l.isOpen()) return l;
        if (lineFailed) return null;

        Mixer.Info preferred = resolveOutput(outputDeviceName);
        AudioFormat fmt = SoundboardDecoder.TARGET;
        l = tryOpen(preferred, fmt, FRAME * 2 * 4);

        if (l == null) {
            log.warn("[Soundboard] Cannot open output line");
            lineFailed = true;
            return null;
        }
        l.start();
        line = l;
        return l;
    }

    private static SourceDataLine tryOpen(Mixer.Info preferred, AudioFormat fmt, int bufBytes) {
        DataLine.Info fmtInfo = new DataLine.Info(SourceDataLine.class, fmt);
        Line.Info     anyInfo = new Line.Info(SourceDataLine.class);

        if (preferred != null) {
            SourceDataLine l = openFrom(AudioSystem.getMixer(preferred), fmtInfo, anyInfo, fmt, bufBytes);
            if (l != null) return l;
        }
        for (Line.Info di : new Line.Info[]{fmtInfo, anyInfo}) {
            try { SourceDataLine l = (SourceDataLine) AudioSystem.getLine(di); l.open(fmt, bufBytes); return l; }
            catch (Exception ignored) {}
        }
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            Mixer mx = AudioSystem.getMixer(mi);
            if (mx.getSourceLineInfo().length == 0) continue;
            SourceDataLine l = openFrom(mx, fmtInfo, anyInfo, fmt, bufBytes);
            if (l != null) return l;
        }
        return null;
    }

    private static SourceDataLine openFrom(Mixer mx, DataLine.Info fmtInfo, Line.Info anyInfo,
                                           AudioFormat fmt, int bufBytes) {
        for (Line.Info di : new Line.Info[]{fmtInfo, anyInfo}) {
            try { SourceDataLine l = (SourceDataLine) mx.getLine(di); l.open(fmt, bufBytes); return l; }
            catch (Exception ignored) {}
        }
        return null;
    }

    private static Mixer.Info resolveOutput(String name) {
        if (name == null || "Default".equals(name)) return null;
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (info.getName().equals(name)
                    && AudioSystem.getMixer(info).getSourceLineInfo().length > 0) {
                return info;
            }
        }
        return null;
    }

    @Override
    public void close() {
        running = false;
        playThread.interrupt();
        stopAll();
        SourceDataLine l = line;
        if (l != null) { try { l.close(); } catch (Exception ignored) {} }
        line = null;
    }
}
