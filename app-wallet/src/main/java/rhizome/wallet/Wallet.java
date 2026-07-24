package rhizome.wallet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.json.JSONObject;

import rhizome.crypto.Crypto;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

/**
 * A key pair and the operations to spend from it: create/load/save and build a
 * signed transaction. The public key and address are derived from the private
 * key, so a key file need only hold the secret.
 */
public final class Wallet {

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final PublicAddress address;

    private Wallet(PrivateKey privateKey, PublicKey publicKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.address = PublicAddress.of(publicKey);
    }

    public static Wallet create() {
        var pair = Crypto.generateKeyPair();
        return fromPrivate(new PrivateKey(
            (org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters) pair.getPrivate()));
    }

    private static Wallet fromPrivate(PrivateKey privateKey) {
        Ed25519PublicKeyParameters pub = privateKey.key().generatePublicKey();
        return new Wallet(privateKey, PublicKey.of(pub));
    }

    public PublicAddress address() {
        return address;
    }

    public PublicKey publicKey() {
        return publicKey;
    }

    /**
     * The key material as JSON. Package-private so the API that exposes the secret is not public
     * (audit F4); {@link #save} builds its own wipeable copy instead of routing the seed through
     * {@link JSONObject#toString} Strings.
     */
    JSONObject toJson() {
        return new JSONObject()
            .put("privateKey", privateKey.toHexString())
            .put("publicKey", publicKey.toHexString())
            .put("address", address.toHexString());
    }

    /**
     * Env var holding the passphrase that encrypts the key file at rest (empty/unset = plaintext).
     * LAST RESORT only: environment variables are visible to any same-UID (or root) process via
     * {@code /proc/<pid>/environ} for the whole lifetime of the process (audit F3). Prefer the
     * CLI's {@code --passphrase-file} or the interactive console prompt.
     */
    private static final String PASSPHRASE_ENV = "RHIZOME_WALLET_PASSPHRASE";

    /**
     * Resolves the key-file passphrase by precedence: explicit argument (the CLI's
     * {@code --passphrase-file}) > interactive console prompt when a console exists >
     * {@value #PASSPHRASE_ENV} (documented last resort — visible in {@code /proc/<pid>/environ},
     * audit F3). Returns null for "no passphrase" (plaintext key file). Callers must wipe the
     * returned array.
     */
    private static char[] resolvePassphrase(char[] explicit) {
        if (explicit != null) {
            return explicit.length == 0 ? null : explicit;
        }
        java.io.Console console = System.console();
        if (console != null) {
            char[] prompted = console.readPassword("wallet passphrase (empty = plaintext key file): ");
            return (prompted == null || prompted.length == 0) ? null : prompted;
        }
        String env = System.getenv(PASSPHRASE_ENV);
        return (env == null || env.isEmpty()) ? null : env.toCharArray();
    }

    /**
     * Persists the key. When a passphrase is available (see {@link #resolvePassphrase}) the seed
     * is sealed with AES-256-GCM under a PBKDF2-derived key, so the file on disk never exposes a
     * spendable key; otherwise it is written as before (backward compatible).
     */
    public void save(Path keyFile) throws IOException {
        save(keyFile, null);
    }

    /** As {@link #save(Path)} but with an explicit passphrase (empty/null = plaintext). */
    public void save(Path keyFile, char[] passphrase) throws IOException {
        char[] plaintext = seedJsonChars();
        char[] pass = resolvePassphrase(passphrase);
        try {
            if (pass == null) {
                // A plaintext seed on disk (0600 or not) is a spendable key in any backup, snapshot,
                // synced dotfile or image layer. Refuse to do it silently — warn loudly (audit S-3).
                System.err.println("WARNING: writing wallet private key UNENCRYPTED to " + keyFile
                    + " — set " + PASSPHRASE_ENV + " to encrypt the key at rest (AES-256-GCM).");
            }
            String content = pass == null ? new String(plaintext) : WalletKeystore.encrypt(plaintext, pass);
            writeOwnerOnly(keyFile, content);
        } finally {
            java.util.Arrays.fill(plaintext, '\0');
            if (pass != null) {
                java.util.Arrays.fill(pass, '\0'); // don't leave the passphrase lingering on the heap
            }
        }
    }

    /**
     * Builds the plaintext key JSON as a wipeable {@code char[]} — not via
     * {@link JSONObject#toString}, whose immutable Strings would pin the seed hex on the heap
     * until GC (audit F4). Only the secret half is handled as chars; the public key and address
     * are not sensitive.
     */
    private char[] seedJsonChars() {
        byte[] seed = privateKey.toBytes();
        char[] seedHex = new char[seed.length * 2];
        try {
            for (int i = 0; i < seed.length; i++) {
                seedHex[2 * i] = HEX_DIGITS[(seed[i] >> 4) & 0xF];
                seedHex[2 * i + 1] = HEX_DIGITS[seed[i] & 0xF];
            }
            String head = "{\n  \"privateKey\": \"";
            String tail = "\",\n  \"publicKey\": \"" + publicKey.toHexString()
                + "\",\n  \"address\": \"" + address.toHexString() + "\"\n}";
            char[] json = new char[head.length() + seedHex.length + tail.length()];
            head.getChars(0, head.length(), json, 0);
            System.arraycopy(seedHex, 0, json, head.length(), seedHex.length);
            tail.getChars(0, tail.length(), json, head.length() + seedHex.length);
            return json;
        } finally {
            java.util.Arrays.fill(seed, (byte) 0);
            java.util.Arrays.fill(seedHex, '\0');
        }
    }

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    /**
     * Writes the key file so it is readable only by its owner (mode {@code 0600}), never briefly
     * world-readable. Without this the file inherited the process umask (typically {@code 0644}),
     * so any local user could read an unencrypted Ed25519 seed and steal the funds (audit H5). On
     * POSIX the file is created (or a temp file is created then atomically moved) with owner-only
     * permissions; on non-POSIX filesystems it falls back to the {@code File} permission API.
     */
    private static void writeOwnerOnly(Path keyFile, String content) throws IOException {
        try {
            var ownerOnly = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
            Path dir = keyFile.toAbsolutePath().getParent();
            Path tmp = Files.createTempFile(dir, ".wallet", ".tmp",
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(ownerOnly));
            try {
                Files.writeString(tmp, content, StandardCharsets.UTF_8);
                Files.move(tmp, keyFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
        } catch (UnsupportedOperationException nonPosix) {
            // Non-POSIX (e.g. Windows): best-effort owner-only via the File API.
            Files.writeString(keyFile, content, StandardCharsets.UTF_8);
            java.io.File f = keyFile.toFile();
            f.setReadable(false, false);
            f.setWritable(false, false);
            f.setReadable(true, true);
            f.setWritable(true, true);
        }
    }

    public static Wallet load(Path keyFile) throws IOException {
        return load(keyFile, null);
    }

    /** As {@link #load(Path)} but with an explicit passphrase (empty/null = none). */
    public static Wallet load(Path keyFile, char[] passphrase) throws IOException {
        String content = Files.readString(keyFile, StandardCharsets.UTF_8);
        char[] decrypted = null;
        if (WalletKeystore.isEncrypted(content)) {
            char[] pass = resolvePassphrase(passphrase);
            if (pass == null) {
                throw new IOException("wallet file is encrypted; supply --passphrase-file, "
                    + "a console passphrase, or set " + PASSPHRASE_ENV + " to load it");
            }
            try {
                decrypted = WalletKeystore.decrypt(content, pass);
            } finally {
                java.util.Arrays.fill(pass, '\0');
            }
        } else {
            warnIfGroupOrOtherReadable(keyFile);
        }
        try {
            JSONObject json = decrypted != null
                ? new JSONObject(new String(decrypted)) : new JSONObject(content);
            return fromPrivate(PrivateKey.of(json.getString("privateKey")));
        } finally {
            if (decrypted != null) {
                java.util.Arrays.fill(decrypted, '\0');
            }
        }
    }

    /**
     * Best-effort permission audit when the file turns out to be a PLAINTEXT key (audit F5): a
     * group/other-readable plaintext seed is spendable by any local user, so warn loudly on load.
     * Ignored where POSIX permissions are unsupported (non-POSIX filesystems).
     */
    private static void warnIfGroupOrOtherReadable(Path keyFile) {
        try {
            var perms = Files.getPosixFilePermissions(keyFile);
            if (perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ)
                || perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ)) {
                System.err.println("WARNING: plaintext wallet key file " + keyFile
                    + " is readable by group/other users — anyone who can read it can spend from it."
                    + " Run `chmod 600 " + keyFile + "` and/or encrypt the key at rest (see "
                    + PASSPHRASE_ENV + ").");
            }
        } catch (UnsupportedOperationException | IOException e) {
            // Non-POSIX filesystem or unreadable metadata: nothing to check (best-effort only).
        }
    }

    /**
     * Builds and signs a transfer. The account nonce and chain-id come from the
     * network (queried from a node); the signature covers both.
     */
    public Transaction signedSend(PublicAddress to, TransactionAmount amount, TransactionAmount fee,
                                  int chainId, long nonce, long timestamp) {
        Transaction t = Transaction.of(address, to, amount, publicKey, fee, timestamp, chainId, nonce);
        return t.sign(privateKey);
    }

    /** Signs a contract DEPLOY (code) or CALL (input) transaction. */
    public Transaction signedContract(rhizome.core.transaction.TransactionKind kind, PublicAddress to,
                                      byte[] data, long value, long gasLimit, long gasPrice,
                                      int chainId, long nonce, long timestamp) {
        Transaction t = rhizome.core.transaction.TransactionImpl.builder()
            .from(address).to(to)
            .amount(new TransactionAmount(value)).fee(new TransactionAmount(0))
            .timestamp(timestamp).chainId(chainId).nonce(nonce).signingKey(publicKey)
            .kind(kind).data(data).gasLimit(gasLimit).gasPrice(gasPrice)
            .build();
        return t.sign(privateKey);
    }

    /**
     * Signs a box transaction (BOX_CREATE/UPDATE/SPEND). Box ops run no VM and carry no
     * gas; {@code value} is the amount locked into the box (CREATE) or a top-up (UPDATE).
     */
    public Transaction signedBox(rhizome.core.transaction.TransactionKind kind, PublicAddress to,
                                 byte[] data, long value, long fee,
                                 int chainId, long nonce, long timestamp) {
        Transaction t = rhizome.core.transaction.TransactionImpl.builder()
            .from(address).to(to)
            .amount(new TransactionAmount(value)).fee(new TransactionAmount(fee))
            .timestamp(timestamp).chainId(chainId).nonce(nonce).signingKey(publicKey)
            .kind(kind).data(data).gasLimit(0).gasPrice(0)
            .build();
        return t.sign(privateKey);
    }

    /**
     * Signs a native-token transaction (TOKEN_MINT/TRANSFER/BURN). Token ops carry no gas
     * and move no PDN — the token amount is in {@code data}; {@code to} is the recipient
     * for mint/transfer (ignored for burn). Only the {@code fee} moves in PDN.
     */
    public Transaction signedToken(rhizome.core.transaction.TransactionKind kind, PublicAddress to,
                                   byte[] data, long fee, int chainId, long nonce, long timestamp) {
        Transaction t = rhizome.core.transaction.TransactionImpl.builder()
            .from(address).to(to)
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(fee))
            .timestamp(timestamp).chainId(chainId).nonce(nonce).signingKey(publicKey)
            .kind(kind).data(data).gasLimit(0).gasPrice(0)
            .build();
        return t.sign(privateKey);
    }
}
