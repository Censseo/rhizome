package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
