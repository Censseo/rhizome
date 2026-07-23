package rhizome.core.transaction;

import java.util.Arrays;
import java.util.Objects;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.json.JSONObject;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.dto.TransactionDto;

import static rhizome.crypto.Crypto.signWithPrivateKey;
import static rhizome.crypto.Crypto.checkSignature;
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

    /** What the transaction does. TRANSFER (default) keeps existing behaviour. */
    @Builder.Default
    private TransactionKind kind = TransactionKind.TRANSFER;

    /** Contract payload: code for DEPLOY, call input for CALL; empty for TRANSFER. */
    @Builder.Default
    private byte[] data = new byte[0];

    /** Max gas a contract execution may consume (0 for TRANSFER). */
    @Builder.Default
    private long gasLimit = 0;

    /** Price per gas unit, in base units, paid to the miner (0 for TRANSFER). */
    @Builder.Default
    private long gasPrice = 0;

    /**
     * Memoized content hash (audit P: verify-once cache). {@link #hashContents()} is on the hottest
     * validation path — the signature verifier recomputes it for every {@code verify}/{@code isCached}/
     * {@code markVerified}, mempool admission, block replay dedup and {@code compareTo}. The content
     * fields are effectively immutable once a transaction is built/signed, so the hash is computed
     * once and reused. Every content-field setter nulls this cache (below) to stay correct even if a
     * field is mutated after a hash was taken. Excluded from Lombok accessors and from equals/hashCode.
     */
    @lombok.Getter(lombok.AccessLevel.NONE)
    @lombok.Setter(lombok.AccessLevel.NONE)
    @Builder.Default
    private transient SHA256Hash cachedContentHash = null;

    private void invalidateContentHash() {
        this.cachedContentHash = null;
    }

    // Content-field setters override the Lombok-generated ones solely to invalidate the memoized
    // content hash; they otherwise behave identically (fluent + chain, returning this).
    public TransactionImpl from(PublicAddress from) { this.from = from; invalidateContentHash(); return this; }
    public TransactionImpl to(PublicAddress to) { this.to = to; invalidateContentHash(); return this; }
    public TransactionImpl amount(TransactionAmount amount) { this.amount = amount; invalidateContentHash(); return this; }
    public TransactionImpl isTransactionFee(boolean v) { this.isTransactionFee = v; invalidateContentHash(); return this; }
    public TransactionImpl timestamp(long timestamp) { this.timestamp = timestamp; invalidateContentHash(); return this; }
    public TransactionImpl fee(TransactionAmount fee) { this.fee = fee; invalidateContentHash(); return this; }
    public TransactionImpl chainId(int chainId) { this.chainId = chainId; invalidateContentHash(); return this; }
    public TransactionImpl nonce(long nonce) { this.nonce = nonce; invalidateContentHash(); return this; }
    public TransactionImpl kind(TransactionKind kind) { this.kind = kind; invalidateContentHash(); return this; }
    public TransactionImpl gasLimit(long gasLimit) { this.gasLimit = gasLimit; invalidateContentHash(); return this; }
    public TransactionImpl gasPrice(long gasPrice) { this.gasPrice = gasPrice; invalidateContentHash(); return this; }
    public TransactionImpl data(byte[] data) { this.data = data; invalidateContentHash(); return this; }

    /**
     * Serialization
     */
    public TransactionDto serialize() {
       return serialize(this);
    }

    /**
     * The exact serialized byte length WITHOUT building the wire form (audit P7). The block-size
     * pre-check summed {@code serialize().getSize()} over every transaction, allocating a full DTO
     * (and copying signature/key/data) per tx only to read a length — then the block was serialized
     * again to store it. This mirrors {@link TransactionDto#getSize()} exactly; a mismatch would make
     * the block-size cap wrong, so it is pinned by a test asserting equality for every kind.
     */
    public int sizeBytes() {
        int size = TransactionDto.FIXED_SIZE + 1; // fixed transfer prefix + the kind byte
        if (kind != TransactionKind.TRANSFER) {
            // contract/box/token suffix: gasLimit(8) + gasPrice(8) + dataLen(4) + data
            size += Long.BYTES + Long.BYTES + Integer.BYTES + data.length;
        }
        return size;
    }

    public JSONObject toJson() {
        return toJson(this);
    }
    
    public boolean signatureValid() {
        // Coinbase and rent collection (BOX_COLLECT) are self-authorized: minted by the
        // block producer, carrying no signature, validated by consensus rules instead.
        if (isTransactionFee() || kind == TransactionKind.BOX_COLLECT) return true;
        return checkSignature(hashContents().toBytes(), this.signature.toBytes(), this.signingKey);
    }

    public SHA256Hash hash() {
        var digest = new SHA256Digest();
        var sha256Hash = new byte[SHA256Hash.SIZE];

        var hashContents = hashContents().toBytes();
        digest.update(hashContents, 0, hashContents.length);
        if(!isTransactionFee) {
            var sig = signature.toBytes();
            digest.update(sig, 0, sig.length);
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
        SHA256Hash cached = this.cachedContentHash;
        if (cached != null) {
            return cached;
        }
        var digest = new SHA256Digest();
        var sha256Hash = new byte[SHA256Hash.SIZE];

        var toBytes = to.toBytes();
        digest.update(toBytes, 0, toBytes.length);
        if (!isTransactionFee) {
            var fromBytes = from.toBytes();
            digest.update(fromBytes, 0, fromBytes.length);
        }
        digest.update(longToBytes(fee.amount()), 0, 8);
        digest.update(longToBytes(amount.amount()), 0, 8);
        digest.update(longToBytes(timestamp), 0, 8);
        digest.update(intToBytes(chainId), 0, 4);
        digest.update(longToBytes(nonce), 0, 8);
        // Payload fields are committed for every non-transfer kind (contract and box),
        // so a plain transfer's content hash (and signature) is byte-for-byte what it
        // was before contracts existed.
        if (kind.hasPayload()) {
            digest.update(new byte[] {kind.code()}, 0, 1);
            digest.update(longToBytes(gasLimit), 0, 8);
            digest.update(longToBytes(gasPrice), 0, 8);
            digest.update(data, 0, data.length);
        }
        digest.doFinal(sha256Hash, 0);

        SHA256Hash result = SHA256Hash.of(sha256Hash);
        this.cachedContentHash = result;
        return result;
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
            kind == that.kind &&
            gasLimit == that.gasLimit &&
            gasPrice == that.gasPrice &&
            Objects.equals(from, that.from) &&
            Objects.equals(to, that.to) &&
            Objects.equals(amount, that.amount) &&
            Objects.equals(fee, that.fee) &&
            Arrays.equals(this.data, that.data) &&
            Arrays.equals(this.signingKey.toBytes(), that.signingKey.toBytes()) &&
            Objects.equals(signature, that.signature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, to, amount, isTransactionFee, timestamp, fee, chainId, nonce, signingKey,
            signature, kind, gasLimit, gasPrice, Arrays.hashCode(data));
    }
}
