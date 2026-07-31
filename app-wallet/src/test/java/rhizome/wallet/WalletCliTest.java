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
    void legacyUnsealedPinIsRepinnedEvenWhenItMatches() {
        // A pin sitting in cleartext beside the GCM envelope is forgeable by anyone who can write
        // the key file, so a match is not enough: it must be moved inside the sealed payload
        // (audit BAS-3). A sealed pin that matches is left alone — no needless re-encryption.
        var legacy = new Wallet.TofuPin(1, "http://localhost:7373", false);
        WalletCli.ChainIdDecision decision = WalletCli.decideChainId(1, null, legacy);
        assertEquals(1, decision.chainId());
        assertTrue(decision.pin(), "an unsealed legacy pin must be re-sealed");
        assertFalse(WalletCli.decideChainId(1, null,
            new Wallet.TofuPin(1, "http://localhost:7373")).pin());
    }

    @Test
    void legacyUnsealedPinStillRefusesAMismatch() {
        // Migration must not weaken the guard: an unsealed pin that disagrees with the node is
        // still a hard failure, not a silent re-pin.
        assertThrows(WalletCli.ChainIdMismatchException.class,
            () -> WalletCli.decideChainId(2, null, new Wallet.TofuPin(1, "http://n", false)));
    }

    @Test
    void tokenAmountsAreValidatedLocally() {
        // token-mint/transfer/burn parsed amounts with a raw Long.parseLong and SIGNED them; the
        // node then rejected amount <= 0 with no hint of why (audit BAS-1).
        assertEquals(42L, WalletCli.tokenAmount("42"));
        assertThrows(IllegalArgumentException.class, () -> WalletCli.tokenAmount("-5"),
            "a negative token amount must not be signed");
        assertThrows(IllegalArgumentException.class, () -> WalletCli.tokenAmount("0"));
        assertThrows(IllegalArgumentException.class, () -> WalletCli.tokenAmount("1.5"));
        assertThrows(IllegalArgumentException.class, () -> WalletCli.tokenAmount("99999999999999999999"));
    }

    @Test
    void tokenDecimalsAreBoundedToTheProtocolRange() {
        // encodeMint narrows decimals with a (byte) cast: 300 used to be silently truncated to 44
        // and -1 to 255, minting a token the operator never asked for (audit BAS-1).
        assertEquals(8, WalletCli.tokenDecimals("8"));
        assertEquals(0, WalletCli.tokenDecimals("0"));
        assertThrows(IllegalArgumentException.class, () -> WalletCli.tokenDecimals("-1"));
        assertThrows(IllegalArgumentException.class, () -> WalletCli.tokenDecimals("19"));
        assertThrows(IllegalArgumentException.class, () -> WalletCli.tokenDecimals("300"));
        assertThrows(IllegalArgumentException.class, () -> WalletCli.tokenDecimals("x"));
    }

    @Test
    void tokenSymbolAndNameAreBoundedInUtf8Bytes() {
        // The payload's length prefix is one byte and the node's limits are in BYTES, so the
        // check must be too: 16 multi-byte characters are not 16 bytes.
        assertEquals("RHZ", WalletCli.tokenText("RHZ", "symbol", 16));
        assertThrows(IllegalArgumentException.class, () -> WalletCli.tokenText("", "symbol", 16));
        assertThrows(IllegalArgumentException.class,
            () -> WalletCli.tokenText("ABCDEFGHIJKLMNOPQ", "symbol", 16));
        assertThrows(IllegalArgumentException.class,
            () -> WalletCli.tokenText("éééééééééé", "symbol", 16),
            "20 UTF-8 bytes must be refused even though it is 10 characters");
    }

    @Test
    void identifiersMustBe32ByteHex() {
        // Unvalidated ids reached the node's query string (a '&' injected extra parameters) and a
        // fixed-size ByteBuffer (BufferOverflowException on anything longer) — audit BAS-2.
        String id = "0".repeat(64);
        assertEquals(id, WalletCli.hexId(id, "tokenId"));
        assertEquals(32, WalletCli.idBytes(id, "tokenId").length);
        assertThrows(IllegalArgumentException.class, () -> WalletCli.hexId("dead", "tokenId"),
            "a short id would encode a payload the node rejects after signing");
        assertThrows(IllegalArgumentException.class, () -> WalletCli.hexId("0".repeat(66), "boxId"),
            "an over-long id used to raise a bare BufferOverflowException");
        assertThrows(IllegalArgumentException.class,
            () -> WalletCli.hexId("x&id2=y" + "0".repeat(57), "tokenId"),
            "query-parameter injection must be refused before the request is built");
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
