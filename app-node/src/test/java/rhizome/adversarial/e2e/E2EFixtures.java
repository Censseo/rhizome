package rhizome.adversarial.e2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SupplyStamp;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.Crypto;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import rhizome.node.RhizomeNode;

/**
 * The two things an end-to-end adversarial scenario needs that a running node does not hand it:
 * funded identities, and a way to put a chosen block on a chosen node's chain.
 *
 * <p><b>Funding.</b> A fresh network has no coins but mining rewards, and rewards are branch-local:
 * an account paid by node B's branch has no balance on node A's. A cross-branch double-spend
 * therefore cannot be built from rewards at all — the spend would simply be unfunded on the
 * winning side, and the scenario would prove nothing. {@link #premine} writes a genesis balance
 * snapshot that every node in the scenario loads, so the same account is funded on every branch
 * and the conflict is real.
 *
 * <p><b>Minting.</b> A node with a producer mines whenever it likes, which is what a scenario
 * wants for realism and not at all what it wants for control. {@link #mint} builds a valid block
 * for a node's current tip and pushes it through {@code submitBlock} — the same entry point the
 * {@code /submit} route uses, so validation is the real one — letting a scenario lay down an exact
 * branch on a producer-less node and keep the fork it is testing deterministic.
 */
final class E2EFixtures {

    private E2EFixtures() {
    }

    /** A key pair with its address, funded at genesis by {@link #premine}. */
    record Identity(PublicKey publicKey, PrivateKey privateKey, PublicAddress address) {

        static Identity generate() {
            Crypto.KeyPair pair = Crypto.generateKeyPairTyped();
            return new Identity(pair.publicKey(), pair.privateKey(),
                PublicAddress.of(pair.publicKey()));
        }

        /** A signed transfer, stamped now, on {@code params}' network. */
        Transaction send(PublicAddress to, long amount, long fee, long nonce, NetworkParameters params) {
            Transaction tx = Transaction.of(address, to, new TransactionAmount(amount), publicKey,
                new TransactionAmount(fee), System.currentTimeMillis(), params.chainId(), nonce);
            tx.sign(privateKey);
            return tx;
        }
    }

    /**
     * Writes a genesis balance snapshot to {@code file} and returns its path, for
     * {@code NodeConfig.withSnapshot}. Every node given the same file boots the same genesis, which
     * is what makes their branches forks of one chain rather than separate networks.
     */
    static Path premine(Path file, NetworkParameters params, Map<Identity, Long> balances) {
        LedgerSnapshot snapshot = new LedgerSnapshot("e2e-premine", 0, params.chainId());
        Map<PublicAddress, Long> ordered = new LinkedHashMap<>();
        balances.forEach((identity, amount) -> ordered.put(identity.address(), amount));
        ordered.forEach((address, amount) -> snapshot.put(address, new TransactionAmount(amount)));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, snapshot.toJson().toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    /**
     * Builds a valid block extending {@code node}'s current tip, carrying {@code transactions}
     * after a coinbase paying {@code miner}, and submits it. Returns the accepted block.
     *
     * <p>The timestamp comes from the engine's own floor calculation, so the block satisfies
     * median-time-past and the parent floor without the scenario having to know either rule; the
     * proof of work is real, at whatever difficulty the chain currently demands.
     *
     * @throws AssertionError if the node refuses the block — a scenario that meant to establish a
     *                        branch must fail loudly rather than quietly assert against a chain
     *                        one block shorter than it thinks
     */
    static Block mint(RhizomeNode node, PublicAddress miner, Transaction... transactions) {
        Block block = build(node, miner, transactions);
        ExecutionStatus status = node.service().submitBlock(block);
        if (status != ExecutionStatus.SUCCESS) {
            throw new AssertionError("minting block " + block.id() + " onto the test node failed: " + status);
        }
        return block;
    }

    /**
     * As {@link #mint}, but stopping short of submission — for scenarios whose subject is a block
     * the node must <em>refuse</em>, which have to hold the block in order to offer it.
     */
    static Block build(RhizomeNode node, PublicAddress miner, Transaction... transactions) {
        NetworkParameters params = node.engine().params();
        long height = node.engine().height() + 1;
        long parentSupply = node.engine().headerAt(node.engine().height()).supply();

        BlockImpl block = (BlockImpl) BlockImpl.builder()
            .id((int) height)
            .timestamp(node.engine().nextBlockTimestamp(System.currentTimeMillis()))
            .difficulty(node.engine().difficulty())
            .lastBlockHash(node.engine().tipHash())
            .supply(SupplyStamp.next(node.engine(), height, node.engine().difficulty()))
            .build();
        // Curve-aware, mirroring BlockAssembler.assemble's own dispatch exactly: the coinbase must
        // agree with the supply stamp above (both read from the SAME parentSupply), or a curve-
        // active profile mints a coinbase that contradicts its own committed supply header and
        // Executor.runBlock rejects it (INCORRECT_MINING_FEE) on every block this fixture builds.
        long rewardAmount = parentSupply == BlockImpl.SUPPLY_ABSENT
            ? params.miningReward(height)
            : params.miningReward(height, parentSupply);
        block.addTransaction(Transaction.of(miner, new TransactionAmount(rewardAmount)));
        for (Transaction transaction : transactions) {
            block.addTransaction(transaction);
        }
        MerkleTree tree = new MerkleTree();
        tree.setItems(block.transactions());
        block.merkleRoot(tree.getRootHash());
        // A real node runs a state accumulator, so the header must commit the state this block
        // produces — and the commitment is inside the hash preimage, so it has to be stamped
        // BEFORE the nonce is solved. Omitting it is not a soft failure: the node answers
        // INVALID_STATE_ROOT and the scenario silently builds no branch at all. Same order as
        // BlockProducer.produce().
        node.engine().stampStateRoot(block);
        block.nonce(Miner.mineNonce(block.hash(), block.difficulty(),
            params.powAlgorithm(), params.powCostsAt(height)));
        return block;
    }

    /** Mints {@code count} empty blocks onto {@code node}. */
    static void mintEmpty(RhizomeNode node, PublicAddress miner, int count) {
        for (int i = 0; i < count; i++) {
            mint(node, miner);
        }
    }

    /**
     * Mines single empty blocks onto {@code node} until its committed supply reaches
     * {@code targetSupply}, or honest mining stalls first — the curve's zero-reward plateau
     * (contracts/emission-curve.md §4: a band of strictly-positive width, immediately below
     * {@code supplyTarget}, where {@code EmissionCurve.raw} floors to exactly 0). Neither
     * {@code TestNetwork} nor {@code E2EFixtures} previously offered a way to aim for a precise
     * parent supply rather than "mine N blocks" — needed to land a real chain at (or within a
     * table step of) a specific {@code EmissionCurve} position.
     *
     * @return the final committed supply, which callers must compare against {@code targetSupply}
     *         themselves: landing inside the plateau band means the target is never reached by
     *         honest mining alone, which is the scenario, not a bug in this helper
     */
    static long mintToSupply(RhizomeNode node, PublicAddress miner, long targetSupply) {
        long supply = node.engine().headerAt(node.engine().height()).supply();
        while (supply < targetSupply) {
            mint(node, miner);
            long next = node.engine().headerAt(node.engine().height()).supply();
            if (next == supply) {
                break;
            }
            supply = next;
        }
        return supply;
    }
}
