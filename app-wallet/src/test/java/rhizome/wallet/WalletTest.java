package rhizome.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;

class WalletTest {

    @TempDir
    Path tempDir;

    @Test
    void keyfileRoundTripPreservesIdentity() throws Exception {
        Wallet original = Wallet.create();
        Path keyfile = tempDir.resolve("wallet.json");
        original.save(keyfile);

        Wallet loaded = Wallet.load(keyfile);
        assertEquals(original.address(), loaded.address());
        assertEquals(original.publicKey().toHexString(), loaded.publicKey().toHexString());
    }

    @Test
    void addressDerivesFromPublicKey() {
        Wallet wallet = Wallet.create();
        assertEquals(PublicAddress.of(wallet.publicKey()), wallet.address());
    }

    @Test
    void signedSendIsValidAndCarriesChainIdAndNonce() {
        Wallet wallet = Wallet.create();
        PublicAddress to = PublicAddress.random();

        Transaction tx = wallet.signedSend(to, new TransactionAmount(500), new TransactionAmount(1),
            7, 3, 123_456L);

        assertTrue(tx.signatureValid());
        assertEquals(wallet.address(), ((TransactionImpl) tx).from());
        assertEquals(7, ((TransactionImpl) tx).chainId());
        assertEquals(3, ((TransactionImpl) tx).nonce());
        assertEquals(500, ((TransactionImpl) tx).amount().amount());
    }
}
