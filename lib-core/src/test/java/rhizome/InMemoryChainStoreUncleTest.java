package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockImpl;
import rhizome.core.block.UncleRef;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.ledger.PublicAddress;
import rhizome.crypto.SHA256Hash;

/**
 * PERS-10, in-memory side: popping a block drops the bodies of the uncles it referenced, exactly as
 * the RocksDB store does in the pop batch. The in-memory store is what every engine-level test runs
 * on, so a divergence here would hide a reorg bug the RocksDB proof cannot reach.
 */
class InMemoryChainStoreUncleTest {

    @Test
    void popDropsTheBodiesOfTheUnclesTheBlockReferenced() {
        var store = new InMemoryChainStore();
        var uncle = (BlockImpl) BlockImpl.builder().id(1).timestamp(1).difficulty(1)
            .lastBlockHash(SHA256Hash.empty()).build();
        var kept = (BlockImpl) BlockImpl.builder().id(1).timestamp(2).difficulty(1)
            .lastBlockHash(SHA256Hash.empty()).build();
        var block = (BlockImpl) BlockImpl.builder().id(1).timestamp(3).difficulty(1)
            .lastBlockHash(SHA256Hash.empty()).build();
        block.uncles(List.of(new UncleRef(uncle.hash(), 1, PublicAddress.random())));

        store.append(block, java.util.Map.of(uncle.hash(), uncle));
        store.putUncle(kept.hash(), kept); // referenced by no block: must survive the pop
        assertEquals(uncle.hash(), store.uncleAt(uncle.hash()).hash(), "persisted with its block");

        store.pop();

        assertNull(store.uncleAt(uncle.hash()), "the popped block's uncle body goes with it");
        assertEquals(kept.hash(), store.uncleAt(kept.hash()).hash(), "unreferenced bodies are untouched");
    }
}
