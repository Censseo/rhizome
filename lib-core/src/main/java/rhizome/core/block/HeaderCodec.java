package rhizome.core.block;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.serialization.BinaryIO;

/**
 * Fixed-layout binary codec for a {@link BlockHeader}, self-delimiting via its
 * {@code uncleCount}:
 *
 * <pre>
 * id(4) ‖ timestamp(8) ‖ difficulty(4) ‖ numTransactions(4)
 *   ‖ lastBlockHash(32) ‖ merkleRoot(32) ‖ nonce(32) ‖ stateRoot(32) ‖ vote(4) ‖ supply(8)
 *   ‖ uncleCount(4) ‖ [uncleHash(32) ‖ uncleDifficulty(4) ‖ uncleMiner(25)]*
 * </pre>
 *
 * <p>The layout mirrors {@link rhizome.core.block.dto.BlockDto} (so a header
 * decodes the same bytes a block header would) and then appends the uncle
 * references, which the header hash commits to. A concatenation of headers is
 * self-framing, which is what the {@code /headers} endpoint streams.
 */
public final class HeaderCodec {

    private HeaderCodec() {}

    /** Bytes for the fixed prefix (everything up to and including {@code uncleCount}). */
    public static final int FIXED_PREFIX = HeaderWire.PREFIX_BYTES + Integer.BYTES;

    /** Bytes for one uncle record. */
    public static final int UNCLE_SIZE = HeaderWire.UNCLE_BYTES;

    public static int sizeOf(BlockHeader header) {
        return FIXED_PREFIX + header.uncles().size() * UNCLE_SIZE;
    }

    public static byte[] encode(BlockHeader header) {
        ByteBuffer buffer = ByteBuffer.allocate(sizeOf(header));
        writeTo(buffer, header);
        return buffer.array();
    }

    public static void writeTo(ByteBuffer buffer, BlockHeader header) {
        HeaderWire.writePrefix(buffer, new HeaderWire.Prefix(
            header.id(), header.timestamp(), header.difficulty(), header.numTransactions(),
            header.lastBlockHash(), header.merkleRoot(), header.nonce(), header.stateRoot(),
            header.vote(), header.supply()));
        List<UncleRef> uncles = header.uncles();
        buffer.putInt(uncles.size());
        for (UncleRef uncle : uncles) {
            HeaderWire.writeUncle(buffer, uncle);
        }
    }

    public static BlockHeader decode(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        BlockHeader header = readFrom(buffer);
        // Single-object decode must consume the whole buffer; trailing bytes are a non-canonical
        // wire form / latent malleability (audit L2). Streamed multi-header decode uses readFrom.
        if (buffer.hasRemaining()) {
            throw new IllegalArgumentException("trailing bytes after header: " + buffer.remaining());
        }
        return header;
    }

    public static BlockHeader readFrom(ByteBuffer buffer) {
        HeaderWire.Prefix p = HeaderWire.readPrefix(buffer);
        int numUncles = HeaderWire.readUncleCount(buffer);
        List<UncleRef> uncles = new ArrayList<>(numUncles);
        for (int i = 0; i < numUncles; i++) {
            uncles.add(HeaderWire.readUncle(buffer));
        }
        return new BlockHeader(p.id(), p.timestamp(), p.difficulty(), p.numTransactions(),
            p.lastBlockHash(), p.merkleRoot(), p.nonce(), p.stateRoot(), p.vote(), p.supply(),
            uncles);
    }

    /** Decodes a self-framing concatenation of headers (as served by {@code /headers}). */
    public static List<BlockHeader> decodeAll(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        List<BlockHeader> headers = new ArrayList<>();
        while (buffer.hasRemaining()) {
            headers.add(readFrom(buffer));
        }
        return headers;
    }
}
