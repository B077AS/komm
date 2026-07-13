package komm.webrtc;

import com.google.gson.Gson;
import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.Device;
import dev.onvoid.webrtc.media.DeviceChangeListener;
import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.MediaStream;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.*;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import komm.App;
import komm.api.json.GsonProvider;
import komm.utils.UserSettings;
import komm.webrtc.audio.AudioLoopbackCapture;
import komm.webrtc.pipeline.MicCapture;
import komm.webrtc.pipeline.SoundboardReceiver;
import komm.webrtc.pipeline.UserAudioPipeline;
import komm.webrtc.pipeline.UserVoiceReceiver;
import komm.ui.customnodes.CustomNotification;
import javafx.application.Platform;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import komm.websocket.InstallationWsClient;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.ChannelJoinPayload;
import komm.websocket.messages.payloads.ChannelLeavePayload;
import komm.websocket.messages.payloads.UserScreenSharePayload;
import komm.websocket.messages.payloads.VoiceTokenPayload;
import komm.ui.screenshare.SourceSelection;
import livekit.LivekitModels;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages WebRTC voice communication via LiveKit SFU.
 *
 * Audio architecture (post-pipeline refactor):
 * ─────────────────────────────────────────────
 *  Capture path (send):
 *    javax.sound mic → UserAudioPipeline (AEC3 → RNNoise → AGC2 → Silero-VAD)
 *    → CustomAudioSource.pushAudio() → Publisher PC → LiveKit
 *
 *  Playback path (receive):
 *    LiveKit → Subscriber PC → hardware AudioDeviceModule (playout only)
 *    → OS speaker
 *
 * The hardware AudioDeviceModule is initialized for PLAYOUT ONLY.
 * Microphone capture is handled entirely by UserAudioPipeline / MicCapture,
 * giving full DSP control without any OS-level audio driver coupling.
 */
@Slf4j
public class WebrtcRoomClient {

    private static final String AUDIO_TRACK_CID        = "audio0";
    private static final String SOUNDBOARD_TRACK_CID   = "soundboard0";
    private static final String SCREEN_TRACK_CID       = "screen0";
    private static final String SCREEN_AUDIO_TRACK_CID = "screen-audio0";
    private static final String MIC_TEST_TRACK_CID     = "mictest0";
    private static final int    PUBLISH_PUSH_SAMPLES  = 480;   // 10 ms @ 48 kHz
    private static final int    PUBLISH_PUSH_BYTES    = PUBLISH_PUSH_SAMPLES * 2;

    private final Gson gson = GsonProvider.get();

    private InstallationWsClient wsClient;
    private LiveKitSignalingClient livekitSignaling;

    // ── WebRTC objects ────────────────────────────────────────────────────────
    private PeerConnectionFactory factory;
    private RTCPeerConnection publisherPc;
    private RTCPeerConnection subscriberPc;

    /** Hardware ADM used ONLY for playout (speaker output). Capture is bypassed. */
    private AudioDeviceModule audioDeviceModule;
    /** Receives PCM pushed by UserAudioPipeline and presents it as a WebRTC audio track. */
    private CustomAudioSource customAudioSource;
    private AudioTrack localAudioTrack;
    /** Separate track for soundboard audio — lets LiveKit drive speaking rings naturally. */
    private CustomAudioSource soundboardAudioSource;
    private AudioTrack soundboardTrack;
    /** Dedicated track carrying captured system audio while screen-sharing the full screen. */
    private CustomAudioSource screenAudioSource;
    private AudioTrack screenAudioTrack;
    /**
     * Dedicated track carrying the same processed mic audio as localAudioTrack, but never
     * touched by setMicrophoneMuted()/setSpeakerMuted() — used exclusively by the Audio
     * settings mic-test loopback so testing still works while muted/deafened in a real call.
     */
    private CustomAudioSource micTestAudioSource;
    private AudioTrack micTestAudioTrack;
    /** System-audio loopback capturer (WASAPI on Windows, PulseAudio on Linux). Non-null only while active. */
    private volatile AudioLoopbackCapture systemAudioCapture;
    /** True while system audio is actually being captured/sent for the current share. */
    private volatile boolean screenAudioActive = false;
    private DeviceChangeListener deviceChangeListener;

    // ── Custom DSP pipeline ───────────────────────────────────────────────────
    private UserAudioPipeline audioPipeline;

    // ── Mic test loopback (local WebRTC round-trip for "hear yourself") ──────
    private RTCPeerConnection micTestPubPc;
    private RTCPeerConnection micTestSubPc;
    private AudioTrack micTestRemoteTrack;
    private UserVoiceReceiver micTestReceiver;

    // ── Screen share ──────────────────────────────────────────────────────────
    private ScreenShareClient screenShareClient;

    // ── State ─────────────────────────────────────────────────────────────────
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean micMuted    = false;
    @Getter private volatile boolean speakerMuted = false;
    private volatile String currentToken = null;

    private volatile String currentInputDeviceName  = null;
    private volatile String currentOutputDeviceName = null;

    @Getter private volatile UUID   currentServerId   = null;
    @Getter private volatile UUID   currentChannelId  = null;
    private volatile String currentLivekitUrl = null;

    private List<String> STUN_URLS;

    @Setter private volatile Runnable onConnectionStateChanged;
    private volatile Runnable onScreenShareStateChanged;

    private final CopyOnWriteArrayList<Runnable>     deviceListListeners    = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<AudioTrack>   remoteAudioTracks      = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<AudioTrack, SoundboardReceiver> remoteSoundboardTracks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AudioTrack, UserVoiceReceiver> remoteUserMicReceivers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AudioTrack, UserVoiceReceiver> remoteScreenAudioReceivers = new ConcurrentHashMap<>();
    /** userId → that user's screen-audio receiver. Active only while we are watching their stream. */
    private final ConcurrentHashMap<UUID, UserVoiceReceiver> userIdToScreenAudioReceiver = new ConcurrentHashMap<>();
    /** Users whose stream audio has been locally muted via the per-stream pill button. */
    private final java.util.Set<UUID> mutedStreamAudioUsers = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ConcurrentHashMap<UUID, UserVoiceReceiver> userIdToMicReceiver = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, VideoTrack>     remoteVideoTracks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, VideoTrackSink> pendingSinks       = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String>         activeSinkByUser   = new ConcurrentHashMap<>();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void start() {
        if (running.getAndSet(true)) return;

        // Opt-in native WebRTC logging (set KOMM_WEBRTC_DEBUG=1). Forwards libwebrtc's
        // own RTC_LOG output — including the PipeWire/xdg-desktop-portal capturer — into
        // komm.log so we can see whether Wayland screen capture is actually delivering
        // frames. Off by default; the native logger is noisy.
        if (System.getenv("KOMM_WEBRTC_DEBUG") != null) {
            try {
                dev.onvoid.webrtc.logging.Logging.addLogSink(
                        dev.onvoid.webrtc.logging.Logging.Severity.INFO,
                        (severity, message) ->
                                log.info("[native/{}] {}", severity, message.strip()));
                dev.onvoid.webrtc.logging.Logging.logToDebug(
                        dev.onvoid.webrtc.logging.Logging.Severity.NONE);
                log.info("[ScreenShare] Native WebRTC logging enabled (KOMM_WEBRTC_DEBUG)");
            } catch (Throwable t) {
                log.warn("[ScreenShare] Could not enable native WebRTC logging: {}", t.getMessage());
            }
        }

        UserSettings us = UserSettings.getInstance();

        // Hardware ADM — playout only (no recording; capture handled by UserAudioPipeline)
        audioDeviceModule = buildAudioDeviceModule();
        // Note: initRecording() / startRecording() are NOT called — our pipeline owns capture.
        audioDeviceModule.initPlayout();

        // CustomAudioSource — receives processed PCM from our pipeline
        customAudioSource = new CustomAudioSource();

        // Single factory: hardware ADM (for speaker playout) + no AudioProcessing (we handle DSP)
        factory = new PeerConnectionFactory(audioDeviceModule, null);
        localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_CID, customAudioSource);
        localAudioTrack.setEnabled(true);

        soundboardAudioSource = new CustomAudioSource();
        soundboardTrack = factory.createAudioTrack(SOUNDBOARD_TRACK_CID, soundboardAudioSource);
        soundboardTrack.setEnabled(true);

        // Screen-audio track — always present (like soundboard) but silent until a full-screen
        // share with audio feeds it. Avoids any per-share audio renegotiation.
        screenAudioSource = new CustomAudioSource();
        screenAudioTrack = factory.createAudioTrack(SCREEN_AUDIO_TRACK_CID, screenAudioSource);
        screenAudioTrack.setEnabled(true);

        // Mic-test track — always enabled, deliberately never touched by mute/deafen, so the
        // Audio settings mic test still works while muted/deafened in a real channel.
        micTestAudioSource = new CustomAudioSource();
        micTestAudioTrack = factory.createAudioTrack(MIC_TEST_TRACK_CID, micTestAudioSource);
        micTestAudioTrack.setEnabled(true);

        // Create DSP pipeline with persisted settings
        audioPipeline = new UserAudioPipeline(
                customAudioSource,
                micTestAudioSource,
                us.isEchoCancellation(),
                us.isNoiseSuppression(),
                us.isAgcEnabled(),
                us.isVadEnabled(),
                us.getVadSensitivity(),
                us.getVadNoiseGateDb()
        );

        log.info("[Voice] WebRTC client started — custom DSP pipeline active");
    }

    public void stop() {
        if (!running.getAndSet(false)) return;

        stopMicTestLoopback();
        stopSystemAudioCapture();

        if (screenShareClient != null) {
            screenShareClient.dispose();
            screenShareClient = null;
        }

        // Disconnect signaling here in case disconnectFromChannel() was never called
        // (e.g. peer connection was not in CONNECTED state when the app closed).
        if (livekitSignaling != null) {
            livekitSignaling.disconnect();
            livekitSignaling = null;
        }

        closePeerConnections();

        if (deviceChangeListener != null) {
            MediaDevices.removeDeviceChangeListener(deviceChangeListener);
            deviceChangeListener = null;
        }

        // Close pipeline (stops capture, disposes DSP modules)
        if (audioPipeline != null) {
            try { audioPipeline.close(); } catch (Exception e) { log.warn("[Voice] Pipeline close error", e); }
            audioPipeline = null;
        }

        localAudioTrack       = null;
        customAudioSource     = null;
        soundboardTrack       = null;
        soundboardAudioSource = null;
        screenAudioTrack      = null;
        screenAudioSource     = null;
        micTestAudioTrack     = null;
        micTestAudioSource    = null;

        if (factory != null) {
            factory.dispose();
            factory = null;
        }

        if (audioDeviceModule != null) {
            try { audioDeviceModule.stopPlayout(); } catch (Throwable ignored) {}
            audioDeviceModule.dispose();
            audioDeviceModule = null;
        }

        log.info("[Voice] WebRTC client stopped");
    }

    public boolean isRunning() { return running.get(); }

    // ── Channel connect / disconnect ──────────────────────────────────────────

    public void connectToChannel(String ip, UUID serverId, UUID channelId) {
        if (!running.get()) throw new IllegalStateException("Call start() before connectToChannel()");

        this.wsClient         = App.getServices().installation().getWsClient();
        this.currentServerId  = serverId;
        this.currentChannelId = channelId;

        wsClient.register(WsMessageType.CHANNEL_TOKEN, payload -> {
            VoiceTokenPayload voiceTokenPayload = gson.fromJson(payload, VoiceTokenPayload.class);
            wsClient.unregister(WsMessageType.CHANNEL_TOKEN);
            onVoiceTokenReceived(ip, voiceTokenPayload.getToken());
        });

        wsClient.send(WsMessageType.CHANNEL_JOIN, ChannelJoinPayload.builder()
                .channelId(channelId)
                .micEnabled(App.getUser().isMicEnabled())
                .speakerEnabled(App.getUser().isSpeakerEnabled())
                .build());
    }

    public void onVoiceTokenReceived(String livekitUrl, String token) {
        // Tear down any existing session before setting up the new one.
        // This handles channel switches where disconnectFromChannel() was not
        // called explicitly (e.g. server-forced move to another channel).
        remoteSoundboardTracks.forEach((track, receiver) -> { track.removeSink(receiver); receiver.close(); });
        remoteSoundboardTracks.clear();
        remoteUserMicReceivers.forEach((track, receiver) -> { track.removeSink(receiver); receiver.close(); });
        remoteUserMicReceivers.clear();
        remoteScreenAudioReceivers.forEach((track, receiver) -> { track.removeSink(receiver); receiver.close(); });
        remoteScreenAudioReceivers.clear();
        userIdToScreenAudioReceiver.clear();
        mutedStreamAudioUsers.clear();
        userIdToMicReceiver.clear();
        remoteAudioTracks.clear();
        pendingSinks.clear();
        activeSinkByUser.clear();
        remoteVideoTracks.clear();
        if (screenShareClient != null) { screenShareClient.dispose(); screenShareClient = null; }
        if (livekitSignaling   != null) { livekitSignaling.disconnect(); livekitSignaling = null; }
        closePeerConnections();
        if (audioPipeline != null) {
            audioPipeline.stopCapture();
            audioPipeline.getSoundboardMixer().stopAll();
            audioPipeline.getSoundboardMixer().setPublishCallback(null);
        }
        try { audioDeviceModule.stopPlayout(); } catch (Throwable ignored) {}
        try { audioDeviceModule.initPlayout(); } catch (Throwable ignored) {}

        this.currentToken      = token;
        this.currentLivekitUrl = livekitUrl;
        STUN_URLS = List.of("stun:" + livekitUrl);

        // Start hardware playout (speaker receives incoming audio via ADM)
        audioDeviceModule.startPlayout();

        publisherPc  = factory.createPeerConnection(buildRTCConfiguration(), new PublisherObserver());
        subscriberPc = factory.createPeerConnection(buildRTCConfiguration(), new SubscriberObserver());

        RTCRtpTransceiverInit sendInit = new RTCRtpTransceiverInit();
        sendInit.direction = RTCRtpTransceiverDirection.SEND_ONLY;
        publisherPc.addTransceiver(localAudioTrack, sendInit);

        RTCRtpTransceiverInit soundboardSendInit = new RTCRtpTransceiverInit();
        soundboardSendInit.direction = RTCRtpTransceiverDirection.SEND_ONLY;
        publisherPc.addTransceiver(soundboardTrack, soundboardSendInit);

        RTCRtpTransceiverInit screenAudioSendInit = new RTCRtpTransceiverInit();
        screenAudioSendInit.direction = RTCRtpTransceiverDirection.SEND_ONLY;
        publisherPc.addTransceiver(screenAudioTrack, screenAudioSendInit);

        RTCRtpTransceiverInit recvInit = new RTCRtpTransceiverInit();
        recvInit.direction = RTCRtpTransceiverDirection.RECV_ONLY;
        subscriberPc.addTransceiver(localAudioTrack, recvInit);

        livekitSignaling = new LiveKitSignalingClient();

        livekitSignaling.setOnJoined(() -> {
            log.info("[Voice] LiveKit join complete — sending AddTrackRequests (mic + soundboard)");
            livekitSignaling.sendAddTrackRequest(AUDIO_TRACK_CID, "microphone",
                    LivekitModels.TrackType.AUDIO, LivekitModels.TrackSource.MICROPHONE);
            livekitSignaling.sendAddTrackRequest(SOUNDBOARD_TRACK_CID, "soundboard",
                    LivekitModels.TrackType.AUDIO, LivekitModels.TrackSource.MICROPHONE);
            livekitSignaling.sendAddTrackRequest(SCREEN_AUDIO_TRACK_CID, "screen_audio",
                    LivekitModels.TrackType.AUDIO, LivekitModels.TrackSource.SCREEN_SHARE_AUDIO);
        });

        // Wait for both tracks to be acknowledged before creating the publisher offer,
        // so both are included in the initial SDP.
        java.util.Set<String> publishedCids = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
        livekitSignaling.setOnTrackPublished(cid -> {
            publishedCids.add(cid);
            if (publishedCids.containsAll(java.util.Set.of(
                    AUDIO_TRACK_CID, SOUNDBOARD_TRACK_CID, SCREEN_AUDIO_TRACK_CID))) {
                livekitSignaling.setOnTrackPublished(null); // one-shot: prevents spurious re-trigger on screen0 ack
                createAndSendPublisherOffer();
            }
        });

        livekitSignaling.setOnRefreshToken(newToken -> {
            log.debug("[Voice] Storing refreshed LiveKit token");
            this.currentToken = newToken;
        });

        livekitSignaling.setOnOffer(this::handleSubscriberOffer);

        livekitSignaling.setOnAnswer(remoteSdp -> {
            if (publisherPc != null) {
                publisherPc.setRemoteDescription(remoteSdp, new SetSessionDescriptionObserver() {
                    @Override public void onSuccess() {}
                    @Override public void onFailure(String e) {
                        log.error("[Publisher] setRemoteDescription (answer) failed: {}", e);
                    }
                });
            }
        });

        livekitSignaling.setOnRemoteIce((candidate, isPublisher) -> {
            if (isPublisher && publisherPc != null)       publisherPc.addIceCandidate(candidate);
            else if (!isPublisher && subscriberPc != null) subscriberPc.addIceCandidate(candidate);
        });

        livekitSignaling.setOnError(err -> log.error("[LiveKit] Signaling error: {}", err));

        livekitSignaling.liveKitConnect(livekitUrl, token);

        screenShareClient = new ScreenShareClient(factory, publisherPc, livekitSignaling);
        screenShareClient.setOnSharingStateChanged(() -> {
            boolean sharing = screenShareClient.isSharing();
            App.getServices().installation().getWsClient()
                    .send(WsMessageType.USER_SCREEN_SHARE, new UserScreenSharePayload(
                            App.getUser().getUserId(), sharing, sharing && screenAudioActive));
        });

        // Start custom mic capture → DSP pipeline → CustomAudioSource
        String inputDevice = UserSettings.getInstance().getInputDevice();
        audioPipeline.startCapture(inputDevice);

        // Carry current deafen state, output device, and saved volume into the freshly
        // started pipeline's soundboard player.  Volume must be applied here (not at
        // pipeline construction) because UserSettings is loaded after login, which
        // happens after WebrtcRoomClient.start() creates the pipeline.
        audioPipeline.getSoundboardMixer().setMonitorMuted(speakerMuted);
        audioPipeline.getSoundboardMixer().setOutputDevice(UserSettings.getInstance().getOutputDevice());
        audioPipeline.getSoundboardMixer().setVolume(UserSettings.getInstance().getSoundboardVolume());

        // Push each mixed soundboard frame into the dedicated WebRTC soundboard track so
        // LiveKit detects audio energy and fires SPEAKERS_CHANGED — driving the ring without
        // any client-side timer. The 20 ms mixer frame is split into two 10 ms WebRTC pushes.
        byte[] sbPush0 = new byte[PUBLISH_PUSH_BYTES];
        byte[] sbPush1 = new byte[PUBLISH_PUSH_BYTES];
        audioPipeline.getSoundboardMixer().setPublishCallback(bytes -> {
            CustomAudioSource src = soundboardAudioSource;
            if (src == null) return;
            System.arraycopy(bytes, 0,                  sbPush0, 0, PUBLISH_PUSH_BYTES);
            System.arraycopy(bytes, PUBLISH_PUSH_BYTES, sbPush1, 0, PUBLISH_PUSH_BYTES);
            src.pushAudio(sbPush0, 16, 48_000, 1, PUBLISH_PUSH_SAMPLES);
            src.pushAudio(sbPush1, 16, 48_000, 1, PUBLISH_PUSH_SAMPLES);
        });
    }

    public void disconnectFromChannel() {
        stopSystemAudioCapture();

        // Stop custom capture pipeline first
        if (audioPipeline != null) {
            audioPipeline.stopCapture();
            audioPipeline.getSoundboardMixer().stopAll();
            // Disconnect the soundboard WebRTC publish path before tearing down the PC.
            audioPipeline.getSoundboardMixer().setPublishCallback(null);
        }
        remoteSoundboardTracks.forEach((track, receiver) -> { track.removeSink(receiver); receiver.close(); });
        remoteSoundboardTracks.clear();
        remoteUserMicReceivers.forEach((track, receiver) -> { track.removeSink(receiver); receiver.close(); });
        remoteUserMicReceivers.clear();
        remoteScreenAudioReceivers.forEach((track, receiver) -> { track.removeSink(receiver); receiver.close(); });
        remoteScreenAudioReceivers.clear();
        userIdToScreenAudioReceiver.clear();
        mutedStreamAudioUsers.clear();
        userIdToMicReceiver.clear();

        // Detach remote video sinks
        pendingSinks.clear();
        for (Map.Entry<String, String> entry : activeSinkByUser.entrySet()) {
            VideoTrack track = remoteVideoTracks.get(entry.getValue());
            VideoTrackSink sink = pendingSinks.get(entry.getKey());
            if (track != null && sink != null) {
                try { track.removeSink(sink); } catch (Throwable ignored) {}
            }
        }
        activeSinkByUser.clear();
        remoteVideoTracks.clear();

        if (screenShareClient != null) {
            screenShareClient.dispose();
            screenShareClient = null;
        }

        if (wsClient != null) {
            wsClient.send(WsMessageType.CHANNEL_LEAVE, ChannelLeavePayload.builder()
                    .channelId(currentChannelId)
                    .build());
        }

        if (livekitSignaling != null) {
            livekitSignaling.disconnect();
            livekitSignaling = null;
        }

        closePeerConnections();
        currentToken      = null;
        currentChannelId  = null;
        currentServerId   = null;

        // Stop playout then immediately re-initialize so the ADM is ready for the next
        // join without requiring a full start() cycle.  Mirrors the stop→init→start
        // pattern already used in changeOutputDevice().
        try { audioDeviceModule.stopPlayout(); } catch (Throwable ignored) {}
        try { audioDeviceModule.initPlayout(); } catch (Throwable ignored) {}

        remoteAudioTracks.clear();

        Runnable cb = onConnectionStateChanged;
        if (cb != null) cb.run();

        log.info("[Voice] Disconnected from channel");
    }

    public boolean isInChannel() {
        if (!running.get() || publisherPc == null) return false;
        RTCPeerConnectionState state = publisherPc.getConnectionState();
        return state == RTCPeerConnectionState.CONNECTED;
    }

    // ── Screen share public API ───────────────────────────────────────────────

    public void startScreenShare(SourceSelection selection) {
        if (!isInChannel()) { log.warn("[ScreenShare] Not in channel"); return; }
        if (screenShareClient == null) { log.warn("[ScreenShare] Client not initialized"); return; }

        // System audio is only offered for a full screen (not a single window) on a
        // supported platform (Windows process-loopback or Linux PulseAudio/PipeWire).
        // Start it BEFORE the video share so the share-state callback reports an
        // accurate audio flag.
        stopSystemAudioCapture();
        boolean wantAudio = selection.isAudioEnabled() && !selection.isWindow()
                && AudioLoopbackCapture.isSupported();
        if (wantAudio) {
            try {
                startSystemAudioCapture();
                screenAudioActive = true;
            } catch (Throwable t) {
                screenAudioActive = false;
                log.error("[ScreenShare] System audio capture failed: {}", t.getMessage(), t);
                Platform.runLater(() -> new CustomNotification(
                        "Audio Error",
                        "Couldn't capture system audio.",
                        new FontIcon(MaterialDesignC.CLOSE_CIRCLE_OUTLINE)).showNotification());
            }
        } else {
            screenAudioActive = false;
        }

        screenShareClient.startSharing(selection.getSourceId(), selection.isWindow(), selection.getQuality());
    }

    public void stopScreenShare() {
        stopSystemAudioCapture();
        screenAudioActive = false;
        if (screenShareClient != null) screenShareClient.stopSharing();
    }

    /** True while we are actively capturing and sending system audio for the current share. */
    public boolean isScreenAudioActive() { return screenAudioActive; }

    private void startSystemAudioCapture() {
        AudioLoopbackCapture capture = AudioLoopbackCapture.create();
        capture.start(bytes -> {
            CustomAudioSource src = screenAudioSource;
            if (src != null) src.pushAudio(bytes, 16, 48_000, 1, 480);
        });
        systemAudioCapture = capture;
    }

    private void stopSystemAudioCapture() {
        AudioLoopbackCapture capture = systemAudioCapture;
        if (capture != null) {
            try { capture.stop(); } catch (Throwable t) {
                log.warn("[ScreenShare] Error stopping system audio capture: {}", t.getMessage());
            }
            systemAudioCapture = null;
        }
    }

    public boolean isScreenSharing() {
        return screenShareClient != null && screenShareClient.isSharing();
    }

    // ── Remote video subscription API ─────────────────────────────────────────

    public void subscribeToRemoteVideo(String userId, VideoTrackSink sink) {
        log.info("[RemoteVideo] Subscribing to video from userId={}", userId);
        pendingSinks.put(userId, sink);
        // Begin playing this user's screen audio (if any) now that we're watching them.
        setScreenAudioActiveForUser(userId, true);
        for (Map.Entry<String, VideoTrack> entry : remoteVideoTracks.entrySet()) {
            if (isScreenShareTrack(entry.getValue(), userId)) {
                attachSink(userId, entry.getKey(), entry.getValue(), sink);
                return;
            }
        }
        log.debug("[RemoteVideo] No track yet for userId={}, sink queued", userId);
    }

    public void unsubscribeRemoteVideo(String userId) {
        log.info("[RemoteVideo] Unsubscribing from video for userId={}", userId);
        VideoTrackSink sink   = pendingSinks.remove(userId);
        String         trackId = activeSinkByUser.remove(userId);
        if (sink != null && trackId != null) {
            VideoTrack track = remoteVideoTracks.get(trackId);
            if (track != null) { try { track.removeSink(sink); } catch (Throwable ignored) {} }
        }
        // Stop playing this user's screen audio once we stop watching.
        setScreenAudioActiveForUser(userId, false);
    }

    /** Activate/deactivate a remote user's screen-audio playback (independent of deafen). */
    private void setScreenAudioActiveForUser(String userId, boolean active) {
        try {
            UUID uid = UUID.fromString(userId);
            UserVoiceReceiver r = userIdToScreenAudioReceiver.get(uid);
            if (r != null) r.setActive(active && !mutedStreamAudioUsers.contains(uid));
        } catch (IllegalArgumentException ignored) {}
    }

    // ── Internal WebRTC helpers ───────────────────────────────────────────────

    private void createAndSendPublisherOffer() {
        if (publisherPc == null) return;
        publisherPc.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription offer) {
                publisherPc.setLocalDescription(offer, new SetSessionDescriptionObserver() {
                    @Override public void onSuccess() {
                        livekitSignaling.sendPublisherOffer(offer);
                    }
                    @Override public void onFailure(String e) {
                        log.error("[Publisher] setLocalDescription failed: {}", e);
                    }
                });
            }
            @Override public void onFailure(String e) {
                log.error("[Publisher] createOffer failed: {}", e);
            }
        });
    }

    private void handleSubscriberOffer(RTCSessionDescription offer) {
        if (subscriberPc == null) return;
        subscriberPc.setRemoteDescription(offer, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                subscriberPc.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
                    @Override
                    public void onSuccess(RTCSessionDescription answer) {
                        subscriberPc.setLocalDescription(answer, new SetSessionDescriptionObserver() {
                            @Override public void onSuccess() {
                                livekitSignaling.sendSubscriberAnswer(answer);
                            }
                            @Override public void onFailure(String e) {
                                log.error("[Subscriber] setLocalDescription (answer) failed: {}", e);
                            }
                        });
                    }
                    @Override public void onFailure(String e) {
                        log.error("[Subscriber] createAnswer failed: {}", e);
                    }
                });
            }
            @Override public void onFailure(String e) {
                log.error("[Subscriber] setRemoteDescription failed: {}", e);
            }
        });
    }

    // ── PeerConnection observers ──────────────────────────────────────────────

    private class PublisherObserver implements PeerConnectionObserver {
        @Override public void onIceCandidate(RTCIceCandidate candidate) {
            if (livekitSignaling != null) livekitSignaling.sendIceCandidate(candidate, true);
        }
        @Override public void onConnectionChange(RTCPeerConnectionState state) {
            Runnable cb = onConnectionStateChanged;
            if (cb != null) cb.run();
        }
        @Override public void onIceCandidateError(RTCPeerConnectionIceErrorEvent e) {
            log.warn("[Publisher] ICE error: {}", e.getErrorText());
        }
        @Override public void onSignalingChange(RTCSignalingState s)       {}
        @Override public void onIceConnectionChange(RTCIceConnectionState s){}
        @Override public void onIceGatheringChange(RTCIceGatheringState s)  {}
        @Override public void onAddStream(MediaStream stream)               {}
        @Override public void onRemoveStream(MediaStream stream)            {}
        @Override public void onDataChannel(RTCDataChannel channel)         {}
        @Override public void onRenegotiationNeeded()                       {}
        @Override public void onRemoveTrack(RTCRtpReceiver receiver)        {}
        @Override public void onAddTrack(RTCRtpReceiver r, MediaStream[] s) {}
        @Override public void onTrack(RTCRtpTransceiver transceiver)        {}
    }

    private class SubscriberObserver implements PeerConnectionObserver {
        @Override public void onIceCandidate(RTCIceCandidate candidate) {
            if (livekitSignaling != null) livekitSignaling.sendIceCandidate(candidate, false);
        }
        @Override public void onTrack(RTCRtpTransceiver transceiver) {
            MediaStreamTrack track = transceiver.getReceiver().getTrack();
            log.debug("[Subscriber] onTrack: kind={} id={}", track.getKind(), track.getId());

            if (track instanceof AudioTrack remoteTrack) {
                String trackName = livekitSignaling != null
                        ? livekitSignaling.getTrackName(remoteTrack.getId()) : null;
                if ("soundboard".equals(trackName)) {
                    // Play soundboard audio through the WebRTC track just like mic audio —
                    // this keeps all listeners in perfect sync with the sender's real-time
                    // stream. The sender hears their own sound locally via SoundboardMixer
                    // (LiveKit never echoes a publisher's own tracks back to them).
                    SoundboardReceiver receiver = new SoundboardReceiver(
                            UserSettings.getInstance().getSoundboardVolume(),
                            UserSettings.getInstance().getOutputDevice());
                    if (speakerMuted) receiver.setActive(false);
                    remoteTrack.addSink(receiver);
                    remoteTrack.setEnabled(false); // ADM is bypassed; sink handles scaled playback
                    remoteSoundboardTracks.put(remoteTrack, receiver);
                    log.info("[Subscriber] Remote soundboard track connected: {}", remoteTrack.getId());
                } else if ("screen_audio".equals(trackName)) {
                    // System audio from a remote user's full-screen share. Like the video stream,
                    // this only plays while we are actually WATCHING that user's stream — it stays
                    // muted for channel members who haven't opened the stream. Activated/deactivated
                    // by subscribeToRemoteVideo / unsubscribeRemoteVideo.
                    String identity = livekitSignaling != null
                            ? livekitSignaling.getTrackParticipantId(remoteTrack.getId()) : null;
                    UUID participantId = null;
                    if (identity != null) {
                        try { participantId = UUID.fromString(identity); } catch (IllegalArgumentException ignored) {}
                    }
                    boolean watching = identity != null && pendingSinks.containsKey(identity);
                    boolean streamMuted = participantId != null && mutedStreamAudioUsers.contains(participantId);
                    float savedStreamVol = participantId != null
                            ? UserSettings.getInstance().getStreamVolume(participantId) : 1.0f;
                    UserVoiceReceiver receiver = new UserVoiceReceiver(
                            savedStreamVol, UserSettings.getInstance().getOutputDevice());
                    receiver.setActive(watching && !streamMuted);
                    remoteTrack.addSink(receiver);
                    remoteTrack.setEnabled(false); // ADM bypassed; sink handles playback
                    remoteScreenAudioReceivers.put(remoteTrack, receiver);
                    if (participantId != null) userIdToScreenAudioReceiver.put(participantId, receiver);
                    log.info("[Subscriber] Remote screen-audio track connected: userId={} watching={}",
                            participantId, watching);
                } else {
                    // Route mic track through UserVoiceReceiver for per-user volume control.
                    String identity = livekitSignaling != null
                            ? livekitSignaling.getTrackParticipantId(remoteTrack.getId()) : null;
                    UUID participantId = null;
                    if (identity != null) {
                        try { participantId = UUID.fromString(identity); } catch (IllegalArgumentException ignored) {}
                    }
                    if (participantId != null) {
                        float savedVol = UserSettings.getInstance().getUserVolume(participantId);
                        UserVoiceReceiver receiver = new UserVoiceReceiver(
                                savedVol, UserSettings.getInstance().getOutputDevice());
                        if (speakerMuted) receiver.setActive(false);
                        remoteTrack.addSink(receiver);
                        remoteTrack.setEnabled(false); // ADM bypassed; sink handles scaled playback
                        remoteUserMicReceivers.put(remoteTrack, receiver);
                        userIdToMicReceiver.put(participantId, receiver);
                        log.info("[Subscriber] Remote mic track connected: userId={} volume={}", participantId, savedVol);
                    } else {
                        // Fallback when participant mapping is unavailable: let the hardware ADM handle it.
                        remoteTrack.setEnabled(!speakerMuted);
                        log.warn("[Subscriber] Remote mic track has no participant mapping, using ADM: {}", remoteTrack.getId());
                    }
                    remoteAudioTracks.add(remoteTrack);
                }

            } else if (track instanceof VideoTrack videoTrack) {
                String trackId = videoTrack.getId();
                remoteVideoTracks.put(trackId, videoTrack);
                log.info("[Subscriber] Remote video track arrived: id={}", trackId);

                for (Map.Entry<String, VideoTrackSink> entry : pendingSinks.entrySet()) {
                    String userId = entry.getKey();
                    if (!activeSinkByUser.containsKey(userId)) {
                        attachSink(userId, trackId, videoTrack, entry.getValue());
                        break;
                    }
                }
            }
        }
        @Override public void onIceConnectionChange(RTCIceConnectionState state) {
            log.debug("[Subscriber] ICE state: {}", state);
        }
        @Override public void onConnectionChange(RTCPeerConnectionState state) {
            log.debug("[Subscriber] Connection state: {}", state);
        }
        @Override public void onRenegotiationNeeded() {
            log.debug("[Subscriber] Renegotiation needed — waiting for LiveKit offer");
        }
        @Override public void onIceCandidateError(RTCPeerConnectionIceErrorEvent e) {
            log.warn("[Subscriber] ICE error: {}", e.getErrorText());
        }
        @Override public void onSignalingChange(RTCSignalingState s)        {}
        @Override public void onIceGatheringChange(RTCIceGatheringState s)  {}
        @Override public void onAddStream(MediaStream stream)               {}
        @Override public void onRemoveStream(MediaStream stream)            {}
        @Override public void onDataChannel(RTCDataChannel channel)         {}
        @Override public void onRemoveTrack(RTCRtpReceiver receiver)        {}
        @Override public void onAddTrack(RTCRtpReceiver r, MediaStream[] s) {}
    }

    // ── Remote video helpers ──────────────────────────────────────────────────

    private void attachSink(String userId, String trackId, VideoTrack track, VideoTrackSink sink) {
        track.addSink(sink);
        activeSinkByUser.put(userId, trackId);
        log.info("[RemoteVideo] Sink attached: userId={} trackId={}", userId, trackId);
    }

    private boolean isScreenShareTrack(VideoTrack track, String userId) {
        return !activeSinkByUser.containsKey(userId);
    }

    // ── Audio controls ────────────────────────────────────────────────────────

    public void setMicrophoneMuted(boolean muted) {
        this.micMuted = muted;
        if (localAudioTrack != null) localAudioTrack.setEnabled(!muted);
    }

    public boolean isMicrophoneMuted() { return micMuted; }

    public void setSpeakerMuted(boolean muted) {
        this.speakerMuted = muted;
        for (AudioTrack track : remoteAudioTracks) {
            UserVoiceReceiver micReceiver = remoteUserMicReceivers.get(track);
            if (micReceiver != null) {
                micReceiver.setActive(!muted);
            } else {
                track.setEnabled(!muted);
            }
        }
        remoteSoundboardTracks.values().forEach(r -> r.setActive(!muted));
        if (audioPipeline != null) audioPipeline.getSoundboardMixer().setMonitorMuted(muted);
    }

    /** Mute/unmute a specific user's screen-share audio stream independently of deafen. */
    public void setStreamAudioMuted(String userId, boolean muted) {
        try {
            UUID uid = UUID.fromString(userId);
            if (muted) mutedStreamAudioUsers.add(uid);
            else mutedStreamAudioUsers.remove(uid);
            UserVoiceReceiver r = userIdToScreenAudioReceiver.get(uid);
            if (r != null) r.setActive(!muted && pendingSinks.containsKey(userId));
        } catch (IllegalArgumentException ignored) {}
    }

    /** Sets the local-only playback volume for a specific remote user's screen-share audio (0.0–2.0). */
    public void setStreamAudioVolume(String userId, float volume) {
        try {
            UUID uid = UUID.fromString(userId);
            float v = Math.max(0f, Math.min(2.0f, volume));
            UserVoiceReceiver r = userIdToScreenAudioReceiver.get(uid);
            if (r != null) r.setVolume(v);
            UserSettings.getInstance().setStreamVolume(uid, v);
        } catch (IllegalArgumentException ignored) {}
    }

    /** Returns the saved local volume for a remote user's screen-share audio (default 1.0 = 100%). */
    public float getStreamAudioVolume(String userId) {
        try {
            return UserSettings.getInstance().getStreamVolume(UUID.fromString(userId));
        } catch (IllegalArgumentException ignored) {
            return 1.0f;
        }
    }

    // ── Soundboard ────────────────────────────────────────────────────────────

    /**
     * Play decoded mono-48 kHz PCM locally (per-listener volume). Replaying the same
     * {@code key} restarts that sound; different keys overlap. No-op while deafened.
     */
    public void playSoundboard(String key, short[] pcm) {
        if (audioPipeline != null) audioPipeline.getSoundboardMixer().play(key, pcm);
    }

    /** Stop every soundboard currently playing locally. */
    public void stopSoundboards() {
        if (audioPipeline != null) audioPipeline.getSoundboardMixer().stopAll();
    }

    /** Stop the locally-playing sounds that were triggered by the given user. */
    public void stopSoundboardsBy(java.util.UUID userId) {
        if (audioPipeline != null) audioPipeline.getSoundboardMixer().stopByKeyPrefix(userId + ":");
    }

    /** Soundboard volume (0.0–1.0). Controls local monitor (sender) and received track gain (subscribers). */
    public void setSoundboardVolume(float volume) {
        float v = Math.max(0f, Math.min(1.0f, volume));
        if (audioPipeline != null) audioPipeline.getSoundboardMixer().setVolume(v);
        remoteSoundboardTracks.values().forEach(r -> r.setVolume(v));
        UserSettings.getInstance().setSoundboardVolume(v);
    }

    // ── Per-user voice volume ─────────────────────────────────────────────────

    /**
     * Sets the local-only playback volume for a specific remote user's mic (0.0–2.0).
     * Persisted to UserSettings so it is restored on reconnect or next session.
     */
    public void setUserVolume(UUID userId, float volume) {
        float v = Math.max(0f, Math.min(2.0f, volume));
        UserVoiceReceiver receiver = userIdToMicReceiver.get(userId);
        if (receiver != null) receiver.setVolume(v);
        UserSettings.getInstance().setUserVolume(userId, v);
    }

    /** Returns the saved local volume for a remote user (default 1.0 = 100%). */
    public float getUserVolume(UUID userId) {
        return UserSettings.getInstance().getUserVolume(userId);
    }

    // ── Device management ─────────────────────────────────────────────────────

    /**
     * Returns input device names from javax.sound (since we capture via MicCapture).
     */
    public List<String> getInputDevices() {
        return MicCapture.listInputDevices();
    }

    /**
     * Returns output device names from the hardware ADM (playout still uses WebRTC ADM).
     */
    public List<String> getOutputDevices() {
        List<String> names = new ArrayList<>();
        names.add("Default");
        MediaDevices.getAudioRenderDevices().stream().map(AudioDevice::getName).forEach(names::add);
        return Collections.unmodifiableList(names);
    }

    /**
     * Hot-swap the microphone device. Forwards to UserAudioPipeline which manages
     * the javax.sound capture line; no reconnection required.
     */
    public void changeInputDevice(String deviceName) {
        if (deviceName == null || deviceName.equals(currentInputDeviceName)) return;
        currentInputDeviceName = deviceName;
        UserSettings.getInstance().setInputDevice(deviceName);
        if (audioPipeline != null) audioPipeline.changeInputDevice(deviceName);
    }

    /**
     * Hot-swap the speaker device. The hardware ADM is used for playout, so the
     * standard stop/setDevice/init/start cycle applies.
     */
    public void changeOutputDevice(String deviceName) {
        if (deviceName == null || deviceName.equals(currentOutputDeviceName)) return;
        currentOutputDeviceName = deviceName;
        UserSettings.getInstance().setOutputDevice(deviceName);
        if (audioPipeline != null) audioPipeline.getSoundboardMixer().setOutputDevice(deviceName);
        remoteSoundboardTracks.values().forEach(r -> r.setOutputDevice(deviceName));
        remoteUserMicReceivers.values().forEach(r -> r.setOutputDevice(deviceName));
        remoteScreenAudioReceivers.values().forEach(r -> r.setOutputDevice(deviceName));
        if (audioDeviceModule == null) return;

        AudioDevice target = resolveRender(deviceName);
        if (target == null) target = MediaDevices.getDefaultAudioRenderDevice();

        boolean wasInChannel = isInChannel();
        try { audioDeviceModule.stopPlayout(); } catch (Throwable ignored) {}
        audioDeviceModule.setPlayoutDevice(target);
        audioDeviceModule.initPlayout();
        if (wasInChannel) audioDeviceModule.startPlayout();
    }

    public String getCurrentInputDevice()  { return currentInputDeviceName; }
    public String getCurrentOutputDevice() { return currentOutputDeviceName; }

    public void addDeviceListListener(Runnable listener)    { deviceListListeners.add(listener); }
    public void removeDeviceListListener(Runnable listener) { deviceListListeners.remove(listener); }

    // ── DSP pipeline toggles (hot-swap, no reconnect needed) ─────────────────

    public void setEchoCancellation(boolean enabled) {
        if (audioPipeline != null) audioPipeline.setAecEnabled(enabled);
        UserSettings.getInstance().setEchoCancellation(enabled);
    }

    public void setNoiseSuppression(boolean enabled) {
        if (audioPipeline != null) audioPipeline.setNsEnabled(enabled);
        UserSettings.getInstance().setNoiseSuppression(enabled);
    }

    public void setAutoGainControl(boolean enabled) {
        if (audioPipeline != null) audioPipeline.setAgcEnabled(enabled);
        UserSettings.getInstance().setAgcEnabled(enabled);
    }

    public void setVadEnabled(boolean enabled) {
        if (audioPipeline != null) audioPipeline.setVadEnabled(enabled);
        UserSettings.getInstance().setVadEnabled(enabled);
    }

    public void setVadThreshold(float threshold) {
        if (audioPipeline != null) audioPipeline.setVadThreshold(threshold);
        UserSettings.getInstance().setVadSensitivity(threshold);
    }

    public void setVadNoiseGate(float db) {
        if (audioPipeline != null) audioPipeline.setVadNoiseGateDb(db);
        UserSettings.getInstance().setVadNoiseGateDb(db);
    }

    /** Human-readable status of the Silero VAD model ("ready", "downloading…", "load failed", etc.) */
    public String getVadStatus() {
        return audioPipeline != null ? audioPipeline.getVadStatus() : "Pipeline not started";
    }

    /** True once the Silero model has been loaded and VAD is functional. */
    public boolean isVadModelReady() {
        return audioPipeline != null && audioPipeline.isVadModelReady();
    }

    /** Human-readable noise suppressor backend ("RNNoise (native, 48 kHz)" or "WebRTC NS (RNNoise unavailable)"). */
    public String getNsBackend() {
        return audioPipeline != null ? audioPipeline.getNsBackend() : "";
    }

    // ── Mic level test (Audio settings UI) ────────────────────────────────────

    /** True while the DSP pipeline is actively capturing — either in a live channel or a UI-driven test. */
    public boolean isCapturingAudio() {
        return audioPipeline != null && audioPipeline.isCapturing();
    }

    /**
     * Starts a temporary capture for the microphone-level meter in the Audio settings UI,
     * unless the pipeline is already capturing (e.g. the user is in a voice channel).
     * Returns true if this call actually started capture, in which case the caller is
     * responsible for calling {@link #stopMicTest()} once the meter is no longer shown.
     */
    public boolean startMicTestIfIdle() {
        if (audioPipeline == null || audioPipeline.isCapturing()) return false;
        audioPipeline.startCapture(UserSettings.getInstance().getInputDevice());
        return true;
    }

    /** Stops a capture previously started by {@link #startMicTestIfIdle()}. */
    public void stopMicTest() {
        if (audioPipeline != null) audioPipeline.stopCapture();
    }

    /** Current mic level (post NS/AGC) as RMS in [0, 1] — what the VAD gate actually sees. */
    public float   getMicLevelRms() { return audioPipeline != null ? audioPipeline.postProcessRms : 0f; }
    /** Latest Silero speech probability in [0, 1] — what the detection threshold compares against. */
    public float   getVadProbability() { return audioPipeline != null ? audioPipeline.vadProbability : 0f; }
    /** True while the VAD gate is open, i.e. audio is currently being transmitted. */
    public boolean isVadSpeaking()  { return audioPipeline != null && audioPipeline.vadActive; }

    /**
     * Hot-swaps the capture device for live preview during a mic test, without persisting it to
     * {@link UserSettings}. Safe to call even against a real channel's ongoing capture (i.e.
     * when {@link #startMicTestIfIdle()} returned {@code false}) as long as the caller has
     * force-muted first, since it otherwise switches the actual live capture that channel is
     * using — {@link #changeInputDevice(String)} is the persisting equivalent, applied on Save.
     */
    public void previewInputDeviceForTest(String deviceName) {
        if (audioPipeline != null) audioPipeline.changeInputDevice(deviceName);
    }

    /**
     * Hot-swaps the mic-test loopback's own playback device for live preview. Never touches
     * {@link UserSettings} or the app's real speaker output — no-op if no test is active.
     */
    public void previewMicTestOutputDevice(String deviceName) {
        if (micTestReceiver != null) micTestReceiver.setOutputDevice(deviceName);
    }

    /**
     * Starts a local "hear yourself" loopback for the Audio settings mic test: two peer
     * connections wired directly to each other within this process (no signaling server, no
     * STUN needed since both ends are local), so the outgoing mic track is actually
     * Opus-encoded, decoded, and played back through the same jitter-buffered pipeline used
     * for real remote voices. A raw local PCM player sounds choppy because it lacks that
     * jitter smoothing — this reuses the real thing instead.
     * <p>
     * Uses {@code micTestAudioTrack} rather than {@code localAudioTrack} — a dedicated track
     * carrying the same processed audio that is never disabled by mute/deafen, so the test
     * still works even while muted/deafened in a real channel (same as Discord's own mic test,
     * which forces you into mute+deafen but still lets you hear yourself). This never touches
     * {@code publisherPc}, so it cannot interfere with an active voice channel connection.
     */
    public void startMicTestLoopback() {
        if (factory == null || micTestAudioTrack == null) return;
        stopMicTestLoopback();

        micTestPubPc = factory.createPeerConnection(new RTCConfiguration(), new MicTestLoopbackObserver(true));
        micTestSubPc = factory.createPeerConnection(new RTCConfiguration(), new MicTestLoopbackObserver(false));

        RTCRtpTransceiverInit sendInit = new RTCRtpTransceiverInit();
        sendInit.direction = RTCRtpTransceiverDirection.SEND_ONLY;
        micTestPubPc.addTransceiver(micTestAudioTrack, sendInit);

        RTCRtpTransceiverInit recvInit = new RTCRtpTransceiverInit();
        recvInit.direction = RTCRtpTransceiverDirection.RECV_ONLY;
        micTestSubPc.addTransceiver(micTestAudioTrack, recvInit);

        micTestPubPc.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
            @Override public void onSuccess(RTCSessionDescription offer) {
                RTCPeerConnection pub = micTestPubPc;
                if (pub == null) return;
                pub.setLocalDescription(offer, new SetSessionDescriptionObserver() {
                    @Override public void onSuccess() { relayOfferToSubscriber(offer); }
                    @Override public void onFailure(String e) { log.error("[MicTest] pub setLocalDescription failed: {}", e); }
                });
            }
            @Override public void onFailure(String e) { log.error("[MicTest] createOffer failed: {}", e); }
        });

        log.info("[MicTest] Local loopback peer connections created");
    }

    private void relayOfferToSubscriber(RTCSessionDescription offer) {
        RTCPeerConnection sub = micTestSubPc;
        if (sub == null) return;
        sub.setRemoteDescription(offer, new SetSessionDescriptionObserver() {
            @Override public void onSuccess() {
                sub.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
                    @Override public void onSuccess(RTCSessionDescription answer) {
                        sub.setLocalDescription(answer, new SetSessionDescriptionObserver() {
                            @Override public void onSuccess() { relayAnswerToPublisher(answer); }
                            @Override public void onFailure(String e) { log.error("[MicTest] sub setLocalDescription failed: {}", e); }
                        });
                    }
                    @Override public void onFailure(String e) { log.error("[MicTest] createAnswer failed: {}", e); }
                });
            }
            @Override public void onFailure(String e) { log.error("[MicTest] sub setRemoteDescription failed: {}", e); }
        });
    }

    private void relayAnswerToPublisher(RTCSessionDescription answer) {
        RTCPeerConnection pub = micTestPubPc;
        if (pub == null) return;
        pub.setRemoteDescription(answer, new SetSessionDescriptionObserver() {
            @Override public void onSuccess() {}
            @Override public void onFailure(String e) { log.error("[MicTest] pub setRemoteDescription failed: {}", e); }
        });
    }

    /** Tears down the mic-test loopback. Safe to call even if it was never started. */
    public void stopMicTestLoopback() {
        if (micTestRemoteTrack != null && micTestReceiver != null) {
            try { micTestRemoteTrack.removeSink(micTestReceiver); } catch (Throwable ignored) {}
        }
        if (micTestReceiver != null) { micTestReceiver.close(); micTestReceiver = null; }
        micTestRemoteTrack = null;
        if (micTestPubPc != null) { micTestPubPc.close(); micTestPubPc = null; }
        if (micTestSubPc != null) { micTestSubPc.close(); micTestSubPc = null; }
    }

    /** Minimal observer shared by both ends of the local mic-test loopback pair. */
    private class MicTestLoopbackObserver implements PeerConnectionObserver {
        private final boolean isPublisherSide;
        MicTestLoopbackObserver(boolean isPublisherSide) { this.isPublisherSide = isPublisherSide; }

        @Override public void onIceCandidate(RTCIceCandidate candidate) {
            RTCPeerConnection other = isPublisherSide ? micTestSubPc : micTestPubPc;
            if (other != null) other.addIceCandidate(candidate);
        }
        @Override public void onTrack(RTCRtpTransceiver transceiver) {
            if (isPublisherSide) return;
            MediaStreamTrack track = transceiver.getReceiver().getTrack();
            if (!(track instanceof AudioTrack remoteTrack)) return;

            UserVoiceReceiver receiver = new UserVoiceReceiver(1.0f, UserSettings.getInstance().getOutputDevice());
            remoteTrack.addSink(receiver);
            remoteTrack.setEnabled(false); // sink handles playback, bypass ADM — same pattern as real remote tracks
            micTestRemoteTrack = remoteTrack;
            micTestReceiver    = receiver;
            log.info("[MicTest] Loopback track connected");
        }
        @Override public void onConnectionChange(RTCPeerConnectionState s)          {}
        @Override public void onIceCandidateError(RTCPeerConnectionIceErrorEvent e) {
            log.warn("[MicTest] ICE error: {}", e.getErrorText());
        }
        @Override public void onSignalingChange(RTCSignalingState s)        {}
        @Override public void onIceConnectionChange(RTCIceConnectionState s){}
        @Override public void onIceGatheringChange(RTCIceGatheringState s)  {}
        @Override public void onAddStream(MediaStream stream)               {}
        @Override public void onRemoveStream(MediaStream stream)            {}
        @Override public void onDataChannel(RTCDataChannel channel)         {}
        @Override public void onRenegotiationNeeded()                       {}
        @Override public void onRemoveTrack(RTCRtpReceiver receiver)        {}
        @Override public void onAddTrack(RTCRtpReceiver r, MediaStream[] s) {}
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private AudioDeviceModule buildAudioDeviceModule() {
        AudioDeviceModule adm = new AudioDeviceModule();

        String savedOutput = UserSettings.getInstance().getOutputDevice();
        if (savedOutput != null && !savedOutput.equals("Default")) {
            AudioDevice d = resolveRender(savedOutput);
            if (d != null) {
                adm.setPlayoutDevice(d);
                currentOutputDeviceName = savedOutput;
            } else {
                adm.setPlayoutDevice(MediaDevices.getDefaultAudioRenderDevice());
            }
        } else {
            adm.setPlayoutDevice(MediaDevices.getDefaultAudioRenderDevice());
        }

        // Restore current input device name for display purposes (actual capture via javax.sound)
        String savedInput = UserSettings.getInstance().getInputDevice();
        if (savedInput != null && !savedInput.equals("Default")) {
            currentInputDeviceName = savedInput;
        }

        deviceChangeListener = new DeviceChangeListener() {
            @Override public void deviceConnected(Device device) {
                deviceListListeners.forEach(Runnable::run);
            }
            @Override public void deviceDisconnected(Device device) {
                deviceListListeners.forEach(Runnable::run);
            }
        };
        MediaDevices.addDeviceChangeListener(deviceChangeListener);
        return adm;
    }

    private AudioDevice resolveRender(String name) {
        if ("Default".equals(name)) return MediaDevices.getDefaultAudioRenderDevice();
        return MediaDevices.getAudioRenderDevices().stream()
                .filter(d -> d.getName().equals(name)).findFirst().orElse(null);
    }

    private RTCConfiguration buildRTCConfiguration() {
        RTCConfiguration config = new RTCConfiguration();
        RTCIceServer stun = new RTCIceServer();
        stun.urls.addAll(STUN_URLS);
        config.iceServers.add(stun);
        return config;
    }

    private void closePeerConnections() {
        if (publisherPc != null) { publisherPc.close();  publisherPc  = null; }
        if (subscriberPc != null){ subscriberPc.close(); subscriberPc = null; }
    }
}
