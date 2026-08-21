package rhizome.core.block;

import java.nio.ByteBuffer;

import rhizome.core.common.Constants;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.serialization.BinaryIO;
import rhizome.crypto.SHA256Hash;

/**
 * The one definition of a block header's wire bytes, shared by every codec that writes or reads
 * them.
 *
 * <p>Two types model a header — {@link BlockHeader}, the canonical hash preimage carrier, and
 * {@link rhizome.core.block.dto.BlockDto}, the wire/storage shape — and their binary forms share a
 * byte-identical {@value #PREFIX_BYTES}-byte prefix. That prefix used to be transcribed field by
 * field in two writers and validated with four different bound sets across four decoders
 * ({@code HeaderCodec.readFrom}, {@code BlockDto.readFrom}, {@code BlockCodec.decode} and
 * {@code Block.of(JSONObject)}), which is how they drifted:
 *
 * <ul>
 *   <li>only {@code HeaderCodec} bounded {@code id}, so the same malformed height was rejected on
 *       the {@code /headers} path and accepted on the {@code /submit} and storage paths;</li>
 *   <li>{@code HeaderCodec} wrote uncle hash and miner through {@link BinaryIO#putFixed}, whose
 *       whole purpose is to reject a wrong-length field rather than silently emit a
 *       different-but-plausible preimage (audit S10) — while {@code BlockCodec} wrote the same
 *       three fields with a raw {@code put}, so the guard did not cover the path that
 *       {@code /submit} and RocksDB storage actually use.</li>
 * </ul>
 *
 * <p>Every bound here is the strictest of the four, applied on every path. Field order and widths
 * are unchanged: no hash, no proof of work and no stored blob moves.
 *
 * <p>⚠ This is NOT the hash preimage. {@link BlockHeader#hash()} commits to the same values in a
 * deliberately different, interleaved order and is marked consensus-critical; it does not and must
 * not route through here.
 */
public final class HeaderWire {

    private HeaderWire() {
    }

    /** Bytes of the shared prefix: everything through {@code supply}. */
    public static final int PREFIX_BYTES =
        Integer.BYTES + Long.BYTES + Integer.BYTES + Integer.BYTES
        + SHA256Hash.SIZE + SHA256Hash.SIZE + SHA256Hash.SIZE + SHA256Hash.SIZE
        + Integer.BYTES + Long.BYTES;

    /** Bytes of one uncle record: hash, difficulty, miner. */
    public static final int UNCLE_BYTES = SHA256Hash.SIZE + Integer.BYTES + PublicAddress.SIZE;

    /** The fields both header shapes carry, in wire order. */
    public record Prefix(int id, long timestamp, int difficulty, int numTransactions,
                         SHA256Hash lastBlockHash, SHA256Hash merkleRoot, SHA256Hash nonce,
                         SHA256Hash stateRoot, int vote, long supply) {
    }

    public static void writePrefix(ByteBuffer buffer, Prefix p) {
        buffer.putInt(p.id());
        buffer.putLong(p.timestamp());
        buffer.putInt(p.difficulty());
        buffer.putInt(p.numTransactions());
        BinaryIO.putFixed(buffer, p.lastBlockHash().toBytes(), SHA256Hash.SIZE);
        BinaryIO.putFixed(buffer, p.merkleRoot().toBytes(), SHA256Hash.SIZE);
        BinaryIO.putFixed(buffer, p.nonce().toBytes(), SHA256Hash.SIZE);
        BinaryIO.putFixed(buffer, p.stateRoot().toBytes(), SHA256Hash.SIZE);
        buffer.putInt(p.vote());
        buffer.putLong(p.supply());
    }

    /**
     * Reads the shared prefix, rejecting every out-of-range field before it can reach consensus
     * arithmetic. Each bound is checked as soon as its field is read, so the first malformed field
     * is the one reported.
     */
    public static Prefix readPrefix(ByteBuffer buffer) {
        int id = buffer.getInt();
        // A height is positive (genesis = 1). HeaderChain rejects id != expectedId anyway, but a
        // negative or zero wire int must never reach the derived-state arithmetic (h % lookback,
        // height comparisons) looking valid (audit S10). A literal avoids a package cycle back to
        // GenesisBlock.GENESIS_ID.
        if (id < 1) {
            throw new IllegalArgumentException("header id out of range: " + id);
        }
        long timestamp = buffer.getLong();
        int difficulty = buffer.getInt();
        // Feeds checkLeadingZeroBits and BigInteger.TWO.pow in the work sums: unbounded means an
        // index overrun or an astronomically large allocation.
        if (difficulty < 0 || difficulty > Constants.MAX_DIFFICULTY) {
            throw new IllegalArgumentException("difficulty out of range: " + difficulty);
        }
        int numTransactions = buffer.getInt();
        // Bounded BEFORE any caller pre-sizes a collection from it: a raw 0x7FFFFFFF would
        // otherwise allocate gigabytes on decode.
        if (numTransactions < 0 || numTransactions > Constants.MAX_TRANSACTIONS_PER_BLOCK) {
            throw new IllegalArgumentException("numTransactions out of range: " + numTransactions);
        }
        SHA256Hash lastBlockHash = SHA256Hash.of(BinaryIO.getFixed(buffer, SHA256Hash.SIZE));
        SHA256Hash merkleRoot = SHA256Hash.of(BinaryIO.getFixed(buffer, SHA256Hash.SIZE));
        SHA256Hash nonce = SHA256Hash.of(BinaryIO.getFixed(buffer, SHA256Hash.SIZE));
        SHA256Hash stateRoot = SHA256Hash.of(BinaryIO.getFixed(buffer, SHA256Hash.SIZE));
        int vote = buffer.getInt();
        // Canonical votes are 0 (abstain) or ±paramId for the two votable params (VoteableParams:
        // STORAGE_FEE_FACTOR=1, MIN_VALUE_PER_BYTE=2) — audit V6e. Long abs so Integer.MIN_VALUE,
        // whose int abs stays negative, is handled.
        if (Math.abs((long) vote) > 2) {
            throw new IllegalArgumentException("vote out of range: " + vote);
        }
        long supply = buffer.getLong();
        // Consensus range is [0, Long.MAX_VALUE]; -1 (BlockImpl.SUPPLY_ABSENT) is the one value
        // below it that means "not committed" (§ supply header commitment). Anything lower is
        // malformed and must never reach the accounting identity in ChainEngine/HeaderChain.
        if (supply < -1L) {
            throw new IllegalArgumentException("supply out of range: " + supply);
        }
        return new Prefix(id, timestamp, difficulty, numTransactions,
            lastBlockHash, merkleRoot, nonce, stateRoot, vote, supply);
    }

    /** Reads and bounds an uncle count before any caller sizes a list from it. */
    public static int readUncleCount(ByteBuffer buffer) {
        int numUncles = buffer.getInt();
        if (numUncles < 0 || numUncles > Constants.MAX_UNCLES_PER_BLOCK) {
            throw new IllegalArgumentException("numUncles out of range: " + numUncles);
        }
        return numUncles;
    }

    /**
     * Writes one uncle record. Both variable-looking fields go through {@link BinaryIO#putFixed}:
     * a wrong-length hash or miner address must fail loudly rather than shift every following byte
     * and produce a different-but-plausible header (audit S10).
     */
    public static void writeUncle(ByteBuffer buffer, UncleRef uncle) {
        BinaryIO.putFixed(buffer, uncle.hash().toBytes(), SHA256Hash.SIZE);
        buffer.putInt(uncle.difficulty());
        BinaryIO.putFixed(buffer, uncle.miner().toBytes(), PublicAddress.SIZE);
    }

    public static UncleRef readUncle(ByteBuffer buffer) {
        SHA256Hash hash = SHA256Hash.of(BinaryIO.getFixed(buffer, SHA256Hash.SIZE));
        int difficulty = buffer.getInt();
        // Folded into BigInteger.TWO.pow(difficulty) by the uncle work sums, so an unbounded or
        // negative wire int would OOM or throw there, before any consensus check runs.
        if (difficulty < 0 || difficulty > Constants.MAX_DIFFICULTY) {
            throw new IllegalArgumentException("uncleDifficulty out of range: " + difficulty);
        }
        PublicAddress miner = PublicAddress.of(BinaryIO.getFixed(buffer, PublicAddress.SIZE));
        return new UncleRef(hash, difficulty, miner);
    }
}
