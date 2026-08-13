package rhizome.vm;

class InMemoryContractStoreTest implements ContractStoreContract {

    @Override
    public ContractStore newContractStore() {
        return new InMemoryContractStore();
    }
}
