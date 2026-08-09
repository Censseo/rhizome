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
import rhizome.crypto.SignatureScheme;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.serialization.JsonSink;
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

    @Builder.Default
    private TransactionAmount amount = new TransactionAmount(0);

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
     * Which signature scheme authorises this transaction, and therefore how {@link #from} is
     * derived from {@link #signingKey}. Defaults to Ed25519, so an ordinary transaction is
     * unchanged. See {@link SignatureScheme} for why the discriminant exists before any
     * post-quantum scheme is implemented.
     */
    @Builder.Default
    private SignatureScheme scheme = SignatureScheme.ED25519;

    /**
     * 32-byte digest of the holder's future post-quantum public key, for a scheme that
     * {@link SignatureScheme#commitsToPostQuantumKey() commits to one}; empty otherwise. It is not
     * itself signed — it is folded into {@link #from}, which is in the signed preimage.
     */
    @Builder.Default
    private byte[] pqCommitment = new byte[0];

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

    /**
     * Memoized full hash (content + signature). {@link #hash()} is recomputed several times per
     * transaction on the block path (merkle leaves call it twice each, checkpoint, gossip dedup)
     * — memoized exactly like {@link #cachedContentHash} (audit perf). Invalidated with the
     * content hash on any content setter, and by {@link #signature(TransactionSignature)}.
     */
    @lombok.Getter(lombok.AccessLevel.NONE)
    @lombok.Setter(lombok.AccessLevel.NONE)
    @Builder.Default
    private transient SHA256Hash cachedFullHash = null;

    private void invalidateContentHash() {
        this.cachedContentHash = null;
        this.cachedFullHash = null;
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
    public TransactionImpl signature(TransactionSignature signature) { this.signature = signature; this.cachedFullHash = null; return this; }
    // scheme/pqCommitment are not themselves in the preimage — they are bound through `from` (see
    // hashContents) — but they change the derived sender address and the serialized size, so they
    // invalidate the memos on the same principle as every other non-signature setter.
    public TransactionImpl scheme(SignatureScheme scheme) { this.scheme = scheme; invalidateContentHash(); return this; }
    public TransactionImpl pqCommitment(byte[] pqCommitment) { this.pqCommitment = pqCommitment == null ? new byte[0] : pqCommitment; invalidateContentHash(); return this; }

    /**
     * The address this transaction's public key actually derives under its declared scheme — the
     * single place that recomputes a sender address, so consensus and mempool cannot drift apart on
     * it. Returns {@link PublicAddress#empty()} rather than throwing when the scheme and commitment
     * are inconsistent (possible on the JSON path and for in-memory builders, never for a
     * wire-decoded transaction): callers use it in an equality check, and failing closed turns a
     * malformed transaction into a clean rejection instead of an exception on a consensus path.
     */
    public PublicAddress derivedSenderAddress() {
        try {
            return PublicAddress.of(signingKey, scheme, pqCommitment);
        } catch (IllegalArgumentException e) {
            return PublicAddress.empty();
        }
    }

    /**
     * Whether {@link #from} is genuinely the address of {@link #signingKey} under {@link #scheme}.
     *
     * <p>This is the load-bearing check for scheme agility. Because {@code from} is in the signed
     * preimage and the address folds in the scheme (as its version byte, and through the checksum),
     * a signature transitively commits to the scheme and to any post-quantum commitment: an attacker
     * who re-encodes a transaction under a different scheme changes the address recomputed here, and
     * the transaction is rejected. That is why no separate scheme field appears in
     * {@link #hashContents()} — and why one must not be added without revisiting this reasoning.
     */
    public boolean senderBindingValid() {
        return derivedSenderAddress().equals(from);
    }

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
        // Scheme-dependent: the authorisation fields are the variable-width part of the prefix.
        int size = TransactionDto.fixedSize(scheme) + 1; // fixed transfer prefix + the kind byte
        if (kind != TransactionKind.TRANSFER) {
            // contract/box/token suffix: gasLimit(8) + gasPrice(8) + dataLen(4) + data
            size += Long.BYTES + Long.BYTES + Integer.BYTES + data.length;
        }
        return size;
    }

    public JSONObject toJson() {
        return toJson(this);
    }

    public void writeJsonBody(JsonSink sink) {
        writeJsonBody(sink, this);
    }

    public boolean signatureValid() {
        // Coinbase and rent collection (BOX_COLLECT) are self-authorized: minted by the
        // block producer, carrying no signature, validated by consensus rules instead.
        if (isTransactionFee() || kind == TransactionKind.BOX_COLLECT) return true;
        return checkSignature(hashContents().toBytes(), this.signature.toBytes(), this.signingKey);
    }

    public SHA256Hash hash() {
        SHA256Hash cached = this.cachedFullHash;
        if (cached != null) {
            return cached;
        }
        var digest = new SHA256Digest();
        var sha256Hash = new byte[SHA256Hash.SIZE];

        var hashContents = hashContents().toBytes();
        digest.update(hashContents, 0, hashContents.length);
        if(!isTransactionFee) {
            var sig = signature.toBytes();
            digest.update(sig, 0, sig.length);
        }
        digest.doFinal(sha256Hash, 0);
        SHA256Hash result = SHA256Hash.of(sha256Hash);
        this.cachedFullHash = result;
        return result;
    }

    /**
     * The signed content hash — the transaction's identity, excluding the
     * signature (Ed25519 malleability means a signature-inclusive id can be
     * double-executed; Pandanite issue #37).
     *
     * <p>Preimage: {@code to || from(if not coinbase) || fee || amount ||
     * timestamp || chainId || nonce} (integers big-endian). Chain-id and account
     * nonce are clean-chain additions for replay protection.
     *
     * <p><strong>Signature-scheme binding.</strong> The preimage carries no scheme field, and must
     * not grow one: {@code from} is in it, and {@code from} is the address derived from the public
     * key <em>under the scheme</em> (the scheme is the address version byte, and is covered by the
     * address checksum). A signature therefore already commits to the scheme and to any
     * post-quantum key commitment, provided {@link #senderBindingValid()} is enforced — which
     * Executor pass 1 and MemPool admission both do. Adding the scheme to the preimage would be
     * redundant and would change every existing txid for no security gain.
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
        this.cachedFullHash = null; // the full hash folds the signature in
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
            scheme == that.scheme &&
            Arrays.equals(this.pqCommitment, that.pqCommitment) &&
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
            signature, kind, gasLimit, gasPrice, Arrays.hashCode(data), scheme, Arrays.hashCode(pqCommitment));
    }
}
