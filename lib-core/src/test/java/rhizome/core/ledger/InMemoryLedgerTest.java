package rhizome.core.ledger;

class InMemoryLedgerTest implements LedgerContract {

    @Override
    public Ledger newLedger() {
        return new InMemoryLedger();
    }
}
