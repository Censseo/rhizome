package rhizome.adversarial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.TestNodeStores;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.mempool.MemPool;
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.net.PeerIdentity;
import rhizome.net.PeerRegistry;
import rhizome.node.NodeService;
import rhizome.node.NodeSources;

/** Peers that advertise a different chain must never enter the peer table. */
class PeerIdentityAttackTest {

    /**
     * NET-15 — Announce (via {@code /add_peer} or PEX) a peer that serves a different chain id or a
     * different genesis, to crowd real peers out of the table, burn sync rounds on it and feed
     * wrong-chain data to the broadcaster. The off-loop admission path reads the candidate's
     * {@code /info} and refuses a chainId or genesisHash mismatch; a rulesetVersion mismatch only
     * warns; an absent field or unreachable probe reads as unknown and is allowed.
     */
    @Test
    void aPeerOnAnotherChainIsRefusedAtAdmission() throws Exception {
        NetworkParameters params = NetworkParameters.testnet();
        var verifier = new SignatureVerifier();
        ChainEngine engine = ChainEngine.boot(params, TestNodeStores.inMemory(),
            new LedgerSnapshot("t", 0, params.chainId())).verifier(verifier).build();
        String genesis = engine.headerAt(1).hash().toHexString();
        String ruleset = params.rulesetVersion();

        Map<String, PeerIdentity> advertised = Map.of(
            "http://127.0.0.1:3001", new PeerIdentity(params.chainId() + 1, genesis, ruleset),
            "http://127.0.0.2:3001", new PeerIdentity(params.chainId(), "00" + genesis.substring(2), ruleset),
            "http://127.0.0.3:3001", new PeerIdentity(params.chainId(), genesis, "FF" + ruleset.substring(2)),
            "http://127.0.0.4:3001", new PeerIdentity(null, null, null),
            "http://127.0.0.6:3001", new PeerIdentity(params.chainId(), genesis, ruleset));
        PeerRegistry registry = new PeerRegistry("http://self.invalid:3000", 32);
        NodeSources sources = NodeSources.builder().peers(registry)
            .peerProbe(url -> {
                if (url.contains("127.0.0.5")) {
                    throw new IllegalStateException("unreachable");
                }
                return advertised.get(url);
            }).build();
        NodeService node = new NodeService(engine, new MemPool(params, verifier, engine, 10), sources);
        try {
            List<String> all = List.of("http://127.0.0.1:3001", "http://127.0.0.2:3001",
                "http://127.0.0.3:3001", "http://127.0.0.4:3001", "http://127.0.0.5:3001",
                "http://127.0.0.6:3001");
            all.forEach(node::addPeer);
            long deadline = System.currentTimeMillis() + 10_000;
            // One FIFO worker: once the last candidate is in, every earlier verdict is final.
            while (!registry.snapshot().contains("http://127.0.0.6:3001")
                && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            List<String> admitted = registry.snapshot();
            assertTrue(admitted.contains("http://127.0.0.6:3001"), "matching peer admitted: " + admitted);
            assertTrue(!admitted.contains("http://127.0.0.1:3001"), "wrong chainId refused: " + admitted);
            assertTrue(!admitted.contains("http://127.0.0.2:3001"), "wrong genesis refused: " + admitted);
            assertTrue(admitted.contains("http://127.0.0.3:3001"), "ruleset mismatch only warns: " + admitted);
            assertTrue(admitted.contains("http://127.0.0.4:3001"), "absent fields = unknown, allowed: " + admitted);
            assertTrue(admitted.contains("http://127.0.0.5:3001"), "unreachable probe = unknown, allowed: " + admitted);
            assertEquals(4, admitted.size());
        } finally {
            node.close();
        }
    }
}
