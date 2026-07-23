package rhizome.core.block;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.serialization.BinaryIO;

/**
 * Fixed-layout binary codec for a {@link BlockHeader}, self-delimiting via its
 * {@code uncleCount}:
 *
 * <pre>
 * id(4) ‖ timestamp(8) ‖ difficulty(4) ‖ numTransactions(4)
 *   ‖ lastBlockHash(32) ‖ merkleRoot(32) ‖ nonce(32) ‖ stateRoot(32) ‖ vote(4)
 *   ‖ uncleCount(4) ‖ [uncleHash(32) ‖ uncleDifficulty(4) ‖ uncleMiner(25)]*
 * </pre>
 *
 * <p>The layout mirrors {@link rhizome.core.block.dto.BlockDto} (so a header
 * decodes the same bytes a block header would) and then appends the uncle
 * references, which the header hash commits to. A concatenation of headers is
 * self-framing, which is what the {@code /headers} endpoint streams.
 */
public final class HeaderCodec {

    private HeaderCodec() {}

    /** Bytes for the fixed prefix (everything up to and including {@code uncleCount}). */
    public static final int FIXED_PREFIX =
        Integer.BYTES + Long.BYTES + Integer.BYTES + Integer.BYTES
        + SHA256Hash.SIZE + SHA256Hash.SIZE + SHA256Hash.SIZE + SHA256Hash.SIZE
        + Integer.BYTES + Integer.BYTES;

    /** Bytes for one uncle record. */
    public static final int UNCLE_SIZE = SHA256Hash.SIZE + Integer.BYTES + PublicAddress.SIZE;

    public static int sizeOf(BlockHeader header) {
        return FIXED_PREFIX + header.uncles().size() * UNCLE_SIZE;
    }

    public static byte[] encode(BlockHeader header) {
        ByteBuffer buffer = ByteBuffer.allocate(sizeOf(header));
        writeTo(buffer, header);
        return buffer.array();
    }

    public static void writeTo(ByteBuffer buffer, BlockHeader header) {
        buffer.putInt(header.id());
        buffer.putLong(header.timestamp());
        buffer.putInt(header.difficulty());
        buffer.putInt(header.numTransactions());
        BinaryIO.putFixed(buffer, header.lastBlockHash().toBytes(), SHA256Hash.SIZE);
        BinaryIO.putFixed(buffer, header.merkleRoot().toBytes(), SHA256Hash.SIZE);
        BinaryIO.putFixed(buffer, header.nonce().toBytes(), SHA256Hash.SIZE);
        BinaryIO.putFixed(buffer, header.stateRoot().toBytes(), SHA256Hash.SIZE);
        buffer.putInt(header.vote());
        List<UncleRef> uncles = header.uncles();
        buffer.putInt(uncles.size());
        for (UncleRef uncle : uncles) {
            BinaryIO.putFixed(buffer, uncle.hash().toBytes(), SHA256Hash.SIZE);
            buffer.putInt(uncle.difficulty());
            BinaryIO.putFixed(buffer, uncle.miner().toBytes(), PublicAddress.SIZE);
        }
    }

    public static BlockHeader decode(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        BlockHeader header = readFrom(buffer);
        // Single-object decode must consume the whole buffer; trailing bytes are a non-canonical
        // wire form / latent malleability (audit L2). Streamed multi-header decode uses readFrom.
        if (buffer.hasRemaining()) {
            throw new IllegalArgumentException("trailing bytes after header: " + buffer.remaining());
        }
        return header;
    }

    public static BlockHeader readFrom(ByteBuffer buffer) {
        int id = buffer.getInt();
        // A header id is a positive height (genesis = 1). Bound it at decode for fail-closed parity
        // with difficulty/numTransactions/vote/uncle fields: HeaderChain rejects id != expectedId, but
        // a negative/zero wire int should never reach the derived-state arithmetic (h % lookback,
        // height comparisons) as a valid-looking value (audit S10, defense-in-depth / wire canonicality).
        // Genesis height is 1 (GenesisBlock.GENESIS_ID); a literal avoids a block->blockchain package cycle.
        if (id < 1) {
            throw new IllegalArgumentException("header id out of range: " + id);
        }
        long timestamp = buffer.getLong();
        int difficulty = buffer.getInt();
        // Bound the header's own difficulty at decode (matches uncleDifficulty below): it feeds
        // checkLeadingZeroBits and BigInteger.TWO.pow in the header-work sums, so an unbounded or
        // negative wire int would reach those as an index overrun or a huge allocation (audit).
        if (difficulty < 0 || difficulty > rhizome.core.common.Constants.MAX_DIFFICULTY) {
            throw new IllegalArgumentException("difficulty out of range: " + difficulty);
        }
        int numTransactions = buffer.getInt();
        SHA256Hash lastBlockHash = SHA256Hash.of(BinaryIO.getFixed(buffer, SHA256Hash.SIZE));
        SHA256Hash merkleRoot = SHA256Hash.of(BinaryIO.getFixed(buffer, SHA256Hash.SIZE));
        SHA256Hash nonce = SHA256Hash.of(BinaryIO.getFixed(buffer, SHA256Hash.SIZE));
        SHA256Hash stateRoot = SHA256Hash.of(BinaryIO.getFixed(buffer, SHA256Hash.SIZE));
        if (numTransactions < 0 || numTransactions > rhizome.core.common.Constants.MAX_TRANSACTIONS_PER_BLOCK) {
            throw new IllegalArgumentException("numTransactions out of range: " + numTransactions);
        }
        int vote = buffer.getInt();
        // Canonical votes are 0 (abstain) or ±paramId for the two votable params (VoteableParams:
        // STORAGE_FEE_FACTOR=1, MIN_VALUE_PER_BYTE=2). Reject anything else at decode so an unbounded
        // wire int never reaches the vote tally or the header preimage (audit V6e). Long abs so
        // Integer.MIN_VALUE (whose int abs stays negative) is handled.
        if (Math.abs((long) vote) > 2) {
            throw new IllegalArgumentException("vote out of range: " + vote);
        }
        int numUncles = buffer.getInt();
        // Bound the uncle count before pre-sizing the list — `new ArrayList<>(0x7FFFFFFF)`
        // is a one-header remote OOM on the /headers sync path otherwise.
        if (numUncles < 0 || numUncles > rhizome.core.common.Constants.MAX_UNCLES_PER_BLOCK) {
            throw new IllegalArgumentException("numUncles out of range: " + numUncles);
        }
        List<UncleRef> uncles = new ArrayList<>(numUncles);
        for (int i = 0; i < numUncles; i++) {
            SHA256Hash uncleHash = SHA256Hash.of(BinaryIO.getFixed(buffer, SHA256Hash.SIZE));
            int uncleDifficulty = buffer.getInt();
            // Bound uncle difficulty at decode: HeaderChain.uncleWork folds it into
            // BigInteger.TWO.pow(difficulty), so an unbounded/negative wire int would OOM
            // (pow(2^31)) or throw (negative) before any consensus check runs.
            if (uncleDifficulty < 0 || uncleDifficulty > rhizome.core.common.Constants.MAX_DIFFICULTY) {
                throw new IllegalArgumentException("uncleDifficulty out of range: " + uncleDifficulty);
            }
            PublicAddress uncleMiner = PublicAddress.of(BinaryIO.getFixed(buffer, PublicAddress.SIZE));
            uncles.add(new UncleRef(uncleHash, uncleDifficulty, uncleMiner));
        }
        return new BlockHeader(id, timestamp, difficulty, numTransactions,
            lastBlockHash, merkleRoot, nonce, stateRoot, vote, uncles);
    }

    /** Decodes a self-framing concatenation of headers (as served by {@code /headers}). */
    public static List<BlockHeader> decodeAll(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        List<BlockHeader> headers = new ArrayList<>();
        while (buffer.hasRemaining()) {
            headers.add(readFrom(buffer));
        }
        return headers;
    }
}
