package rhizome.core.blockchain;

import java.util.List;

import rhizome.core.block.BlockHeader;
import rhizome.core.box.BoxStore;
import rhizome.core.ledger.Ledger;
import rhizome.core.state.RootStore;
import rhizome.core.state.SmtNodeStore;
import rhizome.core.token.TokenStore;

/**
 * The stores a snap-sync bootstrap seeds, as one port instead of five concrete backend types.
 *
 * <p>{@code SnapshotBootstrap} does protocol work — it drives {@code PeerSource}, validates a
 * header window under full proof of work, applies the finality rule and checks the reconstructed
 * root against the pivot header — which is the same class of logic as {@code ChainSynchronizer} and
 * {@code HeaderSynchronizer}, both of which live here behind {@code PeerSource}. It was typed on
 * five RocksDB classes instead, four of which it only ever used through interfaces that already
 * existed. The fifth, the node store, genuinely had no port: its three bootstrap methods each have
 * exactly one caller, so the persistence adapter's public surface was shaped by an app-node
 * algorithm with nothing in between.
 *
 * <p>The payoff is testability. That routine has fourteen distinct refusal branches — no snapshot,
 * an unburied pivot, an integer-overflow guard on the pivot, a non-conforming root, chunk-count and
 * spool-byte bounds, fetch and verification failures — and exactly one is covered today, because
 * exercising any of them meant opening five real RocksDB instances under a temporary directory
 * while the serving side of the same test ran entirely on in-memory stores.
 *
 * <p>The state store appears as TWO accessors rather than one intersection-typed parameter: the
 * snapshot importer wants an {@link SmtNodeStore} and the pivot root write wants a
 * {@link RootStore}. RocksDB happens to satisfy both on one object, but the in-memory pair does
 * not — {@code InMemorySmtNodeStore} and {@code InMemoryRootStore} are separate objects — and an
 * intersection bound would make the in-memory target impossible to write, which is precisely the
 * target this port exists to allow.
 */
public interface BootstrapTarget {

    ChainStore chainStore();

    Ledger ledger();

    NonceStore nonceStore();

    BoxStore boxes();

    TokenStore tokens();

    // The contract store is deliberately NOT here, and its absence records a real wart: it is the
    // only store port declared outside lib-core (in lib-vm, next to the VM that never talks to it
    // directly), so this interface cannot name it without inverting the module direction. The
    // bootstrap takes it as a separate parameter instead — still an interface, just not this one.

    /** SMT node sink for the snapshot importer. */
    SmtNodeStore stateNodes();

    /** Per-height root sink for the pivot root. */
    RootStore stateRoots();

    /**
     * Marks a seed in progress. Everything after this point mutates several independently
     * committing stores, so an interrupted bootstrap must be detectable at the next boot rather
     * than left running on half-written state (audit M8).
     */
    void beginBootstrap();

    /** Adopts validated, body-less headers above genesis, in one contiguous run from height 2. */
    void bootstrapHeaders(List<BlockHeader> headers);

    /** Clears the in-progress marker; only reached once every seed above has committed. */
    void endBootstrap();
}
