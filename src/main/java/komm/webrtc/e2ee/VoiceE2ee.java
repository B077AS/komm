package komm.webrtc.e2ee;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.onvoid.webrtc.RTCEncodedFrame;
import dev.onvoid.webrtc.RTCEncodedFrameTransformer;
import dev.onvoid.webrtc.e2ee.CryptoEvent;
import dev.onvoid.webrtc.e2ee.CryptoFailure;
import dev.onvoid.webrtc.e2ee.CryptoStats;
import dev.onvoid.webrtc.e2ee.KeyProvider;
import komm.api.json.GsonProvider;
import komm.websocket.InstallationWsClient;
import komm.websocket.messages.WsMessageType;
import komm.websocket.messages.payloads.E2eeAnnouncePayload;
import komm.websocket.messages.payloads.E2eeKeyPayload;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end encryption of one voice-channel session (one LiveKit room join).
 *
 * <h3>Media</h3>
 * Every frame is encrypted with SFrame (RFC 9605) by webrtc-java's {@link KeyProvider}
 * transforms: an encryptor on each of our senders (mic, soundboard, screen audio, screen
 * video) and a decryptor on each subscriber receiver. Each member encrypts with its own
 * random key ("sender keys"), identified in every frame by a random 32-bit participant id
 * and an 8-bit generation. The SFU forwards frames it can't read; a frame that doesn't
 * authenticate never reaches a decoder, and a frame that can't be encrypted is dropped,
 * never sent in the clear.
 *
 * <h3>Key exchange (over the installation WebSocket, which only relays)</h3>
 * <ol>
 *   <li>On join we make a fresh X25519 key pair and media key and send
 *       {@code E2EE_ANNOUNCE {publicKey}}; the installation relays it to the channel.</li>
 *   <li>Whenever we learn a peer's public key (their ANNOUNCE, or a KEY from them) that we
 *       have not sent our key to, we send them {@code E2EE_KEY}: our media key sealed for
 *       them ({@link KeySealer}) plus our own public key. The answer to that is their
 *       key, so two members that join at the same moment still end up exchanging keys.</li>
 *   <li>When a member leaves, everyone remaining rotates: a new key, generation + 1, is
 *       sent to the remaining peers first and used for encryption a moment later, so the
 *       member who left can't decrypt anything that follows.</li>
 * </ol>
 * Every key pair, media key and participant id lives for one session only; a rejoin starts
 * from scratch. The public keys are not authenticated (see {@link KeySealer}).
 */
@Slf4j
public final class VoiceE2ee implements AutoCloseable {

    /** Leaves arriving together (e.g. a channel emptying) cause one rotation, not many. */
    private static final long ROTATE_DEBOUNCE_MS = 300;
    /**
     * Time between sending a new key and encrypting with it. Peers drop our frames until
     * the key reaches them, so this covers the WebSocket relay round trip.
     */
    private static final long ACTIVATE_DELAY_MS = 1500;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Gson gson = GsonProvider.get();
    private final InstallationWsClient ws;
    @Getter private final UUID channelId;
    private final UUID selfId;
    private final KeySealer sealer = new KeySealer();
    private final KeyProvider keys;
    @Getter private final long participantId;
    private final ScheduledExecutorService scheduler;

    // ── guarded by this ──────────────────────────────────────────────────────
    private byte[] currentKey;
    private int currentGeneration;
    /** A rotated key already sent to the peers but not encrypted with yet, or null. */
    private byte[] pendingKey;
    private int pendingGeneration = -1;
    private ScheduledFuture<?> rotateTask;
    private ScheduledFuture<?> activateTask;
    private final Map<UUID, Peer> peers = new HashMap<>();
    /** SFrame participant id → the user who claimed it this session. */
    private final Map<Long, UUID> participantOwners = new HashMap<>();
    private boolean closed;

    private static final class Peer {
        /** Their current public key, base64. */
        String publicKey;
        /** Their SFrame participant id, -1 until a key arrived. */
        long participantId = -1;
    }

    public VoiceE2ee(InstallationWsClient ws, UUID channelId, UUID selfId) {
        this.ws = ws;
        this.channelId = channelId;
        this.selfId = selfId;
        this.participantId = RANDOM.nextLong() & 0xFFFF_FFFFL;
        this.keys = KeyProvider.builder(participantId)
                .listener(VoiceE2ee::onCryptoFailure)
                .build();
        this.currentKey = keys.generateKey();
        this.currentGeneration = 0;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "voice-e2ee");
            t.setDaemon(true);
            return t;
        });
    }

    /** Arms our encryption key and announces our public key to the channel. */
    public synchronized void start() {
        keys.setEncryptionKey(currentGeneration, currentKey);
        ws.send(WsMessageType.E2EE_ANNOUNCE, E2eeAnnouncePayload.builder()
                .publicKey(sealer.publicKey())
                .build());
        log.info("[E2EE] Session started: channel={} participant={}", channelId, participantId);
    }

    // ── Transforms ───────────────────────────────────────────────────────────

    /** For an audio sender. */
    public RTCEncodedFrameTransformer createEncryptor() {
        return skipEmpty(keys.createEncryptor());
    }

    /** For a video sender; {@code keyFrameRequest} is typically {@code sender::generateKeyFrame}. */
    public RTCEncodedFrameTransformer createEncryptor(Runnable keyFrameRequest) {
        return skipEmpty(keys.createEncryptor(keyFrameRequest));
    }

    /** For an audio receiver. */
    public RTCEncodedFrameTransformer createDecryptor() {
        return skipEmpty(keys.createDecryptor());
    }

    /** For a video receiver; {@code keyFrameRequest} is typically {@code receiver::requestKeyFrame}. */
    public RTCEncodedFrameTransformer createDecryptor(Runnable keyFrameRequest) {
        return skipEmpty(keys.createDecryptor(keyFrameRequest));
    }

    /**
     * Lets empty frames through untouched. During silence (Opus DTX) the audio
     * sender hands the transform empty frames; the library calls them MALFORMED
     * (no TOC byte to keep clear) and drops them, which logged a warning each time
     * silence followed speech. An empty frame has nothing to protect, and WebRTC
     * sends no packet for it either way.
     */
    private static RTCEncodedFrameTransformer skipEmpty(RTCEncodedFrameTransformer inner) {
        return new RTCEncodedFrameTransformer() {
            @Override
            public void transform(RTCEncodedFrame frame) {
                if (frame.getData().remaining() == 0) return;
                inner.transform(frame);
            }

            @Override
            public boolean allowsPlaintextObservers() {
                return inner.allowsPlaintextObservers();
            }
        };
    }

    public CryptoStats getStats() {
        return keys.getStats();
    }

    // ── Inbound key exchange ─────────────────────────────────────────────────

    public void onAnnounce(JsonObject json) {
        E2eeAnnouncePayload p = gson.fromJson(json, E2eeAnnouncePayload.class);
        if (!channelId.equals(p.getChannelId()) || p.getUserId() == null
                || selfId.equals(p.getUserId()) || p.getPublicKey() == null) {
            return;
        }
        synchronized (this) {
            if (closed) return;
            learnPublicKey(p.getUserId(), p.getPublicKey());
        }
    }

    public void onKey(JsonObject json) {
        E2eeKeyPayload p = gson.fromJson(json, E2eeKeyPayload.class);
        UUID from = p.getFromUserId();
        if (!channelId.equals(p.getChannelId()) || !selfId.equals(p.getToUserId())
                || from == null || selfId.equals(from) || p.getPublicKey() == null) {
            return;
        }
        long pid = p.getParticipantId();
        int generation = p.getGeneration();
        if (pid < 0 || pid > 0xFFFF_FFFFL || generation < 0 || generation > 255) return;

        byte[] mediaKey;
        try {
            mediaKey = sealer.open(p.getPublicKey(), channelId, from, selfId, pid, generation,
                    p.getNonce(), p.getSealedKey());
        } catch (GeneralSecurityException e) {
            // Usually sealed for our key pair of an earlier session; they'll get our new
            // ANNOUNCE and send it again.
            log.debug("[E2EE] Could not open key from {}: {}", from, e.getMessage());
            return;
        }

        synchronized (this) {
            if (closed) return;
            UUID owner = participantOwners.get(pid);
            if (owner != null && !owner.equals(from)) {
                log.warn("[E2EE] {} claimed participant id {} already used by {} — ignored", from, pid, owner);
                return;
            }
            if (pid == participantId) {
                log.warn("[E2EE] {} uses our own participant id {} — ignored", from, pid);
                return;
            }
            Peer peer = peers.computeIfAbsent(from, k -> new Peer());
            if (peer.participantId >= 0 && peer.participantId != pid) {
                // They rejoined: their previous session's keys are dead.
                keys.removeParticipant(peer.participantId);
                participantOwners.remove(peer.participantId);
            }
            peer.participantId = pid;
            participantOwners.put(pid, from);
            try {
                keys.setDecryptionKey(pid, generation, mediaKey);
                log.info("[E2EE] Key from {} (participant {}, generation {})", from, pid, generation);
            } catch (IllegalArgumentException e) {
                log.warn("[E2EE] Rejected key from {}: {}", from, e.getMessage());
            } finally {
                Arrays.fill(mediaKey, (byte) 0);
            }
            learnPublicKey(from, p.getPublicKey());
        }
    }

    /** A member left the channel: forget their keys and rotate ours. */
    public void onPeerLeft(UUID userId) {
        synchronized (this) {
            if (closed) return;
            Peer peer = peers.remove(userId);
            if (peer == null) return;
            if (peer.participantId >= 0) {
                keys.removeParticipant(peer.participantId);
                participantOwners.remove(peer.participantId);
            }
            if (rotateTask == null || rotateTask.isDone()) {
                rotateTask = scheduler.schedule(this::rotate, ROTATE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
            }
        }
        log.info("[E2EE] {} left — rotating our key", userId);
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /** Records a peer's public key and, if it is new to us, sends them our key(s). Holds this. */
    private void learnPublicKey(UUID userId, String publicKey) {
        Peer peer = peers.computeIfAbsent(userId, k -> new Peer());
        if (publicKey.equals(peer.publicKey)) return; // already have our key
        peer.publicKey = publicKey;
        sendKey(userId, peer, currentGeneration, currentKey);
        if (pendingKey != null) sendKey(userId, peer, pendingGeneration, pendingKey);
    }

    private synchronized void rotate() {
        if (closed) return;
        // A key still pending was already sent to whoever just left, so it is replaced too.
        int base = pendingKey != null ? pendingGeneration : currentGeneration;
        if (pendingKey != null) Arrays.fill(pendingKey, (byte) 0);
        pendingKey = keys.generateKey();
        pendingGeneration = (base + 1) & 0xFF;
        peers.forEach((userId, peer) -> sendKey(userId, peer, pendingGeneration, pendingKey));

        if (activateTask != null) activateTask.cancel(false);
        long delay = peers.isEmpty() ? 0 : ACTIVATE_DELAY_MS;
        activateTask = scheduler.schedule(this::activatePending, delay, TimeUnit.MILLISECONDS);
    }

    private synchronized void activatePending() {
        if (closed || pendingKey == null) return;
        keys.setEncryptionKey(pendingGeneration, pendingKey);
        Arrays.fill(currentKey, (byte) 0);
        currentKey = pendingKey;
        currentGeneration = pendingGeneration;
        pendingKey = null;
        pendingGeneration = -1;
        log.info("[E2EE] Now encrypting with generation {}", currentGeneration);
    }

    private void sendKey(UUID to, Peer peer, int generation, byte[] mediaKey) {
        if (peer.publicKey == null) return;
        try {
            KeySealer.Sealed sealed = sealer.seal(peer.publicKey, channelId, selfId, to,
                    participantId, generation, mediaKey);
            ws.send(WsMessageType.E2EE_KEY, E2eeKeyPayload.builder()
                    .toUserId(to)
                    .publicKey(sealer.publicKey())
                    .participantId(participantId)
                    .generation(generation)
                    .nonce(sealed.nonce())
                    .sealedKey(sealed.ciphertext())
                    .build());
        } catch (GeneralSecurityException e) {
            log.warn("[E2EE] Could not seal key for {}: {}", to, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("[E2EE] Could not send key to {}: {}", to, e.getMessage());
        }
    }

    private static void onCryptoFailure(CryptoEvent event) {
        // Reported once per stream when it starts failing, not per frame.
        if (event.getFailure() == CryptoFailure.UNKNOWN_KEY || event.getFailure() == CryptoFailure.MISSING_KEY) {
            log.info("[E2EE] Waiting for key: {}", event);
        } else {
            log.warn("[E2EE] Frames dropped: {}", event);
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            // Transforms may outlive us on a closing peer connection: with no key they drop
            // every frame instead of sending anything in the clear.
            keys.removeEncryptionKey();
            peers.values().forEach(p -> { if (p.participantId >= 0) keys.removeParticipant(p.participantId); });
            peers.clear();
            participantOwners.clear();
            Arrays.fill(currentKey, (byte) 0);
            if (pendingKey != null) Arrays.fill(pendingKey, (byte) 0);
        }
        scheduler.shutdownNow();
        log.info("[E2EE] Session closed: channel={}", channelId);
    }
}
