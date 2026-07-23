package rhizome.crypto;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Total ordering for {@link SHA256Hash#compareTo} across any lengths (audit S9). */
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
}
