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
    /** TOFU chainId pin read from the key file at load, or null when the file has none. */
    private final TofuPin chainIdPin;

    private Wallet(PrivateKey privateKey, PublicKey publicKey, TofuPin chainIdPin) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.address = PublicAddress.of(publicKey);
        this.chainIdPin = chainIdPin;
    }

    public static Wallet create() {
        var pair = Crypto.generateKeyPair();
        return fromPrivate(new PrivateKey(
            (org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters) pair.getPrivate()));
    }

    private static Wallet fromPrivate(PrivateKey privateKey) {
        return fromPrivate(privateKey, null);
    }

    private static Wallet fromPrivate(PrivateKey privateKey, TofuPin chainIdPin) {
        Ed25519PublicKeyParameters pub = privateKey.key().generatePublicKey();
        return new Wallet(privateKey, PublicKey.of(pub), chainIdPin);
    }

    /**
     * The TOFU chainId pin read from the key file this wallet was loaded from (null when the
     * file has none — first use). On an ENCRYPTED file the pin is read from inside the sealed
     * GCM payload, so it is as tamper-proof as the seed itself; a legacy file carrying the pin
     * as cleartext metadata next to the envelope still yields it (it is migrated into the
     * envelope at the next {@link #saveChainIdPin}).
     */
    public TofuPin chainIdPin() {
        return chainIdPin;
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
     * Env var that explicitly allows writing an UNENCRYPTED key file in non-interactive mode
     * (value {@code 1}). Without it (or the CLI's {@code --plaintext}), {@link #save} refuses to
     * write a plaintext seed when no passphrase can be resolved and no console exists (audit S-3).
     */
    private static final String PLAINTEXT_ENV = "RHIZOME_WALLET_PLAINTEXT";

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
     * spendable key. Without a passphrase, writing the seed in clear requires an explicit opt-in
     * (see {@link #save(Path, char[], boolean)}).
     */
    public void save(Path keyFile) throws IOException {
        save(keyFile, null, false);
    }

    /** As {@link #save(Path)} but with an explicit passphrase (empty/null = plaintext). */
    public void save(Path keyFile, char[] passphrase) throws IOException {
        save(keyFile, passphrase, false);
    }

    /**
     * As {@link #save(Path, char[])}; {@code allowPlaintext} (the CLI's {@code --plaintext}, or
     * {@value #PLAINTEXT_ENV}{@code =1}) explicitly permits an unencrypted key file when no
     * passphrase is available. Without that opt-in a plaintext write is REFUSED in
     * non-interactive mode (no console): a silently unencrypted seed on disk is spendable by
     * anyone who reads it (audit S-3). Interactive sessions keep the loud warning and must
     * confirm. An EXISTING key file is never overwritten here — see
     * {@link #save(Path, char[], boolean, boolean)}.
     */
    public void save(Path keyFile, char[] passphrase, boolean allowPlaintext) throws IOException {
        save(keyFile, passphrase, allowPlaintext, false);
    }

    /**
     * As {@link #save(Path, char[], boolean)}; {@code overwrite} (the CLI's {@code --overwrite})
     * permits replacing an existing key file. By default an existing file is REFUSED: there is
     * no backup of a spendable private key, so re-running keygen on an existing path must fail
     * loudly instead of silently destroying the old key. The check runs BEFORE any passphrase
     * prompt, so a refused overwrite fails fast.
     */
    public void save(Path keyFile, char[] passphrase, boolean allowPlaintext, boolean overwrite)
            throws IOException {
        if (!overwrite && Files.exists(keyFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("refusing to overwrite existing wallet key file " + keyFile
                + " — its private key would be lost forever; move it away first or pass --overwrite "
                + "to replace it");
        }
        char[] plaintext = seedJsonChars(null, null);
        char[] pass = resolvePassphrase(passphrase);
        try {
            if (pass == null) {
                checkPlaintextAllowed(keyFile, allowPlaintext);
                writeOwnerOnly(keyFile, plaintext);
            } else {
                writeOwnerOnly(keyFile, WalletKeystore.encrypt(plaintext, pass));
            }
        } finally {
            java.util.Arrays.fill(plaintext, '\0');
            if (pass != null) {
                java.util.Arrays.fill(pass, '\0'); // don't leave the passphrase lingering on the heap
            }
        }
    }

    private static void checkPlaintextAllowed(Path keyFile, boolean allowPlaintext) throws IOException {
        if (allowPlaintext || "1".equals(System.getenv(PLAINTEXT_ENV))) {
            System.err.println("WARNING: writing wallet private key UNENCRYPTED to " + keyFile
                + " — set " + PASSPHRASE_ENV + " to encrypt the key at rest (AES-256-GCM).");
            return;
        }
        java.io.Console console = System.console();
        if (console == null) {
            // Non-interactive (script, cron, CI): nobody can answer a prompt, so a plaintext
            // seed would hit the disk with only a warning nobody reads. Fail closed instead.
            throw new IOException("refusing to write the wallet private key UNENCRYPTED in "
                + "non-interactive mode: pass --plaintext or set " + PLAINTEXT_ENV + "=1 to "
                + "override, or supply a passphrase (--passphrase-file / " + PASSPHRASE_ENV + ")");
        }
        System.err.println("WARNING: writing wallet private key UNENCRYPTED to " + keyFile
            + " — anyone who can read this file can spend from it.");
        String confirm = console.readLine("type 'yes' to write the key UNENCRYPTED: ");
        if (confirm == null || !confirm.trim().equalsIgnoreCase("yes")) {
            throw new IOException("aborted: not writing an unencrypted wallet key to " + keyFile);
        }
    }

    /**
     * Builds the plaintext key JSON as a wipeable {@code char[]} — not via
     * {@link JSONObject#toString}, whose immutable Strings would pin the seed hex on the heap
     * until GC (audit F4). Only the secret half is handled as chars; the public key and address
     * are not sensitive. {@code chainId}/{@code nodeUrl} carry the TOFU pin (audit F2) and are
     * omitted when null.
     */
    private char[] seedJsonChars(Integer chainId, String nodeUrl) {
        byte[] seed = privateKey.toBytes();
        char[] seedHex = new char[seed.length * 2];
        try {
            for (int i = 0; i < seed.length; i++) {
                seedHex[2 * i] = HEX_DIGITS[(seed[i] >> 4) & 0xF];
                seedHex[2 * i + 1] = HEX_DIGITS[seed[i] & 0xF];
            }
            String head = "{\n  \"privateKey\": \"";
            String tail = "\",\n  \"publicKey\": \"" + publicKey.toHexString()
                + "\",\n  \"address\": \"" + address.toHexString() + "\"";
            if (chainId != null) {
                // nodeUrl is written verbatim: node URLs never contain characters that JSON
                // string escaping would alter (no quotes/backslashes).
                tail += ",\n  \"chainId\": " + chainId
                    + ",\n  \"nodeUrl\": \"" + (nodeUrl == null ? "" : nodeUrl) + "\"";
            }
            tail += "\n}";
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
     * POSIX the content goes to a temp file created with owner-only permissions in the same
     * directory, then is moved over the target (atomically where the filesystem supports it); on
     * non-POSIX filesystems the file is created EMPTY, restricted to the owner, and only then
     * written — restricting AFTER the write would leave a window where the seed sits on disk
     * with default (often world-readable) permissions.
     */
    private static void writeOwnerOnly(Path keyFile, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        writeOwnerOnlyBytes(keyFile, bytes); // caller-owned content holds no key material
    }

    /** As {@link #writeOwnerOnly(Path, String)} but never materializes the content as a String. */
    private static void writeOwnerOnly(Path keyFile, char[] content) throws IOException {
        byte[] bytes = WalletKeystore.utf8Encode(content);
        try {
            writeOwnerOnlyBytes(keyFile, bytes);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void writeOwnerOnlyBytes(Path keyFile, byte[] content) throws IOException {
        try {
            var ownerOnly = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
            Path dir = keyFile.toAbsolutePath().getParent();
            Path tmp = Files.createTempFile(dir, ".wallet", ".tmp",
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(ownerOnly));
            try {
                // SYNC forces every write to stable storage before the close returns, and the
                // directory fsync after the rename makes the rename itself durable — otherwise
                // a crash after save() returns could resurrect the OLD key file (or none at
                // all) even though the new seed was "written".
                Files.write(tmp, content,
                    java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.SYNC);
                try {
                    Files.move(tmp, keyFile,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
                    Files.move(tmp, keyFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                fsyncDirectory(dir);
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
        } catch (UnsupportedOperationException nonPosix) {
            // Non-POSIX (e.g. Windows): create the file EMPTY, restrict it to the owner, then
            // stream the content into the SAME file (newOutputStream opens, never re-creates, so
            // the restrictive ACL cannot be reset by a create-with-default-permissions).
            if (Files.notExists(keyFile)) {
                Files.createFile(keyFile);
            }
            restrictToOwner(keyFile);
            try (var out = Files.newOutputStream(keyFile,
                    java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.SYNC)) {
                out.write(content);
            }
        }
    }

    /**
     * Best-effort fsync of a directory after an atomic rename, so the directory entry naming
     * the key file is itself durable. Platforms that refuse to open or force a directory
     * channel (e.g. Windows) are left to the OS — the rename has already happened.
     */
    private static void fsyncDirectory(Path dir) {
        try (var channel = java.nio.channels.FileChannel.open(dir,
                java.nio.file.StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException e) {
            // Directory fsync unsupported here: best-effort only, the key file itself is synced.
        }
    }

    /** Best-effort owner-only ACL via the {@code File} API (non-POSIX fallback). */
    private static void restrictToOwner(Path keyFile) {
        java.io.File f = keyFile.toFile();
        f.setReadable(false, false);
        f.setWritable(false, false);
        f.setReadable(true, true);
        f.setWritable(true, true);
    }

    public static Wallet load(Path keyFile) throws IOException {
        return load(keyFile, null);
    }

    /** As {@link #load(Path)} but with an explicit passphrase (empty/null = none). */
    public static Wallet load(Path keyFile, char[] passphrase) throws IOException {
        // The seed travels only through wipeable arrays: file bytes -> char[] -> raw seed bytes
        // -> PrivateKey (which copies them internally). No String ever holds the plaintext seed,
        // so nothing sensitive is pinned on the heap until GC (audit F4). org.json is only ever
        // given the ENCRYPTED envelope, which contains no key material.
        char[] content = readChars(keyFile);
        char[] plaintext = null;
        byte[] seed = null;
        try {
            TofuPin pin;
            if (WalletKeystore.isEncrypted(content)) {
                char[] pass = resolvePassphrase(passphrase);
                if (pass == null) {
                    throw new IOException("wallet file is encrypted; supply --passphrase-file, "
                        + "a console passphrase, or set " + PASSPHRASE_ENV + " to load it");
                }
                try {
                    plaintext = WalletKeystore.decrypt(new String(content), pass);
                } finally {
                    java.util.Arrays.fill(pass, '\0');
                }
                // The pin lives INSIDE the sealed payload (audit: TOFU pin outside the GCM
                // envelope was rewritable without the passphrase). A file written by the
                // previous version may still carry it as cleartext metadata next to the
                // envelope — read it from there so verification keeps working until the next
                // saveChainIdPin migrates it into the envelope.
                pin = extractPin(plaintext);
                if (pin == null) {
                    pin = extractPin(content);
                }
            } else {
                warnIfGroupOrOtherReadable(keyFile);
                plaintext = content;
                pin = extractPin(plaintext);
            }
            seed = extractPrivateKeySeed(plaintext);
            return fromPrivate(PrivateKey.of(seed), pin);
        } finally {
            java.util.Arrays.fill(content, '\0');
            if (plaintext != null && plaintext != content) {
                java.util.Arrays.fill(plaintext, '\0');
            }
            if (seed != null) {
                java.util.Arrays.fill(seed, (byte) 0);
            }
        }
    }

    /** Reads a key file into a wipeable {@code char[]} (UTF-8, no String intermediate). */
    private static char[] readChars(Path keyFile) throws IOException {
        byte[] bytes = Files.readAllBytes(keyFile);
        try {
            return WalletKeystore.utf8Decode(bytes);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * Decodes the hex {@code "privateKey"} field straight from the JSON chars into raw seed
     * bytes, so the seed never exists as a String (audit F4). The scanner is deliberately
     * minimal — no escape decoding — which covers everything the wallet itself writes (hex,
     * integers, URLs); a hand-edited file using JSON escapes in these fields is rejected.
     */
    private static byte[] extractPrivateKeySeed(char[] json) throws IOException {
        int[] range = jsonFieldValueRange(json, "privateKey");
        if (range == null) {
            throw new IOException("wallet key file has no \"privateKey\" field");
        }
        if (range[1] - range[0] != PrivateKey.SIZE * 2) {
            throw new IOException("wallet \"privateKey\" must be " + (PrivateKey.SIZE * 2)
                + " hex characters, got " + (range[1] - range[0]));
        }
        byte[] seed = new byte[PrivateKey.SIZE];
        try {
            for (int i = 0; i < seed.length; i++) {
                int hi = hexValue(json[range[0] + 2 * i]);
                int lo = hexValue(json[range[0] + 2 * i + 1]);
                if (hi < 0 || lo < 0) {
                    throw new IOException("wallet \"privateKey\" field is not valid hex");
                }
                seed[i] = (byte) ((hi << 4) | lo);
            }
            return seed;
        } catch (IOException e) {
            java.util.Arrays.fill(seed, (byte) 0);
            throw e;
        }
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    /**
     * Locates {@code "name" : <value>} and returns the {@code [start, end)} range of the raw
     * value chars (string contents without the quotes, or the bare number token), or null when
     * the field is absent. Heuristic, escape-free — see {@link #extractPrivateKeySeed}.
     */
    private static int[] jsonFieldValueRange(char[] json, String name) {
        for (int i = 0; i + name.length() + 2 <= json.length; i++) {
            if (json[i] != '"') {
                continue;
            }
            int j = i + 1;
            boolean match = true;
            for (int k = 0; k < name.length(); k++, j++) {
                if (json[j] != name.charAt(k)) {
                    match = false;
                    break;
                }
            }
            if (!match || json[j] != '"') {
                continue;
            }
            j++;
            while (j < json.length && Character.isWhitespace(json[j])) j++;
            if (j >= json.length || json[j] != ':') {
                continue;
            }
            j++;
            while (j < json.length && Character.isWhitespace(json[j])) j++;
            if (j >= json.length) {
                return null;
            }
            if (json[j] == '"') {
                int start = ++j;
                while (j < json.length && json[j] != '"') j++;
                return new int[] {start, j};
            }
            int start = j;
            while (j < json.length && json[j] != ',' && json[j] != '}'
                && !Character.isWhitespace(json[j])) j++;
            return new int[] {start, j};
        }
        return null;
    }

    /**
     * Trust-on-first-use pin recorded in the key file: the chainId (and node URL) this wallet
     * first signed for. The node's /info answer is unauthenticated, so the wallet would
     * otherwise sign whatever chainId any hostile/MITM'd node returns — replaying the
     * transaction onto a different chain (audit F2).
     */
    public record TofuPin(int chainId, String nodeUrl) {}

    /**
     * The TOFU pin stored as CLEARTEXT in {@code keyFile}, or null when the file has no
     * cleartext pin. This covers plaintext key files and legacy encrypted files written by the
     * previous version (pin as metadata next to the envelope). It CANNOT read a pin sealed
     * inside the GCM envelope — callers that need the pin of an encrypted file must go through
     * the loaded wallet ({@code Wallet.load(keyFile, pass).chainIdPin()}).
     */
    public static TofuPin readChainIdPin(Path keyFile) throws IOException {
        char[] content = readChars(keyFile);
        try {
            return extractPin(content);
        } finally {
            java.util.Arrays.fill(content, '\0');
        }
    }

    /**
     * Extracts the {@code chainId}/{@code nodeUrl} pin fields from key-file JSON chars, or null
     * when absent. Works on any JSON the wallet writes (plaintext payload or legacy envelope
     * metadata); a sealed payload is base64 and never matches.
     */
    private static TofuPin extractPin(char[] json) throws IOException {
        int[] range = jsonFieldValueRange(json, "chainId");
        if (range == null) {
            return null;
        }
        int chainId;
        try {
            chainId = Integer.parseInt(new String(json, range[0], range[1] - range[0]));
        } catch (NumberFormatException e) {
            throw new IOException("wallet key file has a non-numeric \"chainId\" field");
        }
        String nodeUrl = null;
        int[] urlRange = jsonFieldValueRange(json, "nodeUrl");
        if (urlRange != null) {
            nodeUrl = new String(json, urlRange[0], urlRange[1] - urlRange[0]);
        }
        return new TofuPin(chainId, nodeUrl);
    }

    /**
     * Records (or replaces) the TOFU pin in the key file. On an ENCRYPTED file the pin lives
     * INSIDE the sealed GCM payload, so updating it requires the passphrase: the envelope is
     * decrypted (authenticating both the passphrase and the file — a tampered file fails here),
     * the pin is set in the payload and the whole file is re-sealed (audit: a pin stored as
     * cleartext metadata was rewritable by anyone with write access to the file, letting a
     * hostile node steer signatures onto another chain). A legacy cleartext pin next to the
     * envelope is migrated into the envelope at this point. On a PLAINTEXT file the pin stays
     * in the clear JSON — the seed itself is in clear there, so forgery is already total;
     * encryption is what gives the pin its integrity. Owner-only permissions are preserved.
     *
     * @param passphrase required if and only if the file is encrypted; wiped after use
     */
    public void saveChainIdPin(Path keyFile, int chainId, String nodeUrl, char[] passphrase)
            throws IOException {
        char[] content = readChars(keyFile);
        try {
            if (WalletKeystore.isEncrypted(content)) {
                char[] pass = resolvePassphrase(passphrase);
                if (pass == null) {
                    throw new IOException("updating the chainId pin on an encrypted key file "
                        + "requires the passphrase (the pin is sealed inside the encrypted "
                        + "payload so it cannot be forged without it); supply --passphrase-file, "
                        + "a console passphrase, or set " + PASSPHRASE_ENV);
                }
                try {
                    // Decrypt first: authenticates the passphrase AND the file (GCM), so a
                    // forged envelope is detected before we re-seal anything.
                    char[] decrypted = WalletKeystore.decrypt(new String(content), pass);
                    byte[] sealedSeed = null;
                    byte[] ownSeed = null;
                    try {
                        sealedSeed = extractPrivateKeySeed(decrypted);
                        ownSeed = privateKey.toBytes();
                        if (!java.util.Arrays.equals(sealedSeed, ownSeed)) {
                            throw new IOException("refusing to re-seal " + keyFile
                                + ": the decrypted key does not match this wallet");
                        }
                    } finally {
                        java.util.Arrays.fill(decrypted, '\0');
                        if (sealedSeed != null) {
                            java.util.Arrays.fill(sealedSeed, (byte) 0);
                        }
                        if (ownSeed != null) {
                            java.util.Arrays.fill(ownSeed, (byte) 0);
                        }
                    }
                    char[] json = seedJsonChars(chainId, nodeUrl);
                    try {
                        writeOwnerOnly(keyFile, WalletKeystore.encrypt(json, pass));
                    } finally {
                        java.util.Arrays.fill(json, '\0');
                    }
                } finally {
                    java.util.Arrays.fill(pass, '\0');
                }
            } else {
                // The same key-identity guard as the encrypted branch: without it, a wrong
                // keyFile path would silently overwrite ANOTHER wallet's plaintext seed with
                // this wallet's key + pin (audit: unverified plaintext pin rewrite).
                byte[] fileSeed = null;
                byte[] ownSeed = null;
                try {
                    fileSeed = extractPrivateKeySeed(content);
                    ownSeed = privateKey.toBytes();
                    if (!java.util.Arrays.equals(fileSeed, ownSeed)) {
                        throw new IOException("refusing to update the chainId pin on " + keyFile
                            + ": the key in the file does not match this wallet");
                    }
                } finally {
                    if (fileSeed != null) {
                        java.util.Arrays.fill(fileSeed, (byte) 0);
                    }
                    if (ownSeed != null) {
                        java.util.Arrays.fill(ownSeed, (byte) 0);
                    }
                }
                char[] json = seedJsonChars(chainId, nodeUrl);
                try {
                    writeOwnerOnly(keyFile, json);
                } finally {
                    java.util.Arrays.fill(json, '\0');
                }
            }
        } finally {
            java.util.Arrays.fill(content, '\0');
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
