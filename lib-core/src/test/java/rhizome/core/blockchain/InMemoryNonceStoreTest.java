package rhizome.core.blockchain;

class InMemoryNonceStoreTest implements NonceStoreContract {

    @Override
    public NonceStore newNonceStore() {
        return new InMemoryNonceStore();
    }
}
