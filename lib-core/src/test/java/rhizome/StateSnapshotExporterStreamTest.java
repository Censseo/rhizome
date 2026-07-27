package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import rhizome.core.state.StateKeys;
import rhizome.core.state.snapshot.SnapshotChunk;
import rhizome.core.state.snapshot.StateSnapshotExporter;
import rhizome.core.state.snapshot.StateSource;

/**
 * The streaming snapshot export (audit: whole-list RAM materialisation): the callback form must
 * emit exactly the same chunk sequence as the list form — which now delegates to it — while never
 * holding more than one chunk at a time.
 */
class StateSnapshotExporterStreamTest {

    /** A tiny in-memory {@link StateSource} over per-domain entry lists. */
    private static StateSource sourceOf(Map<Byte, List<byte[][]>> domains) {
        return (domain, out) -> {
            for (byte[][] e : domains.getOrDefault(domain, List.of())) {
                out.accept(e[0], e[1]);
            }
        };
    }

    private static Map<Byte, List<byte[][]>> sampleDomains() {
        Map<Byte, List<byte[][]>> domains = new HashMap<>();
        List<byte[][]> ledger = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            ledger.add(new byte[][] {("key-" + i).getBytes(), ("value-" + i).getBytes()});
        }
        domains.put(StateKeys.LEDGER, ledger);
        List<byte[][]> boxes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            boxes.add(new byte[][] {("box-" + i).getBytes(), new byte[1024]});
        }
        domains.put(StateKeys.BOX, boxes);
        return domains;
    }

    @Test
    void streamingEmitsTheIdenticalChunkSequence() {
        StateSource source = sourceOf(sampleDomains());
        List<SnapshotChunk> listed = StateSnapshotExporter.export(source, 3, 2048);

        List<SnapshotChunk> streamed = new ArrayList<>();
        StateSnapshotExporter.export(source, 3, 2048, streamed::add);

        // Byte-for-byte identical, including the split points forced by both bounds.
        assertEquals(listed.size(), streamed.size());
        for (int i = 0; i < listed.size(); i++) {
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                listed.get(i).encode(), streamed.get(i).encode(), "chunk " + i);
        }
        assertTrue(listed.size() > 2, "the fixture must force several chunks");
    }

    @Test
    void streamingValidatesTheBounds() {
        StateSource source = sourceOf(sampleDomains());
        assertThrows(IllegalArgumentException.class,
            () -> StateSnapshotExporter.export(source, 0, 2048, c -> { }));
        assertThrows(IllegalArgumentException.class,
            () -> StateSnapshotExporter.export(source, 3, 0, c -> { }));
    }
}
