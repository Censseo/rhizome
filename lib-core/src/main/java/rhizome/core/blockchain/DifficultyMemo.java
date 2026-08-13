package rhizome.core.blockchain;

import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.LongFunction;

import rhizome.core.block.BlockHeader;
import rhizome.core.blockchain.HeaderChain.DifficultyCheckpoint;

/**
 * The retarget memo the two synchronizers share across sync rounds (audit: O(height)
 * difficulty-fold DoS).
 *
 * <p>{@code HeaderChain.validate} used to refold the difficulty from genesis before the first
 * PoW check on every call — O(forkHeight) header reads per round, so a long chain made each
 * sync round (and each hostile "I'm heavier" probe) pay a genesis-length walk. Entries are
 * self-invalidating by boundary-header hash (see {@link DifficultyCheckpoint}), so a reorg or
 * a losing branch can never leave a wrong-chain value behind. Each synchronizer kept its own
 * copy of the map, the monitor guard and the post-validate trim; sharing the type keeps the
 * memo policy — validate under the memo's monitor, then drop everything at or below the fork —
 * in one place.
 */
final class DifficultyMemo {

    private final NavigableMap<Long, DifficultyCheckpoint> memo = new TreeMap<>();

    /**
     * Validates {@code candidates} through {@link HeaderChain#validate} with the memo, then
     * trims entries at or below {@code forkHeight}. Those are ancient history a later round
     * re-derives in O(1) from a newer checkpoint, so dropping them costs nothing (audit
     * follow-up) and keeps the map bounded instead of growing one entry per retarget boundary
     * for the process lifetime.
     */
    synchronized HeaderChain.Result validate(NetworkParameters params,
                                             LongFunction<BlockHeader> trustedHeaderAt,
                                             long forkHeight, List<BlockHeader> candidates,
                                             long nowMillis) {
        HeaderChain.Result result =
            HeaderChain.validate(params, trustedHeaderAt, forkHeight, candidates, nowMillis, memo);
        memo.headMap(forkHeight, true).clear();
        return result;
    }
}
