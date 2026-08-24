package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
import rhizome.core.block.HeaderCodec;
import rhizome.core.block.UncleRef;
import rhizome.core.blockchain.Issuance;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.PublicAddress;
import rhizome.crypto.SHA256Hash;
import rhizome.node.RhizomeNode;

/**
 * End-to-end header-sync proofs for the supply-driven logarithmic emission curve (§ integer log
 * curve): does a real syncing node reject a forged {@code supply} field carried in a headers-only
 * response, under a curve-active profile, before ever fetching a body? {@code
 * E2ESupplyCommitmentTest}'s E2E-35 proves the equivalent claim under a curve-INACTIVE profile
 * (the geometric schedule); these tests extend the same header-sync boundary to profiles where
 * {@code HeaderChain.checkSupply}'s {@code Issuance.minted} call actually dispatches into
 * {@code EmissionCurve.raw}, and to a forgery chosen to look plausible for the curve specifically
 * rather than an arbitrary offset.
 */
class E2ECurveHeaderSyncTest {

    @TempDir
    Path tempDir;

    private record Untouched(SHA256Hash secondBlock, long height, BigInteger work) {
        static Untouched of(RhizomeNode node) {
            return new Untouched(node.engine().blockAt(2).hash(), node.engine().height(),
                node.engine().totalWork());
        }
    }

    /** The "refusal is free" assertion, mirroring {@code E2ESupplyCommitmentTest}'s own. */
    private static void assertSurvivedIntact(RhizomeNode victim, Untouched before)
            throws InterruptedException {
        assertEquals(before.secondBlock(), victim.engine().blockAt(2).hash(),
            "the victim's history was rewritten by a peer that proved nothing");
        assertTrue(victim.engine().height() >= before.height(),
            "the victim lost chain height to a hostile peer");
        assertTrue(victim.engine().totalWork().compareTo(before.work()) >= 0,
            "the victim lost accumulated work");
        assertFalse(victim.engine().isDegraded(),
            "the encounter left the victim degraded, which halts every new-tip write");

        long resumed = victim.engine().height();
        TestNetwork.await(() -> victim.engine().height() > resumed,
            () -> "the victim stopped producing blocks after the encounter");
    }

    private static void admit(RhizomeNode node, String peerUrl) throws InterruptedException {
        node.service().addPeer(peerUrl);
        TestNetwork.await(() -> node.knownPeers().contains(peerUrl),
            () -> "peer " + peerUrl + " was never admitted; known: " + node.knownPeers());
    }

    private static void meet(RhizomeNode victim, String peerUrl) throws InterruptedException {
        admit(victim, peerUrl);
        for (int round = 0; round < 4; round++) {
            victim.syncRound();
        }
    }

    /**
     * E2E-67 — Serve a real syncing node, under a curve-active profile, a headers-only response
     * whose final header carries a forged {@code supply} copied from ANOTHER real height on the
     * same source chain (a plausible-for-the-curve value, not an arbitrary offset), over a real
     * socket via {@link HostilePeer}. Extends the pre-existing E2E-35 (which never exercises the
     * curve dispatch inside {@code Issuance.minted}) to a profile where it does.
     */
    @Test
    void aCurvePlausibleForgedSupplyInAHeadersOnlyResponseLeavesTheVictimsChainUntouched() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode source = network.node("source").params(TestNetwork.CURVE_ACTIVE)
                .mining().blockInterval(150).start();
            TestNetwork.awaitHeight(source, 7);

            long prefixTop = 6;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (long h = 1; h <= prefixTop; h++) {
                out.writeBytes(HeaderCodec.encode(source.engine().headerAt(h)));
            }
            BlockHeader honestLast = source.engine().headerAt(prefixTop + 1);
            // "Plausible for the curve": a real committed supply from ANOTHER real height on this
            // same chain, not an arbitrary +1 offset -- exactly the value an attacker who has
            // watched this chain's own headers could lift verbatim.
            long plausibleForgedSupply = source.engine().headerAt(prefixTop - 1).supply();
            assertNotEquals(honestLast.supply(), plausibleForgedSupply,
                "the forged value must genuinely differ from the honest one for this scenario to mean anything");
            BlockHeader forged = new BlockHeader(
                honestLast.id(), honestLast.timestamp(), honestLast.difficulty(),
                honestLast.numTransactions(), honestLast.lastBlockHash(), honestLast.merkleRoot(),
                honestLast.nonce(), honestLast.stateRoot(), honestLast.vote(),
                plausibleForgedSupply, honestLast.uncles());
            out.writeBytes(HeaderCodec.encode(forged));
            byte[] hostileHeaders = out.toByteArray();

            RhizomeNode victim = network.node("victim").params(TestNetwork.CURVE_ACTIVE)
                .mining().blockInterval(150).start();
            TestNetwork.awaitHeight(victim, 5);
            Untouched before = Untouched.of(victim);

            try (HostilePeer liar = HostilePeer.builder()
                    .sharesGenesisWith(source)
                    .claimsHeight(prefixTop + 1)
                    .claimsWork(() -> new JSONObject().put("totalWork",
                        BigInteger.TWO.pow(200).toString()).toString())
                    .servesHeaders(() -> hostileHeaders)
                    .start()) {
                meet(victim, liar.url());
                assertSurvivedIntact(victim, before);

                long forgedHeight = prefixTop + 1;
                TestNetwork.await(() -> victim.engine().height() >= forgedHeight,
                    () -> "the victim never resumed mining up to the forged height");
                assertNotEquals(forged.hash(), victim.engine().blockAt(forgedHeight).hash(),
                    "the victim must never adopt the branch carrying the curve-plausible forged supply");
            }
        }
    }

    /**
     * E2E-68 — Mine an honest prefix under a curve-active profile, then serve a header stream
     * whose final header drops an already-committed {@code supply} back to {@code SUPPLY_ABSENT}
     * ({@code -1}), attempting to "exit" the commitment mid-chain. Verified in the code: the
     * dedicated guard in {@code HeaderChain.checkSupply} ({@code headerSupply < 0 ->
     * INVALID_SUPPLY}) applies unconditionally, independent of {@code emissionCurveActiveAt} --
     * the curve dispatch inside {@code Issuance.minted} is never even reached for this rejection.
     */
    @Test
    void aHeaderThatDropsAnAlreadyCommittedSupplyMidCurveIsRejectedBeforeAnyBodyIsFetched() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode source = network.node("source").params(TestNetwork.CURVE_ACTIVE)
                .mining().blockInterval(150).start();
            TestNetwork.awaitHeight(source, 7);

            long prefixTop = 6;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (long h = 1; h <= prefixTop; h++) {
                out.writeBytes(HeaderCodec.encode(source.engine().headerAt(h)));
            }
            BlockHeader honestLast = source.engine().headerAt(prefixTop + 1);
            BlockHeader forged = new BlockHeader(
                honestLast.id(), honestLast.timestamp(), honestLast.difficulty(),
                honestLast.numTransactions(), honestLast.lastBlockHash(), honestLast.merkleRoot(),
                honestLast.nonce(), honestLast.stateRoot(), honestLast.vote(),
                BlockImpl.SUPPLY_ABSENT, honestLast.uncles());
            out.writeBytes(HeaderCodec.encode(forged));
            byte[] hostileHeaders = out.toByteArray();

            RhizomeNode victim = network.node("victim").params(TestNetwork.CURVE_ACTIVE)
                .mining().blockInterval(150).start();
            TestNetwork.awaitHeight(victim, 5);
            Untouched before = Untouched.of(victim);

            try (HostilePeer liar = HostilePeer.builder()
                    .sharesGenesisWith(source)
                    .claimsHeight(prefixTop + 1)
                    .claimsWork(() -> new JSONObject().put("totalWork",
                        BigInteger.TWO.pow(200).toString()).toString())
                    .servesHeaders(() -> hostileHeaders)
                    .start()) {
                meet(victim, liar.url());
                assertSurvivedIntact(victim, before);

                long forgedHeight = prefixTop + 1;
                TestNetwork.await(() -> victim.engine().height() >= forgedHeight,
                    () -> "the victim never resumed mining up to the forged height");
                assertNotEquals(forged.hash(), victim.engine().blockAt(forgedHeight).hash(),
                    "the victim must never adopt the branch that dropped its supply commitment");
            }
        }
    }

    /**
     * Test-local mirror of {@code Executor.scaleRewardToWork} (package-private to
     * {@code rhizome.core.blockchain}, unreachable from this package): halves {@code base} once
     * per bit of difficulty deficit, exactly the scaling {@link Issuance#minted} applies to every
     * uncle/nephew term. Reimplemented here only so the test can compute the STALE forged total
     * described in E2E-76 without reaching into package-private production code.
     */
    private static long scaleRewardToWork(long base, int difficultyDeficit) {
        if (difficultyDeficit <= 0) {
            return base;
        }
        if (difficultyDeficit >= Long.SIZE) {
            return 0;
        }
        return base >>> difficultyDeficit;
    }

    /**
     * E2E-76 — Forge a header whose base term exactly equals {@code EmissionCurve.raw(parentSupply)}
     * but whose uncle/nephew contribution is computed with the STALE, pre-curve, height-only
     * divisors ({@code uncleReward(height)}/{@code nephewReward(height)}) instead of their
     * curve-sensitive twins ({@code uncleReward(height,parentSupply)}/{@code
     * nephewReward(height,parentSupply)}) -- betting that the gate checks the base carefully but
     * is laxer about the uncle contribution. Verified in the code: {@code Issuance.minted} scales
     * EVERY uncle/nephew term from the curve-sensitive {@code params.uncleReward(height,parentSupply)}/
     * {@code nephewReward(height,parentSupply)} forms (never the height-only ones), and {@code
     * HeaderChain.checkSupply} always recomputes the WHOLE sum through {@code Issuance.minted} --
     * there is no separate, more lenient path for the uncle/nephew portion, so a header whose
     * declared supply matches only the stale-divisor sum is rejected exactly like any other wrong
     * total, over a real socket via {@link HostilePeer}, before this header's PoW is ever checked
     * ({@code checkSupply} runs before the PoW gate in {@code HeaderChain.validate}).
     */
    @Test
    void aHeaderWhoseUncleContributionUsesTheStaleHeightOnlyDivisorsInsteadOfTheCurveSensitiveOnesIsRejected()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode source = network.node("source").params(TestNetwork.CURVE_ACTIVE)
                .mining().blockInterval(150).start();
            TestNetwork.awaitHeight(source, 7);

            long prefixTop = 6;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (long h = 1; h <= prefixTop; h++) {
                out.writeBytes(HeaderCodec.encode(source.engine().headerAt(h)));
            }
            BlockHeader honestLast = source.engine().headerAt(prefixTop + 1);
            NetworkParameters params = source.engine().params();
            long height = prefixTop + 1;
            long parentSupply = source.engine().headerAt(prefixTop).supply();
            int nephewDifficulty = honestLast.difficulty();

            // Structurally plausible uncles (same shape E2E-66 uses): a zero difficulty deficit,
            // so any drift below is entirely attributable to which divisor form fed the scaling,
            // never to the deficit-scaling arithmetic itself.
            List<UncleRef> fabricatedUncles = List.of(
                new UncleRef(SHA256Hash.random(), nephewDifficulty, PublicAddress.random()));

            // The honest, curve-sensitive total for this header's own (height, parentSupply,
            // uncles) -- the SAME identity checkSupply itself recomputes via Issuance.minted.
            long honestTotal = Issuance.minted(params, height, parentSupply, nephewDifficulty, fabricatedUncles);

            // The forged total: a correct curve-aware BASE, but uncle/nephew terms scaled from
            // the STALE, height-only (pre-curve) divisors instead of their curve-sensitive twins.
            long base = params.miningReward(height, parentSupply);
            long staleUncleReward = params.uncleReward(height);
            long staleNephewReward = params.nephewReward(height);
            long forgedTotal = base;
            for (UncleRef uncle : fabricatedUncles) {
                int deficit = nephewDifficulty - uncle.difficulty();
                forgedTotal = Math.addExact(forgedTotal, scaleRewardToWork(staleUncleReward, deficit));
                forgedTotal = Math.addExact(forgedTotal, scaleRewardToWork(staleNephewReward, deficit));
            }
            assertNotEquals(honestTotal, forgedTotal,
                "the stale-divisor total must genuinely differ from the curve-sensitive one for "
                    + "this forgery to mean anything");

            long forgedSupply = Math.addExact(parentSupply, forgedTotal);
            BlockHeader forged = new BlockHeader(
                honestLast.id(), honestLast.timestamp(), honestLast.difficulty(),
                honestLast.numTransactions(), honestLast.lastBlockHash(), honestLast.merkleRoot(),
                honestLast.nonce(), honestLast.stateRoot(), honestLast.vote(),
                forgedSupply, fabricatedUncles);
            out.writeBytes(HeaderCodec.encode(forged));
            byte[] hostileHeaders = out.toByteArray();

            RhizomeNode victim = network.node("victim").params(TestNetwork.CURVE_ACTIVE)
                .mining().blockInterval(150).start();
            TestNetwork.awaitHeight(victim, 5);
            Untouched before = Untouched.of(victim);

            try (HostilePeer liar = HostilePeer.builder()
                    .sharesGenesisWith(source)
                    .claimsHeight(prefixTop + 1)
                    .claimsWork(() -> new JSONObject().put("totalWork",
                        BigInteger.TWO.pow(200).toString()).toString())
                    .servesHeaders(() -> hostileHeaders)
                    .start()) {
                meet(victim, liar.url());
                assertSurvivedIntact(victim, before);

                long forgedHeight = prefixTop + 1;
                TestNetwork.await(() -> victim.engine().height() >= forgedHeight,
                    () -> "the victim never resumed mining up to the forged height");
                assertNotEquals(forged.hash(), victim.engine().blockAt(forgedHeight).hash(),
                    "the victim must never adopt a branch whose uncle contribution used the stale, "
                        + "height-only divisors");
            }
        }
    }

    /**
     * E2E-77 — Serve a forged header claiming a parent supply near {@code Long.MAX_VALUE}, to
     * verify the syncing thread survives cleanly (a rejection) rather than crashing on an
     * uncaught exception. Verified by re-reading the code: {@code HeaderChain.checkSupply} wraps
     * the ENTIRE {@code Issuance.minted} call (not just the outer sum) in a {@code
     * try/catch(ArithmeticException) -> INVALID_SUPPLY}, and {@code EmissionCurve.mirror()} is
     * bounded by construction to {@code [0, supplyTarget]} for any input -- so a real crash is
     * implausible here. This is a non-regression confirmation rather than a probe for a likely
     * bug: build an honest prefix through a real mining node, then a final forged header whose
     * {@code supply} field is {@code Long.MAX_VALUE}, served by a {@link HostilePeer}, admitted
     * on a real victim -- and assert the victim survives untouched and its sync/mining loop keeps
     * functioning afterward (it resumes mining normally).
     */
    @Test
    void aForgedSupplyNearLongMaxValueIsRejectedWithoutCrashingTheSyncThread() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode source = network.node("source").params(TestNetwork.CURVE_ACTIVE)
                .mining().blockInterval(150).start();
            TestNetwork.awaitHeight(source, 7);

            long prefixTop = 6;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (long h = 1; h <= prefixTop; h++) {
                out.writeBytes(HeaderCodec.encode(source.engine().headerAt(h)));
            }
            BlockHeader honestLast = source.engine().headerAt(prefixTop + 1);
            assertNotEquals(Long.MAX_VALUE, honestLast.supply(),
                "the forged value must genuinely differ from the honest one for this scenario to mean anything");
            BlockHeader forged = new BlockHeader(
                honestLast.id(), honestLast.timestamp(), honestLast.difficulty(),
                honestLast.numTransactions(), honestLast.lastBlockHash(), honestLast.merkleRoot(),
                honestLast.nonce(), honestLast.stateRoot(), honestLast.vote(),
                Long.MAX_VALUE, honestLast.uncles());
            out.writeBytes(HeaderCodec.encode(forged));
            byte[] hostileHeaders = out.toByteArray();

            RhizomeNode victim = network.node("victim").params(TestNetwork.CURVE_ACTIVE)
                .mining().blockInterval(150).start();
            TestNetwork.awaitHeight(victim, 5);
            Untouched before = Untouched.of(victim);

            try (HostilePeer liar = HostilePeer.builder()
                    .sharesGenesisWith(source)
                    .claimsHeight(prefixTop + 1)
                    .claimsWork(() -> new JSONObject().put("totalWork",
                        BigInteger.TWO.pow(200).toString()).toString())
                    .servesHeaders(() -> hostileHeaders)
                    .start()) {
                meet(victim, liar.url());
                // The core of this scenario: the encounter must leave the victim exactly as
                // healthy as any other rejected forgery -- no crash, no degradation, no lost
                // history or work -- confirming an extreme header field is handled by the same
                // ordinary rejection path as a plausible one, not a special (and riskier) one.
                assertSurvivedIntact(victim, before);

                long forgedHeight = prefixTop + 1;
                TestNetwork.await(() -> victim.engine().height() >= forgedHeight,
                    () -> "the victim never resumed mining up to the forged height");
                assertNotEquals(forged.hash(), victim.engine().blockAt(forgedHeight).hash(),
                    "the victim must never adopt the branch claiming a supply near Long.MAX_VALUE");
            }
        }
    }
}
