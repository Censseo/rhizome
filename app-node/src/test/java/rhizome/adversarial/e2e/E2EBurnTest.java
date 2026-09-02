package rhizome.adversarial.e2e;

import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.Transaction;
import rhizome.node.RhizomeNode;

/**
 * E2E-88 — 009-native-coin-burn: the burn across a REAL network — real node processes, real sockets, real
 * mining through the supply-target crossing with live fee flow, and a peer converging on the
 * identical history. Everything an outside observer can check is checked: the published fragment
 * reports a real burned figure (positive once fees flow above the target), the carried debt is a
 * non-negative stock, and the peer's copy of history is byte-identical at a captured height
 * (WI-14: anchored on a captured height, never the live tip — the producer is running).
 */
class E2EBurnTest {

    private static final long PREMINE = 4_000_000L;

    @TempDir
    Path tempDir;

    /**
     * A profile whose committed supply crosses {@code supplyTarget} within a few real blocks
     * (the burn feature's G-4 guard satisfied: c <= the lowest target), so live fee flow starts
     * burning within the scenario's first seconds.
     */
    private static NetworkParameters crossableParams() {
        return TestNetwork.FAST.toBuilder()
            .supplyTarget(4000L)
            .emissionCoefficient(3000L)
            .emissionTableSteps(2)
            .emissionCurveHeight(1)
            .build();
    }

    private static JSONObject emissionOf(RhizomeNode node) throws Exception {
        String body = RawHttp.get(node.apiPort(), "/info", Map.of()).body();
        return new JSONObject(body).getJSONObject("emission");
    }

    /**
     * E2E-88 — Mine a real node with live fee flow (real signed transactions through the real
     * HTTP admission) through the supply-target crossing under a crossable curve profile, hoping
     * the published {@code burned} figure is a cumulative lie or a per-poll fabrication, the
     * carried {@code burnDebt} ever negative, or a freshly-joined peer converges on a different
     * history than the burning node mined. The fragment must report a real per-block burn once
     * fees flow past the target, and the peer's copy of history must be byte-identical at a
     * captured height.
     */
    @Test
    void aRealNodeBurnsLiveFeesAcrossTheCrossingAndAPeerConvergesOnTheIdenticalHistory()
            throws Exception {
        NetworkParameters params = crossableParams();
        E2EFixtures.Identity spender = E2EFixtures.Identity.generate();
        Path premine = E2EFixtures.premine(tempDir.resolve("burn-premine.json"),
            params, Map.of(spender, PREMINE));

        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode source = network.node("source").params(params).snapshot(premine)
                .mining().blockInterval(60).start();
            RhizomeNode victim = network.node("victim").params(params).snapshot(premine)
                .peers(TestNetwork.urlOf(source)).start();

            // Live fee flow: real signed transactions through the real HTTP admission, so the
            // mined blocks carry a genuine eligible pool once supply crosses the target.
            for (int round = 0; round < 24; round++) {
                Transaction feeTx = spender.send(PublicAddress.random(), 0L, 500L, round, params);
                RawHttp.post(source.apiPort(), "/add_transaction_json", Map.of(),
                    feeTx.toJson().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Thread.sleep(50);
            }

            // Wait until a tip carrying flow has genuinely burned: the fragment's burned figure
            // is the outside-observable proof (the tip block's destroyed amount, recovered from
            // two headers by the node's own single writer). The crossing is implied: burned > 0
            // requires a positive carried debt, which requires supply past the live target.
            TestNetwork.await(() -> {
                try {
                    return Long.parseLong(emissionOf(source).getString("burned")) > 0;
                } catch (Exception e) {
                    return false;
                }
            }, () -> "the node never reported a real burn across the crossing");

            // WI-14: anchor on a CAPTURED height — the producer keeps running, so the live tip
            // may be past it by the next statement.
            long captured = source.engine().height();
            var capturedHash = source.engine().blockAt(captured).hash();
            Assertions.assertTrue(captured >= 3,
                "sanity: the chain must have mined through the crossing");

            // The fragment at the captured view: burned a real figure, burnDebt a non-negative
            // stock (the curve governs, so it is never null on this profile).
            JSONObject emission = emissionOf(source);
            long burned = Long.parseLong(emission.getString("burned"));
            long burnDebt = Long.parseLong(emission.getString("burnDebt"));
            Assertions.assertTrue(burned > 0, "the reported burn is a real destroyed amount");
            Assertions.assertTrue(burnDebt >= 0,
                "the carried debt is a non-negative stock wherever the curve governs");

            // The peer converges on the identical history: byte-identical at the captured height.
            TestNetwork.syncUntil(List.of(victim), () -> {
                try {
                    return victim.engine().height() >= captured
                        && victim.engine().blockAt(captured).hash().equals(capturedHash);
                } catch (IllegalArgumentException notYetSynced) {
                    return false;
                }
            });
            Assertions.assertEquals(capturedHash,
                victim.engine().blockAt(captured).hash(),
                "the peer's copy of the burning history is byte-identical");
        }
    }
}
