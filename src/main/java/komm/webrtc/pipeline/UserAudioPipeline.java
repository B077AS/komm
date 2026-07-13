package komm.webrtc.pipeline;

import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import lombok.extern.slf4j.Slf4j;

import javax.sound.sampled.LineUnavailableException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Custom audio capture and processing pipeline for Komm voice channels.
 *
 * Architecture:
 *   MicCapture (javax.sound) → captureQueue
 *   pipelineThread: captureQueue → AEC3 → RNNoise/WebRTC-NS → AGC2 → Silero-VAD → CustomAudioSource
 *
 * The hardware AudioDeviceModule is used for speaker playout only (subscriber PC's
 * remote audio tracks render directly through it). Capture is handled entirely by
 * this pipeline, giving full control over the DSP chain.
 *
 * All DSP stages are independently toggleable at runtime (hot-swap) via their
 * respective setEnabled() methods — no reconnection or restart needed.
 *
 * Lifecycle:
 *   new UserAudioPipeline(source, settings) — creates DSP modules
 *   startCapture(deviceName)               — begins mic capture (call on channel join)
 *   stopCapture()                          — stops mic capture (call on channel leave)
 *   close()                                — disposes DSP modules permanently
 */
@Slf4j
public class UserAudioPipeline implements AutoCloseable {

    private static final int FRAME_SAMPLES  = 960;    // 20 ms @ 48 kHz
    private static final int QUEUE_CAPACITY = 16;     // max buffered capture frames
    // CustomAudioSource.pushAudio() expects 10 ms frames (480 samples @ 48 kHz)
    private static final int PUSH_SAMPLES   = 480;
    private static final int PUSH_BYTES     = PUSH_SAMPLES * 2;
    // Fade ramp length applied at gate open/close — 10 ms eliminates the pop
    // that comes from jumping between zeros and live audio with no transition.
    private static final int FADE_SAMPLES   = 480;
    // Pre-roll: number of 20 ms processed frames to buffer before the gate.
    // When the gate opens these are flushed retroactively so word onsets that
    // arrived during the onset-detection window (~64 ms) are never lost.
    // 4 frames = 80 ms — slightly longer than the detection window for safety.
    private static final int PRE_ROLL_FRAMES = 4;

    // ── DSP modules ───────────────────────────────────────────────────────────
    private final AecProcessor    aec;
    private final NoiseSuppressor ns;
    private final AgcProcessor    agc;
    private final VadProcessor    vad;

    // ── Transport ─────────────────────────────────────────────────────────────
    private final CustomAudioSource customAudioSource;
    // Fed the same frames as customAudioSource, but on a track that is never subject to
    // mic-mute/deafen — this is what the Audio settings mic-test loopback listens to, so
    // testing your mic still works even while muted/deafened in a real channel (Discord
    // does the same: it forces you into mute+deafen for the test but you still hear yourself).
    private final CustomAudioSource micTestAudioSource;

    // ── Soundboard (local playback only; fully decoupled from the mic/voice path) ──
    private final SoundboardMixer soundboardMixer = new SoundboardMixer();

    // ── Capture ───────────────────────────────────────────────────────────────
    private final MicCapture mic = new MicCapture();
    private final BlockingQueue<short[]> captureQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    // Sliding window of the last PRE_ROLL_FRAMES post-AGC frames, used to
    // retroactively send word onsets when the VAD gate opens.
    private final Deque<short[]> preRollBuffer = new ArrayDeque<>(PRE_ROLL_FRAMES + 1);
    private final AtomicBoolean pipelineRunning = new AtomicBoolean(false);
    private Thread pipelineThread;

    // ── Metrics (readable by UI threads) ─────────────────────────────────────
    public volatile float   preProcessRms   = 0.0f;
    public volatile float   postProcessRms  = 0.0f;
    public volatile float   vadProbability  = 0.0f;
    public volatile boolean vadActive       = false;

    public UserAudioPipeline(CustomAudioSource customAudioSource,
                             CustomAudioSource micTestAudioSource,
                             boolean aecEnabled,
                             boolean nsEnabled,
                             boolean agcEnabled,
                             boolean vadEnabled,
                             double  vadThreshold,
                             double  vadNoiseGateDb) {
        this.customAudioSource   = customAudioSource;
        this.micTestAudioSource  = micTestAudioSource;

        aec = new AecProcessor();
        aec.setEnabled(aecEnabled);

        ns  = new NoiseSuppressor(nsEnabled);

        agc = new AgcProcessor(agcEnabled);

        vad = new VadProcessor(vadEnabled, vadThreshold);
        vad.setOnsetMinRms((float) dbToRms(vadNoiseGateDb));

        soundboardMixer.setVolume(komm.utils.UserSettings.getInstance().getSoundboardVolume());

        log.info("[AudioPipeline] DSP modules created — AEC={} NS={} AGC={} VAD={}",
                aecEnabled, nsEnabled, agcEnabled, vadEnabled);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Start microphone capture and the DSP processing thread.
     * Call this when the user joins a voice channel.
     */
    public void startCapture(String inputDeviceName) {
        if (pipelineRunning.getAndSet(true)) return;

        captureQueue.clear();

        // Start processing thread first so it's ready when frames arrive
        pipelineThread = new Thread(this::pipelineLoop, "komm-audio-pipeline");
        pipelineThread.setDaemon(true);
        pipelineThread.setPriority(Thread.MAX_PRIORITY - 1);
        pipelineThread.start();

        try {
            mic.start(inputDeviceName, frame -> {
                if (!captureQueue.offer(frame)) {
                    log.debug("[AudioPipeline] Capture queue full — dropping frame");
                }
            });
        } catch (LineUnavailableException e) {
            log.error("[AudioPipeline] Cannot open mic device '{}': {}", inputDeviceName, e.getMessage(), e);
            pipelineRunning.set(false);
        }

        log.info("[AudioPipeline] Capture started — device={}", inputDeviceName);
    }

    /**
     * Stop microphone capture and the DSP thread.
     * Call this when the user leaves a voice channel.
     * DSP modules remain initialized so the pipeline can be restarted cheaply.
     */
    public void stopCapture() {
        if (!pipelineRunning.getAndSet(false)) return;

        mic.stop();

        if (pipelineThread != null) {
            pipelineThread.interrupt();
            try { pipelineThread.join(1000); } catch (InterruptedException ignored) {}
            pipelineThread = null;
        }

        captureQueue.clear();
        preRollBuffer.clear();
        vad.resetState();
        preProcessRms  = 0.0f;
        postProcessRms = 0.0f;
        vadProbability = 0.0f;
        vadActive      = false;

        log.info("[AudioPipeline] Capture stopped");
    }

    public boolean isCapturing() { return pipelineRunning.get(); }

    // ── Processing loop ───────────────────────────────────────────────────────

    private void pipelineLoop() {
        short[] aecOut = new short[FRAME_SAMPLES];
        short[] nsOut  = new short[FRAME_SAMPLES];
        short[] agcOut = new short[FRAME_SAMPLES];

        // Pre-allocate push buffers (two 10 ms halves of the 20 ms frame)
        byte[] pushBuf0 = new byte[PUSH_BYTES];
        byte[] pushBuf1 = new byte[PUSH_BYTES];

        // Tracks the previous VAD decision so we can detect gate transitions
        // and apply a short amplitude ramp to avoid onset/offset pops.
        boolean prevSpeech = false;

        while (pipelineRunning.get()) {
            try {
                short[] captured = captureQueue.poll(50, TimeUnit.MILLISECONDS);
                if (captured == null) continue;

                preProcessRms = AudioConverter.rms(captured);

                // 1. AEC — echo cancellation (capture-side; render reference not available here)
                aec.processCapture(captured, aecOut);

                // 2. NS — RNNoise (primary) or WebRTC NS (fallback), async inference thread
                ns.processFrame(aecOut, nsOut);

                // 3. AGC — adaptive digital gain control
                agc.processFrame(nsOut, agcOut);

                postProcessRms = AudioConverter.rms(agcOut);

                // Snapshot agcOut before the gate may modify it in-place,
                // so the pre-roll buffer always holds clean unmodified frames.
                short[] agcSnapshot = agcOut.clone();

                // 4. VAD — decide whether to transmit or send silence
                float[] agcFloat = AudioConverter.shortToFloat(agcOut);
                boolean speech = vad.processFrame(agcFloat);
                vadProbability = vad.getSpeechProbability();
                vadActive      = speech;

                // 5. Gate with pre-roll flush + fade-in/out.
                //
                //    silence→speech : flush the pre-roll ring (frames captured DURING
                //                     the onset-detection window) so word onsets arrive
                //                     intact, then send the current frame at full level.
                //    speech→silence : send agcOut for this last frame but ramp the
                //                     tail down to zero to avoid a hard cut.
                //    steady state   : pass through unchanged.
                short[] toSend;
                if (speech) {
                    toSend = agcOut;
                    if (!prevSpeech) {
                        // Gate just opened: retroactively deliver buffered onset frames.
                        if (preRollBuffer.isEmpty()) {
                            // Nothing buffered yet (very start of capture) — plain fade-in.
                            for (int i = 0; i < FRAME_SAMPLES; i++)
                                toSend[i] = (short)(toSend[i] * (float) i / FRAME_SAMPLES);
                        } else {
                            boolean firstPre = true;
                            for (short[] buffered : preRollBuffer) {
                                short[] toFlush = buffered;
                                if (firstPre) {
                                    // Fade-in only the very first pre-roll frame (silence→audio).
                                    toFlush = toFlush.clone();
                                    for (int i = 0; i < FADE_SAMPLES; i++)
                                        toFlush[i] = (short)(toFlush[i] * (float) i / FADE_SAMPLES);
                                    firstPre = false;
                                }
                                byte[] preBytes = AudioConverter.shortToBytes(toFlush);
                                System.arraycopy(preBytes, 0,         pushBuf0, 0, PUSH_BYTES);
                                System.arraycopy(preBytes, PUSH_BYTES, pushBuf1, 0, PUSH_BYTES);
                                customAudioSource.pushAudio(pushBuf0, 16, 48_000, 1, PUSH_SAMPLES);
                                customAudioSource.pushAudio(pushBuf1, 16, 48_000, 1, PUSH_SAMPLES);
                                // Mic-test track needs the same pre-roll flush — without it, the
                                // gate-open transition below arrives as an abrupt full-amplitude
                                // frame straight out of silence, heard as a pop (the real channel
                                // track doesn't click because it always got this flush).
                                micTestAudioSource.pushAudio(pushBuf0, 16, 48_000, 1, PUSH_SAMPLES);
                                micTestAudioSource.pushAudio(pushBuf1, 16, 48_000, 1, PUSH_SAMPLES);
                            }
                            // Current frame follows at full amplitude — pre-roll covers the onset.
                        }
                    }
                } else if (prevSpeech) {
                    toSend = agcOut; // real audio faded to zero — no abrupt cut
                    for (int i = FRAME_SAMPLES - FADE_SAMPLES; i < FRAME_SAMPLES; i++)
                        toSend[i] = (short)(toSend[i] * (float)(FRAME_SAMPLES - i) / FADE_SAMPLES);
                } else {
                    toSend = new short[FRAME_SAMPLES];
                }
                prevSpeech = speech;

                // Push current frame to WebRTC as two 10 ms chunks
                byte[] bytes = AudioConverter.shortToBytes(toSend);
                System.arraycopy(bytes, 0,         pushBuf0, 0, PUSH_BYTES);
                System.arraycopy(bytes, PUSH_BYTES, pushBuf1, 0, PUSH_BYTES);

                customAudioSource.pushAudio(pushBuf0, 16, 48_000, 1, PUSH_SAMPLES);
                customAudioSource.pushAudio(pushBuf1, 16, 48_000, 1, PUSH_SAMPLES);
                micTestAudioSource.pushAudio(pushBuf0, 16, 48_000, 1, PUSH_SAMPLES);
                micTestAudioSource.pushAudio(pushBuf1, 16, 48_000, 1, PUSH_SAMPLES);

                // Slide the pre-roll window forward AFTER pushing so the buffer
                // only ever contains past frames, never the one just sent.
                if (preRollBuffer.size() >= PRE_ROLL_FRAMES) preRollBuffer.pollFirst();
                preRollBuffer.addLast(agcSnapshot);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[AudioPipeline] Processing error: {}", e.getMessage(), e);
            }
        }

        log.info("[AudioPipeline] Processing thread stopped");
    }

    // ── Hot-swap config ───────────────────────────────────────────────────────

    /** Toggle AEC — takes effect on the next processed frame. */
    public void setAecEnabled(boolean enabled) {
        aec.setEnabled(enabled);
        log.debug("[AudioPipeline] AEC → {}", enabled);
    }

    /** Toggle noise suppression — takes effect on the next processed frame. */
    public void setNsEnabled(boolean enabled) {
        ns.setEnabled(enabled);
        log.debug("[AudioPipeline] NS → {}", enabled);
    }

    /** Toggle AGC — takes effect on the next processed frame. */
    public void setAgcEnabled(boolean enabled) {
        agc.setEnabled(enabled);
        log.debug("[AudioPipeline] AGC → {}", enabled);
    }

    /** Toggle VAD — when disabled, all audio is transmitted. */
    public void setVadEnabled(boolean enabled) {
        vad.setEnabled(enabled);
        log.debug("[AudioPipeline] VAD → {}", enabled);
    }

    /**
     * Set the Silero VAD speech probability threshold (0.0 = transmit everything, 1.0 = only
     * unambiguous speech). Corresponds to the "Voice detection" slider in the Audio settings.
     * <p>
     * Controls ONLY the neural-net confidence gate. Deliberately decoupled from the loudness
     * floor: Silero is largely loudness-independent (clear speech scores &gt;0.9 whether spoken
     * loudly or softly), so this slider mainly decides the fate of marginal sounds — whispers,
     * breaths, mumbling. Loudness gating is a separate user setting (see
     * {@link #setVadNoiseGateDb}); coupling the two into one slider previously made quiet
     * speech fail the loudness floor at any slider position above ~30%.
     */
    public void setVadThreshold(double threshold) {
        vad.setThreshold(threshold);
        log.debug("[AudioPipeline] VAD threshold → {}", threshold);
    }

    /**
     * Set the noise gate: the minimum post-AGC input level (dBFS) a sound must reach to OPEN
     * the transmit gate. Corresponds to the "Input sensitivity" slider in the Audio settings.
     * Checked only during gate onset (click backstop) — it never cuts speech already in
     * progress. −60 dBFS effectively disables it; −40 dBFS (default) sits well below quiet
     * speech but above desk-conducted mouse clicks.
     */
    public void setVadNoiseGateDb(double db) {
        vad.setOnsetMinRms((float) dbToRms(db));
        log.debug("[AudioPipeline] VAD noise gate → {} dBFS", db);
    }

    private static double dbToRms(double db) {
        return Math.pow(10.0, db / 20.0);
    }

    /** The soundboard local player (play/stop/volume/monitor controls). */
    public SoundboardMixer getSoundboardMixer() { return soundboardMixer; }

    /**
     * Hot-swap the input capture device. Safe to call while capturing.
     */
    public void changeInputDevice(String deviceName) {
        try {
            mic.changeDevice(deviceName);
        } catch (LineUnavailableException e) {
            log.error("[AudioPipeline] Failed to switch input device to '{}': {}", deviceName, e.getMessage(), e);
        }
    }

    // ── Status accessors ──────────────────────────────────────────────────────

    public boolean isAecEnabled()  { return aec.isEnabled(); }
    public boolean isNsEnabled()   { return ns.isEnabled(); }
    public boolean isAgcEnabled()  { return agc.isEnabled(); }
    public boolean isVadEnabled()  { return vad.isEnabled(); }
    public boolean isVadModelReady(){ return vad.isModelLoaded(); }
    public String  getNsBackend()  { return ns.backendStatus; }
    public String  getVadStatus()  { return vad.statusMessage; }
    public float   getMicLevel()   { return mic.levelRms; }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    @Override
    public void close() {
        stopCapture();
        try { soundboardMixer.close(); } catch (Exception e) { log.warn("[AudioPipeline] Soundboard close error", e); }
        try { aec.close(); } catch (Exception e) { log.warn("[AudioPipeline] AEC close error", e); }
        try { ns.close();  } catch (Exception e) { log.warn("[AudioPipeline] NS close error",  e); }
        try { agc.close(); } catch (Exception e) { log.warn("[AudioPipeline] AGC close error", e); }
        try { vad.close(); } catch (Exception e) { log.warn("[AudioPipeline] VAD close error", e); }
        log.info("[AudioPipeline] Closed");
    }
}
