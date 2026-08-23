package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.GenesisLedger;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.ledger.SnapshotLoader;
import rhizome.core.transaction.TransactionAmount;

class LedgerSnapshotTest {

    private static final String ADDR_A = "0011223344556677889900112233445566778899AA";
    private static final String ADDR_B = "00AABBCCDDEEFF00112233445566778899AABBCCDD";

    private static PublicAddress addr(String shortHex) {
        // pad/truncate to a valid 50-hex (25-byte) address
        StringBuilder sb = new StringBuilder(shortHex);
        while (sb.length() < 50) sb.append('0');
        return PublicAddress.of(sb.substring(0, 50));
    }

    @Test
    void jsonRoundTrip() {
        LedgerSnapshot snapshot = new LedgerSnapshot("pandanite", 536000, 1);
        snapshot.put(addr(ADDR_A), new TransactionAmount(500_000L));
        snapshot.put(addr(ADDR_B), new TransactionAmount(1L));

        JSONObject json = snapshot.toJson();
        LedgerSnapshot restored = LedgerSnapshot.fromJson(json);

        assertEquals(2, restored.size());
        assertEquals("pandanite", restored.source());
        assertEquals(536000, restored.sourceHeight());
        assertEquals(1, restored.chainId());
        assertEquals(500_000L, restored.balances().get(addr(ADDR_A)).amount());
        assertEquals(1L, restored.balances().get(addr(ADDR_B)).amount());
    }

    @Test
    void rejectsUnsignedAmountsWithTheHighBitSet() {
        // audit F3: a uint64 value beyond Long.MAX_VALUE parses via parseUnsignedLong as a NEGATIVE
        // long, and the ledger treats balances as signed 64-bit — such a snapshot entry must be
        // rejected at decode, not seeded as a negative genesis balance.
        long unsignedValue = 0xFFFFFFFFFFFFFFFFL; // -1 signed, 18446744073709551615 unsigned
        LedgerSnapshot snapshot = new LedgerSnapshot("pandanite", 0, 1);
        snapshot.put(addr(ADDR_A), new TransactionAmount(unsignedValue));

        JSONObject json = snapshot.toJson();
        assertTrue(json.getJSONObject("balances").getString(addr(ADDR_A).toHexString())
            .equals("18446744073709551615"));

        assertThrows(IllegalArgumentException.class, () -> LedgerSnapshot.fromJson(json));
    }

    @Test
    void rejectsCaseVariantSpellingsOfTheSameAddress() {
        // E2E-54: org.json does not case-fold object keys, and PublicAddress.of parses hex
        // case-insensitively — so one address spelled once upper- and once lowercase is two
        // JSON keys over ONE decoded address. fromJson must refuse the file outright rather
        // than silently keep whichever entry map iteration visits last (which discarded the
        // other amount, and for an unpinned profile nothing downstream caught the discard).
        String upper = addr(ADDR_A).toHexString(); // toHexString always renders uppercase
        String lower = upper.toLowerCase(java.util.Locale.ROOT);
        assertNotEquals(upper, lower, "this fixture must actually differ in case");
        JSONObject json = new JSONObject("{\"version\":1,\"source\":\"dup\",\"sourceHeight\":0,"
            + "\"chainId\":1,\"balances\":{"
            + "\"" + upper + "\":\"100\","
            + "\"" + lower + "\":\"200\""
            + "}}");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> LedgerSnapshot.fromJson(json));
        assertTrue(ex.getMessage().contains("duplicate"),
            "expected the duplicate-address rejection, got: " + ex.getMessage());
    }

    @Test
    void genesisLedgerRejectsNegativeSeededBalances() {
        // audit F3: even a programmatically built snapshot (bypassing fromJson's guard) must not
        // seed a negative balance into the ledger.
        LedgerSnapshot snapshot = new LedgerSnapshot("pandanite", 0, 1);
        snapshot.put(addr(ADDR_A), new TransactionAmount(-1L));
        assertThrows(IllegalArgumentException.class,
            () -> GenesisLedger.seed(new InMemoryLedger(), snapshot));
    }

    @Test
    void totalSupplySums() {
        LedgerSnapshot snapshot = new LedgerSnapshot("pandanite", 0, 1);
        snapshot.put(addr(ADDR_A), new TransactionAmount(500_000L));
        snapshot.put(addr(ADDR_B), new TransactionAmount(250_000L));
        assertEquals(750_000L, snapshot.totalSupply());
    }

    @Test
    void theShippedAllocationMatchesThePinnedGenesisSupplyExactly() throws IOException {
        // GENESIS-03 (docs/adversarial): the artifact and NetworkParameters.cleanMainnet()'s
        // pin are edited in lockstep; a partial edit of either one fails this test, so the
        // network definition and its own shipped allocation can never silently drift apart.
        LedgerSnapshot artifact = SnapshotLoader.fromResource("genesis/rhizome-mainnet.json");

        assertEquals(NetworkParameters.cleanMainnet().genesisSupply(), artifact.totalSupply());
        assertEquals(NetworkParameters.cleanMainnet().chainId(), artifact.chainId());
        // Provenance honesty (contracts/genesis-allocation-format.md §1): the shipped mainnet
        // allocation is an explicit, authored artifact, never claimed as a Pandanite dump.
        assertEquals("genesis-allocation:provisional", artifact.source());
    }

    @Test
    void resourceLoadingCarriesTheSameGuardsAsFileLoading() throws Exception {
        // Depth guard: fromResource runs the identical bracket-depth scan as fromFile, so a
        // classpath resource cannot bypass the recursive-parse stack-overflow guard.
        IOException depthEx = assertThrows(IOException.class,
            () -> SnapshotLoader.fromResource("genesis-fixtures/too-deep.json"));
        assertTrue(depthEx.getMessage().contains("nesting too deep"));

        // High-bit / invalid-total-supply guard: same LedgerSnapshot.fromJson rejection an
        // operator-supplied file would get (audit F3) — shipped-by-default is not a bypass.
        assertThrows(IllegalArgumentException.class,
            () -> SnapshotLoader.fromResource("genesis-fixtures/high-bit-balance.json"));

        // Size cap: fromResource has no cheap size probe the way fromFile does (Files.size),
        // so it must reject after reading MAX_SNAPSHOT_FILE_BYTES + 1 bytes. A sparse file is
        // generated at test time, directly into the already-compiled test-resources directory
        // (found via a real checked-in fixture), rather than checking a 512 MiB file into
        // source control — it still round-trips through the exact classpath-loading code path
        // fromResource uses in production.
        Path fixtureDir = testResourcesDir();
        Path oversized = fixtureDir.resolve("oversized.json");
        long overCap = 512L * 1024 * 1024 + 2; // one past fromResource's MAX_SNAPSHOT_FILE_BYTES + 1
        try {
            try (RandomAccessFile raf = new RandomAccessFile(oversized.toFile(), "rw")) {
                raf.setLength(overCap);
            }
            IOException sizeEx = assertThrows(IOException.class,
                () -> SnapshotLoader.fromResource("genesis-fixtures/oversized.json"));
            assertTrue(sizeEx.getMessage().contains("too large"));
        } finally {
            Files.deleteIfExists(oversized);
        }
    }

    @Test
    void forBootPrefersFileThenResourceThenEmpty(@TempDir Path tempDir) throws IOException {
        // (1) An explicit file path (the RHIZOME_SNAPSHOT override) wins even though
        // cleanMainnet() also declares a shipped genesisSnapshotResource: forBoot must not
        // silently prefer the profile's default artifact over an operator-supplied file.
        NetworkParameters mainnet = NetworkParameters.cleanMainnet();
        LedgerSnapshot fileContent = new LedgerSnapshot("forBoot-file-fixture", 0, mainnet.chainId());
        fileContent.put(addr(ADDR_A), new TransactionAmount(42L));
        Path snapshotFile = tempDir.resolve("snapshot.json");
        Files.writeString(snapshotFile, fileContent.toJson().toString());

        LedgerSnapshot fromFile = SnapshotLoader.forBoot(Optional.of(snapshotFile.toString()), mainnet);
        assertEquals("forBoot-file-fixture", fromFile.source());
        assertEquals(42L, fromFile.totalSupply());
        assertNotEquals(mainnet.genesisSupply(), fromFile.totalSupply());

        // (2) No file path, but the profile declares a resource: forBoot falls back to the
        // shipped default artifact (same one theShippedAllocationMatchesThePinnedGenesisSupplyExactly
        // pins above).
        LedgerSnapshot fromResource = SnapshotLoader.forBoot(Optional.empty(), mainnet);
        assertEquals("genesis-allocation:provisional", fromResource.source());
        assertEquals(mainnet.genesisSupply(), fromResource.totalSupply());

        // (3) No file path and no resource (testnet is deliberately unpinned with no shipped
        // artifact, research.md Decision 7): forBoot yields SnapshotLoader.empty()'s snapshot.
        NetworkParameters testnet = NetworkParameters.testnet();
        LedgerSnapshot empty = SnapshotLoader.forBoot(Optional.empty(), testnet);
        assertEquals("empty", empty.source());
        assertEquals(0L, empty.totalSupply());
        assertEquals(0, empty.size());
        assertEquals(testnet.chainId(), empty.chainId());
    }

    /** Resolves the compiled test-resources directory via a real checked-in fixture, so the
     *  oversized-resource fixture above can be dropped alongside it without depending on the
     *  test JVM's working directory. */
    private static Path testResourcesDir() throws Exception {
        URL url = LedgerSnapshotTest.class.getClassLoader().getResource("genesis-fixtures/too-deep.json");
        assertNotNull(url, "fixture resource missing from test classpath");
        return Path.of(url.toURI()).getParent();
    }
}
