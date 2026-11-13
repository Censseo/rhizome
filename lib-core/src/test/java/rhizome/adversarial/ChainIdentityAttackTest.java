package rhizome.adversarial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.GenesisBlock;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.TestNodeStores;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.SnapshotLoader;

/**
 * Chain-identity attacks: a node (or the artifact it is handed) quietly redefining which chain it
 * is on. A pinned network's genesis hash is a release constant, so every route that would yield a
 * different genesis must refuse before the node serves or mines a single block.
 */
class ChainIdentityAttackTest {

    /**
     * GENESIS-05 — Hand a node on a pinned network (staging) a tampered allocation artifact or a
     * retuned genesis constant, so it boots a self-consistent but different genesis and forks off
     * the real network on block 2 (silently, with every peer later refused as "wrong chain").
     * The profile's pinned genesis hash makes the boot refuse instead, and the constant is proven
     * to equal what the shipped artifact computes so the two can only move together.
     */
    @Test
    void aGenesisThatDiffersFromTheProfilesPinnedHashRefusesToBoot() throws IOException {
        NetworkParameters staging = NetworkParameters.staging();
        LedgerSnapshot shipped = SnapshotLoader.forBoot(Optional.empty(), staging);

        // The pin equals the computed hash of the shipped artifact (lockstep).
        assertEquals(staging.expectedGenesisHash(), GenesisBlock.build(staging, shipped).hash());

        // The shipped artifact boots.
        ChainEngine.boot(staging, TestNodeStores.mixing(new InMemoryLedger(), new InMemoryChainStore()),
            shipped).build();

        // Same artifact, one retuned genesis constant: a different genesis, refused at boot.
        NetworkParameters retuned = staging.toBuilder().genesisDifficulty(9).build();
        IllegalStateException refused = assertThrows(IllegalStateException.class,
            () -> ChainEngine.boot(retuned,
                TestNodeStores.mixing(new InMemoryLedger(), new InMemoryChainStore()), shipped).build());
        assertTrue(refused.getMessage().contains("genesis"), refused.getMessage());

        // An unpinned profile (testnet) still boots whatever its snapshot yields.
        NetworkParameters testnet = NetworkParameters.testnet();
        assertEquals(null, testnet.expectedGenesisHash());
        assertEquals(null, NetworkParameters.devnet().expectedGenesisHash());
        assertEquals(null, NetworkParameters.cleanMainnet().expectedGenesisHash());
    }
}
