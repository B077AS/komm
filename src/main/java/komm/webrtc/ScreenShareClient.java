package komm.webrtc;

import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.video.VideoDesktopSource;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import komm.ui.screenshare.ScreenShareQuality;
import livekit.LivekitModels;
import lombok.extern.slf4j.Slf4j;

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
 * {@code addTrack()} / {@code replaceTrack()} so the encoder never starts
 * below a usable floor.</p>
 *
 * <p>Bitrate values scale with the selected quality preset (see
 * {@link #targetBitrateBps}): the floor is half the preset's target — enough to
 * skip the BWE warm-up without forcing the encoder above what the link can
 * actually carry — and the ceiling is twice the target, capped at
 * {@link #MAX_BITRATE_CAP_BPS}. A blanket floor that exceeds real path
 * capacity makes LiveKit's congestion controller periodically pause the
 * stream, which shows up as rhythmic stuttering on the viewer's side.</p>
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

    private static final String VIDEO_TRACK_CID = "screen0";

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

    /** Fallback: publish anyway if no frame arrives within this window (portal denied/slow). */
    private static final long WAYLAND_FIRST_FRAME_TIMEOUT_MS = 10_000;

    private final PeerConnectionFactory factory;
    private final RTCPeerConnection publisherPc;
    private final LiveKitSignalingClient livekitSignaling;

    private VideoDesktopSource desktopSource;
    private VideoTrack videoTrack;
    private RTCRtpSender sender;
    /** Quality preset of the share currently being sent; set before any encoding params are applied. */
    private volatile ScreenShareQuality quality;

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

    public void startSharing(long sourceId, boolean isWindow, ScreenShareQuality quality) {
        this.quality = quality;
        VideoTrack oldTrack = null;
        boolean isReuse = sharing.getAndSet(true);
        if (isReuse) {
            oldTrack = this.videoTrack;
            this.videoTrack = null;
            releaseDesktopResources();
        }

        log.info("[ScreenShare] Starting: sourceId={} isWindow={} quality={}",
                sourceId, isWindow, quality);

        desktopSource = new VideoDesktopSource();
        desktopSource.setFrameRate(quality.fps);
        desktopSource.setMaxFrameSize(quality.maxW, quality.maxH);
        desktopSource.setSourceId(sourceId, isWindow);
        desktopSource.start();

        videoTrack = factory.createVideoTrack(VIDEO_TRACK_CID, desktopSource);
        videoTrack.setEnabled(true);

        final VideoTrack trackToPublish = videoTrack;
        final VideoTrack trackToRetire = oldTrack;   // disposed after the sender moves off it
        final boolean firstShare = (sender == null);

        if (WAYLAND) {
            // Wait for the portal to deliver the first frame before publishing, so
            // LiveKit never negotiates an empty video track. A one-shot sink fires
            // publish() on the first frame; a timer publishes anyway if the user is
            // slow or denies the dialog (behaviour then no worse than immediate publish).
            log.info("[ScreenShare] Wayland: deferring publish until first captured frame");
            deferPublishUntilFirstFrame(trackToPublish, firstShare, trackToRetire);
        } else {
            publishTrack(trackToPublish, firstShare, trackToRetire);
        }

        notifySharingStateChanged();
        log.info("[ScreenShare] Desktop source started");
    }

    /**
     * Publishes {@link #videoTrack} to the peer connection and renegotiates.
     * Split out of {@link #startSharing} so the Wayland path can call it later,
     * once the portal has produced the first frame. Runs on the WebRTC thread.
     */
    private void publishTrack(VideoTrack track, boolean firstShare, VideoTrack trackToRetire) {
        // A stale invocation (share already stopped, or the track was swapped out
        // by a newer startSharing) must not publish.
        if (!sharing.get() || track != this.videoTrack) {
            log.debug("[ScreenShare] publishTrack skipped — share no longer current");
            return;
        }

        if (firstShare) {
            // First time: addTrack creates the sender. We then immediately
            // apply bitrate settings before the offer goes out, so the first
            // negotiated encoding already has the correct min/max.
            sender = publisherPc.addTrack(track, List.of("screen-stream"));
            applyBitrateSettings();

            // FIX (ordering): send the offer first, THEN the AddTrackRequest
            // from inside the setLocalDescription callback. LiveKit processes
            // the renegotiation most reliably in that order.
            triggerRenegotiationWithAddTrack();
        } else {
            // Reuse: replace the track on the existing sender and restore
            // the transceiver direction. No new AddTrackRequest needed.
            sender.replaceTrack(track);
            applyBitrateSettings(); // re-apply: replaceTrack may reset encoding params
            for (RTCRtpTransceiver t : publisherPc.getTransceivers()) {
                if (t.getSender() == sender) {
                    t.setDirection(RTCRtpTransceiverDirection.SEND_ONLY);
                    break;
                }
            }
            triggerRenegotiation();
        }

        // The sender has moved to the new track — the previous one can go.
        if (trackToRetire != null) {
            try { trackToRetire.dispose(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Attaches a one-shot {@link VideoTrackSink} that publishes on the first frame,
     * with a timeout that publishes anyway if none arrives. Both paths are guarded
     * so the track is published exactly once and the sink is always detached.
     */
    private void deferPublishUntilFirstFrame(VideoTrack track, boolean firstShare, VideoTrack trackToRetire) {
        AtomicBoolean published = new AtomicBoolean(false);
        // Holder so the sink can remove itself from inside its own callback.
        VideoTrackSink[] sinkRef = new VideoTrackSink[1];

        // Always runs off the frame-callback thread: removeSink() re-entering the
        // native layer from inside onVideoFrame could deadlock, and addTrack/offer
        // shouldn't block frame delivery.
        Runnable publishOnce = () -> {
            if (published.getAndSet(true)) return;
            Thread.ofVirtual().name("screenshare-publish").start(() -> {
                try {
                    if (sinkRef[0] != null) track.removeSink(sinkRef[0]);
                } catch (Throwable ignored) {
                }
                publishTrack(track, firstShare, trackToRetire);
            });
        };

        VideoTrackSink sink = frame -> {
            // webrtc-java hands each sink its own AddRef'd native frame copy —
            // it must be released or it leaks native memory.
            frame.release();
            if (!published.get()) {
                log.info("[ScreenShare] Wayland: first frame received — publishing track");
            }
            publishOnce.run();
        };
        sinkRef[0] = sink;
        track.addSink(sink);

        Thread.ofVirtual().name("screenshare-portal-timeout").start(() -> {
            try {
                Thread.sleep(WAYLAND_FIRST_FRAME_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!published.get()) {
                log.warn("[ScreenShare] Wayland: no frame within {} ms — publishing anyway",
                        WAYLAND_FIRST_FRAME_TIMEOUT_MS);
                publishOnce.run();
            }
        });
    }

    public void stopSharing() {
        if (!sharing.getAndSet(false)) return;
        log.info("[ScreenShare] Stopping");

        if (sender != null) {
            for (RTCRtpTransceiver t : publisherPc.getTransceivers()) {
                if (t.getSender() == sender) {
                    t.setDirection(RTCRtpTransceiverDirection.INACTIVE);
                    break;
                }
            }
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
            sender = null;
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
     * This ensures the very first negotiated encoding already has a high
     * bitrate floor, eliminating the ~10 s BWE ramp-up period.</p>
     */
    private void applyBitrateSettings() {
        if (sender == null) return;
        try {
            ScreenShareQuality q = this.quality;
            int min = minBitrateBps(q);
            int max = maxBitrateBps(q);
            RTCRtpSendParameters params = sender.getParameters();
            if (params.encodings == null || params.encodings.isEmpty()) {
                log.warn("[ScreenShare] No encodings on sender — bitrate constraints not applied.");
                return;
            }
            for (RTCRtpEncodingParameters enc : params.encodings) {
                enc.minBitrate = min;
                enc.maxBitrate = max;
                enc.maxFramerate = (double) q.fps;
                // Leave scaleResolutionDownBy at its default (1.0 = full resolution).
            }
            sender.setParameters(params);
            log.info("[ScreenShare] Bitrate constraints applied: min={}kbps max={}kbps fps={}",
                    min / 1000, max / 1000, q.fps);
        } catch (Exception e) {
            log.error("[ScreenShare] Failed to apply bitrate constraints: {}", e.getMessage(), e);
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
     * Bitrate floor: keeps the BWE from starting at its ~300 kbps default
     * (which causes ~10 s of warm-up choppiness) while staying low enough that
     * the encoder is never forced to overshoot what the link can carry —
     * a floor above the real path capacity causes the SFU's congestion
     * controller to periodically pause the stream.
     */
    private static int minBitrateBps(ScreenShareQuality q) {
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
    private void triggerRenegotiationWithAddTrack() {
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
                                VIDEO_TRACK_CID,
                                "screen_share",
                                LivekitModels.TrackSource.SCREEN_SHARE,
                                q.maxW, q.maxH, targetBitrateBps(q)
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