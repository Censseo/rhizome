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

import rhizome.core.blockchain.EmissionCurve;
import rhizome.core.blockchain.NetworkParameters;

/**
 * Locks reproducibility of the published emission-curve algorithm (WHITEPAPER §5.3,
 * contracts/emission-curve.md §5; spec US2/FR-013/SC-001): a checked-in vector artifact,
 * generated once from the mainnet-calibrated constants, must be reproduced bit-for-bit by a
 * fresh {@link EmissionCurve#build} over the SAME header constants read out of that artifact —
 * not out of a literal duplicated here — so the artifact and the live generator are proven to
 * agree independently rather than by construction. It also locks the artifact's wire shape: every
 * 64-bit quantity is a decimal STRING (the bare-long-in-JSON anti-pattern this repo forbids for
 * any JS-consumable artifact, `JsonWriterEquivalenceTest`/`002` precedent), because a bare JSON
 * number silently loses precision for a JS {@code Number} consumer above 2^53.
 */
class EmissionCurveVectorsTest {

    private static final String RESOURCE = "/emission/curve-vectors.json";

    private static JSONObject loadArtifact() {
        try (InputStream in = EmissionCurveVectorsTest.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                    "missing test resource " + RESOURCE
                        + " -- T015 generates and checks in the emission-curve vector artifact");
            }
            return new JSONObject(new JSONTokener(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void thePublishedVectorsAreReproducedBitForBit() {
        JSONObject artifact = loadArtifact();

        long supplyTarget = Long.parseLong(artifact.getString("supplyTarget"));
        long coefficient = Long.parseLong(artifact.getString("coefficient"));
        int steps = artifact.getInt("steps");

        NetworkParameters mainnet = NetworkParameters.cleanMainnet();
        assertEquals(mainnet.supplyTarget(), supplyTarget,
            "artifact supplyTarget must equal NetworkParameters.cleanMainnet().supplyTarget()");
        assertEquals(mainnet.emissionCoefficient(), coefficient,
            "artifact coefficient must equal NetworkParameters.cleanMainnet().emissionCoefficient()");
        assertEquals(mainnet.emissionTableSteps(), steps,
            "artifact steps must equal NetworkParameters.cleanMainnet().emissionTableSteps()");

        // Built from the artifact's OWN header fields, never from a literal duplicated in this
        // test -- the point is that the checked-in artifact and a fresh, independent generator
        // run over those same fields agree, not that this test's constants match themselves.
        EmissionCurve curve = EmissionCurve.build(supplyTarget, coefficient, steps);

        JSONArray vectors = artifact.getJSONArray("vectors");
        assertTrue(vectors.length() > 0, "vector artifact must contain at least one vector");
        for (int i = 0; i < vectors.length(); i++) {
            JSONObject vector = vectors.getJSONObject(i);
            long supply = Long.parseLong(vector.getString("supply"));
            long expectedReward = Long.parseLong(vector.getString("rawReward"));
            assertEquals(expectedReward, curve.raw(supply),
                "raw(" + supply + ") mismatched the published vector");
        }
    }

    @Test
    void theVectorArtifactUsesDecimalStringsForAll64BitQuantities() {
        JSONObject artifact = loadArtifact();

        // Type-level assertions, not "parses as a decimal string" -- org.json's JSONTokener
        // preserves the JSON source type (String vs Number) on JSONObject#get, so a bare JSON
        // number (which would ALSO often re-parse as a valid long via toString()) is caught here
        // by failing `instanceof String`, not merely by a lenient getString() succeeding.
        assertTrue(artifact.get("supplyTarget") instanceof String,
            "supplyTarget must be a JSON string, not a bare JSON number");
        assertTrue(artifact.get("coefficient") instanceof String,
            "coefficient must be a JSON string, not a bare JSON number");
        assertTrue(artifact.get("domainFloor") instanceof String,
            "domainFloor must be a JSON string, not a bare JSON number");

        // steps is the one bare JSON integer permitted -- it fits safely in a JS Number (<= 256),
        // unlike the 64-bit fields above.
        assertTrue(artifact.get("steps") instanceof Number,
            "steps is the deliberate bare-integer exception (JS-safe magnitude)");

        JSONArray vectors = artifact.getJSONArray("vectors");
        assertTrue(vectors.length() > 0, "vector artifact must contain at least one vector");
        for (int i = 0; i < vectors.length(); i++) {
            JSONObject vector = vectors.getJSONObject(i);
            assertTrue(vector.get("supply") instanceof String,
                "vectors[" + i + "].supply must be a JSON string, not a bare JSON number");
            assertTrue(vector.get("rawReward") instanceof String,
                "vectors[" + i + "].rawReward must be a JSON string, not a bare JSON number");
        }
    }
}
