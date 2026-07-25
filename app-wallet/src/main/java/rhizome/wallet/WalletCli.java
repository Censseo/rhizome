package rhizome.wallet;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import rhizome.core.blockchain.Contracts;
import rhizome.core.common.Constants;
import rhizome.core.common.Helpers;
import rhizome.core.common.Utils;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionKind;

/**
 * Command-line wallet.
 *
 * <pre>
 *   keygen  &lt;keyfile&gt;                              generate a key pair, print the address
 *   address &lt;keyfile&gt;                              print the address of a key file
 *   balance &lt;nodeUrl&gt; &lt;address&gt;                    query a wallet's balance and next nonce
 *   send    &lt;nodeUrl&gt; &lt;keyfile&gt; &lt;to&gt; &lt;amount&gt; [fee]  build, sign and submit a transfer (amounts in PDN)
 *
 * Flags on every command that signs:
 *   --passphrase-file &lt;path&gt;   key-file passphrase from a file (else console prompt, else
 *                               RHIZOME_WALLET_PASSPHRASE — last resort, visible in /proc/&lt;pid&gt;/environ)
 *   --expect-chain-id &lt;n&gt;      abort if the node reports a different chainId (or set
 *                               RHIZOME_EXPECT_CHAIN_ID); overrides and re-pins the TOFU pin
 *   --plaintext                 keygen only: permit an UNENCRYPTED key file in non-interactive
 *                               mode (or set RHIZOME_WALLET_PLAINTEXT=1)
 *
 * Chain-id pinning (trust on first use): the first send/deploy/call/... records the node's
 * chainId and URL in the key file; any later node reporting a different chainId aborts the
 * command, so a hostile node cannot replay your signature onto another chain (audit F2). On an
 * encrypted key file the pin is sealed INSIDE the encrypted payload — writing it requires the
 * passphrase, so it cannot be forged with mere file write access. Read-only commands
 * (balance, *-show, *-list, call-readonly) never touch the pin.
 * </pre>
 */
public final class WalletCli {

    private WalletCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        switch (args[0]) {
            case "keygen" -> keygen(args);
            case "address" -> address(args);
            case "balance" -> balance(args);
            case "send" -> send(args);
            case "deploy" -> deploy(args);
            case "call" -> call(args);
            case "box-create" -> boxCreate(args);
            case "box-update" -> boxUpdate(args);
            case "box-spend" -> boxSpend(args);
            case "box-show" -> boxShow(args);
            case "box-list" -> boxList(args);
            case "call-readonly" -> callReadonly(args);
            case "token-mint" -> tokenMint(args);
            case "token-transfer" -> tokenTransfer(args);
            case "token-burn" -> tokenBurn(args);
            case "token-show" -> tokenShow(args);
            case "token-balance" -> tokenBalance(args);
            case "token-list" -> tokenList(args);
            default -> {
                System.err.println("Unknown command: " + args[0]);
                usage();
                System.exit(2);
            }
        }
    }

    private static void keygen(String[] args) throws Exception {
        require(args, 2, "keygen <keyfile> [--passphrase-file <path>] [--plaintext]");
        Wallet wallet = Wallet.create();
        wallet.save(Path.of(args[1]), passphraseFromFlag(args), hasFlag(args, "--plaintext"));
        System.out.println("Created wallet " + args[1]);
        System.out.println("Address: " + wallet.address().toHexString());
    }

    private static void address(String[] args) throws Exception {
        require(args, 2, "address <keyfile> [--passphrase-file <path>]");
        System.out.println(Wallet.load(Path.of(args[1]), passphraseFromFlag(args)).address().toHexString());
    }

    private static void balance(String[] args) {
        require(args, 3, "balance <nodeUrl> <address>");
        warnIfInsecureNodeUrl(args[1]);
        var info = new WalletClient(args[1]).walletInfo(PublicAddress.of(args[2]));
        System.out.printf("balance: %s PDN (%d base units)%n",
            Helpers.toPDN(info.balance()), info.balance());
        System.out.println("nextNonce: " + info.nextNonce());
    }

    private static void send(String[] args) throws Exception {
        require(args, 5, "send <nodeUrl> <keyfile> <to> <amount> [fee] [--expect-chain-id <n>] [--passphrase-file <path>]");
        warnIfInsecureNodeUrl(args[1]);
        WalletClient client = new WalletClient(args[1]);
        Wallet wallet = Wallet.load(Path.of(args[2]), passphraseFromFlag(args));
        PublicAddress to = checkedRecipient(args[3], args);
        TransactionAmount amount = new TransactionAmount(pdnBaseUnits(args[4]));
        TransactionAmount fee = args.length >= 6 && !args[5].startsWith("--")
            ? new TransactionAmount(pdnBaseUnits(args[5])) : new TransactionAmount(0);
        echoPdn("amount", amount.amount());
        echoPdn("fee", fee.amount());

        int chainId = verifiedChainId(client, args, wallet);
        long nonce = client.walletInfo(wallet.address()).nextNonce();
        Transaction tx = wallet.signedSend(to, amount, fee, chainId, nonce, System.currentTimeMillis());

        String status = client.submit(tx);
        System.out.println("txid: " + tx.hashContents().toHexString());
        System.out.println("status: " + status);
        if (!"SUCCESS".equals(status)) {
            System.exit(1);
        }
    }

    private static final long DEFAULT_GAS_LIMIT = 10_000_000L;
    private static final long DEFAULT_GAS_PRICE = 1L;

    private static void deploy(String[] args) throws Exception {
        require(args, 4, "deploy <nodeUrl> <keyfile> <wasmfile> [gasLimit] [gasPrice] [--expect-chain-id <n>] [--passphrase-file <path>]");
        warnIfInsecureNodeUrl(args[1]);
        WalletClient client = new WalletClient(args[1]);
        Wallet wallet = Wallet.load(Path.of(args[2]), passphraseFromFlag(args));
        byte[] code = Files.readAllBytes(Path.of(args[3]));
        long gasLimit = args.length >= 5 ? Long.parseLong(args[4]) : DEFAULT_GAS_LIMIT;
        long gasPrice = args.length >= 6 ? Long.parseLong(args[5]) : DEFAULT_GAS_PRICE;

        long nonce = client.walletInfo(wallet.address()).nextNonce();
        Transaction tx = wallet.signedContract(TransactionKind.DEPLOY, PublicAddress.empty(),
            code, 0, gasLimit, gasPrice, verifiedChainId(client, args, wallet), nonce, System.currentTimeMillis());
        String status = client.submit(tx);
        System.out.println("contract: " + Contracts.deriveAddress(wallet.address(), nonce).toHexString());
        System.out.println("status: " + status);
        if (!"SUCCESS".equals(status)) {
            System.exit(1);
        }
    }

    private static void call(String[] args) throws Exception {
        require(args, 5, "call <nodeUrl> <keyfile> <contract> <hexInput> [gasLimit] [gasPrice] [--expect-chain-id <n>] [--passphrase-file <path>]");
        warnIfInsecureNodeUrl(args[1]);
        WalletClient client = new WalletClient(args[1]);
        Wallet wallet = Wallet.load(Path.of(args[2]), passphraseFromFlag(args));
        PublicAddress contract = PublicAddress.of(args[3]);
        byte[] input = args[4].isEmpty() ? new byte[0] : Utils.hexStringToByteArray(args[4]);
        long gasLimit = args.length >= 6 ? Long.parseLong(args[5]) : DEFAULT_GAS_LIMIT;
        long gasPrice = args.length >= 7 ? Long.parseLong(args[6]) : DEFAULT_GAS_PRICE;

        long nonce = client.walletInfo(wallet.address()).nextNonce();
        Transaction tx = wallet.signedContract(TransactionKind.CALL, contract,
            input, 0, gasLimit, gasPrice, verifiedChainId(client, args, wallet), nonce, System.currentTimeMillis());
        String status = client.submit(tx);
        System.out.println("txid: " + tx.hashContents().toHexString());
        System.out.println("status: " + status);
        if (!"SUCCESS".equals(status)) {
            System.exit(1);
        }
    }

    // ---- data boxes ----

    private static void boxCreate(String[] args) throws Exception {
        require(args, 4, "box-create <nodeUrl> <keyfile> <value> [--owner <addr>] [--fee <fee>] [--reg <type>:<val>]... [--expect-chain-id <n>] [--passphrase-file <path>]");
        warnIfInsecureNodeUrl(args[1]);
        WalletClient client = new WalletClient(args[1]);
        Wallet wallet = Wallet.load(Path.of(args[2]), passphraseFromFlag(args));
        long value = pdnBaseUnits(args[3]);
        long fee = flagPdn(args, "--fee");
        PublicAddress owner = flag(args, "--owner") != null
            ? checkedRecipient(flag(args, "--owner"), args) : wallet.address();
        byte[] data = rhizome.core.box.BoxPayload.encodeCreate(parseRegisters(args));
        echoPdn("value", value);
        echoPdn("fee", fee);

        long nonce = client.walletInfo(wallet.address()).nextNonce();
        var tx = wallet.signedBox(TransactionKind.BOX_CREATE, owner, data, value, fee,
            verifiedChainId(client, args, wallet), nonce, System.currentTimeMillis());
        String status = client.submit(tx);
        System.out.println("box: " + Utils.bytesToHex(rhizome.core.box.Box.deriveId(wallet.address(), nonce)));
        System.out.println("status: " + status);
        if (!"SUCCESS".equals(status)) {
            System.exit(1);
        }
    }

    private static void boxUpdate(String[] args) throws Exception {
        require(args, 4, "box-update <nodeUrl> <keyfile> <boxId> [--topup <amt>] [--fee <fee>] [--reg <type>:<val>]... [--expect-chain-id <n>] [--passphrase-file <path>]");
        warnIfInsecureNodeUrl(args[1]);
        WalletClient client = new WalletClient(args[1]);
        Wallet wallet = Wallet.load(Path.of(args[2]), passphraseFromFlag(args));
        byte[] boxId = Utils.hexStringToByteArray(args[3]);
        long topup = flagPdn(args, "--topup");
        long fee = flagPdn(args, "--fee");
        byte[] data = rhizome.core.box.BoxPayload.encodeUpdate(boxId, parseRegisters(args));
        echoPdn("topup", topup);
        echoPdn("fee", fee);

        long nonce = client.walletInfo(wallet.address()).nextNonce();
        var tx = wallet.signedBox(TransactionKind.BOX_UPDATE, wallet.address(), data, topup, fee,
            verifiedChainId(client, args, wallet), nonce, System.currentTimeMillis());
        String status = client.submit(tx);
        System.out.println("box: " + args[3]);
        System.out.println("status: " + status);
        if (!"SUCCESS".equals(status)) {
            System.exit(1);
        }
    }

    private static void boxSpend(String[] args) throws Exception {
        require(args, 4, "box-spend <nodeUrl> <keyfile> <boxId> [--fee <fee>] [--expect-chain-id <n>] [--passphrase-file <path>]");
        warnIfInsecureNodeUrl(args[1]);
        WalletClient client = new WalletClient(args[1]);
        Wallet wallet = Wallet.load(Path.of(args[2]), passphraseFromFlag(args));
        byte[] boxId = Utils.hexStringToByteArray(args[3]);
        long fee = flagPdn(args, "--fee");
        byte[] data = rhizome.core.box.BoxPayload.encodeTarget(boxId);
        echoPdn("fee", fee);

        long nonce = client.walletInfo(wallet.address()).nextNonce();
        var tx = wallet.signedBox(TransactionKind.BOX_SPEND, wallet.address(), data, 0, fee,
            verifiedChainId(client, args, wallet), nonce, System.currentTimeMillis());
        String status = client.submit(tx);
        System.out.println("box: " + args[3]);
        System.out.println("status: " + status);
        if (!"SUCCESS".equals(status)) {
            System.exit(1);
        }
    }

    private static void boxShow(String[] args) {
        require(args, 3, "box-show <nodeUrl> <boxId>");
        warnIfInsecureNodeUrl(args[1]);
        System.out.println(new WalletClient(args[1]).box(args[2]));
    }

    private static void boxList(String[] args) {
        require(args, 3, "box-list <nodeUrl> <ownerAddr>");
        warnIfInsecureNodeUrl(args[1]);
        System.out.println(new WalletClient(args[1]).boxesByOwner(PublicAddress.of(args[2])));
    }

    private static void callReadonly(String[] args) {
        require(args, 4, "call-readonly <nodeUrl> <contract> <hexInput>");
        warnIfInsecureNodeUrl(args[1]);
        byte[] input = args[3].isEmpty() ? new byte[0] : Utils.hexStringToByteArray(args[3]);
        System.out.println(new WalletClient(args[1]).callReadonly(PublicAddress.of(args[2]), input));
    }

    // ---- native tokens ----

    private static void tokenMint(String[] args) throws Exception {
        require(args, 7, "token-mint <nodeUrl> <keyfile> <symbol> <name> <amount> <decimals> [--fee <fee>] [--expect-chain-id <n>] [--passphrase-file <path>]");
        warnIfInsecureNodeUrl(args[1]);
        WalletClient client = new WalletClient(args[1]);
        Wallet wallet = Wallet.load(Path.of(args[2]), passphraseFromFlag(args));
        String symbol = args[3];
        String name = args[4];
        long amount = Long.parseLong(args[5]);
        int decimals = Integer.parseInt(args[6]);
        long fee = flagPdn(args, "--fee");
        byte[] data = rhizome.core.token.TokenPayload.encodeMint(amount, decimals, symbol, name);
        echoPdn("fee", fee);

        long nonce = client.walletInfo(wallet.address()).nextNonce();
        var tx = wallet.signedToken(TransactionKind.TOKEN_MINT, wallet.address(), data, fee,
            verifiedChainId(client, args, wallet), nonce, System.currentTimeMillis());
        String status = client.submit(tx);
        System.out.println("token: " + Utils.bytesToHex(
            rhizome.core.token.TokenMeta.deriveId(wallet.address(), nonce)));
        System.out.println("status: " + status);
        if (!"SUCCESS".equals(status)) {
            System.exit(1);
        }
    }

    private static void tokenTransfer(String[] args) throws Exception {
        require(args, 6, "token-transfer <nodeUrl> <keyfile> <tokenId> <to> <amount> [--fee <fee>] [--expect-chain-id <n>] [--passphrase-file <path>]");
        submitTokenAmount(args, TransactionKind.TOKEN_TRANSFER, checkedRecipient(args[4], args),
            args[3], Long.parseLong(args[5]));
    }

    private static void tokenBurn(String[] args) throws Exception {
        require(args, 5, "token-burn <nodeUrl> <keyfile> <tokenId> <amount> [--fee <fee>] [--expect-chain-id <n>] [--passphrase-file <path>]");
        Wallet wallet = Wallet.load(Path.of(args[2]), passphraseFromFlag(args));
        submitTokenAmount(args, TransactionKind.TOKEN_BURN, wallet.address(),
            args[3], Long.parseLong(args[4]));
    }

    private static void submitTokenAmount(String[] args, TransactionKind kind, PublicAddress to,
                                          String tokenIdHex, long amount) throws Exception {
        warnIfInsecureNodeUrl(args[1]);
        WalletClient client = new WalletClient(args[1]);
        Wallet wallet = Wallet.load(Path.of(args[2]), passphraseFromFlag(args));
        byte[] data = rhizome.core.token.TokenPayload.encodeAmount(Utils.hexStringToByteArray(tokenIdHex), amount);
        long fee = flagPdn(args, "--fee");
        echoPdn("fee", fee);

        long nonce = client.walletInfo(wallet.address()).nextNonce();
        var tx = wallet.signedToken(kind, to, data, fee, verifiedChainId(client, args, wallet), nonce, System.currentTimeMillis());
        String status = client.submit(tx);
        System.out.println("token: " + tokenIdHex);
        System.out.println("status: " + status);
        if (!"SUCCESS".equals(status)) {
            System.exit(1);
        }
    }

    private static void tokenShow(String[] args) {
        require(args, 3, "token-show <nodeUrl> <tokenId>");
        warnIfInsecureNodeUrl(args[1]);
        System.out.println(new WalletClient(args[1]).token(args[2]));
    }

    private static void tokenBalance(String[] args) {
        require(args, 4, "token-balance <nodeUrl> <tokenId> <address>");
        warnIfInsecureNodeUrl(args[1]);
        System.out.println(new WalletClient(args[1]).tokenBalance(args[2], PublicAddress.of(args[3])));
    }

    private static void tokenList(String[] args) {
        require(args, 3, "token-list <nodeUrl> <holderAddr>");
        warnIfInsecureNodeUrl(args[1]);
        System.out.println(new WalletClient(args[1]).tokensByHolder(PublicAddress.of(args[2])));
    }

    /** Collects {@code --reg <type>:<value>} pairs, in order, into box registers. */
    private static java.util.List<rhizome.core.box.BoxRegister> parseRegisters(String[] args) {
        var registers = new java.util.ArrayList<rhizome.core.box.BoxRegister>();
        for (int i = 0; i < args.length - 1; i++) {
            if (!"--reg".equals(args[i])) {
                continue;
            }
            String spec = args[i + 1];
            int sep = spec.indexOf(':');
            if (sep < 0) {
                throw new IllegalArgumentException("register must be <type>:<value>, got " + spec);
            }
            String type = spec.substring(0, sep);
            String value = spec.substring(sep + 1);
            registers.add(switch (type) {
                case "bytes" -> rhizome.core.box.BoxRegister.bytes(Utils.hexStringToByteArray(value));
                case "i64" -> rhizome.core.box.BoxRegister.i64(Long.parseLong(value));
                case "bool" -> rhizome.core.box.BoxRegister.bool(Boolean.parseBoolean(value));
                case "addr" -> new rhizome.core.box.BoxRegister(
                    rhizome.core.box.BoxRegisterType.ADDRESS, PublicAddress.of(value).toBytes());
                case "hash" -> new rhizome.core.box.BoxRegister(
                    rhizome.core.box.BoxRegisterType.HASH32, Utils.hexStringToByteArray(value));
                case "str" -> rhizome.core.box.BoxRegister.string(value);
                default -> throw new IllegalArgumentException("unknown register type: " + type);
            });
        }
        return registers;
    }

    /** The token after {@code name} in {@code args}, or null if absent. */
    private static String flag(String[] args, String name) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }

    /** True when {@code name} appears anywhere in {@code args} (a boolean flag). */
    private static boolean hasFlag(String[] args, String name) {
        for (String a : args) {
            if (name.equals(a)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses a recipient <em>wallet</em> address and enforces its 4-byte checksum: a mistyped but
     * well-formed address has no corresponding key, so value sent to it is unspendable forever
     * (audit M10). Contract/box/token addresses are hash-derived and carry no checksum, so the
     * check applies only to version-0 (key-derived) addresses; {@code --force} sends anyway.
     */
    private static PublicAddress checkedRecipient(String hex, String[] args) {
        PublicAddress addr = PublicAddress.of(hex);
        if (addr.toBytes()[0] == 0 && !addr.isValidChecksum() && !hasFlag(args, "--force")) {
            throw new IllegalArgumentException("recipient address has an invalid checksum "
                + "(likely a typo — funds would be unspendable); pass --force to send anyway");
        }
        return addr;
    }

    /** A PDN-denominated flag value in base units, or 0 if absent. */
    private static long flagPdn(String[] args, String name) {
        String v = flag(args, name);
        return v == null ? 0 : pdnBaseUnits(v);
    }

    /**
     * Parses a PDN-denominated amount into base units exactly. {@code Double.parseDouble} rounds
     * (e.g. 2.675 parses to 2.67499…), so the signed amount could silently differ from what the
     * operator typed (audit F6). Rejects negatives, magnitudes past the long range, and precision
     * finer than one base unit — the same guardrails as {@code Helpers.PDN}, but exact.
     */
    private static long pdnBaseUnits(String text) {
        final BigDecimal scaled;
        try {
            scaled = new BigDecimal(text).multiply(BigDecimal.valueOf(Constants.DECIMAL_SCALE_FACTOR));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid PDN amount: " + text);
        }
        if (scaled.signum() < 0 || scaled.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("invalid PDN amount: " + text);
        }
        try {
            return scaled.longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("amount has more precision than one base unit: " + text);
        }
    }

    /**
     * Echoes the exact parsed amount to stderr before anything is signed, so the operator sees
     * precisely what will be submitted (audit F6).
     */
    private static void echoPdn(String label, long baseUnits) {
        System.err.println(label + ": " + Helpers.toPDN(baseUnits)
            + " PDN (" + baseUnits + " base units)");
    }

    /**
     * The key-file passphrase from {@code --passphrase-file <path>}, or null to fall back to the
     * console prompt / env var inside {@link Wallet} (audit F3). A trailing newline is stripped so
     * the file can be produced by {@code echo} or an editor; the wallet wipes the array after use.
     * The passphrase never transits through an immutable String: the file bytes are decoded
     * straight into a {@code char[]} and the byte buffer is wiped (audit F4).
     */
    private static char[] passphraseFromFlag(String[] args) throws java.io.IOException {
        String path = flag(args, "--passphrase-file");
        if (path == null) {
            return null;
        }
        byte[] bytes = Files.readAllBytes(Path.of(path));
        try {
            // CR/LF are single bytes in UTF-8, so trailing-newline stripping is safe at byte level.
            int end = bytes.length;
            while (end > 0 && (bytes[end - 1] == '\n' || bytes[end - 1] == '\r')) {
                end--;
            }
            return WalletKeystore.utf8Decode(bytes, end);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * The chainId the wallet will sign for, enforcing trust-on-first-use: the key file records
     * the chainId (and node URL) of the first node it ever signed with, and any later node
     * reporting a DIFFERENT chainId is refused — the node's /info answer is unauthenticated, so
     * without pinning a hostile/MITM'd node can steer the signature onto another chain and
     * replay it there (audit F2). {@code --expect-chain-id} (or {@code RHIZOME_EXPECT_CHAIN_ID})
     * overrides the pin and re-pins. First contact pins and announces the chainId on stderr.
     * Read-only commands (balance, *-show, *-list, call-readonly) never touch the pin.
     */
    private static int verifiedChainId(WalletClient client, String[] args, Wallet wallet)
            throws java.io.IOException {
        int nodeChainId = client.chainId();
        String expected = flag(args, "--expect-chain-id");
        if (expected == null) {
            expected = System.getenv("RHIZOME_EXPECT_CHAIN_ID");
        }
        Path keyFile = Path.of(args[2]);
        // The pin comes from the LOADED wallet: on an encrypted key file it is sealed inside
        // the GCM payload, so reading it requires the passphrase already resolved at load
        // (a legacy cleartext pin next to the envelope is still surfaced by load).
        Wallet.TofuPin pin = wallet.chainIdPin();
        ChainIdDecision decision;
        try {
            decision = decideChainId(nodeChainId, expected, pin);
        } catch (ChainIdMismatchException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
            return -1; // unreachable: System.exit does not return
        }
        if (decision.pin()) {
            // Persisting the (re-)pin on an encrypted key file re-seals the payload, which
            // needs the passphrase — re-resolve it through the same helper used at load. If it
            // is not resolvable the command FAILS before signing: a first-use/override pin that
            // cannot be persisted would leave the wallet silently unpinned (TOFU downgrade),
            // which is worse than aborting (an already-correct pin never reaches this branch).
            char[] pinPass = passphraseFromFlag(args);
            try {
                wallet.saveChainIdPin(keyFile, nodeChainId, args[1], pinPass);
            } finally {
                if (pinPass != null) {
                    java.util.Arrays.fill(pinPass, '\0');
                }
            }
            if (pin == null && (expected == null || expected.isEmpty())) {
                System.err.println("pinned chainId " + nodeChainId + " (node " + args[1] + ") in "
                    + keyFile + " — first use, trust-on-first-use: a different chainId from any "
                    + "future node will abort signing (override with --expect-chain-id).");
            }
        }
        return decision.chainId();
    }

    /** Outcome of the TOFU chainId check: the chainId to sign with, and whether to (re-)pin it. */
    record ChainIdDecision(int chainId, boolean pin) {}

    /** The node answered a chainId the operator (or the TOFU pin) did not agree to sign for. */
    static final class ChainIdMismatchException extends RuntimeException {
        ChainIdMismatchException(String message) {
            super(message);
        }
    }

    /**
     * Pure TOFU decision, split out for testing: explicit expectation > existing pin > first
     * contact. An explicit expectation that MATCHES the node overrides and re-pins; a pin that
     * differs from the node's answer is a hard failure.
     */
    static ChainIdDecision decideChainId(int nodeChainId, String expected, Wallet.TofuPin pin) {
        if (expected != null && !expected.isEmpty()) {
            if (Integer.parseInt(expected) != nodeChainId) {
                throw new ChainIdMismatchException("node reports chainId " + nodeChainId + " but "
                    + expected + " was expected (--expect-chain-id / RHIZOME_EXPECT_CHAIN_ID); "
                    + "refusing to sign.");
            }
            return new ChainIdDecision(nodeChainId, true); // explicit override re-pins
        }
        if (pin == null) {
            return new ChainIdDecision(nodeChainId, true); // first contact: pin (TOFU)
        }
        if (pin.chainId() != nodeChainId) {
            throw new ChainIdMismatchException("node reports chainId " + nodeChainId
                + " but this keyfile is pinned to chainId " + pin.chainId()
                + (pin.nodeUrl() != null && !pin.nodeUrl().isEmpty()
                    ? " (first seen at " + pin.nodeUrl() + ")" : "")
                + "; signing could replay the transaction onto a different chain. Pass "
                + "--expect-chain-id " + nodeChainId + " to override and re-pin.");
        }
        return new ChainIdDecision(nodeChainId, false);
    }

    private static boolean insecureSchemeWarned;

    /**
     * Loud stderr warning, once per invocation, when the node URL is plain {@code http://} and not
     * loopback: the node's /info and /wallet answers are then unauthenticated in transit, yet the
     * wallet signs the chainId and nonce they return (audit F2).
     */
    private static void warnIfInsecureNodeUrl(String nodeUrl) {
        if (insecureSchemeWarned || nodeUrl == null) {
            return;
        }
        String u = nodeUrl.toLowerCase(java.util.Locale.ROOT);
        if (!u.startsWith("http://")) {
            return;
        }
        String rest = u.substring("http://".length());
        String host = rest.split("[/:]")[0];
        boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host)
            || "::1".equals(host) || rest.startsWith("[::1]");
        if (loopback) {
            return;
        }
        insecureSchemeWarned = true;
        System.err.println("WARNING: node URL " + nodeUrl + " is plain http:// — chainId, nonce and"
            + " balance answers are unauthenticated and can be rewritten in transit; the wallet signs"
            + " what the node tells it. Use https:// (or loopback) and pass --expect-chain-id (audit F2).");
    }

    private static void require(String[] args, int n, String usage) {
        if (args.length < n) {
            System.err.println("usage: " + usage);
            System.exit(2);
        }
    }

    private static void usage() {
        System.err.println("""
            rhizome wallet
              keygen  <keyfile>
              address <keyfile>
              balance <nodeUrl> <address>
              send    <nodeUrl> <keyfile> <to> <amount> [fee]
              deploy  <nodeUrl> <keyfile> <wasmfile> [gasLimit] [gasPrice]
              call    <nodeUrl> <keyfile> <contract> <hexInput> [gasLimit] [gasPrice]
              box-create <nodeUrl> <keyfile> <value> [--owner <addr>] [--fee <fee>] [--reg <type>:<val>]...
              box-update <nodeUrl> <keyfile> <boxId> [--topup <amt>] [--fee <fee>] [--reg <type>:<val>]...
              box-spend  <nodeUrl> <keyfile> <boxId> [--fee <fee>]
              box-show   <nodeUrl> <boxId>
              box-list   <nodeUrl> <ownerAddr>
              call-readonly <nodeUrl> <contract> <hexInput>
              token-mint     <nodeUrl> <keyfile> <symbol> <name> <amount> <decimals> [--fee <fee>]
              token-transfer <nodeUrl> <keyfile> <tokenId> <to> <amount> [--fee <fee>]
              token-burn     <nodeUrl> <keyfile> <tokenId> <amount> [--fee <fee>]
              token-show     <nodeUrl> <tokenId>
              token-balance  <nodeUrl> <tokenId> <address>
              token-list     <nodeUrl> <holderAddr>
              register types: bytes:<hex> i64:<n> bool:<true|false> addr:<hex> hash:<hex> str:<text>
              flags (all commands that sign):
                --passphrase-file <path>  key-file passphrase from a file; else console prompt;
                                          else RHIZOME_WALLET_PASSPHRASE (last resort: env vars are
                                          visible in /proc/<pid>/environ)
                --expect-chain-id <n>     abort if the node reports a different chainId
                                          (or set RHIZOME_EXPECT_CHAIN_ID); overrides and re-pins
                                          the keyfile's trust-on-first-use chainId pin
                --plaintext               keygen only: permit an UNENCRYPTED key file when
                                          non-interactive (or set RHIZOME_WALLET_PLAINTEXT=1)
              chain-id pinning: the first signing command records the node's chainId+URL in the
                                          keyfile (trust on first use); a different chainId later
                                          aborts signing. Read-only commands never pin.""");
    }
}
