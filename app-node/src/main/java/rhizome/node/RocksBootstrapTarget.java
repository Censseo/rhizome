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
 */
record RocksBootstrapTarget(RocksDbNodeStore store, RocksDbBoxStore boxStore,
                            RocksDbTokenStore tokenStore, RocksDbStateStore stateStore,
                            ChainStore chainStore) implements BootstrapTarget {

    /**
     * ⚠ {@link RocksDbNodeStore#chainStore()} builds a NEW view object on every call, so the
     * chain store is resolved once here and held. Binding it as a method reference would hand each
     * call site a different instance — harmless for the stateless RocksDB view, and a bug the day
     * a target keeps state.
     */
    static RocksBootstrapTarget of(RocksDbNodeStore store, RocksDbBoxStore boxStore,
                                   RocksDbTokenStore tokenStore, RocksDbStateStore stateStore) {
        return new RocksBootstrapTarget(store, boxStore, tokenStore, stateStore, store.chainStore());
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
