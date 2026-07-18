package rhizome.core.transaction;

import java.util.Arrays;
import java.util.Objects;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.json.JSONObject;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;
import rhizome.core.crypto.PrivateKey;
import rhizome.core.crypto.PublicKey;
import rhizome.core.crypto.SHA256Hash;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.dto.TransactionDto;

import static rhizome.core.common.Crypto.signWithPrivateKey;
import static rhizome.core.common.Crypto.checkSignature;
import static rhizome.core.common.Utils.intToBytes;
import static rhizome.core.common.Utils.longToBytes;

@Data @Builder
public final class TransactionImpl implements Transaction, Comparable<Transaction> {
    
    @Builder.Default
    private PublicAddress from = PublicAddress.empty();

    @Builder.Default
    private PublicAddress to = PublicAddress.empty();

    private TransactionAmount amount;

    @Builder.Default
    private boolean isTransactionFee = false;

    @Builder.Default
    private long timestamp = System.currentTimeMillis();

    @Builder.Default
    private TransactionAmount fee = new TransactionAmount(0);

    /**
     * Network identifier ({@code NetworkParameters.chainId}). Part of the signed
     * preimage so a signature is only valid on the network it was produced for
     * (Pandanite had no such separation, allowing cross-network replay).
     */
    @Builder.Default
    private int chainId = 0;

    /**
     * Account sequence number: the sender's transaction count at signing time.
     * Part of the signed preimage, making every transaction unique regardless of
     * amount/recipient/timestamp collisions (Pandanite relied on the timestamp,
     * so two identical sends in the same instant were "the same" transaction).
     */
    @Builder.Default
    private long nonce = 0;

    @Builder.Default
    private PublicKey signingKey = PublicKey.empty();

    @Builder.Default
    private TransactionSignature signature = TransactionSignature.empty();

    /**
     * Serialization
     */
    public TransactionDto serialize() {
       return serialize(this);
    }

    public JSONObject toJson() {
        return toJson(this);
    }
    
    public boolean signatureValid() {
        if (isTransactionFee()) return true;
        return checkSignature(hashContents().toBytes(), this.signature.toBytes(), this.signingKey);
    }

    public SHA256Hash hash() {
        var digest = new SHA256Digest();
        var sha256Hash = new byte[SHA256Hash.SIZE];

        var hashContents = hashContents().toBytes();
        digest.update(hashContents, 0, hashContents.length);
        if(!isTransactionFee) {
            digest.update(signature.toBytes(), 0, signature.toBytes().length);
        }
        digest.doFinal(sha256Hash, 0);
        return SHA256Hash.of(sha256Hash);
    }

    /**
     * The signed content hash — the transaction's identity, excluding the
     * signature (Ed25519 malleability means a signature-inclusive id can be
     * double-executed; Pandanite issue #37).
     *
     * <p>Preimage: {@code to || from(if not coinbase) || fee || amount ||
     * timestamp || chainId || nonce} (integers big-endian). Chain-id and account
     * nonce are clean-chain additions for replay protection.
     */
    public SHA256Hash hashContents() {
        var digest = new SHA256Digest();
        var sha256Hash = new byte[SHA256Hash.SIZE];

        digest.update(to.address().getArray(), 0, to.address().readRemaining());
        if (!isTransactionFee) {
            digest.update(from.address().getArray(), 0, from.address().readRemaining());
        }
        digest.update(longToBytes(fee.amount()), 0, 8);
        digest.update(longToBytes(amount.amount()), 0, 8);
        digest.update(longToBytes(timestamp), 0, 8);
        digest.update(intToBytes(chainId), 0, 4);
        digest.update(longToBytes(nonce), 0, 8);
        digest.doFinal(sha256Hash, 0);

        return SHA256Hash.of(sha256Hash);
    }

    public Transaction sign(PrivateKey signingKey) {
        this.signature = TransactionSignature.of(signWithPrivateKey(hashContents().toBytes(), signingKey));
        return this;
    }

    /**
     * Canonical ordering by content hash — used to order transactions
     * deterministically (e.g. inside a block) without mutating the block the
     * way Pandanite's in-place merkle sort did.
     */
    @Override
    public int compareTo(Transaction other) {
        return hashContents().compareTo(other.hashContents());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        TransactionImpl that = (TransactionImpl) obj;

        return timestamp == that.timestamp &&
            isTransactionFee == that.isTransactionFee &&
            chainId == that.chainId &&
            nonce == that.nonce &&
            Objects.equals(from, that.from) &&
            Objects.equals(to, that.to) &&
            Objects.equals(amount, that.amount) &&
            Objects.equals(fee, that.fee) &&
            Arrays.equals(this.signingKey.toBytes(), that.signingKey.toBytes()) &&
            Objects.equals(signature, that.signature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, to, amount, isTransactionFee, timestamp, fee, chainId, nonce, signingKey, signature);
    }
}
