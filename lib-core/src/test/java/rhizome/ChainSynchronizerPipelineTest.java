package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ChainSynchronizer;
import rhizome.core.blockchain.ChainSynchronizer.Result;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.LocalSaturationException;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.PeerSource;
import rhizome.core.blockchain.PeerUnavailableException;
import rhizome.core.common.Constants;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.SHA256Hash;

/**
 * Locks the full-block fallback's fetch/apply pipeline: {@code ChainSynchronizer.applyRange}
 * downloads window K+1 while it applies window K, so the download must run OFF the applying
 * thread while the applied sequence and every failure verdict stay exactly what the serial loop
 * produced. The headers-first path has the same pipeline (and its own coverage); this is the
 * fallback a peer without {@code /headers} drops us onto.
 */
class ChainSynchronizerPipelineTest {

    private static final NetworkParameters PARAMS = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).minDifficulty(4).build();
    private static final long NOW = 100_000_000_000L;
    /** Enough to span three fetch windows, so a mid-range window can misbehave. */
    private static final int CHAIN = 2 * Constants.BLOCKS_PER_FETCH + 10;

    @Test
    void downloadsOffTheApplyingThreadSoFetchAndApplyOverlap() {
        ChainEngine peer = minedChain();
        var recorder = new ThreadRecordingPeer(peer);
        ChainEngine local = newEngine();

        assertEquals(Result.EXTENDED, new ChainSynchronizer(local).syncFrom(recorder));

        // The whole point of the pipeline: no window is downloaded on the thread that applies.
        // A serial loop would fetch on the caller's thread and could never overlap the two.
        assertFalse(recorder.fetchThreads.isEmpty(), "the range was fetched at all");
        assertFalse(recorder.fetchThreads.contains(Thread.currentThread().getName()),
            "blocks() must not run on the applying thread, else nothing overlaps: "
                + recorder.fetchThreads);
        for (String name : recorder.fetchThreads) {
            assertNotEquals(Thread.currentThread().getName(), name);
        }
    }

    @Test
    void appliesTheSameChainTheSerialLoopWould() {
        ChainEngine peer = minedChain();
        ChainEngine local = newEngine();

        assertEquals(Result.EXTENDED, new ChainSynchronizer(local).syncFrom(new EnginePeer(peer)));

        // Order is what consensus depends on: same height, same tip, same accumulated work.
        assertEquals(peer.height(), local.height());
        assertEquals(peer.tipHash(), local.tipHash());
        assertEquals(peer.totalWork(), local.totalWork());
        for (long h = 1; h <= peer.height(); h++) {
            assertEquals(peer.blockAt(h).hash(), local.blockAt(h).hash(), "block " + h);
        }
    }

    @Test
    void aTransportFailureMidRangeStaysUnavailableAndNeverReadsAsInvalid() {
        // The pipeline moves the fetch into a Future, so its exception arrives wrapped in an
        // ExecutionException. If that were folded into a boolean, a peer that merely 503'd
        // (e.g. itself mid-reorg) would be judged PEER_INVALID and earn ban score — the exact
        // regression testnet campaign S5 fixed. The cause must reach the caller unwrapped.
        ChainEngine peer = minedChain();
        var failing = new FailingWindowPeer(peer, 2, new PeerUnavailableException("peer 503", null));
        ChainEngine local = newEngine();

        assertThrows(PeerUnavailableException.class,
            () -> new ChainSynchronizer(local).syncFrom(failing));
        assertTrue(local.height() > 1, "the windows before the failure were still applied");
    }

    @Test
    void localBackpressureMidRangeIsNotAPeerFault() {
        // Same unwrapping requirement for the other typed transport signal: LocalSaturation is
        // OUR bound, so syncFrom maps it to NO_CHANGE, never a ban.
        ChainEngine peer = minedChain();
        var saturated = new FailingWindowPeer(peer, 2, new LocalSaturationException("local cap", null));
        ChainEngine local = newEngine();

        assertEquals(Result.NO_CHANGE, new ChainSynchronizer(local).syncFrom(saturated));
    }

    @Test
    void aMalformedWindowStillReadsAsInvalid() {
        // Anything that is not a typed transport signal remains the peer's fault.
        ChainEngine peer = minedChain();
        var garbage = new FailingWindowPeer(peer, 2, new IllegalStateException("garbage body"));
        ChainEngine local = newEngine();

        assertThrows(IllegalStateException.class,
            () -> new ChainSynchronizer(local).syncFrom(garbage));
    }

    @Test
    void aRejectedBlockStopsTheApplyAndDoesNotConsumeLaterWindows() {
        // An early return must abandon the in-flight prefetch rather than apply past the
        // rejection: the chain has to stop exactly at the bad block.
        ChainEngine peer = minedChain();
        var tampering = new TamperedBlockPeer(peer, Constants.BLOCKS_PER_FETCH + 5);
        ChainEngine local = newEngine();

        assertEquals(Result.PEER_INVALID, new ChainSynchronizer(local).syncFrom(tampering));
        assertEquals(Constants.BLOCKS_PER_FETCH + 4, local.height(),
            "applied up to the block before the tampered one, and no further");
    }

    // --- fixtures -------------------------------------------------------------------------

    private static ChainEngine newEngine() {
        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, PARAMS.chainId());
        return ChainEngine.init(PARAMS, new InMemoryLedger(), new InMemoryChainStore(),
            snapshot, null, () -> NOW);
    }

    /**
     * A nonce that provably FAILS this block's proof of work.
     *
     * <p>The obvious fixture — a fixed all-zero nonce — is only probably invalid. At the difficulty
     * these tests run (4 bits) any given hash satisfies the target about one time in sixteen, and
     * the header differs every run because the miner address is random, so a "tampered" block was
     * silently valid in ~6% of runs and the synchroniser then returned EXTENDED instead of
     * PEER_INVALID. Search for a nonce that actually fails instead of assuming one does.
     */
    private static SHA256Hash failingNonce(BlockImpl block) {
        for (int candidate = 0; candidate < 1000; candidate++) {
            byte[] raw = new byte[SHA256Hash.SIZE];
            raw[0] = (byte) candidate;
            raw[1] = (byte) (candidate >>> 8);
            block.nonce(SHA256Hash.of(raw));
            if (!block.verifyNonce(PARAMS.powAlgorithm())) {
                return SHA256Hash.of(raw);
            }
        }
        throw new IllegalStateException("no failing nonce found — is the difficulty zero?");
    }

    private static ChainEngine minedChain() {
        ChainEngine engine = newEngine();
        AtomicLong clock = new AtomicLong(0);
        PublicAddress miner = PublicAddress.random();
        for (int i = 0; i < CHAIN; i++) {
            long height = engine.height() + 1;
            var b = (BlockImpl) BlockImpl.builder().id((int) height)
                .timestamp(clock.addAndGet(90_000)).difficulty(engine.difficulty())
                .lastBlockHash(engine.tipHash()).build();
            b.addTransaction(Transaction.of(miner, new TransactionAmount(PARAMS.miningReward(height))));
            var tree = new MerkleTree();
            tree.setItems(b.transactions());
            b.merkleRoot(tree.getRootHash());
            b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), PARAMS.powAlgorithm()));
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(b));
        }
        return engine;
    }

    /** A PeerSource backed by another engine. The engine is internally locked, so this is safe
     *  to call from the synchroniser's fetch thread and its applying thread at once. */
    private static class EnginePeer implements PeerSource {
        final ChainEngine engine;

        EnginePeer(ChainEngine engine) {
            this.engine = engine;
        }

        @Override public long height() {
            return engine.height();
        }

        @Override public BigInteger totalWork() {
            return engine.totalWork();
        }

        @Override public SHA256Hash blockHash(long height) {
            return engine.blockAt(height).hash();
        }

        @Override public List<Block> blocks(long start, long end) {
            List<Block> out = new ArrayList<>();
            for (long h = start; h <= end; h++) {
                out.add(engine.blockAt(h));
            }
            return out;
        }
    }

    /** Records which threads served each blocks() call. */
    private static final class ThreadRecordingPeer extends EnginePeer {
        final Set<String> fetchThreads = ConcurrentHashMap.newKeySet();

        ThreadRecordingPeer(ChainEngine engine) {
            super(engine);
        }

        @Override public List<Block> blocks(long start, long end) {
            fetchThreads.add(Thread.currentThread().getName());
            return super.blocks(start, end);
        }
    }

    /** Serves windows normally until the {@code failOn}-th blocks() call, which throws. */
    private static final class FailingWindowPeer extends EnginePeer {
        private final int failOn;
        private final RuntimeException failure;
        private final AtomicLong calls = new AtomicLong();

        FailingWindowPeer(ChainEngine engine, int failOn, RuntimeException failure) {
            super(engine);
            this.failOn = failOn;
            this.failure = failure;
        }

        @Override public List<Block> blocks(long start, long end) {
            if (calls.incrementAndGet() == failOn) {
                throw failure;
            }
            return super.blocks(start, end);
        }
    }

    /** Serves a chain in which one block's body no longer matches its proof of work. */
    private static final class TamperedBlockPeer extends EnginePeer {
        private final long tamperedHeight;

        TamperedBlockPeer(ChainEngine engine, long tamperedHeight) {
            super(engine);
            this.tamperedHeight = tamperedHeight;
        }

        @Override public List<Block> blocks(long start, long end) {
            List<Block> out = new ArrayList<>(super.blocks(start, end));
            for (int i = 0; i < out.size(); i++) {
                var block = (BlockImpl) out.get(i);
                if (block.id() == tamperedHeight) {
                    // Same header, different nonce: the PoW no longer verifies.
                    var broken = (BlockImpl) BlockImpl.builder().id(block.id())
                        .timestamp(block.timestamp()).difficulty(block.difficulty())
                        .lastBlockHash(block.lastBlockHash()).build();
                    block.transactions().forEach(broken::addTransaction);
                    broken.merkleRoot(block.merkleRoot());
                    broken.nonce(failingNonce(broken));
                    out.set(i, broken);
                }
            }
            return out;
        }
    }
}
