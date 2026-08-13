package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
import rhizome.core.block.UncleRef;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ChainStore;
import rhizome.core.blockchain.ChainSynchronizer;
import rhizome.core.blockchain.ChainSynchronizer.Result;
import rhizome.core.blockchain.HeaderSynchronizer;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.PeerSource;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.Ledger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.SHA256Hash;

/**
 * Regression test for the uncle-sync blocker (audit: CRITICAL — a block carrying an uncle made
 * the chain unsynchronisable for any fresh node).
 *
 * <p>A block carries only {@link UncleRef}s (hash + difficulty + miner), never the orphan
 * bodies, and {@code ChainEngine.validateUncles} requires each referenced uncle to resolve
 * locally. Previously the orphan pool (a bounded LRU fed only by live gossip) was the only
 * source, so a fresh node syncing an uncle-bearing chain failed with {@code INVALID_UNCLES}
 * → {@code PEER_INVALID} and banned every honest peer. The fix has three parts, all exercised
 * here:
 * <ol>
 *   <li>the engine persists validated uncle bodies ({@code ChainStore.putUncle}) and serves
 *       them via {@link ChainEngine#orphanBlock} even after a restart or pool eviction;</li>
 *   <li>peers expose them through {@link PeerSource#orphan};</li>
 *   <li>both synchronizers fetch missing uncle bodies on {@code INVALID_UNCLES} and retry the
 *       apply once — the fetch running outside the consensus lock.</li>
 * </ol>
 */
class UncleSyncRegressionTest {

    private static final NetworkParameters PARAMS = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).minDifficulty(4).build();

    private static final long NOW = 100_000_000_000L;

    private static ChainEngine newEngine() {
        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, PARAMS.chainId());
        return ChainEngine.init(PARAMS, new InMemoryLedger(), new InMemoryChainStore(),
            snapshot, null, () -> NOW);
    }

    /** Mines an empty block onto {@code engine}. */
    private static BlockImpl mineBlock(ChainEngine engine, PublicAddress miner, AtomicLong clock) {
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
        return b;
    }

    /** Mines and gossips an orphan sibling of the current tip (not part of the canonical chain). */
    private static BlockImpl mineOrphan(ChainEngine engine, AtomicLong clock) {
        long tipHeight = engine.height();
        SHA256Hash grandparent = engine.blockAt(tipHeight - 1).hash();
        var orphan = (BlockImpl) BlockImpl.builder().id((int) tipHeight)
            .timestamp(clock.addAndGet(500)).difficulty(engine.difficulty())
            .lastBlockHash(grandparent).uncles(new ArrayList<>()).build();
        PublicAddress orphanMiner = PublicAddress.random();
        orphan.addTransaction(Transaction.of(orphanMiner,
            new TransactionAmount(PARAMS.miningReward(tipHeight))));
        var tree = new MerkleTree();
        tree.setItems(orphan.transactions());
        orphan.merkleRoot(tree.getRootHash());
        orphan.nonce(Miner.mineNonce(orphan.hash(), orphan.difficulty(), PARAMS.powAlgorithm()));
        engine.registerOrphan(orphan); // live gossip; never replayed during sync
        return orphan;
    }

    /** Mines the next canonical block citing {@code orphan} as an uncle (protocol-incentivised). */
    private static BlockImpl mineNephew(ChainEngine engine, BlockImpl orphan, AtomicLong clock) {
        long height = engine.height() + 1;
        UncleRef ref = new UncleRef(orphan.hash(), orphan.difficulty(),
            ((rhizome.core.transaction.TransactionImpl) orphan.transactions().get(0)).to());
        var b = (BlockImpl) BlockImpl.builder().id((int) height)
            .timestamp(clock.addAndGet(1000)).difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash()).uncles(new ArrayList<>(List.of(ref))).build();
        b.addTransaction(Transaction.of(PublicAddress.random(),
            new TransactionAmount(PARAMS.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), PARAMS.powAlgorithm()));
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(b),
            "the node holding the orphan accepts the nephew: the chain is canonical");
        return b;
    }

    /**
     * A peer serving headers (the headers-first path) and orphans: its {@link #orphan} is
     * backed by the engine's {@code orphanBlock} — live pool first, persisted store second.
     */
    private static class FullPeer implements PeerSource {
        final ChainEngine engine;
        FullPeer(ChainEngine engine) { this.engine = engine; }
        public long height() { return engine.height(); }
        public BigInteger totalWork() { return engine.totalWork(); }
        public SHA256Hash blockHash(long height) { return engine.blockAt(height).hash(); }
        public List<Block> blocks(long start, long end) {
            List<Block> out = new ArrayList<>();
            for (long h = start; h <= end; h++) out.add(engine.blockAt(h));
            return out;
        }
        @Override
        public List<BlockHeader> headers(long start, long end) {
            List<BlockHeader> out = new ArrayList<>();
            for (long h = start; h <= end; h++) out.add(engine.headerAt(h));
            return out;
        }
        @Override
        public Block orphan(SHA256Hash hash) { return engine.orphanBlock(hash); }
    }

    /** A legacy peer: no /headers (forces the full-block fallback) and no orphan endpoint. */
    private static final class LegacyPeer implements PeerSource {
        final ChainEngine engine;
        LegacyPeer(ChainEngine engine) { this.engine = engine; }
        public long height() { return engine.height(); }
        public BigInteger totalWork() { return engine.totalWork(); }
        public SHA256Hash blockHash(long height) { return engine.blockAt(height).hash(); }
        public List<Block> blocks(long start, long end) {
            List<Block> out = new ArrayList<>();
            for (long h = start; h <= end; h++) out.add(engine.blockAt(h));
            return out;
        }
    }

    /** Builds the honest canonical chain: 2 blocks, 1 orphan, 1 nephew citing it, 2 more blocks. */
    private static ChainEngine buildCanonicalChainWithUncle() {
        AtomicLong clock = new AtomicLong(1000);
        ChainEngine peer = newEngine();
        mineBlock(peer, PublicAddress.random(), clock);              // height 2
        BlockImpl orphan = mineOrphan(peer, clock);                  // orphan @ height 2
        mineNephew(peer, orphan, clock);                             // height 3, cites the uncle
        mineBlock(peer, PublicAddress.random(), clock);              // height 4
        mineBlock(peer, PublicAddress.random(), clock);              // height 5
        return peer;
    }

    @Test
    void headersFirstSyncSucceedsPastAnUncleBearingBlock() {
        ChainEngine peer = buildCanonicalChainWithUncle();
        assertEquals(5, peer.height());

        ChainEngine freshNode = newEngine(); // empty orphan pool, like any new node
        Result result = new HeaderSynchronizer(freshNode).syncFrom(new FullPeer(peer));

        assertEquals(Result.EXTENDED, result,
            "a peer serving orphans lets a fresh node sync past the uncle-bearing block");
        assertEquals(5, freshNode.height());
        assertEquals(peer.tipHash(), freshNode.tipHash());
    }

    @Test
    void legacyFullBlockSyncSucceedsTheSameWay() {
        ChainEngine peer = buildCanonicalChainWithUncle();

        ChainEngine freshNode = newEngine();
        // Legacy peer without /headers → ChainSynchronizer fallback (addBlock path), but
        // still serving orphans through the same engine-backed endpoint.
        Result result = new ChainSynchronizer(freshNode).syncFrom(new FullPeer(peer) {
            @Override
            public List<BlockHeader> headers(long start, long end) {
                return null;
            }
        });

        assertEquals(Result.EXTENDED, result,
            "the legacy full-block path recovers missing uncles the same way");
        assertEquals(5, freshNode.height());
        assertEquals(peer.tipHash(), freshNode.tipHash());
    }

    @Test
    void syncStillFailsCleanlyWhenThePeerRefusesToServeOrphans() {
        ChainEngine canonical = buildCanonicalChainWithUncle();
        ChainEngine freshNode = newEngine();

        // A peer predating the orphan endpoint (the default answers null):
        // the chain cannot be validated, so the peer is rejected — but the node must stay
        // consistent and able to sync a chain it CAN validate afterwards.
        Result result = new HeaderSynchronizer(freshNode).syncFrom(new PeerSource() {
            public long height() { return canonical.height(); }
            public BigInteger totalWork() { return canonical.totalWork(); }
            public SHA256Hash blockHash(long height) { return canonical.blockAt(height).hash(); }
            public List<Block> blocks(long start, long end) {
                List<Block> out = new ArrayList<>();
                for (long h = start; h <= end; h++) out.add(canonical.blockAt(h));
                return out;
            }
            @Override
            public List<BlockHeader> headers(long start, long end) {
                List<BlockHeader> out = new ArrayList<>();
                for (long h = start; h <= end; h++) out.add(canonical.headerAt(h));
                return out;
            }
        });

        assertEquals(Result.PEER_INVALID, result,
            "without an orphan source the uncle-bearing chain is still rejected");
        // Clean partial progress, not corruption: blocks below the uncle applied fine, the
        // node stops at a VALID prefix of the peer's chain (height 2) and can still sync later.
        assertEquals(2, freshNode.height(), "stops just under the uncle-bearing block");
        assertEquals(canonical.headerAt(2).hash(), freshNode.tipHash(),
            "the applied prefix is the peer's own valid prefix, not a corrupted state");

        // No corrupted state: the same node still syncs an uncle-free chain normally.
        ChainEngine uncleFree = newEngine();
        AtomicLong clock = new AtomicLong(1000);
        for (int i = 0; i < 4; i++) {
            mineBlock(uncleFree, PublicAddress.random(), clock);
        }
        Result ok = new HeaderSynchronizer(freshNode).syncFrom(new FullPeer(uncleFree));
        assertEquals(5, freshNode.height(), "sanity: an uncle-free chain still syncs (reorg over the prefix)");
        assertEquals(uncleFree.tipHash(), freshNode.tipHash());
    }

    @Test
    void aServedOrphanWithBadProofOfWorkIsRejected() {
        ChainEngine canonical = buildCanonicalChainWithUncle();
        ChainEngine freshNode = newEngine();

        // A hostile peer serving a "correct-looking" orphan whose PoW does not hold:
        // registerOrphan re-verifies the memory-hard proof before pooling, so the retry
        // fails again and the peer is treated as invalid rather than poisoning the pool.
        PeerSource malicious = new FullPeer(canonical) {
            @Override
            public Block orphan(SHA256Hash hash) {
                Block genuine = engine.orphanBlock(hash);
                if (genuine == null) {
                    return null;
                }
                var fake = (BlockImpl) genuine;
                // The header hash does not commit the nonce, so the fake keeps the genuine
                // orphan's hash — but its PoW must genuinely FAIL (grind a nonce that misses the
                // target; at low test difficulty a fixed nonce could accidentally pass, making
                // the test flaky), so registerOrphan's verifyNonce is what rejects it.
                do {
                    fake.nonce(SHA256Hash.random());
                } while (fake.verifyNonce(PARAMS.powAlgorithm()));
                return fake;
            }
        };
        Result result = new HeaderSynchronizer(freshNode).syncFrom(malicious);

        assertEquals(Result.PEER_INVALID, result,
            "an orphan failing PoW is never pooled; the retry fails and the peer is rejected");
        assertTrue(freshNode.height() < 3, "no uncle-bearing block applied");
    }

    @Test
    void orphanBlockServesAPersistedUncleAfterALogicalRestart() {
        // One node builds the uncle-bearing chain on a store it shares with its restarted self.
        AtomicLong clock = new AtomicLong(1000);
        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, PARAMS.chainId());
        Ledger ledger = new InMemoryLedger();
        ChainStore store = new InMemoryChainStore();
        ChainEngine node = ChainEngine.init(PARAMS, ledger, store, snapshot, null, () -> NOW);
        mineBlock(node, PublicAddress.random(), clock);              // height 2
        BlockImpl orphan = mineOrphan(node, clock);                  // orphan @ height 2
        mineNephew(node, orphan, clock);                             // height 3, cites the uncle
        mineBlock(node, PublicAddress.random(), clock);              // height 4
        mineBlock(node, PublicAddress.random(), clock);              // height 5

        // Logical restart: a fresh engine over the same store. Its orphan pool is empty (the
        // LRU's content is gone), so only the persisted uncle body can answer.
        ChainEngine restarted = ChainEngine.init(PARAMS, ledger, store, snapshot, null, () -> NOW);
        Block served = restarted.orphanBlock(orphan.hash());
        assertNotNull(served, "the persisted uncle survives the restart / empty pool");
        assertEquals(orphan.hash(), served.hash());

        // ...and a fresh node syncing from the restarted peer still gets past the uncle.
        ChainEngine freshNode = newEngine();
        Result result = new HeaderSynchronizer(freshNode).syncFrom(new FullPeer(restarted));
        assertEquals(Result.EXTENDED, result);
        assertEquals(5, freshNode.height());
        assertEquals(restarted.tipHash(), freshNode.tipHash());
    }

    @Test
    void syncingNodeHoldingThePersistedUncleItselfCanStillAdoptTheBranch() {
        // Campaign-2 S7 shape: a node applied the uncle-bearing blocks BEFORE a restart, so its
        // STORE holds the orphan bodies (addBlock persists referenced uncles) while its in-memory
        // pool is empty. Later it must adopt a peer branch that cites the SAME uncle. The sync
        // paths' uncle-resolve skips the fetch when the body is "already known" — and "known"
        // includes the persisted store — so without a store fallback in validateUncles the retry
        // found an empty pool and failed with INVALID_UNCLES every round: PEER_INVALID, a ban of
        // an honest peer, and a wedge that no restart healed (node 5 froze at height 820 with
        // healthy peers, 90 penalties on a seed peer over 16 minutes).
        AtomicLong clock = new AtomicLong(1000);
        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, PARAMS.chainId());
        Ledger ledger = new InMemoryLedger();
        ChainStore store = new InMemoryChainStore();
        ChainEngine node = ChainEngine.init(PARAMS, ledger, store, snapshot, null, () -> NOW);
        PublicAddress miner = PublicAddress.random();
        mineBlock(node, miner, clock);                              // height 2
        BlockImpl orphan = mineOrphan(node, clock);                 // orphan @ height 2
        mineNephew(node, orphan, clock);                            // height 3, cites the uncle
        mineBlock(node, PublicAddress.random(), clock);             // height 4
        mineBlock(node, PublicAddress.random(), clock);             // height 5
        assertNotNull(node.orphanBlock(orphan.hash()),
            "the applied nephew persisted the orphan body into the store");

        // A competing, LONGER branch that cites the SAME orphan: fork at genesis, then a sibling
        // nephew at height 3 referencing it, extended to height 6.
        ChainEngine peer = newEngine();
        AtomicLong peerClock = new AtomicLong(2000);
        mineBlock(peer, PublicAddress.random(), peerClock);         // height 2 (different block)
        peer.registerOrphan(orphan);
        mineNephew(peer, orphan, peerClock);                        // height 3, cites the same uncle
        mineBlock(peer, PublicAddress.random(), peerClock);         // height 4
        mineBlock(peer, PublicAddress.random(), peerClock);         // height 5
        mineBlock(peer, PublicAddress.random(), peerClock);         // height 6
        assertEquals(6, peer.height());

        // Logical restart: same store, empty pool — the persisted uncle is the only source.
        ChainEngine restarted = ChainEngine.init(PARAMS, ledger, store, snapshot, null, () -> NOW);
        Result result = new HeaderSynchronizer(restarted).syncFrom(new FullPeer(peer));

        assertEquals(Result.REORGED, result,
            "the node that itself holds the persisted uncle still adopts the heavier branch");
        assertEquals(6, restarted.height());
        assertEquals(peer.tipHash(), restarted.tipHash());
    }
}
