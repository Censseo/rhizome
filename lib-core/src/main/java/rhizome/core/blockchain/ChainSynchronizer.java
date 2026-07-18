package rhizome.core.blockchain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.common.Constants;
import rhizome.core.crypto.SHA256Hash;
import rhizome.core.mempool.ExecutionStatus;

/**
 * Synchronises the local chain toward a peer, adopting the peer's chain only if
 * it has strictly greater cumulative work — the objective fork-choice rule
 * (Pandanite forked repeatedly for lack of one).
 *
 * <p>Hardened against hostile peers:
 * <ul>
 *   <li><b>Finality window</b> — a reorg deeper than
 *       {@code NetworkParameters.maxReorgDepth} is refused outright; buried
 *       blocks cannot be rewritten no matter the claimed work.</li>
 *   <li><b>No free rollbacks</b> — before any local state is touched, a bounded
 *       prefix of the peer branch is fetched and validated statelessly:
 *       id continuity, hash chaining from the fork point, per-block
 *       proof-of-work, and total verified work strictly above our own branch.
 *       A peer that merely <em>claims</em> huge work can therefore cost us a
 *       bounded download and some hash checks — never a pop/restore cycle.
 *       Passing this gate requires actually spending more PoW than our chain
 *       carries.</li>
 *   <li><b>Restore on failure</b> — if the stateful apply still fails (e.g.
 *       wrong derived difficulty), the local chain is restored exactly.</li>
 * </ul>
 */
public final class ChainSynchronizer {

    public enum Result { NO_CHANGE, EXTENDED, REORGED, REORG_TOO_DEEP, INCOMPATIBLE, PEER_INVALID }

    /** Extra blocks fetched beyond the fork depth during pre-validation. */
    static final int PREFETCH_EXTRA = 2 * Constants.BLOCKS_PER_FETCH;

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
        long depth = engine.height() - forkHeight;
        if (depth > engine.params().maxReorgDepth()) {
            return Result.REORG_TOO_DEEP;
        }

        // --- Stateless gate: no local mutation until the peer has PROVEN more work ---
        long prefetchEnd = Math.min(peer.height(), forkHeight + depth + PREFETCH_EXTRA);
        List<Block> branch = fetchRange(peer, forkHeight + 1, prefetchEnd);
        if (branch == null || !branchChainsFromFork(branch, forkHeight)) {
            return Result.PEER_INVALID;
        }
        if (verifiedWork(branch).compareTo(localWorkAboveFork(forkHeight)) <= 0) {
            return Result.PEER_INVALID; // claimed heavy, proved light
        }

        // --- Stateful apply, with exact restore on failure ---
        List<Block> localBranch = new ArrayList<>();
        for (long h = forkHeight + 1; h <= engine.height(); h++) {
            localBranch.add(engine.blockAt(h));
        }
        while (engine.height() > forkHeight) {
            engine.popBlock();
        }

        for (Block block : branch) {
            if (engine.addBlock(block) != ExecutionStatus.SUCCESS) {
                restore(forkHeight, localBranch);
                return Result.PEER_INVALID;
            }
        }
        // Branch prefix applied and (by the gate) already heavier than what it
        // replaced; keep extending toward the peer tip, best effort.
        applyRange(peer, prefetchEnd + 1, peer.height());
        return Result.REORGED;
    }

    /** Fetches [from..to] in bounded batches; null on any transport/decode failure. */
    private List<Block> fetchRange(PeerSource peer, long from, long to) {
        List<Block> out = new ArrayList<>();
        try {
            for (long start = from; start <= to; start += Constants.BLOCKS_PER_FETCH) {
                long end = Math.min(to, start + Constants.BLOCKS_PER_FETCH - 1);
                out.addAll(peer.blocks(start, end));
            }
        } catch (RuntimeException e) {
            return null;
        }
        return out;
    }

    /** Stateless: ids contiguous from the fork, hashes chain, every PoW nonce holds. */
    private boolean branchChainsFromFork(List<Block> branch, long forkHeight) {
        if (branch.isEmpty()) {
            return false;
        }
        SHA256Hash prevHash = engine.blockAt(forkHeight).hash();
        long expectedId = forkHeight + 1;
        for (Block block : branch) {
            var b = (BlockImpl) block;
            if (b.id() != expectedId || !b.lastBlockHash().equals(prevHash)
                || !block.verifyNonce(engine.params().powAlgorithm())) {
                return false;
            }
            prevHash = block.hash();
            expectedId++;
        }
        return true;
    }

    private static BigInteger verifiedWork(List<Block> branch) {
        BigInteger work = BigInteger.ZERO;
        for (Block block : branch) {
            work = work.add(BigInteger.TWO.pow(((BlockImpl) block).difficulty()));
        }
        return work;
    }

    private BigInteger localWorkAboveFork(long forkHeight) {
        BigInteger work = BigInteger.ZERO;
        for (long h = forkHeight + 1; h <= engine.height(); h++) {
            work = work.add(BigInteger.TWO.pow(((BlockImpl) engine.blockAt(h)).difficulty()));
        }
        return work;
    }

    private void restore(long forkHeight, List<Block> localBranch) {
        while (engine.height() > forkHeight) {
            engine.popBlock();
        }
        for (Block block : localBranch) {
            engine.addBlock(block);
        }
    }
}
