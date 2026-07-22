package rhizome;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockCodec;
import rhizome.core.block.BlockImpl;
import rhizome.core.block.HeaderCodec;
import rhizome.core.block.UncleRef;
import rhizome.core.block.dto.BlockDto;
import rhizome.core.common.Constants;
import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

/**
 * Regression guard for the decode-time OOM (H1/H2): attacker-controlled count and
 * uncle-difficulty fields must be rejected BEFORE any collection is pre-sized or
 * {@code BigInteger.pow} is called, so a tiny crafted header cannot allocate gigabytes.
 */
class CodecBoundsTest {

    private static byte[] header(int numTransactions, int uncleCountField, Integer singleUncleDifficulty) {
        int records = singleUncleDifficulty != null ? 1 : 0;
        ByteBuffer b = ByteBuffer.allocate(HeaderCodec.FIXED_PREFIX + records * HeaderCodec.UNCLE_SIZE);
        b.putInt(1);                 // id
        b.putLong(0L);               // timestamp
        b.putInt(0);                 // difficulty
        b.putInt(numTransactions);   // numTransactions
        b.put(new byte[32]);         // lastBlockHash
        b.put(new byte[32]);         // merkleRoot
        b.put(new byte[32]);         // nonce
        b.put(new byte[32]);         // stateRoot
        b.putInt(0);                 // vote
        b.putInt(uncleCountField);   // uncleCount
        if (singleUncleDifficulty != null) {
            b.put(new byte[32]);            // uncle hash
            b.putInt(singleUncleDifficulty); // uncle difficulty
            b.put(new byte[25]);            // uncle miner
        }
        return b.array();
    }

    @Test
    void wellFormedHeaderDecodes() {
        assertDoesNotThrow(() -> HeaderCodec.decode(header(0, 0, null)));
    }

    @Test
    void rejectsHugeTransactionCount() {
        assertThrows(IllegalArgumentException.class, () -> HeaderCodec.decode(header(Integer.MAX_VALUE, 0, null)));
        assertThrows(IllegalArgumentException.class, () -> HeaderCodec.decode(header(-1, 0, null)));
    }

    @Test
    void rejectsTrailingBytesAfterHeader() {
        // Single-object decode must consume the whole buffer: a valid header with one extra byte is
        // a non-canonical wire form and must be rejected, not silently accepted (audit L2).
        byte[] wellFormed = header(0, 0, null);
        byte[] withTrailer = java.util.Arrays.copyOf(wellFormed, wellFormed.length + 1);
        assertDoesNotThrow(() -> HeaderCodec.decode(wellFormed));
        assertThrows(IllegalArgumentException.class, () -> HeaderCodec.decode(withTrailer));
    }

    @Test
    void rejectsHugeUncleCount() {
        assertThrows(IllegalArgumentException.class, () -> HeaderCodec.decode(header(0, Integer.MAX_VALUE, null)));
        assertThrows(IllegalArgumentException.class, () -> HeaderCodec.decode(header(0, -1, null)));
    }

    @Test
    void rejectsHugeUncleDifficulty() {
        assertThrows(IllegalArgumentException.class, () -> HeaderCodec.decode(header(0, 1, Integer.MAX_VALUE)));
        assertThrows(IllegalArgumentException.class, () -> HeaderCodec.decode(header(0, 1, -1)));
    }

    @Test
    void blockCodecRejectsOutOfRangeUncleDifficulty() {
        // The full-block codec now bounds uncle difficulty like HeaderCodec (codec parity): a valid
        // difficulty round-trips, an out-of-range one is rejected at decode before it could reach
        // BigInteger.TWO.pow in validateUncles.
        assertDoesNotThrow(() -> BlockCodec.decode(BlockCodec.encode(blockWithUncleDifficulty(5))));
        assertThrows(IllegalArgumentException.class,
            () -> BlockCodec.decode(BlockCodec.encode(blockWithUncleDifficulty(Integer.MAX_VALUE))));
        assertThrows(IllegalArgumentException.class,
            () -> BlockCodec.decode(BlockCodec.encode(blockWithUncleDifficulty(-1))));
    }

    private static BlockImpl blockWithUncleDifficulty(int uncleDifficulty) {
        var b = (BlockImpl) BlockImpl.builder().id(2).timestamp(5000).difficulty(4)
            .lastBlockHash(SHA256Hash.empty())
            .uncles(java.util.List.of(new UncleRef(SHA256Hash.random(), uncleDifficulty, PublicAddress.random())))
            .build();
        b.addTransaction(Transaction.of(PublicAddress.random(), new TransactionAmount(50)));
        MerkleTree tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(SHA256Hash.empty());
        return b;
    }

    @Test
    void blockDtoRejectsHugeTransactionCount() {
        ByteBuffer b = ByteBuffer.allocate(BlockDto.BUFFER_SIZE);
        b.putInt(1);                 // id
        b.putLong(0L);               // timestamp
        b.putInt(0);                 // difficulty
        b.putInt(Integer.MAX_VALUE); // numTransactions (poison)
        b.put(new byte[32]);         // lastBlockHash
        b.put(new byte[32]);         // merkleRoot
        b.put(new byte[32]);         // nonce
        b.put(new byte[32]);         // stateRoot
        b.putInt(0);                 // vote
        b.flip();
        assertThrows(IllegalArgumentException.class, () -> BlockDto.readFrom(b));
        // Sanity: the constant the bound uses is the consensus tx cap.
        assertThrows(IllegalArgumentException.class,
            () -> HeaderCodec.decode(header(Constants.MAX_TRANSACTIONS_PER_BLOCK + 1, 0, null)));
    }
}
