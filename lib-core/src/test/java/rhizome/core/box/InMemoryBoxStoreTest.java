package rhizome.core.box;

class InMemoryBoxStoreTest implements BoxStoreContract {

    @Override
    public BoxStore newBoxStore() {
        return new InMemoryBoxStore();
    }
}
