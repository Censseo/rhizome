package rhizome.core.state;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The composite raw-key layouts ({@link StateKeys#TOKEN_BALANCE}'s {@code tokenId ‖ address})
 * that {@code BlockStateChanges} (block application) and {@code DomainStateAdapter} (snapshot
 * export/import) both commit to the state root — now one function on each side instead of two
 * hand-copies that could transpose or drift. {@link StateKeys#splitTokenBalanceKey} must be the
 * exact inverse of {@link StateKeys#tokenBalanceKey} across the edge cases a hand-written offset
 * pair could get wrong: an empty half, and a max-length token id.
 */
class StateKeysCompositeKeyTest {

    @Test
    void concatIsOrderPreservingAndNeverTransposed() {
        byte[] a = {1, 2, 3};
        byte[] b = {9, 9};
        assertArrayEquals(new byte[] {1, 2, 3, 9, 9}, StateKeys.concat(a, b));
        assertArrayEquals(new byte[] {9, 9, 1, 2, 3}, StateKeys.concat(b, a),
            "concatenation is not commutative — the two halves are NOT interchangeable");
    }

    @Test
    void tokenBalanceKeyIsTokenIdThenAddress() {
        byte[] tokenId = fill(StateKeys.TOKEN_ID_BYTES, (byte) 0xAB);
        byte[] address = fill(25, (byte) 0xCD);
        byte[] key = StateKeys.tokenBalanceKey(tokenId, address);

        assertEquals(StateKeys.TOKEN_ID_BYTES + 25, key.length);
        assertArrayEquals(StateKeys.concat(tokenId, address), key);
    }

    @Test
    void splitTokenBalanceKeyInvertsTokenBalanceKeyOnAnEmptyAddress() {
        // A degenerate but not impossible input: split must not throw and must round-trip.
        byte[] tokenId = fill(StateKeys.TOKEN_ID_BYTES, (byte) 0x01);
        byte[] address = new byte[0];
        byte[][] split = StateKeys.splitTokenBalanceKey(StateKeys.tokenBalanceKey(tokenId, address));
        assertArrayEquals(tokenId, split[0]);
        assertArrayEquals(address, split[1]);
    }

    @Test
    void splitTokenBalanceKeyInvertsTokenBalanceKeyOnAMaxByteTokenId() {
        byte[] tokenId = fill(StateKeys.TOKEN_ID_BYTES, (byte) 0xFF);
        byte[] address = fill(25, (byte) 0x00);
        byte[][] split = StateKeys.splitTokenBalanceKey(StateKeys.tokenBalanceKey(tokenId, address));
        assertArrayEquals(tokenId, split[0], "the token-id half must not bleed into the address half");
        assertArrayEquals(address, split[1], "the address half must not bleed into the token-id half");
    }

    private static byte[] fill(int size, byte value) {
        byte[] out = new byte[size];
        java.util.Arrays.fill(out, value);
        return out;
    }
}
