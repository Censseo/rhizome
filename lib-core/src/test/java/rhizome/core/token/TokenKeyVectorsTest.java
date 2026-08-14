package rhizome.core.token;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.state.StateKeys;

/**
 * L25 verrou: byte-for-byte key vectors for the token-domain identifiers and composite
 * keys. The 32-byte token id feeds the state root ({@link StateKeys#TOKEN_META}'s raw
 * key and {@link StateKeys#TOKEN_BALANCE}'s leading half) and the RocksDB key layout, so
 * the typed wrapper ({@link TokenId}) and the typed composite ({@link TokenBalanceKey})
 * must encode to exactly the bytes the raw layout always produced — a flipped or
 * transposed encoding would silently fork the state root. Written before the refactor
 * that retypes the domain, on the {@code StateKeysCompositeKeyTest} model.
 */
class TokenKeyVectorsTest {

    /** A 25-byte address-shaped vector (not checksum-valid; hash-derived addresses aren't). */
    private static PublicAddress addr(byte fill) {
        byte[] bytes = new byte[PublicAddress.SIZE];
        java.util.Arrays.fill(bytes, fill);
        return PublicAddress.of(bytes);
    }

    @Test
    void tokenIdIsExactlyThirtyTwoBytesAndRoundTrips() {
        byte[] raw = fill(32, (byte) 0xAB);
        TokenId id = TokenId.of(raw);
        assertArrayEquals(raw, id.toBytes(), "of()/toBytes() must round-trip");

        // Defensive copies in and out, on the PublicAddress model: mutating the source array
        // after of() or the returned array must not change the value.
        raw[0] ^= 0xFF;
        assertArrayEquals(fill(32, (byte) 0xAB), id.toBytes(), "of() must copy its input");
        id.toBytes()[0] ^= 0xFF;
        assertEquals(fill(32, (byte) 0xAB).length, id.toBytes().length);
        assertArrayEquals(fill(32, (byte) 0xAB), id.toBytes(), "toBytes() must copy its output");
    }

    @Test
    void tokenIdRejectsWrongLengths() {
        assertThrows(IllegalArgumentException.class, () -> TokenId.of(new byte[31]));
        assertThrows(IllegalArgumentException.class, () -> TokenId.of(new byte[33]));
        assertThrows(IllegalArgumentException.class, () -> TokenId.of(new byte[0]));
    }

    @Test
    void tokenIdEqualityIsContentBased() {
        assertEquals(TokenId.of(fill(32, (byte) 0x01)), TokenId.of(fill(32, (byte) 0x01)));
        assertNotEquals(TokenId.of(fill(32, (byte) 0x01)), TokenId.of(fill(32, (byte) 0x02)));
        assertEquals(TokenId.of(fill(32, (byte) 0x01)).hashCode(),
            TokenId.of(fill(32, (byte) 0x01)).hashCode());
    }

    @Test
    void balanceKeyEncodesTokenIdThenAddressByteForByte() {
        // The committed layout: StateKeys.tokenBalanceKey(tokenId, address) = tokenId ‖ address.
        // The typed composite must render the same bytes — the state root and the RocksDB
        // balance column family both key on this layout.
        TokenId tokenId = TokenId.of(fill(32, (byte) 0xAB));
        PublicAddress address = addr((byte) 0xCD);
        assertArrayEquals(StateKeys.tokenBalanceKey(tokenId.toBytes(), address.toBytes()),
            TokenBalanceKey.of(tokenId, address).toBytes());
    }

    @Test
    void balanceKeySplitsBackToItsTwoHalves() {
        // A max-byte token id and an all-zero address are the edge cases a hand-written offset
        // pair could get wrong; the typed key must invert cleanly.
        for (byte tokenFill : new byte[] {0x00, 0x01, (byte) 0xFF}) {
            TokenId tokenId = TokenId.of(fill(32, tokenFill));
            for (byte addrFill : new byte[] {0x00, (byte) 0xCD, (byte) 0xFF}) {
                PublicAddress address = addr(addrFill);
                TokenBalanceKey key = TokenBalanceKey.of(tokenId, address);
                byte[] bytes = key.toBytes();
                assertEquals(32 + PublicAddress.SIZE, bytes.length);
                TokenBalanceKey split = TokenBalanceKey.of(TokenId.of(
                    java.util.Arrays.copyOfRange(bytes, 0, 32)), PublicAddress.of(
                    java.util.Arrays.copyOfRange(bytes, 32, bytes.length)));
                assertEquals(key, split, "split halves must reconstruct the same composite");
            }
        }
    }

    @Test
    void balanceKeyMatchesTheStateRootRoundTrip() {
        // DomainStateAdapter splits a snapshot key via StateKeys.splitTokenBalanceKey and
        // BlockStateChanges joins via StateKeys.tokenBalanceKey: the typed key must agree with
        // both directions of that committed round-trip.
        TokenId tokenId = TokenId.of(fill(32, (byte) 0x7E));
        PublicAddress address = addr((byte) 0x5A);
        byte[] committed = StateKeys.tokenBalanceKey(tokenId.toBytes(), address.toBytes());
        byte[][] halves = StateKeys.splitTokenBalanceKey(committed);
        TokenBalanceKey rebuilt = TokenBalanceKey.of(TokenId.of(halves[0]), PublicAddress.of(halves[1]));
        assertEquals(tokenId, rebuilt.tokenId());
        assertEquals(address, rebuilt.address());
    }

    @Test
    void balanceKeyEqualityIsContentBased() {
        TokenId a = TokenId.of(fill(32, (byte) 0x01));
        TokenId b = TokenId.of(fill(32, (byte) 0x02));
        assertEquals(TokenBalanceKey.of(a, addr((byte) 0x01)),
            TokenBalanceKey.of(TokenId.of(fill(32, (byte) 0x01)), addr((byte) 0x01)));
        assertNotEquals(TokenBalanceKey.of(a, addr((byte) 0x01)),
            TokenBalanceKey.of(b, addr((byte) 0x01)), "a different token id must differ");
        assertNotEquals(TokenBalanceKey.of(a, addr((byte) 0x01)),
            TokenBalanceKey.of(a, addr((byte) 0x02)), "a different address must differ");
    }

    private static byte[] fill(int size, byte value) {
        byte[] out = new byte[size];
        java.util.Arrays.fill(out, value);
        return out;
    }
}
