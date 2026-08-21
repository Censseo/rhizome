package rhizome.core.blockchain;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.GenesisLedger;
import rhizome.core.ledger.Ledger;
import rhizome.core.ledger.LedgerSnapshot;

/**
 * The genesis block of a clean Rhizome chain.
 *
 * <p>Genesis is not mined: it is fully determined by {@link NetworkParameters}
 * and the seeded {@link LedgerSnapshot}. Its {@code merkleRoot} carries the
 * snapshot's {@linkplain LedgerSnapshot#commitmentHash() commitment hash}, so
 * the chain identity is cryptographically bound to the exact balances it was
 * seeded with — a node bootstrapping from a snapshot file can verify it matches
 * the chain's genesis before applying it. Consensus rule: proof-of-work
 * verification starts at block 2; block 1 must byte-equal this construction.
 */
public final class GenesisBlock {

    public static final int GENESIS_ID = 1;

    private GenesisBlock() {}

    /**
     * Genesis commitment: SHA-256 over {@code chainId || snapshotCommitment}. Binding
     * the chain-id means two networks that happen to share a (possibly empty)
     * balance snapshot still have distinct, incompatible genesis blocks.
     */
    private static SHA256Hash genesisCommitment(NetworkParameters params, LedgerSnapshot snapshot) {
        var digest = new org.bouncycastle.crypto.digests.SHA256Digest();
        byte[] chainId = rhizome.core.common.Utils.intToBytes(params.chainId());
        digest.update(chainId, 0, chainId.length);
        byte[] commitment = snapshot.commitmentHash().toBytes();
        digest.update(commitment, 0, commitment.length);
        byte[] out = new byte[SHA256Hash.SIZE];
        digest.doFinal(out, 0);
        return SHA256Hash.of(out);
    }

    /**
     * Builds the deterministic genesis block for the given network and snapshot.
     *
     * <p>Commits {@code supply = snapshot.totalSupply()} (§ supply header commitment, FR-005) --
     * the base case {@code S0} that every later block's {@code Issuance.minted} accounting builds
     * on. Fails loudly rather than silently clamping when the snapshot's unsigned total overflows
     * past {@code Long.MAX_VALUE} (see the bounds check below).
     */
    public static Block build(NetworkParameters params, LedgerSnapshot snapshot) {
        if (snapshot.chainId() != params.chainId()) {
            throw new IllegalArgumentException(
                "Snapshot chainId " + snapshot.chainId() + " does not match network " + params.chainId());
        }
        // Genesis commits S0 = LedgerSnapshot.totalSupply() (§ supply header commitment, FR-005):
        // the sum of seeded balances, 0 for the default empty snapshot. totalSupply() adds with
        // plain `+=` and is documented as an UNSIGNED 64-bit total (mirrored by its
        // Long.toUnsignedString JSON form); the consensus supply range is the non-negative SIGNED
        // 64-bit integers (data-model.md), so an unsigned sum landing past Long.MAX_VALUE wraps
        // negative here and must fail loud at chain init rather than silently commit a bogus S0
        // (research.md Decision 6, FR-013).
        long supply = snapshot.totalSupply();
        if (supply < 0) {
            throw new IllegalArgumentException(
                "Snapshot total supply " + Long.toUnsignedString(supply)
                    + " exceeds Long.MAX_VALUE -- outside the signed 64-bit consensus range");
        }
        return BlockImpl.builder()
            .id(GENESIS_ID)
            .timestamp(params.genesisTimestamp())
            .difficulty(params.genesisDifficulty())
            .merkleRoot(genesisCommitment(params, snapshot))
            .lastBlockHash(SHA256Hash.empty())
            .nonce(SHA256Hash.empty())
            .supply(supply)
            .build();
    }

    /**
     * Checks that {@code candidate} is exactly the genesis block that
     * {@code params} + {@code snapshot} define (header hash equality).
     */
    public static boolean matches(Block candidate, NetworkParameters params, LedgerSnapshot snapshot) {
        return candidate.hash().equals(build(params, snapshot).hash());
    }

    /**
     * Initialises a fresh chain state: verifies the snapshot against the
     * expected genesis commitment, seeds the ledger, and returns the genesis
     * block. {@code expectedGenesisHash} may be null for a brand-new network
     * (the caller then publishes the resulting hash as the network's genesis).
     */
    public static Block initChain(Ledger ledger, NetworkParameters params, LedgerSnapshot snapshot,
                                  SHA256Hash expectedGenesisHash) {
        Block genesis = build(params, snapshot);
        if (expectedGenesisHash != null && !genesis.hash().equals(expectedGenesisHash)) {
            throw new IllegalStateException(
                "Snapshot does not match the network's genesis commitment: expected "
                    + expectedGenesisHash.toHexString() + " but snapshot yields "
                    + genesis.hash().toHexString());
        }
        GenesisLedger.seed(ledger, snapshot);
        return genesis;
    }
}
