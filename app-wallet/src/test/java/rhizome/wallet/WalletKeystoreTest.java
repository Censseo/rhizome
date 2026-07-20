package rhizome.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Regression guard for at-rest wallet encryption (H7). */
class WalletKeystoreTest {

    @Test
    void roundTripsUnderCorrectPassphrase() {
        String secret = "{\"privateKey\":\"deadbeef\"}";
        String envelope = WalletKeystore.encrypt(secret, "correct horse".toCharArray());
        assertTrue(WalletKeystore.isEncrypted(envelope));
        assertFalse(envelope.contains("deadbeef"), "seed must not appear in the ciphertext envelope");
        assertEquals(secret, WalletKeystore.decrypt(envelope, "correct horse".toCharArray()));
    }

    @Test
    void wrongPassphraseFailsClosed() {
        String envelope = WalletKeystore.encrypt("{\"privateKey\":\"deadbeef\"}", "right".toCharArray());
        assertThrows(IllegalStateException.class, () -> WalletKeystore.decrypt(envelope, "wrong".toCharArray()));
    }

    @Test
    void plaintextIsNotMistakenForEnvelope() {
        assertFalse(WalletKeystore.isEncrypted("{\"privateKey\":\"deadbeef\"}"));
    }
}
