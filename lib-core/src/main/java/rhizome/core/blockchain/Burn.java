package rhizome.core.blockchain;

/**
 * The native coin burn (009-native-coin-burn, contracts/native-coin-burn.md) — one home for the
 * three quantities the rule is made of: the <b>carried debt</b>, the <b>share application</b> and
 * the resulting <b>burn</b>. The same discipline {@link Issuance} established for the minting
 * half of the identity: one formula, one home, called by every site that needs it — the supply
 * gates (via {@link SupplyGate}), the executor and the producer. Re-spelling it per call site is
 * exactly the drift the registry's "re-deriving a consensus formula per call site" anti-pattern
 * names.
 *
 * <p>This class is deliberately <b>not</b> on {@link Issuance}: {@code Issuance.minted} is
 * header-only by contract — it runs in {@code HeaderChain} before any block body exists — and
 * the pool is execution-dependent (contract {@code gasUsed}, box rent). Mixing a body-dependent
 * quantity into it would destroy that property (research.md Decision 3). It is deliberately
 * <b>not</b> on {@link EmissionCurve} (the table) or {@link NetworkParameters} (constants, not
 * block-scoped arithmetic).
 *
 * <p>The rule, all integer-only and deterministic:
 *
 * <ul>
 *   <li>{@code debt(h) = max(0, parentSupply + minted(h) − S*(h))} — the supply excess the next
 *       block may consume. Derived from the parent header and the height; no ledger read, no
 *       accumulator, no persisted state (research.md Decision 1). What a block does not burn
 *       stays in supply and reappears in the next block's debt (D-4) — so there is nothing to
 *       roll back on a reorg: a popped block's debt simply ceases to be computed (D-5).</li>
 *   <li>{@code burned(h) = min(⌊pool × βₙ / β_d⌋, debt(h))} at a curve-active height, {@code 0}
 *       elsewhere. Exactly one floor division, at {@link #applyShare} — no per-transaction
 *       rounding anywhere.</li>
 * </ul>
 *
 * <p>The recoverable-from-headers property (B-5) is what makes the whole feature
 * persistence-free: {@code burned(h) = parent.supply + minted(h) − block.supply}, so nothing is
 * committed, stored or journaled that a reorg would need to unwind.
 *
 * <p>Every sum and difference uses checked arithmetic and rejects rather than wrapping (D-7) —
 * the project's standing rule for consensus quantities.
 */
public final class Burn {

    private Burn() {
    }

    /**
     * The carried burn debt {@code debt(h)} at {@code height}: the amount by which
     * {@code parentSupply + minted(h)} exceeds the live supply target {@code S*(h)}, floored at
     * zero. {@code 0} at every height where the curve does not govern (D-2) — a chain the curve
     * never governs has no debt and burns nothing — and {@code 0} in the ordinary inflationary
     * case {@code parentSupply + minted(h) <= S*(h)} (D-3).
     *
     * <p>Pure function of the parent header and the height (D-1): the caller's {@code minted}
     * must be the {@link Issuance#minted} value for the same {@code (params, height,
     * parentSupply, difficulty, uncles)} tuple the caller already uses elsewhere — this method
     * never recomputes it, so both gates share one dispatch of the reward schedule per check.
     *
     * @throws ArithmeticException if {@code parentSupply + minted} or the subtraction overflows
     *             — a rejection, never a wrap
     */
    public static long debt(NetworkParameters params, long height, long parentSupply, long minted) {
        if (!params.emissionCurveActiveAt(height)) {
            return 0;
        }
        long ceiling = Math.addExact(parentSupply, minted);
        return Math.max(0, Math.subtractExact(ceiling, params.supplyTargetAt(height)));
    }

    /**
     * The share of {@code pool} the burn destroys before the debt clamp:
     * {@code ⌊pool × βₙ / β_d⌋}. Exactly one floor division, at this one site (B-3) — no
     * per-transaction or per-credit rounding anywhere in the feature, so per-transaction amounts
     * and every existing fee baseline are untouched. {@code pool} is the block's eligible pool
     * (every base unit credited to the miner that was not freshly minted); it is never negative
     * by construction (P-1), and the multiplication is checked so an absurd pool fails loud
     * rather than wrapping into a plausible-looking burn.
     */
    public static long applyShare(NetworkParameters params, long pool) {
        return Math.floorDiv(
            Math.multiplyExact(pool, params.burnShareNum()), params.burnShareDen());
    }

    /**
     * The burn {@code burned(h)} for a block at {@code height} whose eligible pool is
     * {@code pool} and whose carried debt is {@code debt}: the lesser of the share of the flow
     * and the carried debt — {@code min(⌊pool × βₙ / β_d⌋, debt)} — or exactly {@code 0} at any
     * height the curve does not govern (B-4), whatever the pool, the fee level or the debt.
     *
     * <p>Properties this shape guarantees by construction: {@code 0 <= burned <= debt} (B-1 —
     * the committed supply never lands below the live target {@code S*(h)}; landing exactly on
     * it is legal), and {@code burned <= pool} (B-2 — the burn is never funded from a minted
     * term, which is what keeps the miner revenue floor structurally unreachable).
     */
    public static long burned(NetworkParameters params, long height, long pool, long debt) {
        if (!params.emissionCurveActiveAt(height)) {
            return 0;
        }
        return Math.min(applyShare(params, pool), debt);
    }

    /**
     * The burn recovered from headers alone (B-5): {@code parent.supply + minted(h) −
     * block.supply}. The journal-less rollback path and any observer re-deriving the figure from
     * two headers use this — no committed field, no state, no accumulator. Checked arithmetic:
     * an inconsistency that would overflow is a rejection, never a wrap.
     */
    public static long rederive(long parentSupply, long minted, long blockSupply) {
        return Math.subtractExact(Math.addExact(parentSupply, minted), blockSupply);
    }
}
