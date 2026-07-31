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
 * spendable keys). Key derivation is scrypt (memory-hard; Bouncy Castle) for new envelopes,
 * with PBKDF2-HMAC-SHA256 still accepted when reading older files; the payload is sealed with
 * AES-256-GCM (authenticated, so a wrong passphrase or tampering fails cleanly rather than
 * yielding garbage).
 *
 * <p>Enabled opt-in via {@code RHIZOME_WALLET_PASSPHRASE} (see {@link Wallet}); plaintext files
 * stay readable, and {@link #isEncrypted} lets load auto-detect the format.
 */
final class WalletKeystore {

    private static final String MARKER = "rhizome-keystore";
    // scrypt (memory-hard) is the default KDF for new envelopes; PBKDF2-HMAC-SHA256 stays
    // supported for READING envelopes sealed before the upgrade (the "kdf" field selects the
    // path, so old files keep working). OWASP interactive-login parameters: N=2^15, r=8, p=1
    // (~32 MiB, ~100 ms). Chosen parameters are stored per-envelope and read back on decrypt,
    // so raising them here stays backward compatible (audit L4 follow-up).
    private static final int SCRYPT_N = 1 << 15;
    private static final int SCRYPT_R = 8;
    private static final int SCRYPT_P = 1;
    /** Accepted ranges for file-supplied scrypt parameters, so a tampered envelope cannot wedge
     *  decrypt via CPU (N*r*p) or OOM it via memory (128*N*r bytes, capped at 128 MiB). */
    private static final int MIN_SCRYPT_N = 1 << 10;
    private static final int MAX_SCRYPT_N = 1 << 17;
    private static final int MAX_SCRYPT_R = 16;
    private static final int MAX_SCRYPT_P = 8;
    private static final long MAX_SCRYPT_NR = 1L << 20; // 128 * N * r <= 128 MiB
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

    /**
     * True if {@code content} is a keystore envelope rather than a plaintext key JSON. Parses the
     * JSON first and requires the marker key AND all payload fields: a substring check alone is
     * spoofable (a plaintext file merely quoting the marker would be misrouted to the decrypt
     * path), and an envelope missing its fields can never decrypt anyway (audit F8).
     */
    static boolean isEncrypted(String content) {
        if (content == null) {
            return false;
        }
        try {
            JSONObject o = new JSONObject(content);
            return o.has(MARKER) && o.has("salt") && o.has("iv") && o.has("ct");
        } catch (org.json.JSONException notJson) {
            return false;
        }
    }

    /**
     * As {@link #isEncrypted(String)} but on a wipeable {@code char[]}, so the routing check on
     * load never materializes a plaintext seed into an immutable String (audit F4). Same rule:
     * the marker key AND all payload fields must be present as JSON keys. This is a heuristic
     * scanner (it matches the quoted key followed by ':' without full JSON parsing); it only
     * picks the code path — decrypt still parses strictly and fails closed on garbage.
     */
    static boolean isEncrypted(char[] content) {
        return content != null
            && hasKey(content, MARKER) && hasKey(content, "salt")
            && hasKey(content, "iv") && hasKey(content, "ct");
    }

    /** True when {@code "key"} appears followed (after optional whitespace) by a ':'. */
    private static boolean hasKey(char[] json, String key) {
        outer:
        for (int i = 0; i + key.length() + 2 <= json.length; i++) {
            if (json[i] != '"') {
                continue;
            }
            int j = i + 1;
            for (int k = 0; k < key.length(); k++, j++) {
                if (json[j] != key.charAt(k)) {
                    continue outer;
                }
            }
            if (json[j] != '"') {
                continue;
            }
            j++;
            while (j < json.length && Character.isWhitespace(json[j])) {
                j++;
            }
            if (j < json.length && json[j] == ':') {
                return true;
            }
        }
        return false;
    }

    static String encrypt(char[] plaintext, char[] passphrase) {
        byte[] plaintextBytes = utf8Encode(plaintext);
        try {
            byte[] salt = new byte[SALT_LEN];
            RNG.nextBytes(salt);
            byte[] iv = new byte[IV_LEN];
            RNG.nextBytes(iv);
            WipeableKey key = deriveScryptKey(passphrase, salt, SCRYPT_N, SCRYPT_R, SCRYPT_P);
            byte[] ciphertext;
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
                ciphertext = cipher.doFinal(plaintextBytes);
            } finally {
                key.destroy();
            }
            Base64.Encoder b64 = Base64.getEncoder();
            return new JSONObject()
                .put(MARKER, 1)
                .put("kdf", "scrypt")
                .put("n", SCRYPT_N)
                .put("r", SCRYPT_R)
                .put("p", SCRYPT_P)
                .put("salt", b64.encodeToString(salt))
                .put("iv", b64.encodeToString(iv))
                .put("ct", b64.encodeToString(ciphertext))
                .toString(2);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("wallet encryption failed", e);
        } finally {
            java.util.Arrays.fill(plaintextBytes, (byte) 0);
        }
    }

    static char[] decrypt(String envelope, char[] passphrase) {
        try {
            JSONObject o = new JSONObject(envelope);
            Base64.Decoder b64 = Base64.getDecoder();
            byte[] salt = b64.decode(o.getString("salt"));
            byte[] iv = b64.decode(o.getString("iv"));
            byte[] ciphertext = b64.decode(o.getString("ct"));
            WipeableKey key;
            String kdf = o.optString("kdf", "pbkdf2-hmac-sha256");
            if ("scrypt".equals(kdf)) {
                int n = o.getInt("n");
                int r = o.getInt("r");
                int p = o.getInt("p");
                // The parameters come from the file. Reject out-of-range values instead of feeding
                // them to scrypt, where a hostile "n"/"r"/"p" would OOM (128*N*r bytes) or hang
                // load() for minutes — a local lock-out on the very shared-host/backup threat the
                // keystore defends (audit).
                if (n < MIN_SCRYPT_N || n > MAX_SCRYPT_N || (n & (n - 1)) != 0
                    || r < 1 || r > MAX_SCRYPT_R || p < 1 || p > MAX_SCRYPT_P
                    || (long) n * r > MAX_SCRYPT_NR) {
                    throw new IllegalStateException("wallet keystore scrypt parameters out of range");
                }
                key = deriveScryptKey(passphrase, salt, n, r, p);
            } else if ("pbkdf2-hmac-sha256".equals(kdf)) {
                int iterations = o.optInt("iter", ITERATIONS);
                // Same tamper guard as scrypt: a hostile "iter" (e.g. 2 billion) would hang load().
                if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
                    throw new IllegalStateException("wallet keystore iteration count out of range: " + iterations);
                }
                key = deriveKey(passphrase, salt, iterations);
            } else {
                throw new IllegalStateException("wallet keystore has an unknown kdf: " + kdf);
            }
            byte[] plaintext;
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
                plaintext = cipher.doFinal(ciphertext);
            } finally {
                key.destroy();
            }
            try {
                return utf8Decode(plaintext);
            } finally {
                java.util.Arrays.fill(plaintext, (byte) 0);
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("wallet decryption failed (wrong passphrase or corrupt file)", e);
        }
    }

    /** UTF-8 without a String intermediate, so plaintext stays in wipeable arrays (audit F4). */
    static byte[] utf8Encode(char[] chars) {
        java.nio.ByteBuffer bb = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars));
        byte[] out = new byte[bb.remaining()];
        bb.get(out);
        return out;
    }

    static char[] utf8Decode(byte[] bytes) {
        return utf8Decode(bytes, bytes.length);
    }

    /** Decodes the first {@code length} bytes as UTF-8 without a String intermediate. */
    static char[] utf8Decode(byte[] bytes, int length) {
        java.nio.CharBuffer cb = StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(bytes, 0, length));
        char[] out = new char[cb.remaining()];
        cb.get(out);
        return out;
    }

    private static WipeableKey deriveScryptKey(char[] passphrase, byte[] salt, int n, int r, int p) {
        // scrypt takes the password as bytes; convert without a String intermediate and wipe
        // both the password bytes and the derived key material once copied (audit S-9).
        byte[] passwordBytes = utf8Encode(passphrase);
        try {
            return new WipeableKey(org.bouncycastle.crypto.generators.SCrypt.generate(
                passwordBytes, salt, n, r, p, KEY_BITS / 8));
        } finally {
            java.util.Arrays.fill(passwordBytes, (byte) 0);
        }
    }

    private static WipeableKey deriveKey(char[] passphrase, byte[] salt, int iterations)
            throws GeneralSecurityException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        // Clear the PBEKeySpec's internal copy of the passphrase once the key material has been
        // derived, so it does not linger on the heap (audit S-9).
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, KEY_BITS);
        try {
            return new WipeableKey(factory.generateSecret(spec).getEncoded());
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * An AES {@link SecretKey} whose bytes can actually be wiped once the cipher is done with
     * them. {@link SecretKeySpec} cannot: it inherits the default {@code Destroyable.destroy()},
     * which throws {@code DestroyFailedException} and clears nothing, so the derived 256-bit key
     * would sit on the heap until GC — the one gap in this file's wipe discipline (audit INF-3).
     * The JCA only needs {@code "RAW"}/{@code "AES"} plus {@link #getEncoded()}, so a hand-rolled
     * key is a drop-in (each {@code getEncoded} hands out a copy the provider owns; the provider
     * keeps its own expanded round-key schedule regardless, which is outside our reach).
     */
    private static final class WipeableKey implements SecretKey {

        private final byte[] key;
        private boolean destroyed;

        WipeableKey(byte[] key) {
            this.key = key; // ownership transferred: destroy() wipes this very array
        }

        @Override
        public String getAlgorithm() {
            return "AES";
        }

        @Override
        public String getFormat() {
            return "RAW";
        }

        @Override
        public byte[] getEncoded() {
            return destroyed ? null : key.clone();
        }

        @Override
        public void destroy() {
            java.util.Arrays.fill(key, (byte) 0);
            destroyed = true;
        }

        @Override
        public boolean isDestroyed() {
            return destroyed;
        }
    }
}
