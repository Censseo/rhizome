package rhizome.core.block.dto;

import java.nio.ByteBuffer;

import org.jetbrains.annotations.NotNull;

import lombok.Getter;
import rhizome.crypto.SHA256Hash;
import rhizome.core.serialization.BinaryIO;
import rhizome.core.serialization.BinarySerializable;

/**
 * Wire/storage form of a block header. Fixed big-endian layout
 * ({@link #BUFFER_SIZE} bytes), hand-written for determinism and native-image
 * friendliness: {@code id(4) || timestamp(8) || difficulty(4) ||
 * numTransactions(4) || lastBlockHash(32) || merkleRoot(32) || nonce(32) ||
 * stateRoot(32)}. The state root is carried on the wire always (zero when the
 * producer runs without the accumulator), so the header format is fixed once.
 */
@Getter
public class BlockDto implements BinarySerializable {
    public final int id;
    public final long timestamp;
    public final int difficulty;
    public final int numTransactions;
    public final SHA256Hash lastBlockHash;
    public final SHA256Hash merkleRoot;
    public final SHA256Hash nonce;
    public final SHA256Hash stateRoot;
    public final int vote;

    public static final int BUFFER_SIZE =
        Integer.BYTES + Long.BYTES + Integer.BYTES + Integer.BYTES
        + SHA256Hash.SIZE + SHA256Hash.SIZE + SHA256Hash.SIZE + SHA256Hash.SIZE + Integer.BYTES;

    public BlockDto(
        int id,
        long timestamp,
        int difficulty,
        int numTransactions,
        SHA256Hash lastBlockHash,
        SHA256Hash merkleRoot,
        SHA256Hash nonce) {
        this(id, timestamp, difficulty, numTransactions, lastBlockHash, merkleRoot, nonce, SHA256Hash.empty(), 0);
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

        this.id = id;
        this.timestamp = timestamp;
        this.difficulty = difficulty;
        this.numTransactions = numTransactions;
        this.lastBlockHash = lastBlockHash;
        this.merkleRoot = merkleRoot;
        this.nonce = nonce;
        this.stateRoot = stateRoot;
        this.vote = vote;
    }

    @Override
    public void writeTo(ByteBuffer buffer) {
        buffer.putInt(id);
        buffer.putLong(timestamp);
        buffer.putInt(difficulty);
        buffer.putInt(numTransactions);
        BinaryIO.putFixed(buffer, lastBlockHash.toBytes(), SHA256Hash.SIZE);
        BinaryIO.putFixed(buffer, merkleRoot.toBytes(), SHA256Hash.SIZE);
        BinaryIO.putFixed(buffer, nonce.toBytes(), SHA256Hash.SIZE);
        BinaryIO.putFixed(buffer, stateRoot.toBytes(), SHA256Hash.SIZE);
        buffer.putInt(vote);
    }

    public static BlockDto readFrom(ByteBuffer buffer) {
        int id = buffer.getInt();
        long timestamp = buffer.getLong();
        int difficulty = buffer.getInt();
        // Bound the block's own difficulty at decode, like uncleDifficulty already is: it feeds
        // checkLeadingZeroBits (challenge past 256 bits) and BigInteger.TWO.pow(difficulty) in the
        // work sums, so an unbounded/negative wire int would otherwise reach those as an
        // out-of-range index or an astronomically large allocation (audit).
        if (difficulty < 0 || difficulty > rhizome.core.common.Constants.MAX_DIFFICULTY) {
            throw new IllegalArgumentException("difficulty out of range: " + difficulty);
        }
        int numTransactions = buffer.getInt();
        // Bound the attacker-controlled count BEFORE any caller pre-sizes a collection
        // from it (BlockCodec did `new ArrayList<>(numTransactions)`): a raw wire int of
        // 0x7FFFFFFF would otherwise allocate gigabytes and OOM the node on decode.
        if (numTransactions < 0 || numTransactions > rhizome.core.common.Constants.MAX_TRANSACTIONS_PER_BLOCK) {
            throw new IllegalArgumentException("numTransactions out of range: " + numTransactions);
        }
        SHA256Hash lastBlockHash = SHA256Hash.of(BinaryIO.getFixed(buffer, SHA256Hash.SIZE));
        SHA256Hash merkleRoot = SHA256Hash.of(BinaryIO.getFixed(buffer, SHA256Hash.SIZE));
        SHA256Hash nonce = SHA256Hash.of(BinaryIO.getFixed(buffer, SHA256Hash.SIZE));
        SHA256Hash stateRoot = SHA256Hash.of(BinaryIO.getFixed(buffer, SHA256Hash.SIZE));
        int vote = buffer.getInt();
        return new BlockDto(id, timestamp, difficulty, numTransactions, lastBlockHash, merkleRoot, nonce,
            stateRoot, vote);
    }

    @Override
    public @NotNull int getSize() {
        return BUFFER_SIZE;
    }
}
