package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.Burn;
import rhizome.core.blockchain.CurveActiveNetwork;
import rhizome.core.blockchain.NetworkParameters;

/**
 * The burn quantity (009-native-coin-burn, contracts/native-coin-burn.md §4): the lesser of the
 * share of the flow and the carried debt, clamped to zero wherever the curve does not govern.
 * The seven shipped boundary cases are pinned bit-for-bit by the checked-in
 * {@code /emission/burn-vectors.json} artifact (the sibling of {@code curve-vectors.json}, WI-11).
 */
class BurnTest {

    private static final String RESOURCE = "/emission/burn-vectors.json";

    /** A curve-active profile: activation at height 1 over the test-scale triple. */
    private static final NetworkParameters ACTIVE = CurveActiveNetwork.curveActiveTestnet();

    /** The never-scheduled twin: same constants, the curve never governs any height. */
    private static final NetworkParameters INACTIVE = NetworkParameters.testnet();

    private static JSONObject loadArtifact() {
        try (InputStream in = BurnTest.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                    "missing test resource " + RESOURCE + " — T008 checks in the burn vectors");
            }
            return new JSONObject(new JSONTokener(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void burnIsTheLesserOfTheShareOfFlowAndTheCarriedDebt() {
        // The seven §10 boundary cases, reproduced from the artifact: expected ==
        // min(⌊pool × num/den⌋, debt) where the curve governs, 0 where it does not.
        JSONObject artifact = loadArtifact();
        assertEquals(Long.parseLong(artifact.getString("burnShareNum")),
            NetworkParameters.cleanMainnet().burnShareNum(),
            "artifact share numerator must equal the shipped mainnet constant");
        assertEquals(Long.parseLong(artifact.getString("burnShareDen")),
            NetworkParameters.cleanMainnet().burnShareDen(),
            "artifact share denominator must equal the shipped mainnet constant");

        JSONArray vectors = artifact.getJSONArray("vectors");
        assertTrue(vectors.length() >= 7, "the seven §10 boundary cases must all be pinned");
        for (int i = 0; i < vectors.length(); i++) {
            JSONObject v = vectors.getJSONObject(i);
            boolean curveActive = v.getBoolean("curveActive");
            long pool = Long.parseLong(v.getString("pool"));
            long debt = Long.parseLong(v.getString("debt"));
            long expected = Long.parseLong(v.getString("expected"));
            NetworkParameters params = curveActive ? ACTIVE : INACTIVE;
            long burned = Burn.burned(params, 1, pool, debt);
            assertEquals(expected, burned, v.getString("name"));
            // Cross-check the shape the artifact pins: the lesser of the two terms.
            long share = pool * params.burnShareNum() / params.burnShareDen();
            long shape = curveActive ? Math.min(share, debt) : 0;
            assertEquals(shape, expected,
                "artifact case " + i + " must pin min(share, debt), curve-gated");
        }
    }

    @Test
    void aBurnNeverTakesSupplyBelowTheLiveTarget() {
        // B1: 0 <= burned <= debt — so parent.supply + minted − burned >= S*(h) at every curve-
        // active height. Landing exactly on the live target is legal (debt == burned); landing
        // below it is not, whatever the pool. Swept over pools around the share's rounding
        // boundary and debts around every interesting edge.
        long height = 1;
        long sStar = ACTIVE.supplyTargetAt(height);
        for (long pool : new long[] {0, 1, 2, 3, 7, 1_000, 9_999, 10_000, 10_001, 1_000_000}) {
            for (long debt : new long[] {0, 1, 499, 500, 501, 4_999, 5_000, 5_001, 100_000}) {
                long burned = Burn.burned(ACTIVE, height, pool, debt);
                assertTrue(burned >= 0, "burned must never go negative");
                assertTrue(burned <= debt,
                    "burned " + burned + " exceeded debt " + debt + " (pool " + pool + ")");
                long parent = Math.addExact(sStar, debt);
                long minted = 0;
                long resultingSupply = parent + minted - burned;
                assertTrue(resultingSupply >= sStar,
                    "supply landed below the live target: " + resultingSupply + " < " + sStar
                        + " (pool " + pool + ", debt " + debt + ")");
            }
        }

        // The exact-landing case end to end through Burn.debt: parent below the target, minted
        // pushing the ceiling exactly onto it — debt 0, burn 0, supply lands exactly on S*(h).
        long minted = 5_000;
        long parent = sStar - minted;
        long debt = Burn.debt(ACTIVE, height, parent, minted);
        assertEquals(0, debt, "a ceiling exactly on the target owes nothing");
        assertEquals(0, Burn.burned(ACTIVE, height, 1_000_000, debt));
        long landing = parent + minted - Burn.burned(ACTIVE, height, 1_000_000, debt);
        assertEquals(sStar, landing, "landing exactly on the live target is legal");
    }

    @Test
    void noCoinIsDestroyedWhereTheCurveDoesNotGovern() {
        // B4: burned == 0 on every non-curve-active height, at every supply, fee level, rent
        // level and debt — both on a profile that never schedules the curve and below a
        // scheduled activation height. The debt argument is deliberately large: the curve gate
        // fires before the debt could possibly matter.
        for (long height : new long[] {0, 1, 5, 1_000, Long.MAX_VALUE / 2}) {
            assertEquals(0, Burn.burned(INACTIVE, height, 10_000, Long.MAX_VALUE / 4),
                "a never-scheduled profile destroys nothing at height " + height);
            assertEquals(0, Burn.debt(INACTIVE, height, Long.MAX_VALUE / 4, Long.MAX_VALUE / 4),
                "a never-scheduled profile carries no debt at height " + height);
        }
        long activationHeight = 10;
        NetworkParameters late = ACTIVE.toBuilder().emissionCurveHeight(activationHeight).build();
        for (long height = 0; height < activationHeight; height++) {
            assertEquals(0, Burn.burned(late, height, 10_000, 999_999),
                "nothing burns below the scheduled activation height " + activationHeight);
            assertEquals(0, Burn.debt(late, height, 2_000_000, 5_000),
                "no debt accrues below the scheduled activation height " + activationHeight);
        }
        // And at the activation height itself the rule is live again.
        assertTrue(Burn.burned(late, activationHeight, 10_000, 999_999) > 0,
            "the curve governs from the activation height on");
    }

    @Test
    void theShareRoundsDownAtOneSiteAndNeverProducesANegativeBurn() {
        // B3: exactly one floor division. pool 7 at 1/2 is 3 (floor of 3.5), never 4 (round to
        // nearest) and never an accumulating per-transaction split; pool 1 floors to 0. The
        // odd-base-unit remainder always stays with the miner.
        assertEquals(3, Burn.applyShare(ACTIVE, 7));
        assertEquals(0, Burn.applyShare(ACTIVE, 1));
        assertEquals(0, Burn.burned(ACTIVE, 1, 1, 1_000_000), "a 1-base-unit pool rounds to 0");
        assertEquals(500, Burn.applyShare(ACTIVE, 1_000), "an even pool divides exactly");
        // One division site: applyShare IS the share term burned clamps against, not a second
        // differently-rounded copy of it.
        for (long pool = 0; pool <= 10_000; pool += 7) {
            assertEquals(Math.min(Burn.applyShare(ACTIVE, pool), 4_242),
                Burn.burned(ACTIVE, 1, pool, 4_242),
                "burned must be exactly min(applyShare(pool), debt)");
            assertTrue(Burn.burned(ACTIVE, 1, pool, 4_242) >= 0);
        }
    }
}
