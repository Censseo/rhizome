package rhizome.wallet;

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
}
