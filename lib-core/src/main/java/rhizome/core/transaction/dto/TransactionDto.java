package rhizome.core.transaction.dto;

import java.nio.ByteBuffer;

import lombok.Getter;
import lombok.experimental.Accessors;
import rhizome.crypto.PublicKey;
import rhizome.crypto.SignatureScheme;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.serialization.BinaryIO;
import rhizome.core.transaction.TransactionSignature;

/**
 * Wire/storage form of a transaction:
 * {@code scheme(1) || signature(scheme) || signingKey(scheme) || pqCommitment(scheme) ||
 * timestamp(8) || to(25) || amount(8) || fee(8) || isTransactionFee(1) || chainId(4) || nonce(8)},
 * followed by a one-byte {@code kind}; for a contract transaction (kind != TRANSFER) that byte is
 * followed by {@code gasLimit(8) || gasPrice(8) || dataLen(4) || data}. Every transaction is
 * self-delimiting so a block can pack variable-length transactions back to back.
 *
 * <h2>The leading scheme byte</h2>
 * The authorisation fields are the only variable-width part of the fixed prefix, and their widths
 * come from the leading {@link SignatureScheme} byte — never from a length field on the wire, which
 * would hand an attacker an allocation size on an untrusted decode path. This byte is what lets the
 * chain adopt a post-quantum signature scheme later without a format break: today it always reads
 * {@code 0x00} (Ed25519) or {@code 0x01} (Ed25519 with a post-quantum key commitment), and
 * {@code 0x02..0x0F} are reserved for the NIST schemes. See {@link SignatureScheme} and
 * WHITEPAPER §5.9.
 *
 * <p>An Ed25519 transfer is therefore one byte longer than the pre-agility format; a
 * post-quantum-committed one is 33 bytes longer.
 */
@Accessors(fluent = true) @Getter
public class TransactionDto {
    public final SignatureScheme scheme;
    public final TransactionSignature signature;
    public final PublicKey signingKey;
    /** Empty unless {@link #scheme} commits to a post-quantum key; then exactly 32 bytes. */
    public final byte[] pqCommitment;
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

    /** Scheme-independent part of the fixed prefix: everything after the authorisation fields. */
    private static final int COMMON_SIZE =
        Long.BYTES + PublicAddress.SIZE + Long.BYTES + Long.BYTES + 1 + Integer.BYTES + Long.BYTES;

    /** Fixed prefix for {@code scheme} (excludes the kind byte and any contract suffix). */
    public static int fixedSize(SignatureScheme scheme) {
        return scheme.wireAuthBytes() + COMMON_SIZE;
    }

    /** Fixed prefix of a default (Ed25519) transaction — the common case, 159 bytes. */
    public static final int FIXED_SIZE = fixedSize(SignatureScheme.ED25519);

    /**
     * Widest fixed prefix across all implemented schemes. Buffer and request-body caps size against
     * this so they stay correct as schemes are added — deriving it from {@link SignatureScheme}
     * rather than hard-coding it means a new scheme cannot leave a stale bound behind.
     */
    public static final int MAX_FIXED_SIZE = SignatureScheme.MAX_WIRE_AUTH_BYTES + COMMON_SIZE;

    /** Hard cap on contract payload bytes on the wire. */
    public static final int MAX_DATA = 128 * 1024;

    private static final byte KIND_TRANSFER = 0;
    private static final byte[] NO_COMMITMENT = new byte[0];

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
        this(SignatureScheme.ED25519, signature, signingKey, NO_COMMITMENT, timestamp, to, amount, fee,
            isTransactionFee, chainId, nonce, KIND_TRANSFER, 0, 0, new byte[0]);
    }

    public TransactionDto(
        SignatureScheme scheme,
        TransactionSignature signature,
        PublicKey signingKey,
        byte[] pqCommitment,
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

        this.scheme = scheme == null ? SignatureScheme.ED25519 : scheme;
        this.signature = signature;
        this.signingKey = signingKey;
        this.pqCommitment = pqCommitment == null ? NO_COMMITMENT : pqCommitment;
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

    public void writeTo(ByteBuffer buffer) {
        buffer.put(scheme.code());
        BinaryIO.putFixed(buffer, signature.toBytes(), scheme.signatureBytes());
        BinaryIO.putFixed(buffer, signingKey.toBytes(), scheme.publicKeyBytes());
        BinaryIO.putFixed(buffer, pqCommitment, scheme.commitmentBytes());
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
        // Fails closed on any code that is not an implemented scheme, including the reserved
        // post-quantum block: silently treating an unknown scheme as Ed25519 would read the
        // following fields at the wrong widths and split consensus (see SignatureScheme.fromCode).
        SignatureScheme scheme = SignatureScheme.fromCode(buffer.get());
        TransactionSignature signature = TransactionSignature.of(BinaryIO.getFixed(buffer, scheme.signatureBytes()));
        PublicKey signingKey = PublicKey.of(BinaryIO.getFixed(buffer, scheme.publicKeyBytes()));
        byte[] pqCommitment = BinaryIO.getFixed(buffer, scheme.commitmentBytes());
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
        // Coinbase and rent collection are minted by the block producer and carry no signature, so
        // any scheme other than the default would be pure malleability: a free 32-byte commitment
        // field on an unsigned transaction, changing its wire bytes without changing its meaning.
        if (isTransactionFee && scheme != SignatureScheme.ED25519) {
            throw new IllegalArgumentException("self-authorized transaction must use "
                + SignatureScheme.ED25519 + ", got " + scheme);
        }
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
        return new TransactionDto(scheme, signature, signingKey, pqCommitment, timestamp, to, amount, fee,
            isTransactionFee, chainId, nonce, kind, gasLimit, gasPrice, data);
    }

    public int getSize() {
        int size = fixedSize(scheme) + 1;
        if (kind != KIND_TRANSFER) {
            size += Long.BYTES + Long.BYTES + Integer.BYTES + data.length;
        }
        return size;
    }

    /** The self-delimiting wire form of one transaction. */
    public byte[] toBuffer() {
        ByteBuffer buffer = ByteBuffer.allocate(getSize());
        writeTo(buffer);
        return buffer.array();
    }

    /**
     * Strict single-object decode: the whole {@code buffer} must be exactly one transaction.
     * Trailing bytes are rejected so a wire object has a unique encoding, matching the P7
     * strictness of {@code BlockCodec}/{@code HeaderCodec} (identity is content-hash, so this is
     * wire-hygiene, not a correctness fix — but it closes the last non-strict single-object path,
     * {@code /add_transaction}). The packed block codec reads transactions from a multi-object
     * buffer via {@link #readFrom(ByteBuffer)} instead.
     */
    public static TransactionDto fromBuffer(byte[] buffer) {
        ByteBuffer bb = ByteBuffer.wrap(buffer);
        TransactionDto result = readFrom(bb);
        if (bb.hasRemaining()) {
            throw new IllegalArgumentException(
                "trailing bytes after TransactionDto (" + bb.remaining() + " left)");
        }
        return result;
    }
}
