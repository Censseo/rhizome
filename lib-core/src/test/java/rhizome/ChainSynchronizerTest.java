package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ChainStore;
import rhizome.core.blockchain.ChainSynchronizer;
import rhizome.core.blockchain.ChainSynchronizer.Result;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.LocalSaturationException;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.PeerSource;
import rhizome.core.blockchain.TestNodeStores;
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
        .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).minDifficulty(4).build();

    // Wall-clock "now" is far ahead of the (historical) block timestamps we mine,
    // as it is for a real node syncing past blocks — so the future-time check passes.
    private static final long NOW = 100_000_000_000L;

    /** A fresh engine seeded from the shared snapshot (so all chains share a genesis). */
    private static ChainEngine newEngine() {
        return newEngine(PARAMS);
    }

    private static ChainEngine newEngine(NetworkParameters params) {
        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, params.chainId());
        return ChainEngine.boot(params, TestNodeStores.inMemory(), snapshot).clock(() -> NOW).build();
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

    /** Mines and registers an orphan sibling of {@code engine}'s tip (forks from the tip's parent). */
    private static BlockImpl orphanSiblingOfTip(ChainEngine engine, AtomicLong clock, PublicAddress orphanMiner) {
        long tipHeight = engine.height();
        SHA256Hash grandparent = engine.blockAt(tipHeight - 1).hash();
        var orphan = (BlockImpl) BlockImpl.builder().id((int) tipHeight)
            .timestamp(clock.addAndGet(500)).difficulty(engine.difficulty())
            .lastBlockHash(grandparent).uncles(new ArrayList<>()).build();
        orphan.addTransaction(Transaction.of(orphanMiner, new TransactionAmount(PARAMS.miningReward(tipHeight))));
        var tree = new MerkleTree();
        tree.setItems(orphan.transactions());
        orphan.merkleRoot(tree.getRootHash());
        orphan.nonce(Miner.mineNonce(orphan.hash(), orphan.difficulty(), PARAMS.powAlgorithm()));
        engine.registerOrphan(orphan);
        return orphan;
    }

    /** Mines a next block on {@code engine}'s tip carrying the given uncle references. */
    private static BlockImpl mineNephew(ChainEngine engine, AtomicLong clock, List<rhizome.core.block.UncleRef> uncles) {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) height)
            .timestamp(clock.addAndGet(1000)).difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash()).uncles(new ArrayList<>(uncles)).build();
        b.addTransaction(Transaction.of(PublicAddress.random(), new TransactionAmount(PARAMS.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), PARAMS.powAlgorithm()));
        return b;
    }

    private static rhizome.core.block.UncleRef ref(BlockImpl orphan) {
        return new rhizome.core.block.UncleRef(orphan.hash(), orphan.difficulty(),
            ((rhizome.core.transaction.TransactionImpl) orphan.transactions().get(0)).to());
    }

    @Test
    void keepsAHeavierGhostSubtreeAgainstABaseHeavierPeer() {
        // GHOST fork choice (§3.7, audit S4). The LOCAL chain is base-lighter but carries uncle work:
        // height 2 + a height-3 nephew citing two height-2 uncle orphans (base 2 + uncles 2 = 4 units
        // above genesis). The PEER is a plain 3-block chain (base 3, no uncles). The peer has strictly
        // more BASE work (3 > 2), so the base-only anti-DoS gate lets it in — but the authoritative
        // fork choice weights uncle work, and the local SUBTREE (4) is heavier than the peer's (3), so
        // the peer must be REFUSED and the local chain restored. Before the fix, fork choice was
        // base-only and the node wrongly abandoned its heavier subtree for the peer.
        AtomicLong localClock = new AtomicLong(1000);
        ChainEngine local = newEngine();
        mineBlocks(local, PublicAddress.random(), localClock, 1);       // height 2 (canonical)
        BlockImpl u1 = orphanSiblingOfTip(local, localClock, PublicAddress.random());
        BlockImpl u2 = orphanSiblingOfTip(local, localClock, PublicAddress.random());
        assertEquals(ExecutionStatus.SUCCESS,
            local.addBlock(mineNephew(local, localClock, List.of(ref(u1), ref(u2)))));  // height 3 + 2 uncles
        long localHeightBefore = local.height();
        SHA256Hash localTipBefore = local.tipHash();
        BigInteger localTotalBefore = local.totalWork();

        AtomicLong peerClock = new AtomicLong(9000);
        ChainEngine peer = newEngine();
        mineBlocks(peer, PublicAddress.random(), peerClock, 3);          // height 4, base-only

        // Sanity: the peer is base-heavier (would win a longest-base race) but subtree-lighter.
        assertTrue(peer.totalWork().compareTo(local.baseWork()) > 0, "peer must clear the base-only gate");
        assertTrue(local.totalWork().compareTo(peer.totalWork()) > 0, "local subtree must be GHOST-heavier");

        Result result = new ChainSynchronizer(local).syncFrom(new EnginePeer(peer));

        assertEquals(Result.NO_CHANGE, result, "a base-heavier but GHOST-lighter peer must be refused");
        assertEquals(localHeightBefore, local.height(), "local chain must be intact");
        assertEquals(localTipBefore, local.tipHash());
        assertEquals(localTotalBefore, local.totalWork(), "local subtree weight must be exactly restored");
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

    /**
     * A {@link ChainStore} simulating a pruned node: bodies for heights in
     * {@code (1, watermark)} are gone and {@code blockAt} throws for them, while headers and the
     * transaction index are retained — exactly what a real pruned store keeps (genesis always
     * stays). Used to prove the fork probe reads headers, not bodies (audit F4).
     */
    private static final class PrunedChainStore implements ChainStore {
        private final List<Block> blocks = new ArrayList<>();
        private final Map<SHA256Hash, Long> txIndex = new HashMap<>();
        private final long watermark;

        PrunedChainStore(long watermark) {
            this.watermark = watermark;
        }

        @Override
        public long height() {
            return blocks.size();
        }

        @Override
        public Block blockAt(long height) {
            if (height < 1 || height > blocks.size()) {
                throw new IllegalArgumentException("No block at height " + height);
            }
            if (height > 1 && height < watermark) {
                throw new IllegalStateException("body pruned at height " + height);
            }
            return blocks.get((int) height - 1);
        }

        @Override
        public BlockHeader headerAt(long height) {
            if (height < 1 || height > blocks.size()) {
                throw new IllegalArgumentException("No block at height " + height);
            }
            return BlockHeader.of(blocks.get((int) height - 1));
        }

        @Override
        public long prunedBelow() {
            return watermark;
        }

        @Override
        public void append(Block block) {
            blocks.add(block);
            for (var t : block.transactions()) {
                if (!((rhizome.core.transaction.TransactionImpl) t).isTransactionFee()) {
                    txIndex.put(t.hashContents(), (long) blocks.size());
                }
            }
        }

        @Override
        public void pop() {
            Block removed = blocks.remove(blocks.size() - 1);
            for (var t : removed.transactions()) {
                if (!((rhizome.core.transaction.TransactionImpl) t).isTransactionFee()) {
                    txIndex.remove(t.hashContents());
                }
            }
        }

        @Override
        public boolean hasTransaction(SHA256Hash contentHash) {
            return txIndex.containsKey(contentHash);
        }
    }

    /** Mines one block onto {@code engine} and returns it (so a pruned store never has to serve it back). */
    private static BlockImpl mineOne(ChainEngine engine, PublicAddress miner, AtomicLong clock,
                                     NetworkParameters params) {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder()
            .id((int) height)
            .timestamp(clock.addAndGet(90_000))
            .difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash())
            .build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(b));
        return b;
    }

    @Test
    void prunedNodeForkProbeUsesHeadersNotBodies() {
        // audit F4: the full-block sync fork probe compared engine.blockAt(h).hash(), which throws
        // below the prune watermark — an honest archive peer whose fork point sits under the
        // watermark was misjudged PEER_INVALID (banned) instead of simply refused as REORG_TOO_DEEP.
        // Headers survive pruning and hash identically, so the probe must read them.
        NetworkParameters prunedParams = PARAMS.toBuilder().maxReorgDepth(3).build();
        // Local node: 7 blocks over a store pruned below height 7 (bodies for 2..6 gone).
        AtomicLong localClock = new AtomicLong(1000);
        PublicAddress localMiner = PublicAddress.random();
        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, prunedParams.chainId());
        ChainEngine local = ChainEngine.boot(
                prunedParams,
                TestNodeStores.mixing(new InMemoryLedger(), new PrunedChainStore(7)),
                snapshot)
            .clock(() -> NOW)
            .build();
        List<Block> kept = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            kept.add(mineOne(local, localMiner, localClock, prunedParams)); // heights 2..8
        }
        SHA256Hash localTip = local.tipHash();

        // An honest archive peer sharing blocks 1-2, then diverging with a base-heavier branch.
        AtomicLong peerClock = new AtomicLong(9000);
        ChainEngine peer = newEngine(prunedParams);
        assertEquals(ExecutionStatus.SUCCESS, peer.addBlock(kept.get(0))); // shared height 2
        mineBlocks(peer, PublicAddress.random(), peerClock, 8);            // heights 3..10

        // The fork (height 2) is below the prune watermark: the probe must still FIND it (via
        // headers) and refuse the deep reorg — never PEER_INVALID for an honest peer.
        Result result = new ChainSynchronizer(local).syncFrom(new EnginePeer(peer));
        assertEquals(Result.REORG_TOO_DEEP, result);
        assertEquals(8, local.height());
        assertEquals(localTip, local.tipHash(), "local chain must be untouched");
    }

    @Test
    void fallbackGateRejectsWrongDifficultyBeforeAnyPop() {
        // Audit: the full-block fallback gate checked id/chaining/PoW but NOT the recomputed
        // difficulty (nor MTP / the future bound), so a peer could drive pop/restore cycles with
        // blocks addBlock was guaranteed to reject. The gate now runs the same stateless
        // HeaderChain validation as headers-first sync — a wrong-difficulty branch is refused
        // BEFORE any local mutation. A pop-counting store proves no pop ever happens.
        AtomicLong clock = new AtomicLong(1000);
        PopCountingStore store = new PopCountingStore();
        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, PARAMS.chainId());
        ChainEngine local = ChainEngine.boot(
                PARAMS,
                TestNodeStores.mixing(new InMemoryLedger(), store),
                snapshot)
            .clock(() -> NOW)
            .build();
        mineBlocks(local, PublicAddress.random(), clock, 3); // heights 2..4 at difficulty 4
        SHA256Hash tipBefore = local.tipHash();
        BigInteger workBefore = local.totalWork();

        // A branch forking at genesis: valid PoW per block but at difficulty 5 where the
        // recomputed expectation is 4 — the OLD gate accepted it (PoW holds at 5), popped the
        // local suffix, then watched addBlock reject every block and restored.
        List<Block> branch = new ArrayList<>();
        SHA256Hash parent = local.blockAt(1).hash();
        for (int h = 2; h <= 5; h++) {
            var b = (BlockImpl) BlockImpl.builder().id(h).timestamp(9_000_000L + h * 90_000L)
                .difficulty(5).lastBlockHash(parent).build();
            b.addTransaction(Transaction.of(PublicAddress.random(),
                new TransactionAmount(PARAMS.miningReward(h))));
            var tree = new MerkleTree();
            tree.setItems(b.transactions());
            b.merkleRoot(tree.getRootHash());
            b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), PARAMS.powAlgorithm()));
            parent = b.hash();
            branch.add(b);
        }
        PeerSource peer = new PeerSource() {
            public long height() { return 5; }
            public BigInteger totalWork() { return workBefore.add(BigInteger.valueOf(1_000_000)); }
            public SHA256Hash blockHash(long h) {
                return h == 1 ? local.blockAt(1).hash()
                    : h <= 5 ? branch.get((int) h - 2).hash() : SHA256Hash.random();
            }
            public List<Block> blocks(long start, long end) {
                List<Block> out = new ArrayList<>();
                for (long h = start; h <= end && h <= 5; h++) {
                    out.add(branch.get((int) h - 2));
                }
                return out;
            }
        };

        Result result = new ChainSynchronizer(local).syncFrom(peer);

        assertEquals(Result.PEER_INVALID, result);
        assertEquals(0, store.pops, "the gate must reject BEFORE any pop/restore cycle");
        assertEquals(4, local.height());
        assertEquals(tipBefore, local.tipHash());
        assertEquals(workBefore, local.totalWork());
    }

    @Test
    void localSaturationDuringSyncIsNotPeerInvalid() {
        // A peer exchange rejected by LOCAL transport backpressure (the bounded body-read pool
        // being full, surfaced by the HTTP adapter as LocalSaturationException) carries no
        // information about the peer: it must surface as NO_CHANGE — retried next round — never
        // as PEER_INVALID, which would ban-score an honest peer for our own load (audit:
        // AbortPolicy saturation imputed to peers).
        AtomicLong peerClock = new AtomicLong(0);
        ChainEngine peerEngine = newEngine();
        mineBlocks(peerEngine, PublicAddress.random(), peerClock, 5);
        ChainEngine local = newEngine();
        PeerSource saturated = new PeerSource() {
            public long height() { return peerEngine.height(); }
            public BigInteger totalWork() { return peerEngine.totalWork(); }
            public SHA256Hash blockHash(long height) {
                throw new LocalSaturationException("local body-read pool saturated", null);
            }
            public List<Block> blocks(long start, long end) {
                throw new AssertionError("unreachable: the ancestor probe fails first");
            }
        };

        Result result = new ChainSynchronizer(local).syncFrom(saturated);

        assertEquals(Result.NO_CHANGE, result,
            "local transport backpressure must not be read as a peer fault (PEER_INVALID)");
        assertEquals(1, local.height(), "genesis only: nothing was applied");
    }

    @Test
    void extensionWindowIsCappedDespiteAnOverReportingPeer() {
        // peer.height() is self-reported: a peer that agrees with our tip but claims a far
        // greater height must not size the fetch loop — the extension window is capped at
        // HeaderSynchronizer.MAX_HEADER_WINDOW (20_000; package-private, pinned here by value)
        // and the round returns EXTENDED so the next round continues (audit: sync window).
        AtomicLong peerClock = new AtomicLong(0);
        ChainEngine full = newEngine();
        mineBlocks(full, PublicAddress.random(), peerClock, 6); // real heights 2..7

        ChainEngine local = newEngine();
        // Give local the SAME prefix up to height 6, so the peer purely extends it (fork = tip).
        for (int h = 2; h <= 6; h++) {
            assertEquals(ExecutionStatus.SUCCESS, local.addBlock(full.blockAt(h)));
        }
        long fork = local.height();
        long claimed = fork + 20_000L + 10_000L; // the lie: far beyond the real 7
        AtomicLong maxRequestedEnd = new AtomicLong();
        PeerSource liar = new PeerSource() {
            public long height() { return claimed; }
            public BigInteger totalWork() { return full.totalWork(); }
            public SHA256Hash blockHash(long h) { return full.blockAt(h).hash(); }
            public List<Block> blocks(long start, long end) {
                maxRequestedEnd.set(Math.max(maxRequestedEnd.get(), end));
                List<Block> out = new ArrayList<>();
                for (long h = start; h <= Math.min(end, full.height()); h++) {
                    out.add(full.blockAt(h));
                }
                return out;
            }
        };

        Result result = new ChainSynchronizer(local).syncFrom(liar);

        assertEquals(Result.EXTENDED, result, "a capped window reports progress, not failure");
        assertEquals(full.height(), local.height(), "every real block was applied");
        assertEquals(fork + 20_000L, maxRequestedEnd.get(),
            "the fetch window is capped, never sized by the peer's self-reported height");
    }

    /** A {@link ChainStore} that counts pops, proving a gate rejected before any local mutation. */
    private static final class PopCountingStore implements ChainStore {
        private final InMemoryChainStore delegate = new InMemoryChainStore();
        int pops;
        public long height() { return delegate.height(); }
        public Block blockAt(long height) { return delegate.blockAt(height); }
        public void append(Block block) { delegate.append(block); }
        public void pop() { pops++; delegate.pop(); }
        public boolean hasTransaction(SHA256Hash contentHash) { return delegate.hasTransaction(contentHash); }
    }
}
