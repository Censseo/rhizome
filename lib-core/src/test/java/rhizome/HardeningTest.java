package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ChainStore;
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

/**
 * Hardening: static checkpoints pin history, the finality window refuses deep
 * reorgs, and a peer that merely claims work (without proving it) can no longer
 * cause any local state mutation.
 */
class HardeningTest {

    private static final NetworkParameters PARAMS = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).build();
    private static final long NOW = 100_000_000_000L;

    private static ChainEngine engine(NetworkParameters params, ChainStore store) {
        return ChainEngine.init(params, new InMemoryLedger(), store,
            new LedgerSnapshot("t", 0, params.chainId()), null, () -> NOW);
    }

    private static Block mineNext(ChainEngine e, AtomicLong clock) {
        long h = e.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) h)
            .timestamp(clock.addAndGet(90_000)).difficulty(e.difficulty())
            .lastBlockHash(e.tipHash()).build();
        b.addTransaction(Transaction.of(PublicAddress.random(),
            new TransactionAmount(PARAMS.miningReward(h))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), PARAMS.powAlgorithm()));
        return b;
    }

    // ---- checkpoints ----

    @Test
    void checkpointPinsHistory() {
        // Mine a reference chain to learn the canonical hash at height 2.
        ChainEngine reference = engine(PARAMS, new InMemoryChainStore());
        AtomicLong clock = new AtomicLong(1000);
        Block block2 = mineNext(reference, clock);
        assertEquals(ExecutionStatus.SUCCESS, reference.addBlock(block2));

        // A node with the published checkpoint accepts the canonical block...
        NetworkParameters pinned = PARAMS.toBuilder()
            .checkpoints(Map.of(2L, block2.hash()))
            .build();
        ChainEngine good = engine(pinned, new InMemoryChainStore());
        assertEquals(ExecutionStatus.SUCCESS, good.addBlock(block2));

        // ...and rejects any competing block at that height, even a valid one.
        NetworkParameters wrongPin = PARAMS.toBuilder()
            .checkpoints(Map.of(2L, SHA256Hash.random()))
            .build();
        ChainEngine strict = engine(wrongPin, new InMemoryChainStore());
        assertEquals(ExecutionStatus.HEADER_HASH_INVALID, strict.addBlock(block2));
    }

    // ---- finality window ----

    @Test
    void reorgDeeperThanFinalityWindowIsRefused() {
        NetworkParameters shallow = PARAMS.toBuilder().maxReorgDepth(1).build();

        ChainEngine local = engine(shallow, new InMemoryChainStore());
        AtomicLong clockA = new AtomicLong(1000);
        local.addBlock(mineNext(local, clockA));
        local.addBlock(mineNext(local, clockA)); // local branch depth 2 above genesis

        ChainEngine peer = engine(shallow, new InMemoryChainStore());
        AtomicLong clockB = new AtomicLong(5000);
        for (int i = 0; i < 4; i++) {
            peer.addBlock(mineNext(peer, clockB)); // heavier, diverges at genesis -> depth 2 reorg
        }

        SHA256Hash tipBefore = local.tipHash();
        Result result = new ChainSynchronizer(local).syncFrom(new EnginePeer(peer));

        assertEquals(Result.REORG_TOO_DEEP, result);
        assertEquals(tipBefore, local.tipHash()); // untouched
    }

    // ---- no free rollbacks ----

    /** ChainStore wrapper counting pops, to prove a lying peer causes none. */
    private static final class PopCountingStore implements ChainStore {
        final InMemoryChainStore delegate = new InMemoryChainStore();
        final AtomicInteger pops = new AtomicInteger();
        public long height() { return delegate.height(); }
        public Block blockAt(long height) { return delegate.blockAt(height); }
        public void append(Block block) { delegate.append(block); }
        public void pop() { pops.incrementAndGet(); delegate.pop(); }
        public boolean hasTransaction(SHA256Hash h) { return delegate.hasTransaction(h); }
    }

    @Test
    void claimedButUnprovenWorkCausesZeroStateMutation() {
        PopCountingStore store = new PopCountingStore();
        ChainEngine local = engine(PARAMS, store);
        AtomicLong clock = new AtomicLong(1000);
        local.addBlock(mineNext(local, clock));
        local.addBlock(mineNext(local, clock));
        BigInteger workBefore = local.totalWork();

        // Claims huge work and a divergent chain, but serves blocks with no valid PoW.
        PeerSource liar = new PeerSource() {
            public long height() { return 50; }
            public BigInteger totalWork() { return workBefore.multiply(BigInteger.valueOf(1000)); }
            public SHA256Hash blockHash(long h) {
                return h == 1 ? local.blockAt(1).hash() : SHA256Hash.random();
            }
            public List<Block> blocks(long start, long end) {
                List<Block> fakes = new ArrayList<>();
                for (long h = start; h <= end; h++) {
                    fakes.add(BlockImpl.builder().id((int) h).timestamp(9_000_000)
                        .difficulty(30) // claims high difficulty, pays none
                        .lastBlockHash(SHA256Hash.random())
                        .merkleRoot(SHA256Hash.random())
                        .nonce(SHA256Hash.random()).build());
                }
                return fakes;
            }
        };

        Result result = new ChainSynchronizer(local).syncFrom(liar);

        assertEquals(Result.PEER_INVALID, result);
        assertEquals(0, store.pops.get(), "a lying peer must never trigger a rollback");
        assertEquals(workBefore, local.totalWork());
    }

    /** PeerSource over another engine. */
    private record EnginePeer(ChainEngine engine) implements PeerSource {
        public long height() { return engine.height(); }
        public BigInteger totalWork() { return engine.totalWork(); }
        public SHA256Hash blockHash(long height) { return engine.blockAt(height).hash(); }
        public List<Block> blocks(long start, long end) {
            List<Block> out = new ArrayList<>();
            for (long h = start; h <= end; h++) {
                out.add(engine.blockAt(h));
            }
            return out;
        }
    }
}
