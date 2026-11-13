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

    /**
     * SIG-10 — Slip a small-order key past the denylist by flipping the x sign bit (top bit of
     * the last byte) of a listed encoding, e.g. 00..0080 or 01..0080.
     */
    @Test
    void signBitVariantsOfSmallOrderEncodingsAreRejected() {
        for (String hex : SMALL_ORDER_HEX) {
            byte[] twin = new byte[32];
            for (int i = 0; i < 32; i++) {
                twin[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
            }
            twin[31] ^= (byte) 0x80;
            assertTrue(PublicKey.of(twin).isEmpty(), "sign-bit twin accepted: " + hex);
        }
        byte[] zeroY = new byte[32];
        zeroY[31] = (byte) 0x80;
        assertTrue(PublicKey.of(zeroY).isEmpty());
        byte[] oneY = new byte[32];
        oneY[0] = 1;
        oneY[31] = (byte) 0x80;
        assertTrue(PublicKey.of(oneY).isEmpty());

        // BouncyCastle's partial validation rejects these first, so the end-to-end asserts above
        // pass even with the denylist deleted. Assert the denylist itself (sign bit masked, y = 0
        // included): this is what fails if the mask or the y = 0 entry is removed.
        assertTrue(PublicKey.isSmallOrder(zeroY), "y = 0 sign twin");
        assertTrue(PublicKey.isSmallOrder(oneY), "y = 1 sign twin");
        assertTrue(PublicKey.isSmallOrder(new byte[32]), "y = 0");
        for (String hex : SMALL_ORDER_HEX) {
            byte[] enc = new byte[32];
            for (int i = 0; i < 32; i++) {
                enc[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
            }
            assertTrue(PublicKey.isSmallOrder(enc), "listed: " + hex);
            enc[31] ^= (byte) 0x80;
            assertTrue(PublicKey.isSmallOrder(enc), "sign twin: " + hex);
        }
        byte[] ordinary = new byte[32];
        ordinary[0] = 9;
        assertFalse(PublicKey.isSmallOrder(ordinary));
    }
}
