package rhizome.core.blockchain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import rhizome.core.block.Block;
import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
import rhizome.core.block.UncleRef;
import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.Ledger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
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
    private final rhizome.core.box.BoxProcessor boxProcessor;
    private final rhizome.core.token.TokenProcessor tokenProcessor;
    private final rhizome.core.state.StateAccumulator stateAccumulator;
    private LedgerSnapshot genesisSnapshot;
    private final OrphanPool orphans = new OrphanPool(256);
    private final ReentrantLock lock = new ReentrantLock();

    /** Next expected account nonce per sender; persisted, updated incrementally on add/pop. */
    private final NonceStore nonceStore;

    private BigInteger totalWork = BigInteger.ZERO;
    // Base-only cumulative work (Σ 2^difficulty, no uncle/GHOST term) — the same metric the reorg
    // ADOPTION gate uses (HeaderChain / *Synchronizer.localWorkAboveFork). totalWork adds validated
    // uncle work on top; keeping the base total lets the sync PREFILTER compare like-with-like so an
    // uncle-inflated local total can't make a node refuse to even look at a base-heavier peer, while a
    // peer's self-reported totalWork (an upper bound on its base work) still can't trick it into a
    // pop/restore — that stays gated on validated base work (audit 5th-pass, reorg-gate metric).
    private BigInteger baseWork = BigInteger.ZERO;
    private int currentDifficulty;

    /** Uncle work credited per block height, so a pop subtracts exactly what an add added. */
    private final Map<Long, BigInteger> uncleWorkByHeight = new HashMap<>();
    private volatile java.util.function.LongConsumer onBlockApplied;

    /**
     * Votable box params established at each completed voting-epoch boundary (height →
     * {storageFeeFactor, minValuePerByte}). The current values are the last entry (or the
     * defaults); recomputed at each boundary from that epoch's block votes and simply
     * dropped on a pop, so it is reorg-safe without a reversible tally.
     */
    private final java.util.TreeMap<Long, long[]> voteParamsByBoundary = new java.util.TreeMap<>();
    /**
     * Memoised difficulty at each completed retarget boundary (audit P1). Difficulty is piecewise
     * constant between boundaries and each boundary's value is a pure function of two stored header
     * timestamps, so the full O(height/lookback) fold in {@link #computeDifficultyFromChain} need run
     * only for boundaries not yet cached — amortised O(1) per add instead of O(height) (which made
     * initial sync O(height²)). Entries above the tip are dropped on pop, since a reorg can rewrite a
     * boundary's timestamps; boundaries buried past the reorg window are immutable, so their cached
     * value is exact. The memoised value is byte-identical to the full fold — no consensus quantity moves.
     */
    private final java.util.TreeMap<Long, Integer> difficultyByBoundary = new java.util.TreeMap<>();

    private ChainEngine(NetworkParameters params, Ledger ledger, ChainStore store,
                        NonceStore nonceStore,
                        LongSupplier nowMillis, SignatureVerifier verifier,
                        ContractProcessor contractProcessor,
                        rhizome.core.box.BoxProcessor boxProcessor,
                        rhizome.core.token.TokenProcessor tokenProcessor,
                        rhizome.core.state.StateAccumulator stateAccumulator) {
        this.params = params;
        this.ledger = ledger;
        this.store = store;
        this.nonceStore = nonceStore;
        this.nowMillis = nowMillis;
        this.verifier = verifier;
        this.contractProcessor = contractProcessor;
        this.boxProcessor = boxProcessor;
        this.tokenProcessor = tokenProcessor;
        this.stateAccumulator = stateAccumulator;
        if (contractProcessor != null) {
            // Let the VM bound transfer_value by the contract's committed balance (audit T4).
            contractProcessor.useNativeBalance(a ->
                ledger.hasWallet(a) ? ledger.getWalletValue(a).amount() : 0L);
        }
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
        return init(params, ledger, store, snapshot, expectedGenesisHash, nowMillis, verifier,
            contractProcessor, null);
    }

    /** As {@link #init}, additionally enabling box transactions via a {@link rhizome.core.box.BoxProcessor}. */
    public static ChainEngine init(NetworkParameters params, Ledger ledger, ChainStore store,
                                   LedgerSnapshot snapshot, SHA256Hash expectedGenesisHash,
                                   LongSupplier nowMillis, SignatureVerifier verifier,
                                   ContractProcessor contractProcessor,
                                   rhizome.core.box.BoxProcessor boxProcessor) {
        return init(params, ledger, store, snapshot, expectedGenesisHash, nowMillis, verifier,
            contractProcessor, boxProcessor, null);
    }

    /** As {@link #init}, additionally enabling native-token transactions via a {@link rhizome.core.token.TokenProcessor}. */
    public static ChainEngine init(NetworkParameters params, Ledger ledger, ChainStore store,
                                   LedgerSnapshot snapshot, SHA256Hash expectedGenesisHash,
                                   LongSupplier nowMillis, SignatureVerifier verifier,
                                   ContractProcessor contractProcessor,
                                   rhizome.core.box.BoxProcessor boxProcessor,
                                   rhizome.core.token.TokenProcessor tokenProcessor) {
        return init(params, ledger, store, snapshot, expectedGenesisHash, nowMillis, verifier,
            contractProcessor, boxProcessor, tokenProcessor, null);
    }

    /** As {@link #init}, additionally committing an authenticated state root via a {@link rhizome.core.state.StateAccumulator}. */
    public static ChainEngine init(NetworkParameters params, Ledger ledger, ChainStore store,
                                   LedgerSnapshot snapshot, SHA256Hash expectedGenesisHash,
                                   LongSupplier nowMillis, SignatureVerifier verifier,
                                   ContractProcessor contractProcessor,
                                   rhizome.core.box.BoxProcessor boxProcessor,
                                   rhizome.core.token.TokenProcessor tokenProcessor,
                                   rhizome.core.state.StateAccumulator stateAccumulator) {
        return init(params, ledger, store, new InMemoryNonceStore(), snapshot, expectedGenesisHash,
            nowMillis, verifier, contractProcessor, boxProcessor, tokenProcessor, stateAccumulator);
    }

    /**
     * As {@link #init}, with a persisted {@link NonceStore} so account nonces survive
     * restarts and the boot rebuild need not walk historical transaction bodies.
     */
    public static ChainEngine init(NetworkParameters params, Ledger ledger, ChainStore store,
                                   NonceStore nonceStore,
                                   LedgerSnapshot snapshot, SHA256Hash expectedGenesisHash,
                                   LongSupplier nowMillis, SignatureVerifier verifier,
                                   ContractProcessor contractProcessor,
                                   rhizome.core.box.BoxProcessor boxProcessor,
                                   rhizome.core.token.TokenProcessor tokenProcessor,
                                   rhizome.core.state.StateAccumulator stateAccumulator) {
        ChainEngine engine = new ChainEngine(params, ledger, store, nonceStore, nowMillis, verifier,
            contractProcessor, boxProcessor, tokenProcessor, stateAccumulator);
        engine.genesisSnapshot = snapshot;
        if (store.height() == 0) {
            Block genesis = GenesisBlock.initChain(ledger, params, snapshot, expectedGenesisHash);
            store.append(genesis);
        } else if (!GenesisBlock.matches(store.blockAt(GenesisBlock.GENESIS_ID), params, snapshot)) {
            throw new IllegalStateException("Stored genesis does not match network parameters and snapshot");
        }
        engine.rebuildDerivedState();
        engine.seedGenesisStateRoot();
        return engine;
    }

    /**
     * Seeds the state accumulator with the genesis ledger (the snapshot balances) at
     * height {@link GenesisBlock#GENESIS_ID}, so block 2's state root builds on it. Only
     * supported from genesis: enabling the accumulator on an already-populated chain would
     * need a full replay, which is rejected here rather than committing a wrong root.
     */
    private void seedGenesisStateRoot() {
        if (stateAccumulator == null || stateAccumulator.isSeeded()) {
            return;
        }
        if (store.height() > GenesisBlock.GENESIS_ID) {
            throw new IllegalStateException(
                "state accumulator must be enabled from genesis (chain already at height " + store.height() + ")");
        }
        List<rhizome.core.state.StateChange> changes = new ArrayList<>();
        for (var e : genesisSnapshot.balances().entrySet()) {
            long bal = e.getValue().amount();
            if (bal != 0) {
                changes.add(rhizome.core.state.StateChange.set(
                    rhizome.core.state.StateKeys.LEDGER, e.getKey().toBytes(), longBytesBE(bal)));
            }
        }
        stateAccumulator.applyBlock(GenesisBlock.GENESIS_ID, changes);
    }

    public ExecutionStatus addBlock(Block block) {
        return addBlock(block, false);
    }

    /**
     * Re-applies a block that was already canonical on this node — the restore half of a rejected
     * reorg. It is validated exactly like {@link #addBlock} EXCEPT that its uncle references are
     * trusted rather than re-checked against the orphan pool: those uncles passed full validation
     * when the block was first accepted, but the pool is a bounded LRU that a hostile peer can churn
     * (spraying cheap orphans) so a referenced uncle may have been evicted meanwhile. Re-deriving the
     * uncle work from the block's own committed references — instead of failing the restore and
     * throwing "a full resync is required" — lets an honest node recover its own suffix without the
     * orphan pool being a remote liveness lever (audit V5). Uncle rewards come from the same committed
     * references (via the Executor), so they are applied identically with or without the pool.
     */
    public ExecutionStatus restoreBlock(Block block) {
        return addBlock(block, true);
    }

    private ExecutionStatus addBlock(Block block, boolean trustedRestore) {
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
            // Static checkpoint: at a pinned height, only the published hash passes.
            SHA256Hash checkpoint = params.checkpoints().get(height + 1);
            if (checkpoint != null && !block.hash().equals(checkpoint)) {
                return HEADER_HASH_INVALID;
            }
            // Parent linkage and pacing need only the parent HEADER — a snap-synced node
            // holds headers (not bodies) below its pivot, and this path must still work.
            BlockHeader parent = store.headerAt(height);
            if (!b.lastBlockHash().equals(parent.hash())) {
                return INVALID_LASTBLOCK_HASH;
            }
            if (b.timestamp() <= medianTimePast()) {
                return BLOCK_TIMESTAMP_TOO_OLD;
            }
            // Consensus rate limit: a block must be at least minBlockTimeSec after its
            // parent. Enforced by every node, so it caps block production for everyone
            // (majority miner included), unlike the producer's local pacing.
            if (b.timestamp() < parent.timestamp() + params.minBlockTimeSec() * 1000L) {
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

            // Structural uncle checks (GHOST): bounded count, distinct, none is the parent, and each
            // referenced orphan is PoW-verified (memory-hard) for its work weight. Deliberately AFTER
            // the block's OWN PoW: a submitted block triggers up to maxUnclesPerBlock memory-hard uncle
            // hashes, and running them before line-277 let a PoW-free /submit force ~3x the hashing the
            // submitPowGate budgets (one own hash) — an event-loop DoS under the consensus lock. Gating
            // uncle verification behind the block's proven work means an attacker must do real PoW
            // before any uncle hashing runs, so it is no longer a cheap amplifier (audit 5th-pass,
            // consensus/crypto Finding: uncle-PoW-before-block-PoW).
            BigInteger uncleWork;
            if (trustedRestore) {
                // Trust our own previously-validated block's uncle refs; the pool may have been
                // churned since (audit V5). Work is a pure function of the committed ref difficulties.
                uncleWork = uncleWorkFromRefs(b);
            } else {
                uncleWork = validateUncles(b);
                if (uncleWork == null) {
                    return INVALID_UNCLES;
                }
            }

            java.util.Set<PublicAddress> touched = stateAccumulator == null ? null : new java.util.HashSet<>();
            ExecutionStatus status = Executor.executeBlock(
                block, ledger, store::hasTransaction, params, verifier,
                contractProcessor, boxProcessor, tokenProcessor, touched);
            if (status != SUCCESS) {
                return status;
            }

            // Authenticated state root: fold this block's state changes into the accumulator
            // and require the resulting root to equal the header's. On mismatch the block was
            // fully applied, so undo it (ledger + processors + accumulator) before rejecting.
            if (stateAccumulator != null) {
                long height2 = b.id();
                byte[] newRoot = stateAccumulator.applyBlock(height2, collectStateChanges(block, touched, height2));
                if (!java.util.Arrays.equals(newRoot, b.stateRoot().toBytes())) {
                    stateAccumulator.revertBlock(height2);
                    Executor.rollbackBlock(block, ledger, contractProcessor, boxProcessor, height2, params);
                    if (contractProcessor != null) {
                        contractProcessor.revertBlock(height2);
                    }
                    if (boxProcessor != null) {
                        boxProcessor.revertBlock(height2);
                    }
                    if (tokenProcessor != null) {
                        tokenProcessor.revertBlock(height2);
                    }
                    return INVALID_STATE_ROOT;
                }
            } else if (!java.util.Arrays.equals(b.stateRoot().toBytes(),
                    rhizome.crypto.SHA256Hash.empty().toBytes())) {
                // No accumulator to recompute the root, yet the block commits a non-empty one we
                // cannot verify. Accepting it blindly would fork this node from every validating
                // node (audit M6: state-root validation must not depend on local configuration),
                // so refuse a block whose committed state we are unable to check.
                Executor.rollbackBlock(block, ledger, contractProcessor, boxProcessor, b.id(), params);
                if (contractProcessor != null) {
                    contractProcessor.revertBlock(b.id());
                }
                if (boxProcessor != null) {
                    boxProcessor.revertBlock(b.id());
                }
                if (tokenProcessor != null) {
                    tokenProcessor.revertBlock(b.id());
                }
                return INVALID_STATE_ROOT;
            }

            store.append(block);
            commitAccountNonces(block);
            nonceStore.markSyncedThrough(b.id()); // nonces now reflect this new tip
            totalWork = totalWork.add(BlockWork.of(b.difficulty())).add(uncleWork);
            baseWork = baseWork.add(BlockWork.of(b.difficulty()));
            uncleWorkByHeight.put((long) b.id(), uncleWork);
            // A legal reorg pops at most maxReorgDepth blocks, so uncle-work for heights older
            // than that is never subtracted again — evict it instead of retaining one BigInteger
            // per height for the life of the process (audit: unbounded derived-state growth).
            long uncleWorkFloor = b.id() - params.maxReorgDepth();
            if (uncleWorkFloor > 0) {
                uncleWorkByHeight.keySet().removeIf(h -> h < uncleWorkFloor);
            }
            currentDifficulty = computeDifficultyFromChain();
            applyVotingAt(b.id()); // tally this epoch's votes if a boundary; effective next block
            if (onBlockApplied != null) {
                onBlockApplied.accept(b.id()); // fast/non-blocking by contract (see setter)
            }
            return SUCCESS;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called with the height of every successfully applied block — whatever the entry
     * path (API submit, gossip, sync, local producer). Runs while the engine lock is
     * held, so the listener must be fast and non-blocking (e.g. hand off to a queue or
     * an event loop); it must not call back into the engine.
     */
    public void setOnBlockApplied(java.util.function.LongConsumer listener) {
        this.onBlockApplied = listener;
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
            Executor.rollbackBlock(tip, ledger, contractProcessor, boxProcessor, height, params);
            if (contractProcessor != null) {
                contractProcessor.revertBlock(height); // undo this block's contract-state changes
            }
            if (boxProcessor != null) {
                boxProcessor.revertBlock(height); // undo this block's box-state changes
            }
            if (tokenProcessor != null) {
                tokenProcessor.revertBlock(height); // undo this block's token-state changes
            }
            if (stateAccumulator != null) {
                stateAccumulator.revertBlock(height); // move the state root back one block
            }
            store.pop();
            // Drop any memoised retarget-boundary difficulty at or above the popped height: the block
            // (hence a boundary's timestamps) may be rewritten by the reorg, so those cached values are
            // no longer trusted and are recomputed on demand. Buried boundaries below stay valid (P1).
            difficultyByBoundary.tailMap(height, true).clear();
            revertAccountNonces(tip);
            nonceStore.markSyncedThrough(height - 1); // nonces now reflect the tip after the pop
            // Drop the vote tally established at this height (if it was an epoch boundary),
            // restoring the previous epoch's params — reorg-safe without a reversible tally.
            if (voteParamsByBoundary.remove(height) != null) {
                syncVoteableHolder();
            }
            BigInteger uncleWork = uncleWorkByHeight.remove(height);
            totalWork = totalWork.subtract(BlockWork.of(((BlockImpl) tip).difficulty()));
            baseWork = baseWork.subtract(BlockWork.of(((BlockImpl) tip).difficulty()));
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
            return store.headerAt(store.height()).hash();
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

    /** Logical header at the given height (1-based); served without the body for headers-first sync. */
    public BlockHeader headerAt(long height) {
        lock.lock();
        try {
            return store.headerAt(height);
        } finally {
            lock.unlock();
        }
    }

    /** Exclusive upper bound of pruned block bodies ({@code 0} = archive node). See {@link ChainStore#prunedBelow()}. */
    public long prunedBelow() {
        lock.lock();
        try {
            return store.prunedBelow();
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
            long tipFloor = store.headerAt(store.height()).timestamp() + params.minBlockTimeSec() * 1000L;
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

    /** Base-only cumulative work (Σ 2^difficulty, no uncle term) — the reorg adoption-gate metric. */
    public BigInteger baseWork() {
        lock.lock();
        try {
            return baseWork;
        } finally {
            lock.unlock();
        }
    }

    /** Next expected account nonce for a sender (0 for a fresh account). */
    public long nextNonce(rhizome.core.ledger.PublicAddress sender) {
        lock.lock();
        try {
            return nonceStore.next(sender);
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

    @Override
    public boolean senderExists(rhizome.core.ledger.PublicAddress sender) {
        lock.lock();
        try {
            return ledger.hasWallet(sender);
        } finally {
            lock.unlock();
        }
    }

    public NetworkParameters params() {
        return params;
    }

    /** Current wall-clock (ms) from the engine's time source — the reference for the future-block bound. */
    public long nowMillis() {
        return nowMillis.getAsLong();
    }

    /**
     * Runs {@code action} while holding the engine lock, so it sees one point-in-time view
     * across the chain, ledger, nonces and processor state — no block can land mid-read.
     * Used to capture a consistent state-snapshot export (the whole dump must correspond to
     * a single {@code (height, stateRoot)} pair). Keep the action bounded: block application
     * stalls while it runs.
     */
    public <T> T withConsistentView(java.util.function.Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    // ---- data boxes ----

    /** Whether the data-box layer is wired (box transactions and queries active). */
    public boolean boxesEnabled() {
        return boxProcessor != null;
    }

    /** Whether the native-token layer is wired. */
    public boolean tokensEnabled() {
        return tokenProcessor != null;
    }

    /** The box at {@code id} from committed state, or {@code null} (none / boxes disabled). */
    public rhizome.core.box.Box box(byte[] id) {
        if (boxProcessor == null) {
            return null;
        }
        lock.lock();
        try {
            return boxProcessor.getCommitted(id);
        } finally {
            lock.unlock();
        }
    }

    /** Box ids owned by {@code owner}, paginated after {@code afterId} (null = start). */
    public java.util.List<byte[]> boxIdsByOwner(byte[] owner, byte[] afterId, int limit) {
        if (boxProcessor == null) {
            return java.util.List.of();
        }
        lock.lock();
        try {
            return boxProcessor.boxIdsByOwner(owner, afterId, limit);
        } finally {
            lock.unlock();
        }
    }

    /** Evaluates a box scan predicate over committed state (owner-index fast path when anchored). */
    public rhizome.core.box.BoxProcessor.ScanPage scanBoxes(
            rhizome.core.box.ScanPredicate predicate, byte[] afterId, int limit, int window) {
        if (boxProcessor == null) {
            return new rhizome.core.box.BoxProcessor.ScanPage(java.util.List.of(), null);
        }
        // No engine lock: the scan reads only committed box state (thread-safe), so it does
        // not contend with block production.
        return boxProcessor.scan(predicate, afterId, limit, window);
    }

    /** Rent-collectable box ids at the next block height, lowest expiry first (block producer). */
    public java.util.List<byte[]> collectableBoxIds(long height, int limit) {
        if (boxProcessor == null) {
            return java.util.List.of();
        }
        lock.lock();
        try {
            return boxProcessor.collectableBoxIds(height, limit);
        } finally {
            lock.unlock();
        }
    }

    /** Box lifecycle events emitted by the block at {@code height} (for the agent event feed). */
    public java.util.List<rhizome.core.box.BoxProcessor.BoxEvent> boxEvents(long height) {
        return boxProcessor == null ? java.util.List.of() : boxProcessor.events(height);
    }

    // ---- native tokens ----

    /** Committed metadata for {@code tokenId}, or {@code null} (none / tokens disabled). */
    public rhizome.core.token.TokenMeta tokenMeta(byte[] tokenId) {
        if (tokenProcessor == null) {
            return null;
        }
        lock.lock();
        try {
            return tokenProcessor.meta(tokenId);
        } finally {
            lock.unlock();
        }
    }

    /** Committed balance of {@code tokenId} held by {@code address}. */
    public long tokenBalance(byte[] tokenId, byte[] address) {
        if (tokenProcessor == null) {
            return 0L;
        }
        lock.lock();
        try {
            return tokenProcessor.balance(tokenId, address);
        } finally {
            lock.unlock();
        }
    }

    /** Token ids minted by {@code minter}, paginated after {@code afterId} (null = start). */
    public java.util.List<byte[]> tokenIdsByMinter(byte[] minter, byte[] afterId, int limit) {
        if (tokenProcessor == null) {
            return java.util.List.of();
        }
        lock.lock();
        try {
            return tokenProcessor.tokenIdsByMinter(minter, afterId, limit);
        } finally {
            lock.unlock();
        }
    }

    /** Token ids {@code address} holds, paginated after {@code afterId} (null = start). */
    public java.util.List<byte[]> tokenIdsByHolder(byte[] address, byte[] afterId, int limit) {
        if (tokenProcessor == null) {
            return java.util.List.of();
        }
        lock.lock();
        try {
            return tokenProcessor.tokenIdsByHolder(address, afterId, limit);
        } finally {
            lock.unlock();
        }
    }

    /** Token lifecycle events emitted by the block at {@code height}. */
    public java.util.List<rhizome.core.token.TokenProcessor.TokenEvent> tokenEvents(long height) {
        return tokenProcessor == null ? java.util.List.of() : tokenProcessor.events(height);
    }

    // ---- miner-voted parameters ----

    /** The votable box params (storageFeeFactor, minValuePerByte) currently in effect. */
    public long[] voteableParams() {
        lock.lock();
        try {
            return currentVoteParams();
        } finally {
            lock.unlock();
        }
    }

    /** Current votable params: the last epoch-boundary values, or the network defaults. */
    private long[] currentVoteParams() {
        var e = voteParamsByBoundary.lastEntry();
        return e != null ? e.getValue().clone()
            : new long[] {params.storageFeeFactor(), params.minValuePerByte()};
    }

    /** Pushes the current votable params into the box processor's holder (read at execution). */
    private void syncVoteableHolder() {
        var holder = boxProcessor == null ? null : boxProcessor.voteableParams();
        if (holder != null) {
            long[] p = currentVoteParams();
            holder.set(p[0], p[1]);
        }
    }

    /**
     * At a voting-epoch boundary, tallies that epoch's block votes and moves each votable
     * parameter one bounded step if its net vote exceeds half the epoch. Effective from the
     * next block, so the just-executed boundary block still used the previous values.
     */
    private void applyVotingAt(long height) {
        long epoch = params.votingEpochLength();
        if (epoch <= 0 || height < epoch || height % epoch != 0) {
            return;
        }
        long netSff = 0;
        long netMvb = 0;
        for (long h = height - epoch + 1; h <= height; h++) {
            int vote = store.headerAt(h).vote();
            int paramId = Math.abs(vote);
            int dir = Integer.signum(vote);
            if (paramId == VoteableParams.STORAGE_FEE_FACTOR) {
                netSff += dir;
            } else if (paramId == VoteableParams.MIN_VALUE_PER_BYTE) {
                netMvb += dir;
            }
        }
        long threshold = epoch / 2;
        long[] cur = currentVoteParams();
        long sff = adjust(cur[0], netSff, threshold, params.storageFeeFactorStep(),
            params.storageFeeFactorMin(), params.storageFeeFactorMax());
        long mvb = adjust(cur[1], netMvb, threshold, params.minValuePerByteStep(),
            params.minValuePerByteMin(), params.minValuePerByteMax());
        voteParamsByBoundary.put(height, new long[] {sff, mvb});
        syncVoteableHolder();
    }

    private static long adjust(long value, long netVotes, long threshold, long step, long min, long max) {
        if (netVotes > threshold) {
            return Math.min(max, value + step);
        }
        if (netVotes < -threshold) {
            return Math.max(min, value - step);
        }
        return value;
    }

    // ---- authenticated state root ----

    /** The current authenticated state root (32 bytes), or {@code null} if the accumulator is off. */
    public byte[] stateRoot() {
        if (stateAccumulator == null) {
            return null;
        }
        lock.lock();
        try {
            return stateAccumulator.root();
        } finally {
            lock.unlock();
        }
    }

    /** A membership proof for a state entry at the current root, or {@code null} if absent / off. */
    public rhizome.core.state.StateProof stateProof(byte domain, byte[] rawKey) {
        if (stateAccumulator == null) {
            return null;
        }
        lock.lock();
        try {
            return stateAccumulator.prove(domain, rawKey);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stamps {@code candidate}'s {@code stateRoot} with the root it would produce, so the
     * producer can mine a header that commits it. Tentatively applies the block to compute
     * the root, then rolls the application back — the block is re-applied for real when
     * submitted through {@link #addBlock}. A no-op when the accumulator is off.
     */
    public void stampStateRoot(Block candidate) {
        if (stateAccumulator == null) {
            return;
        }
        lock.lock();
        try {
            var b = (BlockImpl) candidate;
            java.util.Set<PublicAddress> touched = new java.util.HashSet<>();
            ExecutionStatus status = Executor.executeBlock(candidate, ledger, store::hasTransaction, params,
                verifier, contractProcessor, boxProcessor, tokenProcessor, touched);
            if (status != SUCCESS) {
                return; // invalid block; leave the state root empty and let addBlock reject it
            }
            long h = b.id();
            byte[] root = stateAccumulator.dryApply(collectStateChanges(candidate, touched, h));
            b.stateRoot(SHA256Hash.of(root));
            Executor.rollbackBlock(candidate, ledger, contractProcessor, boxProcessor, h, params);
            if (contractProcessor != null) {
                contractProcessor.revertBlock(h);
            }
            if (boxProcessor != null) {
                boxProcessor.revertBlock(h);
            }
            if (tokenProcessor != null) {
                tokenProcessor.revertBlock(h);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Gathers a block's committed ledger, nonce, box and token changes into state accumulator changes. */
    private List<rhizome.core.state.StateChange> collectStateChanges(
            Block block, java.util.Set<PublicAddress> touched, long height) {
        List<rhizome.core.state.StateChange> changes = new ArrayList<>();
        for (PublicAddress a : touched) {
            long bal = ledger.hasWallet(a) ? ledger.getWalletValue(a).amount() : 0;
            byte[] key = a.toBytes();
            changes.add(bal == 0
                ? rhizome.core.state.StateChange.delete(rhizome.core.state.StateKeys.LEDGER, key)
                : rhizome.core.state.StateChange.set(rhizome.core.state.StateKeys.LEDGER, key, longBytesBE(bal)));
        }
        // Account nonces (⚠ consensus domain 0x07): each sender's next-expected nonce after this
        // block is max(txNonce)+1 over its transactions — a deterministic function of block content
        // (sequentiality already validated). Committing it lets a snap-synced node obtain nonces
        // verifiably (root equality) rather than trusting a peer. Derived from the block, not the
        // nonce store, because the store is advanced only after this collection runs.
        java.util.Map<PublicAddress, Long> newNonces = new HashMap<>();
        for (Transaction t : block.transactions()) {
            var tx = (TransactionImpl) t;
            if (!tx.isTransactionFee() && !isSelfAuthorized(tx)) {
                newNonces.merge(tx.from(), tx.nonce() + 1, Math::max);
            }
        }
        newNonces.forEach((from, nonce) -> changes.add(rhizome.core.state.StateChange.set(
            rhizome.core.state.StateKeys.ACCOUNT_NONCE, from.toBytes(), longBytesBE(nonce))));
        if (boxProcessor != null) {
            for (var m : boxProcessor.changes(height)) {
                changes.add(m.box() == null
                    ? rhizome.core.state.StateChange.delete(rhizome.core.state.StateKeys.BOX, m.id())
                    : rhizome.core.state.StateChange.set(rhizome.core.state.StateKeys.BOX, m.id(), m.box().serialize()));
            }
        }
        if (tokenProcessor != null) {
            for (var op : tokenProcessor.changes(height)) {
                if (op instanceof rhizome.core.token.TokenStore.TokenOp.MetaSet ms) {
                    changes.add(rhizome.core.state.StateChange.set(
                        rhizome.core.state.StateKeys.TOKEN_META, ms.meta().id(), ms.meta().serialize()));
                } else if (op instanceof rhizome.core.token.TokenStore.TokenOp.BalanceSet bs) {
                    byte[] rawKey = concat(bs.tokenId(), bs.address());
                    changes.add(bs.amount() == 0
                        ? rhizome.core.state.StateChange.delete(rhizome.core.state.StateKeys.TOKEN_BALANCE, rawKey)
                        : rhizome.core.state.StateChange.set(rhizome.core.state.StateKeys.TOKEN_BALANCE, rawKey,
                            longBytesBE(bs.amount())));
                }
            }
        }
        if (contractProcessor != null) {
            for (var ch : contractProcessor.changes(height)) {
                if (ch.code()) {
                    changes.add(rhizome.core.state.StateChange.set(
                        rhizome.core.state.StateKeys.CONTRACT_CODE, ch.contract().toBytes(), ch.value()));
                } else {
                    byte[] rawKey = concat(ch.contract().toBytes(), ch.key());
                    changes.add(rhizome.core.state.StateChange.set(
                        rhizome.core.state.StateKeys.CONTRACT_STORAGE, rawKey, ch.value()));
                }
            }
        }
        return changes;
    }

    private static byte[] longBytesBE(long value) {
        return rhizome.core.common.Utils.longToBytes(value);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // ---- derived state ----

    private void rebuildDerivedState() {
        totalWork = BigInteger.ZERO;
        baseWork = BigInteger.ZERO;
        uncleWorkByHeight.clear();
        voteParamsByBoundary.clear();
        difficultyByBoundary.clear(); // recomputed from headers by computeDifficultyFromChain (P1)
        long height = store.height();
        // Work, uncle weight and votes are recomputed from headers alone. Account nonces are
        // persisted and maintained incrementally, so they are reconstructed from the bodies
        // only for the heights the nonce store has not yet synced — a fresh column family
        // (one-time migration from a full archive) or a transient in-memory store at boot. A
        // persistent store that advanced in lockstep reports the tip, so a normal restart —
        // even a pruned node's, even one that has never seen an account transaction — reads no
        // body at all.
        long nonceSynced = nonceStore.syncedThroughHeight();
        long backfillFrom = Math.max(GenesisBlock.GENESIS_ID + 1, nonceSynced + 1);
        if (backfillFrom <= height && backfillFrom < store.prunedBelow()) {
            // The nonces we must rebuild live in bodies that have been pruned — an
            // inconsistent store (a pruned node whose nonces were not persisted). Fail loudly
            // rather than dying later on a missing body or, worse, undercounting nonces.
            // prunedBelow() is the EXCLUSIVE upper bound of the pruned range, so the body *at*
            // prunedBelow() is retained: backfillFrom == prunedBelow() is rebuildable and must not
            // trip this guard (audit S8, off-by-one — was <=).
            throw new IllegalStateException(
                "cannot rebuild account nonces from pruned bodies (synced through " + nonceSynced
                    + ", pruned below " + store.prunedBelow() + ")");
        }
        for (long h = GenesisBlock.GENESIS_ID + 1; h <= height; h++) {
            BlockHeader header = store.headerAt(h);
            if (h >= backfillFrom) {
                commitAccountNonces(store.blockAt(h));
            }
            // Uncle work is recomputed from the committed uncle difficulties, so the
            // cumulative weight is restored exactly even with an empty orphan pool.
            BigInteger uncleWork = uncleWorkOf(header);
            uncleWorkByHeight.put(h, uncleWork);
            totalWork = totalWork.add(BlockWork.of(header.difficulty())).add(uncleWork);
            baseWork = baseWork.add(BlockWork.of(header.difficulty()));
            applyVotingAt(h); // replay epoch tallies so the votable params are restored
        }
        if (height > nonceSynced) {
            nonceStore.markSyncedThrough(height); // persist the catch-up so the next restart skips it
        }
        // Evict uncle-work below the reorg horizon, mirroring the addBlock path: the rebuild loop above
        // populated one BigInteger per height genesis->tip, but a legal reorg pops at most maxReorgDepth
        // blocks, so older entries are never subtracted again. Without this a fresh boot at height H
        // holds O(H) BigIntegers until the next addBlock prunes them (audit S10, benign but unbounded).
        long uncleWorkFloor = height - params.maxReorgDepth();
        if (uncleWorkFloor > 0) {
            uncleWorkByHeight.keySet().removeIf(h -> h < uncleWorkFloor);
        }
        currentDifficulty = computeDifficultyFromChain();
        syncVoteableHolder();
    }

    /** Sum of 2^difficulty over a header's referenced uncles (from committed difficulties). */
    private static BigInteger uncleWorkOf(BlockHeader header) {
        BigInteger work = BigInteger.ZERO;
        for (rhizome.core.block.UncleRef uncle : header.uncles()) {
            work = work.add(BlockWork.of(uncle.difficulty()));
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
        long height = store.height();
        if (height < lookback) {
            return params.genesisDifficulty(); // no completed retarget window yet
        }
        long lastBoundary = (height / lookback) * lookback; // highest completed boundary <= height
        // Resume the fold from the highest already-cached boundary (or genesis) rather than replaying
        // it from height 1 on every add/pop. Cached boundaries are immutable buried history; a pop
        // clears any that were above the new tip, so this never returns a stale value (audit P1).
        var floor = difficultyByBoundary.floorEntry(lastBoundary);
        long fromBoundary = floor == null ? 0 : floor.getKey();
        int difficulty = floor == null ? params.genesisDifficulty() : floor.getValue();
        for (long boundary = fromBoundary + lookback; boundary <= lastBoundary; boundary += lookback) {
            long windowStart = boundary - lookback + 1;
            // Exclude the genesis interval from the first window: genesis carries an artificial
            // timestamp (genesisTimestamp, conventionally 0), so measuring from it would treat
            // "epoch → first real block" as one block interval and crater the difficulty of the
            // first retarget. Start the measurement at the first real block instead (audit L2).
            long measureStart = Math.max(windowStart, GenesisBlock.GENESIS_ID + 1);
            long intervals = boundary - measureStart;
            if (intervals > 0) { // else not enough real blocks in this window yet (difficulty unchanged)
                long observedMs = store.headerAt(boundary).timestamp()
                    - store.headerAt(measureStart).timestamp();
                difficulty = DifficultyAdjustment.nextDifficulty(
                    params, difficulty, intervals, observedMs / 1000);
            }
            difficultyByBoundary.put(boundary, difficulty); // cache every boundary so floorEntry advances
        }
        return difficulty;
    }

    private long medianTimePast() {
        long height = store.height();
        int window = (int) Math.min(params.medianTimeWindow(), height);
        List<Long> timestamps = new ArrayList<>(window);
        for (long h = height - window + 1; h <= height; h++) {
            timestamps.add(store.headerAt(h).timestamp());
        }
        timestamps.sort(Long::compare);
        return timestamps.get(timestamps.size() / 2);
    }

    // ---- account nonces ----

    private ExecutionStatus checkAccountNonces(Block block) {
        Map<rhizome.core.ledger.PublicAddress, Long> expected = new HashMap<>();
        for (Transaction t : block.transactions()) {
            var tx = (TransactionImpl) t;
            if (tx.isTransactionFee() || isSelfAuthorized(tx)) {
                continue;
            }
            long want = expected.computeIfAbsent(tx.from(), a -> nonceStore.next(a));
            if (tx.nonce() != want) {
                return INVALID_TRANSACTION_NONCE;
            }
            expected.put(tx.from(), want + 1);
        }
        return SUCCESS;
    }

    /** Self-authorized txs (coinbase and permissionless rent collection) carry no account nonce. */
    private static boolean isSelfAuthorized(TransactionImpl tx) {
        return tx.kind() == rhizome.core.transaction.TransactionKind.BOX_COLLECT;
    }

    private void commitAccountNonces(Block block) {
        for (Transaction t : block.transactions()) {
            var tx = (TransactionImpl) t;
            if (!tx.isTransactionFee() && !isSelfAuthorized(tx)) {
                nonceStore.set(tx.from(), Math.max(nonceStore.next(tx.from()), tx.nonce() + 1));
            }
        }
    }

    private void revertAccountNonces(Block block) {
        Map<rhizome.core.ledger.PublicAddress, Long> lowest = new HashMap<>();
        for (Transaction t : block.transactions()) {
            var tx = (TransactionImpl) t;
            if (!tx.isTransactionFee() && !isSelfAuthorized(tx)) {
                lowest.merge(tx.from(), tx.nonce(), Math::min);
            }
        }
        // The block's lowest nonce for a sender becomes the next-expected again; set(_, 0)
        // clears the entry (that sender's first transaction is being undone).
        lowest.forEach(nonceStore::set);
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
        size += (long) block.uncles().size()
            * (SHA256Hash.SIZE + Integer.BYTES + rhizome.core.ledger.PublicAddress.SIZE);
        return size;
    }

    /**
     * Remembers a valid off-chain block so a later block may reference it as an uncle.
     * Only blocks with valid proof of work are retained (no free pool spam).
     */
    public void registerOrphan(Block block) {
        lock.lock();
        try {
            var b = (BlockImpl) block;
            // Cheap, allocation-free pre-checks BEFORE the memory-hard verifyNonce (audit H3): the
            // Pufferfish2 hash is expensive by design, so `/submit` must not let an attacker force one
            // per throwaway block. A block can only ever become a valid uncle if it is a recent
            // sibling — its height within the uncle-depth window of our tip, and its parent our known
            // canonical block at height-1 (exactly what uncleEligible later requires). Garbage with a
            // random parent or an out-of-window height is dropped here for a few comparisons instead
            // of a hash. This also removes the double-hash: a would-be next block (id = tip+1) that
            // failed addBlock's own verifyNonce has id > tip and is rejected below without re-hashing.
            long tip = store.height();
            int depth = params.uncleMaxDepth();
            int uid = b.id();
            if (b.difficulty() < params.minDifficulty()) {
                return; // worthless as an uncle; also forgeable free "work"
            }
            if (uid <= GenesisBlock.GENESIS_ID || uid > tip || uid < tip - depth + 1) {
                return; // not a recent past sibling of a block we could still build on
            }
            if (!store.headerAt(uid - 1).hash().equals(b.lastBlockHash())) {
                return; // must fork from our known main-chain parent at height uid-1
            }
            // Only now the memory-hard proof-of-work check, on a block that is at least a
            // structurally-plausible recent sibling.
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
    /**
     * The uncle work committed by {@code block}'s references, summed as {@code Σ 2^difficulty} — the
     * same total {@link #validateUncles} returns, but read straight from the (already-validated)
     * references without consulting the orphan pool. Used only by {@link #restoreBlock}. Each
     * committed difficulty is bounded to {@code [0, 255]} at header decode, so the power is bounded.
     */
    private static BigInteger uncleWorkFromRefs(BlockImpl block) {
        BigInteger work = BigInteger.ZERO;
        for (UncleRef ref : block.uncles()) {
            work = work.add(BlockWork.of(ref.difficulty()));
        }
        return work;
    }

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
            PublicAddress uncleMiner = blockMiner(uncle);
            if (uncleMiner == null || !uncleMiner.equals(ref.miner())) {
                return null; // committed miner must match the real orphan (no reward redirection)
            }
            // The nephew's difficulty caps how much work any uncle it references can claim,
            // and minDifficulty floors it — see uncleEligible. block.difficulty() is the
            // including block's own difficulty.
            if (!uncleEligible(uncle, h, depth, tipHeight, ctx, block.difficulty())) {
                return null;
            }
            uncleWork = uncleWork.add(BlockWork.of(ref.difficulty()));
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
                PublicAddress orphanMiner = blockMiner(orphan);
                // The block being produced at height h will carry the current difficulty;
                // only reference orphans whose difficulty fits [minDifficulty, currentDifficulty]
                // so the produced block passes its own validateUncles check.
                if (orphanMiner != null
                        && uncleEligible(orphan, h, depth, tipHeight, ctx, currentDifficulty)) {
                    out.add(new UncleRef(orphan.hash(), ((BlockImpl) orphan).difficulty(), orphanMiner));
                }
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    /** The coinbase recipient (miner) of a block, or {@code null} if it has no coinbase. */
    private static PublicAddress blockMiner(Block block) {
        for (Transaction t : block.transactions()) {
            var tx = (TransactionImpl) t;
            if (tx.isTransactionFee()) {
                return tx.to();
            }
        }
        return null;
    }

    /** Recent main-chain hashes an uncle may fork from, and uncle hashes already referenced. */
    private UncleContext uncleContext(int h, int depth, long tipHeight) {
        java.util.Set<SHA256Hash> recentChain = new java.util.HashSet<>();
        java.util.Set<SHA256Hash> alreadyReferenced = new java.util.HashSet<>();
        for (long ancestor = Math.max(GenesisBlock.GENESIS_ID, h - depth - 1L); ancestor <= tipHeight; ancestor++) {
            BlockHeader onChain = store.headerAt(ancestor);
            recentChain.add(onChain.hash());
            if (ancestor >= h - depth) {
                for (UncleRef ref : onChain.uncles()) {
                    alreadyReferenced.add(ref.hash());
                }
            }
        }
        return new UncleContext(recentChain, alreadyReferenced);
    }

    /**
     * Whether {@code uncle} is a valid uncle for a block at height {@code h} whose own
     * difficulty is {@code nephewDifficulty}. The uncle's difficulty must lie in
     * {@code [minDifficulty, nephewDifficulty]}: no zero-/sub-minimum-work uncle earns a
     * reward, and none can be credited more work than the contemporaneous chain difficulty.
     * The same bound is enforced in HeaderChain.uncleWork so mining, block validation and
     * headers-first sync all agree.
     */
    private boolean uncleEligible(Block uncle, int h, int depth, long tipHeight, UncleContext ctx,
                                  int nephewDifficulty) {
        if (!uncle.verifyNonce(params.powAlgorithm())) {
            return false; // bad PoW
        }
        int ud = ((BlockImpl) uncle).difficulty();
        if (ud < params.minDifficulty() || ud > nephewDifficulty) {
            return false; // work must be real and not inflated beyond the nephew's difficulty
        }
        int uid = uncle.id();
        if (uid >= h || uid < h - depth) {
            return false; // recent and strictly before this block
        }
        if (!ctx.recentChain().contains(uncle.lastBlockHash())) {
            return false; // must fork from a recent main-chain block
        }
        if (uid <= tipHeight && store.headerAt(uid).hash().equals(uncle.hash())) {
            return false; // that is the canonical block, not an orphan
        }
        return !ctx.alreadyReferenced().contains(uncle.hash()); // not already credited
    }

    private record UncleContext(java.util.Set<SHA256Hash> recentChain,
                                java.util.Set<SHA256Hash> alreadyReferenced) {}
}
