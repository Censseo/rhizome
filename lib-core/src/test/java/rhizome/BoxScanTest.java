package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import rhizome.core.box.Box;
import rhizome.core.box.BoxProcessor;
import rhizome.core.box.BoxRegister;
import rhizome.core.box.BoxStore;
import rhizome.core.box.DefaultBoxProcessor;
import rhizome.core.box.InMemoryBoxStore;
import rhizome.core.box.ScanPredicate;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.PublicAddress;

class BoxScanTest {

    private final NetworkParameters params = NetworkParameters.testnet();
    private final PublicAddress alice = PublicAddress.random();
    private final PublicAddress bob = PublicAddress.random();

    private Box box(PublicAddress owner, long nonce, BoxRegister... regs) {
        return new Box(Box.deriveId(owner, nonce), owner, 1000, 1, 1, List.of(regs));
    }

    private DefaultBoxProcessor seeded(Box... boxes) {
        InMemoryBoxStore store = new InMemoryBoxStore();
        List<BoxStore.BoxMutation> muts = new ArrayList<>();
        for (Box b : boxes) {
            muts.add(BoxStore.BoxMutation.write(b));
        }
        store.applyBlock(1, muts);
        return new DefaultBoxProcessor(store, params);
    }

    @Test
    void predicateJsonRoundTrips() {
        ScanPredicate p = new ScanPredicate.And(List.of(
            new ScanPredicate.OwnerEquals(alice.toBytes()),
            new ScanPredicate.Or(List.of(
                new ScanPredicate.RegisterEquals(0, new byte[] {1, 2, 3}),
                new ScanPredicate.RegisterContains(1, "oracle".getBytes())))));
        // Records with byte[] fields don't have value equality; compare the canonical JSON.
        assertEquals(p.toJson().toString(), ScanPredicate.fromJson(p.toJson()).toJson().toString());
    }

    @Test
    void ownerScanUsesOwnerAnchor() {
        DefaultBoxProcessor proc = seeded(
            box(alice, 0, BoxRegister.string("a")),
            box(alice, 1, BoxRegister.string("b")),
            box(bob, 0, BoxRegister.string("c")));
        var page = proc.scan(new ScanPredicate.OwnerEquals(alice.toBytes()), null, 50, 512);
        assertEquals(2, page.matches().size());
        assertTrue(page.matches().stream().allMatch(b -> b.owner().equals(alice)));
        assertNull(page.nextCursor()); // exhausted
    }

    @Test
    void registerContainsScanFindsAcrossOwners() {
        DefaultBoxProcessor proc = seeded(
            box(alice, 0, BoxRegister.string("price-oracle-v1")),
            box(bob, 0, BoxRegister.string("weather-oracle")),
            box(bob, 1, BoxRegister.string("unrelated")));
        var page = proc.scan(new ScanPredicate.RegisterContains(0, "oracle".getBytes()), null, 50, 512);
        assertEquals(2, page.matches().size());
    }

    @Test
    void registerEqualsAndCombinator() {
        byte[] tag = {9, 9};
        DefaultBoxProcessor proc = seeded(
            box(alice, 0, BoxRegister.bytes(tag)),
            box(alice, 1, BoxRegister.bytes(new byte[] {1})),
            box(bob, 0, BoxRegister.bytes(tag)));
        // Alice's boxes whose register 0 equals the tag: exactly one.
        var page = proc.scan(new ScanPredicate.And(List.of(
            new ScanPredicate.OwnerEquals(alice.toBytes()),
            new ScanPredicate.RegisterEquals(0, tag))), null, 50, 512);
        assertEquals(1, page.matches().size());
        assertEquals(Box.deriveId(alice, 0)[0], page.matches().get(0).id()[0]);
    }

    @Test
    void scanPaginatesViaCursor() {
        Box[] boxes = new Box[5];
        for (int i = 0; i < 5; i++) {
            boxes[i] = box(alice, i, BoxRegister.i64(i));
        }
        DefaultBoxProcessor proc = seeded(boxes);

        // limit 2 per page over 5 owner boxes -> pages of 2, 2, 1.
        BoxProcessor.ScanPage p1 = proc.scan(new ScanPredicate.OwnerEquals(alice.toBytes()), null, 2, 512);
        assertEquals(2, p1.matches().size());
        assertNotNull(p1.nextCursor());
        BoxProcessor.ScanPage p2 = proc.scan(new ScanPredicate.OwnerEquals(alice.toBytes()), p1.nextCursor(), 2, 512);
        assertEquals(2, p2.matches().size());
        BoxProcessor.ScanPage p3 = proc.scan(new ScanPredicate.OwnerEquals(alice.toBytes()), p2.nextCursor(), 2, 512);
        assertEquals(1, p3.matches().size());
        assertNull(p3.nextCursor());
    }
}
