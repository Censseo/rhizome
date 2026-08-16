package rhizome.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static rhizome.crypto.Hex.hexStringToByteArray;

/**
 * L28 verrou: known Ed25519 vectors pin the signature primitive, so the refactor that
 * routes signing and verification through the scheme's {@link SignatureAlgorithm}
 * (instead of instantiating {@code Ed25519Signer} inline in {@link Crypto}) cannot
 * silently change what verifies. The vector is RFC 8032 TEST 1 (empty message), which
 * also pins the seed→public-key derivation and the exact signature bytes.
 *
 * <p>Written before the refactor: it must be green on the current inline-signer code
 * and stay green byte-for-byte afterwards.
 */
class SignatureVectorTest {

    /** RFC 8032 TEST 1 — 32-byte seed. */
    private static final byte[] SEED = hexStringToByteArray(
        "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
    /** RFC 8032 TEST 1 — public key derived from the seed. */
    private static final byte[] PUBLIC_KEY = hexStringToByteArray(
        "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
    /** RFC 8032 TEST 1 — signature over the empty message. */
    private static final byte[] SIGNATURE_EMPTY = hexStringToByteArray(
        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555"
        + "fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");

    /** Derives the public key from a private key the way callers do (the seed's public half). */
    private static byte[] publicKeyBytes(PrivateKey privateKey) {
        return privateKey.publicKey().toBytes();
    }

    @Test
    void seedDerivesTheVectorPublicKey() {
        PrivateKey privateKey = PrivateKey.of(SEED);
        // The private key's derived public key must be the vector's — this is the address
        // derivation input, so a wrong derivation silently forks every address.
        assertArrayEquals(PUBLIC_KEY, publicKeyBytes(privateKey), "RFC 8032 TEST 1 public key");
        assertArrayEquals(PUBLIC_KEY, PublicKey.of(PUBLIC_KEY).toBytes());
    }

    @Test
    void vectorSignatureOverTheEmptyMessage() {
        PrivateKey privateKey = PrivateKey.of(SEED);
        PublicKey publicKey = PublicKey.of(PUBLIC_KEY);

        byte[] signature = Crypto.signWithPrivateKey(new byte[0], privateKey);
        assertArrayEquals(SIGNATURE_EMPTY, signature,
            "the exact signature bytes are consensus-visible (RFC 8032 TEST 1)");

        assertTrue(Crypto.checkSignature(new byte[0], signature, publicKey),
            "the vector signature must verify under its public key");
        assertFalse(Crypto.checkSignature(new byte[0], signature, PublicKey.empty()),
            "a missing public key must not verify the signature");
        assertFalse(Crypto.checkSignature(new byte[] {1}, signature, publicKey),
            "a different message must not verify the signature");
    }

    @Test
    void vectorPublicKeyRejectsForgedSignatures() {
        PublicKey publicKey = PublicKey.of(PUBLIC_KEY);
        byte[] forged = hexStringToByteArray(
            "0000000000000000000000000000000000000000000000000000000000000000"
            + "0000000000000000000000000000000000000000000000000000000000000000");
        assertFalse(Crypto.checkSignature(new byte[0], forged, publicKey));
        assertFalse(Crypto.checkSignature("message".getBytes(StandardCharsets.UTF_8), forged, publicKey));
    }

    /**
     * Transaction verification dispatches through the DECLARED scheme's table entry
     * ({@code TransactionImpl.signatureValid} passes its scheme to {@code Crypto.checkSignature}):
     * a scheme shipping a different primitive then changes one table row, not the consensus
     * path. Both implemented schemes name Ed25519 today, so each must verify the RFC vector —
     * a row wired to nothing (or to the wrong primitive) fails here.
     */
    @Test
    void everySchemeVerifiesTheVectorThroughItsTableEntry() {
        PublicKey publicKey = PublicKey.of(PUBLIC_KEY);
        for (SignatureScheme scheme : SignatureScheme.values()) {
            assertTrue(Crypto.checkSignature(new byte[0], SIGNATURE_EMPTY, publicKey, scheme),
                scheme + " must verify the vector through its algorithm table entry");
            assertFalse(Crypto.checkSignature(new byte[] {1}, SIGNATURE_EMPTY, publicKey, scheme),
                scheme + " must reject a different message through the same entry");
        }
    }
}
