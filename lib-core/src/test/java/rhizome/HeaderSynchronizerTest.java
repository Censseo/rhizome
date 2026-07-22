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
}
