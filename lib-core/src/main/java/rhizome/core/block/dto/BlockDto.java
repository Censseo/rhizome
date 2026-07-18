package rhizome.core.block.dto;

import java.nio.ByteBuffer;

import org.jetbrains.annotations.NotNull;

import lombok.Getter;
import rhizome.core.crypto.SHA256Hash;
import rhizome.core.serialization.BinaryIO;
import rhizome.core.serialization.BinarySerializable;

/**
 * Wire/storage form of a block header. Fixed big-endian layout
 * ({@link #BUFFER_SIZE} bytes), hand-written for determinism and native-image
 * friendliness: {@code id(4) || timestamp(8) || difficulty(4) ||
 * numTransactions(4) || lastBlockHash(32) || merkleRoot(32) || nonce(32)}.
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

    public static final int BUFFER_SIZE =
        Integer.BYTES + Long.BYTES + Integer.BYTES + Integer.BYTES
        + SHA256Hash.SIZE + SHA256Hash.SIZE + SHA256Hash.SIZE;

    public BlockDto(
        int id,
        long timestamp,
        int difficulty,
        int numTransactions,
        SHA256Hash lastBlockHash,
        SHA256Hash merkleRoot,
        SHA256Hash nonce) {

        this.id = id;
        this.timestamp = timestamp;
        this.difficulty = difficulty;
        this.numTransactions = numTransactions;
        this.lastBlockHash = lastBlockHash;
        this.merkleRoot = merkleRoot;
        this.nonce = nonce;
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
    }

    public static BlockDto readFrom(ByteBuffer buffer) {
        int id = buffer.getInt();
        long timestamp = buffer.getLong();
        int difficulty = buffer.getInt();
        int numTransactions = buffer.getInt();
        SHA256Hash lastBlockHash = SHA256Hash.of(BinaryIO.getFixed(buffer, SHA256Hash.SIZE));
        SHA256Hash merkleRoot = SHA256Hash.of(BinaryIO.getFixed(buffer, SHA256Hash.SIZE));
        SHA256Hash nonce = SHA256Hash.of(BinaryIO.getFixed(buffer, SHA256Hash.SIZE));
        return new BlockDto(id, timestamp, difficulty, numTransactions, lastBlockHash, merkleRoot, nonce);
    }

    @Override
    public @NotNull int getSize() {
        return BUFFER_SIZE;
    }
}
