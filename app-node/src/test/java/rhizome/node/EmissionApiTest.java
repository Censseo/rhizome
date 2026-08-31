package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import io.activej.eventloop.Eventloop;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.CurveActiveNetwork;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.core.blockchain.TestNodeStores;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.MemPool;
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
                "steps", "floor", "genesisSupply", "decimalScaleFactor", "samples"),
            emission.keySet(), "the schedule carries exactly its ten contracted fields");
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
