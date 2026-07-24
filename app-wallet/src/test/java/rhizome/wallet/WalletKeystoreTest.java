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
        String envelope = WalletKeystore.encrypt(secret.toCharArray(), "correct horse".toCharArray());
        assertTrue(WalletKeystore.isEncrypted(envelope));
        assertFalse(envelope.contains("deadbeef"), "seed must not appear in the ciphertext envelope");
        assertEquals(secret, new String(WalletKeystore.decrypt(envelope, "correct horse".toCharArray())));
    }

    @Test
    void wrongPassphraseFailsClosed() {
        String envelope = WalletKeystore.encrypt("{\"privateKey\":\"deadbeef\"}".toCharArray(), "right".toCharArray());
        assertThrows(IllegalStateException.class, () -> WalletKeystore.decrypt(envelope, "wrong".toCharArray()));
    }

    @Test
    void plaintextIsNotMistakenForEnvelope() {
        assertFalse(WalletKeystore.isEncrypted("{\"privateKey\":\"deadbeef\"}"));
    }

    @Test
    void spoofedMarkerWithoutPayloadFieldsIsTreatedAsPlaintext() {
        // The old substring check routed any file merely QUOTING the marker to the decrypt path
        // (audit F8). Now the marker key plus salt/iv/ct must all be present.
        assertFalse(WalletKeystore.isEncrypted("{\"privateKey\":\"deadbeef\",\"note\":\"rhizome-keystore\"}"));
        assertFalse(WalletKeystore.isEncrypted("{\"rhizome-keystore\":1}"));
        assertFalse(WalletKeystore.isEncrypted("{\"rhizome-keystore\":1,\"salt\":\"AA==\"}"));
        assertFalse(WalletKeystore.isEncrypted("not json at all"));
        assertFalse(WalletKeystore.isEncrypted(null));
    }
}
