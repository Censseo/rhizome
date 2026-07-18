package rhizome.core.transaction.dto;

import org.jetbrains.annotations.NotNull;

import io.activej.serializer.annotations.Deserialize;
import io.activej.serializer.annotations.Serialize;
import lombok.Getter;
import lombok.experimental.Accessors;
import rhizome.core.crypto.PublicKey;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.serialization.BinarySerializable;
import rhizome.core.transaction.TransactionSignature;

@Accessors(fluent = true) @Getter
public class TransactionDto implements BinarySerializable {
    @Serialize public final TransactionSignature signature;
    @Serialize public final PublicKey signingKey;
    @Serialize public final long timestamp;
    @Serialize public final PublicAddress to;
    @Serialize public final long amount;
    @Serialize public final long fee;
    @Serialize public final boolean isTransactionFee;
    @Serialize public final int chainId;
    @Serialize public final long nonce;

    public static final int BUFFER_SIZE = 149 + Integer.BYTES + Long.BYTES;

    public TransactionDto(
        @Deserialize("signature") TransactionSignature signature,
        @Deserialize("signingKey") PublicKey signingKey,
        @Deserialize("timestamp") long timestamp,
        @Deserialize("to") PublicAddress to,
        @Deserialize("amount") long amount,
        @Deserialize("fee") long fee,
        @Deserialize("isTransactionFee") boolean isTransactionFee,
        @Deserialize("chainId") int chainId,
        @Deserialize("nonce") long nonce) {

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
    public @NotNull int getSize() {
        return BUFFER_SIZE;
    }
}
