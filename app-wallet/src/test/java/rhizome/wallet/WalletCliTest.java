package rhizome.wallet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** TOFU chainId decision logic of the signing commands (audit F2). */
class WalletCliTest {

    @Test
    void firstContactPinsTheNodeChainId() {
        WalletCli.ChainIdDecision decision = WalletCli.decideChainId(5, null, null);
        assertEquals(5, decision.chainId());
        assertTrue(decision.pin(), "first use must record the chainId in the keyfile");
    }

    @Test
    void pinnedMismatchIsRefused() {
        // A node answering a different chainId than the pinned one is treated as hostile: the
        // signed transaction could be replayed onto the other chain.
        var pin = new Wallet.TofuPin(1, "http://localhost:7373");
        assertThrows(WalletCli.ChainIdMismatchException.class,
            () -> WalletCli.decideChainId(2, null, pin));
    }

    @Test
    void pinnedMatchSignsWithoutRepinning() {
        var pin = new Wallet.TofuPin(1, "http://localhost:7373");
        WalletCli.ChainIdDecision decision = WalletCli.decideChainId(1, null, pin);
        assertEquals(1, decision.chainId());
        assertFalse(decision.pin());
    }

    @Test
    void explicitExpectationOverridesAndRepins() {
        var pin = new Wallet.TofuPin(1, "http://localhost:7373");
        WalletCli.ChainIdDecision decision = WalletCli.decideChainId(2, "2", pin);
        assertEquals(2, decision.chainId());
        assertTrue(decision.pin(), "an explicit override must re-pin the new chainId");
    }

    @Test
    void explicitExpectationMismatchIsRefused() {
        assertThrows(WalletCli.ChainIdMismatchException.class,
            () -> WalletCli.decideChainId(2, "1", null));
    }

    @Test
    void gasParamsAreValidatedLocally() {
        // deploy/call gas arguments were parsed with a raw Long.parseLong and SIGNED, so a
        // negative or absurd value was only rejected by the node after submission (audit).
        // The CLI now fails fast, like pdnBaseUnits does for amounts.
        assertEquals(10_000_000L, WalletCli.gasParam("10000000", "gasLimit", 50_000_000L));
        assertThrows(IllegalArgumentException.class,
            () -> WalletCli.gasParam("abc", "gasLimit", 50_000_000L));
        assertThrows(IllegalArgumentException.class,
            () -> WalletCli.gasParam("-1", "gasLimit", 50_000_000L),
            "a negative gas value must not be signed");
        assertThrows(IllegalArgumentException.class,
            () -> WalletCli.gasParam("0", "gasPrice", 100_000_000_000L));
        assertThrows(IllegalArgumentException.class,
            () -> WalletCli.gasParam("999999999999999999", "gasLimit", 50_000_000L),
            "past the protocol maxTxGas ceiling the node would reject it");
        assertThrows(IllegalArgumentException.class,
            () -> WalletCli.gasParam("50000001", "gasLimit", 50_000_000L));
    }

    @Test
    void gasPositionalsStopAtTheFirstFlag() {
        // The old per-slot `!startsWith("--")` guard covered only the flag's own slot, not its
        // value: `deploy url key wasm --expect-chain-id 7` parsed gasPrice=7, and
        // `--passphrase-file p` failed with "invalid gasPrice: p" (audit).
        assertArrayEquals(new long[] {10_000_000L, 1L}, WalletCli.gasParams(
            new String[] {"deploy", "url", "key", "wasm", "--expect-chain-id", "7"}, 4),
            "a flag's value must not be swallowed as gasPrice");
        assertArrayEquals(new long[] {10_000_000L, 1L}, WalletCli.gasParams(
            new String[] {"deploy", "url", "key", "wasm", "--passphrase-file", "p"}, 4),
            "a flag value must not fail gas parsing");
        assertArrayEquals(new long[] {10_000_000L, 1L}, WalletCli.gasParams(
            new String[] {"call", "url", "key", "contract", "ff", "--expect-chain-id", "7"}, 5));
        // Real positionals still parse, with or without trailing flags.
        assertArrayEquals(new long[] {2_000_000L, 3L}, WalletCli.gasParams(
            new String[] {"deploy", "url", "key", "wasm", "2000000", "3"}, 4));
        assertArrayEquals(new long[] {2_000_000L, 3L}, WalletCli.gasParams(
            new String[] {"call", "url", "key", "contract", "ff", "2000000", "3",
                "--passphrase-file", "p"}, 5));
        assertArrayEquals(new long[] {2_000_000L, 1L}, WalletCli.gasParams(
            new String[] {"deploy", "url", "key", "wasm", "2000000", "--expect-chain-id", "7"}, 4),
            "gasLimit parses, gasPrice defaults, when a flag intervenes");
        assertArrayEquals(new long[] {10_000_000L, 1L}, WalletCli.gasParams(
            new String[] {"deploy", "url", "key", "wasm"}, 4), "no positionals at all");
    }
}
