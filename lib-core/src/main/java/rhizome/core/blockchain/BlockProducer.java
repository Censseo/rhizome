package rhizome.core.blockchain;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

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

    private final ChainEngine engine;
    private final MemPool mempool;
    private final PublicAddress miner;
    private final LongSupplier nowMillis;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public BlockProducer(ChainEngine engine, MemPool mempool, PublicAddress miner, LongSupplier nowMillis) {
        this.engine = engine;
        this.mempool = mempool;
        this.miner = miner;
        this.nowMillis = nowMillis;
    }

    /**
     * Assembles, mines and applies one block. Returns the applied block, or
     * empty if the chain rejected it (e.g. a peer block raced in and changed the
     * tip — the next call rebuilds on the new tip).
     */
    public Optional<Block> produce() {
        Block candidate = BlockAssembler.assemble(engine, mempool, miner, nowMillis.getAsLong());
        var block = (BlockImpl) candidate;
        block.nonce(Miner.mineNonce(block.hash(), block.difficulty(), engine.params().powAlgorithm()));

        ExecutionStatus status = engine.addBlock(block);
        if (status != ExecutionStatus.SUCCESS) {
            return Optional.empty();
        }
        mempool.onBlockApplied(block);
        return Optional.of(block);
    }

    /** Starts a background mining loop. Idempotent. */
    public void start() {
        if (running.compareAndSet(false, true)) {
            thread = new Thread(this::loop, "block-producer");
            thread.setDaemon(true);
            thread.start();
        }
    }

    public void stop() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private void loop() {
        while (running.get()) {
            try {
                produce();
            } catch (RuntimeException e) {
                // A produce failure (e.g. transient store error) must not kill the loop.
                if (!running.get()) {
                    break;
                }
            }
        }
    }
}
