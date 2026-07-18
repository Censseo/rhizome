package rhizome.core.transaction.dto;

import java.nio.ByteBuffer;

import org.jetbrains.annotations.NotNull;

import lombok.Getter;
import lombok.experimental.Accessors;
import rhizome.core.crypto.PublicKey;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.serialization.BinaryIO;
import rhizome.core.serialization.BinarySerializable;
import rhizome.core.transaction.TransactionSignature;

/**
 * Wire/storage form of a transaction. Fixed big-endian layout ({@link #BUFFER_SIZE}
 * bytes), hand-written for determinism and native-image friendliness:
 * {@code signature(64) || signingKey(32) || timestamp(8) || to(25) || amount(8)
 * || fee(8) || isTransactionFee(1) || chainId(4) || nonce(8)}.
 */
@Accessors(fluent = true) @Getter
public class TransactionDto implements BinarySerializable {
    public final TransactionSignature signature;
    public final PublicKey signingKey;
    public final long timestamp;
    public final PublicAddress to;
    public final long amount;
    public final long fee;
    public final boolean isTransactionFee;
    public final int chainId;
    public final long nonce;

    public static final int BUFFER_SIZE =
        TransactionSignature.SIZE + PublicKey.SIZE + Long.BYTES + PublicAddress.SIZE
        + Long.BYTES + Long.BYTES + 1 + Integer.BYTES + Long.BYTES;

    public TransactionDto(
        TransactionSignature signature,
        PublicKey signingKey,
        long timestamp,
        PublicAddress to,
        long amount,
        long fee,
        boolean isTransactionFee,
        int chainId,
        long nonce) {

        this.signature = signature;
        this.signingKey = signingKey;
        this.timestamp = timestamp;
        this.to = to;
        this.amount = amount;
        this.fee = fee;
        this.isTransactionFee = isTransactionFee;
        this.chainId = chainId;
        this.nonce = nonce;
    }

    @Override
    public void writeTo(ByteBuffer buffer) {
        BinaryIO.putFixed(buffer, signature.toBytes(), TransactionSignature.SIZE);
        BinaryIO.putFixed(buffer, signingKey.toBytes(), PublicKey.SIZE);
        buffer.putLong(timestamp);
        BinaryIO.putFixed(buffer, to.toBytes(), PublicAddress.SIZE);
        buffer.putLong(amount);
        buffer.putLong(fee);
        buffer.put((byte) (isTransactionFee ? 1 : 0));
        buffer.putInt(chainId);
        buffer.putLong(nonce);
    }

    public static TransactionDto readFrom(ByteBuffer buffer) {
        TransactionSignature signature = TransactionSignature.of(BinaryIO.getFixed(buffer, TransactionSignature.SIZE));
        PublicKey signingKey = PublicKey.of(BinaryIO.getFixed(buffer, PublicKey.SIZE));
        long timestamp = buffer.getLong();
        PublicAddress to = PublicAddress.of(BinaryIO.getFixed(buffer, PublicAddress.SIZE));
        long amount = buffer.getLong();
        long fee = buffer.getLong();
        boolean isTransactionFee = buffer.get() != 0;
        int chainId = buffer.getInt();
        long nonce = buffer.getLong();
        return new TransactionDto(signature, signingKey, timestamp, to, amount, fee, isTransactionFee, chainId, nonce);
    }

    @Override
    public @NotNull int getSize() {
        return BUFFER_SIZE;
    }
}
