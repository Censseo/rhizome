package rhizome;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockCodec;
import rhizome.core.block.BlockImpl;
import rhizome.core.block.HeaderCodec;
import rhizome.core.block.UncleRef;
import rhizome.core.block.dto.BlockDto;
import rhizome.core.common.Constants;
import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.dto.TransactionDto;

/**
 * Regression guard for the decode-time OOM (H1/H2): attacker-controlled count and
 * uncle-difficulty fields must be rejected BEFORE any collection is pre-sized or
 * {@code BigInteger.pow} is called, so a tiny crafted header cannot allocate gigabytes.
 */
class CodecBoundsTest {

    private static byte[] header(int numTransactions, int uncleCountField, Integer singleUncleDifficulty) {
        int records = singleUncleDifficulty != null ? 1 : 0;
        ByteBuffer b = ByteBuffer.allocate(HeaderCodec.FIXED_PREFIX + records * HeaderCodec.UNCLE_SIZE);
        b.putInt(1);                 // id
        b.putLong(0L);               // timestamp
        b.putInt(0);                 // difficulty
        b.putInt(numTransactions);   // numTransactions
        b.put(new byte[32]);         // lastBlockHash
        b.put(new byte[32]);         // merkleRoot
        b.put(new byte[32]);         // nonce
        b.put(new byte[32]);         // stateRoot
        b.putInt(0);                 // vote
        b.putInt(uncleCountField);   // uncleCount
        if (singleUncleDifficulty != null) {
            b.put(new byte[32]);            // uncle hash
            b.putInt(singleUncleDifficulty); // uncle difficulty
            b.put(new byte[25]);            // uncle miner
        }
        return b.array();
    }

    @Test
    void wellFormedHeaderDecodes() {
        assertDoesNotThrow(() -> HeaderCodec.decode(header(0, 0, null)));
    }

    /**
     * One malformed prefix field, four decoders, one verdict.
     *
     * <p>The header prefix is byte-identical across HeaderCodec, BlockDto and BlockCodec, but each
     * used to validate it with its own bound set — only HeaderCodec bounded {@code id}, so the same
     * malformed height was rejected on the /headers path and accepted on /submit and on the way
     * into RocksDB. The per-shape tests in this file could not catch that, because each drives one
     * decoder: a suite organised the way the duplication is organised locks the asymmetry in rather
     * than exposing it. This one asserts the decoders agree.
     *
     * <p>A header with no uncles and a block with no transactions and no uncles are the same 156
     * bytes, which is exactly the property being tested, so one buffer drives all three binary
     * paths.
     */
    @Test
    void everyDecoderRejectsTheSameMalformedPrefix() {
        // Offsets into the shared prefix: id(0) timestamp(4) difficulty(12) numTransactions(16)
        // lastBlockHash(20) merkleRoot(52) nonce(84) stateRoot(116) vote(148).
        int[][] malformed = {
            {0, 0}, {0, -1}, {0, Integer.MIN_VALUE},                 // id: a height is positive
            {12, Constants.MAX_DIFFICULTY + 1}, {12, -1},            // difficulty
            {16, Integer.MAX_VALUE}, {16, -1},                       // numTransactions
            {148, 3}, {148, -3}, {148, Integer.MIN_VALUE},           // vote
        };
        for (int[] field : malformed) {
            byte[] bytes = header(0, 0, null);
            ByteBuffer.wrap(bytes).putInt(field[0], field[1]);
            String what = "offset " + field[0] + " = " + field[1];

            assertThrows(IllegalArgumentException.class, () -> HeaderCodec.decode(bytes),
                "HeaderCodec must reject " + what);
            assertThrows(IllegalArgumentException.class, () -> BlockCodec.decode(bytes),
                "BlockCodec must reject " + what + " — same bytes, same verdict");
            assertThrows(IllegalArgumentException.class,
                () -> BlockDto.readFrom(ByteBuffer.wrap(bytes)),
                "BlockDto must reject " + what + " — it is the storage and /submit path");
        }
    }

    @Test
    void aWellFormedPrefixDecodesOnEveryPath() {
        // The negative case above is only meaningful if the same buffer is accepted everywhere.
        byte[] bytes = header(0, 0, null);
        assertDoesNotThrow(() -> HeaderCodec.decode(bytes));
        assertDoesNotThrow(() -> BlockCodec.decode(bytes));
        assertDoesNotThrow(() -> BlockDto.readFrom(ByteBuffer.wrap(bytes)));
    }

    @Test
    void rejectsHugeTransactionCount() {
        assertThrows(IllegalArgumentException.class, () -> HeaderCodec.decode(header(Integer.MAX_VALUE, 0, null)));
        assertThrows(IllegalArgumentException.class, () -> HeaderCodec.decode(header(-1, 0, null)));
    }

    @Test
    void rejectsTrailingBytesAfterHeader() {
        // Single-object decode must consume the whole buffer: a valid header with one extra byte is
        // a non-canonical wire form and must be rejected, not silently accepted (audit L2).
        byte[] wellFormed = header(0, 0, null);
        byte[] withTrailer = java.util.Arrays.copyOf(wellFormed, wellFormed.length + 1);
        assertDoesNotThrow(() -> HeaderCodec.decode(wellFormed));
        assertThrows(IllegalArgumentException.class, () -> HeaderCodec.decode(withTrailer));
    }

    private static byte[] headerWithVote(int vote) {
        byte[] h = header(0, 0, null);
        ByteBuffer.wrap(h).putInt(148, vote); // vote field: after id+ts+diff+numTx + four 32-byte hashes
        return h;
    }

    @Test
    void rejectsOutOfRangeVote() {
        // Canonical votes are 0 (abstain) or ±1/±2 (VoteableParams). An out-of-range wire int must be
        // rejected at decode so it never reaches the vote tally or the header preimage (audit V6e).
        assertDoesNotThrow(() -> HeaderCodec.decode(headerWithVote(0)));
        assertDoesNotThrow(() -> HeaderCodec.decode(headerWithVote(2)));
        assertDoesNotThrow(() -> HeaderCodec.decode(headerWithVote(-2)));
        assertThrows(IllegalArgumentException.class, () -> HeaderCodec.decode(headerWithVote(3)));
        assertThrows(IllegalArgumentException.class, () -> HeaderCodec.decode(headerWithVote(Integer.MIN_VALUE)));
    }

    @Test
    void rejectsHugeUncleCount() {
        assertThrows(IllegalArgumentException.class, () -> HeaderCodec.decode(header(0, Integer.MAX_VALUE, null)));
        assertThrows(IllegalArgumentException.class, () -> HeaderCodec.decode(header(0, -1, null)));
    }

    @Test
    void rejectsHugeUncleDifficulty() {
        assertThrows(IllegalArgumentException.class, () -> HeaderCodec.decode(header(0, 1, Integer.MAX_VALUE)));
        assertThrows(IllegalArgumentException.class, () -> HeaderCodec.decode(header(0, 1, -1)));
    }

    @Test
    void blockCodecRejectsOutOfRangeUncleDifficulty() {
        // The full-block codec now bounds uncle difficulty like HeaderCodec (codec parity): a valid
        // difficulty round-trips, an out-of-range one is rejected at decode before it could reach
        // BigInteger.TWO.pow in validateUncles.
        assertDoesNotThrow(() -> BlockCodec.decode(BlockCodec.encode(blockWithUncleDifficulty(5))));
        assertThrows(IllegalArgumentException.class,
            () -> BlockCodec.decode(BlockCodec.encode(blockWithUncleDifficulty(Integer.MAX_VALUE))));
        assertThrows(IllegalArgumentException.class,
            () -> BlockCodec.decode(BlockCodec.encode(blockWithUncleDifficulty(-1))));
    }

    private static BlockImpl blockWithUncleDifficulty(int uncleDifficulty) {
        var b = (BlockImpl) BlockImpl.builder().id(2).timestamp(5000).difficulty(4)
            .lastBlockHash(SHA256Hash.empty())
            .uncles(java.util.List.of(new UncleRef(SHA256Hash.random(), uncleDifficulty, PublicAddress.random())))
            .build();
        b.addTransaction(Transaction.of(PublicAddress.random(), new TransactionAmount(50)));
        MerkleTree tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(SHA256Hash.empty());
        return b;
    }

    @Test
    void blockDtoRejectsHugeTransactionCount() {
        ByteBuffer b = ByteBuffer.allocate(BlockDto.BUFFER_SIZE);
        b.putInt(1);                 // id
        b.putLong(0L);               // timestamp
        b.putInt(0);                 // difficulty
        b.putInt(Integer.MAX_VALUE); // numTransactions (poison)
        b.put(new byte[32]);         // lastBlockHash
        b.put(new byte[32]);         // merkleRoot
        b.put(new byte[32]);         // nonce
        b.put(new byte[32]);         // stateRoot
        b.putInt(0);                 // vote
        b.flip();
        assertThrows(IllegalArgumentException.class, () -> BlockDto.readFrom(b));
        // Sanity: the constant the bound uses is the consensus tx cap.
        assertThrows(IllegalArgumentException.class,
            () -> HeaderCodec.decode(header(Constants.MAX_TRANSACTIONS_PER_BLOCK + 1, 0, null)));
    }

    // ---- JSON-decode parity with the binary codecs (audit F5, F1) ----

    /** A minimal valid JSON block, produced by the block's own toJson so every required key exists. */
    private static JSONObject jsonBlock() {
        var b = (BlockImpl) BlockImpl.builder().id(2).timestamp(5000).difficulty(4)
            .lastBlockHash(SHA256Hash.empty()).build();
        b.addTransaction(Transaction.of(PublicAddress.random(), new TransactionAmount(50)));
        MerkleTree tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(SHA256Hash.empty());
        return b.toJson();
    }

    private static JSONObject uncleJson(int difficulty) {
        return new JSONObject()
            .put("hash", SHA256Hash.random().toHexString())
            .put("difficulty", difficulty)
            .put("miner", PublicAddress.random().toHexString());
    }

    @Test
    void boxReceiptCodecRoundTripsAndRejectsGarbage() {
        // The persisted form of a block's box receipts (audit F7): an exact round-trip, and a
        // corrupt count rejected before any allocation — the same guard as the box journal codec.
        java.util.List<rhizome.core.box.BoxProcessor.BoxReceipt> receipts = java.util.List.of(
            new rhizome.core.box.BoxProcessor.BoxReceipt(
                rhizome.core.transaction.TransactionKind.BOX_CREATE, 5000, 0, 0),
            new rhizome.core.box.BoxProcessor.BoxReceipt(
                rhizome.core.transaction.TransactionKind.BOX_COLLECT, 0, 42, 7));
        org.junit.jupiter.api.Assertions.assertEquals(receipts,
            rhizome.core.box.BoxReceiptCodec.decode(rhizome.core.box.BoxReceiptCodec.encode(receipts)));
        // count = 2 but no records follow: rejected, not new ArrayList on a bogus count.
        assertThrows(IllegalStateException.class,
            () -> rhizome.core.box.BoxReceiptCodec.decode(new byte[] {0, 0, 0, 2, 0}));
    }

    @Test
    void blockFromJsonEnforcesBinaryCodecBounds() {
        // The JSON decode path must reject exactly what BlockDto/HeaderCodec/BlockCodec reject on
        // the wire, so a JSON-sourced block cannot carry fields a binary peer never accepts.
        JSONObject base = jsonBlock();
        assertDoesNotThrow(() -> Block.of(new JSONObject(base.toString())));

        // difficulty in [0, MAX_DIFFICULTY], as BlockDto.readFrom enforces.
        JSONObject badDifficulty = new JSONObject(base.toString());
        badDifficulty.put("difficulty", Constants.MAX_DIFFICULTY + 1);
        assertThrows(IllegalArgumentException.class, () -> Block.of(badDifficulty));
        JSONObject negativeDifficulty = new JSONObject(base.toString());
        negativeDifficulty.put("difficulty", -1);
        assertThrows(IllegalArgumentException.class, () -> Block.of(negativeDifficulty));

        // vote: 0 (abstain) or ±paramId only — the same canonical rule as the codecs (audit F1).
        JSONObject badVote = new JSONObject(base.toString());
        badVote.put("vote", 3);
        assertThrows(IllegalArgumentException.class, () -> Block.of(badVote));

        // uncle count capped, as BlockCodec/HeaderCodec cap it.
        JSONObject tooManyUncles = new JSONObject(base.toString());
        JSONArray uncles = new JSONArray();
        for (int i = 0; i < Constants.MAX_UNCLES_PER_BLOCK + 1; i++) {
            uncles.put(uncleJson(1));
        }
        tooManyUncles.put("uncles", uncles);
        assertThrows(IllegalArgumentException.class, () -> Block.of(tooManyUncles));

        // uncle difficulty in [0, MAX_DIFFICULTY].
        JSONObject badUncleDifficulty = new JSONObject(base.toString());
        badUncleDifficulty.put("uncles",
            new JSONArray().put(uncleJson(Constants.MAX_DIFFICULTY + 1)));
        assertThrows(IllegalArgumentException.class, () -> Block.of(badUncleDifficulty));

        // A single in-range uncle still decodes.
        JSONObject oneUncle = new JSONObject(base.toString());
        oneUncle.put("uncles", new JSONArray().put(uncleJson(5)));
        assertDoesNotThrow(() -> Block.of(oneUncle));
    }

    @Test
    void transactionFromJsonEnforcesPayloadCap() {
        // TransactionDto.readFrom caps payloads at MAX_DATA; the JSON path must match (audit F5).
        JSONObject tx = new JSONObject()
            .put("to", "00".repeat(25))
            .put("amount", 1L)
            .put("timestamp", "1")
            .put("fee", 0L)
            .put("from", "00".repeat(25))
            .put("signingKey", "00".repeat(32))
            .put("signature", "00".repeat(64))
            .put("kind", "CALL")
            .put("gasLimit", 1L)
            .put("gasPrice", 1L)
            .put("data", "00".repeat(TransactionDto.MAX_DATA + 1));
        assertThrows(IllegalArgumentException.class, () -> Transaction.of(tx));
        // Exactly at the cap passes the bound.
        tx.put("data", "00".repeat(TransactionDto.MAX_DATA));
        assertDoesNotThrow(() -> Transaction.of(tx));
    }
}
