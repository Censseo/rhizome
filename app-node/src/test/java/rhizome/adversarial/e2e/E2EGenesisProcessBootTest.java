package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.blockchain.GenesisBlock;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.LedgerSnapshot;

/**
 * Boot behaviour that only a genuine, separate OS process can prove: that a misconfigured node
 * never opens a socket at all, or that {@code NodeConfig.fromEnv()}'s real {@code System.getenv}
 * plumbing and {@code SnapshotLoader.forBoot}'s selection order agree with the pure-function unit
 * suites once a real shell environment is the thing setting the variables.
 *
 * <p>Every other suite in this package (and the whole rest of {@code rhizome.adversarial.e2e})
 * proves the assembled node in-JVM: a real {@link rhizome.node.RhizomeNode} object, real RocksDB,
 * real sockets, sharing the test JVM. That is indistinguishable from production for consensus and
 * networking, but it cannot show "the process never bound the port" (an in-JVM
 * {@code RhizomeNode.start()} that throws simply never assigns its {@code NodeRuntime} field —
 * there is no separate process outside to observe never having listened), and every other suite's
 * environment-derived config actually goes through {@code NodeConfig.fromEnv(UnaryOperator)}'s
 * test-only injected-lookup overload, never the real {@code System.getenv()}-backed
 * {@code NodeConfig.fromEnv()} the shipped {@code RhizomeNode.main} calls. {@link ProcessHarness}
 * is the one piece of infrastructure in this phase that closes both gaps, by launching
 * {@code rhizome.node.RhizomeNode.main(String[])} as a real {@code java -cp ...} child process with
 * real environment variables, a real stdout/stderr pair and a real exit code.
 */
class E2EGenesisProcessBootTest {

    @TempDir
    Path tempDir;

    /** How long a scenario waits for a healthy child's HTTP port to come up. Empirically a cold
     *  boot with no snapshot resolves in well under a second; this leaves generous headroom for a
     *  loaded CI box without inflating the failure-path budgets below, which do not need it. */
    private static final long BOOT_WAIT_MS = 15_000;

    /** How long a scenario waits to confirm a fail-fast child's port NEVER opens. Empirically a
     *  rejected config or an oversized/missing snapshot fails during argument parsing (well under
     *  200 ms, before any socket call is even reachable) — this is a full order of magnitude more
     *  than that observed cost, not a bare-minimum guess, while still keeping this proof-of-a-
     *  negative from dominating the suite's wall clock. */
    private static final long NEVER_OPENS_WAIT_MS = 2_000;
    private static final long POLL_INTERVAL_MS = 100;

    /**
     * E2E-48 — configure three real, separate OS processes to prove
     * {@code NodeConfig.fromEnv()} + {@code SnapshotLoader.forBoot}'s real precedence (an explicit
     * {@code RHIZOME_SNAPSHOT} file wins; else the network's shipped classpath resource; else an
     * empty ledger) under a genuine shell environment, hoping the real {@code System.getenv()}
     * path or the real classpath-resource loading disagrees with what the injected-lookup-function
     * unit tests already lock down — the one thing none of them can prove, since none of them ever
     * runs as a separate process with real environment variables.
     */
    @Test
    void configPrecedenceHoldsAcrossFileResourceAndEmptyThroughRealOsProcesses() throws Exception {
        // (a) testnet, no RHIZOME_SNAPSHOT: testnet's genesis supply is unpinned and it ships no
        // default resource, so SnapshotLoader.forBoot falls all the way to empty() -- supply 0.
        try (ProcessHarness testnetNode = ProcessHarness.builder()
                .dataDir(tempDir.resolve("testnet-empty"))
                .network("testnet")
                .start()) {
            assertTrue(testnetNode.awaitPortOpen(BOOT_WAIT_MS, POLL_INTERVAL_MS),
                "a default testnet node with no snapshot override never opened its port: stderr="
                    + testnetNode.stderr());
            JSONObject genesis = getJson(testnetNode.port(), "/block?blockId=1");
            assertEquals("0", genesis.getString("supply"),
                "a fresh testnet process with no snapshot override must commit an EMPTY genesis "
                    + "(supply 0), not silently pick up some other source");
        }

        // (b) mainnet, no RHIZOME_SNAPSHOT: falls back to the shipped classpath resource, which
        // pins the real S0.
        String mainnetHash;
        try (ProcessHarness mainnetDefault = ProcessHarness.builder()
                .dataDir(tempDir.resolve("mainnet-default"))
                .network("mainnet")
                .start()) {
            assertTrue(mainnetDefault.awaitPortOpen(BOOT_WAIT_MS, POLL_INTERVAL_MS),
                "a default mainnet node with no snapshot override never opened its port: stderr="
                    + mainnetDefault.stderr());
            JSONObject genesis = getJson(mainnetDefault.port(), "/block?blockId=1");
            assertEquals(Long.toString(NetworkParameters.cleanMainnet().genesisSupply()),
                genesis.getString("supply"),
                "a real mainnet PROCESS with no snapshot override must commit the pinned S0");
            mainnetHash = genesis.getString("hash");

            // Recomputed independently, IN THIS JVM, from nothing the child process's boot path
            // touched: read the identical shipped resource by hand and rebuild genesis with the
            // pure function the unit suites already lock -- so agreement cannot be an artifact of
            // both sides sharing one (possibly buggy) code path.
            String resourcePath = NetworkParameters.cleanMainnet().genesisSnapshotResource().orElseThrow();
            byte[] resourceBytes;
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                assertTrue(in != null, "the shipped genesis resource is missing: " + resourcePath);
                resourceBytes = in.readAllBytes();
            }
            LedgerSnapshot expected = LedgerSnapshot.fromJson(
                new JSONObject(new String(resourceBytes, StandardCharsets.UTF_8)));
            String expectedHash = GenesisBlock.build(NetworkParameters.cleanMainnet(), expected)
                .hash().toHexString();
            assertEquals(expectedHash, mainnetHash,
                "the real process's genesis hash does not match GenesisBlock.build() over the "
                    + "shipped allocation, recomputed independently in this JVM");
        }

        // (c) mainnet, RHIZOME_SNAPSHOT pointing at a real file with the SAME total as the pin but
        // a DIFFERENT distribution: the pin check on the total must pass, but the genesis
        // commitment (over the distribution, not just the total) must differ from (b)'s.
        NetworkParameters mainnet = NetworkParameters.cleanMainnet();
        long pinnedTotal = mainnet.genesisSupply();
        E2EFixtures.Identity holderA = E2EFixtures.Identity.generate();
        E2EFixtures.Identity holderB = E2EFixtures.Identity.generate();
        Path differentDistribution = E2EFixtures.premine(
            tempDir.resolve("mainnet-custom-snapshot.json"), mainnet,
            Map.of(holderA, pinnedTotal / 3, holderB, pinnedTotal - pinnedTotal / 3));

        try (ProcessHarness mainnetCustom = ProcessHarness.builder()
                .dataDir(tempDir.resolve("mainnet-file"))
                .network("mainnet")
                .snapshot(differentDistribution)
                .start()) {
            assertTrue(mainnetCustom.awaitPortOpen(BOOT_WAIT_MS, POLL_INTERVAL_MS),
                "a mainnet node given a same-total, different-distribution snapshot file never "
                    + "opened its port: stderr=" + mainnetCustom.stderr());
            JSONObject genesis = getJson(mainnetCustom.port(), "/block?blockId=1");
            assertEquals(Long.toString(pinnedTotal), genesis.getString("supply"),
                "the RHIZOME_SNAPSHOT override's total must still equal the pin exactly");
            assertNotEquals(mainnetHash, genesis.getString("hash"),
                "a different balance DISTRIBUTION at the same total must commit a different "
                    + "genesis hash -- the header commits to the distribution, not merely the "
                    + "total, so the file override must not collide with the shipped resource's "
                    + "genesis just because the totals agree");
        }
    }

    /**
     * E2E-49 — point {@code RHIZOME_SNAPSHOT} at a sparse 600 MiB file and launch a real process,
     * hoping the size check either happens too late (after the socket is already open) or not at
     * all, so an operator's oversized/corrupted snapshot takes down availability rather than
     * failing the boot cleanly before anything is reachable.
     */
    @Test
    void anOversizedSnapshotFileFailsBeforeTheProcessEverBindsItsPort() throws Exception {
        Path oversized = tempDir.resolve("oversized-snapshot.json");
        // Sparse: a hole, not real content -- fast and does not burn real disk.
        try (RandomAccessFile raf = new RandomAccessFile(oversized.toFile(), "rw")) {
            raf.setLength(627_000_000L);
        }

        try (ProcessHarness node = ProcessHarness.builder()
                .dataDir(tempDir.resolve("oversized"))
                .network("mainnet")
                .snapshot(oversized)
                .start()) {
            boolean exited = node.awaitExit(BOOT_WAIT_MS);
            assertTrue(exited, "an oversized-snapshot process should fail fast, not hang: stdout="
                + node.stdout() + " stderr=" + node.stderr());
            assertNotEquals(0, node.exitCode(),
                "an oversized snapshot must refuse boot with a non-zero exit code");
            assertTrue(node.stderr().contains("snapshot file too large"),
                "expected the real SnapshotLoader message naming the oversize in stderr, got: "
                    + node.stderr());

            assertFalse(node.awaitPortOpen(NEVER_OPENS_WAIT_MS, POLL_INTERVAL_MS),
                "the process must NEVER accept a TCP connection on its configured port -- the "
                    + "size check must run before the HTTP server is ever bound, not merely "
                    + "before some Java-level exception happens to surface");
        }
    }

    /**
     * E2E-50 — point {@code RHIZOME_SNAPSHOT} at a path that does not exist, launch a real
     * process, and then launch a SECOND, correctly-configured process at the exact same data
     * directory, hoping the first failed attempt either fails late (after the port is open) or
     * leaves behind a RocksDB {@code LOCK} / partial state that poisons the retry an operator would
     * naturally make after fixing the typo.
     */
    @Test
    void aMissingSnapshotPathFailsCleanlyAndLeavesNoResidualLockForARetry() throws Exception {
        Path dataDir = tempDir.resolve("missing-snapshot-data");
        Path missing = tempDir.resolve("this-file-does-not-exist.json");

        try (ProcessHarness broken = ProcessHarness.builder()
                .dataDir(dataDir)
                .network("mainnet")
                .snapshot(missing)
                .start()) {
            boolean exited = broken.awaitExit(BOOT_WAIT_MS);
            assertTrue(exited, "a missing-snapshot-path process should fail fast, not hang: stdout="
                + broken.stdout() + " stderr=" + broken.stderr());
            assertNotEquals(0, broken.exitCode(),
                "a missing snapshot path must refuse boot with a non-zero exit code");
            assertTrue(broken.stderr().contains(missing.toString()),
                "expected the missing path named in stderr, got: " + broken.stderr());

            assertFalse(broken.awaitPortOpen(NEVER_OPENS_WAIT_MS, POLL_INTERVAL_MS),
                "the process must NEVER accept a TCP connection on its configured port -- the "
                    + "snapshot read must happen before the HTTP server is ever bound");
        }

        // The decisive extra assertion: a second, correctly-configured process pointed at the
        // SAME data directory must start cleanly -- no RocksDB LOCK contention, no partial state
        // the first attempt left behind. RhizomeNode.assemble loads the snapshot BEFORE opening
        // any store, so today this passes structurally; the point of asserting it is to catch a
        // future reordering (e.g. a store opened before the snapshot read) the moment it starts
        // to matter.
        try (ProcessHarness retry = ProcessHarness.builder()
                .dataDir(dataDir)
                .network("mainnet")
                .start()) {
            assertTrue(retry.awaitPortOpen(BOOT_WAIT_MS, POLL_INTERVAL_MS),
                "a correctly-configured retry at the SAME data directory a failed attempt just "
                    + "used never opened its port -- stdout=" + retry.stdout() + " stderr="
                    + retry.stderr());
            JSONObject genesis = getJson(retry.port(), "/block?blockId=1");
            assertEquals(Long.toString(NetworkParameters.cleanMainnet().genesisSupply()),
                genesis.getString("supply"),
                "the retry must boot normally to the real pinned genesis, not fail on residual "
                    + "state the aborted first attempt left behind");
            // The precise signal a lock-contention/partial-state failure would leave: RocksDB
            // surfaces it as a RocksDBException (e.g. "lock hold by current process" or "IO
            // error: lock ...: Resource temporarily unavailable"). Matching on that class name
            // rather than the bare word "lock" avoids colliding with the JDK's own unrelated
            // "Restricted methods will be BLOCKed in a future release" native-access warning,
            // which is present on every boot regardless of this scenario.
            assertFalse(retry.stderr().contains("RocksDBException"),
                "the retry's stderr surfaces a RocksDBException -- the aborted first attempt left "
                    + "residual RocksDB lock/partial state behind: " + retry.stderr());
        }
    }

    /** A plain GET against a booted child's real HTTP API, parsed as JSON. */
    private static JSONObject getJson(int port, String path) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new AssertionError("GET " + path + " returned " + response.statusCode()
                + ": " + response.body());
        }
        return new JSONObject(response.body());
    }
}
