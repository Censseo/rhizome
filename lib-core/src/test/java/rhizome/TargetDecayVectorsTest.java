package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.EmissionCurve;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SupplyTargetSchedule;

/**
 * Locks reproducibility of the published decaying-supply-target algorithm (008-decaying-supply-
 * target, contracts/supply-target-schedule.md; G8/SC-003): a checked-in vector artifact, generated
 * once from the mainnet calibration constants, must be reproduced bit-for-bit by the real schedule
 * and curve classes built over the SAME header constants read out of that artifact — not out of a
 * literal duplicated here — so an independent implementation that disagrees fails this test instead
 * of forking the chain (WI-11, the {@code curve-vectors.json} precedent, modelled on
 * {@link EmissionCurveVectorsTest}).
 *
 * <p><b>Classification: test-vector artifact.</b> Nothing in {@code main} may load this resource;
 * it pins output, it is never a boot input. It therefore needs no decode bounds and no native-image
 * glob (research.md Decision 7) — and must never gain them, which would mean main started reading
 * it.
 *
 * <p>The artifact carries the full mainnet calibration and the shipped profile schedules it
 * (T045); the vectors are reproduced from the artifact's own header constants, and a separate
 * test pins the live profile's derived schedule against those same constants.
 */
class TargetDecayVectorsTest {

    private static final String RESOURCE = "/emission/target-decay-vectors.json";

    private static JSONObject loadArtifact() {
        try (InputStream in = TargetDecayVectorsTest.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                    "missing test resource " + RESOURCE
                        + " -- T022 generates and checks in the target-decay vector artifact");
            }
            return new JSONObject(new JSONTokener(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void thePublishedDecayVectorsAreReproducedBitForBit() {
        JSONObject artifact = loadArtifact();

        // Build the schedule and the curve from the artifact's OWN header fields, never from a
        // literal duplicated in this test -- the point is that the checked-in artifact and a
        // fresh, independent derivation over those same fields agree.
        long peak = Long.parseLong(artifact.getString("supplyTarget"));
        long coefficient = Long.parseLong(artifact.getString("coefficient"));
        int steps = artifact.getInt("steps");
        SupplyTargetSchedule schedule = SupplyTargetSchedule.build(peak,
            Long.parseLong(artifact.getString("decayStartHeight")),
            Long.parseLong(artifact.getString("decayEpochBlocks")),
            Long.parseLong(artifact.getString("decayNum")),
            Long.parseLong(artifact.getString("decayDen")),
            Long.parseLong(artifact.getString("supplyTargetFloor")),
            coefficient);
        EmissionCurve curve = EmissionCurve.build(peak, coefficient, steps);

        // The header's derived constants must equal the real derivation over the same inputs.
        assertEquals(schedule.epochsToFloor(), Long.parseLong(artifact.getString("epochsToFloor")),
            "artifact epochsToFloor must equal the exact iteration's value");
        assertEquals(schedule.floorArrivalHeight(),
            Long.parseLong(artifact.getString("floorArrivalHeight")),
            "artifact floorArrivalHeight must equal the derived value");
        assertEquals(schedule.perEpochReductionBound(),
            Long.parseLong(artifact.getString("perEpochReductionBound")),
            "artifact perEpochReductionBound must equal the derived value");

        JSONArray targets = artifact.getJSONArray("targets");
        assertTrue(targets.length() > 0, "the artifact must contain height->target vectors");
        for (int i = 0; i < targets.length(); i++) {
            JSONObject vector = targets.getJSONObject(i);
            long height = Long.parseLong(vector.getString("height"));
            long expected = Long.parseLong(vector.getString("target"));
            assertEquals(expected, schedule.targetAt(height),
                "targetAt(" + height + ") mismatched the published vector");
        }

        JSONArray rewards = artifact.getJSONArray("rewards");
        assertTrue(rewards.length() > 0, "the artifact must contain decayed reward vectors");
        for (int i = 0; i < rewards.length(); i++) {
            JSONObject vector = rewards.getJSONObject(i);
            long height = Long.parseLong(vector.getString("height"));
            long supply = Long.parseLong(vector.getString("supply"));
            long expected = Long.parseLong(vector.getString("rawReward"));
            assertEquals(expected, curve.raw(supply, schedule.targetAt(height)),
                "raw(" + supply + ", targetAt(" + height + ")) mismatched the published vector");
        }
    }

    @Test
    void theVectorArtifactUsesDecimalStringsForAll64BitQuantities() {
        JSONObject artifact = loadArtifact();

        // Type-level assertions, not "parses as a decimal string" (the EmissionCurveVectorsTest
        // discipline): a bare JSON number silently loses precision above 2^53 for a JS Number.
        for (String field : new String[] {"supplyTarget", "coefficient", "decayStartHeight",
                "decayEpochBlocks", "decayNum", "decayDen", "supplyTargetFloor", "epochsToFloor",
                "floorArrivalHeight", "perEpochReductionBound"}) {
            assertTrue(artifact.get(field) instanceof String,
                field + " must be a JSON string, not a bare JSON number");
        }
        assertTrue(artifact.get("steps") instanceof Number,
            "steps is the deliberate bare-integer exception (JS-safe magnitude)");

        JSONArray targets = artifact.getJSONArray("targets");
        for (int i = 0; i < targets.length(); i++) {
            JSONObject vector = targets.getJSONObject(i);
            assertTrue(vector.get("height") instanceof String,
                "targets[" + i + "].height must be a JSON string");
            assertTrue(vector.get("target") instanceof String,
                "targets[" + i + "].target must be a JSON string");
        }
        JSONArray rewards = artifact.getJSONArray("rewards");
        for (int i = 0; i < rewards.length(); i++) {
            JSONObject vector = rewards.getJSONObject(i);
            assertTrue(vector.get("height") instanceof String,
                "rewards[" + i + "].height must be a JSON string");
            assertTrue(vector.get("supply") instanceof String,
                "rewards[" + i + "].supply must be a JSON string");
            assertTrue(vector.get("rawReward") instanceof String,
                "rewards[" + i + "].rawReward must be a JSON string");
        }
    }

    /**
     * T024 (FR-004): the sibling peak-curve artifact stays BYTE-identical — the cheapest possible
     * proof that the decay touched no peak-curve value, and the canary for the
     * {@code liveTarget == peak} short-circuit being broken. Pinned by checksum: if this test
     * fails, curve-vectors.json changed and the change must be a deliberate, separately reviewed
     * regeneration — never a side effect of touching the decay.
     */
    @Test
    void theLegacyCurveVectorsArtifactStaysByteIdentical() throws Exception {
        String checksum = sha256OfClasspathResource("/emission/curve-vectors.json");
        assertEquals("8f9caedfe87a519702903be1f187bb71a449a4ef84cff4faa4285c668eabbfdd", checksum,
            "curve-vectors.json changed: the peak-curve vector artifact must be regenerated "
                + "deliberately, never as a side effect of the decaying-target feature (FR-004)");
    }

    private static String sha256OfClasspathResource(String resource) throws Exception {
        try (InputStream in = TargetDecayVectorsTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + resource);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        }
    }

    /** Sanity: the artifact's constants remain the shipped mainnet calibration, and the live
     *  profile's derived schedule equals the artifact's own header-derived one — the two
     *  artifacts and the live profile describe one schedule (T045 landed: mainnet is active). */
    @Test
    void theArtifactCarriesTheShippedMainnetCalibration() {
        JSONObject artifact = loadArtifact();
        NetworkParameters mainnet = NetworkParameters.cleanMainnet();
        assertEquals(mainnet.supplyTarget(), Long.parseLong(artifact.getString("supplyTarget")),
            "artifact supplyTarget must equal cleanMainnet()'s");
        assertEquals(mainnet.emissionCoefficient(), Long.parseLong(artifact.getString("coefficient")),
            "artifact coefficient must equal cleanMainnet()'s");
        assertEquals(mainnet.emissionTableSteps(), artifact.getInt("steps"),
            "artifact steps must equal cleanMainnet()'s");

        // The live schedule's constants and derived values equal the artifact's header, and the
        // live targetAt equals the artifact-derived schedule at sampled heights.
        SupplyTargetSchedule fromArtifact = SupplyTargetSchedule.build(
            Long.parseLong(artifact.getString("supplyTarget")),
            Long.parseLong(artifact.getString("decayStartHeight")),
            Long.parseLong(artifact.getString("decayEpochBlocks")),
            Long.parseLong(artifact.getString("decayNum")),
            Long.parseLong(artifact.getString("decayDen")),
            Long.parseLong(artifact.getString("supplyTargetFloor")),
            Long.parseLong(artifact.getString("coefficient")));
        SupplyTargetSchedule live = mainnet.supplyTargetSchedule();
        assertTrue(live.isScheduled(), "mainnet schedules the decay (T045)");
        assertEquals(fromArtifact.startHeight(), live.startHeight());
        assertEquals(fromArtifact.floorArrivalHeight(), live.floorArrivalHeight());
        assertEquals(fromArtifact.perEpochReductionBound(), live.perEpochReductionBound());
        for (long h : new long[] {1, 1_000, live.startHeight() - 1, live.startHeight(),
                live.startHeight() + live.epochBlocks(), live.floorArrivalHeight(),
                Long.MAX_VALUE}) {
            assertEquals(fromArtifact.targetAt(h), live.targetAt(h),
                "the live schedule must equal the artifact-derived schedule at " + h);
        }
    }
}
