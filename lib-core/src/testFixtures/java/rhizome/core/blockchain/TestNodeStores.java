package rhizome.core.blockchain;

import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.Ledger;

/**
 * {@link NodeStores} aggregates for tests.
 *
 * <p>{@link ChainEngine#boot} takes the chain store, ledger and nonce store as ONE
 * {@code NodeStores} because they must be three views of ONE database: the engine commits a block,
 * its ledger deltas and its nonce watermark in a single atomic batch, and that batch only exists
 * because the views share an instance. Only {@code RocksDbNodeStore} satisfies that. Tests do not
 * want a RocksDB directory per case, and some deliberately want a mismatched pair — an in-memory
 * chain store over a durable ledger, a chain store wrapped in a decorator — so they need an
 * aggregate that makes no such promise.
 *
 * <p>The factories below are that aggregate, and they live in {@code src/testFixtures} rather than
 * {@code src/main} for the same reason {@link ChainEngineTestAccess} does: shipping them would put
 * back the hole {@code NodeStores} was introduced to close. Production could then hand the engine
 * three unrelated views again, and the type would have stopped saying anything. Here, the module
 * graph enforces it — {@code app-node}'s main source set cannot see this class at all, so
 * "production passes a real single-database {@code NodeStores}" is a compile-time fact rather than
 * a convention someone has to remember.
 *
 * <p>Why this is nonetheless safe for tests: the atomicity requirement exists to survive a crash
 * mid-batch. In memory there is no crash to tear a batch and no restart to observe the tear, so an
 * aggregate of three independent in-memory views behaves exactly like one database. A test that
 * mixes a durable view into the aggregate has left that guarantee, which is why the factory is
 * named for it.
 */
public final class TestNodeStores {

    private TestNodeStores() {
    }

    /** A fresh in-memory ledger, chain store and nonce store — the default test chain. */
    public static NodeStores inMemory() {
        return mixing(new InMemoryLedger(), new InMemoryChainStore(), new InMemoryNonceStore());
    }

    /**
     * Aggregates three views the caller asserts may be used together, with a fresh volatile nonce
     * store. Nothing checks that assertion — that is the point of the name. The engine used to
     * fabricate this nonce store itself, inside an {@code init} overload documented as test-only
     * and enforced by nothing; making the test path say the word keeps a durable node from
     * reaching it by picking the shorter call.
     */
    public static NodeStores mixing(Ledger ledger, ChainStore chainStore) {
        return mixing(ledger, chainStore, new InMemoryNonceStore());
    }

    /** As {@link #mixing(Ledger, ChainStore)}, with the nonce store named too. */
    public static NodeStores mixing(Ledger ledger, ChainStore chainStore, NonceStore nonceStore) {
        java.util.Objects.requireNonNull(ledger, "ledger");
        java.util.Objects.requireNonNull(chainStore, "chainStore");
        java.util.Objects.requireNonNull(nonceStore, "nonceStore");
        return new NodeStores() {
            @Override
            public ChainStore chainStore() {
                return chainStore;
            }

            @Override
            public Ledger ledger() {
                return ledger;
            }

            @Override
            public NonceStore nonceStore() {
                return nonceStore;
            }
        };
    }
}
