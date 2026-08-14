package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.activej.http.HttpHeaders;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.core.box.Box;
import rhizome.core.box.BoxRegister;
import rhizome.core.box.BoxRegisterType;
import rhizome.core.box.BoxStore;
import rhizome.core.box.DefaultBoxProcessor;
import rhizome.core.box.InMemoryBoxStore;
import rhizome.core.box.ScanPredicate;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.MemPool;
import rhizome.core.token.DefaultTokenProcessor;
import rhizome.core.token.InMemoryTokenStore;
import rhizome.core.token.TokenBalanceKey;
import rhizome.core.token.TokenId;
import rhizome.core.token.TokenMeta;
import rhizome.core.token.TokenStore;

/**
 * Equivalence between {@link BoxApi}/{@link TokenApi}'s {@link rhizome.core.serialization.JsonSink}-
 * based writers and the legacy {@code org.json}-tree construction they replaced (copied verbatim
 * from the pre-migration source, the same convention {@code ExplorerJsonEquivalenceTest} and
 * {@code JsonWriterEquivalenceTest} use). This is the highest-risk file in the migration: box
 * registers and token symbol/name carry chain-controlled — in the register case, attacker- or
 * contract-controlled — strings and bytes, and the hex case is deliberately mixed within a single
 * object (see {@link BoxApi} and {@link TokenApi}'s class Javadocs).
 *
 * <p>Handlers are called directly rather than through the servlet/routing layer (mirroring {@code
 * ApiResponsesJsonSinkTest}): {@link BoxApi} and {@link TokenApi}'s methods are package-private
 * statics, the response body is already fully materialized ({@code ApiResponses.json(JsonSink)}
 * attaches a plain {@code ByteBuf}, not a stream), so no {@code Eventloop}/reactor is needed to
 * read it back. Fixtures are seeded directly into the in-memory box/token stores via {@code
 * applyBlock}, bypassing on-chain transaction validation — the point is exercising {@code
 * writeBoxJson}/{@code writeTokenJson} against arbitrary (including structurally invalid) register
 * content, which a real {@code BOX_CREATE} transaction could never carry onto the chain in the
 * first place ({@link BoxRegister#validate()} would reject it before it were ever mined).
 */
class BoxTokenJsonEquivalenceTest {

    private NetworkParameters params;
    private InMemoryBoxStore boxStore;
    private InMemoryTokenStore tokenStore;
    private NodeService node;
    private long seedHeight;

    @BeforeEach
    void setUp() {
        params = NetworkParameters.testnet();
        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, params.chainId());
        var verifier = new SignatureVerifier();
        boxStore = new InMemoryBoxStore();
        tokenStore = new InMemoryTokenStore();
        var boxProcessor = new DefaultBoxProcessor(boxStore, params);
        var tokenProcessor = new DefaultTokenProcessor(tokenStore, params);
        ChainEngine engine = ChainEngine.init(params, new InMemoryLedger(), new InMemoryChainStore(),
            snapshot, null, () -> 0L, verifier, null, boxProcessor, tokenProcessor);
        MemPool mempool = new MemPool(params, verifier, engine, 1000);
        node = new NodeService(engine, mempool);
    }

    // ---- fixture plumbing: bypass consensus and write straight into the stores --------------

    private void seedBoxes(Box... boxes) {
        List<BoxStore.BoxMutation> mutations = new ArrayList<>();
        for (Box b : boxes) {
            mutations.add(BoxStore.BoxMutation.write(b));
        }
        boxStore.applyBlock(++seedHeight, mutations);
    }

    private void seedToken(TokenMeta meta, PublicAddress holder, long balance) {
        List<TokenStore.TokenOp> ops = new ArrayList<>();
        ops.add(new TokenStore.TokenOp.MetaSet(meta));
        if (holder != null) {
            ops.add(new TokenStore.TokenOp.BalanceSet(TokenBalanceKey.of(meta.id(), holder), balance));
        }
        tokenStore.applyBlock(++seedHeight, ops);
    }

    private static byte[] filledId(int fill) {
        byte[] b = new byte[32];
        Arrays.fill(b, (byte) fill);
        return b;
    }

    /** 25-byte address whose hex encoding contains letters A-F (bytes >= 0xA0), same "hex letters,
     *  not just digits" guard {@code JsonWriterEquivalenceTest} uses — an all-digit fixture would
     *  pass with the wrong hex case and hide a bug. */
    private static PublicAddress filledAddress(int fill) {
        byte[] b = new byte[PublicAddress.SIZE];
        Arrays.fill(b, (byte) fill);
        return PublicAddress.of(b);
    }

    private static String body(HttpResponse r) {
        return r.getBody().getString(StandardCharsets.UTF_8);
    }

    // ---- shared assertion helpers (mirrors ExplorerJsonEquivalenceTest / JsonWriterEquivalenceTest) --

    private static void assertJsonHeaders(HttpResponse response) {
        assertEquals("application/json; charset=utf-8", response.getHeader(HttpHeaders.CONTENT_TYPE),
            "Content-Type must be application/json with a utf-8 charset");
        assertEquals("nosniff", response.getHeader(ApiResponses.H_XCTO),
            "X-Content-Type-Options must be nosniff");
    }

    private static void assertSameJson(JSONObject legacy, HttpResponse response) {
        assertJsonHeaders(response);
        JSONObject actual = new JSONObject(body(response));

        assertEquals(legacy.keySet(), actual.keySet(),
            () -> "key set mismatch: legacy=" + legacy.keySet() + " actual=" + actual.keySet());

        for (String k : legacy.keySet()) {
            Object legacyValue = legacy.get(k);
            Object actualValue = actual.get(k);
            String legacyType = typeCategory(legacyValue);
            String actualType = typeCategory(actualValue);
            assertEquals(legacyType, actualType, () -> "type mismatch for key '" + k + "': legacy="
                + legacyValue + " (" + legacyValue.getClass().getSimpleName() + "), actual="
                + actualValue + " (" + actualValue.getClass().getSimpleName() + ")");
        }

        assertTrue(legacy.similar(actual), () -> "json bodies differ: " + firstDiff(legacy, actual));
    }

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

    private static String firstDiff(JSONObject legacy, JSONObject actual) {
        for (String key : legacy.keySet()) {
            Object l = legacy.get(key);
            Object a = actual.opt(key);
            if (!java.util.Objects.equals(l, a)) {
                return "key '" + key + "': legacy=" + l + " actual=" + a;
            }
        }
        return "no single differing top-level key found (nested structure differs)";
    }

    // ---- legacy oracles: the pre-migration toJson()-based construction, copied verbatim -------

    /** {@code ApiResponses.hex}'s legacy encoder (lowercase, {@code Character.forDigit}), copied
     *  here rather than reused so the oracle does not depend on the migrated production code
     *  (same reasoning as {@code ExplorerJsonEquivalenceTest#legacySha256Hex}). */
    private static String legacyHexLower(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static JSONObject legacyBoxJson(Box b, long storagePeriodBlocks) {
        JSONArray registers = new JSONArray();
        for (BoxRegister r : b.registers()) {
            JSONObject reg = new JSONObject()
                .put("type", r.type().name())
                .put("hex", legacyHexLower(r.payload()));
            if (r.type() == BoxRegisterType.STRING) {
                reg.put("string", new String(r.payload(), StandardCharsets.UTF_8));
            }
            registers.put(reg);
        }
        return new JSONObject()
            .put("id", legacyHexLower(b.id()))
            .put("owner", b.owner().toHexString())
            .put("value", b.value())
            .put("createdHeight", b.createdHeight())
            .put("rentPaidHeight", b.rentPaidHeight())
            .put("expiresAtHeight", b.expiryHeight(storagePeriodBlocks))
            .put("sizeBytes", b.serializedSize())
            .put("registers", registers);
    }

    private static JSONObject legacyBoxesResponse(byte[] owner, List<Box> boxes, long period, byte[] last) {
        JSONArray arr = new JSONArray();
        for (Box b : boxes) {
            arr.put(legacyBoxJson(b, period));
        }
        JSONObject result = new JSONObject()
            .put("owner", rhizome.core.common.Utils.bytesToHex(owner))
            .put("boxes", arr);
        if (last != null) {
            result.put("next", rhizome.core.common.Utils.bytesToHex(last));
        }
        return result;
    }

    private static JSONObject legacyScanBoxesResponse(List<Box> matches, long period, byte[] nextCursor) {
        JSONArray arr = new JSONArray();
        for (Box b : matches) {
            arr.put(legacyBoxJson(b, period));
        }
        JSONObject result = new JSONObject().put("boxes", arr);
        if (nextCursor != null) {
            result.put("next", legacyHexLower(nextCursor));
        }
        return result;
    }

    private static JSONObject legacyScanListResponse(java.util.Map<Integer, ScanPredicate> scans) {
        JSONArray arr = new JSONArray();
        scans.forEach((id, predicate) ->
            arr.put(new JSONObject().put("scanId", id).put("predicate", predicate.toJson())));
        return new JSONObject().put("scans", arr);
    }

    private static JSONObject legacyTokenJson(TokenMeta meta) {
        return new JSONObject()
            .put("id", legacyHexLower(meta.id().toBytes()))
            .put("minter", meta.minter().toHexString())
            .put("symbol", meta.symbol())
            .put("name", meta.name())
            .put("decimals", meta.decimals())
            .put("totalSupply", meta.totalSupply())
            .put("createdHeight", meta.createdHeight());
    }

    // ---- /box: register-type matrix, including zero registers ---------------------------------

    @Test
    void boxEndpointZeroRegisters() {
        Box b = new Box(filledId(0xA1), filledAddress(0xA2), 5000L, 10L, 20L, List.of());
        seedBoxes(b);

        HttpResponse response = BoxApi.box(node,
            HttpRequest.get("http://x/box?id=" + rhizome.core.common.Utils.bytesToHex(b.id())).build());
        assertEquals(200, response.getCode());
        assertSameJson(legacyBoxJson(b, params.storagePeriodBlocks()), response);
    }

    @Test
    void boxEndpointOneOfEachRegisterType() {
        List<BoxRegister> registers = List.of(
            new BoxRegister(BoxRegisterType.BYTES, new byte[] {(byte) 0xAB, (byte) 0xCD, 0x00, 0x01}),
            BoxRegister.i64(-123_456_789L),
            BoxRegister.bool(true),
            new BoxRegister(BoxRegisterType.ADDRESS, filledAddress(0xB2).toBytes()),
            new BoxRegister(BoxRegisterType.HASH32, filledId(0xC3)),
            BoxRegister.string("agent-\u00e9-memory"));
        Box b = new Box(filledId(0xA5), filledAddress(0xA6), 7777L, 1L, 2L, registers);
        seedBoxes(b);

        HttpResponse response = BoxApi.box(node,
            HttpRequest.get("http://x/box?id=" + rhizome.core.common.Utils.bytesToHex(b.id())).build());
        assertEquals(200, response.getCode());
        assertSameJson(legacyBoxJson(b, params.storagePeriodBlocks()), response);

        // Cheap, explicit hex-case lock on top of the structural check above: id/register-hex
        // lowercase, owner uppercase, within the SAME object.
        JSONObject actual = new JSONObject(body(response));
        assertTrue(actual.getString("id").chars().noneMatch(Character::isUpperCase));
        assertTrue(actual.getString("owner").chars().filter(Character::isLetter)
            .allMatch(Character::isUpperCase));
        JSONArray regs = actual.getJSONArray("registers");
        for (int i = 0; i < regs.length(); i++) {
            assertTrue(regs.getJSONObject(i).getString("hex").chars().noneMatch(Character::isUpperCase));
        }
    }

    // ---- /box: STRING register escaping corpus, including invalid UTF-8 -----------------------

    private static List<byte[]> stringRegisterCorpus() {
        List<byte[]> corpus = new ArrayList<>();
        corpus.add(new byte[0]);                                            // empty
        corpus.add("</script>".getBytes(StandardCharsets.UTF_8));
        corpus.add("\"".getBytes(StandardCharsets.UTF_8));                   // double quote
        corpus.add("\\".getBytes(StandardCharsets.UTF_8));                   // backslash
        corpus.add(new byte[] {0x00});                                       // NUL byte
        corpus.add("a\u2028b".getBytes(StandardCharsets.UTF_8));                // U+2028 range
        // Invalid UTF-8: 0xFF is never a valid UTF-8 lead byte, 0xC3 with a non-continuation
        // trailer is an invalid 2-byte sequence. new String(_, UTF_8) replaces each with U+FFFD.
        corpus.add(new byte[] {(byte) 0xFF, (byte) 0xFE, 0x41});
        corpus.add(new byte[] {(byte) 0xC3, 0x28});
        return corpus;
    }

    @Test
    void boxStringRegisterEscapingCorpusIncludingInvalidUtf8() {
        int fill = 0xA7;
        for (byte[] payload : stringRegisterCorpus()) {
            Box b = new Box(filledId(fill), filledAddress(0xA8), 1L, 1L, 1L,
                List.of(new BoxRegister(BoxRegisterType.STRING, payload)));
            seedBoxes(b);

            HttpResponse response = BoxApi.box(node,
                HttpRequest.get("http://x/box?id=" + rhizome.core.common.Utils.bytesToHex(b.id())).build());
            assertEquals(200, response.getCode());
            assertSameJson(legacyBoxJson(b, params.storagePeriodBlocks()), response);

            // The decode-before-escape claim, locked down explicitly: a String built from invalid
            // UTF-8 already contains U+FFFD, and that is exactly what round-trips through the wire.
            String decoded = new String(payload, StandardCharsets.UTF_8);
            JSONObject actual = new JSONObject(body(response));
            assertEquals(decoded, actual.getJSONArray("registers").getJSONObject(0).getString("string"),
                "register string must equal the UTF_8-decoded payload for " + Arrays.toString(payload));

            fill++;
        }
    }

    // ---- /boxes: uppercase next cursor, present and absent -------------------------------------

    @Test
    void boxesEndpointNextCursorPresentIsUppercase() {
        PublicAddress owner = filledAddress(0xB1);
        Box b1 = new Box(filledId(0xB2), owner, 1L, 1L, 1L, List.of());
        Box b2 = new Box(filledId(0xB3), owner, 2L, 1L, 1L, List.of());
        seedBoxes(b1, b2);

        HttpResponse response = BoxApi.boxes(node, HttpRequest.get(
            "http://x/boxes?owner=" + owner.toHexString() + "&limit=1").build());
        assertEquals(200, response.getCode());

        // boxIdsByOwner sorts by id bytes ascending (InMemoryBoxStore#boxIdsByOwner); reproduce
        // that ordering rather than guessing it, so the legacy oracle sees the exact same page.
        List<byte[]> sortedIds = node.boxIdsByOwner(owner.toBytes(), null, 1);
        assertEquals(1, sortedIds.size());
        Box expectedFirst = Arrays.equals(sortedIds.get(0), b1.id()) ? b1 : b2;

        JSONObject legacy = legacyBoxesResponse(owner.toBytes(), List.of(expectedFirst),
            params.storagePeriodBlocks(), sortedIds.get(0));
        assertSameJson(legacy, response);

        JSONObject actual = new JSONObject(body(response));
        assertTrue(actual.has("next"), "next cursor must be present when more results remain");
        String next = actual.getString("next");
        assertTrue(next.chars().filter(Character::isLetter).allMatch(Character::isUpperCase),
            "/boxes next cursor must be uppercase hex, got " + next);
    }

    @Test
    void boxesEndpointNextCursorAbsentWhenOwnerHasNoBoxes() {
        // The legacy loop sets `last` unconditionally on every iterated id (not only when the
        // page was truncated by `limit`), so `next` is present whenever `boxes` is non-empty —
        // "absent" only happens when boxIdsByOwner returns nothing at all. Verified against the
        // pre-migration source, not assumed: this is not a "has more pages" cursor.
        PublicAddress owner = filledAddress(0xB4);

        HttpResponse response = BoxApi.boxes(node, HttpRequest.get(
            "http://x/boxes?owner=" + owner.toHexString() + "&limit=50").build());
        assertEquals(200, response.getCode());
        JSONObject legacy = legacyBoxesResponse(owner.toBytes(), List.of(), params.storagePeriodBlocks(), null);
        assertSameJson(legacy, response);

        JSONObject actual = new JSONObject(body(response));
        assertFalse(actual.has("next"), "next cursor must be absent when the owner has no boxes at all");
        assertEquals(0, actual.getJSONArray("boxes").length());
    }

    // ---- /scan/boxes: lowercase next cursor, present and absent, plus /scan/list --------------

    private static final ScanRegistry.Owner SCAN_OWNER = ScanRegistry.Owner.of("scan-owner", null);

    @Test
    void scanBoxesEndpointNextCursorPresentIsLowercase() {
        PublicAddress owner = filledAddress(0xC1);
        Box b1 = new Box(filledId(0xC2), owner, 1L, 1L, 1L, List.of());
        Box b2 = new Box(filledId(0xC3), owner, 2L, 1L, 1L, List.of());
        seedBoxes(b1, b2);
        ScanPredicate predicate = new ScanPredicate.OwnerEquals(owner.toBytes());
        int scanId = node.registerScan(SCAN_OWNER, predicate);

        HttpResponse response = BoxApi.scanBoxes(node, SCAN_OWNER, HttpRequest.get(
            "http://x/scan/boxes?scanId=" + scanId + "&limit=1").build());
        assertEquals(200, response.getCode());

        var page = node.scan(predicate, null, 1);
        JSONObject legacy = legacyScanBoxesResponse(page.matches(), params.storagePeriodBlocks(), page.nextCursor());
        assertSameJson(legacy, response);

        JSONObject actual = new JSONObject(body(response));
        assertTrue(actual.has("next"), "next cursor must be present when more matches remain");
        String next = actual.getString("next");
        assertTrue(next.chars().filter(Character::isLetter).allMatch(Character::isLowerCase),
            "/scan/boxes next cursor must be lowercase hex, got " + next);
    }

    @Test
    void scanBoxesEndpointNextCursorAbsentWhenExhausted() {
        PublicAddress owner = filledAddress(0xC4);
        Box b1 = new Box(filledId(0xC5), owner, 1L, 1L, 1L, List.of());
        seedBoxes(b1);
        ScanPredicate predicate = new ScanPredicate.OwnerEquals(owner.toBytes());
        int scanId = node.registerScan(SCAN_OWNER, predicate);

        HttpResponse response = BoxApi.scanBoxes(node, SCAN_OWNER, HttpRequest.get(
            "http://x/scan/boxes?scanId=" + scanId + "&limit=50").build());
        assertEquals(200, response.getCode());

        var page = node.scan(predicate, null, 50);
        JSONObject legacy = legacyScanBoxesResponse(page.matches(), params.storagePeriodBlocks(), page.nextCursor());
        assertSameJson(legacy, response);

        JSONObject actual = new JSONObject(body(response));
        assertFalse(actual.has("next"), "next cursor must be absent once the scan's matches are exhausted");
    }

    @Test
    void scanListEndpointIncludesNestedCombinatorPredicateJson() {
        ScanPredicate leaf1 = new ScanPredicate.OwnerEquals(filledAddress(0xD1).toBytes());
        ScanPredicate leaf2 = new ScanPredicate.RegisterContains(0,
            "oracle".getBytes(StandardCharsets.UTF_8));
        // Exercises ScanPredicate.writeJson's recursive combinator path (And nesting an Or).
        ScanPredicate nested = new ScanPredicate.And(List.of(leaf1, new ScanPredicate.Or(List.of(leaf1, leaf2))));
        node.registerScan(SCAN_OWNER, leaf1);
        node.registerScan(SCAN_OWNER, nested);

        HttpResponse response = BoxApi.scanList(node, SCAN_OWNER);
        assertEquals(200, response.getCode());
        JSONObject legacy = legacyScanListResponse(node.scansOf(SCAN_OWNER));
        assertSameJson(legacy, response);
    }

    // ---- /token, /token_balance, /tokens --------------------------------------------------------

    private static List<String> stringEscapingCorpus() {
        return List.of("", "</script>", "\"", "\\", "\u0000text", "a\u2028b", "PNDA");
    }

    @Test
    void tokenEndpointMatchesLegacyAcrossSymbolNameEscapingCorpus() {
        int fill = 0xE1;
        for (String symbol : stringEscapingCorpus()) {
            for (String name : stringEscapingCorpus()) {
                TokenMeta meta = new TokenMeta(TokenId.of(filledId(fill)), filledAddress(0xE2), symbol, name,
                    8, 1_000_000L, 5L);
                seedToken(meta, null, 0);

                HttpResponse response = TokenApi.token(node, HttpRequest.get(
                    "http://x/token?id=" + rhizome.core.common.Utils.bytesToHex(meta.id().toBytes())).build());
                assertEquals(200, response.getCode());
                assertSameJson(legacyTokenJson(meta), response);

                fill = (fill + 1) & 0xFF;
                if (fill < 0xA0) {
                    fill += 0xA0; // stay in the >= 0xA0 fixture range (hex-letter guard)
                }
            }
        }
    }

    @Test
    void tokenBalanceEndpointIsLowercaseHex() {
        TokenMeta meta = new TokenMeta(TokenId.of(filledId(0xE5)), filledAddress(0xE6), "PNDA", "Panda Coin",
            8, 1_000_000L, 1L);
        PublicAddress holder = filledAddress(0xE7);
        seedToken(meta, holder, 12345L);

        HttpResponse response = TokenApi.tokenBalance(node, HttpRequest.get(
            "http://x/token_balance?id=" + rhizome.core.common.Utils.bytesToHex(meta.id().toBytes())
                + "&address=" + holder.toHexString()).build());
        assertEquals(200, response.getCode());
        JSONObject legacy = new JSONObject()
            .put("token", legacyHexLower(meta.id().toBytes()))
            .put("address", legacyHexLower(holder.toBytes()))
            .put("balance", node.tokenBalance(TokenBalanceKey.of(meta.id(), holder)));
        assertSameJson(legacy, response);

        JSONObject actual = new JSONObject(body(response));
        assertTrue(actual.getString("token").chars().noneMatch(Character::isUpperCase));
        assertTrue(actual.getString("address").chars().noneMatch(Character::isUpperCase));
    }

    @Test
    void tokensEndpointByMinterHasNoBalanceFieldByHolderDoes() {
        PublicAddress minter = filledAddress(0xE8);
        PublicAddress holder = filledAddress(0xE9);
        TokenMeta meta = new TokenMeta(TokenId.of(filledId(0xEA)), minter, "PNDA", "Panda Coin", 8, 500L, 1L);
        seedToken(meta, holder, 250L);

        HttpResponse byMinter = TokenApi.tokens(node,
            HttpRequest.get("http://x/tokens?minter=" + minter.toHexString()).build());
        assertEquals(200, byMinter.getCode());
        JSONObject legacyByMinter = legacyTokensResponse(node, List.of(meta.id().toBytes()), null);
        assertSameJson(legacyByMinter, byMinter);
        JSONObject actualByMinter = new JSONObject(body(byMinter));
        assertFalse(actualByMinter.getJSONArray("tokens").getJSONObject(0).has("balance"),
            "minter listing must not carry a balance field");

        HttpResponse byHolder = TokenApi.tokens(node,
            HttpRequest.get("http://x/tokens?holder=" + holder.toHexString()).build());
        assertEquals(200, byHolder.getCode());
        JSONObject legacyByHolder = legacyTokensResponse(node, List.of(meta.id().toBytes()), holder.toBytes());
        assertSameJson(legacyByHolder, byHolder);
        JSONObject actualByHolder = new JSONObject(body(byHolder));
        assertTrue(actualByHolder.getJSONArray("tokens").getJSONObject(0).has("balance"),
            "holder listing must carry a balance field");
        assertEquals(250L, actualByHolder.getJSONArray("tokens").getJSONObject(0).getLong("balance"));
    }

    private static JSONObject legacyTokensResponse(NodeService node, List<byte[]> ids, byte[] holderKey) {
        JSONArray arr = new JSONArray();
        for (byte[] id : ids) {
            TokenMeta meta = node.tokenMeta(TokenId.of(id));
            if (meta != null) {
                JSONObject entry = legacyTokenJson(meta);
                if (holderKey != null) {
                    entry.put("balance", node.tokenBalance(TokenBalanceKey.of(TokenId.of(id), PublicAddress.of(holderKey))));
                }
                arr.put(entry);
            }
        }
        return new JSONObject().put("tokens", arr);
    }
}
