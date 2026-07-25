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
        assertFalse(WalletKeystore.isEncrypted((String) null));
    }

    @Test
    void newEnvelopesUseScrypt() {
        String envelope = WalletKeystore.encrypt("x".toCharArray(), "pw".toCharArray());
        assertTrue(envelope.contains("\"kdf\": \"scrypt\""), "new envelopes must be scrypt-sealed");
    }

    @Test
    void legacyPbkdf2EnvelopesStillDecrypt() throws Exception {
        // Sealed with the pre-scrypt format (kdf pbkdf2-hmac-sha256, iter 600_000, AES-256-GCM).
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(salt);
        new java.security.SecureRandom().nextBytes(iv);
        javax.crypto.SecretKeyFactory f = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] keyBytes = f.generateSecret(new javax.crypto.spec.PBEKeySpec(
            "pw".toCharArray(), salt, 600_000, 256)).getEncoded();
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
            new javax.crypto.spec.GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal("{\"privateKey\":\"cafe\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.util.Base64.Encoder b64 = java.util.Base64.getEncoder();
        String legacy = new org.json.JSONObject()
            .put("rhizome-keystore", 1).put("kdf", "pbkdf2-hmac-sha256").put("iter", 600_000)
            .put("salt", b64.encodeToString(salt)).put("iv", b64.encodeToString(iv))
            .put("ct", b64.encodeToString(ct)).toString();

        assertEquals("{\"privateKey\":\"cafe\"}",
            new String(WalletKeystore.decrypt(legacy, "pw".toCharArray())));
    }

    @Test
    void tamperedScryptParametersAreRejected() {
        String envelope = WalletKeystore.encrypt("x".toCharArray(), "pw".toCharArray());
        org.json.JSONObject o = new org.json.JSONObject(envelope);
        o.put("n", 1 << 20); // 128*2^20*8 = 1 GiB memory bomb
        assertThrows(IllegalStateException.class,
            () -> WalletKeystore.decrypt(o.toString(), "pw".toCharArray()));
        o.put("n", 1 << 15);
        o.put("p", 1000); // CPU bomb
        assertThrows(IllegalStateException.class,
            () -> WalletKeystore.decrypt(o.toString(), "pw".toCharArray()));
        o.put("p", 1);
        o.put("n", (1 << 15) + 1); // not a power of two
        assertThrows(IllegalStateException.class,
            () -> WalletKeystore.decrypt(o.toString(), "pw".toCharArray()));
    }

    @Test
    void unknownKdfIsRejected() {
        String envelope = WalletKeystore.encrypt("x".toCharArray(), "pw".toCharArray());
        org.json.JSONObject o = new org.json.JSONObject(envelope);
        o.put("kdf", "rot13");
        assertThrows(IllegalStateException.class,
            () -> WalletKeystore.decrypt(o.toString(), "pw".toCharArray()));
    }
}
