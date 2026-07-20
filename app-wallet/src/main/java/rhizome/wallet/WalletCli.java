package rhizome.wallet;

import java.nio.file.Files;
import java.nio.file.Path;

import rhizome.core.blockchain.Contracts;
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
            default -> {
                System.err.println("Unknown command: " + args[0]);
                usage();
                System.exit(2);
            }
        }
    }

    private static void keygen(String[] args) throws Exception {
        require(args, 2, "keygen <keyfile>");
        Wallet wallet = Wallet.create();
        wallet.save(Path.of(args[1]));
        System.out.println("Created wallet " + args[1]);
        System.out.println("Address: " + wallet.address().toHexString());
    }

    private static void address(String[] args) throws Exception {
        require(args, 2, "address <keyfile>");
        System.out.println(Wallet.load(Path.of(args[1])).address().toHexString());
    }

    private static void balance(String[] args) {
        require(args, 3, "balance <nodeUrl> <address>");
        var info = new WalletClient(args[1]).walletInfo(PublicAddress.of(args[2]));
        System.out.printf("balance: %s PDN (%d base units)%n",
            Helpers.toPDN(info.balance()), info.balance());
        System.out.println("nextNonce: " + info.nextNonce());
    }

    private static void send(String[] args) throws Exception {
        require(args, 5, "send <nodeUrl> <keyfile> <to> <amount> [fee]");
        WalletClient client = new WalletClient(args[1]);
        Wallet wallet = Wallet.load(Path.of(args[2]));
        PublicAddress to = PublicAddress.of(args[3]);
        TransactionAmount amount = Helpers.PDN(Double.parseDouble(args[4]));
        TransactionAmount fee = args.length >= 6 ? Helpers.PDN(Double.parseDouble(args[5]))
            : new TransactionAmount(0);

        int chainId = client.chainId();
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
        require(args, 4, "deploy <nodeUrl> <keyfile> <wasmfile> [gasLimit] [gasPrice]");
        WalletClient client = new WalletClient(args[1]);
        Wallet wallet = Wallet.load(Path.of(args[2]));
        byte[] code = Files.readAllBytes(Path.of(args[3]));
        long gasLimit = args.length >= 5 ? Long.parseLong(args[4]) : DEFAULT_GAS_LIMIT;
        long gasPrice = args.length >= 6 ? Long.parseLong(args[5]) : DEFAULT_GAS_PRICE;

        long nonce = client.walletInfo(wallet.address()).nextNonce();
        Transaction tx = wallet.signedContract(TransactionKind.DEPLOY, PublicAddress.empty(),
            code, 0, gasLimit, gasPrice, client.chainId(), nonce, System.currentTimeMillis());
        String status = client.submit(tx);
        System.out.println("contract: " + Contracts.deriveAddress(wallet.address(), nonce).toHexString());
        System.out.println("status: " + status);
        if (!"SUCCESS".equals(status)) {
            System.exit(1);
        }
    }

    private static void call(String[] args) throws Exception {
        require(args, 5, "call <nodeUrl> <keyfile> <contract> <hexInput> [gasLimit] [gasPrice]");
        WalletClient client = new WalletClient(args[1]);
        Wallet wallet = Wallet.load(Path.of(args[2]));
        PublicAddress contract = PublicAddress.of(args[3]);
        byte[] input = args[4].isEmpty() ? new byte[0] : Utils.hexStringToByteArray(args[4]);
        long gasLimit = args.length >= 6 ? Long.parseLong(args[5]) : DEFAULT_GAS_LIMIT;
        long gasPrice = args.length >= 7 ? Long.parseLong(args[6]) : DEFAULT_GAS_PRICE;

        long nonce = client.walletInfo(wallet.address()).nextNonce();
        Transaction tx = wallet.signedContract(TransactionKind.CALL, contract,
            input, 0, gasLimit, gasPrice, client.chainId(), nonce, System.currentTimeMillis());
        String status = client.submit(tx);
        System.out.println("txid: " + tx.hashContents().toHexString());
        System.out.println("status: " + status);
        if (!"SUCCESS".equals(status)) {
            System.exit(1);
        }
    }

    // ---- data boxes ----

    private static void boxCreate(String[] args) throws Exception {
        require(args, 4, "box-create <nodeUrl> <keyfile> <value> [--owner <addr>] [--fee <fee>] [--reg <type>:<val>]...");
        WalletClient client = new WalletClient(args[1]);
        Wallet wallet = Wallet.load(Path.of(args[2]));
        long value = Helpers.PDN(Double.parseDouble(args[3])).amount();
        long fee = flagPdn(args, "--fee");
        PublicAddress owner = flag(args, "--owner") != null
            ? PublicAddress.of(flag(args, "--owner")) : wallet.address();
        byte[] data = rhizome.core.box.BoxPayload.encodeCreate(parseRegisters(args));

        long nonce = client.walletInfo(wallet.address()).nextNonce();
        var tx = wallet.signedBox(TransactionKind.BOX_CREATE, owner, data, value, fee,
            client.chainId(), nonce, System.currentTimeMillis());
        String status = client.submit(tx);
        System.out.println("box: " + Utils.bytesToHex(rhizome.core.box.Box.deriveId(wallet.address(), nonce)));
        System.out.println("status: " + status);
        if (!"SUCCESS".equals(status)) {
            System.exit(1);
        }
    }

    private static void boxUpdate(String[] args) throws Exception {
        require(args, 4, "box-update <nodeUrl> <keyfile> <boxId> [--topup <amt>] [--fee <fee>] [--reg <type>:<val>]...");
        WalletClient client = new WalletClient(args[1]);
        Wallet wallet = Wallet.load(Path.of(args[2]));
        byte[] boxId = Utils.hexStringToByteArray(args[3]);
        long topup = flagPdn(args, "--topup");
        long fee = flagPdn(args, "--fee");
        byte[] data = rhizome.core.box.BoxPayload.encodeUpdate(boxId, parseRegisters(args));

        long nonce = client.walletInfo(wallet.address()).nextNonce();
        var tx = wallet.signedBox(TransactionKind.BOX_UPDATE, wallet.address(), data, topup, fee,
            client.chainId(), nonce, System.currentTimeMillis());
        String status = client.submit(tx);
        System.out.println("box: " + args[3]);
        System.out.println("status: " + status);
        if (!"SUCCESS".equals(status)) {
            System.exit(1);
        }
    }

    private static void boxSpend(String[] args) throws Exception {
        require(args, 4, "box-spend <nodeUrl> <keyfile> <boxId> [--fee <fee>]");
        WalletClient client = new WalletClient(args[1]);
        Wallet wallet = Wallet.load(Path.of(args[2]));
        byte[] boxId = Utils.hexStringToByteArray(args[3]);
        long fee = flagPdn(args, "--fee");
        byte[] data = rhizome.core.box.BoxPayload.encodeTarget(boxId);

        long nonce = client.walletInfo(wallet.address()).nextNonce();
        var tx = wallet.signedBox(TransactionKind.BOX_SPEND, wallet.address(), data, 0, fee,
            client.chainId(), nonce, System.currentTimeMillis());
        String status = client.submit(tx);
        System.out.println("box: " + args[3]);
        System.out.println("status: " + status);
        if (!"SUCCESS".equals(status)) {
            System.exit(1);
        }
    }

    private static void boxShow(String[] args) {
        require(args, 3, "box-show <nodeUrl> <boxId>");
        System.out.println(new WalletClient(args[1]).box(args[2]));
    }

    private static void boxList(String[] args) {
        require(args, 3, "box-list <nodeUrl> <ownerAddr>");
        System.out.println(new WalletClient(args[1]).boxesByOwner(PublicAddress.of(args[2])));
    }

    private static void callReadonly(String[] args) {
        require(args, 4, "call-readonly <nodeUrl> <contract> <hexInput>");
        byte[] input = args[3].isEmpty() ? new byte[0] : Utils.hexStringToByteArray(args[3]);
        System.out.println(new WalletClient(args[1]).callReadonly(PublicAddress.of(args[2]), input));
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

    /** A PDN-denominated flag value in base units, or 0 if absent. */
    private static long flagPdn(String[] args, String name) {
        String v = flag(args, name);
        return v == null ? 0 : Helpers.PDN(Double.parseDouble(v)).amount();
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
              register types: bytes:<hex> i64:<n> bool:<true|false> addr:<hex> hash:<hex> str:<text>""");
    }
}
