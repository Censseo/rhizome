package rhizome.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static rhizome.crypto.Crypto.generateKeyPairTyped;
import org.junit.jupiter.api.Test;

/** Strict Ed25519 public-key encoding validation: canonical y, on-curve, no small order (audit F1). */
class PublicKeyTest {

    /**
     * The seven canonical small-order encodings (orders 1, 2, 4 and 8) from the RFC 8032 /
     * libsodium blocklist. The eighth (all-zero) maps to {@link PublicKey#empty()} historically.
     */
    private static final String[] SMALL_ORDER_HEX = {
        "0000000000000000000000000000000000000000000000000000000000000000",
        "0100000000000000000000000000000000000000000000000000000000000000",
        "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
        "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc85",
        "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
        "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac03fa",
        "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
    };

    @Test
    void smallOrderEncodingsAllMapToEmpty() {
        for (String hex : SMALL_ORDER_HEX) {
            assertEquals(PublicKey.empty(), PublicKey.of(hex), "small-order key accepted: " + hex);
            assertTrue(PublicKey.of(hex).isEmpty());
        }
    }

    @Test
    void nonCanonicalYIsRejected() {
        // y = p = 2^255-19 (little-endian): off the canonical range, so not a valid encoding.
        assertEquals(PublicKey.empty(),
            PublicKey.of("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"));
    }

    @Test
    void offCurvePointIsRejected() {
        // y = 2 (little-endian), x sign 0: canonical but not on the curve.
        assertEquals(PublicKey.empty(),
            PublicKey.of("0200000000000000000000000000000000000000000000000000000000000000"));
    }

    @Test
    void rejectedKeyFailsClosedInCheckSignature() {
        PublicKey smallOrder = PublicKey.of(
            "0100000000000000000000000000000000000000000000000000000000000000");
        assertFalse(Crypto.checkSignature(new byte[] {1, 2, 3}, new byte[64], smallOrder));
    }

    @Test
    void generatedKeysStillValidateAndVerify() {
        var pair = generateKeyPairTyped();
        PublicKey publicKey = pair.publicKey();
        assertTrue(publicKey.isPresent(), "freshly generated key must be accepted");
        byte[] signature = Crypto.signWithPrivateKey("hello", pair.privateKey());
        assertTrue(Crypto.checkSignature("hello", signature, publicKey));
    }

    @Test
    void identicalEncodingsCompareEqual() {
        // Ed25519PublicKeyParameters has no equals/hashCode; equality must be by encoding.
        byte[] encoded = Crypto.generateKeyPairTyped().publicKey().toBytes();
        PublicKey a = PublicKey.of(encoded);
        PublicKey b = PublicKey.of(encoded.clone());
        PublicKey c = PublicKey.of(a.toHexString());
        assertEquals(a, b);
        assertEquals(a, c);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(PublicKey.empty(), PublicKey.empty());
        assertFalse(a.equals(PublicKey.empty()));
        assertFalse(a.equals(null));
    }
}
