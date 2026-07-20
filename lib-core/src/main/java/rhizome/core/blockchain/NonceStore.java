package rhizome.core.blockchain;

import java.util.function.ObjLongConsumer;

import rhizome.core.ledger.PublicAddress;

/**
 * The next expected account nonce per sender — the engine's replay-protection
 * state. Persisting it is what lets a node rebuild its derived state without
 * walking historical transaction bodies: on a normal restart the nonces are
 * already here, so the boot pass is O(headers) rather than O(transactions), and
 * a pruned node (whose old bodies are gone) can still validate the sequential
 * nonce rule.
 *
 * <p>Updated incrementally by the engine on every block applied ({@link #set}
 * to {@code max(current, txNonce+1)}) and reverted on a pop. {@link #forEach}
 * enumerates the full mapping for the state snapshot export (snap-sync).
 */
public interface NonceStore {

    /** The next nonce expected from {@code sender}; {@code 0} if the sender has never sent. */
    long next(PublicAddress sender);

    /** Sets the next nonce for {@code sender}; a value {@code <= 0} removes the entry. */
    void set(PublicAddress sender, long next);

    /**
     * The chain height through which these nonces are known to be current — the tip the
     * store was last {@link #markSyncedThrough(long) marked} at. {@code 0} means the nonces
     * have never been synced (a fresh column family, or a transient in-memory store at boot),
     * which is what tells the engine to reconstruct them from block bodies. A persistent store
     * that advanced in lockstep with the chain reports the tip here, so a restart — even of a
     * pruned node whose old bodies are gone, and even when no account has ever sent — skips the
     * body walk entirely.
     */
    long syncedThroughHeight();

    /** Records that the nonces are current as of chain height {@code height}. */
    void markSyncedThrough(long height);

    /** Visits every {@code (sender, nextNonce)} pair — used to export the account-nonce domain. */
    void forEach(ObjLongConsumer<PublicAddress> consumer);
}
