package rhizome.core.blockchain;

import java.util.List;

import rhizome.core.block.UncleRef;

/**
 * The single formula for how much native supply a block mints (§ supply header commitment):
 * the scheduled mining reward, plus the work-scaled uncle and nephew rewards earned by the
 * block's referenced uncles.
 *
 * <p>Shared by three call sites — {@code ChainEngine.addBlock}'s structural accounting check,
 * {@code HeaderChain.validate}'s stateless header-only gate, and {@code BlockAssembler}'s
 * producer stamping — so the identity has exactly ONE home. Re-spelling it per call site is the
 * exact drift the shared {@code HeaderWire} prefix was built to avoid (see that class's
 * Javadoc); this is the same lesson applied to the accounting identity.
 *
 * <p><b>Reads the SCHEDULED reward, not the block body's actual coinbase amount</b>
 * (research.md Decision 2): {@code Executor} already enforces {@code coinbase ==
 * miningReward(h)} exactly ({@code INCORRECT_MINING_FEE}), so composing that check with this
 * scheduled-form identity is equivalent to reading the real coinbase — and, decisively, the
 * scheduled form is the only one that can run from headers alone in {@link HeaderChain}, before
 * any block body is downloaded.
 *
 * <p>Delegates every term to the existing reward math ({@link NetworkParameters#miningReward(long, long)},
 * {@link NetworkParameters#uncleReward(long, long)}, {@link NetworkParameters#nephewReward(long, long)},
 * {@link Executor#scaleRewardToWork}) rather than reimplementing it, and sums with
 * {@link Math#addExact} so a misconfigured or adversarial input fails loud instead of wrapping
 * silently (the project's checked-arithmetic convention — see {@code NetworkParameters}'
 * {@code multiplyExact} uses).
 */
public final class Issuance {

    private Issuance() {
    }

    /**
     * Total native supply minted by a block at {@code height}: the scheduled mining reward, plus
     * the work-scaled uncle reward and nephew bonus for each referenced uncle. The per-uncle
     * scaling is byte-for-byte the same computation {@code Executor.payUncleRewards} applies when
     * the block is actually executed (deficit = {@code nephewDifficulty - uncle.difficulty()},
     * scaled by {@link Executor#scaleRewardToWork}) — computed here from headers alone, so the
     * identical result can be checked before any body is downloaded.
     *
     * @param params           network parameters (reward schedule)
     * @param height           the minting block's height
     * @param parentSupply     the parent block's committed circulating supply — the curve's sole
     *                         input when active; ignored by the dispatch when the curve is
     *                         inactive at {@code height}
     * @param nephewDifficulty the minting (nephew) block's own difficulty
     * @param uncles           the block's referenced uncles (empty or {@code null} ⇒ no uncle terms)
     */
    public static long minted(NetworkParameters params, long height, long parentSupply,
            int nephewDifficulty, List<UncleRef> uncles) {
        long total = params.miningReward(height, parentSupply);
        if (uncles == null || uncles.isEmpty()) {
            return total;
        }
        long baseUncleReward = params.uncleReward(height, parentSupply);
        long baseNephewReward = params.nephewReward(height, parentSupply);
        for (UncleRef uncle : uncles) {
            int deficit = nephewDifficulty - uncle.difficulty();
            total = Math.addExact(total, Executor.scaleRewardToWork(baseUncleReward, deficit));
            total = Math.addExact(total, Executor.scaleRewardToWork(baseNephewReward, deficit));
        }
        return total;
    }
}
