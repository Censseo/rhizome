package rhizome.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Locks the signature-scheme discriminant: the one byte that lets the chain name a different
 * signature algorithm without a wire-format break, and that must fail closed on anything it does
 * not implement.
 */
class SignatureSchemeTest {

    @Test
    void codesRoundTrip() {
        for (SignatureScheme scheme : SignatureScheme.values()) {
            assertEquals(scheme, SignatureScheme.fromCode(scheme.code()));
            assertTrue(SignatureScheme.isKnown(scheme.code()));
        }
    }

    @Test
    void codesAreStableConsensusValues() {
        // These bytes appear in every address and on every transaction; changing one silently
        // reinterprets existing chain data, so they are pinned as literals rather than derived.
        assertEquals((byte) 0x00, SignatureScheme.ED25519.code());
        assertEquals((byte) 0x01, SignatureScheme.ED25519_PQC.code());
    }

    /**
     * The reserved post-quantum block must be rejected, not defaulted. A node that read an
     * unrecognised scheme as Ed25519 would parse the following fields at the wrong widths and could
     * accept a transaction a scheme-aware node rejects — a consensus split, and the exact failure a
     * discriminant exists to prevent.
     */
    @Test
    void reservedAndUnknownCodesAreRejected() {
        for (int code : new int[] {0x02, 0x03, 0x04, 0x0F, 0x10, 0x7F, 0x80, 0xFF}) {
            assertFalse(SignatureScheme.isKnown((byte) code), "0x" + Integer.toHexString(code));
            assertThrows(IllegalArgumentException.class,
                () -> SignatureScheme.fromCode((byte) code), "0x" + Integer.toHexString(code));
        }
    }

    @Test
    void onlyCommittingSchemesCarryACommitment() {
        assertFalse(SignatureScheme.ED25519.commitsToPostQuantumKey());
        assertEquals(0, SignatureScheme.ED25519.commitmentBytes());
        assertTrue(SignatureScheme.ED25519_PQC.commitsToPostQuantumKey());
        assertEquals(SignatureScheme.COMMITMENT_SIZE, SignatureScheme.ED25519_PQC.commitmentBytes());
    }

    @Test
    void maxWireAuthBytesBoundsEveryScheme() {
        for (SignatureScheme scheme : SignatureScheme.values()) {
            assertTrue(scheme.wireAuthBytes() <= SignatureScheme.MAX_WIRE_AUTH_BYTES,
                scheme + " exceeds MAX_WIRE_AUTH_BYTES");
        }
        // scheme(1) + signature(64) + publicKey(32) + commitment(32)
        assertEquals(129, SignatureScheme.MAX_WIRE_AUTH_BYTES);
    }
}
