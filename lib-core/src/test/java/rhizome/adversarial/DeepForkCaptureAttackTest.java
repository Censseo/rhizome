package rhizome.adversarial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockHeader;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ChainSynchronizer.Result;
import rhizome.core.blockchain.HeaderSynchronizer;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.PeerSource;
import rhizome.crypto.SHA256Hash;

/**
 * Finality-window capture: a branch past {@code maxReorgDepth} is refused by rule, but a refusal
 * that stays silent lets a node sit on a minority chain forever while a heavier network moves on.
 */
class DeepForkCaptureAttackTest {

    private static final int WINDOW = 3;

    private static AdversarialChain node() {
        return AdversarialChain.on(NetworkParameters.testnet().toBuilder().maxReorgDepth(WINDOW).build())
            .build();
    }

    /** A real peer over an engine, serving /headers like the HTTP transport does. */
    private record HeaderPeer(ChainEngine e) implements PeerSource {
        @Override public long height() { return e.height(); }
        @Override public BigInteger totalWork() { return e.totalWork(); }
        @Override public SHA256Hash blockHash(long h) { return e.blockAt(h).hash(); }
        @Override public List<Block> blocks(long start, long end) {
            List<Block> out = new ArrayList<>();
            for (long h = start; h <= Math.min(end, e.height()); h++) out.add(e.blockAt(h));
            return out;
        }
        @Override public List<BlockHeader> headers(long start, long end) {
            List<BlockHeader> out = new ArrayList<>();
            for (long h = start; h <= Math.min(end, e.height()); h++) out.add(e.headerAt(h));
            return out;
        }
        @Override public String describe() { return "http://attacker.test"; }
    }

    /**
     * REORG-15 — Mine a branch that forks past {@code maxReorgDepth} and carries far more proven
     * work than the victim's own (the victim is the minority, e.g. after a long partition): the
     * depth rule rightly refuses to reorg, but silently doing so would leave the node serving and
     * mining a dead branch with no signal. The refused branch's headers are still validated
     * (bounded window); when their proven work exceeds twice the victim's work above the fork, the
     * node is marked degraded for the operator (reason names the peer, the fork height and the
     * work ratio, visible on /stats) and logged at ERROR. Never auto-reorged past the window, and
     * the result stays REORG_TOO_DEEP so the peer is not penalised. A merely longer-but-not-2x
     * branch raises no flag.
     */
    @Test
    void aMuchHeavierBranchPastTheFinalityWindowDegradesTheNodeButIsNeverAdopted() {
        // Not 2x: 6 blocks against 4 -> refused quietly, no flag.
        AdversarialChain victim = node();
        AdversarialChain modest = node();
        victim.extendBy(WINDOW + 1);
        modest.extendBy(6);
        victim.setClock(Math.max(victim.now(), modest.now()));
        assertEquals(Result.REORG_TOO_DEEP,
            new HeaderSynchronizer(victim.engine()).syncFrom(new HeaderPeer(modest.engine())));
        assertNull(victim.engine().degradedState(), "a branch under the 2x margin raises no flag");

        // 2.5x: 10 blocks against 4 -> refused, but the operator is told.
        AdversarialChain heavy = node();
        heavy.extendBy(WINDOW + 7);
        victim.setClock(Math.max(victim.now(), heavy.now()));
        SHA256Hash tipBefore = victim.engine().tipHash();
        long heightBefore = victim.height();

        assertEquals(Result.REORG_TOO_DEEP,
            new HeaderSynchronizer(victim.engine()).syncFrom(new HeaderPeer(heavy.engine())));

        String degraded = victim.engine().degradedState();
        assertNotNull(degraded, "a deep fork with more work must be surfaced to the operator");
        assertTrue(degraded.contains("deep fork with more work"), degraded);
        assertTrue(degraded.contains("http://attacker.test"), degraded);
        assertTrue(degraded.contains("fork height 1"), degraded);
        assertTrue(degraded.contains("ratio"), degraded);
        assertEquals(tipBefore, victim.engine().tipHash(), "never auto-reorged past maxReorgDepth");
        assertEquals(heightBefore, victim.height());
    }
}
