package komm.webrtc.audio;

import com.sun.jna.*;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Captures the Windows system render mix <em>excluding our own process tree</em> via
 * WASAPI process-loopback in EXCLUDE mode, and delivers it as 10&nbsp;ms / 480-sample,
 * 48&nbsp;kHz mono 16-bit PCM frames suitable for {@code CustomAudioSource.pushAudio(...)}.
 *
 * <h3>Why process-loopback EXCLUDE</h3>
 * <p>A plain render-endpoint loopback would also capture the audio Komm itself is playing
 * (remote participants' voices + soundboard). Re-publishing that would echo everyone back
 * to the room. The {@code PROCESS_LOOPBACK_MODE_EXCLUDE_TARGET_PROCESS_TREE} activation
 * captures the full system mix <em>minus</em> our PID and children, which is exactly the
 * "everything on my screen except Komm" signal we want.</p>
 *
 * <h3>How it works (Win32)</h3>
 * <ol>
 *   <li>{@code ActivateAudioInterfaceAsync("VAD\\Process_Loopback", IID_IAudioClient, params, handler)}
 *       where {@code params} is a {@code PROPVARIANT(VT_BLOB)} wrapping
 *       {@code AUDIOCLIENT_ACTIVATION_PARAMS{ PROCESS_LOOPBACK, ourPid, EXCLUDE }}.</li>
 *   <li>Activation is asynchronous; we supply a hand-rolled COM
 *       {@code IActivateAudioInterfaceCompletionHandler} whose {@code ActivateCompleted}
 *       just signals a latch. We then call {@code GetActivateResult} for the {@code IAudioClient}.</li>
 *   <li>{@code IAudioClient::Initialize(SHARED, LOOPBACK|EVENTCALLBACK, …)} — process loopback
 *       <b>requires</b> event-driven mode — then {@code SetEventHandle}, {@code GetService(IAudioCaptureClient)},
 *       {@code Start}, and an event-driven {@code GetBuffer}/{@code ReleaseBuffer} loop.</li>
 * </ol>
 *
 * <p>All COM calls are made through raw vtable dispatch (there are no JNA bindings for
 * these interfaces). The instance is single-use: {@link #start} once, then {@link #stop}.</p>
 *
 * <p>Requires Windows 10 build 19041+ (process loopback API). {@link #isSupported()} gates the
 * UI; any runtime activation/init failure throws from {@link #start} so the caller can disable
 * the feature with a notice instead of producing broken or echoing audio.</p>
 */
@Slf4j
public class SystemAudioLoopbackCapture implements AudioLoopbackCapture {

    // ── Output frame geometry (matches CustomAudioSource.pushAudio contract used elsewhere) ──
    private static final int OUT_SAMPLE_RATE = 48_000;
    private static final int OUT_FRAME_SAMPLES = 480;          // 10 ms @ 48 kHz, mono
    private static final int OUT_FRAME_BYTES = OUT_FRAME_SAMPLES * 2;

    // ── Win32 / WASAPI constants ─────────────────────────────────────────────
    private static final String VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK = "VAD\\Process_Loopback";

    private static final int S_OK = 0;
    private static final int E_NOINTERFACE = 0x80004002;
    private static final int AUDCLNT_E_UNSUPPORTED_FORMAT = 0x88890008;

    private static final int AUDCLNT_SHAREMODE_SHARED = 0;
    private static final int AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;
    private static final int AUDCLNT_STREAMFLAGS_EVENTCALLBACK = 0x00040000;
    private static final int AUDCLNT_BUFFERFLAGS_SILENT = 0x2;

    private static final int VT_BLOB = 65;

    // AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK = 1
    private static final int ACTIVATION_TYPE_PROCESS_LOOPBACK = 1;
    // PROCESS_LOOPBACK_MODE_EXCLUDE_TARGET_PROCESS_TREE = 1
    private static final int PROCESS_LOOPBACK_MODE_EXCLUDE = 1;

    private static final int COINIT_MULTITHREADED = 0x0;
    private static final int WAIT_OBJECT_0 = 0x0;

    // WAVEFORMATEX format tags
    private static final int WAVE_FORMAT_PCM = 1;
    private static final int WAVE_FORMAT_IEEE_FLOAT = 3;

    // ── vtable indices ───────────────────────────────────────────────────────
    // IAudioClient : IUnknown(0,1,2)
    private static final int IAUDIOCLIENT_INITIALIZE = 3;
    private static final int IAUDIOCLIENT_START = 10;
    private static final int IAUDIOCLIENT_STOP = 11;
    private static final int IAUDIOCLIENT_SETEVENTHANDLE = 13;
    private static final int IAUDIOCLIENT_GETSERVICE = 14;
    // IAudioCaptureClient : IUnknown(0,1,2)
    private static final int ICAPTURECLIENT_GETBUFFER = 3;
    private static final int ICAPTURECLIENT_RELEASEBUFFER = 4;
    private static final int ICAPTURECLIENT_GETNEXTPACKETSIZE = 5;
    // IActivateAudioInterfaceAsyncOperation : IUnknown(0,1,2)
    private static final int IASYNCOP_GETACTIVATERESULT = 3;
    // IUnknown
    private static final int IUNKNOWN_RELEASE = 2;

    // ── GUIDs ────────────────────────────────────────────────────────────────
    private static final Guid.GUID IID_IAUDIOCLIENT =
            Guid.GUID.fromString("{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}");
    private static final Guid.GUID IID_IAUDIOCAPTURECLIENT =
            Guid.GUID.fromString("{C8ADBD64-E71E-48A0-A4DE-185C395CD317}");
    private static final Guid.GUID IID_IUNKNOWN =
            Guid.GUID.fromString("{00000000-0000-0000-C000-000000000046}");
    private static final Guid.GUID IID_COMPLETION =
            Guid.GUID.fromString("{41D949AB-9862-444A-80F6-C261334DA5EB}");
    // IAgileObject — a marker interface (no methods). ActivateAudioInterfaceAsync requires the
    // completion handler to be agile; we satisfy that by handing back our own IUnknown vtable.
    private static final Guid.GUID IID_IAGILEOBJECT =
            Guid.GUID.fromString("{94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90}");

    private static final int PS = Native.POINTER_SIZE;

    // ── Native library bindings ──────────────────────────────────────────────
    public interface Mmdevapi extends StdCallLibrary {
        Mmdevapi INSTANCE = Native.load("Mmdevapi", Mmdevapi.class, W32APIOptions.DEFAULT_OPTIONS);

        int ActivateAudioInterfaceAsync(WString activationPath, Guid.GUID.ByReference riid,
                                        Pointer activationParams, Pointer completionHandler,
                                        PointerByReference activationOperation);
    }

    // COM callback signatures for our completion handler vtable.
    public interface QueryInterfaceCB extends StdCallLibrary.StdCallCallback {
        int invoke(Pointer thisPtr, Pointer riid, Pointer ppvObject);
    }
    public interface AddRefReleaseCB extends StdCallLibrary.StdCallCallback {
        int invoke(Pointer thisPtr);
    }
    public interface ActivateCompletedCB extends StdCallLibrary.StdCallCallback {
        int invoke(Pointer thisPtr, Pointer activateOperation);
    }

    // ── State ────────────────────────────────────────────────────────────────
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;
    private Consumer<byte[]> frameCallback;

    // Kept alive for the duration so the GC never frees native callback/vtable memory.
    private QueryInterfaceCB qiCb;
    private AddRefReleaseCB addRefCb;
    private AddRefReleaseCB releaseCb;
    private ActivateCompletedCB activateCompletedCb;
    private Memory handlerVtable;
    private Memory handlerObject;
    private final AtomicInteger handlerRefCount = new AtomicInteger(1);

    private final CountDownLatch activationLatch = new CountDownLatch(1);
    private volatile Pointer asyncOperation;   // IActivateAudioInterfaceAsyncOperation*

    // Capture-thread-owned native handles
    private Pointer audioClient;
    private Pointer captureClient;
    private WinNT.HANDLE eventHandle;

    // Output mono accumulator
    private final short[] monoAccum = new short[OUT_FRAME_SAMPLES];
    private int monoCount = 0;

    // ── Capability gate ──────────────────────────────────────────────────────

    /** True only on Windows 10 build 19041+ where the process-loopback API exists. */
    public static boolean isSupported() {
        if (!Platform.isWindows()) return false;
        try {
            String build = Advapi32Util.registryGetStringValue(
                    WinReg.HKEY_LOCAL_MACHINE,
                    "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion",
                    "CurrentBuildNumber");
            return Integer.parseInt(build.trim()) >= 19041;
        } catch (Throwable t) {
            log.debug("[ScreenAudio] Could not read Windows build number: {}", t.getMessage());
            return false;
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Starts capture on a dedicated MTA daemon thread. Blocks until the WASAPI client
     * is activated and started (or throws if activation/initialization fails).
     *
     * @param frameCallback receives one {@code byte[960]} (480 mono 16-bit LE samples) per 10 ms
     */
    @Override
    public synchronized void start(Consumer<byte[]> frameCallback) {
        if (running.getAndSet(true)) return;
        this.frameCallback = frameCallback;

        CountDownLatch startupGate = new CountDownLatch(1);
        Throwable[] startupError = new Throwable[1];

        thread = new Thread(() -> {
            boolean comInit = false;
            try {
                int hr = Ole32.INSTANCE.CoInitializeEx(null, COINIT_MULTITHREADED).intValue();
                // S_OK or S_FALSE (already initialized) are both fine.
                comInit = (hr >= 0);

                activate();          // → audioClient
                initializeClient();  // Initialize + SetEventHandle + GetService + Start

                startupGate.countDown();
                captureLoop();
            } catch (Throwable t) {
                startupError[0] = t;
                startupGate.countDown();
            } finally {
                cleanupNative();
                if (comInit) {
                    try { Ole32.INSTANCE.CoUninitialize(); } catch (Throwable ignored) {}
                }
            }
        }, "screen-audio-capture");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY - 1);
        thread.start();

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
        log.info("[ScreenAudio] Capture started (process-loopback EXCLUDE, pid={})",
                ProcessHandle.current().pid());
    }

    @Override
    public synchronized void stop() {
        if (!running.getAndSet(false)) return;
        if (thread != null) {
            // Nudge the event so the loop wakes promptly instead of waiting out its timeout.
            if (eventHandle != null) {
                try { Kernel32.INSTANCE.SetEvent(eventHandle); } catch (Throwable ignored) {}
            }
            try { thread.join(1500); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        log.info("[ScreenAudio] Capture stopped");
    }

    @Override
    public boolean isRunning() { return running.get(); }

    // ── Activation (async COM) ───────────────────────────────────────────────

    private void activate() throws Exception {
        buildCompletionHandler();

        // AUDIOCLIENT_ACTIVATION_PARAMS { int ActivationType; { DWORD pid; int mode; } }  (12 bytes)
        Memory params = new Memory(12);
        params.setInt(0, ACTIVATION_TYPE_PROCESS_LOOPBACK);
        params.setInt(4, (int) ProcessHandle.current().pid());
        params.setInt(8, PROCESS_LOOPBACK_MODE_EXCLUDE);

        // PROPVARIANT { VARTYPE vt; … ; BLOB { ULONG cbSize; void* pBlobData; } }  (24 bytes, x64)
        Memory propvariant = new Memory(24);
        propvariant.clear();
        propvariant.setShort(0, (short) VT_BLOB);
        propvariant.setInt(8, (int) params.size());   // BLOB.cbSize
        propvariant.setPointer(16, params);            // BLOB.pBlobData

        Guid.GUID.ByReference riid = new Guid.GUID.ByReference(IID_IAUDIOCLIENT);

        PointerByReference op = new PointerByReference();
        int hr = Mmdevapi.INSTANCE.ActivateAudioInterfaceAsync(
                new WString(VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK),
                riid, propvariant, handlerObject, op);
        if (hr != S_OK) {
            throw new IllegalStateException("ActivateAudioInterfaceAsync failed: " + hrHex(hr));
        }

        if (!activationLatch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Activation completion handler timed out");
        }

        Pointer asyncOp = (asyncOperation != null) ? asyncOperation : op.getValue();
        if (asyncOp == null) {
            throw new IllegalStateException("No async operation returned from activation");
        }

        // IActivateAudioInterfaceAsyncOperation::GetActivateResult(HRESULT*, IUnknown**)
        IntByReference activateResult = new IntByReference();
        PointerByReference activatedInterface = new PointerByReference();
        int ghr = comInvoke(asyncOp, IASYNCOP_GETACTIVATERESULT, activateResult, activatedInterface);
        // Done with the async operation regardless of outcome.
        try { comInvoke(asyncOp, IUNKNOWN_RELEASE); } catch (Throwable ignored) {}
        asyncOperation = null;

        if (ghr != S_OK) {
            throw new IllegalStateException("GetActivateResult failed: " + hrHex(ghr));
        }
        if (activateResult.getValue() != S_OK) {
            throw new IllegalStateException("Activation result error: " + hrHex(activateResult.getValue()));
        }
        audioClient = activatedInterface.getValue();
        if (audioClient == null) {
            throw new IllegalStateException("Activation returned a null IAudioClient");
        }
    }

    private void buildCompletionHandler() {
        qiCb = (thisPtr, riid, ppvObject) -> {
            Guid.GUID req = new Guid.GUID(riid);
            boolean match = req.equals(IID_IUNKNOWN)
                    || req.equals(IID_COMPLETION)
                    || req.equals(IID_IAGILEOBJECT);
            if (match) {
                ppvObject.setPointer(0, thisPtr);
                handlerRefCount.incrementAndGet();
                return S_OK;
            }
            ppvObject.setLong(0, 0L);
            return E_NOINTERFACE;
        };
        addRefCb = thisPtr -> handlerRefCount.incrementAndGet();
        releaseCb = thisPtr -> handlerRefCount.decrementAndGet();
        activateCompletedCb = (thisPtr, activateOperation) -> {
            this.asyncOperation = activateOperation;
            activationLatch.countDown();
            return S_OK;
        };

        handlerVtable = new Memory(4L * PS);
        handlerVtable.setPointer(0L * PS, CallbackReference.getFunctionPointer(qiCb));
        handlerVtable.setPointer(1L * PS, CallbackReference.getFunctionPointer(addRefCb));
        handlerVtable.setPointer(2L * PS, CallbackReference.getFunctionPointer(releaseCb));
        handlerVtable.setPointer(3L * PS, CallbackReference.getFunctionPointer(activateCompletedCb));

        handlerObject = new Memory(PS);
        handlerObject.setPointer(0, handlerVtable);
    }

    // ── Client initialization ────────────────────────────────────────────────

    private boolean captureFloat = false;   // true if we negotiated 32-bit float instead of PCM16
    private int captureChannels = 2;

    private void initializeClient() throws Exception {
        // Try 48 kHz stereo 16-bit PCM first; fall back to 32-bit float if rejected.
        int hr = tryInitialize(WAVE_FORMAT_PCM, 16);
        if (hr == AUDCLNT_E_UNSUPPORTED_FORMAT) {
            log.info("[ScreenAudio] PCM16 rejected, retrying with 32-bit float");
            captureFloat = true;
            hr = tryInitialize(WAVE_FORMAT_IEEE_FLOAT, 32);
        }
        if (hr != S_OK) {
            throw new IllegalStateException("IAudioClient::Initialize failed: " + hrHex(hr));
        }

        eventHandle = Kernel32.INSTANCE.CreateEvent(null, false, false, null);
        if (eventHandle == null) {
            throw new IllegalStateException("CreateEvent failed");
        }
        int shr = comInvoke(audioClient, IAUDIOCLIENT_SETEVENTHANDLE, eventHandle);
        if (shr != S_OK) throw new IllegalStateException("SetEventHandle failed: " + hrHex(shr));

        Guid.GUID.ByReference captureRiid = new Guid.GUID.ByReference(IID_IAUDIOCAPTURECLIENT);
        captureRiid.write();
        PointerByReference svc = new PointerByReference();
        int chr = comInvoke(audioClient, IAUDIOCLIENT_GETSERVICE, captureRiid.getPointer(), svc);
        if (chr != S_OK) throw new IllegalStateException("GetService(IAudioCaptureClient) failed: " + hrHex(chr));
        captureClient = svc.getValue();

        int strt = comInvoke(audioClient, IAUDIOCLIENT_START);
        if (strt != S_OK) throw new IllegalStateException("IAudioClient::Start failed: " + hrHex(strt));
    }

    private int tryInitialize(int formatTag, int bits) {
        captureChannels = 2;
        int blockAlign = captureChannels * bits / 8;

        // WAVEFORMATEX (18 bytes)
        Memory fmt = new Memory(18);
        fmt.clear();
        fmt.setShort(0, (short) formatTag);                                  // wFormatTag
        fmt.setShort(2, (short) captureChannels);                           // nChannels
        fmt.setInt(4, OUT_SAMPLE_RATE);                                     // nSamplesPerSec
        fmt.setInt(8, OUT_SAMPLE_RATE * blockAlign);                        // nAvgBytesPerSec
        fmt.setShort(12, (short) blockAlign);                              // nBlockAlign
        fmt.setShort(14, (short) bits);                                    // wBitsPerSample
        fmt.setShort(16, (short) 0);                                       // cbSize

        int flags = AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK;
        // Shared, event-driven: pass 0 for both buffer duration and periodicity.
        return comInvoke(audioClient, IAUDIOCLIENT_INITIALIZE,
                AUDCLNT_SHAREMODE_SHARED, flags, 0L, 0L, fmt, Pointer.NULL);
    }

    // ── Capture loop ─────────────────────────────────────────────────────────

    private void captureLoop() {
        final IntByReference packet = new IntByReference();
        final int blockAlign = captureChannels * (captureFloat ? 4 : 2);

        while (running.get()) {
            int wr = Kernel32.INSTANCE.WaitForSingleObject(eventHandle, 2000);
            if (wr != WAIT_OBJECT_0) continue;        // timeout — re-check running flag
            if (!running.get()) break;

            if (comInvoke(captureClient, ICAPTURECLIENT_GETNEXTPACKETSIZE, packet) != S_OK) continue;

            while (packet.getValue() > 0 && running.get()) {
                PointerByReference ppData = new PointerByReference();
                IntByReference numFrames = new IntByReference();
                IntByReference dwFlags = new IntByReference();

                int hr = comInvoke(captureClient, ICAPTURECLIENT_GETBUFFER,
                        ppData, numFrames, dwFlags, Pointer.NULL, Pointer.NULL);
                if (hr != S_OK) break;

                int frames = numFrames.getValue();
                boolean silent = (dwFlags.getValue() & AUDCLNT_BUFFERFLAGS_SILENT) != 0;
                Pointer data = ppData.getValue();

                if (frames > 0) {
                    if (silent || data == null) emitSilence(frames);
                    else processBuffer(data, frames, blockAlign);
                }

                comInvoke(captureClient, ICAPTURECLIENT_RELEASEBUFFER, frames);
                if (comInvoke(captureClient, ICAPTURECLIENT_GETNEXTPACKETSIZE, packet) != S_OK) break;
            }
        }
    }

    /** Downmix interleaved stereo (PCM16 or float32) to mono and emit in 480-sample frames. */
    private void processBuffer(Pointer data, int frames, int blockAlign) {
        byte[] raw = data.getByteArray(0, frames * blockAlign);
        for (int i = 0; i < frames; i++) {
            int base = i * blockAlign;
            int mono;
            if (captureFloat) {
                float l = Float.intBitsToFloat(le32(raw, base));
                float r = captureChannels > 1 ? Float.intBitsToFloat(le32(raw, base + 4)) : l;
                mono = clamp16(Math.round((l + r) * 0.5f * 32767f));
            } else {
                int l = (short) ((raw[base] & 0xFF) | (raw[base + 1] << 8));
                int r = captureChannels > 1
                        ? (short) ((raw[base + 2] & 0xFF) | (raw[base + 3] << 8)) : l;
                mono = clamp16((l + r) >> 1);
            }
            pushMono((short) mono);
        }
    }

    private void emitSilence(int frames) {
        for (int i = 0; i < frames; i++) pushMono((short) 0);
    }

    private void pushMono(short sample) {
        monoAccum[monoCount++] = sample;
        if (monoCount == OUT_FRAME_SAMPLES) {
            byte[] out = new byte[OUT_FRAME_BYTES];
            for (int i = 0; i < OUT_FRAME_SAMPLES; i++) {
                out[i * 2] = (byte) (monoAccum[i] & 0xFF);
                out[i * 2 + 1] = (byte) ((monoAccum[i] >> 8) & 0xFF);
            }
            monoCount = 0;
            Consumer<byte[]> cb = frameCallback;
            if (cb != null) {
                try { cb.accept(out); } catch (Throwable t) {
                    log.debug("[ScreenAudio] frame callback error: {}", t.getMessage());
                }
            }
        }
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────

    private void cleanupNative() {
        if (audioClient != null) {
            try { comInvoke(audioClient, IAUDIOCLIENT_STOP); } catch (Throwable ignored) {}
        }
        if (captureClient != null) {
            try { comInvoke(captureClient, IUNKNOWN_RELEASE); } catch (Throwable ignored) {}
            captureClient = null;
        }
        if (audioClient != null) {
            try { comInvoke(audioClient, IUNKNOWN_RELEASE); } catch (Throwable ignored) {}
            audioClient = null;
        }
        if (eventHandle != null) {
            try { Kernel32.INSTANCE.CloseHandle(eventHandle); } catch (Throwable ignored) {}
            eventHandle = null;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Raw COM vtable dispatch: invoke method {@code index} on {@code iface} with {@code this} prepended. */
    private static int comInvoke(Pointer iface, int index, Object... args) {
        Pointer vtbl = iface.getPointer(0);
        Pointer fn = vtbl.getPointer((long) index * PS);
        Object[] full = new Object[args.length + 1];
        full[0] = iface;
        System.arraycopy(args, 0, full, 1, args.length);
        // ALT_CONVENTION = stdcall on x86; a no-op on x64 (single convention).
        return Function.getFunction(fn, Function.ALT_CONVENTION).invokeInt(full);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private static int clamp16(int v) {
        if (v > 32767) return 32767;
        if (v < -32768) return -32768;
        return v;
    }

    private static String hrHex(int hr) {
        return "0x" + Integer.toHexString(hr);
    }
}
