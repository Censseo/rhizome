package rhizome.core.blockchain;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.mempool.MemPool;

/**
 * Produces blocks: assemble a candidate from the mempool, solve the
 * proof-of-work, apply it to the chain, and purge the included transactions.
 *
 * <p>{@link #produce()} makes one block synchronously (mining can be slow under
 * Pufferfish2). {@link #start()} runs a background loop for a self-mining node;
 * the loop re-reads the tip each round, so a block arriving from a peer between
 * rounds simply makes the next candidate build on the newer tip.
 */
public final class BlockProducer {

    private static final Logger log = LoggerFactory.getLogger(BlockProducer.class);

    /**
     * Minimum wall-clock time between "produce failed" log lines. At trivial difficulty (or
     * with pacing disabled) the loop can spin thousands of failing rounds per minute — the
     * failure must be diagnosable (audit: silent producer loop) without flooding the log.
     */
    private static final long PRODUCE_FAILURE_LOG_INTERVAL_MS = 60_000L;

    private final ChainEngine engine;
    private final MemPool mempool;
    private final PublicAddress miner;
    private final LongSupplier nowMillis;
    private final long targetIntervalMs;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Consumer<Block> onProduced;
    private volatile int vote = VoteableParams.ABSTAIN;
    private Thread thread;

    public BlockProducer(ChainEngine engine, MemPool mempool, PublicAddress miner, LongSupplier nowMillis) {
        this(engine, mempool, miner, nowMillis, 0L);
    }

    /**
     * @param targetIntervalMs minimum wall-clock time between produced blocks. Paces the
     *     loop so trivial proof-of-work (low difficulty) does not spin out thousands of
     *     blocks; at real difficulty the PoW itself dominates. 0 disables pacing.
     */
    public BlockProducer(ChainEngine engine, MemPool mempool, PublicAddress miner,
                         LongSupplier nowMillis, long targetIntervalMs) {
        this.engine = engine;
        this.mempool = mempool;
        this.miner = miner;
        this.nowMillis = nowMillis;
        this.targetIntervalMs = targetIntervalMs;
    }

    /**
     * Assembles, mines and applies one block. Returns the applied block, or
     * empty if the chain rejected it (e.g. a peer block raced in and changed the
     * tip — the next call rebuilds on the new tip).
     */
    public Optional<Block> produce() {
        // A non-atomic reorg window is open (headers-first sync): the chain may sit truncated at a
        // fork height, and a block mined on it would be refused (or destroyed by the restore).
        // Stand down for the round rather than burning PoW on a candidate that cannot land —
        // addBlock's reorg-window guard closes the residual race. Likewise a DEGRADED engine
        // (failed post-pop peripheral revert or failed restore): local state is suspect, addBlock
        // would refuse the block with NODE_DEGRADED anyway, and mining on it would waste the PoW.
        if (engine.isReorgInProgress() || engine.isDegraded()) {
            return Optional.empty();
        }
        Block candidate = BlockAssembler.assemble(engine, mempool, miner, nowMillis.getAsLong());
        var block = (BlockImpl) candidate;
        block.vote(vote); // the miner's parameter vote (ABSTAIN by default)
        // The stamp dry-run commits BOTH header values — the exact (burn-aware) supply and, when
        // the accumulator is on, the state root — from one execution of this candidate, before
        // the PoW binds them into the header hash (009 T051: assemble -> dry-run -> stamp -> mine).
        engine.stampStateRoot(block);
        block.nonce(Miner.mineNonce(block.hash(), block.difficulty(), engine.params().powAlgorithm(),
            engine.params().powCostsAt(engine.height() + 1)));

        ExecutionStatus status = engine.addBlock(block);
        if (status != ExecutionStatus.SUCCESS) {
            return Optional.empty();
        }
        mempool.onBlockApplied(block);
        Consumer<Block> listener = onProduced;
        if (listener != null) {
            listener.accept(block);
        }
        return Optional.of(block);
    }

    /** Sets a listener called with each block this producer mines (e.g. to gossip it). */
    public void setOnProduced(Consumer<Block> listener) {
        this.onProduced = listener;
    }

    /**
     * Sets the parameter vote this miner casts on each block it produces (§ miner voting):
     * {@code 0} abstains, {@code ±1} votes on {@code storageFeeFactor}, {@code ±2} on
     * {@code minValuePerByte}. Default abstain. Out-of-range values are rejected: a block
     * carrying one is refused by every node's consensus gate (audit F1), so producing it
     * would waste the mined work.
     */
    public void setVote(int vote) {
        // Canonical votes are 0 or ±paramId (VoteableParams 1/2) — the same bound the codecs and
        // ChainEngine.addBlock enforce. Long abs guards Integer.MIN_VALUE.
        if (Math.abs((long) vote) > 2) {
            throw new IllegalArgumentException("vote out of range: " + vote);
        }
        this.vote = vote;
    }

    /** Starts a background mining loop. Idempotent. */
    public void start() {
        if (running.compareAndSet(false, true)) {
            thread = new Thread(this::loop, "block-producer");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /**
     * Stops the mining loop and waits for the producer thread to exit, so that no
     * mining is in flight (i.e. no {@link ChainEngine#addBlock} touching the store)
     * once this returns — callers may then safely close the underlying storage.
     */
    public void stop() {
        running.set(false);
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private void loop() {
        long lastFailureLogMs = Long.MIN_VALUE;
        while (running.get()) {
            long start = nowMillis.getAsLong();
            try {
                produce();
            } catch (RuntimeException e) {
                // A produce failure (e.g. transient store error) must not kill the loop — but it
                // must not be invisible either: a miner failing every round used to die (or spin)
                // silently, indistinguishable from healthy idle (audit: silent producer loop).
                // Rate-limited: at trivial difficulty the loop can spin thousands of failing
                // rounds per minute. The injected clock drives the limit so tests stay
                // deterministic; the first failure always logs.
                if (!running.get()) {
                    break;
                }
                long now = nowMillis.getAsLong();
                if (lastFailureLogMs == Long.MIN_VALUE
                    || now - lastFailureLogMs >= PRODUCE_FAILURE_LOG_INTERVAL_MS) {
                    lastFailureLogMs = now;
                    log.warn("block production failed; the producer loop continues", e);
                }
            }
            long elapsed = nowMillis.getAsLong() - start;
            long sleep = targetIntervalMs - elapsed;
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}
