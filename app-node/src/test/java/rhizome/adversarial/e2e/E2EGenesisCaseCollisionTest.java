package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.crypto.Hex;

/**
 * A genesis snapshot's JSON keys are hex address strings, and {@code PublicAddress.of(String)}
 * parses hex through {@code java.util.HexFormat}, which is case-insensitive on the way IN even
 * though {@code Hex.bytesToHex} always renders uppercase on the way OUT. That means the SAME
 * 25-byte address can appear in a snapshot file twice, spelled in two different cases, as two
 * textually-distinct JSON object keys ({@code org.json.JSONObject} does not case-fold keys) that
 * decode to one identical {@link rhizome.core.ledger.PublicAddress}.
 *
 * <p>{@code LedgerSnapshot.fromJson} now refuses such a file at decode time: the second key that
 * decodes to an address already present throws {@link IllegalArgumentException} naming the
 * duplicate — the same fail-loud ingress rule as the high-bit balance guard in the same method
 * (audit F3). Before that guard existed this was a RESIDUAL hole, verified empirically by this
 * test's first version: the second {@code put()} silently overwrote the first entry's amount,
 * which entry survived was an artifact of {@code org.json}'s internal {@code HashMap} iteration
 * order rather than anything in the file, and for an UNPINNED profile (testnet/devnet — no
 * genesis-supply pin re-checks the total) nothing downstream ever caught the discard: the boot
 * committed the merged result as if the file were well-formed.
 *
 * <p>This test drives two real, separate-process node boots — one mainnet (pinned) and one
 * testnet (unpinned, the profile the merge used to slip through silently) — each pointed at a
 * case-collided snapshot, and asserts both refuse at snapshot LOAD: a non-zero exit naming the
 * duplicate, before the HTTP port is ever bound, and (on mainnet) before the supply-pin check
 * that would otherwise be the suspect refusal.
 */
class E2EGenesisCaseCollisionTest {

    @TempDir
    Path tempDir;

    private static final long BOOT_WAIT_MS = 15_000;

    /** A rejected snapshot fails during boot config (well under 200 ms); an order of magnitude
     *  more than that keeps this proof-of-a-negative cheap without being a bare-minimum guess. */
    private static final long NEVER_OPENS_WAIT_MS = 2_000;
    private static final long POLL_INTERVAL_MS = 100;

    /**
     * A fixed 25-byte address (version 0x00, arbitrary body/checksum bytes) — hardcoded so the
     * fixture is identical on every run; its hex rendering must contain letters, or there is no
     * case collision to test (asserted below).
     */
    private static byte[] fixedAddressBytes() {
        byte[] address = new byte[25];
        address[0] = 0x00;
        for (int i = 1; i <= 20; i++) {
            address[i] = (byte) (0xAB ^ i);
        }
        for (int i = 21; i < 25; i++) {
            address[i] = (byte) i;
        }
        return address;
    }

    /**
     * E2E-54 — seed a real node's genesis snapshot with the SAME 25-byte address spelled twice
     * (once upper, once lower hex), hoping the case-insensitive address parse silently merges
     * the two entries into one wallet — one amount discarded, the survivor chosen by
     * {@code HashMap} iteration order rather than the file — and that nothing downstream notices:
     * on an unpinned profile there is no supply pin to re-check the merged total against at all.
     * Investigated and confirmed DEFENDED: {@code LedgerSnapshot.fromJson} rejects the duplicate
     * at decode. Asserted through real, separate-process boots on BOTH a pinned (mainnet) and an
     * unpinned (testnet) profile: each process exits non-zero, names the duplicate in stderr,
     * and never accepts a TCP connection.
     */
    @Test
    void duplicateAddressUnderTwoHexCasesIsRefusedAtSnapshotLoadBeforeAnyPortBinds() throws Exception {
        String upperHex = Hex.bytesToHex(fixedAddressBytes()); // Hex.bytesToHex always upper-cases
        String lowerHex = upperHex.toLowerCase(java.util.Locale.ROOT);
        assertNotEquals(upperHex, lowerHex,
            "the fixture address must contain hex letters, or there is no case collision");

        // (a) mainnet, the pinned profile. The two amounts sum EXACTLY to the pinned S0: had the
        // file loaded, no plausible merge/sum of the collided entries is what the refusal then
        // turns on -- and the message assertions below prove the duplicate guard at snapshot
        // load, not the later pin check, is what fires.
        NetworkParameters mainnet = NetworkParameters.cleanMainnet();
        long pinnedTotal = mainnet.genesisSupply();
        Path mainnetSnapshot = tempDir.resolve("mainnet-case-collision.json");
        writeSnapshot(mainnetSnapshot, mainnet.chainId(),
            upperHex, pinnedTotal / 3, lowerHex, pinnedTotal - pinnedTotal / 3);
        assertRefusedAtSnapshotLoad(tempDir.resolve("mainnet-data"), "mainnet", mainnetSnapshot,
            true);

        // (b) testnet, the UNPINNED profile -- the one the silent merge used to slip through
        // with no downstream net at all: before the guard, this boot SUCCEEDED and committed
        // the merged wallet. Arbitrary amounts; only the duplicate can refuse this boot.
        NetworkParameters testnet = NetworkParameters.testnet();
        Path testnetSnapshot = tempDir.resolve("testnet-case-collision.json");
        writeSnapshot(testnetSnapshot, testnet.chainId(), upperHex, 100L, lowerHex, 200L);
        assertRefusedAtSnapshotLoad(tempDir.resolve("testnet-data"), "testnet", testnetSnapshot,
            false);
    }

    private static void writeSnapshot(Path file, int chainId,
                                      String keyA, long amountA, String keyB, long amountB)
            throws Exception {
        String json = "{\"version\":1,\"source\":\"e2e-case-collision\",\"sourceHeight\":0,"
            + "\"chainId\":" + chainId + ",\"balances\":{"
            + "\"" + keyA + "\":\"" + amountA + "\","
            + "\"" + keyB + "\":\"" + amountB + "\""
            + "}}";
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    private static void assertRefusedAtSnapshotLoad(Path dataDir, String network, Path snapshot,
                                                    boolean pinnedProfile) throws Exception {
        try (ProcessHarness node = ProcessHarness.builder()
                .dataDir(dataDir)
                .network(network)
                .snapshot(snapshot)
                .start()) {
            boolean exited = node.awaitExit(BOOT_WAIT_MS);
            assertTrue(exited, "a duplicate-address snapshot should fail fast, not hang: stdout="
                + node.stdout() + " stderr=" + node.stderr());
            assertNotEquals(0, node.exitCode(),
                "a duplicate-address snapshot must refuse boot with a non-zero exit code");
            assertTrue(node.stderr().contains("duplicate address"),
                "expected the real LedgerSnapshot.fromJson duplicate-address rejection in "
                    + "stderr, got: " + node.stderr());
            if (pinnedProfile) {
                assertFalse(node.stderr().contains("does not match the pinned genesis supply"),
                    "the refusal must come from the duplicate-address guard at snapshot load, "
                        + "not the later supply-pin check: " + node.stderr());
            }
            assertFalse(node.awaitPortOpen(NEVER_OPENS_WAIT_MS, POLL_INTERVAL_MS),
                "the process must NEVER accept a TCP connection on its configured port -- the "
                    + "duplicate rejection must happen before the HTTP server is ever bound");
        }
    }
}
