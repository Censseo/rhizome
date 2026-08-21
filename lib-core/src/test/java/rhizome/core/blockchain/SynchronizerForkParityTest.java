package rhizome.core.blockchain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

/**
 * Locks the fork-choice parity between the two synchronizers: on the same fork scenario the
 * full-block path and the headers-first path must find the SAME common ancestor — through
 * {@link AncestorLocator}, with only the peer-hash transport differing — and then reach the
 * SAME verdict and the SAME tip. A divergence would mean the two paths pick different
 * branches for identical peers, which is exactly how a network splits.
 */
class SynchronizerForkParityTest {

    private static final NetworkParameters PARAMS = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).minDifficulty(4).build();

    private static final long NOW = 100_000_000_000L;

    private static ChainEngine newEngine() {
        return ChainEngine.boot(
                PARAMS,
                TestNodeStores.inMemory(),
                new LedgerSnapshot("test", 0, PARAMS.chainId()))
            .clock(() -> NOW)
            .build();
    }

    private static void mine(ChainEngine engine, PublicAddress miner, AtomicLong clock, int count) {
        for (int i = 0; i < count; i++) {
            long height = engine.height() + 1;
            var b = (BlockImpl) BlockImpl.builder()
                .id((int) height)
                .timestamp(clock.addAndGet(90_000))
                .difficulty(engine.difficulty())
                .lastBlockHash(engine.tipHash())
                .supply(SupplyStamp.next(engine, height, engine.difficulty()))
                .build();
            b.addTransaction(Transaction.of(miner, new TransactionAmount(PARAMS.miningReward(height))));
            var tree = new MerkleTree();
            tree.setItems(b.transactions());
            b.merkleRoot(tree.getRootHash());
            b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), PARAMS.powAlgorithm()));
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(b));
        }
    }

    /** A {@link PeerSource} reading straight from an engine, serving both transports. */
    private static final class EnginePeer implements PeerSource {
        final ChainEngine engine;
        EnginePeer(ChainEngine engine) { this.engine = engine; }
        public long height() { return engine.height(); }
        public BigInteger totalWork() { return engine.totalWork(); }
        public SHA256Hash blockHash(long h) { return engine.blockAt(h).hash(); }
        public List<Block> blocks(long start, long end) {
            List<Block> out = new ArrayList<>();
            for (long h = start; h <= Math.min(end, engine.height()); h++) {
                out.add(engine.blockAt(h));
            }
            return out;
        }
        public List<BlockHeader> headers(long start, long end) {
            List<BlockHeader> out = new ArrayList<>();
            for (long h = start; h <= Math.min(end, engine.height()); h++) {
                out.add(engine.headerAt(h));
            }
            return out;
        }
        public Block orphan(SHA256Hash hash) { return engine.orphanBlock(hash); }
    }

    /** The headers transport's per-height hash lookup, exactly as HeaderSynchronizer reads it. */
    private static SHA256Hash peerHeaderHash(EnginePeer peer, long h) {
        List<BlockHeader> one = peer.headers(h, h);
        if (one.isEmpty()) {
            throw new IllegalStateException("peer returned no header at " + h);
        }
        return one.get(0).hash();
    }

    @Test
    void bothTransportsLocateTheSameAncestorOnTheSameFork() {
        // Local: genesis + 12 blocks. Peer: shares the first 7, then a competing branch to 15.
        AtomicLong localClock = new AtomicLong(1000);
        ChainEngine local = newEngine();
        mine(local, PublicAddress.random(), localClock, 12);

        // The clock starts well past the shared tip's timestamps (the shared blocks carry the
        // LOCAL chain's timestamps, so the peer's own continuation must exceed them).
        AtomicLong peerClock = new AtomicLong(1_000_000);
        ChainEngine peer = newEngine();
        for (long h = 2; h <= 7; h++) {
            assertEquals(ExecutionStatus.SUCCESS, peer.addBlock(local.blockAt(h)));
        }
        mine(peer, PublicAddress.random(), peerClock, 9); // heights 8..16, diverging

        EnginePeer transport = new EnginePeer(peer);
        long byBlock = AncestorLocator.findCommonAncestor(local.height(), transport.height(),
            h -> local.headerAt(h).hash(), transport::blockHash);
        long byHeader = AncestorLocator.findCommonAncestor(local.height(), transport.height(),
            h -> local.headerAt(h).hash(), h -> peerHeaderHash(transport, h));

        assertEquals(7, byBlock, "the full-block transport finds the fork");
        assertEquals(byBlock, byHeader,
            "the headers transport must find the SAME ancestor — one locator, one answer");
    }

    @Test
    void bothSynchronizersAdoptTheSameBranch() {
        // The same fork scenario through the two full sync paths: identical verdicts, identical
        // final tips and heights — the branch choice is one, whichever transport the peer serves.
        AtomicLong peerClock = new AtomicLong(5000);
        ChainEngine peer = newEngine();
        mine(peer, PublicAddress.random(), peerClock, 5);
        List<Block> shared = new ArrayList<>();
        for (long h = 2; h <= 5; h++) {
            shared.add(peer.blockAt(h));
        }
        mine(peer, PublicAddress.random(), peerClock, 4); // peer continues alone to 9

        ChainEngine localChain = newEngine();
        for (Block b : shared) {
            assertEquals(ExecutionStatus.SUCCESS, localChain.addBlock(b));
        }
        // Continuation clocks must exceed the shared tip's timestamps (blocks 2..5 carry the
        // peer's timestamps, so a lower local clock would be median-time-rejected).
        AtomicLong localClock = new AtomicLong(1_000_000);
        mine(localChain, PublicAddress.random(), localClock, 2); // local diverges at 5, to 7

        ChainEngine localHeader = newEngine();
        for (Block b : shared) {
            assertEquals(ExecutionStatus.SUCCESS, localHeader.addBlock(b));
        }
        AtomicLong localHeaderClock = new AtomicLong(1_100_000);
        mine(localHeader, PublicAddress.random(), localHeaderClock, 2); // identical divergence

        ChainSynchronizer.Result byBlocks =
            new ChainSynchronizer(localChain).syncFrom(new EnginePeer(peer));
        ChainSynchronizer.Result byHeaders =
            new HeaderSynchronizer(localHeader).syncFrom(new EnginePeer(peer));

        assertEquals(byHeaders, byBlocks, "both paths reach the same verdict on the same fork");
        assertEquals(peer.tipHash(), localChain.tipHash());
        assertEquals(peer.tipHash(), localHeader.tipHash());
        assertEquals(peer.height(), localChain.height());
        assertEquals(peer.height(), localHeader.height());
    }

    /** The probe cap is a shared constant now — pin it so a refactor cannot drop the O(log height) bound. */
    @Test
    void theProbeCapStaysANetworkConstant() {
        assertEquals(64, AncestorLocator.MAX_ANCESTOR_PROBES,
            "the exponential probe cap is consensus-adjacent: a removed cap lets a hostile peer "
                + "tie up the sync thread for height×latency (audit M2/M6)");
    }
}
