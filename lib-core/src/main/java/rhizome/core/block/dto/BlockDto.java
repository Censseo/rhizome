package rhizome.core.block.dto;

import java.nio.ByteBuffer;

import org.jetbrains.annotations.NotNull;

import lombok.Getter;
import rhizome.crypto.SHA256Hash;
import rhizome.core.block.HeaderWire;
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
        HeaderWire.writePrefix(buffer, new HeaderWire.Prefix(id, timestamp, difficulty,
            numTransactions, lastBlockHash, merkleRoot, nonce, stateRoot, vote));
    }

    public static BlockDto readFrom(ByteBuffer buffer) {
        HeaderWire.Prefix p = HeaderWire.readPrefix(buffer);
        return new BlockDto(p.id(), p.timestamp(), p.difficulty(), p.numTransactions(),
            p.lastBlockHash(), p.merkleRoot(), p.nonce(), p.stateRoot(), p.vote());
    }

    @Override
    public @NotNull int getSize() {
        return BUFFER_SIZE;
    }
}
