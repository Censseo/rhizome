package rhizome.node;

import io.activej.http.HttpResponse;

import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.Burn;
import rhizome.core.blockchain.Issuance;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SupplyTargetSchedule;
import rhizome.core.serialization.JsonSink;
import rhizome.core.serialization.JsonSink.Key;

import static rhizome.node.ApiResponses.json;

/**
 * The emission-observability readouts (007-emission-observability; 008-decaying-supply-target):
 * the shared {@code emission} response fragment that {@code GET /info} and {@code GET /stats}
 * both nest, and the schedule read {@code GET /emission}.
 *
 * <p>The fragment has exactly one writer so the two surfaces cannot drift (spec FR-013). Every
 * monetary value is a decimal string in <b>base units</b>; an absent committed supply stays
 * JSON {@code null} and is never coerced to {@code "0"} (zero is a legal committed value —
 * node-api DI-7's distinction). Figures the chain's state cannot support are {@code null}
 * rather than zeroed: a chain the curve does not govern is not converging on the target.
 *
 * <p>008: {@code target} is the <b>live</b> {@code S*(h)} for the next block, not the peak —
 * a consumer that keeps reading {@code target} must get the number that actually governs the
 * next block, so the peak is what moved to the new {@code peakTarget} name (contracts/
 * emission-api.md §1). {@code distanceToTarget} and {@code progressBps} are computed against the
 * live target and may be negative / exceed 10 000 respectively; {@code obligation} publishes the
 * block's derived burn obligation — never a cumulative figure (research.md Decision 4).
 *
 * <p>Bodies are written with {@link JsonSink} instead of an {@code org.json} tree — see that
 * class's Javadoc.
 */
final class EmissionApi {

    /** The rule strings of the emission fragment and schedule. */
    static final String RULE_CURVE = "curve";
    static final String RULE_GEOMETRIC = "geometric";

    // -- JsonSink field keys, pre-encoded once per call site (see JsonSink's class Javadoc) ----
    private static final Key K_EMISSION = Key.of("emission");
    private static final Key K_RULE = Key.of("rule");
    private static final Key K_ACTIVATION_HEIGHT = Key.of("activationHeight");
    private static final Key K_SUPPLY = Key.of("supply");
    private static final Key K_SUBSIDY = Key.of("subsidy");
    private static final Key K_TARGET = Key.of("target");
    private static final Key K_PEAK_TARGET = Key.of("peakTarget");
    private static final Key K_DECAY_START_HEIGHT = Key.of("decayStartHeight");
    private static final Key K_DISTANCE_TO_TARGET = Key.of("distanceToTarget");
    private static final Key K_PROGRESS_BPS = Key.of("progressBps");
    private static final Key K_OBLIGATION = Key.of("obligation");
    private static final Key K_FLOOR = Key.of("floor");
    private static final Key K_BURNED = Key.of("burned");
    private static final Key K_BURN_DEBT = Key.of("burnDebt");
    private static final Key K_DECIMAL_SCALE_FACTOR = Key.of("decimalScaleFactor");

    /** One fragment is fourteen fields, longest value 13 digits — a ~420-byte allocation hint
     *  (raised from 512 by 009's burnDebt field; contracts/emission-fragment.md §1). */
    static final int EMISSION_SIZE_HINT = 512;


    /** How many points the published curve is sampled at: a quarter of the generated table's
     *  256 steps — enough to draw a smooth logarithm, small enough for a ~3 KB once-fetched
     *  payload (data-model §samples). */
    static final int SAMPLE_COUNT = 64;

    // -- schedule keys (the /emission payload) --------------------------------------------------
    private static final Key K_NETWORK = Key.of("network");
    private static final Key K_SUPPLY_TARGET = Key.of("supplyTarget");
    private static final Key K_COEFFICIENT = Key.of("coefficient");
    private static final Key K_STEPS = Key.of("steps");
    private static final Key K_GENESIS_SUPPLY = Key.of("genesisSupply");
    private static final Key K_SAMPLES = Key.of("samples");
    private static final Key K_DECAY = Key.of("decay");
    private static final Key K_SAMPLE_HEIGHT = Key.of("sampleHeight");
    private static final Key K_START_HEIGHT = Key.of("startHeight");
    private static final Key K_EPOCH_BLOCKS = Key.of("epochBlocks");
    private static final Key K_NUM = Key.of("num");
    private static final Key K_DEN = Key.of("den");
    private static final Key K_TARGET_FLOOR = Key.of("targetFloor");
    private static final Key K_BURN_SHARE_NUM = Key.of("burnShareNum");
    private static final Key K_BURN_SHARE_DEN = Key.of("burnShareDen");
    private static final Key K_EPOCHS_TO_FLOOR = Key.of("epochsToFloor");
    private static final Key K_FLOOR_ARRIVAL_HEIGHT = Key.of("floorArrivalHeight");
    private static final Key K_PER_EPOCH_REDUCTION_BOUND = Key.of("perEpochReductionBound");

    /** Base object plus 64 two-field samples of two decimal strings each. */
    private static final int SCHEDULE_SIZE_HINT = 4096;

    private EmissionApi() {}

    /**
     * The decay-epoch index the /emission memo is keyed by: {@code 0} on any height before the
     * decay start (including every height on a profile that never schedules one, so the index —
     * and therefore the memo — is constant there, exactly 007's behaviour), and the count of
     * completed decay epochs once decaying. Pure arithmetic on {@code (params, height)}.
     */
    static long decayEpochIndexOf(NetworkParameters params, long height) {
        return params.supplyTargetSchedule().epochIndexAt(height);
    }

    /**
     * One consistent chain view of the tip: everything {@link #writeEmissionFragment} needs to
     * publish the tip block's destroyed amount and the next block's carried debt. Sourced from
     * ONE compound acquisition — {@code ChainEngine.tipSupply()} on /info, the per-tip
     * {@code StatsWindow} cache on /stats — so the fragment can never straddle a reorg, and
     * neither route adds a lock.
     *
     * @param height        the tip height
     * @param tipSupply     the tip header's committed supply ({@code -1} = absent)
     * @param parentSupply  the parent header's committed supply ({@code -1} = absent or genesis)
     * @param tipDifficulty the tip block's own difficulty (an {@code Issuance.minted} input)
     * @param tipUncles     the tip block's referenced uncles (an {@code Issuance.minted} input)
     */
    record TipView(long height, long tipSupply, long parentSupply, int tipDifficulty,
                   java.util.List<rhizome.core.block.UncleRef> tipUncles) {}

    /**
     * Writes the {@code emission} object into {@code sink} from {@code (params, tip)}. Callers
     * source the view from ONE chain view — {@code ChainEngine.tipSupply()} on /info, the
     * per-tip {@code StatsWindow} on /stats — so the fragment can never straddle a reorg.
     *
     * <p>{@code subsidy} is the <b>next</b> block's subsidy, dispatched through the consensus
     * dispatch {@link NetworkParameters#miningReward(long, long)}: the value the response's own
     * {@code supply} field determines, verifiable from this response alone. On a chain that
     * commits no supply the one-arg geometric form is used — the same fallback the two-arg form
     * itself makes, made explicit here so the absent sentinel never reaches the curve.
     *
     * <p>{@code target} is the live {@code S*(h)} the next block's dispatch actually measures
     * against ({@link NetworkParameters#supplyTargetAt(long)} — the same value the dispatch
     * reads, so the published figure cannot disagree with the minted one). {@code obligation} is
     * the next block's derived burn obligation: {@code "0"} when the curve governs and the raw
     * value is non-negative, positive when the target has fallen below supply, and {@code null}
     * when the curve does not govern (a chain the curve does not govern has no obligation —
     * figures the chain's state cannot support are null rather than zeroed).
     *
     * <p>009 (FR-023..FR-025): {@code burned} is the <b>tip block's</b> destroyed amount —
     * {@code parent.supply + minted(tip) − tip.supply}, recovered from the two headers via
     * {@link Burn#rederive}; never null, {@code "0"} where nothing was destroyed. It is
     * deliberately NOT a cumulative total: the chain tracks neither cumulative minted nor
     * cumulative burned, so a counter would need reorg-unsafe persisted state (the 008
     * rejection). {@code burnDebt} is the <b>next</b> block's carried debt
     * {@code max(0, supply + minted(h+1) − S*(h+1))} — the stock standing between supply and
     * the live target; {@code null} where the curve does not govern the next block or supply is
     * absent, {@code "0"} where the curve governs and nothing is owed (absence is null, never
     * 0 — ADR-012 §3). Both are decimal strings.
     *
     * <p>Arithmetic guards: {@code distanceToTarget} subtracts and {@code progressBps}
     * multiplies with {@code Math.*Exact} so a pathological profile fails loudly instead of
     * wrapping into a plausible-looking figure (data-model §Overflow).
     */
    static void writeEmissionFragment(JsonSink sink, NetworkParameters params, TipView tip) {
        long height = tip.height();
        long tipSupply = tip.tipSupply();
        boolean supplyCommitted = tipSupply != BlockImpl.SUPPLY_ABSENT;
        // The rule governing the NEXT block, not the tip's.
        boolean curveGovernsNext = params.emissionCurveActiveAt(height + 1);
        long liveTarget = params.supplyTargetAt(height + 1);

        sink.name(K_EMISSION);
        sink.beginObject();
        sink.field(K_RULE, curveGovernsNext ? RULE_CURVE : RULE_GEOMETRIC);
        sink.field(K_ACTIVATION_HEIGHT, params.emissionCurveHeight());
        if (supplyCommitted) {
            sink.fieldLongAsString(K_SUPPLY, tipSupply);
            sink.fieldLongAsString(K_SUBSIDY, params.miningReward(height + 1, tipSupply));
        } else {
            sink.fieldNull(K_SUPPLY);
            sink.fieldLongAsString(K_SUBSIDY, params.miningReward(height + 1));
        }
        sink.fieldLongAsString(K_TARGET, liveTarget);
        sink.fieldLongAsString(K_PEAK_TARGET, params.supplyTarget());
        sink.fieldLongAsString(K_DECAY_START_HEIGHT, params.decayStartHeight());
        if (supplyCommitted && curveGovernsNext) {
            // May be negative: past the decay start the live target can sit BELOW the
            // committed supply — the sign is the whole story an operator watches (FR-026).
            sink.fieldLongAsString(K_DISTANCE_TO_TARGET,
                Math.subtractExact(liveTarget, tipSupply));
            // Integer basis points of supply/live-target, UNCLAMPED — above the target it
            // legitimately exceeds 10 000 (and keeps growing as the target decays away). The
            // divisor cannot be zero: SupplyTargetSchedule.targetAt is strictly positive by
            // construction (the floor is > 0 whenever scheduled, the peak > 0 always).
            sink.field(K_PROGRESS_BPS,
                Math.multiplyExact(tipSupply, 10_000L) / liveTarget);
            sink.fieldLongAsString(K_OBLIGATION, params.burnObligation(height + 1, tipSupply));
            // The carried stock: what the NEXT block may consume, computed through the same
            // Burn.debt the gates enforce (one formula, one home). The subsidy figure above is
            // exactly this block's no-uncle minted term, so the debt published here is the
            // figure an independent implementer recomputes from this response alone.
            sink.fieldLongAsString(K_BURN_DEBT,
                Burn.debt(params, height + 1, tipSupply,
                    params.miningReward(height + 1, tipSupply)));
        } else {
            sink.fieldNull(K_DISTANCE_TO_TARGET);
            sink.fieldNull(K_PROGRESS_BPS);
            sink.fieldNull(K_OBLIGATION);
            sink.fieldNull(K_BURN_DEBT);
        }
        sink.fieldLongAsString(K_FLOOR, params.minerRevenueFloor());
        // The tip block's destroyed amount (009 T059): recovered from two headers, never a
        // cumulative counter. "0" on a chain that commits no supply, below activation, or where
        // the block destroyed nothing — the observable value is unchanged in every case that has
        // ever occurred on a pre-009 chain.
        long burned = 0;
        if (supplyCommitted && tip.parentSupply() != BlockImpl.SUPPLY_ABSENT) {
            long mintedTip = Issuance.minted(params, height, tip.parentSupply(),
                tip.tipDifficulty(), tip.tipUncles());
            burned = Math.max(0, Burn.rederive(tip.parentSupply(), mintedTip, tipSupply));
        }
        sink.fieldLongAsString(K_BURNED, burned);
        sink.field(K_DECIMAL_SCALE_FACTOR, params.decimalScaleFactor());
        sink.endObject();
    }

    /**
     * Serializes the {@code GET /emission} schedule body: the profile's published constants
     * (including the {@code decay} object and the {@code sampleHeight} the samples were drawn
     * at) plus a 64-point sampling of its curve at the LIVE target for {@code height}, over
     * {@code (0, 1.25 × S*]} so the floored tail past the target — the regime the miner revenue
     * floor created — is visible instead of hidden.
     *
     * <p>Chain-state-free by requirement: the method itself takes no consensus lock, reads no
     * header and no ledger — {@code height} arrives from the caller (the memo's observed tip
     * height), making the payload a pure function of {@code (params, height)}. Every sampled
     * subsidy passes through the single clamp site (two-arg {@code miningReward}) at
     * {@code sampleHeight}, so the served curve is exactly what a miner would be paid at those
     * supplies under the live target: never negative, never below the floor. {@code rule} here
     * is the schedule's <b>policy</b> — whether the profile schedules the curve at all — not the
     * height-dependent rule the fragment reports.
     *
     * @param height the height the samples are drawn at (the memo key's own height —
     *               {@code sampleHeight} names it so a consumer never has to infer which target
     *               the samples were drawn against)
     * @return an owned copy of the serialized body (the sink's backing array is not held)
     */
    static byte[] schedulePayload(NetworkParameters params, long height) {
        boolean scheduled = params.emissionCurveHeight() > 0;
        // The samples must show the CURVE, never the pre-activation geometric constant: below
        // the activation height miningReward(height, supply) ignores `supply` entirely, so all
        // 64 samples would collapse to one flat value while `rule` still reads "curve" (007
        // pinned emissionCurveHeight here for exactly that reason). Clamping UP to the
        // activation height restores it, and sampleHeight publishes the clamped value so the
        // field keeps naming the height the samples were actually drawn at.
        long sampleHeight = scheduled ? Math.max(height, params.emissionCurveHeight()) : height;
        JsonSink sink = JsonSink.create(SCHEDULE_SIZE_HINT);
        sink.beginObject();
        sink.field(K_NETWORK, params.networkName());
        sink.field(K_RULE, scheduled ? RULE_CURVE : RULE_GEOMETRIC);
        sink.field(K_ACTIVATION_HEIGHT, params.emissionCurveHeight());
        sink.fieldLongAsString(K_SUPPLY_TARGET, params.supplyTarget());
        sink.fieldLongAsString(K_COEFFICIENT, params.emissionCoefficient());
        sink.field(K_STEPS, params.emissionTableSteps());
        sink.fieldLongAsString(K_FLOOR, params.minerRevenueFloor());
        // The pinned burn share (009 T061, FR-035): beside the other pinned constants so an
        // independent implementer can reproduce the burn rule from this route alone (ADR-012
        // §1). Height-invariant, so the decay-epoch memo key and its entry count are unaffected.
        sink.fieldLongAsString(K_BURN_SHARE_NUM, params.burnShareNum());
        sink.fieldLongAsString(K_BURN_SHARE_DEN, params.burnShareDen());
        if (params.genesisSupply() != NetworkParameters.GENESIS_SUPPLY_UNPINNED) {
            sink.fieldLongAsString(K_GENESIS_SUPPLY, params.genesisSupply());
        } else {
            sink.fieldNull(K_GENESIS_SUPPLY);
        }
        sink.field(K_DECIMAL_SCALE_FACTOR, params.decimalScaleFactor());
        // The decay constants: published verbatim (008 contracts/emission-api.md §2) so a
        // consumer can reproduce the whole schedule without a second endpoint. 0 = never.
        sink.name(K_DECAY);
        sink.beginObject();
        SupplyTargetSchedule schedule = params.supplyTargetSchedule();
        sink.fieldLongAsString(K_START_HEIGHT, schedule.startHeight());
        sink.fieldLongAsString(K_EPOCH_BLOCKS, schedule.epochBlocks());
        sink.fieldLongAsString(K_NUM, schedule.num());
        sink.fieldLongAsString(K_DEN, schedule.den());
        sink.fieldLongAsString(K_TARGET_FLOOR, schedule.floor());
        sink.fieldLongAsString(K_EPOCHS_TO_FLOOR, schedule.epochsToFloor());
        sink.fieldLongAsString(K_FLOOR_ARRIVAL_HEIGHT, schedule.floorArrivalHeight());
        sink.fieldLongAsString(K_PER_EPOCH_REDUCTION_BOUND, schedule.perEpochReductionBound());
        sink.endObject();
        sink.field(K_SAMPLE_HEIGHT, sampleHeight);
        sink.name(K_SAMPLES);
        sink.beginArray();
        if (scheduled) {
            // An empty array is a statement, not a failure; a scheduling profile samples
            // supply = i × ⌊(S* + S*/4) ÷ 64⌋ for i in [1, 64], each subsidy evaluated against
            // the live target at sampleHeight. Checked arithmetic on the span and the index
            // product: a degenerate profile fails loudly, never wraps.
            long span = Math.addExact(params.supplyTarget(), params.supplyTarget() / 4);
            long step = span / SAMPLE_COUNT;
            for (int i = 1; i <= SAMPLE_COUNT; i++) {
                long supply = Math.multiplyExact(i, step);
                sink.beginObject();
                sink.fieldLongAsString(K_SUPPLY, supply);
                sink.fieldLongAsString(K_SUBSIDY, params.miningReward(sampleHeight, supply));
                sink.endObject();
            }
        }
        sink.endArray();
        sink.endObject();
        // The one deliberate copy: the memo outlives the sink, so it must own its bytes.
        return sink.toByteArray();
    }

    /**
     * Serves the memoized schedule bytes as a fresh response per request — no serialization,
     * no curve dispatches on the event-loop thread, and no shared {@link HttpResponse} (a
     * served response's body buffer is consumed by the write; see {@code ApiResponses.json(byte[])}).
     */
    static HttpResponse emissionSchedule(byte[] payload) {
        return json(payload);
    }
}
