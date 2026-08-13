package rhizome.node;

import java.util.List;

import rhizome.core.block.BlockHeader;
import rhizome.core.blockchain.BootstrapTarget;
import rhizome.core.blockchain.ChainStore;
import rhizome.core.blockchain.NonceStore;
import rhizome.core.box.BoxStore;
import rhizome.core.ledger.Ledger;
import rhizome.core.state.RootStore;
import rhizome.core.state.SmtNodeStore;
import rhizome.core.token.TokenStore;
import rhizome.persistence.rocksdb.RocksDbBoxStore;
import rhizome.persistence.rocksdb.RocksDbNodeStore;
import rhizome.persistence.rocksdb.RocksDbStateStore;
import rhizome.persistence.rocksdb.RocksDbTokenStore;

/**
 * The durable {@link BootstrapTarget}: the node's five RocksDB stores presented as one port.
 *
 * <p>This is the only place in the tree that knows a snap-sync seed lands in RocksDB. Everything
 * upstream of it works against the interface, which is what lets the bootstrap's refusal branches
 * be exercised against in-memory stores.
 *
 * <p>{@code chainStore()}/{@code ledger()}/{@code nonceStore()} delegate straight through to
 * {@code store}: {@link RocksDbNodeStore} memoizes its own three views (see {@code NodeStores}),
 * so there is no longer a need to resolve and hold one of them here while the other two re-resolve
 * on every call — this record used to do exactly that, asymmetrically.
 */
record RocksBootstrapTarget(RocksDbNodeStore store, RocksDbBoxStore boxStore,
                            RocksDbTokenStore tokenStore, RocksDbStateStore stateStore)
        implements BootstrapTarget {

    @Override
    public ChainStore chainStore() {
        return store.chainStore();
    }

    @Override
    public Ledger ledger() {
        return store.ledger();
    }

    @Override
    public NonceStore nonceStore() {
        return store.nonceStore();
    }

    @Override
    public BoxStore boxes() {
        return boxStore;
    }

    @Override
    public TokenStore tokens() {
        return tokenStore;
    }

    @Override
    public SmtNodeStore stateNodes() {
        return stateStore;
    }

    @Override
    public RootStore stateRoots() {
        return stateStore;
    }

    @Override
    public void beginBootstrap() {
        store.beginBootstrap();
    }

    @Override
    public void bootstrapHeaders(List<BlockHeader> headers) {
        store.bootstrapHeaders(headers);
    }

    @Override
    public void endBootstrap() {
        store.endBootstrap();
    }
}
