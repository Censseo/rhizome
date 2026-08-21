package rhizome.core.block.dto;

import java.nio.ByteBuffer;

import lombok.Getter;
import rhizome.crypto.SHA256Hash;
import rhizome.core.block.HeaderWire;
import rhizome.core.serialization.BinaryIO;

/**
 * Wire/storage form of a block header. Fixed big-endian layout
 * ({@link #BUFFER_SIZE} bytes), hand-written for determinism and native-image
 * friendliness: {@code id(4) || timestamp(8) || difficulty(4) ||
 * numTransactions(4) || lastBlockHash(32) || merkleRoot(32) || nonce(32) ||
 * stateRoot(32) || vote(4) || supply(8)}. The state root and supply are
 * carried on the wire always (absent sentinels when the producer does not
 * commit them), so the header format is fixed once.
 */
@Getter
public class BlockDto {
    public final int id;
    public final long timestamp;
    public final int difficulty;
    public final int numTransactions;
    public final SHA256Hash lastBlockHash;
    public final SHA256Hash merkleRoot;
    public final SHA256Hash nonce;
    public final SHA256Hash stateRoot;
    public final int vote;
    public final long supply;

    public static final int BUFFER_SIZE =
        Integer.BYTES + Long.BYTES + Integer.BYTES + Integer.BYTES
        + SHA256Hash.SIZE + SHA256Hash.SIZE + SHA256Hash.SIZE + SHA256Hash.SIZE + Integer.BYTES
        + Long.BYTES;

    public BlockDto(
        int id,
        long timestamp,
        int difficulty,
        int numTransactions,
        SHA256Hash lastBlockHash,
        SHA256Hash merkleRoot,
        SHA256Hash nonce) {
        // -1: BlockImpl.SUPPLY_ABSENT, spelled as a literal to avoid a package cycle back to
        // rhizome.core.block (BlockImpl itself imports this package's BlockDto).
        this(id, timestamp, difficulty, numTransactions, lastBlockHash, merkleRoot, nonce,
            SHA256Hash.empty(), 0, -1L);
    }

    public BlockDto(
        int id,
        long timestamp,
        int difficulty,
        int numTransactions,
        SHA256Hash lastBlockHash,
        SHA256Hash merkleRoot,
        SHA256Hash nonce,
        SHA256Hash stateRoot,
        int vote) {
        // -1: BlockImpl.SUPPLY_ABSENT (see the note on the 7-arg constructor above).
        this(id, timestamp, difficulty, numTransactions, lastBlockHash, merkleRoot, nonce,
            stateRoot, vote, -1L);
    }

    public BlockDto(
        int id,
        long timestamp,
        int difficulty,
        int numTransactions,
        SHA256Hash lastBlockHash,
        SHA256Hash merkleRoot,
        SHA256Hash nonce,
        SHA256Hash stateRoot,
        int vote,
        long supply) {

        this.id = id;
        this.timestamp = timestamp;
        this.difficulty = difficulty;
        this.numTransactions = numTransactions;
        this.lastBlockHash = lastBlockHash;
        this.merkleRoot = merkleRoot;
        this.nonce = nonce;
        this.stateRoot = stateRoot;
        this.vote = vote;
        this.supply = supply;
    }

    public void writeTo(ByteBuffer buffer) {
        HeaderWire.writePrefix(buffer, new HeaderWire.Prefix(id, timestamp, difficulty,
            numTransactions, lastBlockHash, merkleRoot, nonce, stateRoot, vote, supply));
    }

    public static BlockDto readFrom(ByteBuffer buffer) {
        HeaderWire.Prefix p = HeaderWire.readPrefix(buffer);
        return new BlockDto(p.id(), p.timestamp(), p.difficulty(), p.numTransactions(),
            p.lastBlockHash(), p.merkleRoot(), p.nonce(), p.stateRoot(), p.vote(), p.supply());
    }

    public int getSize() {
        return BUFFER_SIZE;
    }

    /** The fixed-layout wire form of one header. */
    public byte[] toBuffer() {
        ByteBuffer buffer = ByteBuffer.allocate(getSize());
        writeTo(buffer);
        return buffer.array();
    }

    /**
     * Strict single-object decode: the whole {@code buffer} must be exactly one header. Trailing
     * bytes are rejected so a wire object has a unique encoding, matching the strictness of
     * {@code BlockCodec.decode} (identity is content-hash, so this is wire-hygiene, not a
     * correctness fix). The packed block codec reads headers from a multi-object buffer via
     * {@link #readFrom(ByteBuffer)} instead.
     */
    public static BlockDto fromBuffer(byte[] buffer) {
        ByteBuffer bb = ByteBuffer.wrap(buffer);
        BlockDto result = readFrom(bb);
        if (bb.hasRemaining()) {
            throw new IllegalArgumentException(
                "trailing bytes after BlockDto (" + bb.remaining() + " left)");
        }
        return result;
    }
}
