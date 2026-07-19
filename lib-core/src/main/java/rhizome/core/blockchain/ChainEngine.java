package rhizome.core.blockchain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.block.UncleRef;
import rhizome.core.crypto.SHA256Hash;
import rhizome.core.ledger.Ledger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionImpl;

import static rhizome.core.mempool.ExecutionStatus.*;

/**
 * The chain engine: accepts blocks, maintains the ledger, account nonces,
 * cumulative work and difficulty, and can pop blocks for reorgs.
 *
 * <p>Validation order for {@link #addBlock} (cheap and structural first, the
 * expensive proof-of-work last so invalid blocks cannot burn CPU — a Pandanite
 * DoS lesson):
 * <ol>
 *   <li>id continuity, transaction count</li>
 *   <li>lastBlockHash chains to the tip (checked from block 2 — Pandanite
 *       skipped this for its first ~8000 blocks; issue #2's fork disaster)</li>
 *   <li>timestamp above median-time-past, not too far past the local clock</li>
 *   <li>difficulty equals the value recomputed from chain history (never a
 *       stored field that can go stale after a pop)</li>
 *   <li>merkle root matches the transactions</li>
 *   <li>account nonces strictly sequential per sender</li>
 *   <li>proof-of-work under the network's algorithm</li>
 *   <li>{@link Executor} applies transactions transactionally</li>
 * </ol>
 *
 * <p>All public methods are serialised on a single lock: one writer at a time,
 * and reads see consistent state (Pandanite's unlocked getters produced torn
 * reads of its Bigint total work).
 */
public final class ChainEngine implements Blockchain, rhizome.core.mempool.AccountView {

    private final NetworkParameters params;
    private final Ledger ledger;
    private final ChainStore store;
    private final LongSupplier nowMillis;
    private final SignatureVerifier verifier;
    private final ContractProcessor contractProcessor;
    private final OrphanPool orphans = new OrphanPool(256);
    private final ReentrantLock lock = new ReentrantLock();

    /** Next expected account nonce per sender; rebuilt on init, updated on add/pop. */
    private final Map<rhizome.core.ledger.PublicAddress, Long> nextNonce = new HashMap<>();

    private BigInteger totalWork = BigInteger.ZERO;
    private int currentDifficulty;

    /** Uncle work credited per block height, so a pop subtracts exactly what an add added. */
    private final Map<Long, BigInteger> uncleWorkByHeight = new HashMap<>();

    private ChainEngine(NetworkParameters params, Ledger ledger, ChainStore store,
                        LongSupplier nowMillis, SignatureVerifier verifier,
                        ContractProcessor contractProcessor) {
        this.params = params;
        this.ledger = ledger;
        this.store = store;
        this.nowMillis = nowMillis;
        this.verifier = verifier;
        this.contractProcessor = contractProcessor;
    }

    /**
     * Boots a chain: on an empty store, verifies the snapshot against
     * {@code expectedGenesisHash} (null for a brand-new network), seeds the
     * ledger and appends genesis. On a non-empty store, verifies the stored
     * genesis matches and rebuilds derived state (difficulty, work, nonces).
     */
    public static ChainEngine init(NetworkParameters params, Ledger ledger, ChainStore store,
                                   LedgerSnapshot snapshot, SHA256Hash expectedGenesisHash,
                                   LongSupplier nowMillis) {
        return init(params, ledger, store, snapshot, expectedGenesisHash, nowMillis, null);
    }

    /** As {@link #init}, with a shared {@link SignatureVerifier} for fast parallel/cached signature checks. */
    public static ChainEngine init(NetworkParameters params, Ledger ledger, ChainStore store,
                                   LedgerSnapshot snapshot, SHA256Hash expectedGenesisHash,
                                   LongSupplier nowMillis, SignatureVerifier verifier) {
        return init(params, ledger, store, snapshot, expectedGenesisHash, nowMillis, verifier, null);
    }

    /** As {@link #init}, with a {@link ContractProcessor} enabling contract transactions. */
    public static ChainEngine init(NetworkParameters params, Ledger ledger, ChainStore store,
                                   LedgerSnapshot snapshot, SHA256Hash expectedGenesisHash,
                                   LongSupplier nowMillis, SignatureVerifier verifier,
                                   ContractProcessor contractProcessor) {
        ChainEngine engine = new ChainEngine(params, ledger, store, nowMillis, verifier, contractProcessor);
        if (store.height() == 0) {
            Block genesis = GenesisBlock.initChain(ledger, params, snapshot, expectedGenesisHash);
            store.append(genesis);
        } else if (!GenesisBlock.matches(store.blockAt(GenesisBlock.GENESIS_ID), params, snapshot)) {
            throw new IllegalStateException("Stored genesis does not match network parameters and snapshot");
        }
        engine.rebuildDerivedState();
        return engine;
    }

    public ExecutionStatus addBlock(Block block) {
        lock.lock();
        try {
            var b = (BlockImpl) block;
            long height = store.height();

            if (b.id() != height + 1) {
                return INVALID_BLOCK_ID;
            }
            if (block.transactions().isEmpty()
                || block.transactions().size() > params.maxTransactionsPerBlock()) {
                return INVALID_TRANSACTION_COUNT; // must at least carry a coinbase
            }
            // Bound the block's serialized size (cheap, before any expensive work) so a
            // block laden with contract payloads cannot be a download/storage DoS.
            if (serializedSize(block) > params.maxBlockSizeBytes()) {
                return BLOCK_TOO_LARGE;
            }
            // Structural uncle checks (GHOST): bounded count, distinct, and none is the
            // parent itself. Ancestry/PoW validation and fork-choice weighting land with
            // the orphan pool; for now uncles are carried and committed but earn no work.
            BigInteger uncleWork = validateUncles(b);
            if (uncleWork == null) {
                return INVALID_UNCLES;
            }
            // Static checkpoint: at a pinned height, only the published hash passes.
            SHA256Hash checkpoint = params.checkpoints().get(height + 1);
            if (checkpoint != null && !block.hash().equals(checkpoint)) {
                return HEADER_HASH_INVALID;
            }
            Block parent = store.tip();
            if (!b.lastBlockHash().equals(parent.hash())) {
                return INVALID_LASTBLOCK_HASH;
            }
            if (b.timestamp() <= medianTimePast()) {
                return BLOCK_TIMESTAMP_TOO_OLD;
            }
            // Consensus rate limit: a block must be at least minBlockTimeSec after its
            // parent. Enforced by every node, so it caps block production for everyone
            // (majority miner included), unlike the producer's local pacing.
            if (b.timestamp() < ((BlockImpl) parent).timestamp() + params.minBlockTimeSec() * 1000L) {
                return BLOCK_TIMESTAMP_TOO_CLOSE;
            }
            if (b.timestamp() > nowMillis.getAsLong() + params.maxFutureBlockTimeSec() * 1000L) {
                return BLOCK_TIMESTAMP_IN_FUTURE;
            }
            if (b.difficulty() != currentDifficulty) {
                return INVALID_DIFFICULTY;
            }
            if (!computeMerkleRoot(block).equals(b.merkleRoot())) {
                return INVALID_MERKLE_ROOT;
            }
            ExecutionStatus nonceCheck = checkAccountNonces(block);
            if (nonceCheck != SUCCESS) {
                return nonceCheck;
            }
            if (!block.verifyNonce(params.powAlgorithm())) {
                return INVALID_NONCE;
            }

            ExecutionStatus status = Executor.executeBlock(
                block, ledger, store::hasTransaction, params, verifier, contractProcessor);
            if (status != SUCCESS) {
                return status;
            }

            store.append(block);
            commitAccountNonces(block);
            totalWork = totalWork.add(BigInteger.TWO.pow(b.difficulty())).add(uncleWork);
            uncleWorkByHeight.put((long) b.id(), uncleWork);
            currentDifficulty = computeDifficultyFromChain();
            return SUCCESS;
        } finally {
            lock.unlock();
        }
    }

    /** Removes the tip block (never genesis), reverting ledger and nonces. */
    public void popBlock() {
        lock.lock();
        try {
            long height = store.height();
            if (height <= GenesisBlock.GENESIS_ID) {
                throw new IllegalStateException("Cannot pop genesis");
            }
            Block tip = store.tip();
            Executor.rollbackBlock(tip, ledger, contractProcessor, height);
            if (contractProcessor != null) {
                contractProcessor.revertBlock(height); // undo this block's contract-state changes
            }
            store.pop();
            revertAccountNonces(tip);
            BigInteger uncleWork = uncleWorkByHeight.remove(height);
            totalWork = totalWork.subtract(BigInteger.TWO.pow(((BlockImpl) tip).difficulty()));
            if (uncleWork != null) {
                totalWork = totalWork.subtract(uncleWork);
            }
            currentDifficulty = computeDifficultyFromChain();
        } finally {
            lock.unlock();
        }
    }

    public long height() {
        lock.lock();
        try {
            return store.height();
        } finally {
            lock.unlock();
        }
    }

    public SHA256Hash tipHash() {
        lock.lock();
        try {
            return store.tip().hash();
        } finally {
            lock.unlock();
        }
    }

    /** Block at the given height (1-based). Throws if out of range. */
    public Block blockAt(long height) {
        lock.lock();
        try {
            return store.blockAt(height);
        } finally {
            lock.unlock();
        }
    }

    /**
     * A timestamp acceptable for the next block: the caller's {@code preferred}
     * time, bumped above the median-time-past floor if a fast cadence would
     * otherwise put it too early. Used by the block producer.
     */
    public long nextBlockTimestamp(long preferred) {
        lock.lock();
        try {
            long tipFloor = ((BlockImpl) store.tip()).timestamp() + params.minBlockTimeSec() * 1000L;
            return Math.max(Math.max(preferred, medianTimePast() + 1), tipFloor);
        } finally {
            lock.unlock();
        }
    }

    public int difficulty() {
        lock.lock();
        try {
            return currentDifficulty;
        } finally {
            lock.unlock();
        }
    }

    public BigInteger totalWork() {
        lock.lock();
        try {
            return totalWork;
        } finally {
            lock.unlock();
        }
    }

    /** Next expected account nonce for a sender (0 for a fresh account). */
    public long nextNonce(rhizome.core.ledger.PublicAddress sender) {
        lock.lock();
        try {
            return nextNonce.getOrDefault(sender, 0L);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long confirmedNextNonce(rhizome.core.ledger.PublicAddress sender) {
        return nextNonce(sender);
    }

    @Override
    public long confirmedBalance(rhizome.core.ledger.PublicAddress sender) {
        lock.lock();
        try {
            return ledger.hasWallet(sender) ? ledger.getWalletValue(sender).amount() : 0L;
        } finally {
            lock.unlock();
        }
    }

    public NetworkParameters params() {
        return params;
    }

    // ---- derived state ----

    private void rebuildDerivedState() {
        totalWork = BigInteger.ZERO;
        nextNonce.clear();
        uncleWorkByHeight.clear();
        long height = store.height();
        for (long h = GenesisBlock.GENESIS_ID + 1; h <= height; h++) {
            Block block = store.blockAt(h);
            commitAccountNonces(block);
            // Uncle work is recomputed from the committed uncle difficulties, so the
            // cumulative weight is restored exactly even with an empty orphan pool.
            BigInteger uncleWork = uncleWorkOf(block);
            uncleWorkByHeight.put(h, uncleWork);
            totalWork = totalWork.add(BigInteger.TWO.pow(((BlockImpl) block).difficulty())).add(uncleWork);
        }
        currentDifficulty = computeDifficultyFromChain();
    }

    /** Sum of 2^difficulty over a block's referenced uncles (from committed difficulties). */
    private static BigInteger uncleWorkOf(Block block) {
        BigInteger work = BigInteger.ZERO;
        for (rhizome.core.block.UncleRef uncle : block.uncles()) {
            work = work.add(BigInteger.TWO.pow(uncle.difficulty()));
        }
        return work;
    }

    /**
     * Recomputes the current difficulty purely from stored block timestamps:
     * genesis difficulty stepped through every completed retarget window. Being
     * derived (never cached across mutations without recompute) it cannot go
     * stale after a pop — the flaw behind Pandanite's hardcoded 536100–536200
     * difficulty exception.
     */
    private int computeDifficultyFromChain() {
        int lookback = params.difficultyLookback();
        int difficulty = params.genesisDifficulty();
        long height = store.height();
        for (long boundary = lookback; boundary <= height; boundary += lookback) {
            long windowStart = boundary - lookback + 1;
            long observedMs = ((BlockImpl) store.blockAt(boundary)).timestamp()
                - ((BlockImpl) store.blockAt(windowStart)).timestamp();
            difficulty = DifficultyAdjustment.nextDifficulty(
                params, difficulty, lookback - 1L, observedMs / 1000);
        }
        return difficulty;
    }

    private long medianTimePast() {
        long height = store.height();
        int window = (int) Math.min(params.medianTimeWindow(), height);
        List<Long> timestamps = new ArrayList<>(window);
        for (long h = height - window + 1; h <= height; h++) {
            timestamps.add(((BlockImpl) store.blockAt(h)).timestamp());
        }
        timestamps.sort(Long::compare);
        return timestamps.get(timestamps.size() / 2);
    }

    // ---- account nonces ----

    private ExecutionStatus checkAccountNonces(Block block) {
        Map<rhizome.core.ledger.PublicAddress, Long> expected = new HashMap<>();
        for (Transaction t : block.transactions()) {
            var tx = (TransactionImpl) t;
            if (tx.isTransactionFee()) {
                continue;
            }
            long want = expected.computeIfAbsent(tx.from(), a -> nextNonce.getOrDefault(a, 0L));
            if (tx.nonce() != want) {
                return INVALID_TRANSACTION_NONCE;
            }
            expected.put(tx.from(), want + 1);
        }
        return SUCCESS;
    }

    private void commitAccountNonces(Block block) {
        for (Transaction t : block.transactions()) {
            var tx = (TransactionImpl) t;
            if (!tx.isTransactionFee()) {
                nextNonce.merge(tx.from(), tx.nonce() + 1, Math::max);
            }
        }
    }

    private void revertAccountNonces(Block block) {
        Map<rhizome.core.ledger.PublicAddress, Long> lowest = new HashMap<>();
        for (Transaction t : block.transactions()) {
            var tx = (TransactionImpl) t;
            if (!tx.isTransactionFee()) {
                lowest.merge(tx.from(), tx.nonce(), Math::min);
            }
        }
        lowest.forEach((sender, nonce) -> {
            if (nonce == 0) {
                nextNonce.remove(sender);
            } else {
                nextNonce.put(sender, nonce);
            }
        });
    }

    private static SHA256Hash computeMerkleRoot(Block block) {
        var tree = new MerkleTree();
        tree.setItems(block.transactions());
        return tree.getRootHash();
    }

    /** Serialized byte size of the block (header + variable-length transactions + uncles). */
    private static long serializedSize(Block block) {
        long size = rhizome.core.block.dto.BlockDto.BUFFER_SIZE + Integer.BYTES;
        for (Transaction t : block.transactions()) {
            size += t.serialize().getSize();
        }
        size += (long) block.uncles().size() * (SHA256Hash.SIZE + Integer.BYTES);
        return size;
    }

    /**
     * Remembers a valid off-chain block so a later block may reference it as an uncle.
     * Only blocks with valid proof of work are retained (no free pool spam).
     */
    public void registerOrphan(Block block) {
        lock.lock();
        try {
            if (block.verifyNonce(params.powAlgorithm())) {
                orphans.put(block);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Full uncle validation (GHOST): bounded count and distinct; each uncle must be a
     * known orphan with valid PoW, recent (its id strictly below this block and within
     * {@code uncleMaxDepth}), forked from a main-chain block (its parent is on our
     * chain), not itself the canonical block at its height, and not already referenced
     * by a recent block. Returns the summed uncle work (2^difficulty over the
     * referenced uncles) to fold into the chain weight, or {@code null} if any
     * check fails.
     */
    private BigInteger validateUncles(BlockImpl block) {
        List<UncleRef> uncles = block.uncles();
        if (uncles.isEmpty()) {
            return BigInteger.ZERO;
        }
        if (uncles.size() > params.maxUnclesPerBlock()) {
            return null;
        }
        int h = block.id();
        int depth = params.uncleMaxDepth();
        long tipHeight = store.height();
        UncleContext ctx = uncleContext(h, depth, tipHeight);

        BigInteger uncleWork = BigInteger.ZERO;
        java.util.Set<SHA256Hash> seen = new java.util.HashSet<>();
        for (UncleRef ref : uncles) {
            SHA256Hash u = ref.hash();
            if (!seen.add(u)) {
                return null; // distinct
            }
            Block uncle = orphans.get(u);
            if (uncle == null) {
                return null; // unknown orphan
            }
            if (((BlockImpl) uncle).difficulty() != ref.difficulty()) {
                return null; // committed difficulty must match the real orphan (no work inflation)
            }
            if (!uncleEligible(uncle, h, depth, tipHeight, ctx)) {
                return null;
            }
            uncleWork = uncleWork.add(BigInteger.TWO.pow(ref.difficulty()));
        }
        return uncleWork;
    }

    /**
     * The uncle references a block at height {@code height} would include when
     * produced now: eligible orphans from the pool, up to {@code maxUnclesPerBlock},
     * each committing the orphan's real difficulty. Empty when nothing qualifies.
     */
    public List<UncleRef> selectUncles() {
        lock.lock();
        try {
            int h = (int) (store.height() + 1);
            int depth = params.uncleMaxDepth();
            long tipHeight = store.height();
            UncleContext ctx = uncleContext(h, depth, tipHeight);
            List<UncleRef> out = new ArrayList<>();
            for (Block orphan : orphans.snapshot()) {
                if (out.size() >= params.maxUnclesPerBlock()) {
                    break;
                }
                if (uncleEligible(orphan, h, depth, tipHeight, ctx)) {
                    out.add(new UncleRef(orphan.hash(), ((BlockImpl) orphan).difficulty()));
                }
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    /** Recent main-chain hashes an uncle may fork from, and uncle hashes already referenced. */
    private UncleContext uncleContext(int h, int depth, long tipHeight) {
        java.util.Set<SHA256Hash> recentChain = new java.util.HashSet<>();
        java.util.Set<SHA256Hash> alreadyReferenced = new java.util.HashSet<>();
        for (long ancestor = Math.max(GenesisBlock.GENESIS_ID, h - depth - 1L); ancestor <= tipHeight; ancestor++) {
            Block onChain = store.blockAt(ancestor);
            recentChain.add(onChain.hash());
            if (ancestor >= h - depth) {
                for (UncleRef ref : onChain.uncles()) {
                    alreadyReferenced.add(ref.hash());
                }
            }
        }
        return new UncleContext(recentChain, alreadyReferenced);
    }

    /** Whether {@code uncle} is a valid uncle for a block at height {@code h}. */
    private boolean uncleEligible(Block uncle, int h, int depth, long tipHeight, UncleContext ctx) {
        if (!uncle.verifyNonce(params.powAlgorithm())) {
            return false; // bad PoW
        }
        int uid = uncle.id();
        if (uid >= h || uid < h - depth) {
            return false; // recent and strictly before this block
        }
        if (!ctx.recentChain().contains(uncle.lastBlockHash())) {
            return false; // must fork from a recent main-chain block
        }
        if (uid <= tipHeight && store.blockAt(uid).hash().equals(uncle.hash())) {
            return false; // that is the canonical block, not an orphan
        }
        return !ctx.alreadyReferenced().contains(uncle.hash()); // not already credited
    }

    private record UncleContext(java.util.Set<SHA256Hash> recentChain,
                                java.util.Set<SHA256Hash> alreadyReferenced) {}
}
