package rhizome.core.blockchain;

import rhizome.core.ledger.Ledger;

/**
 * The three views of ONE node database, as a port instead of three independently passed
 * arguments.
 *
 * <p>{@code ChainEngine} commits a block, its ledger deltas and its nonce watermark in a single
 * atomic batch: the append stages pending ledger and nonce writes into the same durable batch as
 * the block. That batch only exists because the three views sit on one RocksDB instance. Passed
 * as three separate parameters, that requirement was a convention — nothing in the type system
 * stopped a caller handing {@link ChainEngine#init} a RocksDB chain store, a RocksDB ledger and an
 * unrelated {@code InMemoryNonceStore}. A crash would then lose the nonces the committed blocks
 * already assumed.
 *
 * <p>{@link Ledger} lives in {@code rhizome.core.ledger}, not here; naming it from this package is
 * the same crossing {@link BootstrapTarget} already makes, and the alternative — moving the type —
 * touches the whole tree for nothing.
 *
 * <p>Deliberately NOT here: the box, token, state and contract stores. Those are separate
 * databases in separate directories; folding them in would turn a statement about atomicity into
 * a list of everything the node happens to open, which is {@code NodeComponents}' job.
 *
 * <p>No default methods, like {@link BootstrapTarget}: an implementation states all three or does
 * not exist.
 */
public interface NodeStores {

    ChainStore chainStore();

    Ledger ledger();

    NonceStore nonceStore();
}
