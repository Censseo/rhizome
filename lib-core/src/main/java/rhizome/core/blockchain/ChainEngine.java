package rhizome.core.blockchain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
public final class ChainEngine implements rhizome.core.mempool.AccountView {

    private static final Logger log = LoggerFactory.getLogger(ChainEngine.class);

    private final NetworkParameters params;
    private final Ledger ledger;
    private final ChainStore store;
    private final LongSupplier nowMillis;
    private final SignatureVerifier verifier;
    private final ContractProcessor contractProcessor;
    private final rhizome.core.box.BoxProcessor boxProcessor;
    private final rhizome.core.token.TokenProcessor tokenProcessor;
    /**
     * The same three instances as the typed fields above, in commit order. The typed fields serve
     * the domain-specific read paths and the state-change translation; this list serves every
     * lifecycle walk, so the order those walks use is a single value rather than five hand-written
     * statement sequences. Pinned by {@code StateDomainOrderTest}.
     */
    private final java.util.List<BlockStateProcessor> stateDomains;
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
    /**
     * Ring of the last {@code medianTimeWindow} header timestamps in ascending height order (audit P6).
     * Maintained incrementally on add/pop and rebuilt at boot, so {@link #medianTimePast} sorts a small
     * {@code long[]} copy instead of re-reading and re-boxing the whole window from the store on every
     * {@code addBlock} and {@code nextBlockTimestamp}. It holds exactly the heights the store-read
     * version would ({@code [max(genesis, tip-W+1), tip]}), so the median is byte-identical — pinned by
     * an equivalence test over a random add/pop/reorg walk.
     */
    private final java.util.ArrayDeque<Long> mtpWindow = new java.util.ArrayDeque<>();

    /**
     * Open while a synchronizer runs a NON-atomic reorg (HeaderSynchronizer's pop → body-apply →
     * restore/adopt sequence, which streams up to MAX_HEADER_WINDOW bodies with interleaved
     * network I/O and so cannot hold the engine lock across it). During that window the chain sits
     * truncated at the fork height: a gossiped, submitted or locally-produced block accepted at
     * forkHeight+1 would be destroyed by the restore (honest PoW lost), and readers would be served
     * the truncated tip. New-tip {@link #addBlock} therefore fails fast with IS_SYNCING while the
     * window is open; the synchronizer's own paths (restoreBlock / addValidatedBody) bypass the
     * guard. The window is opened UNDER the engine lock (inside the synchronizer's capture+pop
     * {@link #withConsistentView}) and closed in try/finally after the restore/adopt — never
     * held across downloads.
     */
    private final java.util.concurrent.atomic.AtomicBoolean reorgWindowOpen =
        new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Verify-once cache of orphan-header proof of work (audit: uncle re-hash). Every production
     * round's {@link #selectUncles} scans the whole orphan pool under the engine lock, and each
     * eligible orphan's memory-hard Pufferfish2 hash was re-run per candidate block — up to 256
     * hashes per round. PoW validity is a pure function of the header: the hash commits every
     * header field including the id (which selects the PoW cost via {@code powCostsAt}), so a
     * cached positive verdict can never go stale — it does NOT depend on the tip, hence needs no
     * invalidation on pop/reorg. Bounded LRU (access-order); only successes are cached, so an
     * attacker cannot evict useful entries with junk headers. Engine-lock-guarded like every
     * caller ({@link #registerOrphan}, {@link #uncleEligible}).
     */
    private final java.util.LinkedHashMap<SHA256Hash, Boolean> verifiedOrphanPow =
        new java.util.LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<SHA256Hash, Boolean> eldest) {
                return size() > 1024;
            }
        };

    /**
     * Recently popped canonical blocks (bounded), mapped block-hash → proven PoW nonce, so
     * {@link #restoreBlock} skips the memory-hard PoW re-verification ONLY for a block whose
     * header is identical to one this node popped — the hash commits every header field except
     * the nonce, which is stored alongside so the exact proven (hash, nonce) pair is required.
     * That pair passed PoW when first accepted, and PoW validity is tip-independent, so
     * re-hashing it on restore is pure waste (up to maxReorgDepth Pufferfish2 hashes per
     * rejected reorg, under the lock). Membership is the safety condition: a restore of any
     * other block (mutated, fabricated) misses the set and falls back to the full check.
     */
    private final java.util.LinkedHashMap<SHA256Hash, SHA256Hash> recentlyPoppedBlocks =
        new java.util.LinkedHashMap<>(64, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<SHA256Hash, SHA256Hash> eldest) {
                return size() > 2L * Math.max(1, params.maxReorgDepth());
            }
        };

    /**
     * Observable degraded-state marker (audit: silent restore failure). Set when a post-reorg
     * restore of the local branch fails — the node is then shorter than it started and needs a
     * full resync; cleared when a restore completes and the chain is whole again. Volatile-read
     * getter so an API layer can relay the condition; the failure is also logged and thrown.
     */
    private final java.util.concurrent.atomic.AtomicReference<String> degradedState =
        new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean degradedRestartRequired =
        new java.util.concurrent.atomic.AtomicBoolean();

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
        // Null means "this node has no such domain". Normalize it once, here, to the absent
        // singleton: every downstream site then talks to a processor instead of guarding against a
        // null one, and "absent" is expressed by available() rather than by identity. The public
        // init() overloads keep accepting null, so no caller changes.
        this.contractProcessor = contractProcessor == null ? ContractProcessor.NONE : contractProcessor;
        this.boxProcessor = boxProcessor == null ? rhizome.core.box.BoxProcessor.NONE : boxProcessor;
        this.tokenProcessor =
            tokenProcessor == null ? rhizome.core.token.TokenProcessor.NONE : tokenProcessor;
        this.stateAccumulator = stateAccumulator;
        this.stateDomains = BlockStateProcessor.inCommitOrder(
            this.contractProcessor, this.boxProcessor, this.tokenProcessor);
        // Let the VM bound transfer_value by the contract's committed balance (audit T4).
        this.contractProcessor.useNativeBalance(a ->
            ledger.hasWallet(a) ? ledger.getWalletValue(a).amount() : 0L);
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
        engine.reconcilePeripheralStores();
        engine.rebuildDerivedState();
        engine.seedGenesisStateRoot();
        return engine;
    }

    /**
     * Boot recovery for a torn multi-store commit (audit S3) or a torn popBlock revert (audit:
     * revert-path tear). A block's state commits in program order — contract, box, token
     * processors, then the state accumulator, then the atomic block/height/ledger batch last —
     * and a pop lands the height/ledger batch FIRST and reverts the peripherals SECOND, so any
     * crash, in either direction, leaves the peripheral stores one (or, on a power loss, more)
     * block AHEAD of the chain height with their undo journals still present. Rewind each back
     * down to the chain height using its per-block undo journal, so the node comes up at one
     * consistent height instead of wedging on the next block's state-root check. Bounded by the
     * reorg window: journals older than that are gone (a deeper tear is unrecoverable —
     * impossible on a clean process crash, only on a power loss without per-store fsync).
     *
     * <p>Processor {@code revertBlock} is a no-op when there is no journal at that height, so sweeping
     * the window is safe on a normal (untorn) boot; the accumulator's is not, so it is driven by its
     * exact committed height.
     */
    private void reconcilePeripheralStores() {
        long chainHeight = store.height();
        if (stateAccumulator != null) {
            for (long h = stateAccumulator.committedHeight(); h > chainHeight; h--) {
                stateAccumulator.revertBlock(h);
            }
        }
        long scanTop = chainHeight + params.maxReorgDepth();
        for (long h = scanTop; h > chainHeight; h--) {
            revertStateDomains(h);
        }
    }

    /**
     * Seeds the state accumulator with the genesis ledger (the snapshot balances) at
     * height {@link GenesisBlock#GENESIS_ID}, so block 2's state root builds on it. Only
     * supported from genesis: enabling the accumulator on an already-populated chain would
     * need a full replay, which is rejected here rather than committing a wrong root.
     */
    /**
     * Undoes {@code height} in every per-block state domain, in the canonical order.
     *
     * <p>Replaces four byte-identical hand-written triplets (boot reconciliation, the two addBlock
     * state-root rejections, and popBlock), whose only relationship to Executor's commit order was
     * a comment. The state accumulator is deliberately NOT in this walk: its revertBlock throws on
     * an unjournalled height where a processor's is a documented no-op, and its position differs
     * per site — first during reconciliation, last in popBlock, absent from the stampStateRoot undo
     * because that path uses dryApply. Folding it in would change three orderings silently and turn
     * a fail-loud into a fail-silent.
     */
    private void revertStateDomains(long height) {
        for (BlockStateProcessor domain : stateDomains) {
            domain.revertBlock(height);
        }
    }

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
                    rhizome.core.state.StateKeys.LEDGER, e.getKey().toBytes(),
                    BlockStateChanges.longBytesBE(bal)));
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
     *
     * <p>The block's own PoW is ALSO not re-verified when the block's header is identical to one
     * this node just popped (audit: restore re-hashes): that exact (hash, nonce) pair proved its
     * work when first accepted, and PoW validity is tip-independent. {@link #recentlyPoppedBlocks}
     * membership is the gate — any other block (mutated or fabricated) misses it and gets the full
     * memory-hard check. Only the PoW is skipped: id continuity, timestamps, difficulty, merkle
     * root, nonces, the uncle structural bounds and the full state re-application all still run.
     *
     * <p>Package-private (audit F2): only the in-package synchronizers may drive the trusted path,
     * and even they get the pool-free STRUCTURAL bounds on the committed refs (count cap,
     * distinctness, difficulty range — see {@link #uncleWorkFromRefs}), so a fabricated block can
     * never mint uncle work a normal {@link #addBlock} would have rejected.
     */
    ExecutionStatus restoreBlock(Block block) {
        return addBlock(block, true, false);
    }

    /**
     * Applies a body during headers-first sync whose proof of work is ALREADY proven — its hash equals
     * a header that {@link HeaderChain#validate} PoW-verified (memory-hard Pufferfish2). Skips only the
     * redundant re-run of that same memory-hard hash in {@link #addBlock}; every other check (merkle
     * root, account nonces, difficulty, uncles, state root, executor) runs in full. PoW is the single
     * most expensive validation step, so not re-hashing every synced body roughly halves body-sync CPU.
     *
     * <p><b>Caller contract (audit P4):</b> the caller MUST have confirmed {@code block.hash()} equals
     * an already-PoW-validated header for this height before calling this — otherwise it accepts
     * unproven work. Only {@link HeaderSynchronizer#applyBodies} does so, immediately after its
     * hash-equality check against the {@code HeaderChain}-validated branch. Package-private so no
     * external caller can reach the PoW-skipping path.
     */
    ExecutionStatus addValidatedBody(Block block) {
        return addBlock(block, false, true);
    }

    private ExecutionStatus addBlock(Block block, boolean trustedRestore) {
        return addBlock(block, trustedRestore, false);
    }

    private ExecutionStatus addBlock(Block block, boolean trustedRestore, boolean trustedPow) {
        lock.lock();
        try {
            // Degraded barrier (audit 17th pass): after a failed post-pop peripheral revert or a
            // failed post-reorg restore, the local state is suspect — a peripheral store may sit
            // AHEAD of the height (its receipts would be silently overwritten by a new block at
            // that height, destroying the journal boot recovery needs), or the chain is shorter
            // than it was. Refuse every NEW-tip write — gossip, /submit, production and
            // peer-branch adoption alike — instead of entrenching state boot recovery has not
            // repaired. Only the trusted RESTORE path bypasses the barrier: it re-applies this
            // node's own already-canonical suffix and clears the flag on success
            // (ChainSynchronizer.restore), so the barrier cannot wedge recovery — and a degraded
            // node refusing work is exactly the fail-loud signal that a restart is required.
            if (!trustedRestore && isDegraded()) {
                return NODE_DEGRADED;
            }
            // Reorg-window guard (audit: non-atomic reorg window). Checked UNDER the lock, and the
            // window is opened under the same lock (HeaderSynchronizer's phase 1 runs begin inside
            // the capture+pop withConsistentView), so no new-tip block can slip between the window
            // opening and the first pop: while a non-atomic reorg holds the chain truncated at the
            // fork height, refuse NEW tip blocks (gossip, /submit, local production) instead of
            // accepting one the restore would destroy. The window CLOSES outside the lock (finally,
            // after the restore/adopt view completes), which can only delay an acceptance by one
            // IS_SYNCING retry — benign, never a lost block. The trusted paths bypass the guard:
            // restoreBlock re-adds our own suffix and addValidatedBody applies the proven branch —
            // both driven by the synchronizer that opened the window.
            if (!trustedRestore && !trustedPow && reorgWindowOpen.get()) {
                return IS_SYNCING;
            }
            Block b = block;
            long height = store.height();

            if (b.id() != height + 1) {
                return INVALID_BLOCK_ID;
            }
            if (block.transactions().isEmpty()
                || block.transactions().size() > params.maxTransactionsPerBlock()) {
                return INVALID_TRANSACTION_COUNT; // must at least carry a coinbase
            }
            // One canonical vote rule at the consensus gate, not just in the codecs (audit F1):
            // BlockDto/HeaderCodec already reject |vote| > 2 on the wire, but a block arriving via
            // JSON or the local producer must meet the same bound before its vote can reach the
            // epoch tally. Cheap and structural, so it runs with the other pre-PoW checks.
            if (Math.abs((long) b.vote()) > 2) {
                return INVALID_VOTE;
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
            // Proof of work, verified last so an invalid block can't burn CPU (Pandanite lesson) — unless
            // the caller already proved it: a headers-first-synced body whose hash equals a header
            // HeaderChain.validate PoW-verified carries the same memory-hard proof, so re-hashing it is
            // pure waste (audit P4). trustedPow is reachable only via addValidatedBody, whose contract
            // pins that hash-equality guarantee. On the trusted-restore path the same skip applies when
            // the block's header is identical to a block this node just popped — hash match PLUS the
            // proven nonce (the hash preimage does not commit the nonce). Anything else re-checks in full.
            boolean powAlreadyProven = trustedPow
                || (trustedRestore && b.nonce().equals(recentlyPoppedBlocks.get(block.hash())));
            if (!powAlreadyProven && !block.verifyNonce(params.powAlgorithm(), params.powCostsAt(b.id()))) {
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
                // Trust our own previously-validated block's uncle refs (the pool may have been
                // churned since, audit V5) — but still enforce the pool-free STRUCTURAL bounds the
                // committed refs carry on their own (audit F2): count cap, distinct hashes and
                // minDifficulty <= ref.difficulty() <= block.difficulty(). A fabricated block passed
                // to this trusted path can then never inflate uncle work/rewards beyond what a
                // normal addBlock would accept.
                uncleWork = uncleWorkFromRefs(b);
                if (uncleWork == null) {
                    return INVALID_UNCLES;
                }
            } else {
                uncleWork = validateUncles(b);
                if (uncleWork == null) {
                    return INVALID_UNCLES;
                }
            }

            java.util.Set<PublicAddress> touched = stateAccumulator == null ? null : new java.util.HashSet<>();
            // Open a block commit: the ledger writes below stage in the store and flush atomically with
            // the block/height in store.append, so a crash can never leave the ledger ahead of the
            // height (audit S3). Every exit before append must discard the staged writes, so the whole
            // mutation runs under a finally.
            store.beginBlockCommit();
            boolean appended = false;
            try {
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
                        revertStateDomains(height2);
                        return INVALID_STATE_ROOT;
                    }
                } else if (!java.util.Arrays.equals(b.stateRoot().toBytes(),
                        rhizome.crypto.SHA256Hash.empty().toBytes())) {
                    // No accumulator to recompute the root, yet the block commits a non-empty one we
                    // cannot verify. Accepting it blindly would fork this node from every validating
                    // node (audit M6: state-root validation must not depend on local configuration),
                    // so refuse a block whose committed state we are unable to check.
                    Executor.rollbackBlock(block, ledger, contractProcessor, boxProcessor, b.id(), params);
                    revertStateDomains(b.id());
                    return INVALID_STATE_ROOT;
                }

                // Nonce updates are derived purely from the block, so stage them BEFORE the
                // append: they flush in the SAME atomic batch as the block/height/ledger instead
                // of one synced put per sender after it (audit perf: per-sender fsync). On a
                // failed append the staged values are discarded, exactly as they were previously
                // never written.
                commitAccountNonces(block);
                nonceStore.markSyncedThrough(b.id()); // nonces now reflect this new tip
                store.append(block); // flushes the staged ledger + nonce writes + block + height in one batch
                appended = true;
                // Persist the bodies of the uncles this block references, BEFORE the bounded
                // orphan pool's LRU can evict them (audit: uncle-sync blocker). A block carries
                // only UncleRefs, so peers syncing past this height later fetch the bodies from
                // us (PeerSource.orphan → orphanBlock) — without persistence the pool churn would
                // make the uncle unserveable and the chain unsynchronisable for fresh nodes. On
                // the trusted-restore path the uncle may already be gone from the pool; the
                // entry written on first acceptance is simply kept.
                if (!trustedRestore) {
                    for (UncleRef ref : block.uncles()) {
                        Block uncle = orphans.get(ref.hash());
                        if (uncle != null) {
                            store.putUncle(ref.hash(), uncle);
                        }
                    }
                }
                // Slide the median-time window forward: the new tip enters, the oldest leaves (P6).
                mtpWindow.addLast(b.timestamp());
                if (mtpWindow.size() > params.medianTimeWindow()) {
                    mtpWindow.removeFirst();
                }
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
                pruneDerivedStateCaches(b.id()); // bound vote/difficulty memo growth (audit)
                // Domain retention prunes ONLY here, on a block that stands: the stampStateRoot
                // dry run and the state-root rejections above commit domains at this height and
                // then revert them, so a commit-time prune would key retention to a commit
                // attempt rather than the chain tip and delete the oldest in-window receipts a
                // max-depth reorg still needs (see BlockStateProcessor.pruneToChainTip).
                for (BlockStateProcessor domain : stateDomains) {
                    domain.pruneToChainTip(b.id());
                }
                if (onBlockApplied != null) {
                    onBlockApplied.accept(b.id()); // fast/non-blocking by contract (see setter)
                }
                return SUCCESS;
            } finally {
                if (!appended) {
                    store.discardBlockCommit(); // drop the staged (and possibly rolled-back) ledger writes
                }
            }
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

    /**
     * Removes the tip block (never genesis), reverting ledger and nonces. Package-private
     * (audit: unguarded public popBlock): truncating the canonical chain is a
     * synchronizer-only operation — tests and recovery simulations use
     * {@link ChainEngineTestAccess#popBlock(ChainEngine)}.
     * <p>Crash-consistency ordering (audit: revert-path tear): the height/ledger batch lands
     * FIRST, the peripheral reverts (contract, box, token, state) SECOND. A crash before the
     * pop leaves every store at the old height (staged ledger writes are discarded); a crash
     * during the peripheral phase leaves those stores AHEAD of the height with their undo
     * journals still present — the exact torn-commit shape {@link #reconcilePeripheralStores}
     * rewinds at boot. Reverting the peripherals BEFORE the pop instead consumed each store's
     * journal and receipts while the chain still sat at the old height: a reverse-direction
     * tear no recovery pass could detect, wedging the node on its fork or silently diverging
     * its state.
     */
    void popBlock() {
        lock.lock();
        try {
            long height = store.height();
            if (height <= GenesisBlock.GENESIS_ID) {
                throw new IllegalStateException("Cannot pop genesis");
            }
            Block tip = store.tip();
            // Stage the ledger reversals so store.pop() flushes them atomically with the height
            // decrement — the pop is then atomic for the ledger too (audit S3). rollbackBlock only
            // READS the peripherals' receipts (still present at this point: they are dropped by
            // the revert phase below, after the height has moved).
            store.beginBlockCommit();
            boolean popped = false;
            try {
                Executor.rollbackBlock(tip, ledger, contractProcessor, boxProcessor, height, params);
                // Stage the nonce reversals BEFORE the pop so they flush in the same atomic batch
                // as the height decrement (audit perf: per-sender fsync) — derived purely from the
                // popped tip, so on a failed pop they are discarded, as they were previously never
                // written.
                revertAccountNonces(tip);
                nonceStore.markSyncedThrough(height - 1); // nonces now reflect the tip after the pop
                store.pop(); // flushes the staged ledger + nonce reversals + height decrement in one batch
                popped = true;
            } finally {
                if (!popped) {
                    store.discardBlockCommit();
                }
            }
            // The height has moved: FIRST bring the in-memory view down to H-1 so it always
            // agrees with the store, even if a peripheral revert below fails (audit 17th pass:
            // throwing out of popBlock with the memory bookkeeping skipped left totalWork, the
            // MTP window and currentDifficulty describing a chain that no longer existed).
            // Slide the median-time window back one block (P6): the popped tip leaves the window, and a
            // lower height re-enters at the front when the chain is still taller than the window.
            mtpWindow.removeLast();
            if (height - params.medianTimeWindow() >= GenesisBlock.GENESIS_ID) {
                mtpWindow.addFirst(store.headerAt(height - params.medianTimeWindow()).timestamp());
            }
            // Drop any memoised retarget-boundary difficulty at or above the popped height: the block
            // (hence a boundary's timestamps) may be rewritten by the reorg, so those cached values are
            // no longer trusted and are recomputed on demand. Buried boundaries below stay valid (P1).
            difficultyByBoundary.tailMap(height, true).clear();
            // Drop the vote tally established at this height (if it was an epoch boundary),
            // restoring the previous epoch's params — reorg-safe without a reversible tally.
            if (voteParamsByBoundary.remove(height) != null) {
                syncVoteableHolder();
            }
            BigInteger uncleWork = uncleWorkByHeight.remove(height);
            totalWork = totalWork.subtract(BlockWork.of(tip.difficulty()));
            baseWork = baseWork.subtract(BlockWork.of(tip.difficulty()));
            if (uncleWork != null) {
                totalWork = totalWork.subtract(uncleWork);
            }
            // Remember the popped block (hash → proven nonce) so a subsequent restoreBlock of the
            // SAME header skips the (tip-independent, already-proven) PoW re-verification.
            recentlyPoppedBlocks.put(tip.hash(), tip.nonce());
            currentDifficulty = computeDifficultyFromChain();
            // THEN revert the peripheral stores (each restores from its own journal and drops
            // journal + receipts in one atomic unit). A failure here is a recoverable tear: the
            // ledger, height and in-memory state all agree at H-1, and every peripheral not yet
            // reverted is AHEAD of the height with its journal intact — exactly the direction
            // reconcilePeripheralStores rewinds at boot. Mark the node degraded restart-required:
            // the addBlock barrier then refuses every new-tip write until the operator restarts
            // into boot recovery (a restore never rewinds the torn peripheral, so it cannot
            // clear the mark).
            try {
                revertStateDomains(height); // undo this block's contract/box/token changes
                if (stateAccumulator != null) {
                    stateAccumulator.revertBlock(height); // move the state root back one block
                }
            } catch (RuntimeException e) {
                markDegraded("peripheral revert failed after popping block " + height
                    + " (" + e.getMessage() + ") — a peripheral store is ahead of the chain"
                    + " height; new-tip blocks are refused until the node restarts into boot"
                    + " recovery (a restore cannot clear this mark)", true);
                throw e;
            }
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

    /**
     * Runs {@code action} while holding the engine lock: any in-flight block application
     * has finished and no new one can start until it returns. Used at shutdown to quiesce
     * writers before the underlying stores are closed — closing a native store handle while
     * another thread is inside a write corrupts the native heap (observed as a JVM abort
     * once writes became fsynced, audit F3).
     */
    public void runExclusive(Runnable action) {
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    // ---- reorg window (non-atomic synchronizer reorgs) ----

    /**
     * Opens the reorg window: while open, new-tip {@link #addBlock} fails fast (IS_SYNCING) and the
     * block producer stands down. Called by an in-package synchronizer UNDER the engine lock —
     * atomically with the capture+pop phase of a non-atomic pop → body-apply → restore/adopt
     * sequence — and closed in try/finally after the restore/adopt (outside the lock, which can
     * only delay an acceptance by one IS_SYNCING retry). NEVER held across downloads (the body
     * apply runs outside the lock by design).
     *
     * @return false if a window was already open (defensive; sync runs on a single thread).
     */
    boolean beginReorgWindow() {
        return reorgWindowOpen.compareAndSet(false, true);
    }

    /** Closes the reorg window opened by {@link #beginReorgWindow()}. */
    void endReorgWindow() {
        reorgWindowOpen.set(false);
    }

    /** Whether a non-atomic reorg window is open (the chain may sit truncated at a fork height). */
    public boolean isReorgInProgress() {
        return reorgWindowOpen.get();
    }

    // ---- degraded state (restore failure after a rejected reorg, or a failed post-pop revert) ----

    /**
     * Marks the node degraded: local state is suspect and every NEW-tip {@link #addBlock} is
     * refused with {@code NODE_DEGRADED}. Two causes, two healing paths (audit 17th-pass
     * counter-review): a restore failure ({@code restartRequired = false}) heals when a later
     * full restore succeeds and calls {@link #clearDegraded}; a torn pop
     * ({@code restartRequired = true} — a peripheral store sits AHEAD of the chain height)
     * is only rewound by boot recovery, so the mark survives every restore until the operator
     * restarts the node.
     */
    void markDegraded(String reason, boolean restartRequired) {
        if (restartRequired) {
            // Set BEFORE the reason so a concurrent clearDegraded never observes a
            // restart-required mark as clearable — and never unset here: a later
            // restore-failure mark must not downgrade a torn pop to restore-clearable.
            degradedRestartRequired.set(true);
        }
        degradedState.set(reason);
    }

    /**
     * Clears the degraded marker once the local branch is fully restored (chain whole again).
     * A no-op when the mark demands a restart: the restore re-applied the suffix but never
     * rewound the torn peripheral — only boot recovery ({@code reconcilePeripheralStores})
     * does, so the barrier stays up until the operator restarts.
     */
    void clearDegraded() {
        if (degradedRestartRequired.get()) {
            log.warn("refusing to clear the degraded marker ({}): the cause requires a node"
                + " restart into boot recovery", degradedState.get());
            return;
        }
        degradedState.set(null);
    }

    /** The degraded-state reason, or {@code null} when the node is healthy. */
    public String degradedState() {
        return degradedState.get();
    }

    /**
     * Whether the node is in a degraded state. This is a HARD barrier, not just an observable
     * flag: while set, {@code addBlock} refuses new-tip writes and the block producer stands
     * down, so the node does NOT progress at all — not even by direct extension (audit 17th
     * pass). Fail-loud by design: the only external signal is the {@code degraded} field of
     * {@code /stats}, which supervision must alert on (otherwise a frozen node goes
     * unnoticed), and restart-required marks only clear via an operator restart into boot
     * recovery.
     */
    public boolean isDegraded() {
        return degradedState.get() != null;
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

    /**
     * The height of the applied block containing {@code contentHash}, or {@code null} — the
     * O(1) txid index lookup used by {@code /transaction} to avoid decoding a window of full
     * blocks on the read path (audit perf).
     */
    public Long transactionHeight(rhizome.crypto.SHA256Hash contentHash) {
        lock.lock();
        try {
            return store.transactionHeight(contentHash);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long confirmedHeight() {
        return height();
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

    /**
     * Stamp-in-progress version counter (a seqlock) guarding the deliberately lock-free
     * box/token readers below. {@link #stampStateRoot} increments it at entry (odd = a
     * commit-then-revert dry-run is mutating the live box/token stores) and again at exit
     * (even). A reader that observes an odd starting value, or a value that changed across
     * its read, redoes the read under the engine lock — so those readers can never observe
     * the phantom, never-committed state a stamp briefly commits before rolling back
     * (audit review: torn reads through the lock-free API paths), while a reader racing no
     * stamp pays only two volatile reads and stays off the consensus lock.
     */
    private final java.util.concurrent.atomic.AtomicLong stampVersion =
        new java.util.concurrent.atomic.AtomicLong();

    /** Runs {@code read}, falling back to the engine lock iff a stamp overlapped it (see above). */
    private <T> T readOutsideStamp(java.util.function.Supplier<T> read) {
        long v = stampVersion.get();
        if ((v & 1L) == 0L) {
            T result = read.get();
            if (stampVersion.get() == v) {
                return result; // no stamp overlapped — the lock-free read saw committed state only
            }
        }
        lock.lock();
        try {
            return read.get();
        } finally {
            lock.unlock();
        }
    }

    // ---- data boxes ----

    /** Whether the data-box layer is wired (box transactions and queries active). */
    public boolean boxesEnabled() {
        return boxProcessor.available();
    }

    /** Whether the native-token layer is wired. */
    public boolean tokensEnabled() {
        return tokenProcessor.available();
    }

    /** The box at {@code id} from committed state, or {@code null} (none / boxes disabled). */
    public rhizome.core.box.Box box(byte[] id) {
        lock.lock();
        try {
            return boxProcessor.getCommitted(id);
        } finally {
            lock.unlock();
        }
    }

    /** Box ids owned by {@code owner}, paginated after {@code afterId} (null = start). */
    public java.util.List<byte[]> boxIdsByOwner(byte[] owner, byte[] afterId, int limit) {
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
        if (!boxProcessor.available()) {
            return new rhizome.core.box.BoxProcessor.ScanPage(java.util.List.of(), null);
        }
        // No engine lock on the fast path: the scan reads only committed box state (thread-safe),
        // so it does not contend with block production — unless a stampStateRoot dry-run is
        // mid-flight, which readOutsideStamp detects via the stamp seqlock and falls back to the lock.
        return readOutsideStamp(() -> boxProcessor.scan(predicate, afterId, limit, window));
    }

    /** Rent-collectable box ids at the next block height, lowest expiry first (block producer). */
    public java.util.List<byte[]> collectableBoxIds(long height, int limit) {
        lock.lock();
        try {
            return boxProcessor.collectableBoxIds(height, limit);
        } finally {
            lock.unlock();
        }
    }

    /** Box lifecycle events emitted by the block at {@code height} (for the agent event feed). */
    public java.util.List<rhizome.core.box.BoxProcessor.BoxEvent> boxEvents(long height) {
        return !boxProcessor.available()
            ? java.util.List.of()
            // Lock-free unless a stamp is committing-then-reverting phantom events for this
            // height (readOutsideStamp falls back to the engine lock in that window).
            : readOutsideStamp(() -> boxProcessor.events(height));
    }

    // ---- native tokens ----

    /** Committed metadata for {@code tokenId}, or {@code null} (none / tokens disabled). */
    public rhizome.core.token.TokenMeta tokenMeta(byte[] tokenId) {
        lock.lock();
        try {
            return tokenProcessor.meta(tokenId);
        } finally {
            lock.unlock();
        }
    }

    /** Committed balance of {@code tokenId} held by {@code address}. */
    public long tokenBalance(byte[] tokenId, byte[] address) {
        lock.lock();
        try {
            return tokenProcessor.balance(tokenId, address);
        } finally {
            lock.unlock();
        }
    }

    /** Token ids minted by {@code minter}, paginated after {@code afterId} (null = start). */
    public java.util.List<byte[]> tokenIdsByMinter(byte[] minter, byte[] afterId, int limit) {
        lock.lock();
        try {
            return tokenProcessor.tokenIdsByMinter(minter, afterId, limit);
        } finally {
            lock.unlock();
        }
    }

    /** Token ids {@code address} holds, paginated after {@code afterId} (null = start). */
    public java.util.List<byte[]> tokenIdsByHolder(byte[] address, byte[] afterId, int limit) {
        lock.lock();
        try {
            return tokenProcessor.tokenIdsByHolder(address, afterId, limit);
        } finally {
            lock.unlock();
        }
    }

    /** Token lifecycle events emitted by the block at {@code height}. */
    public java.util.List<rhizome.core.token.TokenProcessor.TokenEvent> tokenEvents(long height) {
        return !tokenProcessor.available()
            ? java.util.List.of()
            // Same stamp seqlock as boxEvents: never expose phantom, never-committed events.
            : readOutsideStamp(() -> tokenProcessor.events(height));
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
        var holder = boxProcessor.voteableParams();
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

    /**
     * Bounds the two per-boundary derived-state memos (audit: unbounded growth — one entry per
     * retarget/voting boundary for the life of the process, ~300k/yr at mainnet constants).
     *
     * <p>{@code difficultyByBoundary}: cached values are byte-identical recomputations from
     * immutable buried headers, so any resume point yields the same fold — entries below the
     * reorg window are simply dropped; a post-reorg refold then stays window-sized (the pop path
     * already clears entries at/above the popped height, which a reorg may rewrite).
     *
     * <p>{@code voteParamsByBoundary}: values are NOT recomputable without the tally, so the
     * prune must preserve pop correctness. A pop-run from the current tip reaches at most
     * {@code maxReorgDepth} down, so it can only remove boundaries in {@code (tip-mrd, tip]};
     * popping boundary H falls back to the previous boundary H-epoch, which lies strictly above
     * {@code tip-mrd-epoch}. Keeping every key above that cutoff therefore preserves every
     * reachable tally and fallback. The single floor entry at/below the cutoff is retained as
     * belt-and-braces: it is the live base value a boundary case would read.
     */
    private void pruneDerivedStateCaches(long tip) {
        long mrd = params.maxReorgDepth();
        // Difficulty memo: keep the window's boundaries PLUS the single floor entry below the
        // cutoff — the ideal resume point for the fold. Without it, a lookback wider than the
        // reorg depth would leave the map empty and force a genesis-length refold per add. The
        // floor entry is immutable sealed history (a legal reorg can never reach it), so its
        // cached value is exact; popBlock's tailMap clear remains the only invalidator needed.
        long diffCutoff = tip - mrd;
        var resume = difficultyByBoundary.floorEntry(diffCutoff);
        difficultyByBoundary.headMap(diffCutoff, true).clear();
        if (resume != null) {
            difficultyByBoundary.put(resume.getKey(), resume.getValue());
        }
        long epoch = params.votingEpochLength();
        if (epoch > 0) {
            long cutoff = tip - mrd - epoch;
            var base = voteParamsByBoundary.floorEntry(cutoff);
            voteParamsByBoundary.headMap(cutoff, true).clear();
            if (base != null) {
                voteParamsByBoundary.put(base.getKey(), base.getValue());
            }
        }
    }

    /** Test hook: current sizes of the two per-boundary memos (pruning assertions). */
    int[] derivedCacheSizesForTest() {
        lock.lock();
        try {
            return new int[] {difficultyByBoundary.size(), voteParamsByBoundary.size()};
        } finally {
            lock.unlock();
        }
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
            // A reorg window is open: the chain sits truncated at a fork height, so stamping a
            // candidate on it is wasted work whose block addBlock would refuse anyway. Skip.
            if (reorgWindowOpen.get()) {
                return;
            }
            // Stage this dry-run's ledger writes in the overlay so they never touch the column
            // family and are dropped wholesale by discardBlockCommit — a producer-only
            // apply-then-revert that leaves zero ledger residue even if the process dies
            // mid-stamp (audit S3). Staged BEFORE the stamp window opens: if this throws, the
            // version was never bumped and the outer finally still releases the lock.
            store.beginBlockCommit();
            // Mark the stamp window for the lock-free box/token readers: the executeBlock below
            // commits this candidate's box/token mutations to the live stores before they are
            // reverted, so readOutsideStamp must fall back to the engine lock until the rollback
            // completes (odd = stamp in flight; back to even when the stores are clean again).
            // No store mutation happens between beginBlockCommit and this increment, so
            // lock-free readers cannot observe a dirty store outside the window.
            stampVersion.incrementAndGet();
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
                revertStateDomains(h);
            } finally {
                store.discardBlockCommit(); // drop the dry-run's staged ledger writes
                stampVersion.incrementAndGet(); // stamp window closed — stores are clean again
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gathers a block's committed effects into state-accumulator changes.
     *
     * <p>The five statements below ARE the emission order the state root commits to; each
     * translation lives in {@link BlockStateChanges}, where it can be asserted without an engine.
     * This order is deliberately not the domain commit order — see that class.
     */
    private List<rhizome.core.state.StateChange> collectStateChanges(
            Block block, java.util.Set<PublicAddress> touched, long height) {
        List<rhizome.core.state.StateChange> changes = new ArrayList<>();
        BlockStateChanges.ledger(ledger, touched, changes);
        BlockStateChanges.nonces(block, changes);
        BlockStateChanges.box(boxProcessor, height, changes);
        BlockStateChanges.token(tokenProcessor, height, changes);
        BlockStateChanges.contract(contractProcessor, height, changes);
        return changes;
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
        rebuildMtpWindow(); // repopulate the median-time ring from headers (P6)
        syncVoteableHolder();
        pruneDerivedStateCaches(height); // the boot replay repopulated one entry per boundary
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
            // The window arithmetic is shared with HeaderChain (see Retarget); only the memo
            // policy below is the engine's own. store::headerAt, not this::headerAt — the public
            // accessor takes the engine lock.
            difficulty = Retarget.stepWindow(params, store::headerAt, difficulty, boundary);
            difficultyByBoundary.put(boundary, difficulty); // cache every boundary so floorEntry advances
        }
        return difficulty;
    }

    private long medianTimePast() {
        int size = mtpWindow.size();
        if (size == 0) {
            return 0; // no chain yet (defensive; the ring holds >= genesis whenever height >= 1)
        }
        long[] ts = new long[size];
        int i = 0;
        for (long t : mtpWindow) {
            ts[i++] = t;
        }
        java.util.Arrays.sort(ts);
        return ts[size / 2];
    }

    /** The median-time window, rebuilt from headers (boot / reorg base). Ascending height order. */
    private void rebuildMtpWindow() {
        mtpWindow.clear();
        long height = store.height();
        long lo = Math.max(GenesisBlock.GENESIS_ID, height - params.medianTimeWindow() + 1);
        for (long h = lo; h <= height; h++) {
            mtpWindow.addLast(store.headerAt(h).timestamp());
        }
    }

    /** Test hook (audit P6): the ring-based median, compared in tests to a fresh store computation. */
    public long medianTimePastForTest() {
        lock.lock();
        try {
            return medianTimePast();
        } finally {
            lock.unlock();
        }
    }

    // ---- account nonces ----

    private ExecutionStatus checkAccountNonces(Block block) {
        Map<rhizome.core.ledger.PublicAddress, Long> expected = new HashMap<>();
        for (Transaction tx : block.transactions()) {
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
    static boolean isSelfAuthorized(Transaction tx) {
        return tx.kind() == rhizome.core.transaction.TransactionKind.BOX_COLLECT;
    }

    private void commitAccountNonces(Block block) {
        for (Transaction tx : block.transactions()) {
            if (!tx.isTransactionFee() && !isSelfAuthorized(tx)) {
                nonceStore.set(tx.from(), Math.max(nonceStore.next(tx.from()), tx.nonce() + 1));
            }
        }
    }

    private void revertAccountNonces(Block block) {
        Map<rhizome.core.ledger.PublicAddress, Long> lowest = new HashMap<>();
        for (Transaction tx : block.transactions()) {
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
            size += t.sizeBytes(); // exact wire length without building the DTO (P7)
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
            Block b = block;
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
            // Same serialized-size bound addBlock enforces, checked BEFORE the PoW hash: the
            // pool retains full bodies, so without it a spray of max-size orphans (each with a
            // real but cheap minDifficulty PoW) could pin ~maxSize × maxBlockSizeBytes of heap
            // (audit: orphan body retention).
            if (serializedSize(block) > params.maxBlockSizeBytes()) {
                return; // too large to ever be accepted or referenced
            }
            if (uid <= GenesisBlock.GENESIS_ID || uid > tip || uid < tip - depth + 1) {
                return; // not a recent past sibling of a block we could still build on
            }
            if (!store.headerAt(uid - 1).hash().equals(b.lastBlockHash())) {
                return; // must fork from our known main-chain parent at height uid-1
            }
            // Only now the memory-hard proof-of-work check, on a block that is at least a
            // structurally-plausible recent sibling — verify-once: a sibling already proven (e.g.
            // re-gossiped, or scanned by selectUncles on a previous round) is not re-hashed.
            if (verifyOrphanPowOnce(block, uid)) {
                orphans.put(block);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * The full body of a known orphan by hash: the live pool first, then the store's
     * persisted uncle bodies (surviving a restart or an LRU eviction — audit:
     * uncle-sync blocker). Served to syncing peers via {@link PeerSource#orphan}
     * and used by the synchronizers' on-demand uncle fetch. {@code null} when unknown.
     */
    public Block orphanBlock(SHA256Hash hash) {
        lock.lock();
        try {
            Block orphan = orphans.get(hash);
            return orphan != null ? orphan : store.uncleAt(hash);
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
     * references without consulting the orphan pool. Used only by {@link #restoreBlock}.
     *
     * <p>Even on the trusted path the pool-free structural bounds are enforced from the refs alone
     * (audit F2): at most {@code maxUnclesPerBlock} references, distinct hashes, and
     * {@code minDifficulty <= ref.difficulty() <= block.difficulty()} — the SAME range
     * {@link #uncleEligible} and {@code HeaderChain.uncleWork} enforce (nephewDifficulty = the
     * including block's own difficulty), so every path agrees on the bound. Returns {@code null}
     * when a bound fails, exactly like {@link #validateUncles}.
     */
    private BigInteger uncleWorkFromRefs(Block block) {
        return UncleWeight.structuralWork(block.uncles(), block.difficulty(), params);
    }

    private BigInteger validateUncles(Block block) {
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
                // The sync paths' uncle-resolve skips a fetch when the body is already known, and
                // "known" includes the PERSISTED uncle bodies (addBlock persists referenced uncles
                // so fresh nodes can fetch them later). A node that applied the referencing block
                // before a restart therefore holds the body in the store but NOT in the in-memory
                // pool: the retry after the resolve would fail right here — INVALID_UNCLES every
                // round, a PEER_INVALID ban of an honest peer (campaign 2, S7: a wedged cluster
                // that froze at the first block referencing a persisted uncle). The persisted body
                // was fully validated when first applied and its eligibility is re-checked below
                // against the live context, so falling back to it is exact.
                uncle = store.uncleAt(u);
            }
            if (uncle == null) {
                return null; // unknown orphan
            }
            if (uncle.difficulty() != ref.difficulty()) {
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
            // Block ids are int on the wire (BlockDto/HeaderCodec), so the chain is protocol-capped
            // at 2^31-1 blocks (~340 years at 5 s) — an accepted protocol limit, frozen by the format.
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
                    out.add(new UncleRef(orphan.hash(), orphan.difficulty(), orphanMiner));
                }
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    /** The coinbase recipient (miner) of a block, or {@code null} if it has no coinbase. */
    private static PublicAddress blockMiner(Block block) {
        for (Transaction tx : block.transactions()) {
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
        // Cheapest-first, PoW last — the same DoS-ordering doctrine as addBlock: the memory-hard
        // verifyNonce runs only for orphans every cheap check already accepts, so a pool full of
        // already-referenced or out-of-range orphans costs map lookups, not Pufferfish2 hashes,
        // on every production round (selectUncles scans the whole orphan pool under the engine
        // lock). Pure checks only, so the verdict is unchanged by the reordering.
        int ud = uncle.difficulty();
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
        if (ctx.alreadyReferenced().contains(uncle.hash())) {
            return false; // not already credited
        }
        if (uid <= tipHeight && store.headerAt(uid).hash().equals(uncle.hash())) {
            return false; // that is the canonical block, not an orphan
        }
        // Real PoW, last — verify-once: the memory-hard hash is deterministic per header, so an
        // orphan already proven (registration, an earlier production round's scan, or a previous
        // block's uncle validation) is a cache hit instead of a fresh Pufferfish2 run. selectUncles
        // scans the whole pool under the engine lock on EVERY production round; without the cache
        // that is up to 256 Pufferfish2 hashes per candidate block.
        return verifyOrphanPowOnce(uncle, uid);
    }

    /**
     * {@code verifyNonce} with a bounded verify-once cache keyed by the header hash (see
     * {@link #verifiedOrphanPow}). Only successful verifications are cached; failures re-run
     * (they follow the same deterministic verdict, so caching them would only let junk evict
     * useful entries). Callers hold the engine lock.
     */
    private boolean verifyOrphanPowOnce(Block block, int id) {
        SHA256Hash key = block.hash();
        if (verifiedOrphanPow.containsKey(key)) {
            return true;
        }
        if (!block.verifyNonce(params.powAlgorithm(), params.powCostsAt(id))) {
            return false;
        }
        verifiedOrphanPow.put(key, Boolean.TRUE);
        return true;
    }

    /** Test hook: current size of the orphan-PoW verify-once cache (bounded-LRU assertions). */
    int verifiedOrphanPowCacheSizeForTest() {
        lock.lock();
        try {
            return verifiedOrphanPow.size();
        } finally {
            lock.unlock();
        }
    }

    private record UncleContext(java.util.Set<SHA256Hash> recentChain,
                                java.util.Set<SHA256Hash> alreadyReferenced) {}
}
