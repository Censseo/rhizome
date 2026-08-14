package rhizome;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPairTyped;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.block.dto.BlockDto;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import rhizome.crypto.SHA256Hash;
import rhizome.crypto.SignatureScheme;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.dto.TransactionDto;

/**
 * Locks the hand-written fixed-layout codec that replaced the ActiveJ
 * runtime-codegen serializer on the core objects: exact byte sizes and lossless
 * round-trips (incl. the coinbase case with empty signature/signing key).
 */
class BinaryCodecTest {

    @Test
    void fixedBufferSizes() {
        // scheme(1) + signature(64) + signingKey(32) + the scheme-independent common fields.
        assertEquals(1 + 64 + 32 + 8 + 25 + 8 + 8 + 1 + 4 + 8, TransactionDto.FIXED_SIZE);
        assertEquals(4 + 8 + 4 + 4 + 32 + 32 + 32 + 32 + 4, BlockDto.BUFFER_SIZE); // + stateRoot(32) + vote(4)
    }

    /**
     * The widest prefix must cover every implemented scheme, so buffer and request-body caps sized
     * against it cannot under-allocate. Locks the derivation rather than a literal: adding a scheme
     * with larger fields must move this bound automatically, not silently leave it stale.
     */
    @Test
    void maxFixedSizeCoversEveryScheme() {
        for (SignatureScheme scheme : SignatureScheme.values()) {
            assertTrue(TransactionDto.fixedSize(scheme) <= TransactionDto.MAX_FIXED_SIZE,
                scheme + " exceeds MAX_FIXED_SIZE");
        }
        assertEquals(1 + 64 + 32 + 32 + 8 + 25 + 8 + 8 + 1 + 4 + 8,
            TransactionDto.fixedSize(SignatureScheme.ED25519_PQC));
    }

    @Test
    void signedTransactionRoundTripByteExact() {
        var pair = generateKeyPairTyped();
        var key = pair.publicKey();
        Transaction t = Transaction.of(PublicAddress.of(key), PublicAddress.random(),
            new TransactionAmount(1234), key, new TransactionAmount(7), 999L, 3, 11);
        t.sign(pair.privateKey());

        TransactionDto dto = t.serialize();
        byte[] bytes = dto.toBuffer();
        // A transfer is the fixed prefix plus the one-byte kind tag.
        assertEquals(TransactionDto.FIXED_SIZE + 1, bytes.length);

        TransactionDto restored = TransactionDto.fromBuffer(bytes);
        assertArrayEquals(bytes, restored.toBuffer());
        assertEquals(3, restored.chainId());
        assertEquals(11, restored.nonce());
        assertEquals(1234, restored.amount());
    }

    @Test
    void strictSingleObjectDecodeRejectsTrailingBytes() {
        // The single-object entry (used by POST /add_transaction) must consume the whole buffer, so a
        // wire tx has a unique form (audit codec P7 parity). Multi-object buffers are decoded through
        // the codecs' readFrom(ByteBuffer), never through this entry point.
        var pair = generateKeyPairTyped();
        var key = pair.publicKey();
        Transaction t = Transaction.of(PublicAddress.of(key), PublicAddress.random(),
            new TransactionAmount(1234), key, new TransactionAmount(7), 999L, 3, 11);
        t.sign(pair.privateKey());
        byte[] bytes = t.serialize().toBuffer();
        byte[] withTrailer = java.util.Arrays.copyOf(bytes, bytes.length + 1);

        assertDoesNotThrow(() -> TransactionDto.fromBuffer(bytes));
        assertThrows(IllegalArgumentException.class,
            () -> TransactionDto.fromBuffer(withTrailer));
    }

    @Test
    void contractTransactionRoundTripPreservesKindDataAndGas() {
        var pair = generateKeyPairTyped();
        var key = pair.publicKey();
        byte[] code = new byte[] {0x00, 0x61, 0x73, 0x6d, 1, 2, 3, 4, 5};
        Transaction t = rhizome.core.transaction.TransactionImpl.builder()
            .from(PublicAddress.of(key)).to(PublicAddress.random())
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(0))
            .chainId(3).nonce(7).signingKey(key)
            .kind(rhizome.core.transaction.TransactionKind.DEPLOY)
            .data(code).gasLimit(500_000).gasPrice(2)
            .build();
        t.sign(pair.privateKey());

        // Signature covers the contract fields (they are in the preimage).
        assertEquals(true, t.signatureValid());

        // Wire round-trip: variable length, self-delimiting, byte-exact.
        byte[] bytes = t.serialize().toBuffer();
        assertEquals(TransactionDto.FIXED_SIZE + 1 + 8 + 8 + 4 + code.length, bytes.length);
        var restoredDto = TransactionDto.fromBuffer(bytes);
        var restored = (rhizome.core.transaction.TransactionImpl) Transaction.of(restoredDto);
        assertEquals(rhizome.core.transaction.TransactionKind.DEPLOY, restored.kind());
        assertArrayEquals(code, restored.data());
        assertEquals(500_000, restored.gasLimit());
        assertEquals(2, restored.gasPrice());

        // JSON round-trip preserves the contract fields too.
        var fromJson = (rhizome.core.transaction.TransactionImpl) Transaction.of(t.toJson());
        assertEquals(rhizome.core.transaction.TransactionKind.DEPLOY, fromJson.kind());
        assertArrayEquals(code, fromJson.data());
        assertEquals(500_000, fromJson.gasLimit());
    }

    @Test
    void coinbaseTransactionRoundTrip() {
        // Empty signature and signing key must serialise to fixed zero-filled fields.
        Transaction coinbase = Transaction.of(PublicAddress.random(), new TransactionAmount(50_0000));
        TransactionDto dto = coinbase.serialize();
        byte[] bytes = dto.toBuffer();
        assertEquals(TransactionDto.FIXED_SIZE + 1, bytes.length);

        TransactionDto restored = TransactionDto.fromBuffer(bytes);
        assertEquals(true, restored.isTransactionFee());
        assertArrayEquals(bytes, restored.toBuffer());
    }

    @Test
    void blockHeaderRoundTripByteExact() {
        var block = (BlockImpl) BlockImpl.builder()
            .id(4242).timestamp(1234567890L).difficulty(22)
            .merkleRoot(SHA256Hash.random())
            .lastBlockHash(SHA256Hash.random())
            .nonce(SHA256Hash.random())
            .build();
        block.addTransaction(Transaction.of(PublicAddress.random(), new TransactionAmount(1)));

        BlockDto dto = block.serialize();
        byte[] bytes = dto.toBuffer();
        assertEquals(BlockDto.BUFFER_SIZE, bytes.length);

        BlockDto restored = BlockDto.fromBuffer(bytes);
        assertEquals(4242, restored.id());
        assertEquals(1234567890L, restored.timestamp());
        assertEquals(22, restored.difficulty());
        assertEquals(1, restored.numTransactions());
        assertArrayEquals(bytes, restored.toBuffer());
    }

    @Test
    void blockDtoReflectsTransactionCount() {
        var block = (BlockImpl) Block.empty();
        block.addTransaction(Transaction.of(PublicAddress.random(), new TransactionAmount(1)));
        block.addTransaction(Transaction.of(PublicAddress.random(), new TransactionAmount(2)));
        assertEquals(2, block.serialize().numTransactions());
    }
}
