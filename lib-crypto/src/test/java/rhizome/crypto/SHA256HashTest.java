package rhizome.crypto;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Total ordering for {@link SHA256Hash#compareTo} across any lengths (audit S9), plus the
 *  raw()/cloning-accessor contract. */
class SHA256HashTest {

    @Test
    void compareToIsTotalAndNeverThrowsOnLengthMismatch() {
        // The old loop indexed the other hash up to this hash's length, throwing
        // ArrayIndexOutOfBounds when the other was shorter. SHA256Hash is also used as a wrapper over
        // arbitrary-length PoW preimages (Crypto.PUFFERFISH), so a length mismatch is reachable and
        // must yield a clean ordering, not a crash.
        SHA256Hash shortHash = SHA256Hash.of(new byte[] {0x01, 0x02});
        SHA256Hash longHash = SHA256Hash.of(new byte[32]);
        assertDoesNotThrow(() -> shortHash.compareTo(longHash));
        assertDoesNotThrow(() -> longHash.compareTo(shortHash));
        // A prefix sorts before its extension; the relation is antisymmetric.
        assertEquals(-shortHash.compareTo(longHash), longHash.compareTo(shortHash));
    }

    @Test
    void compareToGivesSignedLexicographicOrderForEqualLengths() {
        byte[] lo = new byte[32];
        byte[] hi = new byte[32];
        hi[0] = 0x01;
        assertTrue(SHA256Hash.of(lo).compareTo(SHA256Hash.of(hi)) < 0);
        assertTrue(SHA256Hash.of(hi).compareTo(SHA256Hash.of(lo)) > 0);
        assertEquals(0, SHA256Hash.of(lo).compareTo(SHA256Hash.of(new byte[32])));
    }

    @Test
    void rawSharesTheBackingArrayWhileTheDefaultAccessorsClone() {
        // raw() is the zero-copy hot-path accessor: identical content, but the SAME array —
        // callers must not mutate or retain it (see its javadoc). hash()/toBytes() stay
        // defensive copies: mutating what they return must never corrupt the hash value.
        byte[] source = new byte[32];
        source[0] = 0x42;
        SHA256Hash h = SHA256Hash.of(source);
        source[0] = 0x00; // construction already cloned — the value is pinned
        assertEquals(0x42, h.raw()[0]);
        org.junit.jupiter.api.Assertions.assertArrayEquals(h.raw(), h.toBytes());
        org.junit.jupiter.api.Assertions.assertArrayEquals(h.raw(), h.hash());
        org.junit.jupiter.api.Assertions.assertSame(h.raw(), h.raw(), "raw() must not allocate");
        byte[] copy = h.toBytes();
        copy[0] = 0x7f;
        assertEquals(0x42, h.raw()[0], "mutating a toBytes() copy must not leak into the value");
    }
}
