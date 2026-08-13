package rhizome.core.blockchain;

class InMemoryChainStoreTest implements ChainStoreContract {

    @Override
    public ChainStore newChainStore() {
        return new InMemoryChainStore();
    }
}
