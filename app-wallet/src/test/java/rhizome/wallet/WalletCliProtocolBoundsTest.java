package rhizome.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.token.TokenId;

/**
 * The CLI validates gas and mint metadata BEFORE contacting any node — a checksum typo should
 * not cost a passphrase prompt and a scrypt round to discover. That means its bounds cannot be
 * fetched from the node being addressed, so it reads them from one {@link NetworkParameters}
 * instance instead.
 *
 * <p>Reading from a single instance is only sound while these bounds are network-invariant. This
 * class is the proof obligation for that: it asserts every bound the CLI takes is identical
 * across mainnet, testnet and devnet. If a future network diverges, this test fails and the CLI
 * has to learn which network it is talking to — which is exactly the conversation that should
 * happen, rather than the wallet quietly refusing transactions the node would have accepted, or
 * signing ones it will reject.
 */
class WalletCliProtocolBoundsTest {

    private static final List<NetworkParameters> NETWORKS = List.of(
        NetworkParameters.cleanMainnet(), NetworkParameters.testnet(), NetworkParameters.devnet());

    @Test
    void everyBoundTheCliReadsIsTheSameOnEveryNetwork() {
        NetworkParameters reference = NETWORKS.get(0);
        for (NetworkParameters network : NETWORKS) {
            assertEquals(reference.maxTxGas(), network.maxTxGas(),
                "maxTxGas differs by network: the CLI's gas ceiling can no longer be a constant");
            assertEquals(reference.maxTokenSymbolBytes(), network.maxTokenSymbolBytes(),
                "maxTokenSymbolBytes differs by network");
            assertEquals(reference.maxTokenNameBytes(), network.maxTokenNameBytes(),
                "maxTokenNameBytes differs by network");
            assertEquals(reference.maxTokenDecimals(), network.maxTokenDecimals(),
                "maxTokenDecimals differs by network");
        }
    }

    /**
     * The values the CLI used to hard-code, kept here as an independent record of what the
     * bounds were when they were copied. This is not a second definition — nothing reads it —
     * but a change to any of these is a protocol change, and it should be visible as an edit to
     * a test that says so rather than as a silently different rejection message.
     */
    @Test
    void theBoundsStillHoldTheValuesTheCliUsedToCopy() {
        NetworkParameters p = NETWORKS.get(0);
        assertEquals(50_000_000L, p.maxTxGas());
        assertEquals(16, p.maxTokenSymbolBytes());
        assertEquals(64, p.maxTokenNameBytes());
        assertEquals(18, p.maxTokenDecimals());
        assertEquals(32, TokenId.SIZE, "box and token ids are both SHA-256 derived");
    }
}
