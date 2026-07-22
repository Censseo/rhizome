package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ChainSynchronizer;
import rhizome.core.blockchain.ChainSynchronizer.Result;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.PeerSource;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

class ChainSynchronizerTest {

    private static final NetworkParameters PARAMS = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).build();

    // Wall-clock "now" is far ahead of the (historical) block timestamps we mine,
    // as it is for a real node syncing past blocks — so the future-time check passes.
    private static final long NOW = 100_000_000_000L;

    /** A fresh engine seeded from the shared snapshot (so all chains share a genesis). */
    private static ChainEngine newEngine() {
        return newEngine(PARAMS);
    }

    private static ChainEngine newEngine(NetworkParameters params) {
        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, params.chainId());
        return ChainEngine.init(params, new InMemoryLedger(), new InMemoryChainStore(),
            snapshot, null, () -> NOW);
    }

    /** Mines empty blocks onto {@code engine} using {@code miner}; the engine shares {@code clock}. */
    private static void mineBlocks(ChainEngine engine, PublicAddress miner, AtomicLong clock, int count) {
        for (int i = 0; i < count; i++) {
            long height = engine.height() + 1;
            var b = (BlockImpl) BlockImpl.builder()
                .id((int) height)
                .timestamp(clock.addAndGet(90_000))
                .difficulty(engine.difficulty())
                .lastBlockHash(engine.tipHash())
                .build();
            b.addTransaction(Transaction.of(miner, new TransactionAmount(PARAMS.miningReward(height))));
            var tree = new MerkleTree();
            tree.setItems(b.transactions());
            b.merkleRoot(tree.getRootHash());
            b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), PARAMS.powAlgorithm()));
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(b));
        }
    }

    /** A PeerSource backed by another engine. */
    private static final class EnginePeer implements PeerSource {
        final ChainEngine engine;
        EnginePeer(ChainEngine engine) { this.engine = engine; }
        public long height() { return engine.height(); }
        public BigInteger totalWork() { return engine.totalWork(); }
        public SHA256Hash blockHash(long height) { return engine.blockAt(height).hash(); }
        public List<Block> blocks(long start, long end) {
            List<Block> out = new ArrayList<>();
            for (long h = start; h <= end; h++) out.add(engine.blockAt(h));
            return out;
        }
    }

    @Test
    void freshNodeCatchesUp() {
        AtomicLong peerClock = new AtomicLong(0);
        ChainEngine peer = newEngine();
        mineBlocks(peer, PublicAddress.random(), peerClock, 5);

        ChainEngine local = newEngine();
        Result result = new ChainSynchronizer(local).syncFrom(new EnginePeer(peer));

        assertEquals(Result.EXTENDED, result);
        assertEquals(6, local.height());
        assertEquals(peer.tipHash(), local.tipHash());
        assertEquals(peer.totalWork(), local.totalWork());
    }

    @Test
    void ignoresLighterOrEqualPeer() {
        AtomicLong localClock = new AtomicLong(0);
        ChainEngine local = newEngine();
        mineBlocks(local, PublicAddress.random(), localClock, 4);

        AtomicLong peerClock = new AtomicLong(0);
        ChainEngine peer = newEngine();
        mineBlocks(peer, PublicAddress.random(), peerClock, 2); // shorter -> less work

        assertEquals(Result.NO_CHANGE, new ChainSynchronizer(local).syncFrom(new EnginePeer(peer)));
        assertEquals(5, local.height());
    }

    @Test
    void reorgsToHeavierCompetingChain() {
        // Local: genesis + 2 blocks (miner A). Peer: genesis + 4 blocks (miner B) -> diverges at height 2.
        AtomicLong localClock = new AtomicLong(1000);
        ChainEngine local = newEngine();
        mineBlocks(local, PublicAddress.random(), localClock, 2);
        SHA256Hash localTipBefore = local.tipHash();

        AtomicLong peerClock = new AtomicLong(5000);
        ChainEngine peer = newEngine();
        mineBlocks(peer, PublicAddress.random(), peerClock, 4);

        Result result = new ChainSynchronizer(local).syncFrom(new EnginePeer(peer));

        assertEquals(Result.REORGED, result);
        assertEquals(5, local.height());
        assertEquals(peer.tipHash(), local.tipHash());
        assertTrue(local.totalWork().compareTo(peer.totalWork()) == 0);
        assertTrue(!local.tipHash().equals(localTipBefore));
    }

    @Test
    void lyingPeerDoesNotCorruptLocalState() {
        AtomicLong localClock = new AtomicLong(1000);
        ChainEngine local = newEngine();
        mineBlocks(local, PublicAddress.random(), localClock, 2);
        long heightBefore = local.height();
        SHA256Hash tipBefore = local.tipHash();
        BigInteger workBefore = local.totalWork();

        // A peer that claims huge work and a diverging chain but serves an invalid block.
        PeerSource liar = new PeerSource() {
            public long height() { return 4; }
            public BigInteger totalWork() { return workBefore.add(BigInteger.valueOf(1_000_000)); }
            public SHA256Hash blockHash(long h) {
                return h == 1 ? local.blockAt(1).hash() : SHA256Hash.random(); // fork at genesis
            }
            public List<Block> blocks(long start, long end) {
                // A structurally invalid block with NO valid PoW. difficulty 30 (not 4) so a random
                // nonce fails verifyNonce deterministically: the branch is rejected at the stateless
                // branchChainsFromFork gate as PEER_INVALID (a real protocol violation), never reaching
                // the work-comparison gate — which, since the 5th-pass reorg-gate fix, correctly returns
                // NO_CHANGE for a merely-lighter VALID branch. (At difficulty 4 the nonce passed PoW ~1/16
                // of runs and slipped through to that gate, a latent flake the fix exposed.)
                var bad = (BlockImpl) BlockImpl.builder().id((int) start).timestamp(9_000_000)
                    .difficulty(30).lastBlockHash(local.blockAt(1).hash())
                    .merkleRoot(SHA256Hash.random()).nonce(SHA256Hash.random()).build();
                return List.of(bad);
            }
        };

        Result result = new ChainSynchronizer(local).syncFrom(liar);

        assertEquals(Result.PEER_INVALID, result);
        assertEquals(heightBefore, local.height());
        assertEquals(tipBefore, local.tipHash());
        assertEquals(workBefore, local.totalWork());
    }

    @Test
    void incompatibleGenesisIsRejected() {
        AtomicLong localClock = new AtomicLong(0);
        ChainEngine local = newEngine();
        mineBlocks(local, PublicAddress.random(), localClock, 1);

        // Peer on a different network (different chainId -> different genesis commitment).
        NetworkParameters otherNet = PARAMS.toBuilder().chainId(999).build();
        AtomicLong peerClock = new AtomicLong(0);
        ChainEngine peer = newEngine(otherNet);
        mineBlocks(peer, PublicAddress.random(), peerClock, 5);

        assertEquals(Result.INCOMPATIBLE, new ChainSynchronizer(local).syncFrom(new EnginePeer(peer)));
        assertEquals(2, local.height()); // untouched
    }
}
