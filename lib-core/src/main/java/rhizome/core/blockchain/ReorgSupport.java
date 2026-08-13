package rhizome.core.blockchain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import rhizome.core.block.Block;
import rhizome.crypto.SHA256Hash;
import rhizome.core.mempool.ExecutionStatus;

/**
 * The local-side mechanics of a reorg, shared by both synchronizers.
 *
 * <p>Both paths run the same sequence against the local chain: capture the branch above the
 * fork together with the GHOST total and the tip — the three facts the later fork-choice
 * comparison and a failed reorg's restore need — then pop to the fork; apply the peer branch;
 * and either adopt it or restore the captured branch exactly. The copies drifted on exactly
 * the detail that matters: the header path once captured its tip outside the view that
 * popped, so a raced producer block made the GHOST tiebreak compare against a tip that no
 * longer existed. One capture-and-pop, one restore, one local-work fold.
 *
 * <p>All calls run inside the caller's {@code withConsistentView}, so capture and pop are
 * atomic with each other and restore cannot race a producer/submit add.
 */
final class ReorgSupport {

    private ReorgSupport() {
    }

    /** The local chain state a reorg must be able to put back: captured before anything is popped. */
    record LocalBranch(BigInteger totalWork, SHA256Hash tipHash, List<Block> blocks) {}

    /**
     * Captures the local branch above {@code forkHeight} — uncle-inclusive total, tip, and the
     * blocks themselves — then pops to the fork. The capture must precede the pop: once the
     * blocks are gone, the total and tip they carried cannot be re-read, and a failed reorg
     * must restore them exactly.
     */
    static LocalBranch captureAndPop(ChainEngine engine, long forkHeight) {
        // Uncle-inclusive chain weight before we touch anything — the authoritative GHOST metric (§3.7).
        BigInteger total = engine.totalWork();
        // The tip is captured alongside the total: the GHOST vote breaks an exact-total tie by
        // comparing tip hashes, and it must compare the state as it was atomically with the pop,
        // not a tip that the producer raced past.
        SHA256Hash tip = engine.tipHash();
        List<Block> branch = new ArrayList<>();
        for (long h = forkHeight + 1; h <= engine.height(); h++) {
            branch.add(engine.blockAt(h));
        }
        while (engine.height() > forkHeight) {
            engine.popBlock();
        }
        return new LocalBranch(total, tip, branch);
    }

    /**
     * Puts the captured local branch back after a rejected reorg: pop whatever the failed apply
     * left, then re-add each captured block with {@code restoreBlock} — not {@code addBlock}:
     * these blocks were canonical here, so their uncle refs were already fully validated;
     * re-checking them against the orphan pool would let a hostile peer that churned the pool
     * (evicting a referenced uncle) turn a rejected reorg into a forced full resync (audit V5).
     * Any OTHER failure is still a genuine invariant breach: re-adding a just-canonical block
     * must otherwise succeed, so it fails loud (degraded marker + exception) instead of
     * silently leaving the node permanently shorter (audit: restore self-truncation) — the
     * marker clears once a restore fully succeeds.
     */
    static void restore(ChainEngine engine, long forkHeight, List<Block> localBranch) {
        while (engine.height() > forkHeight) {
            engine.popBlock();
        }
        for (Block block : localBranch) {
            ExecutionStatus status = engine.restoreBlock(block);
            if (status != ExecutionStatus.SUCCESS) {
                String reason = "failed to restore local branch at " + block.id()
                    + " after a rejected reorg: " + status + " — a full resync is required";
                engine.markDegraded(reason, false); // a later full restore genuinely heals this
                throw new IllegalStateException(reason);
            }
        }
        engine.clearDegraded(); // the local branch is whole again
    }

    /**
     * Local PoW above the fork, base work only — the symmetric counterpart of the branch total
     * {@link HeaderChain#validate} returns (each block's own {@code 2^difficulty}, deliberately NOT
     * the uncle work, audit M4: committed uncle refs are unverified at this stateless stage, so
     * counting them would let a cheap branch inflate its claimed work ~3× and force a pop/restore
     * cycle before the fakes are rejected). Read from HEADERS, not bodies (audit F4), so a pruned
     * node whose fork sits below the watermark answers the gate instead of throwing.
     */
    static BigInteger localWorkAboveFork(ChainEngine engine, long forkHeight) {
        BigInteger work = BigInteger.ZERO;
        for (long h = forkHeight + 1; h <= engine.height(); h++) {
            work = work.add(BlockWork.of(engine.headerAt(h).difficulty()));
        }
        return work;
    }
}
