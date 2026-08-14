package rhizome.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import rhizome.core.transaction.TransactionKind;

/**
 * L24 verrou: the transaction-kind byte is a consensus value — it sits in the signed
 * preimage ({@code TransactionImpl.hashContents}), on the wire ({@code TransactionDto})
 * and in the persisted box receipts — so it must not depend on the enum's declaration
 * order. The vectors below pin the kind→byte mapping as literals (the same convention
 * as {@code SignatureSchemeTest#codesAreStableConsensusValues}), and the unknown-code
 * rejection proves a reordered enum cannot silently reinterpret old chain data.
 *
 * <p>Written before the refactor that makes the codes explicit constructor arguments
 * instead of {@code ordinal()}s: it must stay green with byte-identical vectors, and
 * fail if a code ever moved.
 */
class TransactionKindWireTest {

    @Test
    void kindsCarryTheirFrozenWireCodes() {
        assertEquals((byte) 0, TransactionKind.TRANSFER.code());
        assertEquals((byte) 1, TransactionKind.DEPLOY.code());
        assertEquals((byte) 2, TransactionKind.CALL.code());
        assertEquals((byte) 3, TransactionKind.BOX_CREATE.code());
        assertEquals((byte) 4, TransactionKind.BOX_UPDATE.code());
        assertEquals((byte) 5, TransactionKind.BOX_SPEND.code());
        assertEquals((byte) 6, TransactionKind.BOX_COLLECT.code());
        assertEquals((byte) 7, TransactionKind.TOKEN_MINT.code());
        assertEquals((byte) 8, TransactionKind.TOKEN_TRANSFER.code());
        assertEquals((byte) 9, TransactionKind.TOKEN_BURN.code());
    }

    @Test
    void everyCodeRoundTripsThroughTheDecoder() {
        for (TransactionKind kind : TransactionKind.values()) {
            assertEquals(kind, TransactionKind.fromCode(kind.code()),
                kind + " must decode from its own code");
        }
    }

    @Test
    void unknownCodesAreRejected() {
        // A node that read an unrecognised kind as some other kind would parse the following
        // fields at the wrong widths — the same class of consensus split SignatureScheme guards
        // against. Every byte outside the implemented 0..9 range must fail, not default.
        for (int code : new int[] {0x0A, 0x0B, 0x0F, 0x10, 0x7F, 0x80, 0xFF}) {
            assertThrows(IllegalArgumentException.class,
                () -> TransactionKind.fromCode((byte) code), "0x" + Integer.toHexString(code));
        }
    }
}
