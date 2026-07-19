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
              call    <nodeUrl> <keyfile> <contract> <hexInput> [gasLimit] [gasPrice]""");
    }
}
