package komm.webrtc.pipeline;

import com.sun.jna.Platform;
import lombok.extern.slf4j.Slf4j;

import javax.sound.sampled.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;




/**
 * Captures microphone audio via javax.sound.sampled.
 *
 * Produces 960-sample (20 ms) 16-bit PCM mono frames at 48 kHz and delivers
 * them to a caller-supplied callback, which routes them into the DSP pipeline.
 *
 * Device hot-swap is supported: call {@link #changeDevice(String)} at any time;
 * the current capture line is closed and a new one opened on the given mixer.
 *
 * Ported from Java-vad reference project, adapted for Komm naming conventions.
 */
@Slf4j
public class MicCapture implements AutoCloseable {

    public static final AudioFormat FORMAT = new AudioFormat(
            48_000, 16, 1, true, false); // 48 kHz, 16-bit, mono, signed, little-endian

    private static final boolean IS_LINUX = Platform.isLinux();

    // ALSA plugin names that appear as capture mixers on Linux but are not useful
    // user-selectable inputs. "hw:" prefix names are also skipped — "plughw:" variants
    // include ALSA's plug layer for format conversion and are strictly more compatible.
    private static final Set<String> LINUX_BLOCKLIST = Set.of(
            "acp", "dmix", "dsnoop", "sysdefault", "default", "null",
            "lavrate", "speexrate", "upmix", "vdownmix"
    );

    private static final int FRAME_SAMPLES = 960;             // 20 ms @ 48 kHz
    private static final int FRAME_BYTES   = FRAME_SAMPLES * 2;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread captureThread;
    private TargetDataLine line;

    private Consumer<short[]> frameCallback;
    private String currentDeviceName;

    /** Real-time RMS level in [0, 1] — readable by UI threads for level meters. */
    public volatile float levelRms = 0.0f;

    public MicCapture() {}

    // ── Device enumeration ────────────────────────────────────────────────────

    /**
     * Returns the names of all available audio input devices on this system.
     * The first entry is always "Default".
     * On Linux, ALSA technical suffixes (e.g. " [plughw:1,0]") are stripped from
     * display names and low-level plugin aliases (acp, dmix, hw:* …) are hidden.
     */
    public static List<String> listInputDevices() {
        List<String> names = new ArrayList<>();
        names.add("Default");
        Set<String> seen = new HashSet<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            String actualName = info.getName();
            if (IS_LINUX && shouldSkipOnLinux(actualName)) continue;
            boolean hasCaptureLine = Arrays.stream(AudioSystem.getMixer(info).getTargetLineInfo())
                    .anyMatch(li -> li instanceof DataLine.Info);
            if (!hasCaptureLine) continue;
            String displayName = IS_LINUX ? cleanLinuxName(actualName) : actualName;
            if (!seen.add(displayName)) continue; // deduplicate by display name on Linux
            names.add(displayName);
        }
        return names;
    }

    /**
     * Resolves a device name to a {@link Mixer.Info}. Returns null for "Default" or not found.
     * On Linux, accepts both the cleaned display name ("Generic 1") and the full ALSA name
     * ("Generic 1 [plughw:1,0]") so saved settings round-trip correctly.
     */
    public static Mixer.Info resolveByName(String name) {
        if (name == null || "Default".equals(name)) return null;
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            Mixer mx = AudioSystem.getMixer(info);
            if (mx.getTargetLineInfo().length == 0) continue;
            String actualName = info.getName();
            if (actualName.equals(name)) return info;
            if (IS_LINUX && cleanLinuxName(actualName).equals(name)) return info;
        }
        return null;
    }

    // ── Linux helpers ─────────────────────────────────────────────────────────

    private static boolean shouldSkipOnLinux(String name) {
        // Check both the raw ALSA name and the cleaned display name (e.g. "acp [plughw:2,0]" → "acp")
        if (LINUX_BLOCKLIST.contains(name) || LINUX_BLOCKLIST.contains(cleanLinuxName(name))) return true;
        // Skip raw hw: mixers — plughw: variants are strictly more compatible
        if (name.contains(" [hw:") || name.startsWith("hw:")) return true;
        if (name.startsWith("sysdefault:") || name.startsWith("dmix:")
                || name.startsWith("dsnoop:")) return true;
        return false;
    }

    private static String cleanLinuxName(String name) {
        // Strip " [plughw:X,Y]" / " [hw:X,Y]" / " [Loopback, ..., plughw:X,Y]" suffixes
        int bracket = name.indexOf(" [");
        if (bracket > 0) return name.substring(0, bracket).trim();
        return name;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Begin capture from the device identified by {@code deviceName} ("Default" for system default).
     * Each 20 ms frame is delivered to {@code frameCallback} on the capture thread.
     */
    public synchronized void start(String deviceName, Consumer<short[]> frameCallback)
            throws LineUnavailableException {
        if (running.get()) stop();

        this.frameCallback     = frameCallback;
        this.currentDeviceName = deviceName;

        openLine(deviceName);

        running.set(true);
        captureThread = new Thread(this::captureLoop, "komm-mic-capture");
        captureThread.setDaemon(true);
        captureThread.setPriority(Thread.MAX_PRIORITY);
        captureThread.start();

        log.info("[MicCapture] Started — device={}", deviceName != null ? deviceName : "Default");
    }

    public synchronized void stop() {
        running.set(false);
        if (captureThread != null) {
            captureThread.interrupt();
            try { captureThread.join(1000); } catch (InterruptedException ignored) {}
            captureThread = null;
        }
        closeLine();
        log.info("[MicCapture] Stopped");
    }

    /**
     * Hot-swap the input device without interrupting the pipeline. Safe to call from any thread.
     */
    public synchronized void changeDevice(String deviceName) throws LineUnavailableException {
        if (deviceName != null && deviceName.equals(currentDeviceName)) return;
        log.info("[MicCapture] Switching device → {}", deviceName);
        boolean wasRunning = running.get();
        stop();
        if (wasRunning) start(deviceName, frameCallback);
        else currentDeviceName = deviceName;
    }

    public String getCurrentDeviceName() { return currentDeviceName; }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void openLine(String deviceName) throws LineUnavailableException {
        Mixer.Info mixerInfo = resolveByName(deviceName);
        DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, FORMAT);

        if (mixerInfo != null) {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            if (mixer.isLineSupported(lineInfo)) {
                line = (TargetDataLine) mixer.getLine(lineInfo);
            } else {
                log.warn("[MicCapture] Device '{}' doesn't support requested format, using default", deviceName);
                line = AudioSystem.getTargetDataLine(FORMAT);
            }
        } else {
            line = AudioSystem.getTargetDataLine(FORMAT);
        }

        line.open(FORMAT, FRAME_BYTES * 4); // 4-frame buffer
        line.start();
    }

    private void closeLine() {
        if (line != null) {
            line.stop();
            line.flush();
            line.close();
            line = null;
        }
    }

    private void captureLoop() {
        byte[]  buf     = new byte[FRAME_BYTES];
        short[] samples = new short[FRAME_SAMPLES];

        while (running.get()) {
            try {
                int read = line.read(buf, 0, FRAME_BYTES);
                if (read <= 0) continue;

                // Unpack little-endian 16-bit PCM into short[]
                for (int i = 0; i < FRAME_SAMPLES; i++) {
                    samples[i] = (short) ((buf[i * 2] & 0xFF) | ((buf[i * 2 + 1] & 0xFF) << 8));
                }

                // Update RMS meter for UI
                double sum = 0;
                for (short s : samples) { double n = s / 32768.0; sum += n * n; }
                levelRms = (float) Math.sqrt(sum / FRAME_SAMPLES);

                // Deliver copy to pipeline (clone avoids race if pipeline is slow)
                Consumer<short[]> cb = frameCallback;
                if (cb != null) cb.accept(samples.clone());

            } catch (Exception e) {
                if (running.get()) {
                    log.error("[MicCapture] Capture loop error: {}", e.getMessage(), e);
                    try { Thread.sleep(20); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    public boolean isRunning() { return running.get(); }

    @Override
    public void close() { stop(); }
}
