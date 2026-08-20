package rhizome.adversarial;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import rhizome.core.block.Block;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.PeerSource;
import rhizome.crypto.SHA256Hash;

/**
 * The counterparty in a synchronisation scenario: a {@link PeerSource} serving another engine's
 * branch faithfully.
 *
 * <p>"Honest" describes the transport, not the intent. The attacks it carries — a withheld branch
 * released to force a reorg, a fork deeper than the finality window — are made of entirely valid
 * blocks, which is what makes them interesting: the victim cannot refuse them on validity, only on
 * the depth rule. Scenarios where the <em>peer itself</em> lies (claimed-but-unproven work,
 * over-reported height, malformed windows) are proven where their fixtures already live, in
 * {@code HardeningTest}, {@code ChainSynchronizerTest} and {@code HeaderSynchronizerTest}; this
 * class deliberately carries no lying machinery of its own rather than shipping a second,
 * unexercised copy of it.
 */
public final class AdversarialPeer implements PeerSource {

    private final ChainEngine engine;

    private AdversarialPeer(ChainEngine engine) {
        this.engine = engine;
    }

    /** Serves {@code engine}'s branch as a real peer would. */
    public static AdversarialPeer honest(ChainEngine engine) {
        return new AdversarialPeer(engine);
    }

    @Override
    public long height() {
        return engine.height();
    }

    @Override
    public BigInteger totalWork() {
        return engine.totalWork();
    }

    @Override
    public SHA256Hash blockHash(long height) {
        return engine.blockAt(height).hash();
    }

    /**
     * Serves a referenced orphan the same way the real {@code /orphan} endpoint does — required
     * for any branch that cites uncles: without it, {@code validateUncles} cannot fetch an
     * eligible reference from a fresh pool and every such sync fails {@code INVALID_UNCLES} /
     * {@code PEER_INVALID}, which is a silent scenario failure (Rule 2) rather than a fidelity
     * nicety. The default in {@link PeerSource#orphan} returns {@code null} precisely because a
     * transport that predates uncles has nothing to serve here; this peer is never that transport.
     */
    @Override
    public Block orphan(SHA256Hash hash) {
        return engine.orphanBlock(hash);
    }

    /**
     * The peer's bodies for the requested range, clamped to what the branch actually holds. The
     * synchronizer deliberately over-fetches past the fork depth, so a fixture that answered the
     * full range with filler would make every honest peer read as PEER_INVALID — turning an
     * adoption scenario into a rejection scenario that still passes its assertion count.
     */
    @Override
    public List<Block> blocks(long start, long end) {
        List<Block> out = new ArrayList<>();
        for (long h = start; h <= Math.min(end, engine.height()); h++) {
            out.add(engine.blockAt(h));
        }
        return out;
    }
}
