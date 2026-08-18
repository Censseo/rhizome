package rhizome.adversarial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.ChainSynchronizer;
import rhizome.core.blockchain.ChainSynchronizer.Result;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.crypto.SHA256Hash;

/**
 * History rewriting, and the exact depth at which it stops (REORG family — see
 * docs/adversarial/spec.md).
 *
 * <p>This is where the protocol names its irreducible residual honestly. A majority of hash power
 * <em>can</em> rewrite recent history; no proof-of-work chain removes that. What Rhizome bounds is
 * the <em>depth</em>: past {@code maxReorgDepth} a heavier branch is refused whatever work it
 * claims, so buried history is final by rule rather than by economics.
 *
 * <p>A bound is only meaningful if both of its sides are pinned, which is why REORG-02 is proved
 * twice over: the window is genuinely open at its documented depth (a node that refused everything
 * would look "secure" while being unable to follow the real chain) and genuinely shut one block
 * deeper. REORG-03 then proves the refusal costs the victim nothing — no pop, no lost work.
 */
class ReorgAttackTest {

    /** A network whose finality window is small enough to probe exhaustively. */
    private static final int WINDOW = 3;

    private static NetworkParameters params() {
        return NetworkParameters.testnet().toBuilder().maxReorgDepth(WINDOW).build();
    }

    /**
     * Two chains on identical parameters and an identical (empty) genesis ledger, so they share a
     * genesis block and fork at height 1. No account is funded: a funded snapshot would give each
     * chain its own genesis hash and the peer would read as INCOMPATIBLE rather than as a fork.
     */
    private static AdversarialChain node() {
        return AdversarialChain.on(params()).build();
    }

    /**
     * Puts the victim's clock where the attacker's branch was actually mined. Each fixture drives
     * its own clock, so a longer attacker branch ends up stamped past the victim's "now" — and the
     * stateless gate would then refuse it as a future-dated branch (BLOCK_TIMESTAMP_IN_FUTURE)
     * rather than on the depth rule these scenarios are about. A withheld branch is by definition
     * released after it was mined, so this is the honest reading of the attack, not a weakening of
     * the test: TIME-01 covers the future bound on its own terms.
     */
    private static void releaseBranchTo(AdversarialChain victim, AdversarialChain attacker) {
        victim.setClock(Math.max(victim.now(), attacker.now()));
    }

    /**
     * REORG-02 — the open side of the bound, and REORG-01's residual made visible. A withheld
     * branch that is heavier and forks exactly {@code maxReorgDepth} blocks back <em>is</em>
     * adopted: within the window, more proven work wins, which is the property that makes the
     * chain converge at all.
     */
    @Test
    void aHeavierWithheldBranchForkingExactlyAtTheWindowIsAdopted() {
        AdversarialChain victim = node();
        AdversarialChain attacker = node();
        assertEquals(victim.engine().blockAt(1).hash(), attacker.engine().blockAt(1).hash(),
            "both nodes must share a genesis, or this is not a fork");

        victim.extendBy(WINDOW);            // height 1 + 3 = 4, so the fork at height 1 is depth 3
        attacker.extendBy(WINDOW + 2);      // strictly heavier
        releaseBranchTo(victim, attacker);

        SHA256Hash attackerTip = attacker.engine().tipHash();
        Result result = new ChainSynchronizer(victim.engine())
            .syncFrom(AdversarialPeer.honest(attacker.engine()));

        assertEquals(Result.REORGED, result);
        assertEquals(attackerTip, victim.engine().tipHash(), "the heavier branch became canonical");
        assertEquals(WINDOW + 3, victim.height());
    }

    /**
     * REORG-02 — one block deeper, the same attack is refused outright. Note what is <em>not</em>
     * compared: the branch is heavier by real, proven work and is still refused. Depth is the rule,
     * not work.
     */
    @Test
    void oneBlockPastTheWindowTheSameHeavierBranchIsRefused() {
        AdversarialChain victim = node();
        AdversarialChain attacker = node();

        victim.extendBy(WINDOW + 1);        // fork at height 1 is now depth 4 > maxReorgDepth
        attacker.extendBy(WINDOW + 4);      // and heavier still
        releaseBranchTo(victim, attacker);

        SHA256Hash tipBefore = victim.engine().tipHash();
        long heightBefore = victim.height();
        java.math.BigInteger workBefore = victim.engine().totalWork();

        Result result = new ChainSynchronizer(victim.engine())
            .syncFrom(AdversarialPeer.honest(attacker.engine()));

        assertEquals(Result.REORG_TOO_DEEP, result);
        assertEquals(tipBefore, victim.engine().tipHash(), "buried history is not rewritten");
        assertEquals(heightBefore, victim.height());
        assertEquals(workBefore, victim.engine().totalWork());
    }

    /**
     * REORG-03 — refusing must be free. A branch the node will not adopt must cost it no local
     * mutation at all: the deep-fork peer is a fork, not misbehaviour, so the node keeps its chain,
     * keeps its work, and stays able to extend immediately.
     */
    @Test
    void aRefusedDeepReorgLeavesTheNodeAbleToKeepMiningItsOwnBranch() {
        AdversarialChain victim = node();
        AdversarialChain attacker = node();

        victim.extendBy(WINDOW + 1);
        attacker.extendBy(WINDOW + 4);
        releaseBranchTo(victim, attacker);

        assertEquals(Result.REORG_TOO_DEEP, new ChainSynchronizer(victim.engine())
            .syncFrom(AdversarialPeer.honest(attacker.engine())));

        long heightAfterRefusal = victim.height();
        victim.extendBy(1);
        assertEquals(heightAfterRefusal + 1, victim.height(),
            "the refusal left no degraded state or open reorg window behind");
        assertTrue(victim.engine().totalWork().signum() > 0);
    }
}
