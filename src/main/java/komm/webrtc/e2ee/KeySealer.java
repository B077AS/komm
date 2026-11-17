package komm.webrtc.e2ee;

import javax.crypto.Cipher;
import javax.crypto.KDF;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.HKDFParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/**
 * Wraps a member's SFrame media key for one other member of the call, so the installation
 * relaying it (and the SFU) never sees it. Only JDK primitives:
 * <pre>
 *   shared  = X25519(myPrivate, theirPublic)                      ephemeral keys, one pair per call
 *   wrapKey = HKDF-SHA256(ikm = shared, salt = channelId,
 *                         info = "komm-e2ee-v1/wrap|from|to")     direction-bound
 *   sealed  = AES-256-GCM(wrapKey, nonce, mediaKey,
 *                         aad = "komm-e2ee-v1|channel|from|to|participant|generation")
 * </pre>
 * The AAD binds the key to its sender, recipient, call, participant id and generation, so the
 * relay can't replay a sealed key under another identity or generation.
 * <p>
 * What this does NOT give: authentication of the public keys themselves. They are
 * distributed by the installation, which could substitute its own (an active MITM).
 * Defeating that needs long-term identity keys and out-of-band verification.
 */
public final class KeySealer {

    private static final String VERSION = "komm-e2ee-v1";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final KeyPair keyPair;
    private final String publicKeyBase64;

    public KeySealer() {
        try {
            keyPair = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("X25519 unavailable", e);
        }
        publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    /** This member's public key for the call, base64 X.509. */
    public String publicKey() {
        return publicKeyBase64;
    }

    public record Sealed(String nonce, String ciphertext) {}

    /** Seals {@code mediaKey} for the holder of {@code recipientPublicKey}. */
    public Sealed seal(String recipientPublicKey, UUID channelId, UUID from, UUID to,
                       long participantId, int generation, byte[] mediaKey) throws GeneralSecurityException {
        SecretKey wrap = wrapKey(recipientPublicKey, channelId, from, to);
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.ENCRYPT_MODE, wrap, new GCMParameterSpec(TAG_BITS, nonce));
        gcm.updateAAD(aad(channelId, from, to, participantId, generation));
        byte[] ct = gcm.doFinal(mediaKey);
        Base64.Encoder b64 = Base64.getEncoder();
        return new Sealed(b64.encodeToString(nonce), b64.encodeToString(ct));
    }

    /**
     * Opens a key sealed for us by the holder of {@code senderPublicKey}.
     *
     * @throws GeneralSecurityException if it was not sealed for our current key pair, or was
     *                                  tampered with (including any field bound in the AAD)
     */
    public byte[] open(String senderPublicKey, UUID channelId, UUID from, UUID to,
                       long participantId, int generation, String nonce, String ciphertext)
            throws GeneralSecurityException {
        SecretKey wrap = wrapKey(senderPublicKey, channelId, from, to);
        Base64.Decoder b64 = Base64.getDecoder();
        byte[] n;
        byte[] ct;
        try {
            n = b64.decode(nonce);
            ct = b64.decode(ciphertext);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Malformed base64", e);
        }
        if (n.length != NONCE_BYTES) throw new GeneralSecurityException("Bad nonce length");
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.DECRYPT_MODE, wrap, new GCMParameterSpec(TAG_BITS, n));
        gcm.updateAAD(aad(channelId, from, to, participantId, generation));
        return gcm.doFinal(ct);
    }

    private SecretKey wrapKey(String peerPublicKey, UUID channelId, UUID from, UUID to)
            throws GeneralSecurityException {
        PublicKey peer = decodePublicKey(peerPublicKey);
        KeyAgreement ka = KeyAgreement.getInstance("X25519");
        ka.init(keyPair.getPrivate());
        ka.doPhase(peer, true);
        byte[] shared = ka.generateSecret(); // the JDK rejects an all-zero (small-order) result
        try {
            KDF hkdf = KDF.getInstance("HKDF-SHA256");
            return hkdf.deriveKey("AES", HKDFParameterSpec.ofExtract()
                    .addIKM(shared)
                    .addSalt(uuidBytes(channelId))
                    .thenExpand(utf8(VERSION + "/wrap|" + from + "|" + to), 32));
        } finally {
            Arrays.fill(shared, (byte) 0);
        }
    }

    private static PublicKey decodePublicKey(String base64) throws GeneralSecurityException {
        try {
            return KeyFactory.getInstance("X25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Malformed public key", e);
        }
    }

    private static byte[] aad(UUID channelId, UUID from, UUID to, long participantId, int generation) {
        return utf8(VERSION + "|" + channelId + "|" + from + "|" + to + "|" + participantId + "|" + generation);
    }

    private static byte[] uuidBytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
