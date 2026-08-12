package rhizome.node;

import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import rhizome.core.blockchain.ChainEngine;
import rhizome.core.state.snapshot.StateSnapshotExporter;
import rhizome.core.state.snapshot.StateSource;

/**
 * Owns the node's snap-sync export: the spool directory, the capture under the engine's consistent
 * view, and the one materialised snapshot the {@code /state/snapshot/*} routes serve from.
 *
 * <p>These ~200 lines were the last mutable state in {@link NodeService} — a {@code volatile} spool
 * directory installed by a setter that did file I/O, and a {@code volatile} snapshot rotated under
 * it. Unlike the wiring fields, those two genuinely do change while the node runs, so the fix is not
 * to make them final but to give them an owner: everything here is about one file-backed export and
 * nothing here is about serving the chain.
 *
 * <p>The spool directory is a <em>constructor</em> argument, which is why {@link #open} is a factory
 * rather than a constructor: wiring the directory creates it and sweeps stale spools, so it can
 * fail, and a throwing {@code NodeService} constructor would propagate to eighteen test sites that
 * have no snapshot at all. A service built by {@link #inTempDir} does no I/O until the first
 * capture.
 *
 * <p>Absence is a legal configuration, not a half-built one: with a {@code null} source (no state
 * accumulator, or a node that does not export) {@link #materialize()} returns false, {@link
 * #current()} stays null and {@link #pivotHeight()} stays 0 — so no caller needs a null check on
 * the service itself.
 */
public final class SnapshotService implements AutoCloseable {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SnapshotService.class);

    /** Prefix every spool file carries, so the sweep can recognise its own leftovers. */
    private static final String SPOOL_PREFIX = "rhizome-snapshot-";
    private static final String SPOOL_SUFFIX = ".chunks";

    /** Entry bound per snapshot chunk (bytes are bounded separately by the exporter). */
    public static final int SNAPSHOT_CHUNK_ENTRIES = 4096;

    private final ChainEngine engine;
    /** The state to export, or {@code null} on a node that does not serve snapshots. */
    private final StateSource source;
    /** Where spools are created, or {@code null} for the OS temp dir (tests). */
    private final Path spoolDir;

    private volatile MaterializedSnapshot snapshot;

    private SnapshotService(ChainEngine engine, StateSource source, Path spoolDir) {
        this.engine = engine;
        this.source = source;
        this.spoolDir = spoolDir;
    }

    /**
     * Wires the directory snapshot spools live in (the node's data dir, alongside the stores —
     * NOT the OS temp dir, which is commonly a tmpfs and would silently put the whole state back
     * in RAM). Stale {@code rhizome-snapshot-*} spools from a previous process (SIGKILL leaves
     * them behind — {@link #close()} only runs on a clean shutdown) are swept here, once, at
     * wiring time.
     */
    public static SnapshotService open(ChainEngine engine, StateSource source, Path spoolDir)
            throws IOException {
        Files.createDirectories(spoolDir);
        int swept = 0;
        try (var entries = Files.list(spoolDir)) {
            for (var stale : (Iterable<Path>) entries
                    .filter(p -> p.getFileName().toString().startsWith(SPOOL_PREFIX))
                    ::iterator) {
                try {
                    Files.deleteIfExists(stale);
                    swept++;
                } catch (IOException e) {
                    log.warn("Stale snapshot spool {} could not be deleted", stale, e);
                }
            }
        }
        if (swept > 0) {
            log.info("Swept {} stale snapshot spool(s) from {}", swept, spoolDir);
        }
        return new SnapshotService(engine, source, spoolDir);
    }

    /**
     * Spools to the OS temp dir instead of a wired directory, and does no I/O at construction.
     * What tests use, and the shape a node with no data dir falls back to.
     */
    public static SnapshotService inTempDir(ChainEngine engine, StateSource source) {
        return new SnapshotService(engine, source, null);
    }

    /** A service that can never export: every query answers "no snapshot". */
    public static SnapshotService none(ChainEngine engine) {
        return new SnapshotService(engine, null, null);
    }

    /**
     * Captures a fresh materialised snapshot of the full committed state under the engine lock, so
     * every chunk corresponds to the single {@code (height, stateRoot)} pair it advertises. Chunks
     * are spooled to a temp file as the exporter emits them (see {@link MaterializedSnapshot});
     * only the offset index is kept on the heap. Replaces — and deletes the spool of — any previous
     * snapshot. False when the node cannot export (no source wired, or no state accumulator
     * producing roots) or when the spool I/O fails; a failure cleans up the partial file and keeps
     * the previous snapshot.
     */
    public boolean materialize() {
        if (source == null || engine.stateRoot() == null) {
            return false;
        }
        final MaterializedSnapshot fresh;
        try {
            fresh = engine.withConsistentView(() -> {
                try {
                    return capture();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            log.error("Snapshot materialisation failed; the previous snapshot is kept", e.getCause());
            return false;
        }
        MaterializedSnapshot previous = this.snapshot;
        this.snapshot = fresh;
        if (previous != null) {
            previous.close();
        }
        return true;
    }

    /** The current materialised snapshot, or {@code null} if none has been captured. */
    public MaterializedSnapshot current() {
        return snapshot;
    }

    /** Height of the current materialised snapshot ({@code 0} when none). */
    public long pivotHeight() {
        var snap = snapshot;
        return snap == null ? 0 : snap.pivotHeight();
    }

    /**
     * Releases the file-backed snapshot: closes its channel and deletes its spool. Called at node
     * shutdown; a fresh materialisation after close starts from no snapshot again.
     */
    @Override
    public void close() {
        MaterializedSnapshot snap = this.snapshot;
        this.snapshot = null;
        if (snap != null) {
            snap.close();
        }
    }

    /**
     * Stream-encodes every exported chunk into a fresh temp spool and returns the file-backed
     * snapshot over it. At most one chunk is heap-resident at a time. A failure anywhere (write,
     * channel open) deletes the partial spool before propagating.
     */
    private MaterializedSnapshot capture() throws IOException {
        Path file = spoolDir != null
            ? Files.createTempFile(spoolDir, SPOOL_PREFIX, SPOOL_SUFFIX)
            : Files.createTempFile(SPOOL_PREFIX, SPOOL_SUFFIX);
        try {
            var offsets = new LongIndex();
            var lengths = new LongIndex();
            long[] position = {0};
            try (var out = Files.newOutputStream(file)) {
                StateSnapshotExporter.export(
                        source, SNAPSHOT_CHUNK_ENTRIES, StateSnapshotExporter.DEFAULT_CHUNK_BYTES,
                        chunk -> {
                            byte[] encoded = chunk.encode();
                            offsets.add(position[0]);
                            lengths.add(encoded.length);
                            try {
                                out.write(encoded);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                            position[0] += encoded.length;
                        });
            }
            return new MaterializedSnapshot(engine.height(), engine.stateRoot(), file,
                offsets.toArray(), lengths.toArray(),
                FileChannel.open(file, StandardOpenOption.READ));
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException cleanup) {
                log.warn("Partial snapshot spool {} could not be deleted", file, cleanup);
            }
            throw e;
        }
    }

    /**
     * A consistent full-state export frozen at one (pivotHeight, stateRoot) point, backed by a
     * spool file instead of the heap: the exporter's chunks are stream-encoded into the file at
     * capture time and only their {@code (offset, length)} index is retained (a few bytes per
     * chunk), so the RAM peak of a materialisation is one chunk rather than the whole state —
     * and an idle node serving snapshots holds none of it on the heap (audit: snapshot export
     * RAM peak). Chunk reads are positional ({@link java.nio.channels.FileChannel#read} with an
     * explicit offset), which is thread-safe across concurrent API requests sharing one snapshot.
     * {@link #close()} releases the file; the owning {@link SnapshotService} closes a snapshot when
     * it is replaced and at shutdown.
     */
    public static final class MaterializedSnapshot implements AutoCloseable {
        private final long pivotHeight;
        private final byte[] stateRoot;
        private final Path file;
        private final long[] offsets;
        private final long[] lengths;
        private final FileChannel channel;

        private MaterializedSnapshot(long pivotHeight, byte[] stateRoot, Path file,
                                     long[] offsets, long[] lengths, FileChannel channel) {
            this.pivotHeight = pivotHeight;
            this.stateRoot = stateRoot;
            this.file = file;
            this.offsets = offsets;
            this.lengths = lengths;
            this.channel = channel;
        }

        public long pivotHeight() {
            return pivotHeight;
        }

        public byte[] stateRoot() {
            return stateRoot;
        }

        /** The spool the chunks are read from (visible for the replacement/deletion tests). */
        public Path file() {
            return file;
        }

        public int chunkCount() {
            return lengths.length;
        }

        /** On-wire length of chunk {@code index}, without reading its bytes. */
        public long chunkLength(int index) {
            return lengths[index];
        }

        public byte[] chunkBytes(int index) {
            if (index < 0 || index >= lengths.length) {
                throw new IndexOutOfBoundsException(index);
            }
            ByteBuffer buf = ByteBuffer.allocate(Math.toIntExact(lengths[index]));
            try {
                while (buf.hasRemaining()) {
                    if (channel.read(buf, offsets[index] + buf.position()) < 0) {
                        throw new EOFException("snapshot spool truncated at chunk " + index);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("snapshot chunk read failed", e);
            }
            return buf.array();
        }

        @Override
        public void close() {
            try {
                channel.close();
            } catch (IOException e) {
                log.warn("Snapshot spool {} could not be closed", file, e);
            }
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.warn("Snapshot spool {} could not be deleted", file, e);
            }
        }
    }

    /** A minimal growable {@code long[]} for the snapshot chunk index (avoids boxed lists). */
    private static final class LongIndex {
        private long[] values = new long[64];
        private int size;

        void add(long value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        long[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }
}
