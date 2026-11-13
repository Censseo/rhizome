package rhizome.node;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.block.Block;
import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.BootstrapTarget;
import rhizome.core.blockchain.ChainStore;
import rhizome.core.blockchain.GenesisBlock;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.InMemoryNonceStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.NonceStore;
import rhizome.core.blockchain.PeerSource;
import rhizome.core.box.BoxStore;
import rhizome.core.box.InMemoryBoxStore;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.Ledger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.state.InMemoryRootStore;
import rhizome.core.state.InMemorySmtNodeStore;
import rhizome.core.state.RootStore;
import rhizome.core.state.SmtNodeStore;
import rhizome.core.token.InMemoryTokenStore;
import rhizome.core.token.TokenStore;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.SHA256Hash;
import rhizome.vm.InMemoryContractStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The snap-sync bootstrap's refusal branches.
 *
 * <p>{@code SnapshotBootstrap.bootstrap} has fourteen distinct ways to decline a peer's snapshot,
 * and exactly one of them was covered — because until it was typed on {@link BootstrapTarget} the
 * only way to call it was to open five real RocksDB instances under a temporary directory, so the
 * sole integration test that does exercises one branch and stops.
 *
 * <p>Adopting a bad snapshot is not a recoverable error: it seeds the ledger, the nonces and the
 * state root of a chain this node will then build on. Every one of these refusals is the node
 * declining to start from someone else's claim.
 */
class SnapshotBootstrapRefusalTest {

    private static final NetworkParameters PARAMS = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).minDifficulty(4)
        .maxReorgDepth(3).build();
    private static final long NOW = 100_000_000_000L;

    @TempDir
    Path tempDir;

    private InMemoryTarget target;
    private LedgerSnapshot genesisSnapshot;

    @BeforeEach
    void setUp() {
        target = new InMemoryTarget();
        genesisSnapshot = new LedgerSnapshot("t", 0, PARAMS.chainId());
    }

    private boolean bootstrap(PeerSource peer) {
        return SnapshotBootstrap.bootstrap(PARAMS, genesisSnapshot, target,
            new InMemoryContractStore(), peer, NOW, tempDir);
    }

    /** Mines a standalone, PoW-valid header (no retarget crossed in these short test chains). */
    private BlockHeader mineHeader(long id, SHA256Hash parent, long timestamp, SHA256Hash stateRoot, long supply) {
        var b = (BlockImpl) BlockImpl.builder().id((int) id).timestamp(timestamp)
            .difficulty(PARAMS.genesisDifficulty()).lastBlockHash(parent)
            .merkleRoot(SHA256Hash.random()).stateRoot(stateRoot).supply(supply).build();
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), PARAMS.powAlgorithm()));
        return BlockHeader.of(b);
    }

    /**
     * A real, PoW-validated header chain from height 2 through {@code pivot + maxReorgDepth} —
     * exactly the window {@code SnapshotBootstrap} fetches and validates before it ever looks at
     * the advertised chunk count — with {@code pivotRoot} on the pivot header and everywhere else
     * empty (only the pivot's state root is checked against the snapshot advertisement). Every
     * header commits the supply the emission rule dictates: the header gate refuses a chain without
     * it, which is how these cases once failed for the wrong reason (and passed vacuously). The peer
     * tip must equal the last served height, because the bootstrap validates to the TIP.
     */
    private List<BlockHeader> buriedPivotHeaders(long pivot, SHA256Hash pivotRoot) {
        SHA256Hash parent = BlockHeader.of(GenesisBlock.build(PARAMS, genesisSnapshot)).hash();
        List<BlockHeader> chain = new ArrayList<>();
        long validateTo = pivot + PARAMS.maxReorgDepth();
        long supply = genesisSnapshot.totalSupply();
        for (long h = 2; h <= validateTo; h++) {
            supply += PARAMS.miningReward(h, supply);
            BlockHeader header = mineHeader(h, parent, h * 1000L, h == pivot ? pivotRoot : SHA256Hash.empty(), supply);
            chain.add(header);
            parent = header.hash();
        }
        return chain;
    }

    @Test
    void aNonEmptyChainStoreIsARefusalToOverwriteLocalHistory() {
        // Seeding over an existing chain would silently replace history the node already served.
        target.chainStore().append(rhizome.core.blockchain.GenesisBlock.build(PARAMS, genesisSnapshot));
        assertThrows(IllegalStateException.class, () -> bootstrap(new FakePeer(100, null)));
    }

    @Test
    void aPeerWithNoMaterialisedSnapshotIsSkipped() {
        assertFalse(bootstrap(new FakePeer(100, null)));
        assertEquals(0, target.chainStore().height(), "nothing was seeded");
    }

    @Test
    void aPivotBelowTwoIsRefused() {
        // Genesis is height 1; a "snapshot" at or below it carries no state to import.
        assertFalse(bootstrap(new FakePeer(100, info(1, new byte[32], 1))));
        assertEquals(0, target.chainStore().height());
    }

    @Test
    void anUnburiedPivotIsRefused() {
        // The pivot must sit deeper than maxReorgDepth under the peer's tip, or the chain it
        // anchors could still be reorged away — this node would then be seeded onto a dead branch.
        assertFalse(bootstrap(new FakePeer(10, info(9, new byte[32], 1))),
            "pivot 9 under tip 10 is not buried by maxReorgDepth 3");
        assertFalse(bootstrap(new FakePeer(10, info(8, new byte[32], 1))),
            "pivot + maxReorgDepth must be strictly within the peer tip");
        assertEquals(0, target.chainStore().height());
    }

    @Test
    void aPivotThatWouldOverflowTheHeaderWindowIsRefused() {
        // The header window is indexed as an int; a pivot near Integer.MAX_VALUE must be caught
        // before the arithmetic wraps rather than after (audit: integer overflow guard).
        long huge = Integer.MAX_VALUE;
        assertFalse(bootstrap(new FakePeer(huge + 100, info(huge, new byte[32], 1))));
        assertEquals(0, target.chainStore().height());
    }

    @Test
    void anUnservableHeaderWindowIsRefused() {
        // The peer advertised a pivot but cannot produce the headers proving it — nothing to
        // validate the claimed root against.
        assertFalse(bootstrap(new FakePeer(100, info(20, new byte[32], 1))),
            "no headers served: the pivot is unproven");
        assertEquals(0, target.chainStore().height());
    }

    /**
     * A chain-valid, root-matching snapshot at pivot 2 whose tip is exactly {@code pivot + maxReorgDepth},
     * advertising {@code chunks} chunks. Returns the peer so the test can read how many chunks were fetched.
     */
    private FakePeer peerAdvertising(int chunks) {
        long pivot = 2;
        SHA256Hash pivotRoot = SHA256Hash.random();
        List<BlockHeader> chain = buriedPivotHeaders(pivot, pivotRoot);
        return new FakePeer(pivot + PARAMS.maxReorgDepth(), info(pivot, pivotRoot.toBytes(), chunks), chain);
    }

    @Test
    void theChunkCountCasesReachTheChunkCountCheck() {
        // CONTROL for the three refusals below: with a sane count the very same chain gets through
        // header validation, the burial check and the root agreement, and the bootstrap goes on to
        // FETCH chunk 0 (which this peer then fails to serve, so the attempt still returns false).
        // If this fetch count were 0, the refusals below would be proving nothing about the count.
        FakePeer peer = peerAdvertising(1);
        assertFalse(bootstrap(peer));
        assertEquals(1, peer.chunkFetches.get(), "a valid count proceeds past every earlier gate to the fetch");
        assertEquals(0, target.chainStore().height());
    }

    @Test
    void anOutOfRangeChunkCountIsRefusedBeforeAnyFetch() {
        // Same chain as the control above, so these are refused BY THE CHUNK COUNT specifically: the
        // refusal is the absence of the fetch the control performs.
        for (int count : new int[] {-1, Integer.MAX_VALUE}) {
            FakePeer peer = peerAdvertising(count);
            assertFalse(bootstrap(peer), "count " + count);
            assertEquals(0, peer.chunkFetches.get(), "count " + count + " must be refused before any fetch");
        }
        assertEquals(0, target.chainStore().height());
    }

    @Test
    void aZeroChunkCountIsRefused() {
        // A snapshot at a real (non-genesis, buried) pivot must carry at least one chunk of
        // state; zero is as absurd as a negative count, not a legitimate empty snapshot.
        // HttpPeerSource's own /state/snapshot/info parsing already refuses chunks <= 0 (audit
        // F6) — this pins the outcome for SnapshotBootstrap's own check, which used to only
        // refuse a negative count. The chain, burial and root are valid (see the control test), so
        // the only thing that can refuse this peer before a fetch is the chunk count.
        FakePeer peer = peerAdvertising(0);
        assertFalse(bootstrap(peer));
        assertEquals(0, peer.chunkFetches.get());
        assertEquals(0, target.chainStore().height());
    }

    private static PeerSource.SnapshotInfo info(long pivot, byte[] root, int chunks) {
        return new PeerSource.SnapshotInfo(pivot, root, chunks);
    }

    /** A peer that advertises a snapshot, optionally serves a real header chain, and nothing else. */
    private record FakePeer(long height, PeerSource.SnapshotInfo snapshot, List<BlockHeader> servedHeaders,
                            java.util.concurrent.atomic.AtomicInteger chunkFetches) implements PeerSource {

        FakePeer(long height, PeerSource.SnapshotInfo snapshot) {
            this(height, snapshot, List.of());
        }

        FakePeer(long height, PeerSource.SnapshotInfo snapshot, List<BlockHeader> servedHeaders) {
            this(height, snapshot, servedHeaders, new java.util.concurrent.atomic.AtomicInteger());
        }

        @Override public PeerSource.SnapshotInfo snapshotInfo() {
            return snapshot;
        }

        @Override public List<Block> blocks(long start, long end) {
            return List.of();
        }

        @Override public List<BlockHeader> headers(long start, long end) {
            return servedHeaders.stream().filter(h -> h.id() >= start && h.id() <= end).toList();
        }

        @Override public byte[] snapshotChunk(int index) {
            chunkFetches.incrementAndGet();
            throw new IllegalStateException("no chunks served");
        }

        @Override public java.math.BigInteger totalWork() {
            return java.math.BigInteger.ONE;
        }

        @Override public rhizome.crypto.SHA256Hash blockHash(long height) {
            return rhizome.crypto.SHA256Hash.empty();
        }
    }

    /** The whole seed target, in memory. This is what the port exists for. */
    private static final class InMemoryTarget implements BootstrapTarget {
        private final ChainStore chain = new InMemoryChainStore();
        private final Ledger ledger = new InMemoryLedger();
        private final NonceStore nonces = new InMemoryNonceStore();
        private final BoxStore boxes = new InMemoryBoxStore();
        private final TokenStore tokens = new InMemoryTokenStore();
        private final SmtNodeStore nodes = new InMemorySmtNodeStore();
        private final RootStore roots = new InMemoryRootStore();
        private final List<BlockHeader> adopted = new ArrayList<>();
        private boolean bootstrapping;

        @Override public ChainStore chainStore() {
            return chain;
        }

        @Override public Ledger ledger() {
            return ledger;
        }

        @Override public NonceStore nonceStore() {
            return nonces;
        }

        @Override public BoxStore boxes() {
            return boxes;
        }

        @Override public TokenStore tokens() {
            return tokens;
        }

        @Override public SmtNodeStore stateNodes() {
            return nodes;
        }

        @Override public RootStore stateRoots() {
            return roots;
        }

        @Override public void beginBootstrap() {
            bootstrapping = true;
        }

        @Override public void bootstrapHeaders(List<BlockHeader> headers) {
            adopted.addAll(headers);
        }

        @Override public void endBootstrap() {
            bootstrapping = false;
        }

        boolean bootstrapMarkerSet() {
            return bootstrapping;
        }
    }
}
