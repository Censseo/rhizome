package rhizome.periodic.e2e;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Build I5 + E2E-G08: {@code GenesisLedger.seed} loops over every entry in the loaded snapshot,
 * calling {@code hasWallet}/{@code createWallet}/{@code deposit} once per wallet against the
 * node's real store -- with no documented cap on {@code balances.size()}. This proves what a
 * genuinely large allocation artifact costs a real node to boot, in wall-clock time and in peak
 * process memory, measured from the outside rather than assumed.
 *
 * <p>Deliberately placed OUTSIDE {@code rhizome.adversarial.e2e} (see
 * {@link E2EGenesisExoticPathsTest}'s class javadoc for why that placement excludes a class from
 * both Gradle filters) and reachable via {@code :app-node:periodicAdversarial}.
 *
 * <p><b>DEFENDED (was RESIDUAL).</b> The first run of this test found {@code GenesisLedger.seed}
 * paying one synchronous, SYNCED (fsync'd) RocksDB write per wallet -- actually two, one for
 * {@code createWallet} and one for {@code deposit} -- measured at <b>~3.3-3.6 ms/wallet</b>: a
 * six-figure wallet count alone, no other attack needed, was a multi-minute-to-multi-hour startup
 * DoS via an otherwise perfectly valid genesis allocation file (1,000 wallets -> ~3.2 s, 8,000 ->
 * ~27.8 s of seeding alone, net of a ~0.8 s zero-wallet baseline). The fix is
 * {@code Ledger#beginBulkLoad()}/{@code Ledger#endBulkLoad()}:
 * {@code GenesisLedger.seed} now opens a bulk-load window around its (UNCHANGED) per-entry
 * hasWallet/createWallet/deposit loop, and the durable backend
 * ({@code RocksDbNodeStore.RocksLedger}) buffers those same calls in memory and flushes them in
 * chunked {@code WriteBatch}es of 10,000 wallets instead of one fsync per wallet. This is a pure
 * batching change -- the loop and its arithmetic are untouched, so the resulting balances are
 * byte-identical (see {@code LedgerContract}'s bulk-load contract test, run against both the
 * in-memory and RocksDB ledgers, and the unmodified genesis-hash assertions in
 * {@code GenesisBlockTest}/{@code LedgerSnapshotTest}).
 *
 * <p>Measured AFTER the fix on this box: roughly <b>0.08 ms (~80 microseconds) per wallet</b> --
 * about a <b>40-45x</b> reduction in the marginal per-wallet cost -- with {@link #SMALL} =
 * 50,000 wallets booting in ~3.5-3.6 s and {@link #LARGE} = 200,000 in ~15-18 s. {@code SMALL}/
 * {@code LARGE} were deliberately raised from the original 2,000/8,000 (still kept at
 * {@code LARGE / 4}, per the design's suggested N vs. N/4 comparison): at the old 2,000/8,000
 * scale the fix makes wallet-seeding cost small enough to be swallowed by this test's own
 * ~0.9-1.2 s of fixed per-process overhead (JVM start, RocksDB open) -- itself evidence the fix
 * works, but too noise-dominated to assert a clean scaling ratio against. 50,000/200,000 gives a
 * clean signal while still finishing FASTER in total than the ~35-40 s this class used to budget
 * for the old 2,000/8,000 pair -- and at this fixed per-wallet cost, even a seven-figure wallet
 * count is on the order of a minute, not hours.
 */
class E2EGenesisLargeSnapshotStartupTest {

    @TempDir
    Path tempDir;

    private static final long SMALL = 50_000L;
    private static final long LARGE = 200_000L;

    /** Generous absolute ceiling: if booting {@link #LARGE} wallets ever takes longer than this,
     *  that is a startup-latency regression regardless of how it compares to {@link #SMALL}. Set
     *  well above the ~15-18 s measured on this box for {@link #LARGE} AFTER the batching fix
     *  (see the class javadoc) -- comfortably below the old ~28 s the unfixed loop took for the
     *  much smaller 8,000-wallet case this replaced. */
    private static final long ABSOLUTE_TIMEOUT_MS = 60_000;

    /**
     * E2E-58 -- boot a real node process against genuinely large synthetic genesis snapshots
     * (50,000 and 200,000 wallets) and measure wall-clock time to the HTTP port opening and the
     * child process's own peak RSS ({@code /proc/<pid>/status}'s {@code VmHWM}). Originally
     * written hoping {@code GenesisLedger.seed}'s unbounded per-wallet loop turned out to be worse
     * than linear, or that no one had ever actually measured it against a real store -- it found
     * both worse (an individual fsync per wallet) and now, after the fix, verifies the DoS is
     * closed rather than merely re-confirming it.
     *
     * <p>DEFENDED: after batching {@code GenesisLedger.seed}'s writes (see the class javadoc),
     * {@link #LARGE} boots in well under {@link #ABSOLUTE_TIMEOUT_MS}, and 4x the wallet count
     * still costs roughly 4x the time -- the same linear-growth bar this test always asserted,
     * now met at a per-wallet cost ~40-45x lower than before. If a future change makes either
     * assertion fail, that is a genuine startup-latency regression against a real
     * allocation-shaped fixture, not a flaky bound.
     */
    @Test
    void startupTimeScalesRoughlyLinearlyWithWalletCountAndStaysBounded() throws Exception {
        long smallTotal = LargeSnapshotFixture.generate(
            tempDir.resolve("small-fixture.json"), SMALL, 1L, 2);
        long largeTotal = LargeSnapshotFixture.generate(
            tempDir.resolve("large-fixture.json"), LARGE, 1L, 2);
        assertTrue(smallTotal > 0 && largeTotal > 0, "fixture generation must produce a positive total");

        Measurement small = bootAndMeasure(tempDir.resolve("small-fixture.json"), tempDir.resolve("small-data"));
        Measurement large = bootAndMeasure(tempDir.resolve("large-fixture.json"), tempDir.resolve("large-data"));
        System.out.println("E2E-58 measurements: SMALL(" + SMALL + " wallets) boot=" + small.bootMillis()
            + "ms peakRssKb=" + small.peakRssKb() + "; LARGE(" + LARGE + " wallets) boot="
            + large.bootMillis() + "ms peakRssKb=" + large.peakRssKb());

        assertTrue(large.bootMillis() < ABSOLUTE_TIMEOUT_MS,
            "booting " + LARGE + " wallets took " + large.bootMillis() + " ms, over the "
                + ABSOLUTE_TIMEOUT_MS + " ms absolute ceiling -- GenesisLedger.seed's bulk-load "
                + "batching (see the class javadoc) no longer bounds this; treat this as a "
                + "startup-latency regression, not a re-confirmation of the old per-wallet-fsync DoS");

        // The scaling check: 4x the wallets should not cost wildly more than ~4x the time. A
        // generous band (0.5x-8x the naive linear prediction) absorbs JIT warmup, RocksDB
        // compaction jitter and this being a shared box, while still catching genuinely
        // super-linear behaviour (e.g. an accidental O(n^2) pass over the snapshot).
        double ratio = (double) large.bootMillis() / Math.max(1, small.bootMillis());
        assertTrue(ratio > 0.5 && ratio < 8.0,
            "expected startup time to scale roughly linearly with wallet count (ratio in (0.5, 8) "
                + "for a 4x wallet-count increase), got ratio=" + ratio + " (small=" + SMALL + " -> "
                + small.bootMillis() + " ms, large=" + LARGE + " -> " + large.bootMillis() + " ms) "
                + "-- a ratio far outside this band means GenesisLedger.seed's cost is not linear "
                + "in balances.size(), which is worse news at millions-scale than a naive linear "
                + "extrapolation from this test would suggest");
    }

    private record Measurement(long bootMillis, long peakRssKb) {
    }

    private Measurement bootAndMeasure(Path snapshot, Path dataDir) throws Exception {
        long start = System.nanoTime();
        try (MinimalNodeProcess node = MinimalNodeProcess.start(dataDir,
                Map.of("RHIZOME_NETWORK", "testnet", "RHIZOME_SNAPSHOT", snapshot.toString()))) {
            boolean opened = node.awaitPortOpen(ABSOLUTE_TIMEOUT_MS, 50);
            long bootMillis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(opened, "node never opened its port within " + ABSOLUTE_TIMEOUT_MS
                + " ms booting from " + snapshot + " -- stdout=" + node.stdout()
                + " stderr=" + node.stderr());
            long peakRssKb = readVmHwmKb(node.pid());
            return new Measurement(bootMillis, peakRssKb);
        }
    }

    /** Reads the child's own peak resident set size from {@code /proc/<pid>/status}, isolating
     *  the measurement from this test JVM's own heap -- Linux-specific, fine on this box. */
    private static long readVmHwmKb(long pid) {
        Path status = Path.of("/proc/" + pid + "/status");
        try {
            for (String line : Files.readAllLines(status)) {
                if (line.startsWith("VmHWM:")) {
                    String[] parts = line.trim().split("\\s+");
                    return Long.parseLong(parts[1]);
                }
            }
        } catch (Exception ignored) {
            // best-effort measurement -- absence does not fail the scenario
        }
        return -1;
    }
}
