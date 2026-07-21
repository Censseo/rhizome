package rhizome.core.block;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import rhizome.core.block.dto.BlockDto;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.dto.TransactionDto;

/**
 * Fixed-layout codec for a whole block (header + transactions), reusing the
 * per-object {@link BlockDto}/{@link TransactionDto} codecs:
 * {@code header(BlockDto.BUFFER_SIZE) || tx[0] || tx[1] || ...}, where the
 * transaction count is carried in the header. Used to store a full block as a
 * single value and to frame blocks on the wire.
 */
public final class BlockCodec {

    private BlockCodec() {}

    public static byte[] encode(Block block) {
        BlockDto header = block.serialize();
        List<Transaction> transactions = block.transactions();
        List<UncleRef> uncles = block.uncles();
        // Transactions are variable length (contract payloads), so sum their sizes.
        int size = BlockDto.BUFFER_SIZE;
        TransactionDto[] dtos = new TransactionDto[transactions.size()];
        for (int i = 0; i < dtos.length; i++) {
            dtos[i] = transactions.get(i).serialize();
            size += dtos[i].getSize();
        }
        // Each uncle: hash (32) + difficulty (4) + miner address (25).
        int uncleSize = rhizome.core.crypto.SHA256Hash.SIZE + Integer.BYTES + rhizome.core.ledger.PublicAddress.SIZE;
        size += Integer.BYTES + uncles.size() * uncleSize;

        ByteBuffer buffer = ByteBuffer.allocate(size);
        header.writeTo(buffer);
        for (TransactionDto dto : dtos) {
            dto.writeTo(buffer);
        }
        buffer.putInt(uncles.size());
        for (UncleRef uncle : uncles) {
            buffer.put(uncle.hash().hash().getArray());
            buffer.putInt(uncle.difficulty());
            buffer.put(uncle.miner().toBytes());
        }
        return buffer.array();
    }

    public static Block decode(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        Block block = decode(buffer);
        // A single-object decode must consume the whole buffer: trailing bytes mean two distinct
        // wire encodings map to the same block, a malleability source for any code that keys on raw
        // stored bytes (audit L2). decodeAll uses the streaming decode(ByteBuffer) and is exempt.
        if (buffer.hasRemaining()) {
            throw new IllegalArgumentException("trailing bytes after block: " + buffer.remaining());
        }
        return block;
    }

    private static Block decode(ByteBuffer buffer) {
        // BlockDto.readFrom already bounds numTransactions, so this pre-size is safe.
        BlockDto header = BlockDto.readFrom(buffer);

        List<Transaction> transactions = new ArrayList<>(header.numTransactions());
        for (int i = 0; i < header.numTransactions(); i++) {
            transactions.add(Transaction.of(TransactionDto.readFrom(buffer)));
        }
        List<UncleRef> uncles = new ArrayList<>();
        int numUncles = buffer.getInt();
        // Reject an out-of-range uncle count before iterating: consensus caps uncles at
        // maxUnclesPerBlock (2); a raw wire int must never drive an unbounded loop/alloc.
        if (numUncles < 0 || numUncles > rhizome.core.common.Constants.MAX_UNCLES_PER_BLOCK) {
            throw new IllegalArgumentException("numUncles out of range: " + numUncles);
        }
        for (int i = 0; i < numUncles; i++) {
            byte[] h = new byte[rhizome.core.crypto.SHA256Hash.SIZE];
            buffer.get(h);
            int difficulty = buffer.getInt();
            byte[] m = new byte[rhizome.core.ledger.PublicAddress.SIZE];
            buffer.get(m);
            uncles.add(new UncleRef(rhizome.core.crypto.SHA256Hash.of(h), difficulty,
                rhizome.core.ledger.PublicAddress.of(m)));
        }
        return Block.of(header, transactions, uncles);
    }

    /**
     * Decodes a concatenation of blocks (each self-delimiting via its header's
     * transaction count), as served by the {@code /sync} endpoint.
     */
    public static List<Block> decodeAll(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        List<Block> blocks = new ArrayList<>();
        while (buffer.hasRemaining()) {
            blocks.add(decode(buffer));
        }
        return blocks;
    }

    /**
     * Streams a concatenation of blocks from {@code in}, decoding one block at a time so peak
     * transient memory is bounded to a single block (≤ {@code maxBlockBytes}) rather than the
     * whole response — the fix for the ~800 MiB single-shot {@code /sync} buffer (audit M5).
     * Aborts if the stream yields more than {@code maxBlocks} blocks or a single block exceeds
     * {@code maxBlockBytes}, so a hostile peer cannot force an unbounded allocation.
     */
    public static List<Block> decodeStreamed(java.io.InputStream in, int maxBlocks, int maxBlockBytes)
            throws java.io.IOException {
        List<Block> blocks = new ArrayList<>();
        byte[] carry = new byte[0];
        final int chunk = 64 * 1024;
        while (true) {
            // Try to decode as many complete blocks as the carry currently holds.
            ByteBuffer buffer = ByteBuffer.wrap(carry);
            boolean progressed = true;
            while (buffer.hasRemaining() && progressed) {
                int mark = buffer.position();
                try {
                    Block block = decode(buffer);
                    if (blocks.size() >= maxBlocks) {
                        throw new java.io.IOException("sync stream exceeds " + maxBlocks + " blocks");
                    }
                    blocks.add(block);
                } catch (java.nio.BufferUnderflowException incomplete) {
                    buffer.position(mark); // partial trailing block: keep it, read more
                    progressed = false;
                }
            }
            carry = java.util.Arrays.copyOfRange(carry, buffer.position(), carry.length);
            if (carry.length > maxBlockBytes) {
                throw new java.io.IOException("sync stream single block exceeds " + maxBlockBytes + " bytes");
            }
            byte[] more = in.readNBytes(chunk);
            if (more.length == 0) {
                break; // stream exhausted
            }
            byte[] merged = new byte[carry.length + more.length];
            System.arraycopy(carry, 0, merged, 0, carry.length);
            System.arraycopy(more, 0, merged, carry.length, more.length);
            carry = merged;
        }
        if (carry.length != 0) {
            throw new java.io.IOException("sync stream ended mid-block (" + carry.length + " trailing bytes)");
        }
        return blocks;
    }
}
