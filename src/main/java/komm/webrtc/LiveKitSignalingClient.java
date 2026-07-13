package komm.webrtc;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCSdpType;
import jakarta.websocket.*;
import komm.App;
import komm.api.json.GsonProvider;
import komm.ui.cards.ChannelCard;
import livekit.LivekitModels;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@Slf4j
public class LiveKitSignalingClient extends Endpoint {

    private final Gson gson = GsonProvider.get();

    @Setter
    private volatile Consumer<RTCSessionDescription> onOffer;
    @Setter
    private volatile Consumer<RTCSessionDescription> onAnswer;
    @Setter
    private volatile BiConsumer<RTCIceCandidate, Boolean> onRemoteIce;
    @Setter
    private volatile Runnable onJoined;
    @Setter
    private volatile Consumer<String> onRefreshToken;
    @Setter
    private volatile Consumer<String> onTrackPublished;
    @Setter
    private volatile Consumer<String> onError;

    private volatile Session wsSession;
    private volatile RTCSessionDescription pendingPublisherOffer;

    private final Map<String, String> sidToIdentity         = new ConcurrentHashMap<>();
    // LiveKit track SID → track name as set in AddTrackRequest (e.g. "microphone", "soundboard")
    private final Map<String, String> trackSidToName        = new ConcurrentHashMap<>();
    // LiveKit track SID → participant identity (userId) that published it
    private final Map<String, String> trackSidToParticipant = new ConcurrentHashMap<>();

    public void liveKitConnect(String livekitUrl, String token) {
        String url = "ws://" + livekitUrl + "/rtc?access_token=" + token
                + "&sdk=javaclient&version=1.0&protocol=9&auto_subscribe=1";
        try {
            ClientEndpointConfig config = ClientEndpointConfig.Builder.create().build();
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            container.setDefaultMaxTextMessageBufferSize(128 * 1024);
            container.connectToServer(this, config, URI.create(url));
            log.info("[LiveKit] Connecting to {}", livekitUrl);
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect to LiveKit signal: " + e.getMessage(), e);
        }
    }

    public void disconnect() {
        Session s = wsSession;
        if (s != null && s.isOpen()) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        wsSession = null;
        sidToIdentity.clear();
        trackSidToName.clear();
        trackSidToParticipant.clear();
    }

    /** Returns the track name registered in AddTrackRequest for a LiveKit track SID, or null. */
    public String getTrackName(String trackSid) {
        return trackSidToName.get(trackSid);
    }

    /** Returns the participant identity (userId string) that published this track SID, or null. */
    public String getTrackParticipantId(String trackSid) {
        return trackSidToParticipant.get(trackSid);
    }

    public boolean isConnected() {
        return wsSession != null && wsSession.isOpen();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OUTBOUND
    // ─────────────────────────────────────────────────────────────────────────

    public void sendAddTrackRequest(String cid, String name,
                                    LivekitModels.TrackType type, LivekitModels.TrackSource source) {
        livekit.LivekitRtc.SignalRequest req = livekit.LivekitRtc.SignalRequest.newBuilder()
                .setAddTrack(livekit.LivekitRtc.AddTrackRequest.newBuilder()
                        .setCid(cid)
                        .setName(name)
                        .setType(type)
                        .setSource(source)
                        .build())
                .build();
        sendBinary(req);
        log.debug("[LiveKit] → AddTrackRequest sent cid={}", cid);
    }

    /**
     * AddTrackRequest for a video track. Unlike the audio variant, video must
     * declare its dimensions and at least one {@link LivekitModels.VideoLayer}
     * (with the expected bitrate) — the SFU's stream allocator uses the layer
     * bitrate to budget subscriber bandwidth. Publishing a video track without
     * layers leaves the SFU guessing and makes it far more aggressive about
     * pausing the stream under any perceived congestion.
     */
    public void sendAddVideoTrackRequest(String cid, String name, LivekitModels.TrackSource source,
                                         int width, int height, int maxBitrateBps) {
        livekit.LivekitRtc.SignalRequest req = livekit.LivekitRtc.SignalRequest.newBuilder()
                .setAddTrack(livekit.LivekitRtc.AddTrackRequest.newBuilder()
                        .setCid(cid)
                        .setName(name)
                        .setType(LivekitModels.TrackType.VIDEO)
                        .setSource(source)
                        .setWidth(width)
                        .setHeight(height)
                        .addLayers(LivekitModels.VideoLayer.newBuilder()
                                .setQuality(LivekitModels.VideoQuality.HIGH)
                                .setWidth(width)
                                .setHeight(height)
                                .setBitrate(maxBitrateBps))
                        .build())
                .build();
        sendBinary(req);
        log.debug("[LiveKit] → AddTrackRequest (video) sent cid={} {}x{} @{}kbps",
                cid, width, height, maxBitrateBps / 1000);
    }

    public void sendPublisherOffer(RTCSessionDescription offer) {
        if (!isConnected()) {
            pendingPublisherOffer = offer;
            return;
        }
        sendOffer(offer);
    }

    public void sendSubscriberAnswer(RTCSessionDescription answer) {
        livekit.LivekitRtc.SignalRequest req = livekit.LivekitRtc.SignalRequest.newBuilder()
                .setAnswer(livekit.LivekitRtc.SessionDescription.newBuilder()
                        .setType("answer")
                        .setSdp(answer.sdp)
                        .build())
                .build();
        sendBinary(req);
        //log.debug("[LiveKit] → answer sent");
    }

    public void sendIceCandidate(RTCIceCandidate candidate, boolean isPublisher) {
        JsonObject cand = new JsonObject();
        cand.addProperty("candidate", candidate.sdp);
        cand.addProperty("sdpMid", candidate.sdpMid);
        cand.addProperty("sdpMLineIndex", candidate.sdpMLineIndex);

        livekit.LivekitRtc.SignalRequest req = livekit.LivekitRtc.SignalRequest.newBuilder()
                .setTrickle(livekit.LivekitRtc.TrickleRequest.newBuilder()
                        .setCandidateInit(gson.toJson(cand))
                        .setTarget(isPublisher
                                ? livekit.LivekitRtc.SignalTarget.PUBLISHER
                                : livekit.LivekitRtc.SignalTarget.SUBSCRIBER)
                        .build())
                .build();
        sendBinary(req);
    }

    private void sendOffer(RTCSessionDescription offer) {
        livekit.LivekitRtc.SignalRequest req = livekit.LivekitRtc.SignalRequest.newBuilder()
                .setOffer(livekit.LivekitRtc.SessionDescription.newBuilder()
                        .setType("offer")
                        .setSdp(offer.sdp)
                        .build())
                .build();
        sendBinary(req);
       // log.debug("[LiveKit] → offer sent");
    }

    private void sendBinary(com.google.protobuf.MessageLite msg) {
        Session s = wsSession;
        if (s == null || !s.isOpen()) {
            log.warn("[LiveKit] Cannot send — not connected");
            return;
        }
        try {
            s.getBasicRemote().sendBinary(ByteBuffer.wrap(msg.toByteArray()));
        } catch (IOException e) {
            log.warn("[LiveKit] Send failed: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JSR-356 CALLBACKS
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onOpen(Session session, EndpointConfig config) {
        this.wsSession = session;

        session.addMessageHandler(ByteBuffer.class, data -> {
            try {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                livekit.LivekitRtc.SignalResponse response =
                        livekit.LivekitRtc.SignalResponse.parseFrom(bytes);
                dispatchProto(response);
            } catch (Throwable e) {
                // Must catch Throwable, not just Exception: NoClassDefFoundError (an Error)
                // escaping here causes Tyrus to treat the message handler as fatally broken
                // and close the WebSocket session, dropping the signaling connection even
                // though the WebRTC peer connection is still healthy.
                log.warn("[LiveKit] Failed to parse binary frame: {}", e.getMessage());
            }
        });
    }

    private void dispatchProto(livekit.LivekitRtc.SignalResponse msg) {
        switch (msg.getMessageCase()) {

            case JOIN -> {
                livekit.LivekitRtc.JoinResponse join = msg.getJoin();
                log.info("[LiveKit] ← join: room={} participant={}",
                        join.getRoom().getName(),
                        join.getParticipant().getIdentity());

                // Cache self
                sidToIdentity.put(join.getParticipant().getSid(), join.getParticipant().getIdentity());
                join.getParticipant().getTracksList().forEach(t -> trackSidToName.put(t.getSid(), t.getName()));
                // Cache existing participants
                join.getOtherParticipantsList().forEach(p -> {
                    sidToIdentity.put(p.getSid(), p.getIdentity());
                    p.getTracksList().forEach(t -> {
                        trackSidToName.put(t.getSid(), t.getName());
                        trackSidToParticipant.put(t.getSid(), p.getIdentity());
                    });
                });

                Runnable cb = onJoined;
                if (cb != null) cb.run();
                RTCSessionDescription queued = pendingPublisherOffer;
                if (queued != null) {
                    pendingPublisherOffer = null;
                    sendOffer(queued);
                }
            }

            case OFFER -> {
                livekit.LivekitRtc.SessionDescription sdp = msg.getOffer();
                log.debug("[LiveKit] ← offer (subscriber)");
                Consumer<RTCSessionDescription> cb = onOffer;
                if (cb != null) cb.accept(new RTCSessionDescription(RTCSdpType.OFFER, sdp.getSdp()));
            }

            case ANSWER -> {
                livekit.LivekitRtc.SessionDescription sdp = msg.getAnswer();
                //log.debug("[LiveKit] ← answer (publisher)");
                Consumer<RTCSessionDescription> cb = onAnswer;
                if (cb != null) cb.accept(new RTCSessionDescription(RTCSdpType.ANSWER, sdp.getSdp()));
            }

            case TRICKLE -> {
                livekit.LivekitRtc.TrickleRequest trickle = msg.getTrickle();
                boolean isPublisher = trickle.getTarget()
                        == livekit.LivekitRtc.SignalTarget.PUBLISHER;
                try {
                    JsonObject cand = JsonParser.parseString(
                            trickle.getCandidateInit()).getAsJsonObject();
                    RTCIceCandidate candidate = new RTCIceCandidate(
                            cand.has("sdpMid") ? cand.get("sdpMid").getAsString() : "",
                            cand.has("sdpMLineIndex") ? cand.get("sdpMLineIndex").getAsInt() : 0,
                            cand.has("candidate") ? cand.get("candidate").getAsString() : ""
                    );
                    log.debug("[LiveKit] ← trickle candidate ({}): {}",
                            isPublisher ? "publisher" : "subscriber", candidate.sdp);
                    BiConsumer<RTCIceCandidate, Boolean> cb = onRemoteIce;
                    if (cb != null) cb.accept(candidate, isPublisher);
                } catch (Exception e) {
                    log.warn("[LiveKit] Failed to parse trickle candidate: {}", e.getMessage());
                }
            }

            case SPEAKERS_CHANGED -> {
                // SpeakersChanged can fail to initialize on Linux when an audio device is
                // added/removed at runtime (classloader issue on the Grizzly thread).
                // Wrap separately so a NoClassDefFoundError here doesn't poison other cases.
                try {
                    livekit.LivekitRtc.SpeakersChanged speakers = msg.getSpeakersChanged();
                    List<LivekitModels.SpeakerInfo> speakerList = speakers.getSpeakersList();
                    var page = App.getCachedServerPage();
                    if (page == null) return;
                    Map<UUID, ChannelCard> boxes = page.getChannelSection().getChannelBoxes();

                    speakerList.forEach(s -> {
                        String identity = sidToIdentity.get(s.getSid());
                        if (identity == null) return;
                        try {
                            UUID userId = UUID.fromString(identity);
                            boxes.values().forEach(box -> box.setSpeaking(userId, s.getActive()));
                        } catch (IllegalArgumentException ignored) {}
                    });
                } catch (Throwable e) {
                    log.warn("[LiveKit] Skipped speakers_changed: {}", e.getMessage());
                }
            }

            case REFRESH_TOKEN -> {
                String newToken = msg.getRefreshToken();
                log.debug("[LiveKit] ← refresh_token received");
                Consumer<String> cb = onRefreshToken;
                if (cb != null) cb.accept(newToken);
            }

            case TRACK_PUBLISHED -> {
                livekit.LivekitRtc.TrackPublishedResponse published = msg.getTrackPublished();
                log.debug("[LiveKit] ← track published: cid={} sid={}",
                        published.getCid(), published.getTrack().getSid());
                Consumer<String> cb = onTrackPublished;
                if (cb != null) cb.accept(published.getCid());
            }

            case STREAM_STATE_UPDATE -> {
                // The SFU reports here when its stream allocator pauses/resumes a
                // track for us (congestion control). Logged at WARN because a PAUSED
                // state is exactly what viewers perceive as periodic stutter — if
                // these lines appear in the log during playback, the allocator is
                // the cause; if not, look at the sender/capture side instead.
                msg.getStreamStateUpdate().getStreamStatesList().forEach(s ->
                        log.warn("[LiveKit] ← stream state: track={} participant={} state={}",
                                s.getTrackSid(), s.getParticipantSid(), s.getState()));
            }

            case UPDATE -> {
                msg.getUpdate().getParticipantsList().forEach(p -> {
                    sidToIdentity.put(p.getSid(), p.getIdentity());
                    p.getTracksList().forEach(t -> {
                        trackSidToName.put(t.getSid(), t.getName());
                        trackSidToParticipant.put(t.getSid(), p.getIdentity());
                    });
                });
            }

            //default -> log.debug("[LiveKit] ← unhandled proto message: {}", msg.getMessageCase());
        }
    }

    @Override
    public void onClose(Session session, CloseReason reason) {
        this.wsSession = null;
        sidToIdentity.clear();
        log.info("[LiveKit] Signal WebSocket closed: {}", reason.getReasonPhrase());
    }

    @OnError
    public void onError(Session session, Throwable t) {
        log.error("[LiveKit] Signal error: {}", t.getMessage(), t);
        Consumer<String> cb = onError;
        if (cb != null) cb.accept(t.getMessage());
    }
}