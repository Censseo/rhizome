package rhizome.core.blockchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockImpl;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PowCosts;
import rhizome.crypto.SHA256Hash;

/**
 * An orphan's body must be bound to the PoW header it arrived under. The block hash commits the
 * merkle root but neither the nonce nor the body, so "same hash" never implies "same block":
 * the orphan pool, the verify-once PoW cache and the uncle-miner lookup (read from the body's
 * coinbase) each had to be fixed locally.
 */
class OrphanBodyBindingTest {

    private NetworkParameters params;
    private ChainEngine engine;
    private AtomicLong clock;

    @BeforeEach
    void setUp() {
        params = params(NetworkParameters.testnet().toBuilder());
        engine = engine(params);
    }

    private static NetworkParameters params(NetworkParameters.NetworkParametersBuilder b) {
        return b.powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3).build();
    }

    private ChainEngine engine(NetworkParameters p) {
        clock = new AtomicLong(1_000_000L);
        return ChainEngine.boot(p, TestNodeStores.inMemory(), new LedgerSnapshot("t", 0, p.chainId()))
            .clock(clock::get).build();
    }

    private BlockImpl mine(long height, SHA256Hash parent, PublicAddress to) {
        var b = (BlockImpl) BlockImpl.builder().id((int) height)
            .timestamp(clock.addAndGet(1000L)).difficulty(engine.difficulty())
            .lastBlockHash(parent)
            .supply(SupplyStamp.next(engine, height, engine.difficulty())).build();
        b.addTransaction(Transaction.of(to, new TransactionAmount(params.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    /** Same header (hash AND nonce) as {@code honest}, body paying {@code thief}. */
    private static BlockImpl withCoinbaseTo(BlockImpl honest, PublicAddress thief) {
        var forged = (BlockImpl) BlockImpl.builder().id(honest.id()).timestamp(honest.timestamp())
            .difficulty(honest.difficulty()).lastBlockHash(honest.lastBlockHash())
            .merkleRoot(honest.merkleRoot()).nonce(honest.nonce()).supply(honest.supply()).build();
        forged.addTransaction(Transaction.of(thief, new TransactionAmount(1)));
        return forged;
    }

    private BlockImpl siblingOfTip() {
        assertEquals(ExecutionStatus.SUCCESS,
            engine.addBlock(mine(2, engine.tipHash(), PublicAddress.random())));
        return mine(2, engine.blockAt(1).hash(), PublicAddress.random());
    }

    /**
     * UNCLE-08 — an honest orphan's header and nonce are replayed over a body whose coinbase pays
     * the attacker: the body must be refused (merkle root mismatch) before the PoW verdict for the
     * real header can vouch for it, and the honest orphan must remain the pooled body.
     */
    @Test
    void anOrphanBodyThatDoesNotMatchItsHeaderMerkleRootIsNeverPooled() {
        BlockImpl honest = siblingOfTip();
        BlockImpl forged = withCoinbaseTo(honest, PublicAddress.random());
        assertEquals(honest.hash(), forged.hash(), "the hash does not commit the body");

        engine.registerOrphan(forged);
        assertNull(engine.orphanBlock(honest.hash()), "forged body was refused outright");
        assertEquals(0, engine.verifiedOrphanPowCacheSizeForTest(), "refused before any PoW hash");

        engine.registerOrphan(honest);
        engine.registerOrphan(forged); // and cannot displace the honest body afterwards
        assertEquals(honest.transactions().get(0), engine.orphanBlock(honest.hash()).transactions().get(0));
    }

    /**
     * UNCLE-09 — the same header resubmitted with a garbage nonce must neither ride the verify-once
     * cache (keyed by hash, which omits the nonce) nor overwrite the pooled body, or a bad-nonce
     * body is later persisted as the uncle body.
     */
    @Test
    void aGarbageNonceUnderACachedHeaderNeitherPassesTheCacheNorReplacesThePooledBody() {
        BlockImpl honest = siblingOfTip();
        engine.registerOrphan(honest);
        assertEquals(1, engine.verifiedOrphanPowCacheSizeForTest());

        var garbage = (BlockImpl) BlockImpl.builder().id(honest.id()).timestamp(honest.timestamp())
            .difficulty(honest.difficulty()).lastBlockHash(honest.lastBlockHash())
            .merkleRoot(honest.merkleRoot()).supply(honest.supply()).build();
        honest.transactions().forEach(garbage::addTransaction);
        byte[] raw = honest.nonce().toBytes();
        do {
            raw[0]++;
            garbage.nonce(SHA256Hash.of(raw));
        } while (garbage.verifyNonce(params.powAlgorithm()));

        engine.registerOrphan(garbage);
        assertEquals(honest.nonce(), engine.orphanBlock(honest.hash()).nonce(),
            "the pooled body keeps the PoW-proven nonce");

        // The assertion above is satisfied by EITHER defence alone (a cache hit is still refused by
        // put-if-absent, and put-if-absent is irrelevant to a garbage nonce the cache rejects), so
        // each is isolated below.

        // (1) Cache keyed by nonce: evict the honest body from the 256-slot pool while its cache entry
        // survives (1024 slots), then resubmit the garbage nonce. A hash-only cache would vouch for it
        // and pool it; the nonce-bound cache re-hashes it, finds the PoW invalid and refuses it.
        for (int i = 0; i < 256; i++) {
            engine.registerOrphan(mine(2, engine.blockAt(1).hash(), PublicAddress.random()));
        }
        assertNull(engine.orphanBlock(honest.hash()), "the honest body was churned out of the pool");
        engine.registerOrphan(garbage);
        assertNull(engine.orphanBlock(honest.hash()),
            "a garbage nonce must not ride the cached verdict for the honest header");

        // (2) Put-if-absent: a SECOND genuinely valid nonce for the same header passes the PoW check
        // legitimately, so only the pool's put-if-absent keeps the first-vetted body in place.
        BlockImpl first = siblingOfTip2();
        engine.registerOrphan(first);
        var second = (BlockImpl) BlockImpl.builder().id(first.id()).timestamp(first.timestamp())
            .difficulty(first.difficulty()).lastBlockHash(first.lastBlockHash())
            .merkleRoot(first.merkleRoot()).supply(first.supply()).build();
        first.transactions().forEach(second::addTransaction);
        byte[] raw2 = first.nonce().toBytes();
        do {
            raw2[1]++;
            second.nonce(SHA256Hash.of(raw2));
        } while (!second.verifyNonce(params.powAlgorithm()) || second.nonce().equals(first.nonce()));
        engine.registerOrphan(second);
        assertEquals(first.nonce(), engine.orphanBlock(first.hash()).nonce(),
            "a later valid-nonce resubmission must not replace the pooled body");
    }

    /** A further sibling of the tip (the tip block itself already exists from {@link #siblingOfTip}). */
    private BlockImpl siblingOfTip2() {
        return mine(2, engine.blockAt(1).hash(), PublicAddress.random());
    }

    /**
     * UNCLE-10 — an orphan body over maxUncleBodyBytes is refused before hashing: uncle bodies are
     * retained (and persisted) without full validation, so they get a cap far below a block's.
     */
    @Test
    void anOrphanBodyOverTheUncleBodyCapIsRefusedBeforeAnyHashing() {
        NetworkParameters tight = params(NetworkParameters.testnet().toBuilder().maxUncleBodyBytes(1));
        assertTrue(tight.maxUncleBodyBytes() < params.maxUncleBodyBytes());
        engine = engine(tight);
        params = tight;
        BlockImpl orphan = siblingOfTip();
        engine.registerOrphan(orphan);
        assertNull(engine.orphanBlock(orphan.hash()));
        assertEquals(0, engine.verifiedOrphanPowCacheSizeForTest());
        assertEquals(64 * 1024, NetworkParameters.testnet().maxUncleBodyBytes());
        assertEquals(64 * 1024, NetworkParameters.cleanMainnet().maxUncleBodyBytes());
        assertEquals(64 * 1024, NetworkParameters.devnet().maxUncleBodyBytes());
        assertEquals(64 * 1024, NetworkParameters.staging().maxUncleBodyBytes());
    }

    /**
     * POW-09 — the built-in miner abandons a candidate once the tip moves instead of grinding it
     * to completion: an unsolvable difficulty returns empty as soon as the stale check holds, and
     * the check is polled again between attempts (not just once up front).
     */
    @Test
    void theMinerAbandonsACandidateWhenItsStaleCheckFires() {
        AtomicInteger polls = new AtomicInteger();
        var result = Miner.mineNonce(SHA256Hash.empty(), 255, PowAlgorithm.SHA256, PowCosts.DEFAULT,
            () -> polls.incrementAndGet() > 3);
        assertTrue(result.isEmpty());
        assertEquals(4, polls.get(), "polled repeatedly until it fired, then stopped");
        assertTrue(Miner.mineNonce(SHA256Hash.empty(), 1, PowAlgorithm.SHA256, PowCosts.DEFAULT,
            () -> false).isPresent());
    }
}
