package rhizome.core.blockchain;

import java.util.ArrayList;
import java.util.List;

import rhizome.core.block.Block;
import rhizome.core.common.Constants;
import rhizome.core.mempool.ExecutionStatus;

/**
 * Synchronises the local chain toward a peer, adopting the peer's chain only if
 * it has strictly greater cumulative work — the objective fork-choice rule
 * (Pandanite forked repeatedly for lack of one).
 *
 * <p>Handles both cases:
 * <ul>
 *   <li><b>Catch-up</b>: peer extends our tip — apply its new blocks in bounded
 *       batches.</li>
 *   <li><b>Reorg</b>: peer diverges — find the common ancestor, roll back to it,
 *       apply the peer branch. If any peer block fails to validate, the local
 *       chain is restored to exactly its prior state (a lying peer cannot leave
 *       us worse off).</li>
 * </ul>
 */
public final class ChainSynchronizer {

    public enum Result { NO_CHANGE, EXTENDED, REORGED, REJECTED_LIGHTER, INCOMPATIBLE, PEER_INVALID }

    private final ChainEngine engine;

    public ChainSynchronizer(ChainEngine engine) {
        this.engine = engine;
    }

    public Result syncFrom(PeerSource peer) {
        if (peer.totalWork().compareTo(engine.totalWork()) <= 0) {
            return Result.NO_CHANGE;
        }

        long forkHeight = findCommonAncestor(peer);
        if (forkHeight < GenesisBlock.GENESIS_ID) {
            return Result.INCOMPATIBLE; // genesis mismatch: different network
        }

        if (forkHeight == engine.height()) {
            return applyRange(peer, forkHeight + 1, peer.height()) ? Result.EXTENDED : Result.PEER_INVALID;
        }
        return reorg(peer, forkHeight);
    }

    /** Highest height at which our block and the peer's agree, walking down from the shared tip. */
    private long findCommonAncestor(PeerSource peer) {
        long h = Math.min(engine.height(), peer.height());
        while (h >= GenesisBlock.GENESIS_ID) {
            if (engine.blockAt(h).hash().equals(peer.blockHash(h))) {
                return h;
            }
            h--;
        }
        return GenesisBlock.GENESIS_ID - 1; // no common block, not even genesis
    }

    private boolean applyRange(PeerSource peer, long from, long to) {
        for (long start = from; start <= to; start += Constants.BLOCKS_PER_FETCH) {
            long end = Math.min(to, start + Constants.BLOCKS_PER_FETCH - 1);
            for (Block block : peer.blocks(start, end)) {
                if (engine.addBlock(block) != ExecutionStatus.SUCCESS) {
                    return false;
                }
            }
        }
        return true;
    }

    private Result reorg(PeerSource peer, long forkHeight) {
        // Snapshot the local branch above the fork so we can restore it on failure.
        List<Block> localBranch = new ArrayList<>();
        for (long h = forkHeight + 1; h <= engine.height(); h++) {
            localBranch.add(engine.blockAt(h));
        }

        while (engine.height() > forkHeight) {
            engine.popBlock();
        }

        if (applyRange(peer, forkHeight + 1, peer.height())
            && engine.totalWork().compareTo(worthBeating(localBranch, forkHeight)) > 0) {
            return Result.REORGED;
        }

        // Peer branch invalid or not actually heavier: roll back to the fork and
        // restore the original local branch.
        while (engine.height() > forkHeight) {
            engine.popBlock();
        }
        for (Block block : localBranch) {
            engine.addBlock(block);
        }
        return Result.PEER_INVALID;
    }

    /** Cumulative work the restored local branch would have had (for the sanity check). */
    private java.math.BigInteger worthBeating(List<Block> localBranch, long forkHeight) {
        java.math.BigInteger work = engineWorkAt(forkHeight);
        for (Block block : localBranch) {
            work = work.add(java.math.BigInteger.TWO.pow(((rhizome.core.block.BlockImpl) block).difficulty()));
        }
        return work;
    }

    private java.math.BigInteger engineWorkAt(long height) {
        java.math.BigInteger work = java.math.BigInteger.ZERO;
        for (long h = GenesisBlock.GENESIS_ID + 1; h <= height; h++) {
            work = work.add(java.math.BigInteger.TWO.pow(((rhizome.core.block.BlockImpl) engine.blockAt(h)).difficulty()));
        }
        return work;
    }
}
