package rhizome;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.GenesisBlock;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.common.Utils;
import rhizome.core.ledger.Ledger;
import rhizome.core.ledger.LedgerException;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.ledger.SnapshotLoader;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.SHA256Hash;

class GenesisBlockTest {

    private static PublicAddress addr(int seed) {
        byte[] a = new byte[PublicAddress.SIZE];
        for (int i = 1; i < a.length; i++) {
            a[i] = (byte) (seed * 17 + i);
        }
        return PublicAddress.of(a);
    }

    private static LedgerSnapshot snapshot(int chainId) {
        LedgerSnapshot s = new LedgerSnapshot("pandanite", 536000, chainId);
        s.put(addr(1), new TransactionAmount(500_000L));
        s.put(addr(2), new TransactionAmount(42L));
        return s;
    }

    /** Minimal in-memory Ledger for tests. */
    private static final class MapLedger implements Ledger {
        final Map<PublicAddress, TransactionAmount> map = new HashMap<>();

        public boolean hasWallet(PublicAddress wallet) { return map.containsKey(wallet); }
        public void createWallet(PublicAddress wallet) {
            if (map.putIfAbsent(wallet, new TransactionAmount(0)) != null)
                throw new LedgerException("Wallet already exists");
        }
        public TransactionAmount getWalletValue(PublicAddress wallet) { return map.get(wallet); }
        public void withdraw(PublicAddress wallet, TransactionAmount amt) {
            map.merge(wallet, amt, (a, b) -> new TransactionAmount(a.amount() - b.amount()));
        }
        public void revertSend(PublicAddress wallet, TransactionAmount amt) { deposit(wallet, amt); }
        public void deposit(PublicAddress wallet, TransactionAmount amt) {
            map.merge(wallet, amt, (a, b) -> new TransactionAmount(a.amount() + b.amount()));
        }
        public void revertDeposit(PublicAddress wallet, TransactionAmount amt) {
            map.merge(wallet, amt, (a, b) -> new TransactionAmount(a.amount() - b.amount()));
        }
        public void forEachBalance(java.util.function.ObjLongConsumer<PublicAddress> consumer) {
            map.forEach((wallet, amount) -> consumer.accept(wallet, amount.amount()));
        }
    }

    @Test
    void genesisIsDeterministic() {
        NetworkParameters params = NetworkParameters.testnet();
        Block a = GenesisBlock.build(params, snapshot(params.chainId()));
        Block b = GenesisBlock.build(params, snapshot(params.chainId()));
        assertEquals(a.hash(), b.hash());
        assertEquals(GenesisBlock.GENESIS_ID, ((rhizome.core.block.BlockImpl) a).id());
    }

    @Test
    void commitmentIsOrderIndependentButValueSensitive() {
        NetworkParameters params = NetworkParameters.testnet();

        // Same balances inserted in reverse order -> same commitment.
        LedgerSnapshot reversed = new LedgerSnapshot("pandanite", 536000, params.chainId());
        reversed.put(addr(2), new TransactionAmount(42L));
        reversed.put(addr(1), new TransactionAmount(500_000L));
        assertEquals(snapshot(params.chainId()).commitmentHash(), reversed.commitmentHash());

        // A single different balance -> different commitment -> different genesis.
        LedgerSnapshot tampered = snapshot(params.chainId());
        tampered.put(addr(2), new TransactionAmount(43L));
        assertNotEquals(
            GenesisBlock.build(params, snapshot(params.chainId())).hash(),
            GenesisBlock.build(params, tampered).hash());
    }

    @Test
    void chainIdMismatchRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> GenesisBlock.build(NetworkParameters.testnet(), snapshot(999)));
    }

    @Test
    void matchesDetectsTampering() {
        NetworkParameters params = NetworkParameters.testnet();
        Block genesis = GenesisBlock.build(params, snapshot(params.chainId()));
        assertTrue(GenesisBlock.matches(genesis, params, snapshot(params.chainId())));

        LedgerSnapshot tampered = snapshot(params.chainId());
        tampered.put(addr(3), new TransactionAmount(1_000_000L));
        assertFalse(GenesisBlock.matches(genesis, params, tampered));
    }

    @Test
    void initChainSeedsLedgerAndVerifiesCommitment() {
        NetworkParameters params = NetworkParameters.testnet();
        MapLedger ledger = new MapLedger();

        Block genesis = GenesisBlock.initChain(ledger, params, snapshot(params.chainId()), null);

        assertEquals(500_000L, ledger.getWalletValue(addr(1)).amount());
        assertEquals(42L, ledger.getWalletValue(addr(2)).amount());

        // Re-init against the published hash succeeds with the right snapshot...
        MapLedger ledger2 = new MapLedger();
        GenesisBlock.initChain(ledger2, params, snapshot(params.chainId()), genesis.hash());

        // ...and fails with a tampered one.
        LedgerSnapshot tampered = snapshot(params.chainId());
        tampered.put(addr(1), new TransactionAmount(999L));
        assertThrows(IllegalStateException.class,
            () -> GenesisBlock.initChain(new MapLedger(), params, tampered, genesis.hash()));
    }

    @Test
    void initChainRefusesToSeedOverANonEmptyLedger() {
        NetworkParameters params = NetworkParameters.testnet();
        MapLedger ledger = new MapLedger();
        GenesisBlock.initChain(ledger, params, snapshot(params.chainId()), null);
        assertEquals(500_000L, ledger.getWalletValue(addr(1)).amount());

        // A second init over the same ledger is the torn-seed shape: height 0 with durable
        // balances, i.e. the partial flush of a genesis seed that crashed inside its (not
        // crash-atomic) bulk-load window. It must REFUSE -- seed tops existing wallets up, so a
        // re-seed would double-deposit the durable wallets while the header commits the
        // snapshot's own, correct total -- and the refusal must leave the ledger untouched.
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> GenesisBlock.initChain(ledger, params, snapshot(params.chainId()), null));
        assertTrue(ex.getMessage().contains("non-empty ledger"),
            "expected the torn-seed refusal, got: " + ex.getMessage());
        assertEquals(500_000L, ledger.getWalletValue(addr(1)).amount(),
            "a refused re-seed must not top the wallet up a second time");
        assertEquals(42L, ledger.getWalletValue(addr(2)).amount());
        assertEquals(2, ledger.map.size(), "a refused re-seed must not create wallets either");
    }

    @Test
    void genesisCommitsSnapshotTotalSupply() {
        NetworkParameters params = NetworkParameters.testnet();

        // The empty snapshot commits supply 0 -- a legal value, not the SUPPLY_ABSENT sentinel.
        LedgerSnapshot empty = new LedgerSnapshot("pandanite", 536000, params.chainId());
        assertEquals(0L, empty.totalSupply());
        Block emptyGenesis = GenesisBlock.build(params, empty);
        assertEquals(0L, emptyGenesis.supply());
        assertNotEquals(BlockImpl.SUPPLY_ABSENT, emptyGenesis.supply());

        // A funded snapshot commits exactly its total.
        LedgerSnapshot funded = snapshot(params.chainId());
        Block genesis = GenesisBlock.build(params, funded);
        assertEquals(funded.totalSupply(), genesis.supply());

        // A snapshot whose UNSIGNED sum exceeds Long.MAX_VALUE must fail loud at chain init
        // rather than silently wrapping into a bogus S0 (research.md Decision 6, FR-005/FR-013).
        LedgerSnapshot overflow = new LedgerSnapshot("pandanite", 536000, params.chainId());
        overflow.put(addr(1), new TransactionAmount(Long.MAX_VALUE));
        overflow.put(addr(2), new TransactionAmount(2L));
        assertTrue(overflow.totalSupply() < 0, "the unsigned sum wraps the signed field negative");
        assertThrows(IllegalArgumentException.class, () -> GenesisBlock.build(params, overflow));
    }

    /**
     * FAMILY GENESIS-01 — a snapshot whose total differs from the pinned genesis supply by one
     * base unit, in either direction, refuses boot with a message naming both totals, and never
     * reaches seeding (FR-003, FR-004).
     */
    @Test
    void aSnapshotWhoseTotalDiffersFromThePinnedGenesisSupplyRefusesBoot() {
        long s0 = 1_000_000L;
        NetworkParameters pinned = NetworkParameters.testnet().toBuilder().genesisSupply(s0).build();

        LedgerSnapshot over = new LedgerSnapshot("test", 0, pinned.chainId());
        over.put(addr(1), new TransactionAmount(s0 + 1));

        LedgerSnapshot under = new LedgerSnapshot("test", 0, pinned.chainId());
        under.put(addr(1), new TransactionAmount(s0 - 1));

        IllegalArgumentException overEx = assertThrows(IllegalArgumentException.class,
            () -> GenesisBlock.build(pinned, over));
        assertTrue(overEx.getMessage().contains(Long.toUnsignedString(s0 + 1)),
            "expected the actual (over) total in the message: " + overEx.getMessage());
        assertTrue(overEx.getMessage().contains(Long.toUnsignedString(s0)),
            "expected the pinned S0 in the message: " + overEx.getMessage());

        IllegalArgumentException underEx = assertThrows(IllegalArgumentException.class,
            () -> GenesisBlock.build(pinned, under));
        assertTrue(underEx.getMessage().contains(Long.toUnsignedString(s0 - 1)),
            "expected the actual (under) total in the message: " + underEx.getMessage());
        assertTrue(underEx.getMessage().contains(Long.toUnsignedString(s0)),
            "expected the pinned S0 in the message: " + underEx.getMessage());

        // Nothing is seeded: the pin check fires inside build(), which initChain calls BEFORE
        // GenesisLedger.seed -- a mismatched snapshot never touches the ledger.
        MapLedger ledger = new MapLedger();
        assertThrows(IllegalArgumentException.class,
            () -> GenesisBlock.initChain(ledger, pinned, over, null));
        assertTrue(ledger.map.isEmpty(), "a refused genesis must not seed any wallet");
    }

    /**
     * FAMILY GENESIS-02 — the pin checks the TOTAL, the genesis commitment binds the
     * DISTRIBUTION: a snapshot that keeps the pinned total but reshuffles it among addresses
     * passes the new pin check yet still fails the existing commitment re-verification. Also
     * locks the check ORDER: the chain-id and signed-range guards fire before the pin check,
     * even against a snapshot that would fail the pin too (FR-009, FR-010).
     */
    @Test
    void thePinChecksTheTotalAndTheCommitmentBindsTheDistribution() {
        long s0 = 1_000_000L;
        NetworkParameters pinned = NetworkParameters.testnet().toBuilder().genesisSupply(s0).build();

        LedgerSnapshot original = new LedgerSnapshot("test", 0, pinned.chainId());
        original.put(addr(1), new TransactionAmount(600_000L));
        original.put(addr(2), new TransactionAmount(400_000L));
        assertEquals(s0, original.totalSupply());
        Block genesis = GenesisBlock.build(pinned, original);

        // Same total, different distribution: passes the pin...
        LedgerSnapshot reshuffled = new LedgerSnapshot("test", 0, pinned.chainId());
        reshuffled.put(addr(1), new TransactionAmount(1_000L));
        reshuffled.put(addr(2), new TransactionAmount(999_000L));
        assertEquals(s0, reshuffled.totalSupply());
        Block reshuffledGenesis = GenesisBlock.build(pinned, reshuffled);
        assertNotEquals(genesis.hash(), reshuffledGenesis.hash(),
            "a different distribution must yield a different commitment");

        // ...but fails initChain's re-verification against the ORIGINAL genesis hash: the pin
        // guards the total, the commitment guards the allocation -- the two checks compose.
        assertThrows(IllegalStateException.class,
            () -> GenesisBlock.initChain(new MapLedger(), pinned, reshuffled, genesis.hash()));

        // Check order: the chain-id guard fires first, even against a snapshot that would ALSO
        // fail the new pin check (wrong chain id, mismatched total).
        LedgerSnapshot wrongChainAndTotal = new LedgerSnapshot("test", 0, pinned.chainId() + 1);
        wrongChainAndTotal.put(addr(1), new TransactionAmount(s0 + 1));
        IllegalArgumentException chainIdEx = assertThrows(IllegalArgumentException.class,
            () -> GenesisBlock.build(pinned, wrongChainAndTotal));
        assertTrue(chainIdEx.getMessage().contains("chainId"),
            "expected the chain-id guard's message, got: " + chainIdEx.getMessage());
        assertFalse(chainIdEx.getMessage().toLowerCase(java.util.Locale.ROOT).contains("pinned"),
            "the chain-id guard must fire, not the pin check: " + chainIdEx.getMessage());

        // Check order: the signed-range guard fires next, even against a snapshot whose wrapped
        // (negative) total would ALSO fail the pin check.
        LedgerSnapshot overflowAndMismatched = new LedgerSnapshot("test", 0, pinned.chainId());
        overflowAndMismatched.put(addr(1), new TransactionAmount(Long.MAX_VALUE));
        overflowAndMismatched.put(addr(2), new TransactionAmount(2L));
        assertTrue(overflowAndMismatched.totalSupply() < 0);
        IllegalArgumentException rangeEx = assertThrows(IllegalArgumentException.class,
            () -> GenesisBlock.build(pinned, overflowAndMismatched));
        assertTrue(rangeEx.getMessage().contains("Long.MAX_VALUE"),
            "expected the signed-range guard's message, got: " + rangeEx.getMessage());
        assertFalse(rangeEx.getMessage().toLowerCase(java.util.Locale.ROOT).contains("pinned"),
            "the range guard must fire, not the pin check: " + rangeEx.getMessage());
    }

    /**
     * FAMILY GENESIS — US2/SC-002/SC-003: an auditor with nothing but the published mainnet
     * allocation artifact, the pinned S0, and the documented genesis-commitment formula
     * (FR-008: {@code SHA-256(chainId || snapshotCommitment)}, unchanged by this feature)
     * independently recomputes the exact hash the shipped genesis block carries. The
     * recomputation below never calls a {@code GenesisBlock}-internal method to produce its
     * expected value -- it reimplements the published formula with its own {@link SHA256Digest}
     * instance over {@link LedgerSnapshot#commitmentHash()} and {@link Utils#intToBytes(int)},
     * so a drift in {@code GenesisBlock}'s own formula (wrong byte order, a dropped chainId, a
     * stale snapshot) would be caught here even though two calls to
     * {@code GenesisBlock.build} on the same snapshot would still (uselessly) agree with each
     * other.
     */
    @Test
    void thePublishedAllocationRecomputesToTheGenesisIdentity() throws IOException {
        NetworkParameters mainnet = NetworkParameters.cleanMainnet();

        // Step 1 (parse): the auditor's only input besides the pin is the published artifact.
        LedgerSnapshot artifact = SnapshotLoader.fromResource("genesis/rhizome-mainnet.json");

        // Step 2 (sum): the allocation sums to exactly the pinned S0, and S0 is non-zero
        // (SC-002 scenario 1; FR-002 -- the whole reason this feature exists).
        assertEquals(mainnet.genesisSupply(), artifact.totalSupply());
        assertTrue(artifact.totalSupply() > 0, "mainnet's pinned S0 must be non-zero (FR-002)");

        // Step 3 (recompute commitment): reproduce the documented genesis-commitment formula
        // (FR-008) by hand, from public building blocks only -- no GenesisBlock method is
        // called to derive this expected value.
        SHA256Hash recomputedSnapshotCommitment = artifact.commitmentHash();
        SHA256Digest digest = new SHA256Digest();
        byte[] chainIdBytes = Utils.intToBytes(mainnet.chainId());
        digest.update(chainIdBytes, 0, chainIdBytes.length);
        byte[] commitmentBytes = recomputedSnapshotCommitment.toBytes();
        digest.update(commitmentBytes, 0, commitmentBytes.length);
        byte[] out = new byte[SHA256Hash.SIZE];
        digest.doFinal(out, 0);
        SHA256Hash recomputedGenesisCommitment = SHA256Hash.of(out);

        // Step 4 (build genesis): through the real, checked public path.
        Block genesis = GenesisBlock.build(mainnet, artifact);

        // Step 5 (match): the genesis block's committed snapshot hash -- its merkleRoot, per
        // GenesisBlock's javadoc ("its merkleRoot carries the snapshot's commitment hash") --
        // equals the independently recomputed commitment, and its committed supply equals the
        // pinned S0 (SC-002 scenario 2, SC-003).
        assertEquals(recomputedGenesisCommitment, genesis.merkleRoot());
        assertEquals(mainnet.genesisSupply(), genesis.supply());

        // Not vacuous: the recomputed commitment is sensitive to the actual artifact content,
        // not merely self-consistent with whatever GenesisBlock.build happens to do. A
        // differently-distributed (but still S0-summing) snapshot yields a DIFFERENT
        // recomputed commitment, so this is a real content-binding check, not a tautology.
        LedgerSnapshot reshuffled = new LedgerSnapshot("test", 0, mainnet.chainId());
        reshuffled.put(addr(1), new TransactionAmount(mainnet.genesisSupply()));
        assertEquals(mainnet.genesisSupply(), reshuffled.totalSupply());
        assertNotEquals(recomputedSnapshotCommitment, reshuffled.commitmentHash());
    }

    /**
     * US3 preservation/uniformity proof for the pinned-total gate (FR-007) — not independently
     * catalogued as its own GENESIS scenario; GENESIS-03 is the artifact/pin lockstep proof
     * ({@code LedgerSnapshotTest#theShippedAllocationMatchesThePinnedGenesisSupplyExactly}).
     * Preservation: an unpinned profile (testnet, as it ships) accepts any snapshot total and
     * builds genesis exactly as it would have before this feature existed -- the gate never
     * fires when {@code genesisSupply() == GENESIS_SUPPLY_UNPINNED}. Uniformity: a
     * testnet-DERIVED profile that opts into a pin behaves exactly like mainnet's pinned check
     * for a mismatched total -- proving the check is gated purely on the presence of a pin,
     * never on which network/profile it is (no network-id special-casing in consensus code).
     */
    @Test
    void anUnpinnedProfileAcceptsAnySnapshotTotalAsBefore() {
        NetworkParameters unpinned = NetworkParameters.testnet();
        assertEquals(NetworkParameters.GENESIS_SUPPLY_UNPINNED, unpinned.genesisSupply());

        // Preservation proof: an arbitrary total -- deliberately not a pinned constant used
        // anywhere in this file or on cleanMainnet() -- builds without incident under the
        // unpinned testnet profile, exactly as it would have before this feature existed.
        long arbitraryTotal = 777_777L;
        assertNotEquals(1_000_000L, arbitraryTotal, "must not collide with this file's pinned S0 fixture");
        assertNotEquals(NetworkParameters.cleanMainnet().genesisSupply(), arbitraryTotal,
            "must not collide with mainnet's pinned S0 -- the point is that no pin fires here");
        LedgerSnapshot funded = new LedgerSnapshot("test", 0, unpinned.chainId());
        funded.put(addr(1), new TransactionAmount(arbitraryTotal));
        assertEquals(arbitraryTotal, funded.totalSupply());
        Block genesis = assertDoesNotThrow(() -> GenesisBlock.build(unpinned, funded),
            "an unpinned profile must accept any snapshot total, as before this feature");
        assertEquals(arbitraryTotal, genesis.supply());

        // Uniformity proof: a testnet-DERIVED profile that pins a value to something OTHER than
        // the funded snapshot's total is refused -- the same pinned-total check mainnet uses,
        // fired here purely because THIS profile set a pin, not because of its network identity.
        long pinnedToADifferentTotal = 1_234_567L;
        assertNotEquals(arbitraryTotal, pinnedToADifferentTotal);
        NetworkParameters borrowedPinned =
            unpinned.toBuilder().genesisSupply(pinnedToADifferentTotal).build();
        assertThrows(IllegalArgumentException.class,
            () -> GenesisBlock.build(borrowedPinned, funded));
    }
}
