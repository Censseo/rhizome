package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.block.Block;
import rhizome.core.block.BlockCodec;
import rhizome.core.blockchain.Contracts;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.core.transaction.TransactionKind;
import rhizome.node.RhizomeNode;

/**
 * Contract execution as it reaches a real node: a deploy and a call arriving over HTTP, and a
 * "poison block" pushed at the {@code /submit} route.
 *
 * <p>The poison block is the scenario that needs this layer. Its component proof
 * ({@code ContractConsensusTest}) shows the gas ceiling refusing an over-limit call before any
 * instruction runs — but the vector's whole premise is a *miner* pushing such a block at every
 * node on the network, and what has to hold is that the refusal happens on the wire, on the event
 * loop, without the node stalling under the consensus lock. That is only observable from outside.
 */
class E2EContractTest {

    @TempDir
    Path tempDir;

    private static final long PREMINE = 500_000_000L;
    private static final long GAS_LIMIT = 100_000L;

    private static byte[] counterWasm() {
        try (var in = E2EContractTest.class.getResourceAsStream("/counter.wasm")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Transaction contractTx(E2EFixtures.Identity sender, PublicAddress to,
                                          TransactionKind kind, byte[] data, long gasLimit, long nonce) {
        Transaction tx = TransactionImpl.builder()
            .from(sender.address()).to(to)
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(0))
            .chainId(TestNetwork.FAST.chainId()).nonce(nonce)
            .signingKey(sender.publicKey())
            .kind(kind).data(data)
            .gasLimit(gasLimit).gasPrice(1)
            .timestamp(System.currentTimeMillis())
            .build();
        tx.sign(sender.privateKey());
        return tx;
    }

    /**
     * E2E-26 — a contract is deployed and called through the node's HTTP surface, and the calls
     * are mined, executed and paid for. The happy path is here because the refusal below is only
     * meaningful if the same route accepts legitimate contract work.
     */
    @Test
    void aContractIsDeployedAndCalledThroughTheHttpSurface() throws Exception {
        E2EFixtures.Identity deployer = E2EFixtures.Identity.generate();
        Path premine = E2EFixtures.premine(tempDir.resolve("premine.json"),
            TestNetwork.FAST, Map.of(deployer, PREMINE));

        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode node = network.node("contracts")
                .snapshot(premine).mining().blockInterval(150).start();
            int port = node.apiPort();
            TestNetwork.awaitHeight(node, 3);

            long deployNonce = node.engine().nextNonce(deployer.address());
            Transaction deploy = contractTx(deployer, PublicAddress.empty(),
                TransactionKind.DEPLOY, counterWasm(), GAS_LIMIT, deployNonce);
            assertEquals(200, RawHttp.post(port, "/add_transaction_json", Map.of(),
                    deploy.toJson().toString().getBytes(StandardCharsets.UTF_8)).status(),
                "the node refused a well-formed deploy");

            TestNetwork.await(() -> node.engine().nextNonce(deployer.address()) > deployNonce,
                () -> "the deploy was never mined");

            PublicAddress contract = Contracts.deriveAddress(deployer.address(), deployNonce);
            assertTrue(node.service().contractCode(contract).length > 0,
                "the deployed code is not readable back from the node");

            long callNonce = node.engine().nextNonce(deployer.address());
            Transaction call = contractTx(deployer, contract,
                TransactionKind.CALL, new byte[0], GAS_LIMIT, callNonce);
            assertEquals(200, RawHttp.post(port, "/add_transaction_json", Map.of(),
                    call.toJson().toString().getBytes(StandardCharsets.UTF_8)).status());

            TestNetwork.await(() -> node.engine().nextNonce(deployer.address()) > callNonce,
                () -> "the contract call was never mined");
            assertTrue(node.engine().confirmedBalance(deployer.address()) < PREMINE,
                "contract execution must be paid for in gas");
            assertFalse(node.engine().isDegraded());
        }
    }

    /**
     * E2E-27 — the poison block. A miner pushes a block whose contract transaction declares more
     * gas than the per-transaction ceiling allows; at {@code gasPrice} 0 it costs the attacker
     * nothing, and every validating node would otherwise run those instructions synchronously
     * under its consensus lock. The block must be refused on the wire, and the node must be
     * demonstrably alive and able to accept an honest block immediately afterwards.
     */
    @Test
    void aPoisonBlockPushedAtTheSubmitRouteIsRefusedAndTheNodeStaysHealthy() throws Exception {
        E2EFixtures.Identity sender = E2EFixtures.Identity.generate();
        Path premine = E2EFixtures.premine(tempDir.resolve("premine.json"),
            TestNetwork.FAST, Map.of(sender, PREMINE));

        try (TestNetwork network = new TestNetwork(tempDir)) {
            // No producer: the tip must stay still, or the forged block would be refused for being
            // stale (INVALID_BLOCK_ID) and the scenario would prove nothing about the gas ceiling.
            RhizomeNode node = network.node("victim").snapshot(premine).start();
            int port = node.apiPort();

            long overCeiling = TestNetwork.FAST.maxTxGas() + 1;
            Transaction poison = contractTx(sender, PublicAddress.random(),
                TransactionKind.CALL, new byte[0], overCeiling, 0L);
            Block poisonBlock = E2EFixtures.build(node, PublicAddress.random(), poison);

            long heightBefore = node.engine().height();
            var response = RawHttp.post(port, "/submit", Map.of(), BlockCodec.encode(poisonBlock));

            assertNotEquals(200, response.status(),
                "the node accepted a block declaring more gas than the ceiling permits");
            assertEquals(heightBefore, node.engine().height(),
                "the poison block must not extend the chain");
            assertFalse(node.engine().isDegraded(),
                "refusing the poison block left the node degraded");

            // Alive, and still willing to do honest work — a refusal that wedges the node is not a
            // defence.
            E2EFixtures.mint(node, PublicAddress.random());
            assertEquals(heightBefore + 1, node.engine().height());
            assertEquals(200, RawHttp.get(port, "/block_count", Map.of()).status());
        }
    }
}
