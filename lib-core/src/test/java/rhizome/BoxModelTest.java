package rhizome;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import rhizome.core.box.Box;
import rhizome.core.box.BoxPayload;
import rhizome.core.box.BoxRegister;
import rhizome.core.box.BoxRegisterType;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.TransactionKind;

class BoxModelTest {

    private final PublicAddress owner = PublicAddress.random();

    private Box sampleBox() {
        return new Box(Box.deriveId(owner, 7), owner, 5000, 3, 3, List.of(
            BoxRegister.string("agent-42"),
            BoxRegister.i64(1234),
            BoxRegister.bool(true),
            new BoxRegister(BoxRegisterType.HASH32, new byte[32])));
    }

    @Test
    void boxRoundTrips() {
        Box box = sampleBox();
        Box back = Box.deserialize(box.serialize());
        assertEquals(box, back);
        assertArrayEquals(box.id(), back.id());
        assertEquals(box.owner(), back.owner());
        assertEquals(box.value(), back.value());
        assertEquals(box.registers(), back.registers());
        assertEquals(box.serialize().length, box.serializedSize());
    }

    @Test
    void deriveIdIsDeterministicUniqueAndDomainSeparated() {
        assertArrayEquals(Box.deriveId(owner, 7), Box.deriveId(owner, 7));
        assertFalse(java.util.Arrays.equals(Box.deriveId(owner, 7), Box.deriveId(owner, 8)));
        // Domain suffix separates a box id from a contract address derivation.
        byte[] contractAddr25 = rhizome.core.blockchain.Contracts.deriveAddress(owner, 7).toBytes();
        byte[] boxId32 = Box.deriveId(owner, 7);
        assertNotEquals(32, contractAddr25.length);
        assertFalse(java.util.Arrays.equals(
            java.util.Arrays.copyOf(boxId32, 25), contractAddr25));
    }

    @Test
    void registerValidationEnforcesTagShape() {
        assertTrue(BoxRegister.i64(1).validate());
        assertTrue(BoxRegister.bool(false).validate());
        assertTrue(BoxRegister.string("héllo").validate());
        // Wrong fixed length.
        assertFalse(new BoxRegister(BoxRegisterType.I64, new byte[7]).validate());
        assertFalse(new BoxRegister(BoxRegisterType.HASH32, new byte[31]).validate());
        // Bad boolean byte.
        assertFalse(new BoxRegister(BoxRegisterType.BOOL, new byte[] {2}).validate());
        // Invalid UTF-8.
        assertFalse(new BoxRegister(BoxRegisterType.STRING, new byte[] {(byte) 0xC0}).validate());
    }

    @Test
    void payloadRoundTripsPerKind() {
        List<BoxRegister> regs = List.of(BoxRegister.string("x"), BoxRegister.i64(9));
        byte[] boxId = Box.deriveId(owner, 1);

        BoxPayload create = BoxPayload.decode(TransactionKind.BOX_CREATE,
            BoxPayload.encodeCreate(regs), 6);
        assertEquals(regs, create.registers());

        BoxPayload update = BoxPayload.decode(TransactionKind.BOX_UPDATE,
            BoxPayload.encodeUpdate(boxId, regs), 6);
        assertArrayEquals(boxId, update.boxId());
        assertEquals(regs, update.registers());

        BoxPayload spend = BoxPayload.decode(TransactionKind.BOX_SPEND,
            BoxPayload.encodeTarget(boxId), 6);
        assertArrayEquals(boxId, spend.boxId());
    }

    @Test
    void payloadDecodeRejectsMalformed() {
        // Too many registers.
        byte[] many = BoxPayload.encodeCreate(List.of(
            BoxRegister.i64(1), BoxRegister.i64(2), BoxRegister.i64(3)));
        assertThrows(IllegalArgumentException.class,
            () -> BoxPayload.decode(TransactionKind.BOX_CREATE, many, 2));
        // Trailing bytes.
        byte[] spend = BoxPayload.encodeTarget(new byte[32]);
        byte[] withTrailer = java.util.Arrays.copyOf(spend, 33);
        assertThrows(IllegalArgumentException.class,
            () -> BoxPayload.decode(TransactionKind.BOX_SPEND, withTrailer, 6));
        // Unknown register tag.
        byte[] badTag = {1, (byte) 0x7F, 0, 1, 0x00}; // count=1, tag=0x7F, len=1
        assertThrows(IllegalArgumentException.class,
            () -> BoxPayload.decode(TransactionKind.BOX_CREATE, badTag, 6));
    }

    @Test
    void boxCollectTransactionRoundTripsUnsigned() {
        byte[] boxId = Box.deriveId(owner, 3);
        var tx = rhizome.core.transaction.TransactionImpl.builder()
            .kind(TransactionKind.BOX_COLLECT)
            .from(PublicAddress.empty())
            .to(owner)
            .amount(new rhizome.core.transaction.TransactionAmount(0))
            .fee(new rhizome.core.transaction.TransactionAmount(0))
            .isTransactionFee(false)
            .chainId(2).nonce(0).timestamp(1000)
            .data(BoxPayload.encodeTarget(boxId))
            .build();

        // Unsigned but consensus-valid, and byte-for-byte stable across serialization.
        assertTrue(tx.signatureValid());
        var back = (rhizome.core.transaction.TransactionImpl)
            rhizome.core.transaction.Transaction.of(tx.serialize());
        assertEquals(TransactionKind.BOX_COLLECT, back.kind());
        assertEquals(PublicAddress.empty(), back.from());
        assertArrayEquals(boxId, BoxPayload.decode(TransactionKind.BOX_COLLECT, back.data(), 6).boxId());
        assertEquals(tx.hashContents(), back.hashContents());
    }

    @Test
    void transferSerializationUnchangedByBoxKinds() {
        // A plain transfer must still add exactly one byte (the kind) over the fixed prefix.
        var transfer = rhizome.core.transaction.TransactionImpl.builder()
            .from(owner).to(PublicAddress.random())
            .amount(new rhizome.core.transaction.TransactionAmount(1))
            .signingKey(rhizome.crypto.PublicKey.empty())
            .build();
        int size = transfer.serialize().getSize();
        assertEquals(rhizome.core.transaction.dto.TransactionDto.FIXED_SIZE + 1, size);
        assertFalse(TransactionKind.TRANSFER.hasPayload());
        assertTrue(TransactionKind.BOX_CREATE.hasPayload());
        assertFalse(TransactionKind.BOX_CREATE.isContract());
        assertTrue(TransactionKind.BOX_CREATE.isBox());
    }
}
