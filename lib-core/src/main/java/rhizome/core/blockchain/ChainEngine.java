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

    /** Next expected account nonce per sender; rebuilt on init, updated on add/pop. */
    private final Map<rhizome.core.ledger.PublicAddress, Long> nextNonce = new HashMap<>();

    private BigInteger totalWork = BigInteger.ZERO;
    private int currentDifficulty;

    /** Uncle work credited per block height, so a pop subtracts exactly what an add added. */
    private final Map<Long, BigInteger> uncleWorkByHeight = new HashMap<>();
    private volatile java.util.function.LongConsumer onBlockApplied;

    private ChainEngine(NetworkParameters params, Ledger ledger, ChainStore store,
                        LongSupplier nowMillis, SignatureVerifier verifier,
                        ContractProcessor contractProcessor,
                        rhizome.core.box.BoxProcessor boxProcessor,
                        rhizome.core.token.TokenProcessor tokenProcessor,
                        rhizome.core.state.StateAccumulator stateAccumulator) {
        this.params = params;
        this.ledger = ledger;
        this.store = store;
        this.nowMillis = nowMillis;
        this.verifier = verifier;
        this.contractProcessor = contractProcessor;
        this.boxProcessor = boxProcessor;
        this.tokenProcessor = tokenProcessor;
        this.stateAccumulator = stateAccumulator;
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
        ChainEngine engine = new ChainEngine(params, ledger, store, nowMillis, verifier,
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
                byte[] newRoot = stateAccumulator.applyBlock(height2, collectStateChanges(touched, height2));
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
            }

            store.append(block);
            commitAccountNonces(block);
            totalWork = totalWork.add(BigInteger.TWO.pow(b.difficulty())).add(uncleWork);
            uncleWorkByHeight.put((long) b.id(), uncleWork);
            currentDifficulty = computeDifficultyFromChain();
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

    // ---- data boxes ----

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
            byte[] root = stateAccumulator.dryApply(collectStateChanges(touched, h));
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

    /** Gathers a block's committed ledger, box and token changes into state accumulator changes. */
    private List<rhizome.core.state.StateChange> collectStateChanges(
            java.util.Set<PublicAddress> touched, long height) {
        List<rhizome.core.state.StateChange> changes = new ArrayList<>();
        for (PublicAddress a : touched) {
            long bal = ledger.hasWallet(a) ? ledger.getWalletValue(a).amount() : 0;
            byte[] key = a.toBytes();
            changes.add(bal == 0
                ? rhizome.core.state.StateChange.delete(rhizome.core.state.StateKeys.LEDGER, key)
                : rhizome.core.state.StateChange.set(rhizome.core.state.StateKeys.LEDGER, key, longBytesBE(bal)));
        }
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
            if (tx.isTransactionFee() || isSelfAuthorized(tx)) {
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

    /** Self-authorized txs (coinbase and permissionless rent collection) carry no account nonce. */
    private static boolean isSelfAuthorized(TransactionImpl tx) {
        return tx.kind() == rhizome.core.transaction.TransactionKind.BOX_COLLECT;
    }

    private void commitAccountNonces(Block block) {
        for (Transaction t : block.transactions()) {
            var tx = (TransactionImpl) t;
            if (!tx.isTransactionFee() && !isSelfAuthorized(tx)) {
                nextNonce.merge(tx.from(), tx.nonce() + 1, Math::max);
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
            PublicAddress uncleMiner = blockMiner(uncle);
            if (uncleMiner == null || !uncleMiner.equals(ref.miner())) {
                return null; // committed miner must match the real orphan (no reward redirection)
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
                PublicAddress orphanMiner = blockMiner(orphan);
                if (orphanMiner != null && uncleEligible(orphan, h, depth, tipHeight, ctx)) {
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
