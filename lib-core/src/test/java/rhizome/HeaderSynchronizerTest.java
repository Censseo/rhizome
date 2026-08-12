package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ChainSynchronizer;
import rhizome.core.blockchain.HeaderSynchronizer;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.LocalSaturationException;
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

/**
 * The headers-first synchroniser: it extends and reorgs like the old block-based one,
 * but a peer that lies about its total work is refused after downloading only headers —
 * never a block — and a peer without the /headers endpoint transparently falls back to
 * full-block sync.
 */
class HeaderSynchronizerTest {

    private static final NetworkParameters PARAMS = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).minDifficulty(4).build();

    private static ChainEngine newEngine() {
        return ChainEngine.init(PARAMS, new InMemoryLedger(), new InMemoryChainStore(),
            new LedgerSnapshot("t", 0, PARAMS.chainId()), null, () -> 100_000_000_000L);
    }

    private static void mine(ChainEngine engine, PublicAddress miner, AtomicLong clock, int count) {
        for (int i = 0; i < count; i++) {
            long h = engine.height() + 1;
            var b = (BlockImpl) BlockImpl.builder().id((int) h)
                .timestamp(clock.addAndGet(90_000)).difficulty(engine.difficulty())
                .lastBlockHash(engine.tipHash()).build();
            b.addTransaction(Transaction.of(miner, new TransactionAmount(PARAMS.miningReward(h))));
            var tree = new MerkleTree();
            tree.setItems(b.transactions());
            b.merkleRoot(tree.getRootHash());
            b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), PARAMS.powAlgorithm()));
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(b));
        }
    }

    /** A {@link PeerSource} reading straight from an engine (in-process, no HTTP). */
    static class EnginePeer implements PeerSource {
        final ChainEngine e;
        int blockFetches = 0;
        long prunedBelow = 0;
        EnginePeer(ChainEngine e) { this.e = e; }
        @Override public long height() { return e.height(); }
        @Override public BigInteger totalWork() { return e.totalWork(); }
        @Override public long prunedBelow() { return prunedBelow; }
        @Override public SHA256Hash blockHash(long h) { return e.blockAt(h).hash(); }
        @Override public List<Block> blocks(long start, long end) {
            blockFetches++;
            List<Block> out = new ArrayList<>();
            for (long h = start; h <= Math.min(end, e.height()); h++) out.add(e.blockAt(h));
            return out;
        }
        @Override public List<BlockHeader> headers(long start, long end) {
            List<BlockHeader> out = new ArrayList<>();
            for (long h = start; h <= Math.min(end, e.height()); h++) out.add(e.headerAt(h));
            return out;
        }
        @Override public Block orphan(SHA256Hash hash) { return e.orphanBlock(hash); }
    }

    /** Mines and gossips an orphan sibling of the current tip (not part of the canonical chain). */
    private static BlockImpl mineOrphan(ChainEngine engine, PublicAddress miner, AtomicLong clock) {
        long tipHeight = engine.height();
        SHA256Hash grandparent = engine.blockAt(tipHeight - 1).hash();
        var orphan = (BlockImpl) BlockImpl.builder().id((int) tipHeight)
            .timestamp(clock.addAndGet(500)).difficulty(engine.difficulty())
            .lastBlockHash(grandparent).uncles(new ArrayList<>()).build();
        orphan.addTransaction(Transaction.of(miner, new TransactionAmount(PARAMS.miningReward(tipHeight))));
        var tree = new MerkleTree();
        tree.setItems(orphan.transactions());
        orphan.merkleRoot(tree.getRootHash());
        orphan.nonce(Miner.mineNonce(orphan.hash(), orphan.difficulty(), PARAMS.powAlgorithm()));
        engine.registerOrphan(orphan);
        return orphan;
    }

    /** Mines the next canonical block citing {@code orphan} as an uncle (protocol-incentivised). */
    private static BlockImpl mineNephew(ChainEngine engine, BlockImpl orphan, AtomicLong clock) {
        long height = engine.height() + 1;
        var ref = new rhizome.core.block.UncleRef(orphan.hash(), orphan.difficulty(),
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
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(b));
        return b;
    }

    @Test
    void extendsFromHeavierPeer() {
        ChainEngine peer = newEngine();
        mine(peer, PublicAddress.random(), new AtomicLong(0), 5);

        ChainEngine local = newEngine();
        ChainSynchronizer.Result r = new HeaderSynchronizer(local).syncFrom(new EnginePeer(peer));

        assertEquals(ChainSynchronizer.Result.EXTENDED, r);
        assertEquals(6, local.height());
        assertEquals(peer.totalWork(), local.totalWork());
        assertTrue(local.tipHash().equals(peer.tipHash()));
    }

    @Test
    void extendsAcrossMultipleBodyWindowsInOrder() {
        // More than BLOCKS_PER_FETCH (200) blocks, so applyBodies pipelines across at least two fetch
        // windows: the "prefetch next window while applying current" path must apply every body in the
        // right order and reach the exact peer tip (the pipeline overlaps I/O but must not reorder or
        // drop blocks).
        ChainEngine peer = newEngine();
        mine(peer, PublicAddress.random(), new AtomicLong(0), 250);

        ChainEngine local = newEngine();
        ChainSynchronizer.Result r = new HeaderSynchronizer(local).syncFrom(new EnginePeer(peer));

        assertEquals(ChainSynchronizer.Result.EXTENDED, r);
        assertEquals(251, local.height());
        assertEquals(peer.totalWork(), local.totalWork());
        assertTrue(local.tipHash().equals(peer.tipHash()));
        // Spot-check bodies landed at the right heights (in-order apply across the window boundary).
        for (long h = 2; h <= 251; h += 37) {
            assertEquals(peer.blockAt(h).hash(), local.blockAt(h).hash(), "block " + h + " must match");
        }
    }

    @Test
    void reorgsToAHeavierBranch() {
        ChainEngine local = newEngine();
        mine(local, PublicAddress.random(), new AtomicLong(0), 3); // local: genesis + 3

        ChainEngine peer = newEngine();
        mine(peer, PublicAddress.random(), new AtomicLong(0), 6); // peer: genesis + 6, heavier

        ChainSynchronizer.Result r = new HeaderSynchronizer(local).syncFrom(new EnginePeer(peer));

        assertEquals(ChainSynchronizer.Result.REORGED, r);
        assertEquals(7, local.height());
        assertTrue(local.tipHash().equals(peer.tipHash()));
    }

    @Test
    void peerLyingAboutTotalWorkCostsOnlyHeaders() {
        ChainEngine local = newEngine();
        mine(local, PublicAddress.random(), new AtomicLong(0), 5); // real work W

        ChainEngine peer = newEngine();
        mine(peer, PublicAddress.random(), new AtomicLong(0), 2); // only 2 blocks of real work

        // The peer claims enormous work but can only serve its 2 light headers.
        EnginePeer liar = new EnginePeer(peer) {
            @Override public BigInteger totalWork() { return e.totalWork().add(BigInteger.TWO.pow(200)); }
        };

        ChainSynchronizer.Result r = new HeaderSynchronizer(local).syncFrom(liar);

        // The peer served structurally VALID headers that simply prove less base work — it loses the
        // fork race but committed no protocol violation, so it is left alone (NO_CHANGE), not banned as
        // PEER_INVALID (audit 5th-pass, reorg-gate metric: don't ban honest total-heavier/base-lighter
        // peers). Local is still untouched and no body was downloaded.
        assertEquals(ChainSynchronizer.Result.NO_CHANGE, r);
        assertEquals(6, local.height(), "local chain untouched");
        assertEquals(0, liar.blockFetches, "gate rejected on headers alone — no body downloaded");
    }

    @Test
    void skipsPeerThatHasPrunedTheNeededBodies() {
        ChainEngine peer = newEngine();
        mine(peer, PublicAddress.random(), new AtomicLong(0), 11); // peer height 12

        EnginePeer pruned = new EnginePeer(peer);
        pruned.prunedBelow = 6; // bodies below 6 discarded

        ChainEngine local = newEngine(); // fresh: needs bodies from height 2 up
        ChainSynchronizer.Result r = new HeaderSynchronizer(local).syncFrom(pruned);

        assertEquals(ChainSynchronizer.Result.PEER_PRUNED, r);
        assertEquals(1, local.height(), "nothing applied");
        assertEquals(0, pruned.blockFetches, "did not even attempt to download pruned bodies");
    }

    @Test
    void syncsFromPrunedPeerWhenTheNeededRangeIsRetained() {
        ChainEngine peer = newEngine();
        AtomicLong peerClock = new AtomicLong(0);
        PublicAddress peerMiner = PublicAddress.random();
        mine(peer, peerMiner, peerClock, 10); // peer height 11

        // Local catches up fully while the peer is still an archive.
        ChainEngine local = newEngine();
        assertEquals(ChainSynchronizer.Result.EXTENDED, new HeaderSynchronizer(local).syncFrom(new EnginePeer(peer)));
        assertEquals(11, local.height());

        // The peer advances (same clock) and then prunes below 6; local only needs the tail (12..13).
        mine(peer, peerMiner, peerClock, 2);
        EnginePeer prunedPeer = new EnginePeer(peer);
        prunedPeer.prunedBelow = 6;

        ChainSynchronizer.Result r = new HeaderSynchronizer(local).syncFrom(prunedPeer);
        assertEquals(ChainSynchronizer.Result.EXTENDED, r);
        assertEquals(13, local.height());
        assertTrue(local.tipHash().equals(peer.tipHash()));
    }

    @Test
    void localSaturationDuringHeaderProbeIsNotPeerInvalid() {
        // Same rule as ChainSynchronizerTest.localSaturationDuringSyncIsNotPeerInvalid, on the
        // headers-first path: a LocalSaturationException out of the /headers probe is LOCAL
        // backpressure (our bounded body-read pool full), not a peer fault — NO_CHANGE, retried
        // next round, never the ban-score-earning PEER_INVALID.
        ChainEngine peer = newEngine();
        mine(peer, PublicAddress.random(), new AtomicLong(0), 5);
        EnginePeer saturated = new EnginePeer(peer) {
            @Override public List<BlockHeader> headers(long start, long end) {
                throw new LocalSaturationException("local body-read pool saturated", null);
            }
        };

        ChainEngine local = newEngine();
        ChainSynchronizer.Result r = new HeaderSynchronizer(local).syncFrom(saturated);

        assertEquals(ChainSynchronizer.Result.NO_CHANGE, r,
            "local transport backpressure must not be read as a peer fault (PEER_INVALID)");
        assertEquals(1, local.height(), "genesis only: nothing was applied");
    }

    @Test
    void fallsBackToBlockSyncForPeerWithoutHeaders() {
        ChainEngine peer = newEngine();
        mine(peer, PublicAddress.random(), new AtomicLong(0), 4);

        // A peer predating /headers: headers() throws, so the synchroniser must fall back.
        EnginePeer legacy = new EnginePeer(peer) {
            @Override public List<BlockHeader> headers(long start, long end) {
                throw new UnsupportedOperationException("no /headers");
            }
        };

        ChainEngine local = newEngine();
        ChainSynchronizer.Result r = new HeaderSynchronizer(local).syncFrom(legacy);

        assertEquals(ChainSynchronizer.Result.EXTENDED, r);
        assertEquals(5, local.height());
        assertTrue(local.tipHash().equals(peer.tipHash()));
        assertTrue(legacy.blockFetches > 0, "fallback path downloads full blocks");
    }

    // ---- metastable-split regression (testnet campaign S7) ----
    //
    // Two branches with EXACTLY equal base work above the fork (same heights, constant
    // difficulty) can differ only in genuine uncle work. The reorg gate used to treat the
    // base-work tie as a loss for the peer (validated.work() <= local -> NO_CHANGE), so the
    // GHOST vote — the one place validated uncle work is authoritative — was never reached
    // and the heavier-subtree branch could never win: a healed partition stayed silently
    // split for as long as the miners kept heights equal (measured: 6+ minutes, constant
    // work gap of exactly 2^difficulty). Fix 4 descends to phase 3 on a base-work TIE when
    // the peer's self-reported total could actually win; the GHOST vote then decides on
    // validated data.

    /** Local and peer fork at height 2 (different miners), then both mine blocks up to height 4:
     *  h2, h3, and an h4 that either carries one of their own orphans as an uncle
     *  ({@code localGetsAnUncleToo}) or is plain. The peer ALWAYS cites one uncle, so
     *  {@code false} gives "equal base, peer heavier total" and {@code true} gives "equal base,
     *  equal total" — the two S7 tie shapes. */
    private static ChainEngine[] equalBaseFork(boolean localGetsAnUncleToo) {
        AtomicLong clock = new AtomicLong(1000);
        PublicAddress minerA = PublicAddress.random();
        PublicAddress minerB = PublicAddress.random();

        ChainEngine local = newEngine();
        mine(local, minerA, clock, 2); // h2, h3
        if (localGetsAnUncleToo) {
            BlockImpl orphan = mineOrphan(local, minerA, clock);
            mineNephew(local, orphan, clock); // h4 cites the uncle
        } else {
            mine(local, minerA, clock, 1); // h4, plain
        }

        ChainEngine peer = newEngine();
        mine(peer, minerB, clock, 2); // h2, h3
        BlockImpl peerOrphan = mineOrphan(peer, minerB, clock);
        mineNephew(peer, peerOrphan, clock); // h4 cites the uncle

        return new ChainEngine[] {local, peer};
    }

    @Test
    void equalBaseWorkWithHeavierUncleSubtreeWinsViaGhost() {
        // S7: the peer's branch has the same base work but one more validated uncle — it must
        // win the fork race, breaking the metastable split, instead of being stuck at NO_CHANGE.
        ChainEngine[] engines = equalBaseFork(false);
        ChainEngine local = engines[0], peer = engines[1];
        // Sanity: this really is the S7 shape — equal heights, equal base work, heavier total.
        assertEquals(peer.height(), local.height());
        assertEquals(local.baseWork(), peer.baseWork());
        assertTrue(peer.totalWork().compareTo(local.totalWork()) > 0,
            "the peer's uncle must make its subtree heavier");

        ChainSynchronizer.Result r = new HeaderSynchronizer(local).syncFrom(new EnginePeer(peer));

        assertEquals(ChainSynchronizer.Result.REORGED, r,
            "a base-work tie with a heavier uncle subtree must resolve via the GHOST vote");
        assertEquals(peer.height(), local.height());
        assertTrue(local.tipHash().equals(peer.tipHash()), "the heavier subtree is adopted");
        assertEquals(peer.totalWork(), local.totalWork());
    }

    @Test
    void equalBaseWorkAndEqualTotalResolvesDeterministically() {
        // Anti-thrash + convergence (testnet campaign S5/S7 replay): two equal-rate camps hold
        // EXACTLY equal base work AND equal totals (one uncle each). The asymmetric total-only
        // gate leaves this forever unbroken; the deterministic tiebreak (smaller tip hash wins)
        // must converge both sides in one round — each side reaches the same verdict — and a
        // re-sync afterwards must be a quiet NO_CHANGE (no pop/restore oscillation).
        ChainEngine[] engines = equalBaseFork(true);
        ChainEngine a = engines[0], b = engines[1];
        assertEquals(a.baseWork(), b.baseWork(), "the S7 shape: equal base work");
        assertEquals(b.totalWork(), a.totalWork(), "the S7 shape: equal totals (one uncle each)");

        EnginePeer bView = new EnginePeer(b);
        ChainSynchronizer.Result r = new HeaderSynchronizer(a).syncFrom(bView);
        EnginePeer aView = new EnginePeer(a);
        ChainSynchronizer.Result r2 = new HeaderSynchronizer(b).syncFrom(aView);

        // Whichever branch won, both nodes now agree on one tip (the deterministic verdict is
        // the same on both sides), and exactly one side did the reorg work.
        assertEquals(a.tipHash(), b.tipHash(),
            "the deterministic tiebreak converges both camps on the same branch");
        int reorgs = (r == ChainSynchronizer.Result.REORGED ? 1 : 0)
            + (r2 == ChainSynchronizer.Result.REORGED ? 1 : 0);
        assertEquals(1, reorgs, "exactly the losing camp reorgs (the winning camp keeps its branch)");

        // Anti-thrash: once converged, another round from both sides must do nothing and must
        // not download a single body.
        ChainSynchronizer.Result after1 = new HeaderSynchronizer(a).syncFrom(new EnginePeer(b));
        ChainSynchronizer.Result after2 = new HeaderSynchronizer(b).syncFrom(new EnginePeer(a));
        assertEquals(ChainSynchronizer.Result.NO_CHANGE, after1);
        assertEquals(ChainSynchronizer.Result.NO_CHANGE, after2);
        assertEquals(a.tipHash(), b.tipHash(), "converged state is stable");
    }

    @Test
    void equalTotalTiebreakAlsoConvergesViaTheLegacyBlockFallback() {
        // The ChainSynchronizer fallback (legacy peers without /headers) applies the same
        // deterministic tiebreak: through the fallback path, the equal-base/equal-total camps
        // must also converge on one tip instead of staying split.
        ChainEngine[] engines = equalBaseFork(true);
        ChainEngine a = engines[0], b = engines[1];
        assertEquals(a.baseWork(), b.baseWork());

        EnginePeer legacyB = new EnginePeer(b) {
            @Override public List<BlockHeader> headers(long start, long end) {
                throw new UnsupportedOperationException("no /headers");
            }
        };
        new HeaderSynchronizer(a).syncFrom(legacyB);
        EnginePeer legacyA = new EnginePeer(a) {
            @Override public List<BlockHeader> headers(long start, long end) {
                throw new UnsupportedOperationException("no /headers");
            }
        };
        new HeaderSynchronizer(b).syncFrom(legacyA);

        assertEquals(a.tipHash(), b.tipHash(),
            "the legacy full-block path converges on the same deterministic branch");
    }

    @Test
    void aPeerThatGoesUnavailableMidBodyLeavesTheLocalBranchIntact() {
        // Review follow-up to the reorg-window 503. The body download runs INSIDE the reorg
        // window: the chain is already popped to the fork and the local branch is held only in
        // the synchronizer's frame. applyBodies re-throws a transport failure (rather than
        // returning false) so it is never read as PEER_INVALID — but that re-throw skipped the
        // restore on its way out, leaving the node truncated at the fork with a PARTIAL peer
        // branch that never faced the GHOST vote, and its own blocks — including any it mined
        // that no peer holds — silently gone. The failure must propagate AND the chain must be
        // exactly what it was.
        //
        // Not a corner case since the 503 gate: a peer entering its own reorg mid-stream is the
        // expected way for this to fire, and equal-work tips now reorg by construction.
        ChainEngine local = newEngine();
        AtomicLong clock = new AtomicLong(1000);
        PublicAddress minerA = PublicAddress.random();
        mine(local, minerA, clock, 2); // fork at h1, local branch = h2..h3

        ChainEngine peerEngine = newEngine();
        mine(peerEngine, PublicAddress.random(), clock, 4); // strictly heavier: h2..h5

        long heightBefore = local.height();
        SHA256Hash tipBefore = local.tipHash();
        BigInteger workBefore = local.totalWork();

        // Answers the header gate in full, then dies on the first BODY fetch — the exact shape of
        // a peer that opens its own reorg window between our header phase and our body phase.
        EnginePeer dyingMidBody = new EnginePeer(peerEngine) {
            @Override public List<Block> blocks(long start, long end) {
                throw new rhizome.core.blockchain.PeerUnavailableException("503 mid-body", null);
            }
        };

        org.junit.jupiter.api.Assertions.assertThrows(
            rhizome.core.blockchain.PeerUnavailableException.class,
            () -> new HeaderSynchronizer(local).syncFrom(dyingMidBody),
            "a transport failure must stay a transport failure: retried, never PEER_INVALID");

        assertEquals(heightBefore, local.height(), "the local branch must be restored, not dropped");
        assertEquals(tipBefore, local.tipHash(), "…to the exact tip it had before the attempt");
        assertEquals(workBefore, local.totalWork(), "…with its work intact");
        org.junit.jupiter.api.Assertions.assertFalse(local.isReorgInProgress(),
            "the reorg window must be closed, so the chain accepts new tips again");
    }

    @Test
    void aMalformedBodyWindowIsThePeersFaultAndNeverEscapesTheSyncPass() {
        // The headers-first and full-block paths classify a failed body fetch DIFFERENTLY, and
        // that disagreement is deliberate: the full-block path propagates every RuntimeException
        // to its caller, this one propagates only the two typed transport signals and treats
        // anything else as a bad peer. Only the two typed cases were pinned, so the divergence
        // itself was untested — swapping this path onto the other policy broke no test.
        ChainEngine peerEngine = newEngine();
        mine(peerEngine, PublicAddress.random(), new AtomicLong(0), 5);
        ChainEngine local = newEngine();

        EnginePeer garbageBodies = new EnginePeer(peerEngine) {
            @Override public List<Block> blocks(long start, long end) {
                throw new IllegalStateException("undecodable body window");
            }
        };

        assertEquals(ChainSynchronizer.Result.PEER_INVALID,
            new HeaderSynchronizer(local).syncFrom(garbageBodies),
            "a malformed body window is a verdict about the peer, not an exception for the round");
        assertEquals(1, local.height(), "nothing was applied from the failed window");
    }

    @Test
    void localBackpressureMidBodyIsNotAPeerFaultOnTheHeadersPath() {
        // The other side of the same policy: LocalSaturation is OUR bound, so it must reach
        // syncFrom unwrapped (the pipeline hands it back as an ExecutionException cause) and map
        // to NO_CHANGE — never a ban for a peer that did nothing wrong.
        ChainEngine peerEngine = newEngine();
        mine(peerEngine, PublicAddress.random(), new AtomicLong(0), 5);
        ChainEngine local = newEngine();

        EnginePeer saturated = new EnginePeer(peerEngine) {
            @Override public List<Block> blocks(long start, long end) {
                throw new LocalSaturationException("local exchange cap", null);
            }
        };

        assertEquals(ChainSynchronizer.Result.NO_CHANGE,
            new HeaderSynchronizer(local).syncFrom(saturated));
        assertEquals(1, local.height());
    }
}
