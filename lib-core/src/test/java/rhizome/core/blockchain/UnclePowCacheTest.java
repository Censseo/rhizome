package rhizome.core.blockchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.block.UncleRef;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.SHA256Hash;

/**
 * The orphan-PoW verify-once cache (audit: uncle re-hash). The memory-hard hash of an orphan
 * header is deterministic per header, so a proven orphan must never be hashed twice — not by a
 * repeated registration, and not by {@code selectUncles} scanning the pool on every production
 * round under the engine lock. The functional uncle behavior is pinned by BlockUnclesTest; this
 * class pins the cache accounting.
 */
class UnclePowCacheTest {

    private NetworkParameters params;
    private ChainEngine engine;
    private AtomicLong clock;

    @BeforeEach
    void setUp() {
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3).build();
        clock = new AtomicLong(1_000_000L);
        engine = ChainEngine.boot(
                params,
                TestNodeStores.inMemory(),
                new LedgerSnapshot("t", 0, params.chainId()))
            .clock(clock::get)
            .build();
    }

    private BlockImpl mine(long height, SHA256Hash parent, int salt) {
        var b = (BlockImpl) BlockImpl.builder().id((int) height)
            .timestamp(clock.addAndGet(1000L + salt)).difficulty(engine.difficulty())
            .lastBlockHash(parent).build();
        b.addTransaction(Transaction.of(PublicAddress.random(),
            new TransactionAmount(params.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    private static UncleRef ref(Block orphan) {
        return new UncleRef(orphan.hash(), ((BlockImpl) orphan).difficulty(),
            ((TransactionImpl) orphan.transactions().get(0)).to());
    }

    @Test
    void aProvenOrphanIsNeverRehashed() {
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mine(2, engine.tipHash(), 7))); // height 2
        assertEquals(0, engine.verifiedOrphanPowCacheSizeForTest());

        // Registration verifies the orphan's PoW once and caches the verdict.
        BlockImpl orphan = mine(2, engine.blockAt(1).hash(), 500); // sibling of the tip
        engine.registerOrphan(orphan);
        assertEquals(1, engine.verifiedOrphanPowCacheSizeForTest());

        // Re-registration (gossip loops re-submit siblings) is a cache hit, not a re-hash.
        engine.registerOrphan(orphan);
        assertEquals(1, engine.verifiedOrphanPowCacheSizeForTest());

        // Every production round's pool scan reuses the cached verdict: repeated selectUncles
        // offers the orphan without growing the cache (previously a fresh Pufferfish2 per scan).
        for (int i = 0; i < 3; i++) {
            List<UncleRef> picked = engine.selectUncles();
            assertEquals(1, picked.size());
            assertEquals(orphan.hash(), picked.get(0).hash());
        }
        assertEquals(1, engine.verifiedOrphanPowCacheSizeForTest());

        // A second orphan is verified once on its own registration; both stay cached.
        BlockImpl orphan2 = mine(2, engine.blockAt(1).hash(), 900);
        engine.registerOrphan(orphan2);
        assertEquals(2, engine.verifiedOrphanPowCacheSizeForTest());

        // The uncle-validation path inside addBlock also consults the cache.
        List<UncleRef> picked = engine.selectUncles();
        assertEquals(2, picked.size());
        long h = engine.height() + 1;
        var nephew = (BlockImpl) BlockImpl.builder().id((int) h)
            .timestamp(clock.addAndGet(1000L)).difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash()).uncles(new java.util.ArrayList<>(picked)).build();
        nephew.addTransaction(Transaction.of(PublicAddress.random(),
            new TransactionAmount(params.miningReward(h))));
        var tree = new MerkleTree();
        tree.setItems(nephew.transactions());
        nephew.merkleRoot(tree.getRootHash());
        nephew.nonce(Miner.mineNonce(nephew.hash(), nephew.difficulty(), params.powAlgorithm()));
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(nephew));
        assertEquals(2, engine.verifiedOrphanPowCacheSizeForTest());
        // The referenced orphans are no longer offered (already credited) — no re-hash either way.
        assertTrue(engine.selectUncles().isEmpty());
        assertEquals(2, engine.verifiedOrphanPowCacheSizeForTest());
    }
}
