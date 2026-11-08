package komm.webrtc;

import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.MediaSource;
import dev.onvoid.webrtc.media.video.VideoDesktopSource;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import komm.ui.screenshare.ScreenShareQuality;
import livekit.LivekitModels;
import lombok.extern.slf4j.Slf4j;

import dev.onvoid.webrtc.media.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages a screen-share video track inside an already-connected LiveKit room.
 *
 * <h3>Bug-fixes applied in this revision</h3>
 *
 * <h4>1. Warm-up choppiness (~10 s) and motion quality loss</h4>
 * <p>WebRTC's bandwidth estimator (BWE) always starts at ~150-300 kbps and
 * probes upward over 5-15 seconds using REMB/TWCC feedback. This is the
 * <em>actual</em> cause of both symptoms — not the rendering pipeline.
 * The fix is to call {@link #applyBitrateSettings()} immediately after
 * {@code addTrack()} / {@code replaceTrack()}: it restarts the estimator from a
 * usable bitrate with {@code RTCPeerConnection.setBitrate(null, start, max)}
 * (webrtc-java 0.20+), so the first seconds don't crawl at the default.</p>
 *
 * <p>Bitrate values scale with the selected quality preset (see
 * {@link #targetBitrateBps}): the estimator starts at half the preset's target
 * and the encoder ceiling is twice the target, capped at
 * {@link #MAX_BITRATE_CAP_BPS}. Only the <em>estimate</em> is seeded, never the
 * encoder's {@code minBitrate}: pinning a floor above what the path can really
 * carry makes LiveKit's congestion controller periodically pause the stream,
 * which shows up as rhythmic stuttering on the viewer's side. (Before 0.20 the
 * only way to skip the warm-up was such a floor.)</p>
 * <p>Per the webrtc-java constraints docs, you must always do
 * {@code getParameters() → modify → setParameters()}. Constructing a new
 * {@code RTCRtpSendParameters} from scratch loses the transaction ID and the
 * call silently fails. This method follows that contract exactly.</p>
 *
 * <h4>2. {@code sendAddTrackRequest} ordering</h4>
 * <p>The previous code sent the {@code AddTrackRequest} <em>before</em>
 * {@code triggerRenegotiation()} had even started. For screen-share
 * renegotiation (as opposed to the initial audio join), LiveKit processes the
 * track signal most reliably when it arrives <em>after</em> the offer is
 * committed. {@code sendAddTrackRequest} is now called from inside the
 * {@code setLocalDescription} success callback so the ordering is:
 * offer set → offer sent to LiveKit → AddTrackRequest sent.</p>
 *
 * <p>Note: this only applies to the <em>first</em> time a screen track is
 * added ({@code sender == null}). On reuse ({@code replaceTrack}), no new
 * AddTrackRequest is needed because LiveKit already knows about this track
 * CID; we only renegotiate.</p>
 */
@Slf4j
public class ScreenShareClient {

    /** Track/AddTrackRequest cid prefix; a fresh cid is used whenever a new sender is published. */
    private static final String VIDEO_TRACK_CID_PREFIX = "screen";

    // Absolute encoder ceiling regardless of the selected quality preset.
    private static final int MAX_BITRATE_CAP_BPS = 12_000_000;

    /**
     * Wayland screen capture goes through xdg-desktop-portal: the desktop source
     * doesn't produce any frame until the user answers the portal's picker dialog,
     * which can take several seconds. We must not publish the track / send the SDP
     * offer before the first frame exists, or LiveKit negotiates an empty video
     * track that never recovers (viewers stay on "Waiting for stream"). On X11 and
     * Windows the first frame is effectively instant, so this deferral is Wayland-only.
     */
    private static final boolean WAYLAND = com.sun.jna.Platform.isLinux()
            && (System.getenv("WAYLAND_DISPLAY") != null
                || "wayland".equalsIgnoreCase(System.getenv("XDG_SESSION_TYPE")));

    /**
     * Safety net: abort the pending share if no frame ever arrives. The portal picker
     * itself has no deadline — cancel/deny is detected early via the source state —
     * so this only catches a portal that hangs without ever ending the source.
     */
    private static final long WAYLAND_PORTAL_TIMEOUT_MS = 120_000;

    private final PeerConnectionFactory factory;
    private final RTCPeerConnection publisherPc;
    private final LiveKitSignalingClient livekitSignaling;

    private VideoDesktopSource desktopSource;
    private VideoTrack videoTrack;
    private RTCRtpSender sender;
    /** Quality preset of the share currently being sent; set before any encoding params are applied. */
    private volatile ScreenShareQuality quality;
    /** Codec requested for the share being started (already resolved, never AUTO). */
    private volatile VideoCodecChoice codec = VideoCodecChoice.VP8;
    /** Codec the current {@link #sender} was published with; LiveKit can't change it in place. */
    private VideoCodecChoice senderCodec;
    /** Bumped for every newly published sender so each AddTrackRequest carries a fresh cid. */
    private int publishSeq;

    private Runnable onSharingStateChanged;
    private final AtomicBoolean sharing = new AtomicBoolean(false);

    // ── Constructor ────────────────────────────────────────────────────────────

    public ScreenShareClient(PeerConnectionFactory factory,
                             RTCPeerConnection publisherPc,
                             LiveKitSignalingClient livekitSignaling) {
        this.factory = factory;
        this.publisherPc = publisherPc;
        this.livekitSignaling = livekitSignaling;
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * @param codec resolved codec for this share ({@link VideoCodecChoice#resolve()}, never AUTO)
     */
    public void startSharing(long sourceId, boolean isWindow, ScreenShareQuality quality, VideoCodecChoice codec) {
        this.quality = quality;
        this.codec = codec == null || codec == VideoCodecChoice.AUTO ? VideoCodecChoice.AUTO.resolve() : codec;
        VideoTrack oldTrack = null;
        boolean isReuse = sharing.getAndSet(true);
        if (isReuse) {
            oldTrack = this.videoTrack;
            this.videoTrack = null;
            releaseDesktopResources();
        }

        log.info("[ScreenShare] Starting: sourceId={} isWindow={} quality={}x{}@{} codec={}",
                sourceId, isWindow, quality.maxW, quality.maxH, quality.fps, this.codec.label());

        desktopSource = new VideoDesktopSource();
        desktopSource.setFrameRate(quality.fps);
        desktopSource.setMaxFrameSize(quality.maxW, quality.maxH);
        desktopSource.setSourceId(sourceId, isWindow);
        desktopSource.start();

        // A new sender is needed for the first share and whenever the codec changes — LiveKit
        // can't switch the codec of an already published track, so it gets re-published under a
        // fresh cid instead of replaceTrack()'d.
        final boolean newSender = (sender == null) || senderCodec != this.codec;
        final String cid = newSender ? VIDEO_TRACK_CID_PREFIX + (publishSeq++) : VIDEO_TRACK_CID_PREFIX + "-reuse";
        videoTrack = factory.createVideoTrack(cid, desktopSource);
        videoTrack.setEnabled(true);

        final VideoTrack trackToPublish = videoTrack;
        final VideoTrack trackToRetire = oldTrack;   // disposed after the sender moves off it
        // Whether a previous share was live on a sender (decides what an aborted attempt winds down).
        final boolean firstShare = (sender == null);

        if (WAYLAND) {
            // libwebrtc's DesktopCapturer::IsRunningUnderWayland() requires BOTH
            // XDG_SESSION_TYPE to start with "wayland" AND WAYLAND_DISPLAY to be set
            // before it routes capture through the PipeWire/xdg-desktop-portal backend.
            // Our own WAYLAND flag is an OR of the two, so in a half-set environment
            // (IDE, launcher, systemd, .desktop file) komm shows the portal flow while
            // the native layer silently creates the X11 capturer — and the portal
            // picker dialog never opens.
            String sessionType = System.getenv("XDG_SESSION_TYPE");
            String waylandDisplay = System.getenv("WAYLAND_DISPLAY");
            log.info("[ScreenShare] Wayland env: XDG_SESSION_TYPE={} WAYLAND_DISPLAY={}",
                    sessionType, waylandDisplay);
            if (sessionType == null || !sessionType.startsWith("wayland") || waylandDisplay == null) {
                log.warn("[ScreenShare] Wayland env incomplete — libwebrtc will fall back to "
                        + "X11 capture and the xdg-desktop-portal picker will NOT open");
            }
            // Wait for the portal to deliver the first frame before publishing, so
            // LiveKit never negotiates an empty video track. The share announcement
            // (USER_SCREEN_SHARE broadcast via notifySharingStateChanged) is deferred
            // with it: clicking "Start Sharing" only opens the portal picker — nobody
            // should see "user is sharing" until a source was actually picked and the
            // first frame exists. Cancelling the picker aborts the attempt silently.
            log.info("[ScreenShare] Wayland: deferring publish and announcement until first captured frame");
            deferPublishUntilFirstFrame(trackToPublish, firstShare, newSender, cid, trackToRetire);
            // On reuse the share is already announced — refresh the state right away
            // so a changed audio flag still propagates while the picker is open.
            if (isReuse) notifySharingStateChanged();
        } else {
            publishTrack(trackToPublish, newSender, cid, trackToRetire);
            notifySharingStateChanged();
        }
        log.info("[ScreenShare] Desktop source started");
    }

    /**
     * Publishes {@link #videoTrack} to the peer connection and renegotiates.
     * Split out of {@link #startSharing} so the Wayland path can call it later,
     * once the portal has produced the first frame. Runs on the WebRTC thread.
     */
    private void publishTrack(VideoTrack track, boolean newSender, String cid, VideoTrack trackToRetire) {
        // A stale invocation (share already stopped, or the track was swapped out
        // by a newer startSharing) must not publish.
        if (!sharing.get() || track != this.videoTrack) {
            log.debug("[ScreenShare] publishTrack skipped — share no longer current");
            return;
        }

        if (newSender) {
            if (sender != null) {
                // Codec changed since the last share: unpublish the old sender (its m-line goes
                // inactive in the same offer that adds the new one).
                log.info("[ScreenShare] Codec changed {} -> {} — re-publishing the track",
                        senderCodec == null ? "?" : senderCodec.label(), codec.label());
                try { publisherPc.removeTrack(sender); } catch (Throwable ignored) {}
                try { sender.dispose(); } catch (Throwable ignored) {}
                sender = null;
            }
            // addTrack creates the sender. Codec preference and bitrate settings are applied
            // before the offer goes out, so the first negotiation already uses them.
            sender = publisherPc.addTrack(track, List.of("screen-stream"));
            senderCodec = codec;
            applyCodecPreferences();
            applyBitrateSettings();

            // FIX (ordering): send the offer first, THEN the AddTrackRequest
            // from inside the setLocalDescription callback. LiveKit processes
            // the renegotiation most reliably in that order.
            triggerRenegotiationWithAddTrack(cid);
        } else {
            // Reuse: replace the track on the existing sender and restore
            // the transceiver direction. No new AddTrackRequest needed.
            sender.replaceTrack(track);
            applyBitrateSettings(); // re-apply: replaceTrack may reset encoding params
            withTransceiverOf(sender, t -> t.setDirection(RTCRtpTransceiverDirection.SEND_ONLY));
            triggerRenegotiation();
        }
        logEncoderInUseLater();

        // The sender has moved to the new track — the previous one can go.
        if (trackToRetire != null) {
            try { trackToRetire.dispose(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Attaches a one-shot {@link VideoTrackSink} that publishes (and announces) on
     * the first frame. A watchdog polls the desktop source state: when the user
     * cancels or denies the portal picker, the native capturer ends the source and
     * the pending share is aborted — it was never announced, so to everyone else
     * nothing happened. The picker itself has no deadline; a generous hard timeout
     * only catches a portal that hangs without ending the source.
     */
    private void deferPublishUntilFirstFrame(VideoTrack track, boolean firstShare, boolean newSender,
                                             String cid, VideoTrack trackToRetire) {
        AtomicBoolean done = new AtomicBoolean(false);
        // Holder so the sink can remove itself from inside its own callback.
        VideoTrackSink[] sinkRef = new VideoTrackSink[1];

        // Always runs off the frame-callback thread: removeSink() re-entering the
        // native layer from inside onVideoFrame could deadlock, and addTrack/offer
        // shouldn't block frame delivery.
        Runnable publishOnce = () -> {
            if (done.getAndSet(true)) return;
            Thread.ofVirtual().name("screenshare-publish").start(() -> {
                try {
                    if (sinkRef[0] != null) track.removeSink(sinkRef[0]);
                } catch (Throwable ignored) {
                }
                publishTrack(track, newSender, cid, trackToRetire);
                // Only now is the user really sharing — announce it. (On reuse this
                // merely refreshes an already-announced state.)
                notifySharingStateChanged();
            });
        };

        VideoTrackSink sink = frame -> {
            // webrtc-java hands each sink its own AddRef'd native frame copy —
            // it must be released or it leaks native memory.
            frame.release();
            if (!done.get()) {
                log.info("[ScreenShare] Wayland: first frame received — publishing track");
            }
            publishOnce.run();
        };
        sinkRef[0] = sink;
        track.addSink(sink);

        Thread.ofVirtual().name("screenshare-portal-watchdog").start(() -> {
            long deadline = System.currentTimeMillis() + WAYLAND_PORTAL_TIMEOUT_MS;
            while (!done.get() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (track != videoTrack) return; // superseded by a newer share attempt
                if (desktopSourceEnded()) {
                    if (done.getAndSet(true)) return;
                    log.info("[ScreenShare] Wayland: portal cancelled or denied — aborting share");
                    abortPendingShare(track, sinkRef[0], firstShare);
                    return;
                }
            }
            if (!done.getAndSet(true)) {
                log.warn("[ScreenShare] Wayland: no frame within {} ms — aborting share",
                        WAYLAND_PORTAL_TIMEOUT_MS);
                abortPendingShare(track, sinkRef[0], firstShare);
            }
        });
    }

    /** True when the desktop source ended natively (portal picker cancelled/denied). */
    private boolean desktopSourceEnded() {
        VideoDesktopSource src = desktopSource;
        if (src == null) return false;
        try {
            return src.getState() == MediaSource.State.ENDED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Tears down a Wayland share attempt whose portal dialog was cancelled, denied,
     * or never produced a frame. On a first share nothing was published or announced,
     * so this is invisible to everyone else. On reuse the previous share was already
     * announced and its source torn down, so the sender is deactivated like a normal
     * stop and the state change is broadcast.
     */
    private void abortPendingShare(VideoTrack track, VideoTrackSink sink, boolean firstShare) {
        if (track != this.videoTrack) return; // superseded by a newer share attempt
        sharing.set(false);

        try {
            if (sink != null) track.removeSink(sink);
        } catch (Throwable ignored) {
        }

        this.videoTrack = null;
        releaseDesktopResources();
        try {
            // Never handed to the sender for this attempt — safe to dispose here.
            track.dispose();
        } catch (Throwable ignored) {
        }

        if (!firstShare && sender != null) {
            // A previous share was live on this sender; wind it down like stopSharing().
            // (The retired track itself is disposed in dispose(), same as a normal stop.)
            withTransceiverOf(sender, t -> t.setDirection(RTCRtpTransceiverDirection.INACTIVE));
            triggerRenegotiation();
        }

        // For a first share this broadcasts sharing=false that nobody ever saw as
        // true — harmless, and it resets any local UI. For a reuse it takes the
        // stale share down for everyone.
        notifySharingStateChanged();
    }

    public void stopSharing() {
        if (!sharing.getAndSet(false)) return;
        log.info("[ScreenShare] Stopping");

        if (sender != null) {
            withTransceiverOf(sender, t -> t.setDirection(RTCRtpTransceiverDirection.INACTIVE));
        }

        releaseDesktopResources();
        triggerRenegotiation();
        notifySharingStateChanged();
    }

    public boolean isSharing() {
        return sharing.get();
    }

    public void dispose() {
        sharing.set(false);
        releaseDesktopResources();

        if (sender != null && publisherPc != null) {
            try {
                publisherPc.removeTrack(sender);
            } catch (Throwable ignored) {
            }
            try {
                sender.dispose(); // addTrack() senders are not owned by the PC
            } catch (Throwable ignored) {
            }
            sender = null;
            senderCodec = null;
        }
        if (videoTrack != null) {
            videoTrack.dispose();
            videoTrack = null;
        }
        log.info("[ScreenShare] Disposed");
    }

    public void setOnSharingStateChanged(Runnable listener) {
        this.onSharingStateChanged = listener;
    }

    // ── Bitrate constraints ────────────────────────────────────────────────────

    /**
     * Applies screen-share bitrate constraints to the current sender.
     *
     * <p>Must always follow the get→modify→set pattern. The transaction ID
     * embedded in the object returned by {@code getParameters()} must be
     * preserved or {@code setParameters()} will silently fail.</p>
     *
     * <p>Called immediately after {@code addTrack()} (first stream) and after
     * {@code replaceTrack()} (subsequent streams), before the offer is sent.
     * The per-encoding ceiling/fps go through the sender; the estimator restart
     * goes through the peer connection (it is shared by all senders of the
     * publisher PC, which is fine — the other senders are a few tens of kbps of audio).</p>
     */
    private void applyBitrateSettings() {
        if (sender == null) return;
        ScreenShareQuality q = this.quality;
        int start = startBitrateBps(q);
        int max = maxBitrateBps(q);
        try {
            RTCRtpSendParameters params = sender.getParameters();
            if (params.encodings == null || params.encodings.isEmpty()) {
                log.warn("[ScreenShare] No encodings on sender — bitrate constraints not applied.");
            } else {
                for (RTCRtpEncodingParameters enc : params.encodings) {
                    enc.minBitrate = null; // never pin an encoder floor — see class doc
                    enc.maxBitrate = max;
                    enc.maxFramerate = (double) q.fps;
                    // Leave scaleResolutionDownBy at its default (1.0 = full resolution).
                }
                sender.setParameters(params);
            }
        } catch (Exception e) {
            log.error("[ScreenShare] Failed to apply sender bitrate constraints: {}", e.getMessage(), e);
        }
        try {
            publisherPc.setBitrate(null, start, max);
            log.info("[ScreenShare] Bitrate applied: estimator start={}kbps max={}kbps fps={}",
                    start / 1000, max / 1000, q.fps);
        } catch (Exception e) {
            log.error("[ScreenShare] Failed to restart bandwidth estimate: {}", e.getMessage(), e);
        }
    }

    /**
     * Target encoder bitrate for a quality preset. Values are tuned for screen
     * content (mostly-static frames with bursts of motion); presets above 30 fps
     * get 50% more budget.
     */
    private static int targetBitrateBps(ScreenShareQuality q) {
        int base = switch (q.maxH) {
            case 360  -> 1_200_000;
            case 480  -> 1_600_000;
            case 720  -> 2_800_000;
            case 1080 -> 4_500_000;
            default   -> 7_000_000; // 1440p and above
        };
        return q.fps > 30 ? (int) (base * 1.5) : base;
    }

    /**
     * Where the bandwidth estimator restarts: keeps it from starting at its
     * ~300 kbps default (which causes ~10 s of warm-up choppiness). It is only
     * an estimate, so on a slower link it simply backs off — unlike an encoder
     * floor, which would force the encoder above what the path can carry and
     * make the SFU's congestion controller periodically pause the stream.
     */
    private static int startBitrateBps(ScreenShareQuality q) {
        return targetBitrateBps(q) / 2;
    }

    /**
     * Bitrate ceiling: 2× the target gives the encoder headroom for fast
     * motion / scrolling / video playback inside the shared screen, capped at
     * {@link #MAX_BITRATE_CAP_BPS}. The BWE still governs the actual rate, so
     * on a constrained link the encoder simply won't reach this ceiling.
     */
    private static int maxBitrateBps(ScreenShareQuality q) {
        return Math.min(targetBitrateBps(q) * 2, MAX_BITRATE_CAP_BPS);
    }

    // ── Renegotiation ──────────────────────────────────────────────────────────

    /**
     * Creates an offer, sets it as the local description, then — from inside
     * the success callback — sends the offer <em>and</em> the AddTrackRequest
     * to LiveKit in that order.
     *
     * <p>This is the correct ordering for adding a new track: LiveKit must
     * receive and process the offer before it can acknowledge the track via
     * TrackPublished. Sending AddTrackRequest first (as the previous code did)
     * races against the offer and causes LiveKit to delay or drop the track,
     * contributing to the startup lag.</p>
     */
    private void triggerRenegotiationWithAddTrack(String cid) {
        final VideoCodecChoice publishedCodec = codec;
        if (publisherPc == null) return;

        publisherPc.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription offer) {
                publisherPc.setLocalDescription(offer, new SetSessionDescriptionObserver() {
                    @Override
                    public void onSuccess() {
                        log.debug("[ScreenShare] Sending offer + AddTrackRequest");
                        livekitSignaling.sendPublisherOffer(offer);
                        // Send AddTrackRequest AFTER the offer is committed locally
                        // and sent to LiveKit — this is the ordering LiveKit expects.
                        // Dimensions + a layer with the expected bitrate are required
                        // for the SFU's stream allocator to budget this track properly;
                        // without them it treats the bitrate as unknown and is prone to
                        // pausing the stream on any bandwidth-estimate dip.
                        // Declare the TARGET bitrate, not the burst ceiling: the SFU
                        // budgets subscriber bandwidth against this number, and an
                        // inflated value makes the allocator consider the stream
                        // "deficient" (and pause-eligible) whenever the estimate
                        // dips below it.
                        ScreenShareQuality q = quality;
                        livekitSignaling.sendAddVideoTrackRequest(
                                cid,
                                "screen_share",
                                LivekitModels.TrackSource.SCREEN_SHARE,
                                q.maxW, q.maxH, targetBitrateBps(q),
                                publishedCodec.getLivekitName()
                        );
                    }

                    @Override
                    public void onFailure(String e) {
                        log.error("[ScreenShare] setLocalDescription failed: {}", e);
                    }
                });
            }

            @Override
            public void onFailure(String e) {
                log.error("[ScreenShare] createOffer failed: {}", e);
            }
        });
    }

    /**
     * Plain renegotiation with no AddTrackRequest — used for stopSharing()
     * and for reuse (replaceTrack) where LiveKit already knows the track CID.
     */
    private void triggerRenegotiation() {
        if (publisherPc == null) return;

        publisherPc.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription offer) {
                publisherPc.setLocalDescription(offer, new SetSessionDescriptionObserver() {
                    @Override
                    public void onSuccess() {
                        log.debug("[ScreenShare] Sending renegotiation offer");
                        livekitSignaling.sendPublisherOffer(offer);
                    }

                    @Override
                    public void onFailure(String e) {
                        log.error("[ScreenShare] setLocalDescription failed: {}", e);
                    }
                });
            }

            @Override
            public void onFailure(String e) {
                log.error("[ScreenShare] createOffer failed: {}", e);
            }
        });
    }

    // ── Codec selection ────────────────────────────────────────────────────────

    /**
     * Puts the chosen codec first in the screen-share transceiver's codec list (the rest keep
     * their order, so RTX/RED/FEC and a fallback codec still negotiate). The factory's encoder
     * for that codec is GPU-first with a software fallback of the same codec, see
     * {@link VideoCodecChoice}.
     */
    private void applyCodecPreferences() {
        VideoCodecChoice c = codec;
        if (sender == null || c == null || c.getSdpName() == null) return;
        try {
            List<RTCRtpCodecCapability> all = factory.getRtpSenderCapabilities(MediaType.VIDEO).getCodecs();
            List<RTCRtpCodecCapability> ordered = new ArrayList<>(all.size());
            for (RTCRtpCodecCapability cap : all) {
                if (c.getSdpName().equalsIgnoreCase(cap.getName())) ordered.add(cap);
            }
            if (ordered.isEmpty()) {
                log.warn("[ScreenShare] {} is not supported by this build — keeping the default codec order",
                        c.getDisplayName());
                return;
            }
            for (RTCRtpCodecCapability cap : all) {
                if (!c.getSdpName().equalsIgnoreCase(cap.getName())) ordered.add(cap);
            }
            boolean applied = withTransceiverOf(sender, t -> t.setCodecPreferences(ordered));
            if (!applied) log.warn("[ScreenShare] Screen-share transceiver not found — codec preference not applied");
        } catch (Throwable t) {
            log.warn("[ScreenShare] Failed to apply codec preference {}: {}", c.getDisplayName(), t.toString());
        }
    }

    /**
     * Logs the encoder implementation the share actually ended up with (e.g. "NVENC (…)",
     * "libvpx", or a software fallback) a few seconds after publishing — once frames flow.
     */
    private void logEncoderInUseLater() {
        final RTCRtpSender s = sender;
        if (s == null) return;
        Thread.ofVirtual().name("screenshare-encoder-log").start(() -> {
            try {
                Thread.sleep(5000);
                if (!sharing.get() || s != sender) return;
                publisherPc.getStats(s, report -> report.getStats().values().stream()
                        .filter(st -> st.getType() == RTCStatsType.OUTBOUND_RTP)
                        .findFirst()
                        .ifPresent(st -> {
                            var a = st.getAttributes();
                            log.info("[ScreenShare] Encoder in use: {} (powerEfficient={}) {}x{} @{} fps, requested {}",
                                    a.get("encoderImplementation"), a.get("powerEfficientEncoder"),
                                    a.get("frameWidth"), a.get("frameHeight"), a.get("framesPerSecond"),
                                    codec.label());
                        }));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                log.debug("[ScreenShare] Encoder stats unavailable: {}", t.toString());
            }
        });
    }

    /**
     * Runs {@code action} on the transceiver that owns {@code target}. webrtc-java hands out a new
     * Java wrapper on every {@code getTransceivers()} / {@code getSender()} call, so identity
     * ({@code ==}) never matches — {@code equals()} compares the native sender. The temporary
     * wrappers are disposed (that only drops their own native reference).
     *
     * @return true if the transceiver was found
     */
    private boolean withTransceiverOf(RTCRtpSender target, java.util.function.Consumer<RTCRtpTransceiver> action) {
        if (target == null || publisherPc == null) return false;
        boolean found = false;
        for (RTCRtpTransceiver t : publisherPc.getTransceivers()) {
            RTCRtpSender s = null;
            try {
                s = t.getSender();
                if (!found && target.equals(s)) {
                    action.accept(t);
                    found = true;
                }
            } finally {
                if (s != null && s != target) {
                    try { s.dispose(); } catch (Throwable ignored) {}
                }
                try { t.dispose(); } catch (Throwable ignored) {}
            }
        }
        return found;
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    private void releaseDesktopResources() {
        if (desktopSource != null) {
            try {
                desktopSource.stop();
            } catch (Throwable ignored) {
            }
            try {
                desktopSource.dispose();
            } catch (Throwable ignored) {
            }
            desktopSource = null;
        }
        // Do NOT dispose videoTrack here — the sender still holds a reference.
        // Disposal happens in dispose() after removeTrack().
    }

    private void notifySharingStateChanged() {
        Runnable cb = onSharingStateChanged;
        if (cb != null) cb.run();
    }
}