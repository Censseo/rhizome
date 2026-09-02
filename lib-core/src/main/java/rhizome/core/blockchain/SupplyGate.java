package rhizome.core.blockchain;

import java.util.List;

import rhizome.core.block.BlockImpl;
import rhizome.core.block.UncleRef;

/**
 * The ONE supply rule (009-native-coin-burn, data-model.md §6): the remedy registry open item
 * <b>OI-4</b> itself names. Since 002 the same rule has lived byte-for-byte duplicated in three
 * places — {@code ChainEngine.checkSupply} (returns {@code ExecutionStatus}),
 * {@code HeaderChain.checkSupply} (returns {@code Rejection}) and
 * {@code checkEmissionScheduleConsistency} rule 6 (throws) — and this feature must change that
 * rule. Changing it three times is precisely the drift the shared-formula discipline (ADR-005)
 * exists to prevent, so the three call sites collapse onto this one gate, which returns a
 * <b>neutral verdict</b> each caller maps to its own failure surface (S-5): the engine keeps
 * {@code INVALID_SUPPLY}, header sync keeps {@code Rejection.INVALID_SUPPLY}, and the boot check
 * keeps its refusal message.
 *
 * <p><b>Header-only</b> (S-1): the inputs are exactly the parent header, the block/header
 * under judgment and {@code params} — no body, no ledger state — so the gate stays in the
 * pre-PoW structural pass of {@code ChainEngine.addBlock} and before any body fetch in
 * {@code HeaderChain.validate} (S-2, the WHITEPAPER §3.5 DoS-armor ordering). The inflation
 * direction is enforced here: nothing that would mint supply without matching issuance can get
 * past this gate, before a single hash is verified.
 *
 * <p><b>Checked arithmetic</b> (S-6): every sum is {@link Math#addExact} — an overflowing
 * identity can never be satisfied by any legal {@code long}, so it is reported as
 * {@link Verdict#OVERFLOW} (a rejection), never wrapped into a value that happens to match.
 */
public final class SupplyGate {

    /** The neutral verdict each caller maps to its own outcome (OI-4's named remedy). */
    public enum Verdict {
        /** The committed supply satisfies the rule. */
        OK,
        /** Parent commits no supply but the block commits one anyway (or vice versa across
         *  the rule: parent absent forces block absent — FR-004 prefix closure). */
        ABSENT_MISMATCH,
        /** A negative committed supply — below the absent sentinel, never a legal value. */
        NEGATIVE,
        /** The accounting identity overflows; no legal long can satisfy it. */
        OVERFLOW,
        /** The committed supply is outside the bound the rule allows. */
        OUT_OF_BOUND
    }

    private SupplyGate() {
    }

    /**
     * The supply rule for a block at {@code height} committing {@code blockSupply} on top of a
     * parent committing {@code parentSupply}.
     *
     * <p><b>The rule (T031, FR-015): the bound</b> {@code 0 <= burned <= debt(h)}, where
     * {@code burned = parentSupply + minted(h) − blockSupply} is what the block is claiming to
     * have destroyed and {@code debt(h)} is the carried excess {@link Burn#debt} computes. Two
     * properties carry the design:
     *
     * <ul>
     *   <li><b>The inflation direction is unchanged</b> (S-4): {@code burned >= 0} is exactly
     *       today's {@code blockSupply <= parentSupply + minted(h)} — no block can over-mint
     *       past this gate, still before PoW.</li>
     *   <li><b>The bound collapses to exact equality wherever no burn is possible</b> (S-3):
     *       {@code debt(h) == 0} at every pre-activation height, on every profile that never
     *       schedules the curve, and at every curve-active height at or below the live target —
     *       and there {@code 0 <= burned <= 0} forces {@code blockSupply == ceiling}, byte for
     *       byte the pre-feature rule.</li>
     * </ul>
     *
     * <p>Only where a burn is actually possible does the equality relax into the bound — the
     * exact figure is enforced post-execution in {@code Executor} (the pool is not known before
     * the block runs), mirroring the {@code stateRoot} precedent. This relaxation is the one
     * documented deviation in the feature's Complexity Tracking.
     *
     * @param params           network parameters (the reward schedule)
     * @param height           the block's height
     * @param parentSupply     the parent header's committed supply ({@code -1} = absent)
     * @param blockSupply      the block's committed supply ({@code -1} = absent)
     * @param nephewDifficulty the block's own difficulty
     * @param uncles           the block's referenced uncles (empty or {@code null} allowed)
     */
    public static Verdict check(NetworkParameters params, long height, long parentSupply,
            long blockSupply, int nephewDifficulty, List<UncleRef> uncles) {
        if (parentSupply == BlockImpl.SUPPLY_ABSENT) {
            // Prefix closure (FR-004): a parent that commits no supply forces the block to
            // commit none too — a mid-chain start is rejected exactly like a dropped commitment.
            return blockSupply == BlockImpl.SUPPLY_ABSENT ? Verdict.OK : Verdict.ABSENT_MISMATCH;
        }
        if (blockSupply < 0) {
            // Parent committed: dropping the commitment (or any value below the absent sentinel,
            // which decode-time bounds already reject on every wire ingress path) is invalid too.
            return Verdict.NEGATIVE;
        }
        long minted;
        try {
            minted = Issuance.minted(params, height, parentSupply, nephewDifficulty, uncles);
        } catch (ArithmeticException overflow) {
            return Verdict.OVERFLOW;
        }
        long burned;
        try {
            long ceiling = Math.addExact(parentSupply, minted);
            burned = Math.subtractExact(ceiling, blockSupply);
        } catch (ArithmeticException overflow) {
            return Verdict.OVERFLOW;
        }
        long debt = Burn.debt(params, height, parentSupply, minted);
        return burned >= 0 && burned <= debt ? Verdict.OK : Verdict.OUT_OF_BOUND;
    }
}
