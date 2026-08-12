package rhizome.node;

import java.util.function.Consumer;

import rhizome.core.block.Block;
import rhizome.core.transaction.Transaction;

/**
 * What to do when a pushed block or transaction is ACCEPTED: in production, re-broadcast it, so
 * the network floods from whatever node received it first.
 *
 * <p>Separate from {@link NodeSources} because it is the other direction — these are called, not
 * read — and because the two are supplied by different halves of the assembly: the sources are
 * stores and processors, the listeners are the gossip broadcaster.
 *
 * @param onBlockAccepted       null when nothing should be re-broadcast (every test but the
 *                              gossip ones)
 * @param onTransactionAccepted likewise
 */
public record NodeListeners(
    Consumer<Block> onBlockAccepted,
    Consumer<Transaction> onTransactionAccepted) {

    private static final NodeListeners NONE = new NodeListeners(null, null);

    /** Accept and keep: no gossip fan-out. */
    public static NodeListeners none() {
        return NONE;
    }
}
