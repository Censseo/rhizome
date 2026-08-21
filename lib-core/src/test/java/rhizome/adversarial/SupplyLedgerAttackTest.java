package rhizome.adversarial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockCodec;
import rhizome.core.block.BlockImpl;
import rhizome.core.block.HeaderCodec;
import rhizome.core.block.HeaderWire;
import rhizome.core.block.dto.BlockDto;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.InMemoryChainStore;
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
import rhizome.crypto.SHA256Hash;

/**
 * Attacks on the supply header commitment's arithmetic and wire boundaries (SUPPLY family — see
 * docs/adversarial/spec.md). Most of the family's scenarios already have proofs elsewhere
 * ({@code SupplyCommitmentTest}, {@code GenesisBlockTest}, {@code CodecBoundsTest},
 * {@code HeaderChainTest}, {@code HeaderSynchronizerTest}); this suite covers the two that do not:
 * an attacker trying to make the accounting identity's own {@code long} arithmetic lie by
 * overflowing it (SUPPLY-04) rather than by forging an unrelated value, and an attacker trying to
 * make a pre-feature wire shape survive the current decoder through truncation rather than through
 * an honest, prefix-closed commitment (SUPPLY-07).
 */
class SupplyLedgerAttackTest {

    /**
     * A coinbase-only block, hand-mined, with an explicit committed supply — mirrors
     * {@code SupplyCommitmentTest}'s own fixture idiom (rather than {@code AdversarialChain}) so
     * this suite builds and forges its chain exactly the way this feature's own tests already do.
     */
    private static Block mineOnto(NetworkParameters params, long height, SHA256Hash parentHash,
            int difficulty, long timestamp, PublicAddress miner, long supply) {
        var b = (BlockImpl) BlockImpl.builder()
            .id((int) height)
            .timestamp(timestamp)
            .difficulty(difficulty)
            .lastBlockHash(parentHash)
            .uncles(new ArrayList<>())
            .supply(supply)
            .build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    /**
     * SUPPLY-04 — Push the accounting identity's sum past the signed 64-bit range so it wraps
     * instead of failing, escaping the exact-match check through integer overflow.
     */
    @Test
    void anOverflowingSupplySumIsRejectedRatherThanWrappedIntoAFalseMatch() {
        NetworkParameters params = NetworkParameters.testnet();
        AtomicLong clock = new AtomicLong(0L);
        InMemoryLedger ledger = new InMemoryLedger();
        InMemoryChainStore store = new InMemoryChainStore();
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());

        // A single seeded balance already at the signed 64-bit ceiling: genesis commits it
        // verbatim (FR-005, no scaling — GenesisBlockTest#genesisCommitsSnapshotTotalSupply proves
        // the same snapshot.totalSupply() passthrough), so the very first non-genesis block starts
        // with parent.supply already at Long.MAX_VALUE.
        PublicAddress whale = PublicAddress.random();
        snapshot.put(whale, new TransactionAmount(Long.MAX_VALUE));

        ChainEngine engine = ChainEngine.boot(params, TestNodeStores.mixing(ledger, store), snapshot)
            .clock(clock::get)
            .build();
        assertEquals(Long.MAX_VALUE, engine.headerAt(1).supply(),
            "genesis commits the whale-funded snapshot's total supply verbatim");

        PublicAddress miner = PublicAddress.random();
        long height = engine.height() + 1;
        long reward = params.miningReward(height);
        assertTrue(reward > 0,
            "the reward must be strictly positive for parent.supply + minted(...) to overflow");

        long ts = clock.addAndGet(params.desiredBlockTimeSec() * 1000L);
        // The forged block's own claimed supply is irrelevant to which gate fires here: ANY
        // non-negative claim reaches the Math.addExact(parentSupply, minted) call, and that call
        // overflows regardless of the claim, because parentSupply alone is already Long.MAX_VALUE.
        // Long.MAX_VALUE is as plausible a decoy as any -- what a forger computing the identity
        // with plain, unchecked `+` and reporting the (already-overflowed) result might claim.
        Block overflowing = mineOnto(params, height, engine.tipHash(), engine.difficulty(), ts,
            miner, Long.MAX_VALUE);

        long heightBefore = engine.height();
        assertEquals(ExecutionStatus.INVALID_SUPPLY, engine.addBlock(overflowing),
            "parent.supply + Issuance.minted(...) overflows signed 64-bit arithmetic; Math.addExact "
                + "must throw and be converted into a rejection rather than silently wrapping into a "
                + "value that could coincidentally satisfy the exact-match check");
        assertEquals(heightBefore, engine.height(), "the overflow forgery must not extend the chain");
    }

    /**
     * SUPPLY-07 — Feed the current decoder a pre-feature-shaped header blob, eight bytes short of
     * the field it now expects, hoping the truncated legacy shape is silently misread as a valid
     * supply-less header instead of blocking boot.
     */
    @Test
    void aHeaderTruncatedRightBeforeTheSupplyFieldIsRejectedOnEveryDecoder() {
        // The pre-feature wire shape: id, timestamp, difficulty, numTransactions, four 32-byte
        // hashes and vote -- HeaderWire.PREFIX_BYTES minus the 8-byte supply field this feature
        // appended, and nothing after it (not even uncleCount). Mirrors CodecBoundsTest's own
        // hand-built header(...)/headerWithSupply(...) byte layout.
        ByteBuffer buffer = ByteBuffer.allocate(HeaderWire.PREFIX_BYTES - Long.BYTES);
        buffer.putInt(1);            // id
        buffer.putLong(0L);          // timestamp
        buffer.putInt(0);            // difficulty
        buffer.putInt(0);            // numTransactions
        buffer.put(new byte[32]);    // lastBlockHash
        buffer.put(new byte[32]);    // merkleRoot
        buffer.put(new byte[32]);    // nonce
        buffer.put(new byte[32]);    // stateRoot
        buffer.putInt(0);            // vote
        byte[] legacyShaped = buffer.array();
        assertEquals(HeaderWire.PREFIX_BYTES - Long.BYTES, legacyShaped.length,
            "exactly the pre-feature 152-byte shape: everything through `vote`, no supply field");

        // Every binary ingress path shares HeaderWire.readPrefix, so all three must refuse this
        // shape identically -- silently defaulting the missing field to -1 (absent) on any one of
        // them would let an old data directory or an old peer's wire bytes boot as a legitimate
        // supply-less chain instead of failing loudly.
        assertThrows(BufferUnderflowException.class, () -> HeaderCodec.decode(legacyShaped),
            "the /headers ingress path must not silently read the missing supply field as absent");
        assertThrows(BufferUnderflowException.class, () -> BlockCodec.decode(legacyShaped),
            "the /submit and storage path must refuse the same truncated shape");
        assertThrows(BufferUnderflowException.class,
            () -> BlockDto.readFrom(ByteBuffer.wrap(legacyShaped)),
            "the RocksDB storage decoder must refuse it too, not boot on a phantom supply-less header");
    }
}
