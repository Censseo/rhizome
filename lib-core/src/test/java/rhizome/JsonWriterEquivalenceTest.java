package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPair;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.block.UncleRef;
import rhizome.core.box.BoxPayload;
import rhizome.core.common.Constants;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.serialization.JsonSink;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.core.transaction.TransactionKind;
import rhizome.core.transaction.dto.TransactionDto;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import rhizome.crypto.SHA256Hash;
import rhizome.crypto.SignatureScheme;

/**
 * Equivalence between the legacy {@code org.json}-tree {@code toJson()} and the new
 * {@code JsonSink}-based {@code writeJson()} writer.
 *
 * <p>{@link #assertSameJson(JSONObject, byte[])} is the shared helper this suite establishes and
 * a later step extends for {@code Block}: same key set (conditional-key drift), same type per
 * shared key (String vs Number is the dangerous mismatch), then a value-level
 * {@link JSONObject#similar} check with an actual diagnostic on failure — {@code similar} alone
 * swallows exceptions and returns bare {@code false}.
 *
 * <p>The Transaction matrix below covers: plain transfer under both signature schemes, both
 * payload-bearing kinds (DEPLOY, CALL) so every optional key is exercised, coinbase (the {@code
 * from == ""} case {@code app.js} depends on), and the unsigned self-authorized BOX_COLLECT that
 * BlockAssembler mints for rent collection — the one case where both independent conditional
 * blocks in {@code writeJsonBody} fire together.
 */
class JsonWriterEquivalenceTest {

    // ---- shared helper (grown by the Block equivalence step) --------------------------------

    static void assertSameJson(JSONObject legacy, byte[] written) {
        JSONObject actual = new JSONObject(new String(written, StandardCharsets.UTF_8));

        assertEquals(legacy.keySet(), actual.keySet(),
            () -> "key set mismatch: legacy=" + legacy.keySet() + " actual=" + actual.keySet());

        for (String key : legacy.keySet()) {
            Object legacyValue = legacy.get(key);
            Object actualValue = actual.get(key);
            String legacyType = typeCategory(legacyValue);
            String actualType = typeCategory(actualValue);
            assertEquals(legacyType, actualType, () -> "type mismatch for key '" + key + "': legacy="
                + legacyValue + " (" + legacyValue.getClass().getSimpleName() + "), actual="
                + actualValue + " (" + actualValue.getClass().getSimpleName() + ")");
        }

        assertTrue(legacy.similar(actual), () -> "json bodies differ: " + firstDiff(legacy, actual));
    }

    /** String / Boolean / Number (Integer, Long, BigInteger, ... normalize together) / null. */
    private static String typeCategory(Object value) {
        if (JSONObject.NULL.equals(value)) {
            return "null";
        }
        if (value instanceof String) {
            return "String";
        }
        if (value instanceof Boolean) {
            return "Boolean";
        }
        if (value instanceof Number) {
            return "Number";
        }
        return value.getClass().getName();
    }

    /** {@code JSONObject.similar} gives no diagnostic on failure, so re-walk the top-level keys
     *  to name the first one that actually differs. */
    private static String firstDiff(JSONObject legacy, JSONObject actual) {
        for (String key : legacy.keySet()) {
            Object l = legacy.get(key);
            Object a = actual.opt(key);
            if (!valuesEqual(l, a)) {
                return "key '" + key + "': legacy=" + l + " actual=" + a;
            }
        }
        return "no single differing top-level key found (nested structure differs)";
    }

    private static boolean valuesEqual(Object l, Object a) {
        if (l instanceof Number && a instanceof Number) {
            return new BigDecimal(l.toString()).compareTo(new BigDecimal(a.toString())) == 0;
        }
        return Objects.equals(l, a);
    }

    private static byte[] write(Transaction tx) {
        JsonSink sink = JsonSink.create(256);
        tx.writeJson(sink);
        return sink.toByteArray();
    }

    private static void assertNonceKeyIsAccountNonce(byte[] written) {
        JSONObject actual = new JSONObject(new String(written, StandardCharsets.UTF_8));
        assertTrue(actual.has("accountNonce"), "expected key 'accountNonce'");
        assertFalse(actual.has("nonce"), "the nonce key must never be plain 'nonce'");
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private static final byte[] COMMITMENT = commitment();

    private static byte[] commitment() {
        byte[] c = new byte[SignatureScheme.COMMITMENT_SIZE];
        Arrays.fill(c, (byte) 0x5a);
        return c;
    }

    /** 25-byte address whose hex encoding contains letters A-F — an all-digit fixture would pass
     *  with the wrong hex case and hide a bug. */
    private static PublicAddress hexLetterAddress() {
        byte[] bytes = new byte[PublicAddress.SIZE];
        Arrays.fill(bytes, (byte) 0xAB);
        return PublicAddress.of(bytes);
    }

    /** Builds and signs a transaction of any kind/scheme with the given content fields — "built
     *  for real" the same way {@code TransactionSchemeAgilityTest.signed(...)} does. */
    private static TransactionImpl buildSigned(SignatureScheme scheme, byte[] pq, TransactionKind kind,
            byte[] data, long gasLimit, long gasPrice, long amount, long fee, long nonce,
            long timestamp, PublicAddress to) {
        AsymmetricCipherKeyPair pair = generateKeyPair();
        PublicKey key = PublicKey.of(pair.getPublic());
        TransactionImpl tx = TransactionImpl.builder()
            .from(PublicAddress.of(key, scheme, pq))
            .to(to)
            .amount(new TransactionAmount(amount))
            .fee(new TransactionAmount(fee))
            .timestamp(timestamp)
            .chainId(7)
            .nonce(nonce)
            .signingKey(key)
            .scheme(scheme)
            .pqCommitment(pq == null ? new byte[0] : pq)
            .kind(kind)
            .data(data)
            .gasLimit(gasLimit)
            .gasPrice(gasPrice)
            .build();
        tx.sign(new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate()));
        return tx;
    }

    // ---- the six-transaction matrix ------------------------------------------------------------

    @Test
    void plainTransferEd25519() {
        Transaction tx = buildSigned(SignatureScheme.ED25519, null, TransactionKind.TRANSFER,
            new byte[0], 0, 0, 1234, 7, 11, 1_750_000_000_000L, hexLetterAddress());

        byte[] written = write(tx);
        assertSameJson(tx.toJson(), written);
        assertNonceKeyIsAccountNonce(written);
    }

    @Test
    void plainTransferPostQuantumScheme() {
        Transaction tx = buildSigned(SignatureScheme.ED25519_PQC, COMMITMENT, TransactionKind.TRANSFER,
            new byte[0], 0, 0, 1234, 7, 11, 1_750_000_000_000L, hexLetterAddress());

        byte[] written = write(tx);
        assertSameJson(tx.toJson(), written);
        assertNonceKeyIsAccountNonce(written);

        // classical fields aside, the scheme fields must actually be present on this branch.
        JSONObject actual = new JSONObject(new String(written, StandardCharsets.UTF_8));
        assertTrue(actual.has("sigScheme"));
        assertTrue(actual.has("pqCommitment"));
    }

    @Test
    void payloadBearingDeployEd25519() {
        Transaction tx = buildSigned(SignatureScheme.ED25519, null, TransactionKind.DEPLOY,
            new byte[] {1, 2, 3, 4}, 100_000, 5, 500, 3, 42, 1_750_000_000_000L, hexLetterAddress());

        byte[] written = write(tx);
        assertSameJson(tx.toJson(), written);
        assertNonceKeyIsAccountNonce(written);
    }

    @Test
    void payloadBearingCallPostQuantumScheme() {
        // Exercises every optional key at once: kind/gasLimit/gasPrice/data AND
        // sigScheme/pqCommitment together.
        Transaction tx = buildSigned(SignatureScheme.ED25519_PQC, COMMITMENT, TransactionKind.CALL,
            new byte[] {9, 8, 7}, 50_000, 2, 500, 3, 42, 1_750_000_000_000L, hexLetterAddress());

        byte[] written = write(tx);
        assertSameJson(tx.toJson(), written);
        assertNonceKeyIsAccountNonce(written);

        JSONObject actual = new JSONObject(new String(written, StandardCharsets.UTF_8));
        assertTrue(actual.has("kind"));
        assertTrue(actual.has("gasLimit"));
        assertTrue(actual.has("gasPrice"));
        assertTrue(actual.has("data"));
        assertTrue(actual.has("sigScheme"));
        assertTrue(actual.has("pqCommitment"));
    }

    @Test
    void coinbaseFromFieldIsExplicitlyEmptyString() {
        Transaction coinbase = Transaction.of(hexLetterAddress(), new TransactionAmount(5000));

        byte[] written = write(coinbase);
        assertSameJson(coinbase.toJson(), written);
        assertNonceKeyIsAccountNonce(written);

        // app.js's coinbase detection is `t.from === ''` — from must be the literal empty
        // string, never omitted and never null.
        JSONObject actual = new JSONObject(new String(written, StandardCharsets.UTF_8));
        assertEquals("", actual.getString("from"));
    }

    @Test
    void boxCollectIsUnsignedAndFiresBothConditionalBlocks() {
        // Mirrors BlockAssembler's rent-collection minting exactly (BlockAssembler ~93-118):
        // unsigned, self-authorized, from = PublicAddress.empty(). BOX_COLLECT != TRANSFER makes
        // hasPayload() true and isTransactionFee() is false, so both independent conditional
        // blocks in writeJsonBody fire on the same transaction.
        byte[] boxId = new byte[32];
        Arrays.fill(boxId, (byte) 0x7a);
        Transaction collect = TransactionImpl.builder()
            .kind(TransactionKind.BOX_COLLECT)
            .from(PublicAddress.empty())
            .to(hexLetterAddress())
            .amount(new TransactionAmount(0))
            .fee(new TransactionAmount(0))
            .isTransactionFee(false)
            .chainId(1)
            .nonce(0)
            .timestamp(1_750_000_000_000L)
            .data(BoxPayload.encodeTarget(boxId))
            .build();

        byte[] written = write(collect);
        assertSameJson(collect.toJson(), written);
        assertNonceKeyIsAccountNonce(written);

        // The trap: the unsigned signingKey is PublicKey.empty(), whose toHexString() returns ""
        // rather than hex of the zero-filled encoding toBytes() falls back to — a writer that
        // hex-encodes toBytes() unconditionally would silently diverge only in this one case.
        JSONObject actual = new JSONObject(new String(written, StandardCharsets.UTF_8));
        assertEquals("", actual.getString("signingKey"));
    }

    // ---- boundary values, layered onto the payload-bearing kinds -----------------------------

    @Test
    void boundaryValuesAtTheMinimum() {
        // amount/fee/nonce/timestamp all zero, empty data.
        Transaction tx = buildSigned(SignatureScheme.ED25519, null, TransactionKind.CALL,
            new byte[0], 0, 0, 0, 0, 0, 0, hexLetterAddress());

        byte[] written = write(tx);
        assertSameJson(tx.toJson(), written);
        assertNonceKeyIsAccountNonce(written);
    }

    @Test
    void boundaryValuesAtTheMaximum() {
        byte[] maxData = new byte[TransactionDto.MAX_DATA];
        for (int i = 0; i < maxData.length; i++) {
            // Bytes >= 0xA0 throughout: an all-digit-hex fixture would pass with the wrong case.
            maxData[i] = (byte) (0xA0 + (i % 0x60));
        }
        // Timestamp above 2^53 so a JS Number would lose precision if it were ever emitted as a
        // bare number instead of a string — the exact reason timestamp is string-serialized.
        long aboveTwoPow53 = (1L << 53) + 12345L;

        Transaction tx = buildSigned(SignatureScheme.ED25519_PQC, COMMITMENT, TransactionKind.DEPLOY,
            maxData, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
            aboveTwoPow53, hexLetterAddress());

        byte[] written = write(tx);
        assertSameJson(tx.toJson(), written);
        assertNonceKeyIsAccountNonce(written);
    }

    // ---- Block matrix ---------------------------------------------------------------------

    private static byte[] write(Block block) {
        JsonSink sink = JsonSink.create(4096);
        block.writeJson(sink);
        return sink.toByteArray();
    }

    /** A 32-byte hash filled with a single byte >= 0xA0 — same "hex letters, not just digits"
     *  guard as {@link #hexLetterAddress()}. */
    private static SHA256Hash filledHash(int fill) {
        byte[] bytes = new byte[SHA256Hash.SIZE];
        Arrays.fill(bytes, (byte) fill);
        return SHA256Hash.of(bytes);
    }

    private static UncleRef uncle(int salt) {
        return new UncleRef(filledHash(0xA0 + (salt % 0x60)), 5, hexLetterAddress());
    }

    private static List<UncleRef> unclesOfSize(int n) {
        List<UncleRef> uncles = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            uncles.add(uncle(i));
        }
        return uncles;
    }

    /** Mirrors {@code BlockAssembler}'s unsigned, self-authorized rent-collection minting (see
     *  {@link #boxCollectIsUnsignedAndFiresBothConditionalBlocks()}) — used here as one element
     *  of a mixed-kind transaction list rather than the sole subject of the test. */
    private static Transaction buildBoxCollect() {
        byte[] boxId = new byte[32];
        Arrays.fill(boxId, (byte) 0x7a);
        return TransactionImpl.builder()
            .kind(TransactionKind.BOX_COLLECT)
            .from(PublicAddress.empty())
            .to(hexLetterAddress())
            .amount(new TransactionAmount(0))
            .fee(new TransactionAmount(0))
            .isTransactionFee(false)
            .chainId(1)
            .nonce(0)
            .timestamp(1_750_000_000_000L)
            .data(BoxPayload.encodeTarget(boxId))
            .build();
    }

    /** The three transaction-count/shape variants the Block matrix crosses: none, coinbase only,
     *  and a mix reusing shapes from the Transaction matrix above (plain transfer, PQ-scheme
     *  payload-bearing call, and the unsigned BOX_COLLECT) — insertion order matters and must
     *  survive {@code writeJsonBody}'s per-transaction {@code writeJson} unchanged. */
    private static List<List<Transaction>> transactionVariants() {
        Transaction coinbase = Transaction.of(hexLetterAddress(), new TransactionAmount(5000));
        return List.of(
            List.of(),
            List.of(coinbase),
            List.of(
                coinbase,
                buildSigned(SignatureScheme.ED25519, null, TransactionKind.TRANSFER,
                    new byte[0], 0, 0, 1234, 7, 11, 1_750_000_000_000L, hexLetterAddress()),
                buildSigned(SignatureScheme.ED25519_PQC, COMMITMENT, TransactionKind.CALL,
                    new byte[] {9, 8, 7}, 50_000, 2, 500, 3, 42, 1_750_000_000_000L, hexLetterAddress()),
                buildBoxCollect()
            )
        );
    }

    private static BlockImpl block(SHA256Hash stateRoot, int vote, List<UncleRef> uncles,
            List<Transaction> transactions) {
        return (BlockImpl) BlockImpl.builder()
            .id(42)
            .timestamp(1_750_000_000_000L)
            .difficulty(4)
            .merkleRoot(filledHash(0xB1))
            .lastBlockHash(filledHash(0xC2))
            .nonce(filledHash(0xD3))
            .stateRoot(stateRoot)
            .vote(vote)
            .transactions(new ArrayList<>(transactions))
            .uncles(new ArrayList<>(uncles))
            .build();
    }

    /** Cross product: {@code stateRoot} absent/present x {@code vote} in {0, +1, -2} x
     *  {@code uncles} in {0, 1, {@link Constants#MAX_UNCLES_PER_BLOCK}} x the three transaction
     *  variants above. */
    private static List<BlockImpl> blockMatrix() {
        List<SHA256Hash> stateRoots = List.of(SHA256Hash.empty(), filledHash(0xA5));
        int[] votes = {0, 1, -2};
        List<List<UncleRef>> uncleVariants = List.of(
            List.of(), unclesOfSize(1), unclesOfSize(Constants.MAX_UNCLES_PER_BLOCK));

        List<BlockImpl> blocks = new ArrayList<>();
        for (SHA256Hash stateRoot : stateRoots) {
            for (int vote : votes) {
                for (List<UncleRef> uncles : uncleVariants) {
                    for (List<Transaction> transactions : transactionVariants()) {
                        blocks.add(block(stateRoot, vote, uncles, transactions));
                    }
                }
            }
        }
        return blocks;
    }

    @Test
    void blockMatrixEquivalence() {
        List<BlockImpl> blocks = blockMatrix();
        assertTrue(blocks.size() >= 2 * 3 * 3 * 3, "matrix collapsed: " + blocks.size() + " cases");
        for (BlockImpl b : blocks) {
            byte[] written = write(b);
            assertSameJson(b.toJson(), written);
        }
    }

    /**
     * Invariant lock, not a cosmetic check: {@code ChainSynchronizer.java:178} compares
     * {@code engine.headerAt(h).hash()} against {@code peer.blockHash(h)}, and
     * {@code HttpPeerSource.java:164} implements {@code blockHash} by parsing our own
     * {@code /block} response body and calling {@code Block.of(PeerJson.parseObject(body)).hash()}
     * — i.e. fork-point bisection re-derives a header hash from exactly the bytes
     * {@code writeJson()} produces. If a written block round-tripped through
     * {@link Block#fromJson} to a DIFFERENT hash than the block that produced it, an honest
     * node's own {@code /block} response would make peers compute the wrong hash for it during
     * bisection — a consensus-visible bug, not a formatting one. This test is what proves
     * {@code writeJson()} cannot introduce that divergence, across the same matrix the
     * equivalence test above uses.
     */
    @Test
    void blockMatrixHashRoundTrips() {
        List<BlockImpl> blocks = blockMatrix();
        for (BlockImpl b : blocks) {
            byte[] written = write(b);
            String json = new String(written, StandardCharsets.UTF_8);
            Block roundTripped = Block.of(new JSONObject(json));
            assertEquals(b.hash(), roundTripped.hash(),
                () -> "hash round-trip mismatch for block written as " + json);
        }
    }
}
