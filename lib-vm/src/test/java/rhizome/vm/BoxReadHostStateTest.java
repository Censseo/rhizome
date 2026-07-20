package rhizome.vm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import rhizome.core.box.Box;
import rhizome.core.box.BoxRegister;
import rhizome.core.ledger.PublicAddress;

/**
 * The Java glue behind the VM's {@code box_read} host function: it copies out
 * {@code host.boxRead(id).serialize()}, so what matters here is that the host state
 * delegates to the wired {@link BoxReader} (and yields null when none is wired).
 */
class BoxReadHostStateTest {

    private final PublicAddress contract = PublicAddress.random();
    private final PublicAddress owner = PublicAddress.random();

    @Test
    void boxReadReturnsNullWithoutReader() {
        var host = new PersistentHostState(new InMemoryContractStore(), contract,
            owner.toBytes(), new byte[0], 0);
        assertNull(host.boxRead(Box.deriveId(owner, 0)));
    }

    @Test
    void boxReadDelegatesToWiredReader() {
        Box box = new Box(Box.deriveId(owner, 1), owner, 4242, 1, 1,
            List.of(BoxRegister.string("data-input")));
        BoxReader reader = id -> java.util.Arrays.equals(id, box.id()) ? box : null;

        var host = new PersistentHostState(new InMemoryContractStore(), contract,
            owner.toBytes(), new byte[0], 0, reader);

        Box read = host.boxRead(box.id());
        assertEquals(box, read);
        // The host function serializes this; confirm it round-trips to the same bytes.
        assertArrayEquals(box.serialize(), read.serialize());
        assertNull(host.boxRead(Box.deriveId(owner, 2)));
    }
}
