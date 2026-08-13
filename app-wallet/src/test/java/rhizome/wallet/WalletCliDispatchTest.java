package rhizome.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * L21 lock: the CLI's dispatch table must cover every documented command, bind each name to the
 * handler whose arity gate runs BEFORE any network I/O, and report its outcome as a RETURNED
 * exit code rather than a {@code System.exit} inside a command body. Every assertion here runs
 * offline: the network commands are probed only through their pre-network arity gates (one
 * positional short of the minimum, so no client is ever built), and {@code keygen}/{@code
 * address} through a real end-to-end run in a temp dir.
 */
class WalletCliDispatchTest {

    /** command -> minimal argument count INCLUDING the command name (its own usage gate). */
    private static final Map<String, Integer> MIN_ARGS = Map.ofEntries(
        Map.entry("keygen", 2),
        Map.entry("address", 2),
        Map.entry("balance", 3),
        Map.entry("send", 5),
        Map.entry("deploy", 4),
        Map.entry("call", 5),
        Map.entry("box-create", 4),
        Map.entry("box-update", 4),
        Map.entry("box-spend", 4),
        Map.entry("box-show", 3),
        Map.entry("box-list", 3),
        Map.entry("call-readonly", 4),
        Map.entry("token-mint", 7),
        Map.entry("token-transfer", 6),
        Map.entry("token-burn", 5),
        Map.entry("token-show", 3),
        Map.entry("token-balance", 4),
        Map.entry("token-list", 3)
    );

    @Test
    void unknownCommandIsReportedAsExitCode2() throws Exception {
        // dispatch prints the usage line itself; the code 2 is what main() turns into System.exit.
        assertEquals(2, WalletCli.dispatch(new String[] {"definitely-not-a-command"}));
    }

    @Test
    void everyCommandRefusesItsMissingPositionalsBeforeAnyNetworkIo() {
        for (Map.Entry<String, Integer> command : MIN_ARGS.entrySet()) {
            String name = command.getKey();
            String[] tooFew = new String[command.getValue() - 1];
            tooFew[0] = name;
            var ex = assertThrows(IllegalArgumentException.class,
                () -> WalletCli.dispatch(tooFew), name + " with too few args must fail offline");
            assertTrue(ex.getMessage().startsWith("usage: " + name),
                name + " must be bound to its own handler's gate, got: " + ex.getMessage());
        }
    }

    @Test
    void keygenAndAddressRunEndToEndWithoutANode(@TempDir Path dir) throws Exception {
        String keyfile = dir.resolve("wallet.key").toString();
        // keygen: creates and writes the key file in non-interactive mode (--plaintext).
        assertEquals(0, WalletCli.dispatch(new String[] {"keygen", keyfile, "--plaintext"}),
            "keygen must complete offline and report success as exit code 0");
        // address: reads the same file back — the full dispatch -> handler path, no network.
        assertEquals(0, WalletCli.dispatch(new String[] {"address", keyfile}),
            "address must complete offline and report success as exit code 0");
    }
}
