package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static rhizome.core.common.Crypto.generateKeyPair;
import static rhizome.core.common.Helpers.PDN;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.crypto.PublicKey;
import rhizome.core.ledger.LedgerException;
import rhizome.core.ledger.PublicAddress;
import rhizome.persistence.leveldb.LevelDBLedger;

class LevelDBLedgerTests {

    private static final String TEST_DB_PATH = "./test-data/tmpdb-ledger";
    private LevelDBLedger ledger;
    private PublicAddress wallet;

    @BeforeEach
    void setUp() throws IOException {
        var pair = generateKeyPair();
        wallet = PublicAddress.of(PublicKey.of(pair.getPublic()));

        ledger = new LevelDBLedger(TEST_DB_PATH);
    }

    @AfterEach
    void tearDown() throws IOException {
        ledger.closeDB();
        ledger.deleteDB();
    }

    @Test
    void testLedgerStoresWallets() {
        ledger.createWallet(wallet);
        ledger.deposit(wallet, PDN(50.0));
        assertEquals(PDN(50.0), ledger.getWalletValue(wallet));
    }

    @Test
    void testWithdrawMoreThanBalanceFails() {
        ledger.createWallet(wallet);
        ledger.deposit(wallet, PDN(10.0));
        assertThrows(LedgerException.class, () -> ledger.withdraw(wallet, PDN(20.0)));
        assertEquals(PDN(10.0), ledger.getWalletValue(wallet));
    }

    @Test
    void testRevertDepositSubtracts() {
        ledger.createWallet(wallet);
        ledger.deposit(wallet, PDN(50.0));
        ledger.revertDeposit(wallet, PDN(20.0));
        assertEquals(PDN(30.0), ledger.getWalletValue(wallet));
    }

    @Test
    void testRevertSendAdds() {
        ledger.createWallet(wallet);
        ledger.deposit(wallet, PDN(50.0));
        ledger.withdraw(wallet, PDN(20.0));
        ledger.revertSend(wallet, PDN(20.0));
        assertEquals(PDN(50.0), ledger.getWalletValue(wallet));
    }
}
