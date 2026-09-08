package rhizome.adversarial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Issuance;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.TestNodeStores;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.Crypto;
import rhizome.crypto.SHA256Hash;

/**
 * TIME family (see {@code docs/adversarial/spec.md}): here the thing that decides whether
 * {@code ChainEngine}'s future-block bound fires is the victim's own wall clock, not an attacker's
 * crafted input. A node whose local clock has drifted ahead of real time is strictly more
 * permissive than an aligned one — it accepts a block an honest peer, booted from the identical
 * genesis, currently refuses as too far in the future.
 *
 * <p>That gap is not a hole to close: every node is entitled to compute the bound from its own
 * {@code LongSupplier} clock (WHITEPAPER §3.5), and nothing in the protocol can force two
 * independently-clocked nodes to agree on "now". What the rule must guarantee instead is that the
 * gap is <em>bounded and self-healing</em> — the rejection never mutates state, and the moment real
 * time (or the honest node's own clock) advances past the block's timestamp minus
 * {@code maxFutureBlockTimeSec}, the two nodes reconverge on the identical block without any
 * special-cased recovery path.
 */
class ClockDriftAttackTest {

    /**
     * A coinbase-only block, hand-mined for {@code height} onto {@code parentHash} — mirrors
     * {@code SupplyCommitmentTest}/{@code SupplyLedgerAttackTest}'s own fixture idiom rather than
     * {@code AdversarialChain}: {@code AdversarialChain.Builder#fund} mints a fresh random keypair
     * on every {@code build()}, so two independently funded chains would never share a genesis hash
     * on a supply-pinned profile like {@link NetworkParameters#staging()} — and this scenario's
     * whole premise (one block, valid against either engine's identical tip) depends on them
     * sharing one.
     */
    private static Block mineOnto(NetworkParameters params, long height, SHA256Hash parentHash,
            int difficulty, long timestamp, PublicAddress miner, long parentSupply) {
        var b = (BlockImpl) BlockImpl.builder()
            .id((int) height)
            .timestamp(timestamp)
            .difficulty(difficulty)
            .lastBlockHash(parentHash)
            .uncles(new ArrayList<>())
            .supply(parentSupply
                + Issuance.minted(params, height, parentSupply, difficulty, List.of()))
            .build();
        b.addTransaction(Transaction.of(miner,
            new TransactionAmount(params.miningReward(height, parentSupply))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm(),
            params.powCostsAt(height)));
        return b;
    }

    /**
     * TIME-06 — A victim node on {@link NetworkParameters#staging()} (mainnet's real
     * {@code maxFutureBlockTimeSec} of 15s, inherited unchanged) whose wall clock reads 20s ahead of
     * real time accepts a block stamped 18s ahead of real time: that timestamp sits comfortably
     * inside the drifted node's OWN 15s-from-its-own-clock window, even though it is 3s past the
     * 15s window an aligned clock enforces. An honest peer, booted from the byte-identical genesis
     * but running an aligned clock, rejects the very same block with
     * {@code BLOCK_TIMESTAMP_IN_FUTURE} — and that rejection is free: the honest engine's height and
     * tip do not move, so the two nodes diverge instead of either one corrupting state. Once the
     * honest node's own clock (standing in for real time passing) reaches where the drifted node's
     * clock already stood, the identical, never-re-mined block is accepted there too and the two
     * tips reconverge — proving the divergence a clock drift causes is a self-healing timing
     * artifact, not a permanent fork forced by forged content.
     */
    @Test
    void aDriftedClockAcceptsWhatAnAlignedClockRejectsUntilRealTimeCatchesUp() {
        NetworkParameters params = NetworkParameters.staging();

        // staging() pins a genesis supply (inherited from cleanMainnet(), § genesis allocation):
        // an empty snapshot's total (0) would fail GenesisBlock's boot-time pin check. Both
        // engines fund the SAME address with the SAME amount -- not just the same total, since
        // GENESIS-02 in the catalogue is exactly "same total, different distribution" changing the
        // genesis hash -- so the two independently booted chains still share a byte-identical tip.
        PublicAddress whale = PublicAddress.of(Crypto.generateKeyPairTyped().publicKey());

        AtomicLong driftedClock = new AtomicLong();
        LedgerSnapshot driftedSnapshot = new LedgerSnapshot("clock-drift", 0, params.chainId());
        driftedSnapshot.put(whale, new TransactionAmount(params.genesisSupply()));
        ChainEngine driftedEngine = ChainEngine.boot(params,
                TestNodeStores.mixing(new InMemoryLedger(), new InMemoryChainStore()), driftedSnapshot)
            .clock(driftedClock::get)
            .build();

        AtomicLong honestClock = new AtomicLong();
        LedgerSnapshot honestSnapshot = new LedgerSnapshot("clock-drift", 0, params.chainId());
        honestSnapshot.put(whale, new TransactionAmount(params.genesisSupply()));
        ChainEngine honestEngine = ChainEngine.boot(params,
                TestNodeStores.mixing(new InMemoryLedger(), new InMemoryChainStore()), honestSnapshot)
            .clock(honestClock::get)
            .build();

        assertEquals(honestEngine.tipHash(), driftedEngine.tipHash(),
            "identical params and an identical (address, amount) genesis snapshot must produce a "
                + "byte-identical genesis hash");

        long t = 1_000_000_000_000L;
        // The victim's own wall clock: 20s ahead of real time t, beyond staging()'s inherited 15s
        // future-tolerance window.
        driftedClock.set(t + 20_000L);
        // The honest observer's clock is real, aligned time.
        honestClock.set(t);

        PublicAddress miner = PublicAddress.of(Crypto.generateKeyPairTyped().publicKey());
        long parentSupply = driftedEngine.headerAt(driftedEngine.height()).supply();
        // A block stamped 18s ahead of real time t: past the 15s bound an aligned clock enforces,
        // but still comfortably inside the drifted node's own (also skewed) 15s window measured
        // from ITS clock, so it clears that node's own future-time check.
        Block block = mineOnto(params, driftedEngine.height() + 1, driftedEngine.tipHash(),
            driftedEngine.difficulty(), t + 18_000L, miner, parentSupply);

        assertEquals(ExecutionStatus.SUCCESS, driftedEngine.addBlock(block),
            "the drifted node's own clock puts this timestamp inside its 15s future-tolerance window");
        assertEquals(2, driftedEngine.height());

        assertEquals(ExecutionStatus.BLOCK_TIMESTAMP_IN_FUTURE, honestEngine.addBlock(block),
            "the SAME block is 18s ahead of the honest node's aligned clock, past the 15s bound");
        assertEquals(1, honestEngine.height(),
            "the rejection must be free: an honest node's state never moves for a future block");

        // The two nodes have diverged purely from a clock skew, not from any forged content.
        assertEquals(2, driftedEngine.height());
        assertEquals(1, honestEngine.height());
        assertNotEquals(driftedEngine.tipHash(), honestEngine.tipHash());

        // Real time catches up to where the drifted node's clock already stood: the identical
        // block -- unchanged, not re-mined -- now falls inside the honest node's own future
        // window too.
        honestClock.set(t + 20_000L);
        assertEquals(ExecutionStatus.SUCCESS, honestEngine.addBlock(block),
            "once the honest node's clock reaches where the drifted node's already stood, the same "
                + "block it just refused becomes acceptable -- the bound heals instead of forking "
                + "permanently");
        assertEquals(2, honestEngine.height());
        assertEquals(driftedEngine.tipHash(), honestEngine.tipHash(),
            "the two nodes reconverge on the identical block once the clock skew is no longer a gap");
    }
}
