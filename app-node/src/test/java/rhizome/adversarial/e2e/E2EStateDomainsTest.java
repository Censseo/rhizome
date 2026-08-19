package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.box.Box;
import rhizome.core.box.BoxPayload;
import rhizome.core.box.BoxRegister;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.token.TokenMeta;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.core.transaction.TransactionKind;
import rhizome.core.token.TokenPayload;
import rhizome.crypto.Hex;
import rhizome.node.RhizomeNode;

/**
 * Data boxes and native tokens through a real node, and through a real reorganisation.
 *
 * <p>These two domains keep their own stores and their own persisted undo journals, and consensus
 * requires their state to move atomically with the block that caused it and to reverse exactly when
 * that block is orphaned. The component suites prove the journals invert correctly against an
 * in-memory store. What they cannot reach is the same inversion running on RocksDB, inside a reorg
 * driven over HTTP by a peer, on a node that is doing everything else at the same time — which is
 * the only configuration that has ever actually lost state.
 *
 * <p>The observation is deliberately made from outside, over the node's own read API: if a box
 * survives a reorg only in the store and not in the served view (or the reverse) the node is
 * lying to its clients, and that is exactly as bad as losing it.
 */
class E2EStateDomainsTest {

    @TempDir
    Path tempDir;

    private static final long PREMINE = 10_000_000L;
    private static final long BOX_VALUE = 500_000L;
    private static final long MINT_AMOUNT = 4_242L;

    private static Transaction boxCreate(E2EFixtures.Identity owner, long nonce) {
        Transaction tx = TransactionImpl.builder()
            .from(owner.address()).to(owner.address()).signingKey(owner.publicKey())
            .amount(new TransactionAmount(BOX_VALUE)).fee(new TransactionAmount(0))
            .chainId(TestNetwork.FAST.chainId()).nonce(nonce)
            .timestamp(System.currentTimeMillis())
            .kind(TransactionKind.BOX_CREATE)
            .data(BoxPayload.encodeCreate(List.of(BoxRegister.string("e2e"), BoxRegister.i64(7))))
            .gasLimit(0).gasPrice(0).build();
        tx.sign(owner.privateKey());
        return tx;
    }

    private static Transaction tokenMint(E2EFixtures.Identity minter, long nonce) {
        Transaction tx = TransactionImpl.builder()
            .from(minter.address()).to(minter.address()).signingKey(minter.publicKey())
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(0))
            .chainId(TestNetwork.FAST.chainId()).nonce(nonce)
            .timestamp(System.currentTimeMillis())
            .kind(TransactionKind.TOKEN_MINT)
            .data(TokenPayload.encodeMint(MINT_AMOUNT, 2, "E2E", "End To End"))
            .gasLimit(0).gasPrice(0).build();
        tx.sign(minter.privateKey());
        return tx;
    }

    private static int boxStatus(int port, byte[] boxId) {
        return RawHttp.get(port, "/box?id=" + Hex.bytesToHex(boxId), Map.of()).status();
    }

    private static int tokenStatus(int port, byte[] tokenId) {
        return RawHttp.get(port, "/token?id=" + Hex.bytesToHex(tokenId), Map.of()).status();
    }

    /**
     * E2E-29 — a box and a token created on a real node are committed and served back over the
     * node's own read API, and the box's value is genuinely locked out of the owner's balance.
     */
    @Test
    void aBoxAndATokenCreatedOnARealNodeAreCommittedAndServedBack() throws Exception {
        E2EFixtures.Identity owner = E2EFixtures.Identity.generate();
        Path premine = E2EFixtures.premine(tempDir.resolve("premine.json"),
            TestNetwork.FAST, Map.of(owner, PREMINE));

        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode node = network.node("domains").snapshot(premine).start();
            int port = node.apiPort();
            assertTrue(node.service().boxesAvailable() && node.service().tokensAvailable(),
                "the node must have both domains wired, or this proves nothing");

            byte[] boxId = Box.deriveId(owner.address(), 0);
            byte[] tokenId = TokenMeta.deriveId(owner.address(), 1).toBytes();
            assertEquals(404, boxStatus(port, boxId), "the box cannot exist before it is created");

            E2EFixtures.mint(node, PublicAddress.random(), boxCreate(owner, 0), tokenMint(owner, 1));

            assertEquals(200, boxStatus(port, boxId), "the created box is not served back");
            assertEquals(200, tokenStatus(port, tokenId), "the minted token is not served back");
            assertEquals(PREMINE - BOX_VALUE, node.engine().confirmedBalance(owner.address()),
                "the box's value must be locked out of the owner's spendable balance");
            assertEquals(2L, node.engine().nextNonce(owner.address()));
        }
    }

    /**
     * E2E-30 — the same box and token, on a branch that loses a fork race. Both domains must
     * reverse exactly: the box and token gone from the served view, the locked value returned, the
     * nonce free again. A domain that reverts partially leaves the node's state root disagreeing
     * with its peers, which is a permanent fork rather than a lost box.
     */
    @Test
    void aReorgReversesBoxAndTokenStateExactlyOnARealNode() throws Exception {
        E2EFixtures.Identity owner = E2EFixtures.Identity.generate();
        Path premine = E2EFixtures.premine(tempDir.resolve("premine.json"),
            TestNetwork.FAST, Map.of(owner, PREMINE));

        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode winner = network.node("winner")
                .snapshot(premine).mining().blockInterval(250).start();
            RhizomeNode loser = network.node("loser").snapshot(premine).start();
            int port = loser.apiPort();

            byte[] boxId = Box.deriveId(owner.address(), 0);
            byte[] tokenId = TokenMeta.deriveId(owner.address(), 1).toBytes();

            // The losing branch creates both, then buries them.
            E2EFixtures.mint(loser, PublicAddress.random(), boxCreate(owner, 0), tokenMint(owner, 1));
            E2EFixtures.mintEmpty(loser, PublicAddress.random(), 3);
            assertEquals(200, boxStatus(port, boxId));
            assertEquals(200, tokenStatus(port, tokenId));
            assertEquals(PREMINE - BOX_VALUE, loser.engine().confirmedBalance(owner.address()));

            TestNetwork.awaitHeight(winner, 12);
            loser.service().addPeer(TestNetwork.urlOf(winner));
            TestNetwork.await(() -> loser.knownPeers().contains(TestNetwork.urlOf(winner)),
                () -> "the winning peer was never admitted");
            TestNetwork.syncUntil(loser,
                () -> loser.engine().blockAt(2).hash().equals(winner.engine().blockAt(2).hash()));

            assertEquals(404, boxStatus(port, boxId),
                "the box outlived the branch that created it");
            assertEquals(404, tokenStatus(port, tokenId),
                "the token outlived the branch that minted it");
            assertEquals(PREMINE, loser.engine().confirmedBalance(owner.address()),
                "the box's locked value was not returned by the reorg");
            assertEquals(0L, loser.engine().nextNonce(owner.address()),
                "the nonces must be free again, or the owner can never redo either operation");
            assertFalse(loser.engine().isDegraded(),
                "a reorg that leaves the node degraded has not reverted cleanly");
        }
    }
}
