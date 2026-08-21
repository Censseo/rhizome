package rhizome.adversarial;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.block.UncleRef;
import rhizome.core.blockchain.Issuance;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.SHA256Hash;

/**
 * Builds the block an adversarial scenario submits: valid by construction, then mutated in exactly
 * one place.
 *
 * <p>Two properties make this usable as attack equipment rather than a convenience:
 *
 * <ol>
 *   <li><b>{@link #seal()} re-mines.</b> Mutating any hash-committed field invalidates the proof of
 *       work, so a hand-rolled forgery is normally rejected at the PoW gate — the last check in
 *       {@code addBlock} — no matter which rule it was aimed at. The test then passes while proving
 *       nothing. Sealing recomputes the Merkle root and mines a fresh nonce, so the forged block
 *       reaches the gate under attack carrying real work, which is also what a real attacker with
 *       a miner would submit.</li>
 *   <li><b>Only what the scenario names changes.</b> The template is the honest next block for the
 *       engine's tip: correct id, difficulty from the engine's own recomputation, parent hash,
 *       coinbase paying the fixture's miner the exact reward for the height, and a timestamp one
 *       target interval past the parent.</li>
 * </ol>
 *
 * <p>{@link #sealWithoutPow()} is the deliberate exception, for the scenarios whose whole subject
 * <em>is</em> unproven work.
 *
 * <p>The mutator vocabulary is deliberately only what the catalogued scenarios use. A new scenario
 * that needs another field mutated adds the setter here — one line delegating to {@link BlockImpl} —
 * rather than the fixture shipping a speculative surface no test exercises.
 */
public final class BlockForge {

    private final NetworkParameters params;
    private final BlockImpl block;
    private final List<Transaction> transactions = new ArrayList<>();
    private final long parentSupply;

    private boolean coinbaseSuppressed;
    private Transaction coinbase;

    BlockForge(AdversarialChain chain, NetworkParameters params,
               long height, int difficulty, SHA256Hash parentHash, AtomicLong clock) {
        this.params = params;
        this.parentSupply = chain.parentSupply();
        long timestamp = clock.addAndGet(params.desiredBlockTimeSec() * 1000L);
        this.coinbase = coinbaseFor(chain.miner(), params.miningReward(height), timestamp);
        this.block = BlockImpl.builder()
            .id((int) height)
            .timestamp(timestamp)
            .difficulty(difficulty)
            .lastBlockHash(parentHash)
            .build();
    }

    /**
     * A coinbase stamped from the fixture's controlled clock rather than from
     * {@code System.currentTimeMillis()} — which is what {@link Transaction#of(PublicAddress,
     * TransactionAmount)} uses. The timestamp is a content field, so a wall-clock coinbase makes
     * every forged block's Merkle root (and therefore its hash) depend on which millisecond the
     * test happened to run in. Two forges a millisecond apart then produce different bodies, and a
     * scenario comparing them — the CVE-2012-2459 root collision, say — fails intermittently for a
     * reason that has nothing to do with the rule under attack.
     */
    private static Transaction coinbaseFor(PublicAddress recipient, long reward, long timestamp) {
        return rhizome.core.transaction.TransactionImpl.builder()
            .to(recipient)
            .amount(new TransactionAmount(reward))
            .isTransactionFee(true)
            .timestamp(timestamp)
            .build();
    }

    // ---- honest content ----

    /** Adds a transaction after the coinbase, in submission order (the Merkle commits to it). */
    public BlockForge transaction(Transaction transaction) {
        transactions.add(transaction);
        return this;
    }

    /** Replaces the coinbase — for reward-inflation and coinbase-shape scenarios. */
    public BlockForge coinbase(Transaction replacement) {
        this.coinbase = replacement;
        return this;
    }

    /**
     * Redirects the coinbase to {@code miner} — the block's own height and timestamp are
     * unchanged, so this is purely who gets paid, not how much or when. For scenarios that need to
     * attribute mined blocks to an address other than the fixture's default miner (selfish-mining
     * revenue attribution, chiefly).
     */
    public BlockForge coinbaseTo(PublicAddress miner) {
        this.coinbase = coinbaseFor(miner, params.miningReward(block.id()), block.timestamp());
        return this;
    }

    /**
     * Commits {@code uncles} into the header — exactly what {@code BlockAssembler} does with
     * {@code engine.selectUncles()}. No fixture could otherwise construct a valid, PoW-eligible
     * {@code UncleRef} by hand, so this exists for scenarios that need a real uncle commitment
     * (GHOST weighting, uncle-reward attribution) rather than a forged one.
     */
    public BlockForge uncles(List<UncleRef> uncles) {
        block.uncles(uncles);
        return this;
    }

    /** Emits a block with no coinbase at all. */
    public BlockForge withoutCoinbase() {
        this.coinbaseSuppressed = true;
        return this;
    }

    // ---- header mutations ----

    public BlockForge timestamp(long millis) {
        block.timestamp(millis);
        return this;
    }

    // ---- named attack shapes ----

    /**
     * Appends a byte-identical copy of the last transaction (CVE-2012-2459). On a tree that folds
     * an odd level by duplicating its last hash, {@code [t0..tn]} and {@code [t0..tn,tn]} share a
     * Merkle root, so this is the classic attempt to mutate a block's body while keeping the
     * commitment its proof of work was mined over.
     */
    public BlockForge duplicateLastTransaction() {
        if (transactions.isEmpty()) {
            throw new IllegalStateException("nothing to duplicate: add a transaction first");
        }
        transactions.add(transactions.get(transactions.size() - 1));
        return this;
    }

    // ---- sealing ----

    /**
     * Assembles the body, commits the Merkle root and mines a valid nonce for whatever the block
     * now says. The returned block always carries real proof of work for its declared difficulty.
     */
    public Block seal() {
        assemble();
        block.nonce(Miner.mineNonce(block.hash(), block.difficulty(),
            params.powAlgorithm(), params.powCostsAt(block.id())));
        return block;
    }

    /**
     * Assembles and commits the body but leaves the nonce unmined — a block that claims a
     * difficulty it has not paid for. Only for scenarios whose subject is unproven work.
     */
    public Block sealWithoutPow() {
        assemble();
        return block;
    }

    private void assemble() {
        List<Transaction> body = new ArrayList<>();
        if (!coinbaseSuppressed) {
            body.add(coinbase);
        }
        body.addAll(transactions);
        block.transactions(body);
        block.merkleRoot(merkleRootOf(body));
        // Supply is in the PoW preimage (§ supply header commitment), so it is stamped here,
        // last, from whatever the forge's content says AT THIS POINT — after every mutator
        // (uncles(), coinbaseTo(), ...) has run. Computed the same way BlockAssembler stamps an
        // honest candidate, so an unmutated forge() keeps satisfying addBlock's check, and a
        // scenario that mutates the uncle list still commits a value consistent with its OWN
        // (possibly forged) declared uncles — the point being to fail on the rule under attack,
        // not to be rejected earlier as an accounting mismatch that proves nothing.
        block.supply(parentSupply == BlockImpl.SUPPLY_ABSENT
            ? BlockImpl.SUPPLY_ABSENT
            : Math.addExact(parentSupply, Issuance.minted(params, block.id(), block.difficulty(), block.uncles())));
    }

    /** The Merkle root of {@code transactions} as consensus computes it. */
    public static SHA256Hash merkleRootOf(List<Transaction> transactions) {
        MerkleTree tree = new MerkleTree();
        tree.setItems(transactions);
        return tree.getRootHash();
    }
}
