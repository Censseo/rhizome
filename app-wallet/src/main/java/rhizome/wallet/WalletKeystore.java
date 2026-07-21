package rhizome.wallet;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONObject;

/**
 * Passphrase-based encryption for the on-disk wallet key file, so the Ed25519 seed is not
 * stored in clear (a file read — backup, stolen laptop, shared host — would otherwise yield
 * spendable keys). Key derivation is PBKDF2-HMAC-SHA256; the payload is sealed with AES-256-GCM
 * (authenticated, so a wrong passphrase or tampering fails cleanly rather than yielding garbage).
 *
 * <p>Enabled opt-in via {@code RHIZOME_WALLET_PASSPHRASE} (see {@link Wallet}); plaintext files
 * stay readable, and {@link #isEncrypted} lets load auto-detect the format.
 */
final class WalletKeystore {

    private static final String MARKER = "rhizome-keystore";
    // OWASP 2023 floor for PBKDF2-HMAC-SHA256. The chosen count is stored in each envelope's
    // "iter" field and read back on decrypt, so raising it here stays backward compatible with
    // files sealed at the old count (audit L4). A memory-hard KDF (scrypt/argon2id) would be
    // stronger still and is the recommended follow-up.
    private static final int ITERATIONS = 600_000;
    /** Accepted range for a file-supplied iteration count, so a tampered "iter" cannot wedge decrypt. */
    private static final int MIN_ITERATIONS = 100_000;
    private static final int MAX_ITERATIONS = 10_000_000;
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;
    private static final int KEY_BITS = 256;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RNG = new SecureRandom();

    private WalletKeystore() {}

    /** True if {@code content} is a keystore envelope rather than a plaintext key JSON. */
    static boolean isEncrypted(String content) {
        return content != null && content.contains("\"" + MARKER + "\"");
    }

    static String encrypt(String plaintext, char[] passphrase) {
        try {
            byte[] salt = new byte[SALT_LEN];
            RNG.nextBytes(salt);
            byte[] iv = new byte[IV_LEN];
            RNG.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, ITERATIONS),
                new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder b64 = Base64.getEncoder();
            return new JSONObject()
                .put(MARKER, 1)
                .put("kdf", "pbkdf2-hmac-sha256")
                .put("iter", ITERATIONS)
                .put("salt", b64.encodeToString(salt))
                .put("iv", b64.encodeToString(iv))
                .put("ct", b64.encodeToString(ciphertext))
                .toString(2);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("wallet encryption failed", e);
        }
    }

    static String decrypt(String envelope, char[] passphrase) {
        try {
            JSONObject o = new JSONObject(envelope);
            int iterations = o.optInt("iter", ITERATIONS);
            // The count comes from the file. Reject an out-of-range value instead of feeding it to
            // PBKDF2, where a hostile "iter" (e.g. 2 billion) would hang load() for minutes — a
            // local lock-out on the very shared-host/backup threat the keystore defends (audit).
            if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
                throw new IllegalStateException("wallet keystore iteration count out of range: " + iterations);
            }
            Base64.Decoder b64 = Base64.getDecoder();
            byte[] salt = b64.decode(o.getString("salt"));
            byte[] iv = b64.decode(o.getString("iv"));
            byte[] ciphertext = b64.decode(o.getString("ct"));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt, iterations),
                new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("wallet decryption failed (wrong passphrase or corrupt file)", e);
        }
    }

    private static SecretKey deriveKey(char[] passphrase, byte[] salt, int iterations)
            throws GeneralSecurityException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] key = factory.generateSecret(new PBEKeySpec(passphrase, salt, iterations, KEY_BITS)).getEncoded();
        return new SecretKeySpec(key, "AES");
    }
}
