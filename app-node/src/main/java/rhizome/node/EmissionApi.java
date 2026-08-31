package rhizome.node;

import io.activej.http.HttpResponse;

import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.serialization.JsonSink;
import rhizome.core.serialization.JsonSink.Key;

import static rhizome.node.ApiResponses.json;

/**
 * The emission-observability readouts (007-emission-observability): the shared
 * {@code emission} response fragment that {@code GET /info} and {@code GET /stats} both nest,
 * and — in this class's second half of tasks — the chain-state-free {@code GET /emission}
 * schedule read.
 *
 * <p>The fragment has exactly one writer so the two surfaces cannot drift (spec FR-013). Every
 * monetary value is a decimal string in <b>base units</b>; an absent committed supply stays
 * JSON {@code null} and is never coerced to {@code "0"} (zero is a legal committed value —
 * node-api DI-7's distinction). Figures the chain's state cannot support are {@code null}
 * rather than zeroed: a chain the curve does not govern is not converging on the target.
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
    private static final Key K_DISTANCE_TO_TARGET = Key.of("distanceToTarget");
    private static final Key K_PROGRESS_BPS = Key.of("progressBps");
    private static final Key K_FLOOR = Key.of("floor");
    private static final Key K_BURNED = Key.of("burned");
    private static final Key K_DECIMAL_SCALE_FACTOR = Key.of("decimalScaleFactor");

    /** One fragment is ten fields, longest value 13 digits — a ~300-byte allocation hint. */
    static final int EMISSION_SIZE_HINT = 384;

    /** Total native coin destroyed by consensus: zero because no destruction mechanism exists
     *  (spec FR-014). A defined constant, deliberately distinct from a burn-debt field — none
     *  ships, because no definition of one exists yet. */
    private static final long BURNED = 0L;

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

    /** Base object ~200 B plus 64 two-field samples of two decimal strings each. */
    private static final int SCHEDULE_SIZE_HINT = 4096;

    private EmissionApi() {}

    /**
     * Writes the {@code emission} object into {@code sink} from {@code (params, height,
     * tipSupply)}. Callers source {@code (height, tipSupply)} from ONE chain view —
     * {@code ChainEngine.tipSupply()} on /info, the per-tip {@code StatsWindow} on /stats — so
     * the fragment can never straddle a reorg.
     *
     * <p>{@code subsidy} is the <b>next</b> block's subsidy, dispatched through the consensus
     * dispatch {@link NetworkParameters#miningReward(long, long)}: the value the response's own
     * {@code supply} field determines, verifiable from this response alone. On a chain that
     * commits no supply the one-arg geometric form is used — the same fallback the two-arg form
     * itself makes, made explicit here so the absent sentinel never reaches the curve.
     *
     * <p>Arithmetic guards: {@code distanceToTarget} subtracts and {@code progressBps}
     * multiplies with {@code Math.*Exact} so a pathological profile fails loudly instead of
     * wrapping into a plausible-looking figure (data-model §Overflow).
     */
    static void writeEmissionFragment(JsonSink sink, NetworkParameters params, long height,
                                      long tipSupply) {
        boolean supplyCommitted = tipSupply != BlockImpl.SUPPLY_ABSENT;
        // The rule governing the NEXT block, not the tip's.
        boolean curveGovernsNext = params.emissionCurveActiveAt(height + 1);

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
        sink.fieldLongAsString(K_TARGET, params.supplyTarget());
        if (supplyCommitted && curveGovernsNext) {
            sink.fieldLongAsString(K_DISTANCE_TO_TARGET,
                Math.subtractExact(params.supplyTarget(), tipSupply));
            // Integer basis points of supply/target, UNCLAMPED — above the target it
            // legitimately exceeds 10 000. The divisor cannot be zero: NetworkParameters'
            // constructor eagerly builds the curve, and EmissionCurve.build refuses
            // supplyTarget <= 0 — no constructible profile reaches this division unsound.
            sink.field(K_PROGRESS_BPS,
                Math.multiplyExact(tipSupply, 10_000L) / params.supplyTarget());
        } else {
            sink.fieldNull(K_DISTANCE_TO_TARGET);
            sink.fieldNull(K_PROGRESS_BPS);
        }
        sink.fieldLongAsString(K_FLOOR, params.minerRevenueFloor());
        sink.fieldLongAsString(K_BURNED, BURNED);
        sink.field(K_DECIMAL_SCALE_FACTOR, params.decimalScaleFactor());
        sink.endObject();
    }

    /**
     * Serializes the {@code GET /emission} schedule body ONCE: the profile's published
     * constants plus a 64-point sampling of its curve, over {@code (0, 1.25 × S*]} so the
     * floored tail past the target — the regime the miner revenue floor created — is visible
     * instead of hidden.
     *
     * <p>Chain-state-free by requirement: no consensus lock, no header read, no ledger read.
     * Every input is a construction-time constant of {@link NetworkParameters}, so the payload
     * is identical for the life of the process — the caller memoizes it ({@code NodeApi} builds
     * it once at route assembly) and serves each request through {@link #emissionSchedule(byte[])}.
     * {@code rule} here is the schedule's <b>policy</b> — whether the profile schedules the
     * curve at all — not the height-dependent rule the fragment reports. Every sampled subsidy
     * passes through the single clamp site (two-arg {@code miningReward}), so the served curve
     * is exactly what a miner would be paid at those supplies: never negative, never below the
     * floor.
     *
     * @return an owned copy of the serialized body (the sink's backing array is not held)
     */
    static byte[] schedulePayload(NodeService node) {
        NetworkParameters params = node.params();
        boolean scheduled = params.emissionCurveHeight() > 0;
        JsonSink sink = JsonSink.create(SCHEDULE_SIZE_HINT);
        sink.beginObject();
        sink.field(K_NETWORK, params.networkName());
        sink.field(K_RULE, scheduled ? RULE_CURVE : RULE_GEOMETRIC);
        sink.field(K_ACTIVATION_HEIGHT, params.emissionCurveHeight());
        sink.fieldLongAsString(K_SUPPLY_TARGET, params.supplyTarget());
        sink.fieldLongAsString(K_COEFFICIENT, params.emissionCoefficient());
        sink.field(K_STEPS, params.emissionTableSteps());
        sink.fieldLongAsString(K_FLOOR, params.minerRevenueFloor());
        if (params.genesisSupply() != NetworkParameters.GENESIS_SUPPLY_UNPINNED) {
            sink.fieldLongAsString(K_GENESIS_SUPPLY, params.genesisSupply());
        } else {
            sink.fieldNull(K_GENESIS_SUPPLY);
        }
        sink.field(K_DECIMAL_SCALE_FACTOR, params.decimalScaleFactor());
        sink.name(K_SAMPLES);
        sink.beginArray();
        if (scheduled) {
            // An empty array is a statement, not a failure; a scheduling profile samples
            // supply = i × ⌊(S* + S*/4) ÷ 64⌋ for i in [1, 64]. Checked arithmetic on the span
            // and the index product: a degenerate profile fails loudly, never wraps.
            long span = Math.addExact(params.supplyTarget(), params.supplyTarget() / 4);
            long step = span / SAMPLE_COUNT;
            for (int i = 1; i <= SAMPLE_COUNT; i++) {
                long supply = Math.multiplyExact(i, step);
                sink.beginObject();
                sink.fieldLongAsString(K_SUPPLY, supply);
                sink.fieldLongAsString(K_SUBSIDY, params.miningReward(
                    params.emissionCurveHeight(), supply));
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
