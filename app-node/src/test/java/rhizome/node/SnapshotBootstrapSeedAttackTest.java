package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.block.BlockHeader;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.crypto.SHA256Hash;
import rhizome.vm.InMemoryContractStore;

/** Snap bootstrap trusts a seed for state only after the seed's chain proves real work. */
class SnapshotBootstrapSeedAttackTest {

    @TempDir
    Path tempDir;

    private boolean run(NetworkParameters params, SnapTestFixture f, SnapTestFixture.Target target,
                        SnapTestFixture.Peer peer, BigInteger requiredWork) {
        return SnapshotBootstrap.bootstrap(params, f.genesisSnapshot, target,
            new InMemoryContractStore(), peer, SnapTestFixture.NOW, tempDir, requiredWork);
    }

    private static SnapTestFixture.Peer honest(SnapTestFixture f, List<BlockHeader> chain) {
        return new SnapTestFixture.Peer(chain.get(chain.size() - 1).id(), f.info(), chain, f.chunk, BigInteger.TEN);
    }

    /**
     * NET-16 — Be the first (or only) seed a joiner asks and serve a fabricated ledger behind a
     * cheaply-mined, structurally valid header chain: the joiner adopted the first snapshot that
     * verified, with no comparison against any other seed. The joiner now requires the candidate's
     * HeaderChain-proven work, validated to its TIP, to reach the work the other reachable seeds
     * report (within a stated tolerance for uncle work and tip skew), and refuses before spooling
     * a single chunk or touching a store; a chain the peer advertises but cannot serve to its tip
     * is refused too.
     */
    @Test
    void aSeedWhoseProvenWorkFallsShortOfTheOtherSeedsIsNeverAdopted() {
        SnapTestFixture f = new SnapTestFixture();
        List<BlockHeader> chain = f.chain(5);
        // Proven work: genesis + heights 2..5 at difficulty 4 = 5 * 16 = 80.
        var target = new SnapTestFixture.Target();
        assertFalse(run(SnapTestFixture.PARAMS, f, target, honest(f, chain), BigInteger.valueOf(10_000)));
        assertFalse(target.bootstrapping, "refused before any store was touched");
        assertEquals(0, target.adoptedHeaders);

        // At or within tolerance of the other seeds' work: adopted.
        var ok = new SnapTestFixture.Target();
        assertTrue(run(SnapTestFixture.PARAMS, f, ok, honest(f, chain), BigInteger.valueOf(85)));
        assertEquals(1, ok.adoptedHeaders, "headers up to the pivot are adopted");

        // Advertising a tip it cannot serve headers for: validated to the TIP, so refused.
        var phantom = new SnapTestFixture.Peer(50, f.info(), f.chain(5), f.chunk, BigInteger.TEN);
        assertFalse(run(SnapTestFixture.PARAMS, f, new SnapTestFixture.Target(), phantom, BigInteger.ZERO));
    }

    /**
     * NET-17 — Sit on the path to the seeds (or be one) over plaintext http and answer both the
     * snapshot and the {@code /total_work} probes, or leave a lone reachable seed to vouch for
     * itself while the others are unreachable. Snap bootstrap refuses {@code http://} seeds unless
     * the operator opts in (RHIZOME_SNAP_ALLOW_HTTP, local testnets), needs at least two reachable
     * seeds unless exactly one is configured, and requires each candidate to match the best OTHER
     * seed's reported work.
     */
    @Test
    void snapSeedsMustBeHttpsAndCorroboratedByASecondReachableSeed() {
        Function<String, BigInteger> work = url -> switch (url) {
            case "https://a" -> BigInteger.valueOf(100);
            case "https://b" -> BigInteger.valueOf(300);
            case "https://c" -> BigInteger.valueOf(200);
            default -> throw new IllegalStateException("unreachable " + url);
        };

        // Plaintext seeds are dropped; the override restores them.
        assertEquals(List.of(), SnapSeeds.plan(List.of("http://a"), false, work));
        assertEquals(1, SnapSeeds.plan(List.of("https://a"), false, work).size(), "a lone configured seed is allowed");
        assertEquals(List.of(), SnapSeeds.plan(List.of("https://a", "http://x"), false, work),
            "one usable https seed out of two configured is not a quorum");

        // Two of three reachable: each candidate must match the best OTHER reachable seed.
        List<SnapSeeds.Attempt> plan = SnapSeeds.plan(
            List.of("https://a", "https://b", "https://down", "https://c"), false, work);
        assertEquals(List.of(
            new SnapSeeds.Attempt("https://b", BigInteger.valueOf(200)),
            new SnapSeeds.Attempt("https://c", BigInteger.valueOf(300)),
            new SnapSeeds.Attempt("https://a", BigInteger.valueOf(300))), plan);

        // Only one of several reachable: refused.
        assertEquals(List.of(), SnapSeeds.plan(List.of("https://a", "https://down"), false, work));

        // Local-testnet override.
        Function<String, BigInteger> any = url -> BigInteger.ONE;
        assertEquals(2, SnapSeeds.plan(List.of("http://a", "http://b"), true, any).size());
    }

    /**
     * NET-18 — Serve a chain shorter than the release's highest checkpoint, a chain that forks
     * below a checkpoint, or a snapshot whose locally built genesis is not the network's pinned
     * genesis. Each is refused before any store is seeded: the checkpoint mismatch by the header
     * gate, the short chain and the genesis pin by the bootstrap itself.
     */
    @Test
    void checkpointsAndThePinnedGenesisBindEverySnapBootstrap() {
        SnapTestFixture f = new SnapTestFixture();
        List<BlockHeader> chain = f.chain(5);
        SHA256Hash h4 = chain.get(2).hash(); // height 4

        // Matching checkpoint inside the served range: adopted.
        NetworkParameters matching = SnapTestFixture.PARAMS.toBuilder().checkpoints(Map.of(4L, h4)).build();
        assertTrue(run(matching, f, new SnapTestFixture.Target(), honest(f, chain), BigInteger.ZERO));

        // Wrong hash at a checkpointed height: the header gate refuses.
        NetworkParameters forked = SnapTestFixture.PARAMS.toBuilder()
            .checkpoints(Map.of(4L, SHA256Hash.random())).build();
        var t1 = new SnapTestFixture.Target();
        assertFalse(run(forked, f, t1, honest(f, chain), BigInteger.ZERO));
        assertFalse(t1.bootstrapping);

        // Chain shorter than the highest checkpoint: refused even though every served header is valid.
        NetworkParameters beyond = SnapTestFixture.PARAMS.toBuilder()
            .checkpoints(Map.of(4L, h4, 9L, SHA256Hash.random())).build();
        var t2 = new SnapTestFixture.Target();
        assertFalse(run(beyond, f, t2, honest(f, chain), BigInteger.ZERO));
        assertFalse(t2.bootstrapping);

        // The locally built genesis must be the pinned one.
        NetworkParameters wrongPin = SnapTestFixture.PARAMS.toBuilder()
            .expectedGenesisHash(SHA256Hash.random()).build();
        var t3 = new SnapTestFixture.Target();
        assertFalse(run(wrongPin, f, t3, honest(f, chain), BigInteger.ZERO));
        assertFalse(t3.bootstrapping);
        assertEquals(0, t3.adoptedHeaders);
    }
}
