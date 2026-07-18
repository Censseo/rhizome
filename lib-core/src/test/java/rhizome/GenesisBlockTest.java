package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.blockchain.GenesisBlock;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.Ledger;
import rhizome.core.ledger.LedgerException;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.TransactionAmount;

class GenesisBlockTest {

    private static PublicAddress addr(int seed) {
        byte[] a = new byte[PublicAddress.SIZE];
        for (int i = 1; i < a.length; i++) {
            a[i] = (byte) (seed * 17 + i);
        }
        return PublicAddress.of(a);
    }

    private static LedgerSnapshot snapshot(int chainId) {
        LedgerSnapshot s = new LedgerSnapshot("pandanite", 536000, chainId);
        s.put(addr(1), new TransactionAmount(500_000L));
        s.put(addr(2), new TransactionAmount(42L));
        return s;
    }

    /** Minimal in-memory Ledger for tests. */
    private static final class MapLedger implements Ledger {
        final Map<PublicAddress, TransactionAmount> map = new HashMap<>();

        public boolean hasWallet(PublicAddress wallet) { return map.containsKey(wallet); }
        public void createWallet(PublicAddress wallet) {
            if (map.putIfAbsent(wallet, new TransactionAmount(0)) != null)
                throw new LedgerException("Wallet already exists");
        }
        public TransactionAmount getWalletValue(PublicAddress wallet) { return map.get(wallet); }
        public void withdraw(PublicAddress wallet, TransactionAmount amt) {
            map.merge(wallet, amt, (a, b) -> new TransactionAmount(a.amount() - b.amount()));
        }
        public void revertSend(PublicAddress wallet, TransactionAmount amt) { deposit(wallet, amt); }
        public void deposit(PublicAddress wallet, TransactionAmount amt) {
            map.merge(wallet, amt, (a, b) -> new TransactionAmount(a.amount() + b.amount()));
        }
        public void revertDeposit(PublicAddress wallet, TransactionAmount amt) {
            map.merge(wallet, amt, (a, b) -> new TransactionAmount(a.amount() - b.amount()));
        }
    }

    @Test
    void genesisIsDeterministic() {
        NetworkParameters params = NetworkParameters.testnet();
        Block a = GenesisBlock.build(params, snapshot(params.chainId()));
        Block b = GenesisBlock.build(params, snapshot(params.chainId()));
        assertEquals(a.hash(), b.hash());
        assertEquals(GenesisBlock.GENESIS_ID, ((rhizome.core.block.BlockImpl) a).id());
    }

    @Test
    void commitmentIsOrderIndependentButValueSensitive() {
        NetworkParameters params = NetworkParameters.testnet();

        // Same balances inserted in reverse order -> same commitment.
        LedgerSnapshot reversed = new LedgerSnapshot("pandanite", 536000, params.chainId());
        reversed.put(addr(2), new TransactionAmount(42L));
        reversed.put(addr(1), new TransactionAmount(500_000L));
        assertEquals(snapshot(params.chainId()).commitmentHash(), reversed.commitmentHash());

        // A single different balance -> different commitment -> different genesis.
        LedgerSnapshot tampered = snapshot(params.chainId());
        tampered.put(addr(2), new TransactionAmount(43L));
        assertNotEquals(
            GenesisBlock.build(params, snapshot(params.chainId())).hash(),
            GenesisBlock.build(params, tampered).hash());
    }

    @Test
    void chainIdMismatchRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> GenesisBlock.build(NetworkParameters.testnet(), snapshot(999)));
    }

    @Test
    void matchesDetectsTampering() {
        NetworkParameters params = NetworkParameters.testnet();
        Block genesis = GenesisBlock.build(params, snapshot(params.chainId()));
        assertTrue(GenesisBlock.matches(genesis, params, snapshot(params.chainId())));

        LedgerSnapshot tampered = snapshot(params.chainId());
        tampered.put(addr(3), new TransactionAmount(1_000_000L));
        assertFalse(GenesisBlock.matches(genesis, params, tampered));
    }

    @Test
    void initChainSeedsLedgerAndVerifiesCommitment() {
        NetworkParameters params = NetworkParameters.testnet();
        MapLedger ledger = new MapLedger();

        Block genesis = GenesisBlock.initChain(ledger, params, snapshot(params.chainId()), null);

        assertEquals(500_000L, ledger.getWalletValue(addr(1)).amount());
        assertEquals(42L, ledger.getWalletValue(addr(2)).amount());

        // Re-init against the published hash succeeds with the right snapshot...
        MapLedger ledger2 = new MapLedger();
        GenesisBlock.initChain(ledger2, params, snapshot(params.chainId()), genesis.hash());

        // ...and fails with a tampered one.
        LedgerSnapshot tampered = snapshot(params.chainId());
        tampered.put(addr(1), new TransactionAmount(999L));
        assertThrows(IllegalStateException.class,
            () -> GenesisBlock.initChain(new MapLedger(), params, tampered, genesis.hash()));
    }
}
