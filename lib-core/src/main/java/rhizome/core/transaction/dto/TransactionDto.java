package rhizome.core.transaction.dto;

import java.nio.ByteBuffer;

import org.jetbrains.annotations.NotNull;

import lombok.Getter;
import lombok.experimental.Accessors;
import rhizome.crypto.PublicKey;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.serialization.BinaryIO;
import rhizome.core.serialization.BinarySerializable;
import rhizome.core.transaction.TransactionSignature;

/**
 * Wire/storage form of a transaction. The fixed prefix ({@link #FIXED_SIZE} bytes)
 * is unchanged from the transfer-only layout:
 * {@code signature(64) || signingKey(32) || timestamp(8) || to(25) || amount(8)
 * || fee(8) || isTransactionFee(1) || chainId(4) || nonce(8)}. It is followed by a
 * one-byte {@code kind}; for a contract transaction (kind != TRANSFER) that byte is
 * followed by {@code gasLimit(8) || gasPrice(8) || dataLen(4) || data}. A transfer
 * therefore adds exactly one byte over the old format, and every transaction is
 * self-delimiting so a block can pack variable-length transactions back to back.
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
    public final byte kind;
    public final long gasLimit;
    public final long gasPrice;
    public final byte[] data;

    /** Size of the fixed transfer prefix (excludes the kind byte and any contract suffix). */
    public static final int FIXED_SIZE =
        TransactionSignature.SIZE + PublicKey.SIZE + Long.BYTES + PublicAddress.SIZE
        + Long.BYTES + Long.BYTES + 1 + Integer.BYTES + Long.BYTES;

    /** Back-compat alias: the fixed prefix size, used by callers sizing buffers/caps. */
    public static final int BUFFER_SIZE = FIXED_SIZE;

    /** Hard cap on contract payload bytes on the wire. */
    public static final int MAX_DATA = 128 * 1024;

    private static final byte KIND_TRANSFER = 0;

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
        this(signature, signingKey, timestamp, to, amount, fee, isTransactionFee, chainId, nonce,
            KIND_TRANSFER, 0, 0, new byte[0]);
    }

    public TransactionDto(
        TransactionSignature signature,
        PublicKey signingKey,
        long timestamp,
        PublicAddress to,
        long amount,
        long fee,
        boolean isTransactionFee,
        int chainId,
        long nonce,
        byte kind,
        long gasLimit,
        long gasPrice,
        byte[] data) {

        this.signature = signature;
        this.signingKey = signingKey;
        this.timestamp = timestamp;
        this.to = to;
        this.amount = amount;
        this.fee = fee;
        this.isTransactionFee = isTransactionFee;
        this.chainId = chainId;
        this.nonce = nonce;
        this.kind = kind;
        this.gasLimit = gasLimit;
        this.gasPrice = gasPrice;
        this.data = data == null ? new byte[0] : data;
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
        buffer.put(kind);
        if (kind != KIND_TRANSFER) {
            buffer.putLong(gasLimit);
            buffer.putLong(gasPrice);
            buffer.putInt(data.length);
            buffer.put(data);
        }
    }

    public static TransactionDto readFrom(ByteBuffer buffer) {
        TransactionSignature signature = TransactionSignature.of(BinaryIO.getFixed(buffer, TransactionSignature.SIZE));
        PublicKey signingKey = PublicKey.of(BinaryIO.getFixed(buffer, PublicKey.SIZE));
        long timestamp = buffer.getLong();
        PublicAddress to = PublicAddress.of(BinaryIO.getFixed(buffer, PublicAddress.SIZE));
        long amount = buffer.getLong();
        long fee = buffer.getLong();
        // Canonical decode: writeTo emits exactly 0 or 1, so a byte in 2..255 is a non-canonical
        // encoding of the same logical transaction (255 wire forms for one flag). Harmless for the
        // txid today (it hashes the boolean, not the raw byte) but a latent malleability source if
        // any future code ever hashes the raw bytes — reject it so the wire form is unique (audit L1).
        int feeFlag = buffer.get() & 0xFF;
        if (feeFlag > 1) {
            throw new IllegalArgumentException("non-canonical isTransactionFee byte: " + feeFlag);
        }
        boolean isTransactionFee = feeFlag != 0;
        int chainId = buffer.getInt();
        long nonce = buffer.getLong();
        byte kind = buffer.get();
        long gasLimit = 0;
        long gasPrice = 0;
        byte[] data = new byte[0];
        if (kind != KIND_TRANSFER) {
            gasLimit = buffer.getLong();
            gasPrice = buffer.getLong();
            int len = buffer.getInt();
            if (len < 0 || len > MAX_DATA) {
                throw new IllegalArgumentException("contract data length out of range: " + len);
            }
            data = new byte[len];
            buffer.get(data);
        }
        return new TransactionDto(signature, signingKey, timestamp, to, amount, fee, isTransactionFee,
            chainId, nonce, kind, gasLimit, gasPrice, data);
    }

    @Override
    public @NotNull int getSize() {
        int size = FIXED_SIZE + 1;
        if (kind != KIND_TRANSFER) {
            size += Long.BYTES + Long.BYTES + Integer.BYTES + data.length;
        }
        return size;
    }
}
