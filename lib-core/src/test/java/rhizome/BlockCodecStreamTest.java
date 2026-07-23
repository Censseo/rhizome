package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockCodec;
import rhizome.core.block.BlockImpl;
import rhizome.core.common.Constants;
import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

/** Streamed multi-block decode: correct round-trip and the anti-DoS abort bounds (audit V6b). */
class BlockCodecStreamTest {

    private static BlockImpl block(int id) {
        var b = (BlockImpl) BlockImpl.builder().id(id).timestamp(1000L + id).difficulty(4)
            .lastBlockHash(SHA256Hash.random()).build();
        b.addTransaction(Transaction.of(PublicAddress.random(), new TransactionAmount(50)));
        MerkleTree tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(SHA256Hash.empty());
        return b;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    @Test
    void decodesAStreamSpanningManyReadChunks() throws IOException {
        // Exercises the offset buffer across the internal 512 KiB read chunk (audit P10): thousands of
        // blocks, with partial blocks straddling chunk boundaries, must round-trip in order.
        int n = 4000;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 2; i < 2 + n; i++) {
            out.writeBytes(BlockCodec.encode(block(i)));
        }
        byte[] stream = out.toByteArray();
        assertTrue(stream.length > 512 * 1024, "stream must exceed one read chunk to exercise the buffer");
        List<Block> decoded = BlockCodec.decodeStreamed(
            new ByteArrayInputStream(stream), n + 10, Constants.MAX_BLOCK_SIZE_BYTES);
        assertEquals(n, decoded.size());
        assertEquals(2, ((BlockImpl) decoded.get(0)).id());
        assertEquals(2 + n - 1, ((BlockImpl) decoded.get(n - 1)).id());
    }

    @Test
    void decodesASingleBlockLargerThanTheReadChunk() throws IOException {
        // The quadratic path P10 targets: one block that spans several read chunks, so its partial tail
        // is carried and compacted across reads. It must still decode to the identical block.
        var b = (BlockImpl) BlockImpl.builder().id(2).timestamp(1000L).difficulty(4)
            .lastBlockHash(SHA256Hash.random()).build();
        for (int i = 0; i < 6000; i++) {
            b.addTransaction(Transaction.of(PublicAddress.random(), new TransactionAmount(i)));
        }
        MerkleTree tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(SHA256Hash.empty());
        byte[] encoded = BlockCodec.encode(b);
        assertTrue(encoded.length > 512 * 1024, "block must exceed one read chunk");
        List<Block> decoded = BlockCodec.decodeStreamed(
            new ByteArrayInputStream(encoded), 10, Constants.MAX_BLOCK_SIZE_BYTES);
        assertEquals(1, decoded.size());
        assertEquals(6000, decoded.get(0).transactions().size());
        assertEquals(b.hash(), decoded.get(0).hash());
    }

    @Test
    void decodesAConcatenationOfBlocks() throws IOException {
        byte[] stream = concat(BlockCodec.encode(block(2)), BlockCodec.encode(block(3)),
            BlockCodec.encode(block(4)));
        List<Block> out = BlockCodec.decodeStreamed(
            new ByteArrayInputStream(stream), 200, Constants.MAX_BLOCK_SIZE_BYTES);
        assertEquals(3, out.size());
        assertEquals(2, ((BlockImpl) out.get(0)).id());
        assertEquals(4, ((BlockImpl) out.get(2)).id());
    }

    @Test
    void abortsWhenTheStreamExceedsMaxBlocks() {
        byte[] stream = concat(BlockCodec.encode(block(2)), BlockCodec.encode(block(3)));
        assertThrows(IOException.class, () -> BlockCodec.decodeStreamed(
            new ByteArrayInputStream(stream), 1, Constants.MAX_BLOCK_SIZE_BYTES));
    }

    @Test
    void abortsOnAStreamThatEndsMidBlock() {
        byte[] one = BlockCodec.encode(block(2));
        byte[] truncated = java.util.Arrays.copyOf(one, one.length - 1);
        assertThrows(IOException.class, () -> BlockCodec.decodeStreamed(
            new ByteArrayInputStream(truncated), 200, Constants.MAX_BLOCK_SIZE_BYTES));
    }
}
