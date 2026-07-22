package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
import rhizome.core.block.HeaderCodec;
import rhizome.core.block.UncleRef;
import rhizome.core.blockchain.Miner;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

/**
 * {@link BlockHeader} is the canonical hash carrier: it must reproduce the exact
 * block preimage (so {@code BlockImpl.hash()} can delegate to it) across every
 * combination of the optional fields (stateRoot, vote, uncles), verify the PoW
 * nonce without the body, and round-trip losslessly through {@link HeaderCodec}.
 */
class BlockHeaderTest {

    private static BlockImpl block(SHA256Hash stateRoot, int vote, List<UncleRef> uncles) {
        var b = (BlockImpl) BlockImpl.builder()
            .id(42)
            .timestamp(1_700_000_000_000L)
            .difficulty(4)
            .lastBlockHash(SHA256Hash.random())
            .merkleRoot(SHA256Hash.random())
            .nonce(SHA256Hash.random())
            .stateRoot(stateRoot)
            .vote(vote)
            .uncles(new java.util.ArrayList<>(uncles))
            .build();
        b.addTransaction(Transaction.of(PublicAddress.random(), new TransactionAmount(50L)));
        return b;
    }

    private static UncleRef uncle() {
        return new UncleRef(SHA256Hash.random(), 5, PublicAddress.random());
    }

    private static List<BlockImpl> allShapes() {
        return List.of(
            block(SHA256Hash.empty(), 0, List.of()),                              // plain
            block(SHA256Hash.random(), 0, List.of()),                             // + stateRoot
            block(SHA256Hash.empty(), 1, List.of()),                              // + vote
            block(SHA256Hash.empty(), -2, List.of()),                             // + negative vote
            block(SHA256Hash.empty(), 0, List.of(uncle())),                       // + one uncle
            block(SHA256Hash.empty(), 0, List.of(uncle(), uncle())),              // + two uncles
            block(SHA256Hash.random(), 2, List.of(uncle(), uncle())));            // + everything
    }

    @Test
    void headerHashEqualsBlockHashForEveryShape() {
        for (BlockImpl b : allShapes()) {
            assertEquals(b.hash(), BlockHeader.of(b).hash(),
                "header hash must equal block hash");
        }
    }

    @Test
    void optionalFieldsEachChangeTheHash() {
        SHA256Hash plain = block(SHA256Hash.empty(), 0, List.of()).hash();
        // Fix all mandatory fields; the shapes above randomise them, so compare via a
        // controlled trio built on identical mandatory fields instead.
        var lbh = SHA256Hash.random();
        var mr = SHA256Hash.random();
        var nc = SHA256Hash.random();
        BlockImpl base = fixed(lbh, mr, nc, SHA256Hash.empty(), 0, List.of());
        BlockImpl withState = fixed(lbh, mr, nc, SHA256Hash.random(), 0, List.of());
        BlockImpl withVote = fixed(lbh, mr, nc, SHA256Hash.empty(), 3, List.of());
        BlockImpl withUncle = fixed(lbh, mr, nc, SHA256Hash.empty(), 0, List.of(uncle()));
        assertNotEquals(base.hash(), withState.hash());
        assertNotEquals(base.hash(), withVote.hash());
        assertNotEquals(base.hash(), withUncle.hash());
        assertNotEquals(plain, null);
    }

    private static BlockImpl fixed(SHA256Hash lbh, SHA256Hash mr, SHA256Hash nc,
            SHA256Hash stateRoot, int vote, List<UncleRef> uncles) {
        var b = (BlockImpl) BlockImpl.builder()
            .id(7).timestamp(123456789L).difficulty(4)
            .lastBlockHash(lbh).merkleRoot(mr).nonce(nc)
            .stateRoot(stateRoot).vote(vote)
            .uncles(new java.util.ArrayList<>(uncles)).build();
        b.addTransaction(Transaction.of(PublicAddress.random(), new TransactionAmount(1L)));
        return b;
    }

    @Test
    void codecRoundTripPreservesFieldsAndHash() {
        for (BlockImpl b : allShapes()) {
            BlockHeader h = BlockHeader.of(b);
            BlockHeader decoded = HeaderCodec.decode(HeaderCodec.encode(h));
            assertEquals(h, decoded, "header must round-trip through the codec");
            assertEquals(h.hash(), decoded.hash());
            assertEquals(b.hash(), decoded.hash());
        }
    }

    @Test
    void decodeAllReadsSelfFramedConcatenation() {
        List<BlockImpl> blocks = allShapes();
        var buf = new java.io.ByteArrayOutputStream();
        for (BlockImpl b : blocks) {
            byte[] enc = HeaderCodec.encode(BlockHeader.of(b));
            buf.write(enc, 0, enc.length);
        }
        List<BlockHeader> decoded = HeaderCodec.decodeAll(buf.toByteArray());
        assertEquals(blocks.size(), decoded.size());
        for (int i = 0; i < blocks.size(); i++) {
            assertEquals(blocks.get(i).hash(), decoded.get(i).hash());
        }
    }

    @Test
    void verifyNonceMatchesBlockAndValidatesRealProofOfWork() {
        // A genuinely mined nonce (low difficulty, SHA-256) must verify through the header
        // exactly as through the block — the header carries the whole preimage.
        var b = (BlockImpl) BlockImpl.builder()
            .id(1).timestamp(1_000_000L).difficulty(4)
            .lastBlockHash(SHA256Hash.random()).merkleRoot(SHA256Hash.random())
            .build();
        b.addTransaction(Transaction.of(PublicAddress.random(), new TransactionAmount(50L)));
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), PowAlgorithm.SHA256));

        assertTrue(b.verifyNonce(PowAlgorithm.SHA256));
        assertEquals(b.verifyNonce(PowAlgorithm.SHA256),
            BlockHeader.of(b).verifyNonce(PowAlgorithm.SHA256));

        // A header with a bogus nonce is rejected by both paths.
        var bogus = (BlockImpl) BlockImpl.builder()
            .id(1).timestamp(1_000_000L).difficulty(20)
            .lastBlockHash(SHA256Hash.random()).merkleRoot(SHA256Hash.random())
            .nonce(SHA256Hash.empty()).build();
        assertEquals(bogus.verifyNonce(PowAlgorithm.SHA256),
            BlockHeader.of(bogus).verifyNonce(PowAlgorithm.SHA256));
    }
}
