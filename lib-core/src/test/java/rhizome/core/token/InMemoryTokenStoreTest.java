package rhizome.core.token;

class InMemoryTokenStoreTest implements TokenStoreContract {

    @Override
    public TokenStore newTokenStore() {
        return new InMemoryTokenStore();
    }
}
