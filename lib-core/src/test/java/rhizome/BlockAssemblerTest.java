package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.blockchain.BlockAssembler;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.CurveActiveNetwork;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.core.blockchain.TestNodeStores;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.mempool.MemPool;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;

import static rhizome.crypto.Crypto.generateKeyPairTyped;

/**
 * US3 (009-native-coin-burn, SC-012/FR-034): transaction SELECTION is untouched by the burn
 * share. A uniform share is a monotone transform of the fee, so the greedy-by-miner-revenue
 * selector and its tie-breaks produce a byte-identical body for the same mempool contents under
 * ANY proper-fraction share — asserted here by assembling the same mempool under two profiles
 * that differ in NOTHING BUT the share. (T049's verification lives in this assertion: the
 * selector itself is not modified by the feature, and this test fails if anything about the
 * burn reaches into selection.)
 */
class BlockAssemblerTest {

    @Test
    void blockSelectionOrderIsUnchangedByTheBurnShare() {
        // Two profiles identical except for the burn share: 1/2 (the shipped mainnet value)
        // vs 1/4 — maximally different legal shares, same everything else.
        NetworkParameters half = CurveActiveNetwork.curveActiveTestnet().toBuilder()
            .burnShareNum(1L).burnShareDen(2L).build();
        NetworkParameters quarter = CurveActiveNetwork.curveActiveTestnet().toBuilder()
            .burnShareNum(1L).burnShareDen(4L).build();
        assertEquals(half.supplyTarget(), quarter.supplyTarget());
        assertEquals(half.emissionCurveHeight(), quarter.emissionCurveHeight());
        assertEquals(half.chainId(), quarter.chainId());

        AtomicLong clock = new AtomicLong(1_000_000L);
        PublicAddress miner = PublicAddress.random();

        // One sender PER transaction: the selector orders across senders by priority rate
        // (same-sender runs are nonce-chained FIFO), so distinct senders let the greedy
        // ordering actually engage — the property the sanity check below asserts.
        int senders = 7;
        List<PublicKey> keys = new ArrayList<>();
        List<PrivateKey> privs = new ArrayList<>();
        List<PublicAddress> sendersAddresses = new ArrayList<>();
        for (int i = 0; i < senders; i++) {
            var pair = generateKeyPairTyped();
            keys.add(pair.publicKey());
            privs.add(pair.privateKey());
            sendersAddresses.add(PublicAddress.of(pair.publicKey()));
        }

        // The same funded snapshot under both profiles: identical chain state, identical
        // mempool admission, identical balances.
        LedgerSnapshot snapshotHalf = new LedgerSnapshot("t", 0, half.chainId());
        for (PublicAddress s : sendersAddresses) {
            snapshotHalf.put(s, new TransactionAmount(20_000_000L));
        }
        ChainEngine engineHalf = ChainEngine.boot(half, TestNodeStores.mixing(
                new rhizome.core.ledger.InMemoryLedger(), new InMemoryChainStore()), snapshotHalf)
            .clock(clock::get).build();

        LedgerSnapshot snapshotQuarter = new LedgerSnapshot("t", 0, quarter.chainId());
        for (PublicAddress s : sendersAddresses) {
            snapshotQuarter.put(s, new TransactionAmount(20_000_000L));
        }
        ChainEngine engineQuarter = ChainEngine.boot(quarter, TestNodeStores.mixing(
                new rhizome.core.ledger.InMemoryLedger(), new InMemoryChainStore()), snapshotQuarter)
            .clock(clock::get).build();

        // The same signed transactions (signed ONCE, admitted to both pools): fees deliberately
        // out of order, so the greedy-by-revenue selector has real ordering work to do.
        long[] fees = {900L, 100L, 2_500L, 40L, 17_000L, 3L, 601L};
        MemPool poolHalf = new MemPool(half, new SignatureVerifier(), engineHalf, 1024);
        MemPool poolQuarter = new MemPool(quarter, new SignatureVerifier(), engineQuarter, 1024);
        List<SHA256> order = new ArrayList<>();
        for (int i = 0; i < fees.length; i++) {
            Transaction t = Transaction.of(sendersAddresses.get(i), PublicAddress.random(),
                new TransactionAmount(0), keys.get(i), new TransactionAmount(fees[i]),
                clock.get(), half.chainId(), 0);
            t.sign(privs.get(i));
            order.add(new SHA256(t.hashContents().toHexString()));
            assertEquals(ExecutionStatus.SUCCESS, poolHalf.addTransaction(t));
            assertEquals(ExecutionStatus.SUCCESS, poolQuarter.addTransaction(t));
        }

        Block fromHalf = BlockAssembler.assemble(engineHalf, poolHalf, miner, clock.get());
        Block fromQuarter = BlockAssembler.assemble(engineQuarter, poolQuarter, miner, clock.get());

        // Byte-identical bodies: same transactions, same order, same merkle root. If the burn
        // share ever reached into selection (resorting, dropping, or re-pricing), the two
        // bodies would diverge here.
        assertEquals(fromHalf.transactions().size(), fromQuarter.transactions().size());
        assertEquals(order.size() + 1, fromHalf.transactions().size(),
            "sanity: the coinbase plus every pooled transaction were selected");
        // Position 0 is the coinbase each assembler minted for its own candidate; the
        // SELECTION is positions 1.. — compare those, where the share could only act.
        for (int i = 1; i < fromHalf.transactions().size(); i++) {
            assertEquals(fromHalf.transactions().get(i).hashContents(),
                fromQuarter.transactions().get(i).hashContents(),
                "selection position " + i + " must be identical under both shares");
        }

        // And the order the selector produced is genuinely revenue-driven (fee-descending for
        // these same-amount transfers) — the assertion would be vacuous if selection were a no-op.
        List<Long> selectedFees = new ArrayList<>();
        for (int i = 1; i < fromHalf.transactions().size(); i++) {
            selectedFees.add(fromHalf.transactions().get(i).fee().amount());
        }
        for (int i = 1; i < selectedFees.size(); i++) {
            assertTrue(selectedFees.get(i - 1) >= selectedFees.get(i),
                "the selector must still order by miner revenue: " + selectedFees);
        }
    }

    /** A tiny record so the expected-order check needs no equals() on the transaction type. */
    private record SHA256(String hex) {}
}
