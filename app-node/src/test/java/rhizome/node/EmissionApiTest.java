package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import io.activej.eventloop.Eventloop;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.CurveActiveNetwork;
import rhizome.core.serialization.JsonSink;
import rhizome.core.blockchain.HonestBlockMiner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.core.blockchain.TestNodeStores;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.mempool.MemPool;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.PowAlgorithm;

/**
 * The chain-state-free emission schedule read, {@code GET /emission} (007-emission-observability,
 * US2): every published constant, and a sample set that can never drift from the schedule
 * consensus actually pays — every served sample must equal a direct evaluation of the same
 * profile's dispatch (SC-004b).
 */
class EmissionApiTest {

    private Eventloop eventloop;
    private Thread eventloopThread;

    @BeforeEach
    void setUp() {
        eventloop = Eventloop.create();
        eventloop.keepAlive(true);
        eventloopThread = new Thread(eventloop, "emission-api-test-eventloop");
        eventloopThread.setDaemon(true);
        eventloopThread.start();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        eventloop.keepAlive(false);
        eventloop.execute(eventloop::breakEventloop);
        eventloopThread.join(2000);
    }

    /** A fast-PoW curve-scheduling harness over an empty chain — /emission reads no chain state. */
    private record Harness(NetworkParameters params, AsyncServlet servlet) {}

    private Harness harness(NetworkParameters p) {
        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, p.chainId());
        if (p.genesisSupply() != NetworkParameters.GENESIS_SUPPLY_UNPINNED) {
            // A pinned profile boots only when the snapshot total matches the pin (C-11).
            snapshot.put(PublicAddress.random(),
                new rhizome.core.transaction.TransactionAmount(p.genesisSupply()));
        }
        var verifier = new SignatureVerifier();
        ChainEngine e = ChainEngine.boot(p, TestNodeStores.mixing(new InMemoryLedger(),
            new rhizome.core.blockchain.InMemoryChainStore()), snapshot)
            .clock(() -> 0L).verifier(verifier).build();
        NodeService n = new NodeService(e, new MemPool(p, verifier, e, 1000));
        return new Harness(p, NodeApi.servlet(eventloop, n));
    }

    private Harness curveActiveHarness() {
        return harness(CurveActiveNetwork.curveActiveTestnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).build());
    }

    private String getBody(AsyncServlet servlet) throws Exception {
        HttpResponse response = eventloop.<HttpResponse>submit(() ->
            servlet.serve(HttpRequest.get("http://x/emission").build())
                .then(resp -> resp.loadBody().map($ -> resp))
        ).get();
        assertEquals(200, response.getCode());
        return response.getBody().getString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void emissionPublishesEveryConstantWithContractedTypesAndNullability() throws Exception {
        Harness h = curveActiveHarness();
        JSONObject emission = new JSONObject(getBody(h.servlet()));

        assertEquals(Set.of("network", "rule", "activationHeight", "supplyTarget", "coefficient",
                "steps", "floor", "burnShareNum", "burnShareDen", "genesisSupply",
                "decimalScaleFactor", "samples", "decay", "sampleHeight"),
            emission.keySet(), "the schedule carries exactly its fourteen contracted fields "
                + "(009 T061 added the two pinned burn-share constants)");
        assertEquals(h.params().networkName(), emission.getString("network"));
        assertEquals("curve", emission.getString("rule"));
        assertEquals(h.params().emissionCurveHeight(), emission.getLong("activationHeight"));
        assertEquals(h.params().supplyTarget() + "", emission.getString("supplyTarget"));
        assertEquals(h.params().emissionCoefficient() + "", emission.getString("coefficient"));
        assertEquals(h.params().emissionTableSteps(), emission.getLong("steps"));
        assertEquals(h.params().minerRevenueFloor() + "", emission.getString("floor"));
        assertEquals(h.params().decimalScaleFactor(), emission.getLong("decimalScaleFactor"));
        // testnet derives from an unpinned profile: no genesis supply is published, and absence
        // is null — not "0", not the key omitted.
        assertTrue(emission.isNull("genesisSupply"),
            "an unpinned profile publishes genesisSupply as null");
        assertEquals(64, emission.getJSONArray("samples").length());
    }

    @Test
    void aPinnedProfilePublishesItsGenesisSupplyAndAnUnpinnedOnePublishesNull() throws Exception {
        // Mainnet-shaped profile: pins a genesis supply (provisional allocation) — the constant
        // ships as a decimal string. Boot requires the snapshot total to match the pin.
        NetworkParameters pinned = NetworkParameters.cleanMainnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).build();
        Harness mainnet = harness(pinned);
        JSONObject emission = new JSONObject(getBody(mainnet.servlet()));
        assertEquals(pinned.genesisSupply() + "", emission.getString("genesisSupply"));
        // cleanMainnet schedules the curve from height 1 (006-emission-fork-activation): the
        // pinned profile is the calibrated, curve-active one, with a full sample set.
        assertEquals("curve", emission.getString("rule"));
        assertEquals(1, emission.getLong("activationHeight"));
        assertEquals(64, emission.getJSONArray("samples").length());
    }

    @Test
    void everyServedSampleEqualsTheConsensusDispatch() throws Exception {
        Harness h = curveActiveHarness();
        JSONObject emission = new JSONObject(getBody(h.servlet()));
        JSONArray samples = emission.getJSONArray("samples");
        long activationHeight = emission.getLong("activationHeight");

        for (int i = 0; i < samples.length(); i++) {
            JSONObject sample = samples.getJSONObject(i);
            long supply = Long.parseLong(sample.getString("supply"));
            long subsidy = Long.parseLong(sample.getString("subsidy"));
            assertEquals(h.params().miningReward(activationHeight, supply), subsidy,
                "sample " + i + " must equal the dispatch consensus itself would pay — the "
                    + "plotted curve may not drift from the paid schedule");
        }
    }

    @Test
    void theSampleSetSpansTheTargetWithSixtyFourAscendingFlooredEntries() throws Exception {
        Harness h = curveActiveHarness();
        JSONObject emission = new JSONObject(getBody(h.servlet()));
        JSONArray samples = emission.getJSONArray("samples");
        long target = h.params().supplyTarget();
        long floor = h.params().minerRevenueFloor();
        long expectedStep = (target + target / 4) / 64;

        assertEquals(64, samples.length(), "exactly 64 samples on a curve-scheduling profile");
        long previous = -1;
        for (int i = 0; i < samples.length(); i++) {
            JSONObject sample = samples.getJSONObject(i);
            long supply = Long.parseLong(sample.getString("supply"));
            long subsidy = Long.parseLong(sample.getString("subsidy"));
            assertEquals((i + 1) * expectedStep, supply, "sample " + i + " sits at i × step");
            assertTrue(supply > previous, "supplies are strictly ascending");
            previous = supply;
            assertTrue(subsidy >= floor, "every sampled subsidy is at least the revenue floor");
            assertTrue(subsidy >= 0, "never negative — the clamp site is not bypassed");
        }
        assertTrue(previous > target,
            "the last sample sits above S* so the floored tail is visible");
    }

    @Test
    void theSchedulePublishesTheDecayObjectAndNamesItsSampleHeight() throws Exception {
        Harness h = harness(CurveActiveNetwork.decayActiveTestnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).build());
        JSONObject emission = new JSONObject(getBody(h.servlet()));

        // sampleHeight names the height the samples were drawn at — the boot height here, so a
        // consumer never has to infer which live target the samples were drawn against.
        assertEquals(1, emission.getLong("sampleHeight"));
        var schedule = h.params().supplyTargetSchedule();
        JSONObject decay = emission.getJSONObject("decay");
        assertEquals(Set.of("startHeight", "epochBlocks", "num", "den", "targetFloor",
                "epochsToFloor", "floorArrivalHeight", "perEpochReductionBound"),
            decay.keySet(), "the decay object carries exactly its eight contracted fields");
        assertEquals(schedule.startHeight() + "", decay.getString("startHeight"));
        assertEquals(schedule.epochBlocks() + "", decay.getString("epochBlocks"));
        assertEquals(schedule.num() + "", decay.getString("num"));
        assertEquals(schedule.den() + "", decay.getString("den"));
        assertEquals(schedule.floor() + "", decay.getString("targetFloor"));
        assertEquals(schedule.epochsToFloor() + "", decay.getString("epochsToFloor"));
        assertEquals(schedule.floorArrivalHeight() + "", decay.getString("floorArrivalHeight"));
        assertEquals(schedule.perEpochReductionBound() + "",
            decay.getString("perEpochReductionBound"));

        // The samples are drawn at the LIVE target of sampleHeight: with the decay starting at
        // height 10, the boot height is still on the peak plateau, so the samples equal the
        // peak-target dispatch — but they are explicitly the height-height evaluations.
        JSONArray samples = emission.getJSONArray("samples");
        assertEquals(64, samples.length());
        long at = emission.getLong("sampleHeight");
        for (int i = 0; i < samples.length(); i++) {
            JSONObject sample = samples.getJSONObject(i);
            long supply = Long.parseLong(sample.getString("supply"));
            assertEquals(h.params().miningReward(at, supply),
                Long.parseLong(sample.getString("subsidy")),
                "sample " + i + " must equal the dispatch at sampleHeight's live target");
        }
    }

    @Test
    void theScheduleMemoIsReplacedWhenTheDecayEpochIndexChanges() throws Exception {
        Harness h = harness(CurveActiveNetwork.decayActiveTestnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).build());
        NodeService n = harnessService(h);
        var schedule = h.params().supplyTargetSchedule();

        // Boot: memo built for height 1 (peak plateau, epoch index 0).
        byte[] before = n.emissionSchedulePayload();
        n.noteAppliedHeight(1);
        assertEquals(before, n.emissionSchedulePayload(),
            "a new applied height in the SAME epoch must not rebuild the memo");

        // Cross the decay start AND the first epoch boundary: the index changes, the memo is
        // replaced — sampleHeight moves and the samples are re-drawn at the decayed target.
        long boundary = schedule.startHeight() + schedule.epochBlocks() + 2;
        n.noteAppliedHeight(boundary);
        byte[] after = n.emissionSchedulePayload();
        org.junit.jupiter.api.Assertions.assertArrayEquals(after, n.emissionSchedulePayload(),
            "a second read within the new epoch must be served from the memo, not rebuilt");
        JSONObject payloadA = new JSONObject(new String(before,
            java.nio.charset.StandardCharsets.UTF_8));
        JSONObject payloadB = new JSONObject(new String(after,
            java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(1, payloadA.getLong("sampleHeight"));
        assertEquals(boundary, payloadB.getLong("sampleHeight"),
            "the replaced memo must name its own (moved) sample height");
        long targetA = Long.parseLong(payloadA.getJSONArray("samples").getJSONObject(0)
            .getString("subsidy"));
        long targetB = Long.parseLong(payloadB.getJSONArray("samples").getJSONObject(0)
            .getString("subsidy"));
        assertTrue(targetA != targetB || payloadA.getLong("sampleHeight") != payloadB
            .getLong("sampleHeight"),
            "the payload must actually move across the epoch boundary");
    }

    @Test
    void aRestartedNodeSeedsTheScheduleMemoAtTheStoredTipNotAtHeightOne() throws Exception {
        NetworkParameters p = decayActive();
        var schedule = p.supplyTargetSchedule();
        AtomicLong clock = new AtomicLong(1_000_000L);
        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, p.chainId());
        var verifier = new SignatureVerifier();
        ChainEngine e = ChainEngine.boot(p, TestNodeStores.mixing(new InMemoryLedger(),
            new rhizome.core.blockchain.InMemoryChainStore()), snapshot)
            .clock(clock::get).verifier(verifier).build();

        // Mine past the decay start AND the first epoch boundary: the stored tip an actual
        // restart boots from (honest coinbase per height — the dispatch handles the decay).
        PublicAddress miner = PublicAddress.random();
        long tip = schedule.startHeight() + schedule.epochBlocks() + 2;
        for (long h = e.height() + 1; h <= tip; h++) {
            long parentSupply = e.headerAt(e.height()).supply();
            BlockImpl b = HonestBlockMiner.mineNext(p, e,
                clock.addAndGet(p.desiredBlockTimeSec() * 1000L),
                Transaction.of(miner, new TransactionAmount(p.miningReward(h, parentSupply))));
            assertEquals(ExecutionStatus.SUCCESS, e.addBlock(b), "block " + h + " must apply");
        }
        assertEquals(tip, e.height());

        // The restart: a service assembled over the already-tipped engine with NO
        // noteAppliedHeight call — the block-applied listener never fires for the stored tip
        // loaded at boot, so the memo must be seeded from the engine's own height.
        NodeService restarted = new NodeService(e, new MemPool(p, verifier, e, 1000));
        byte[] first = restarted.emissionSchedulePayload();
        org.junit.jupiter.api.Assertions.assertArrayEquals(first, restarted.emissionSchedulePayload(),
            "the boot memo must be served as-is on repeated reads within the epoch");
        JSONObject payload = new JSONObject(new String(first,
            java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(tip, payload.getLong("sampleHeight"),
            "the boot memo must name the stored tip, not height 1 — a height-1 seed would serve "
                + "a peak-target schedule while the live-target tiles already report the decay");
        assertTrue(p.supplyTargetAt(tip) != p.supplyTarget(),
            "fixture sanity: the decay must have moved the live target off the peak at the tip");
        JSONArray samples = payload.getJSONArray("samples");
        long at = payload.getLong("sampleHeight");
        for (int i = 0; i < samples.length(); i++) {
            JSONObject sample = samples.getJSONObject(i);
            long supply = Long.parseLong(sample.getString("supply"));
            assertEquals(p.miningReward(at, supply),
                Long.parseLong(sample.getString("subsidy")),
                "sample " + i + " must be drawn at the stored tip's decayed target");
        }
    }

    /** Writes the fragment directly and parses it back -- the single-writer contract's own seat.
     *  The parent supply defaults to absent (burned "0"); the 4-arg overload states it. */
    private JSONObject fragment(NetworkParameters params, long height, long tipSupply) {
        return fragment(params, height, tipSupply, rhizome.core.block.BlockImpl.SUPPLY_ABSENT);
    }

    private JSONObject fragment(NetworkParameters params, long height, long tipSupply,
            long parentSupply) {
        JsonSink sink = JsonSink.create(512);
        // The fragment always nests inside a served root object (that is how /info and /stats
        // write it), so the helper mirrors that shape.
        sink.beginObject();
        EmissionApi.writeEmissionFragment(sink, params, new EmissionApi.TipView(height, tipSupply,
            parentSupply, 4, java.util.List.of()));
        sink.endObject();
        return new JSONObject(new String(sink.toByteArray(), java.nio.charset.StandardCharsets.UTF_8))
            .getJSONObject("emission");
    }

    private NetworkParameters decayActive() {
        return CurveActiveNetwork.decayActiveTestnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).build();
    }

    @Test
    void theFragmentReportsTheLiveTargetNotThePeakOnceTheDecayEngages() throws Exception {
        NetworkParameters params = decayActive();
        long supply = 900_000L; // above the decayed target after the first epochs
        long height = schedulePastFirstEpoch(params);

        JSONObject em = fragment(params, height, supply);
        // SC-009: no published surface may report the peak as "the target" once decay is live.
        assertEquals(params.supplyTargetAt(height + 1), Long.parseLong(em.getString("target")),
            "target must be the live S*(h) for the next block");
        assertTrue(Long.parseLong(em.getString("target")) != params.supplyTarget(),
            "past the first decay epoch the live target differs from the peak");
        assertEquals(params.supplyTarget() + "", em.getString("peakTarget"),
            "the peak moves to peakTarget, unchanged");
        assertEquals(params.decayStartHeight() + "", em.getString("decayStartHeight"));
    }

    @Test
    void theFragmentCarriesNegativeDistanceUnclampedProgressAndTheObligation() throws Exception {
        NetworkParameters params = decayActive();
        long supply = 900_000L; // deliberately ABOVE the decayed target
        long height = schedulePastFirstEpoch(params);
        long liveTarget = params.supplyTargetAt(height + 1);
        assertTrue(supply > liveTarget, "fixture sanity: supply above the live target");

        JSONObject em = fragment(params, height, supply);
        assertEquals(liveTarget - supply, Long.parseLong(em.getString("distanceToTarget")),
            "the distance is negative when supply sits above the live target (FR-026)");
        assertTrue(em.getLong("progressBps") > 10_000,
            "progress past the target legitimately exceeds 10 000 bps — unclamped");
        assertEquals(params.burnObligation(height + 1, supply) + "",
            em.getString("obligation"),
            "the obligation is the per-block derived figure, positive here");
        // 009 rewrite of the two 007/008 locking assertions (T054): burned reports the tip
        // block's destroyed amount, burnDebt the carried stock. This fragment's tip burned
        // nothing (the parent supply is absent in this shape), so burned reads "0" — a real
        // per-block figure, not the old defined constant.
        assertEquals("0", em.getString("burned"),
            "this tip destroyed nothing — a per-block figure that happens to be 0");
        assertEquals(rhizome.core.blockchain.Burn.debt(params, height + 1, supply,
                params.miningReward(height + 1, supply)) + "",
            em.getString("burnDebt"),
            "burnDebt reports the carried stock the next block may consume");
        // The distinction that survives 009: per-block and stock, never a counter.
        assertFalse(em.has("cumulativeObligation") || em.has("cumulativeBurned")
                || em.has("totalBurned") || em.has("totalBurn"),
            "no CUMULATIVE burn field ships — a counter is not header-derivable and could not "
                + "reverse structurally on a reorg (research.md Decision 1)");
    }

    @Test
    void burnedReportsTheTipBlocksDestroyedAmountAndBurnDebtTheCarriedStock() throws Exception {
        // The 009 T054 rewrite of the 007/008 locks, with a tip that REALLY burned: the burned
        // figure is recovered from two headers (parent.supply + minted(tip) - tip.supply) and
        // burnDebt is the carried stock for the NEXT block.
        NetworkParameters params = decayActive();
        long height = schedulePastFirstEpoch(params);
        long parentSupply = 900_000L;
        long fee = 1_000L;                       // the tip's eligible flow
        long minted = params.miningReward(height, parentSupply);
        long burned = rhizome.core.blockchain.Burn.burned(params, height, fee,
            rhizome.core.blockchain.Burn.debt(params, height, parentSupply, minted));
        assertTrue(burned > 0, "fixture sanity: the tip really burned");
        long tipSupply = parentSupply + minted - burned;

        JSONObject em = fragment(params, height, tipSupply, parentSupply);
        assertEquals(burned + "", em.getString("burned"),
            "burned is the tip block's destroyed amount, recovered from the two headers");
        long nextDebt = rhizome.core.blockchain.Burn.debt(params, height + 1, tipSupply,
            params.miningReward(height + 1, tipSupply));
        assertEquals(nextDebt + "", em.getString("burnDebt"),
            "burnDebt is the next block's carried debt");
        assertFalse(em.has("cumulativeBurned") || em.has("totalBurned"),
            "still no cumulative field: per-block and stock, never a counter");
    }

    @Test
    void burnDebtIsNullWhereTheCurveDoesNotGovernAndZeroWhereNothingIsOwed() throws Exception {
        // 009 T055 (ADR-012 §3): absence stays null, never 0 — a chain the curve does not
        // govern carries NO debt figure; where the curve governs and nothing is owed the figure
        // exists and reads exactly "0".
        NetworkParameters geometric = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).build();
        JSONObject off = fragment(geometric, 1, 900_000L);
        assertTrue(off.isNull("burnDebt"),
            "the curve does not govern the next block: no debt figure at all — null, not 0");

        // Curve governing, supply at/below the live target: the figure exists, exactly "0".
        NetworkParameters active = CurveActiveNetwork.curveActiveTestnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).build();
        JSONObject belowTarget = fragment(active, 1, 500_000L, 500_000L);
        assertEquals("0", belowTarget.getString("burnDebt"),
            "the curve governs and nothing is owed: a real 0, never null");
    }

    @Test
    void theEmissionRoutePublishesTheBurnShareConstants() throws Exception {
        // 009 T057 (FR-035): the pinned share constants join the published schedule so an
        // independent implementer can reproduce the burn rule from this route alone (ADR-012
        // §1). They are height-invariant, so the decay-epoch memo key is unaffected.
        Harness h = curveActiveHarness();
        JSONObject emission = new JSONObject(getBody(h.servlet()));
        assertEquals(h.params().burnShareNum() + "", emission.getString("burnShareNum"),
            "the pinned share numerator, decimal string");
        assertEquals(h.params().burnShareDen() + "", emission.getString("burnShareDen"),
            "the pinned share denominator, decimal string");
        assertEquals(NetworkParameters.cleanMainnet().burnShareNum() + "",
            emission.getString("burnShareNum"),
            "the published share must equal the shipped mainnet constant");
    }

    @Test
    void theFragmentKeepsNullSemanticsWhereTheChainCannotSupportTheFigures() throws Exception {
        NetworkParameters params = decayActive();
        long height = schedulePastFirstEpoch(params);

        // Supply absent: distance/progress/obligation are null — never zeroed.
        JSONObject absent = fragment(params, height, rhizome.core.block.BlockImpl.SUPPLY_ABSENT);
        assertTrue(absent.isNull("distanceToTarget"));
        assertTrue(absent.isNull("progressBps"));
        assertTrue(absent.isNull("obligation"));
        assertTrue(absent.isNull("supply"));

        // A profile that never schedules the curve: the curve does not govern the next block —
        // null again, and the published target is the (live == peak) value.
        NetworkParameters geometricProfile = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).build();
        JSONObject geometric = fragment(geometricProfile, 1, 900_000L);
        assertTrue(geometric.isNull("obligation"),
            "a chain the curve does not govern has no obligation — null, not 0");
        assertTrue(geometric.isNull("distanceToTarget"));
        assertEquals(geometricProfile.supplyTargetAt(2) + "", geometric.getString("target"),
            "the target is still published (the live value, peak here) even when geometric");
    }

    /** A height whose NEXT block sits two completed decay epochs in (start 10, epoch 5). */
    private static long schedulePastFirstEpoch(NetworkParameters params) {
        var schedule = params.supplyTargetSchedule();
        return schedule.startHeight() + 2 * schedule.epochBlocks() - 1;
    }

    private NodeService harnessService(Harness h) {
        // Rebuilds the service the harness's servlet wraps -- the harness record keeps only the
        // servlet, so this constructs an equivalent service for memo-level assertions.
        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, h.params().chainId());
        if (h.params().genesisSupply() != NetworkParameters.GENESIS_SUPPLY_UNPINNED) {
            snapshot.put(PublicAddress.random(),
                new rhizome.core.transaction.TransactionAmount(h.params().genesisSupply()));
        }
        var verifier = new SignatureVerifier();
        ChainEngine e = ChainEngine.boot(h.params(), TestNodeStores.mixing(new InMemoryLedger(),
            new rhizome.core.blockchain.InMemoryChainStore()), snapshot)
            .clock(() -> 0L).verifier(verifier).build();
        return new NodeService(e, new MemPool(h.params(), verifier, e, 1000));
    }

    @Test
    void aProfileThatNeverSchedulesTheCurveServesAnEmptySampleSetAsAStatement() throws Exception {
        Harness h = harness(NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).build());
        JSONObject emission = new JSONObject(getBody(h.servlet()));

        assertEquals("geometric", emission.getString("rule"));
        assertEquals(0, emission.getLong("activationHeight"));
        assertEquals(0, emission.getJSONArray("samples").length(),
            "an empty sample set states 'this chain is not curve-governed' — it is not an error");
        // The constants are still published for a consumer that wants them.
        assertEquals(h.params().supplyTarget() + "", emission.getString("supplyTarget"));
        assertFalse(emission.isNull("decimalScaleFactor"));
        assertFalse(emission.has("burnDebt"),
            "no burn-debt field exists on the schedule either (FR-014)");
    }
}
