package rhizome.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.dto.TransactionDto;

/**
 * The WALLET-08/09/11 helpers are unit-tested in {@link WalletCliHardeningAttackTest}; these prove the
 * commands actually CALL them. A stub node answers {@code /info}, {@code /wallet} and
 * {@code /add_transaction}, so each case runs the real {@code send}/{@code balance}/{@code address}
 * path: deleting the call to a helper (rather than the helper) must fail a test.
 */
class WalletCliWiringTest {

    /** A node reporting {@code chainId} and a fixed next nonce; records the last submitted tx bytes. */
    private static HttpServer stub(int chainId, long nextNonce, AtomicReference<byte[]> submitted) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/info", ex -> reply(ex, "{\"chainId\":" + chainId + "}"));
        server.createContext("/wallet", ex ->
            reply(ex, "{\"balance\":1000000000000,\"nextNonce\":" + nextNonce + "}"));
        server.createContext("/add_transaction", ex -> {
            submitted.set(ex.getRequestBody().readAllBytes());
            reply(ex, "{\"status\":\"SUCCESS\"}");
        });
        server.start();
        return server;
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, String json) throws java.io.IOException {
        byte[] b = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private static String url(HttpServer s) {
        return "http://127.0.0.1:" + s.getAddress().getPort();
    }

    private static String newKey(Path dir) throws Exception {
        String keyfile = dir.resolve("wallet.key").toString();
        assertEquals(0, WalletCli.dispatch(new String[] {"keygen", keyfile, "--plaintext"}));
        return keyfile;
    }

    /** WALLET-08 -- {@code send} derives the chainId expectation from {@code --network}. */
    @Test
    void sendOnFirstUseHonoursTheStatedNetworkAndRefusesAMismatchedNode(@TempDir Path dir) throws Exception {
        String keyfile = newKey(dir);
        String to = PublicAddress.random().toHexString();
        int testnet = NetworkParameters.testnet().chainId();
        var sent = new AtomicReference<byte[]>();
        HttpServer good = stub(testnet, 0, sent);
        HttpServer lying = stub(NetworkParameters.cleanMainnet().chainId(), 0, new AtomicReference<>());
        try {
            assertThrows(WalletCli.ChainIdMismatchException.class, () -> WalletCli.dispatch(
                new String[] {"send", url(good), keyfile, to, "1"}), "first use with no stated network");
            assertThrows(WalletCli.ChainIdMismatchException.class, () -> WalletCli.dispatch(
                new String[] {"send", url(lying), keyfile, to, "1", "--network", "testnet"}),
                "a node answering another chain than --network names");
            assertEquals(0, WalletCli.dispatch(
                new String[] {"send", url(good), keyfile, to, "1", "--network", "testnet"}));
        } finally {
            good.stop(0);
            lying.stop(0);
        }
    }

    /** WALLET-09 -- {@code send} runs the cross-check and honours {@code --nonce}. */
    @Test
    void sendCrossChecksTheNonceAndHonoursTheOverride(@TempDir Path dir) throws Exception {
        String keyfile = newKey(dir);
        String to = PublicAddress.random().toHexString();
        int testnet = NetworkParameters.testnet().chainId();
        var sent = new AtomicReference<byte[]>();
        HttpServer primary = stub(testnet, 8, sent);   // hostile: real+1
        HttpServer second = stub(testnet, 7, new AtomicReference<>());
        try {
            var ex = assertThrows(IllegalArgumentException.class, () -> WalletCli.dispatch(new String[] {
                "send", url(primary), keyfile, to, "1", "--network", "testnet", "--cross-check", url(second)}));
            assertTrue(ex.getMessage().contains("disagree"), ex.getMessage());
            assertEquals(null, sent.get(), "nothing was signed or submitted after the disagreement");

            assertEquals(0, WalletCli.dispatch(new String[] {
                "send", url(primary), keyfile, to, "1", "--network", "testnet", "--nonce", "5"}));
            Transaction tx = Transaction.of(TransactionDto.fromBuffer(sent.get()));
            assertEquals(5L, tx.nonce(), "--nonce overrides the node's answer");
        } finally {
            primary.stop(0);
            second.stop(0);
        }
    }

    /** WALLET-11 -- the passphrase file and the API-token file are both refused when group/other readable. */
    @Test
    void commandsRefuseGroupReadableSecretFiles(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("secret");
        Files.writeString(secret, "hunter2\n");
        try {
            Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException e) {
            return; // non-POSIX filesystem: the check is skipped by design
        }
        String keyfile = newKey(dir);
        var pass = assertThrows(IllegalArgumentException.class, () -> WalletCli.dispatch(
            new String[] {"address", keyfile, "--passphrase-file", secret.toString()}));
        assertTrue(pass.getMessage().contains("passphrase file"), pass.getMessage());
        var token = assertThrows(IllegalArgumentException.class, () -> WalletCli.dispatch(
            new String[] {"balance", "http://127.0.0.1:1", PublicAddress.random().toHexString(),
                "--api-token-file", secret.toString()}));
        assertTrue(token.getMessage().contains("api token file"), token.getMessage());
    }
}
