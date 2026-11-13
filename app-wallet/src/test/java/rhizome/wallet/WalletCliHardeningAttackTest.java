package rhizome.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.blockchain.NetworkParameters;

/** Offline attack cases for the wallet CLI's trust in node answers and operator input. */
class WalletCliHardeningAttackTest {

    /**
     * WALLET-08 — a hostile "testnet" node answers /info with the mainnet chainId; a wallet that
     * pins whatever the node says would sign a mainnet-valid transaction.
     */
    @Test
    void firstContactWithoutAnOperatorStatedNetworkIsRefused() {
        assertThrows(WalletCli.ChainIdMismatchException.class,
            () -> WalletCli.decideChainId(1, null, null));
        String testnet = WalletCli.expectedChainId(null, "testnet", null);
        assertEquals(String.valueOf(NetworkParameters.testnet().chainId()), testnet);
        int mainnetId = NetworkParameters.cleanMainnet().chainId();
        assertThrows(WalletCli.ChainIdMismatchException.class,
            () -> WalletCli.decideChainId(mainnetId, testnet, null),
            "node answering mainnet to a --network testnet request must be refused");
        assertThrows(IllegalArgumentException.class,
            () -> WalletCli.expectedChainId("999", "testnet", null),
            "conflicting --network and --expect-chain-id must be refused");
    }

    /**
     * WALLET-09 — a node answers real+1 and withholds the signed transaction; the second node's
     * nonce exposes it, and --nonce lets the retry re-sign at the same nonce.
     */
    @Test
    void nonceFromAHostileNodeIsCrossCheckedAndOverridable() {
        assertThrows(IllegalArgumentException.class, () -> WalletCli.checkNoncesAgree(8, 7));
        WalletCli.checkNoncesAgree(7, 7);
        assertEquals(7L, WalletCli.parseNonce("7"));
        assertThrows(IllegalArgumentException.class, () -> WalletCli.parseNonce("-1"));
        assertThrows(IllegalArgumentException.class, () -> WalletCli.parseNonce("x"));
    }

    /**
     * WALLET-10 — "1e5" must not send 100000 PDN; signs, excess precision and long overflow must
     * fail rather than be silently reinterpreted.
     */
    @Test
    void amountsWithExponentSignOrExcessPrecisionAreRefused() {
        assertEquals(15_000L, WalletCli.pdnBaseUnits("1.5"));
        for (String bad : new String[] {"1e5", "1E5", "+1", "-1", ".5", "5.", "1.00001",
                "0x10", " 1", "1 ", "922337203685477.5808", "99999999999999999999999"}) {
            assertThrows(IllegalArgumentException.class, () -> WalletCli.pdnBaseUnits(bad), bad);
        }
    }

    /**
     * WALLET-11 — a group/other-readable passphrase (or API token) file unlocks the wallet for any
     * local user; the CLI refuses it instead of warning.
     */
    @Test
    void secretFileReadableByOthersIsRefused(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("pass");
        Files.writeString(f, "secret\n");
        try {
            Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException e) {
            return; // non-POSIX filesystem: the check is skipped by design
        }
        var e = assertThrows(IllegalArgumentException.class,
            () -> WalletCli.requireOwnerOnly(f, "passphrase file"));
        assertTrue(e.getMessage().contains("chmod 600"));
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
        WalletCli.requireOwnerOnly(f, "passphrase file");
    }
}
