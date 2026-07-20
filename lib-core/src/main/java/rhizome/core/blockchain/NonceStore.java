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

    /** True when no sender has a recorded nonce (a fresh store, before any account tx). */
    boolean isEmpty();

    /** Visits every {@code (sender, nextNonce)} pair — used to export the account-nonce domain. */
    void forEach(ObjLongConsumer<PublicAddress> consumer);
}
