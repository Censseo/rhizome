package rhizome;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.token.TokenMeta;
import rhizome.core.token.TokenPayload;
import rhizome.core.transaction.TransactionKind;

class TokenModelTest {

    private final PublicAddress minter = PublicAddress.random();

    @Test
    void metaRoundTrips() {
        TokenMeta meta = new TokenMeta(TokenMeta.deriveId(minter, 3), minter, "PNDA", "Panda Coin", 8, 1_000_000, 12);
        TokenMeta back = TokenMeta.deserialize(meta.serialize());
        assertEquals(meta, back);
        assertArrayEquals(meta.id(), back.id());
        assertEquals("PNDA", back.symbol());
        assertEquals("Panda Coin", back.name());
        assertEquals(8, back.decimals());
        assertEquals(1_000_000, back.totalSupply());
    }

    @Test
    void deriveIdIsDeterministicAndUnique() {
        assertArrayEquals(TokenMeta.deriveId(minter, 3), TokenMeta.deriveId(minter, 3));
        assertFalse(java.util.Arrays.equals(TokenMeta.deriveId(minter, 3), TokenMeta.deriveId(minter, 4)));
    }

    @Test
    void mintPayloadRoundTrips() {
        byte[] data = TokenPayload.encodeMint(500_000, 6, "DOGE", "Doge");
        TokenPayload p = TokenPayload.decode(TransactionKind.TOKEN_MINT, data, 16, 64, 18);
        assertEquals(500_000, p.amount());
        assertEquals(6, p.decimals());
        assertEquals("DOGE", p.symbol());
        assertEquals("Doge", p.name());
    }

    @Test
    void amountPayloadRoundTrips() {
        byte[] tokenId = TokenMeta.deriveId(minter, 1);
        byte[] data = TokenPayload.encodeAmount(tokenId, 42);
        TokenPayload p = TokenPayload.decode(TransactionKind.TOKEN_TRANSFER, data, 16, 64, 18);
        assertArrayEquals(tokenId, p.tokenId());
        assertEquals(42, p.amount());
    }

    @Test
    void decodeRejectsMalformed() {
        // Zero mint amount.
        byte[] zero = TokenPayload.encodeMint(1, 0, "X", "x");
        zero[7] = 0; // clobber the low byte of the amount to 0 (amount was 1)
        assertThrows(IllegalArgumentException.class,
            () -> TokenPayload.decode(TransactionKind.TOKEN_MINT, zero, 16, 64, 18));
        // Symbol too long for the cap.
        byte[] longSym = TokenPayload.encodeMint(10, 2, "TOOLONGSYMBOL", "n");
        assertThrows(IllegalArgumentException.class,
            () -> TokenPayload.decode(TransactionKind.TOKEN_MINT, longSym, 4, 64, 18));
        // Decimals over the cap.
        byte[] bigDec = TokenPayload.encodeMint(10, 30, "X", "x");
        assertThrows(IllegalArgumentException.class,
            () -> TokenPayload.decode(TransactionKind.TOKEN_MINT, bigDec, 16, 64, 18));
        // Trailing bytes.
        byte[] amt = TokenPayload.encodeAmount(new byte[32], 5);
        byte[] trailer = java.util.Arrays.copyOf(amt, amt.length + 1);
        assertThrows(IllegalArgumentException.class,
            () -> TokenPayload.decode(TransactionKind.TOKEN_TRANSFER, trailer, 16, 64, 18));
    }

    @Test
    void kindClassification() {
        assertFalse(TransactionKind.TOKEN_MINT.isBox());
        assertFalse(TransactionKind.TOKEN_MINT.isContract());
        org.junit.jupiter.api.Assertions.assertTrue(TransactionKind.TOKEN_MINT.isToken());
        org.junit.jupiter.api.Assertions.assertTrue(TransactionKind.TOKEN_MINT.hasPayload());
        // Box kinds are not tokens and vice versa.
        assertFalse(TransactionKind.BOX_CREATE.isToken());
    }
}
