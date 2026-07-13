package komm.webrtc.audio;

import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Captures the default PulseAudio / PipeWire output-sink monitor source and
 * delivers it as 10&nbsp;ms / 480-sample, 48&nbsp;kHz mono 16-bit LE PCM frames
 * suitable for {@code CustomAudioSource.pushAudio(...)}.
 *
 * <h3>Backend compatibility</h3>
 * <p>Uses the PulseAudio Simple API ({@code libpulse-simple}) which PipeWire
 * exposes via its drop-in {@code libpulse.so} compatibility layer. The same
 * binary therefore runs transparently on both classic PulseAudio installations
 * and modern PipeWire-based systems (Fedora 34+, Ubuntu 22.04+, Arch, etc.)
 * without any runtime detection.</p>
 *
 * <h3>Device: {@code @DEFAULT_MONITOR@}</h3>
 * <p>This PulseAudio convention resolves at runtime to the monitor source of
 * whichever sink is currently the user's default output device. No device
 * enumeration is required, and it follows dynamic default-sink changes
 * automatically (e.g. plugging in headphones mid-session).</p>
 *
 * <h3>Excluding Komm's own audio (echo)</h3>
 * <p>The Windows implementation uses
 * {@code PROCESS_LOOPBACK_MODE_EXCLUDE_TARGET_PROCESS_TREE} to strip Komm's
 * own audio from the captured mix, preventing remote-participant voices from
 * being echoed back. PulseAudio has no equivalent single-call API. On
 * <b>PipeWire</b> we achieve the same result with {@link PipeWirePatchBay}: a
 * dedicated capture sink into which every <em>other</em> application's audio is
 * tapped, leaving Komm's streams unlinked — so this class records that sink's
 * monitor instead of {@code @DEFAULT_MONITOR@}. On classic PulseAudio-only
 * systems (where the PipeWire CLI tooling is absent) we fall back to capturing
 * {@code @DEFAULT_MONITOR@}, which includes Komm's own playback; users who
 * experience echo there can route Komm to a separate device in
 * {@code pavucontrol}.</p>
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>{@link #isSupported()} probes for {@code libpulse-simple} via JNA
 *       without actually opening a connection.</li>
 *   <li>{@link #start} opens a {@code PA_STREAM_RECORD} connection to
 *       {@code @DEFAULT_MONITOR@} requesting 48&nbsp;kHz mono
 *       {@code PA_SAMPLE_S16LE}. PulseAudio resamples and downmixes from the
 *       native sink format transparently.</li>
 *   <li>The capture thread calls {@code pa_simple_read} in a tight loop,
 *       requesting exactly 960&nbsp;bytes (480 mono S16LE samples = 10&nbsp;ms)
 *       per iteration. The call blocks until the server has that many bytes
 *       ready, delivering natural 10&nbsp;ms pacing without a polling timer.
 *       Because a monitor source delivers silence frames even when the sink is
 *       idle, the loop never stalls indefinitely.</li>
 *   <li>{@link #stop} sets the {@code running} flag and joins the thread. The
 *       thread exits after at most one 10&nbsp;ms read completes.</li>
 *   <li>{@code pa_simple_free} is called from the capture thread's
 *       {@code finally} block, keeping all PA calls on the same thread and
 *       avoiding PA's non-thread-safe object model.</li>
 * </ol>
 *
 * <p>Requires a running PulseAudio daemon or PipeWire with
 * {@code pipewire-pulse}. Any connection failure throws from {@link #start}
 * so the caller can disable the feature with a notice.</p>
 */
@Slf4j
public class LinuxAudioLoopbackCapture implements AudioLoopbackCapture {

    // ── Output frame geometry (matches CustomAudioSource.pushAudio contract) ───
    private static final int OUT_SAMPLE_RATE   = 48_000;
    private static final int OUT_FRAME_SAMPLES = 480;           // 10 ms @ 48 kHz, mono
    private static final int OUT_FRAME_BYTES   = OUT_FRAME_SAMPLES * 2; // 16-bit LE

    // ── PulseAudio constants ──────────────────────────────────────────────────
    private static final int    PA_STREAM_RECORD = 2;
    private static final int    PA_SAMPLE_S16LE  = 3;
    private static final String DEFAULT_MONITOR  = "@DEFAULT_MONITOR@";

    // ── Native library binding ────────────────────────────────────────────────

    /**
     * Minimal JNA binding for {@code libpulse-simple} (the PulseAudio simple API).
     *
     * <p>Only the three calls actually needed for loopback capture are declared;
     * the full PA API ({@code libpulse}) is intentionally avoided to keep the
     * surface area small and the threading model trivial.</p>
     */
    interface PulseSimple extends Library {

        /**
         * Opens a new connection to the PulseAudio server.
         *
         * @param server     {@code NULL} → connect to the default server
         * @param name       application name shown in pavucontrol / pw-top
         * @param dir        stream direction — {@code PA_STREAM_RECORD (2)} for capture
         * @param dev        source device name; {@code @DEFAULT_MONITOR@} selects
         *                   the monitor of the current default output sink
         * @param streamDesc human-readable stream description
         * @param ss         pointer to a {@code pa_sample_spec} laid out as:
         *                   {@code int format | uint32 rate | uint8 channels}
         *                   (9 bytes; we allocate 16 to avoid any ABI edge-cases)
         * @param map        {@code NULL} → default channel map
         * @param attr       {@code pa_buffer_attr} requesting low-latency capture;
         *                   {@code NULL} would select the (large) server default
         * @param error      on failure, receives the PA error code
         * @return opaque PA simple object, or {@code NULL} on failure
         */
        Pointer pa_simple_new(String server, String name, int dir, String dev,
                              String streamDesc, Pointer ss,
                              Pointer map, Pointer attr, IntByReference error);

        /**
         * Reads exactly {@code bytes} bytes of audio into {@code data}, blocking
         * until the data is available.
         *
         * @return 0 on success, negative on error
         */
        int pa_simple_read(Pointer s, Pointer data, long bytes, IntByReference error);

        /**
         * Frees a PA simple object and closes the connection.
         * Must be called from the same thread as {@link #pa_simple_read}.
         */
        void pa_simple_free(Pointer s);

        /** Returns a human-readable string for a PA error code. */
        String pa_strerror(int error);
    }

    // ── Support probe (cached) ────────────────────────────────────────────────

    private static volatile Boolean supportedCache;

    /**
     * Returns {@code true} if {@code libpulse-simple} can be loaded on this system.
     * The result is cached after the first call.
     */
    public static boolean isSupported() {
        if (!Platform.isLinux()) return false;
        if (supportedCache != null) return supportedCache;
        try {
            Native.load("pulse-simple", PulseSimple.class);
            supportedCache = Boolean.TRUE;
        } catch (UnsatisfiedLinkError e) {
            log.debug("[ScreenAudio] libpulse-simple not found: {}", e.getMessage());
            supportedCache = Boolean.FALSE;
        }
        return supportedCache;
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread captureThread;
    private Consumer<byte[]> frameCallback;
    /** Non-null while a PipeWire tap is excluding Komm's own output from the captured mix. */
    private volatile PipeWirePatchBay patchBay;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Opens a {@code PA_STREAM_RECORD} connection on a dedicated daemon thread
     * and blocks until recording has begun (or throws on failure).
     *
     * @param frameCallback receives one {@code byte[960]} (480 mono 16-bit LE
     *                      samples) approximately every 10 ms
     * @throws IllegalStateException if the PA connection cannot be opened
     */
    @Override
    public synchronized void start(Consumer<byte[]> frameCallback) {
        if (running.getAndSet(true)) return;
        this.frameCallback = frameCallback;

        CountDownLatch startupGate  = new CountDownLatch(1);
        Throwable[]    startupError = new Throwable[1];

        captureThread = new Thread(() -> {
            PulseSimple lib = null;
            Pointer     pa  = null;
            try {
                lib = Native.load("pulse-simple", PulseSimple.class);

                // pa_sample_spec { PA_SAMPLE_S16LE, 48000 Hz, 1 channel (mono) }
                // C layout: int format (4) | uint32 rate (4) | uint8 channels (1) = 9 bytes.
                // Allocate 16 bytes (zero-padded) to absorb any ABI trailing-padding variation.
                Memory ss = new Memory(16);
                ss.clear();
                ss.setInt(0, PA_SAMPLE_S16LE);
                ss.setInt(4, OUT_SAMPLE_RATE);
                ss.setByte(8, (byte) 1);   // mono; PA down-mixes the stereo sink for us

                // pa_buffer_attr { uint32 maxlength; tlength; prebuf; minreq; fragsize; } = 20 bytes.
                // Passing NULL lets the server choose a large default record buffer (hundreds of
                // ms → seconds of latency), which desyncs the audio from the video. We instead
                // request small fragments and a tightly bounded buffer so the captured audio
                // stays close to real time. -1 (0xFFFFFFFF) means "server default" for a field;
                // only maxlength + fragsize matter for a record stream.
                Memory attr = new Memory(20);
                attr.setInt(0, OUT_FRAME_BYTES * 4);   // maxlength — cap buffering at ~40 ms
                attr.setInt(4, -1);                    // tlength   — playback only → default
                attr.setInt(8, -1);                    // prebuf    — playback only → default
                attr.setInt(12, -1);                   // minreq    — playback only → default
                attr.setInt(16, OUT_FRAME_BYTES);      // fragsize  — deliver ~10 ms fragments

                // Decide which monitor source to capture. On PipeWire we stand up a tap that
                // excludes Komm's own output (no echo, like Windows EXCLUDE) and capture its
                // dedicated sink monitor; otherwise we fall back to the full default-sink mix.
                String device = DEFAULT_MONITOR;
                if (PipeWirePatchBay.isAvailable()) {
                    try {
                        PipeWirePatchBay pb = new PipeWirePatchBay();
                        device = pb.setUp();
                        patchBay = pb;
                    } catch (Throwable t) {
                        log.warn("[ScreenAudio] PipeWire tap setup failed ({}); falling back to "
                                + "full-mix monitor (Komm's own audio may echo)", t.getMessage());
                        device = DEFAULT_MONITOR;
                    }
                }

                IntByReference error = new IntByReference();
                pa = lib.pa_simple_new(
                        null,                  // server  — NULL = default
                        "Komm",                // app name visible in pavucontrol / pw-top
                        PA_STREAM_RECORD,
                        device,                // tap sink monitor (PipeWire) or @DEFAULT_MONITOR@
                        "Screen share audio",  // stream description
                        ss,
                        null,                  // channel map  — NULL = default
                        attr,                  // low-latency buffer attrs (see above)
                        error
                );

                if (pa == null) {
                    String msg = lib.pa_strerror(error.getValue());
                    throw new IllegalStateException("pa_simple_new failed: " + msg);
                }

                log.info("[ScreenAudio] PulseAudio capture opened (device={}, pid={})",
                        device, ProcessHandle.current().pid());
                startupGate.countDown();
                captureLoop(lib, pa);

            } catch (Throwable t) {
                startupError[0] = t;
                startupGate.countDown();
            } finally {
                // Always free from the capture thread — PA objects are not thread-safe.
                if (pa != null && lib != null) {
                    try { lib.pa_simple_free(pa); } catch (Throwable ignored) {}
                }
                // Drop the PipeWire tap (unloads the null sink + all its links). Runs on both the
                // stop path and the error path, so a failed start never leaks a virtual sink.
                PipeWirePatchBay pb = patchBay;
                patchBay = null;
                if (pb != null) {
                    try { pb.tearDown(); } catch (Throwable ignored) {}
                }
            }
        }, "screen-audio-capture");
        captureThread.setDaemon(true);
        captureThread.setPriority(Thread.MAX_PRIORITY - 1);
        captureThread.start();

        try {
            startupGate.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (startupError[0] != null) {
            running.set(false);
            throw new IllegalStateException(
                    "System audio capture failed to start: " + startupError[0].getMessage(),
                    startupError[0]);
        }
        log.info("[ScreenAudio] Capture started (PulseAudio monitor)");
    }

    @Override
    public synchronized void stop() {
        if (!running.getAndSet(false)) return;
        Thread t = captureThread;
        if (t != null) {
            // pa_simple_read blocks for ~10 ms per call; the thread will see
            // running == false after the current read and exit naturally.
            t.interrupt(); // best-effort nudge for any accidental Java-side sleep
            try { t.join(1500); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            captureThread = null;
        }
        log.info("[ScreenAudio] Capture stopped");
    }

    @Override
    public boolean isRunning() { return running.get(); }

    // ── Capture loop ──────────────────────────────────────────────────────────

    /**
     * Reads 960-byte chunks (480 mono S16LE samples = 10 ms) in a tight loop.
     *
     * <p>{@code pa_simple_read} blocks until the server has delivered the
     * requested bytes, providing natural 10&nbsp;ms pacing. A monitor source
     * delivers silence frames even when the sink is idle, so this loop never
     * stalls when no audio is playing.</p>
     */
    private void captureLoop(PulseSimple lib, Pointer pa) {
        Memory         buf   = new Memory(OUT_FRAME_BYTES);
        IntByReference error = new IntByReference();

        while (running.get()) {
            int ret = lib.pa_simple_read(pa, buf, OUT_FRAME_BYTES, error);
            if (ret < 0) {
                if (!running.get()) break; // stopped while blocked in read — not an error
                log.warn("[ScreenAudio] pa_simple_read error: {}",
                        lib.pa_strerror(error.getValue()));
                break;
            }
            if (!running.get()) break;

            byte[]           frame = buf.getByteArray(0, OUT_FRAME_BYTES);
            Consumer<byte[]> cb    = frameCallback;
            if (cb != null) {
                try { cb.accept(frame); } catch (Throwable t) {
                    log.debug("[ScreenAudio] frame callback error: {}", t.getMessage());
                }
            }
        }
    }
}
