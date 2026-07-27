package rhizome.node;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rhizome.core.block.Block;
import rhizome.core.block.BlockHeader;
import rhizome.core.blockchain.GenesisBlock;
import rhizome.core.blockchain.HeaderChain;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.PeerSource;
import rhizome.core.common.Constants;
import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.state.snapshot.DomainStateAdapter;
import rhizome.core.state.snapshot.SnapshotChunk;
import rhizome.core.state.snapshot.StateSnapshotImporter;
import rhizome.persistence.rocksdb.RocksDbBoxStore;
import rhizome.persistence.rocksdb.RocksDbContractStore;
import rhizome.persistence.rocksdb.RocksDbNodeStore;
import rhizome.persistence.rocksdb.RocksDbStateStore;
import rhizome.persistence.rocksdb.RocksDbTokenStore;
import rhizome.vm.ContractStateAdapter;

/**
 * Trust-minimised snapshot bootstrap (snap-sync, plan D4/D5): a fresh node adopts a peer's
 * state at a buried pivot height instead of replaying every historical block. Trust reduces
 * to the same thing full validation gives:
 *
 * <ol>
 *   <li>Genesis is built <b>locally</b> from the network parameters and balance snapshot —
 *       chain identity is never taken from the peer.</li>
 *   <li>Every header from genesis to the peer tip is validated statelessly
 *       ({@link HeaderChain}: chaining, PoW, difficulty, timestamps), so the state root
 *       committed in the pivot header carries the chain's full proof-of-work.</li>
 *   <li>The snapshot chunks must rebuild <b>exactly that root</b> before a single binding
 *       is written to a store; secondary indexes are derived locally from verified values.</li>
 *   <li>The pivot must be buried at least {@code maxReorgDepth} under the peer tip: since
 *       every node refuses deeper reorgs, the imported state can never need unwinding.</li>
 * </ol>
 *
 * <p>Afterwards the store holds genesis (with body), headers up to the pivot (body-less,
 * marked pruned), and the full state at the pivot; the normal engine boot and headers-first
 * sync then pull only the body suffix above the pivot.
 */
final class SnapshotBootstrap {

    private static final Logger log = LoggerFactory.getLogger(SnapshotBootstrap.class);

    /**
     * Hard cap on the number of snapshot chunks a peer may advertise. {@code chunkCount} arrives
     * verbatim from an untrusted peer's {@code /info} JSON; pre-sizing {@code new ArrayList<>(n)}
     * with {@code Integer.MAX_VALUE} allocates a multi-gigabyte backing array and OOMs a
     * bootstrapping node before a single chunk is fetched or the root is verified. This bound (each
     * chunk carries many bindings, so even a full-chain snapshot is far under it) makes the pre-size
     * safe, matching the length bounds the block/tx codecs already apply before allocating.
     */
    private static final int MAX_SNAPSHOT_CHUNKS = 1_000_000;

    /**
     * Hard cap on the total snapshot bytes spooled during bootstrap. Every fetched chunk is
     * streamed to a temporary file until the root verifies (see the import comment below), and
     * a hostile bootstrap peer may serve each chunk at the full 16 MiB its own endpoint permits
     * — {@code MAX_SNAPSHOT_CHUNKS} such chunks would exhaust any heap (audit F7) and, without
     * the cap, any disk. 4 GiB is far above any plausible mainnet snapshot while keeping the
     * spool bounded. The heap peak is now ONE chunk (~16 MiB): chunks are decoded lazily from
     * the spool during verification and replay.
     */
    private static final long MAX_SNAPSHOT_BUFFERED_BYTES = 4L * 1024 * 1024 * 1024;

    /** Worst-case bytes of one served chunk (the peer-side /state/snapshot/chunk fetch cap). */
    private static final long MAX_CHUNK_BYTES = 16L * 1024 * 1024;

    private SnapshotBootstrap() {}

    /**
     * Attempts to bootstrap the given (empty) stores from {@code peer}.
     * Returns {@code true} on success; {@code false} when the peer offers no usable
     * snapshot (none materialised, pivot not buried, or verification failed).
     * Chunk spooling uses the default temporary-file directory.
     */
    static boolean bootstrap(NetworkParameters params, LedgerSnapshot genesisSnapshot,
                             RocksDbNodeStore store, RocksDbBoxStore boxStore, RocksDbTokenStore tokenStore,
                             RocksDbContractStore contractStore, RocksDbStateStore stateStore,
                             PeerSource peer, long nowMillis) {
        return bootstrap(params, genesisSnapshot, store, boxStore, tokenStore, contractStore, stateStore,
            peer, nowMillis, java.nio.file.Path.of(System.getProperty("java.io.tmpdir")));
    }

    /**
     * As above, spooling fetched chunks to a temporary file inside {@code spoolDir} (the data
     * directory in production, so a multi-GiB snapshot never lands on a small tmpfs). The spool
     * is deleted after the import — success or failure (audit: snapshot bootstrap heap).
     */
    static boolean bootstrap(NetworkParameters params, LedgerSnapshot genesisSnapshot,
                             RocksDbNodeStore store, RocksDbBoxStore boxStore, RocksDbTokenStore tokenStore,
                             RocksDbContractStore contractStore, RocksDbStateStore stateStore,
                             PeerSource peer, long nowMillis, java.nio.file.Path spoolDir) {
        if (store.chainStore().height() != 0) {
            throw new IllegalStateException("snapshot bootstrap requires an empty chain store");
        }
        PeerSource.SnapshotInfo info = peer.snapshotInfo();
        if (info == null) {
            return false; // peer has no materialised snapshot
        }
        long peerHeight = peer.height();
        long pivot = info.pivotHeight();
        if (pivot < 2 || pivot + params.maxReorgDepth() > peerHeight) {
            log.info("Snapshot pivot {} not buried under peer tip {} (need {}); skipping",
                pivot, peerHeight, params.maxReorgDepth());
            return false;
        }
        // The validated-header list below is indexed by (int)(h - 2) up to
        // validateTo = pivot + maxReorgDepth: a pivot so large that such an index overflows int
        // would silently WRAP the casts into valid-looking negative/positive indexes (audit:
        // unchecked pivot cast). Compared overflow-safe on the pivot itself (maxReorgDepth is a
        // small positive int, so the right-hand side never overflows). Refuse the snapshot like
        // any other unusable advertisement — a chain billions of blocks long cannot exist anyway.
        if (pivot > Integer.MAX_VALUE + 2L - params.maxReorgDepth()) {
            log.warn("Snapshot pivot {} exceeds the supported header-index range; refusing", pivot);
            return false;
        }

        // Chain identity: genesis is derived locally, never downloaded.
        Block genesis = GenesisBlock.build(params, genesisSnapshot);
        BlockHeader genesisHeader = BlockHeader.of(genesis);

        // Validate the peer's header chain above genesis under full PoW, in bounded windows: fetch a
        // window, validate it chaining from the prefix already validated, then advance — so a peer that
        // serves cheap invalid headers is rejected after ONE window instead of OOM-ing us by buffering
        // its whole advertised span before a single check (audit 5th-pass, bootstrap buffering). We
        // validate only up to pivot+maxReorgDepth — enough to prove the pivot is buried (final) — never
        // the peer's untrusted advertised height. The below-pivot headers are retained because they are
        // adopted below; a genuinely large chain still costs O(pivot) retained headers, but those must
        // carry real PoW to pass validation, so there is no cheap OOM primitive left.
        long validateTo = pivot + params.maxReorgDepth();
        List<BlockHeader> headers = new ArrayList<>();
        for (long start = 2; start <= validateTo; start += Constants.BLOCK_HEADERS_PER_FETCH) {
            long end = Math.min(validateTo, start + Constants.BLOCK_HEADERS_PER_FETCH - 1);
            List<BlockHeader> window = fetchHeaders(peer, start, end);
            if (window == null || window.isEmpty()) {
                return false;
            }
            List<BlockHeader> prefix = headers; // validated heights 2..start-1, at index h-2
            HeaderChain.Result validated = HeaderChain.validate(params,
                h -> h <= GenesisBlock.GENESIS_ID ? genesisHeader : prefix.get((int) (h - 2)),
                start - 1, window, nowMillis);
            if (!validated.valid()) {
                log.warn("Peer headers invalid at {} ({}); refusing snapshot", validated.rejectedHeight(),
                    validated.rejection());
                return false;
            }
            headers.addAll(window);
        }

        // The authority for the expected root is the VALIDATED HEADER at the pivot —
        // the /info advertisement merely has to agree with it.
        BlockHeader pivotHeader = headers.get((int) (pivot - 2));
        SHA256Hash committedRoot = pivotHeader.stateRoot();
        if (committedRoot.equals(SHA256Hash.empty())
            || !java.util.Arrays.equals(committedRoot.toBytes(), info.stateRoot())) {
            log.warn("Snapshot info root does not match the validated pivot header; refusing");
            return false;
        }

        if (info.chunkCount() < 0 || info.chunkCount() > MAX_SNAPSHOT_CHUNKS) {
            log.warn("Snapshot advertises an out-of-range chunk count ({}); refusing", info.chunkCount());
            return false;
        }
        // Up-front cross-check against the spooled-bytes bound: even at the worst-case chunk
        // size the advertised set must fit the cap, or the loop below would spool its way to a
        // full disk before aborting (audit F7).
        if (info.chunkCount() * MAX_CHUNK_BYTES > MAX_SNAPSHOT_BUFFERED_BYTES) {
            log.warn("Snapshot of {} chunks could exceed the {} byte bootstrap spool bound; refusing",
                info.chunkCount(), MAX_SNAPSHOT_BUFFERED_BYTES);
            return false;
        }
        // Spool each chunk to disk AS IT IS FETCHED instead of buffering every chunk on the heap
        // until the root verifies (audit F7 follow-up: 4 GiB of peer-controlled bytes used to sit
        // in memory before a single check ran). Only the offset index is retained; chunks are
        // decoded lazily from the spool during verification and replay, so the heap peak is one
        // chunk. The spool is deleted in the finally below, on success and on every failure.
        java.nio.file.Path spool;
        try {
            spool = java.nio.file.Files.createTempFile(spoolDir, "rhizome-snapshot-", ".chunks");
        } catch (java.io.IOException e) {
            log.warn("Snapshot spool file could not be created: {}", e.toString());
            return false;
        }
        long[] offsets = new long[info.chunkCount()];
        long[] lengths = new long[info.chunkCount()];
        long spooledBytes = 0;
        // Hoisted: the seed phase after the try needs both (the spool is already deleted by then;
        // chunks re-read nothing — it is only the decoded view the importer verified).
        DomainStateAdapter adapter = null;
        List<SnapshotChunk> chunks = null;
        // One try/finally covers BOTH the fetch/spool loop and the verify/replay below: every
        // failure path (bound exceeded, fetch error, verification failure) deletes the spool —
        // a hostile bootstrap peer must never leave gigabytes behind per attempt (audit F7).
        try {
            try (var out = java.nio.file.Files.newOutputStream(spool)) {
                for (int i = 0; i < info.chunkCount(); i++) {
                    byte[] raw = peer.snapshotChunk(i);
                    spooledBytes += raw.length;
                    if (spooledBytes > MAX_SNAPSHOT_BUFFERED_BYTES) {
                        log.warn("Snapshot chunks exceeded the {} byte bootstrap spool bound; aborting",
                            MAX_SNAPSHOT_BUFFERED_BYTES);
                        return false;
                    }
                    offsets[i] = spooledBytes - raw.length;
                    lengths[i] = raw.length;
                    out.write(raw);
                }
            } catch (RuntimeException | java.io.IOException e) {
                log.warn("Snapshot chunk fetch/spool failed: {}", e.toString());
                return false;
            }

            // Rebuild the state tree and require root equality BEFORE seeding any store. The sink
            // writes ledger/nonce bindings through immediately, so the full set must be verified
            // against the PoW-validated pivot root before any of it is seeded, and the seeded bytes
            // must be the exact ones verified (no re-fetch): the spooled, file-backed chunk list
            // below re-reads the same bytes on each pass (verify, then replay — audit V6a note: a
            // fully transactional sink would avoid the two-pass decode; the bootstrap peer is an
            // operator-configured trusted seed and the chunk count is already bounded).
            var contracts = new ContractStateAdapter(contractStore);
            adapter = new DomainStateAdapter(store.ledger(), store.nonceStore(), boxStore, tokenStore,
                contracts, contracts);
            try (var channel = java.nio.channels.FileChannel.open(spool, java.nio.file.StandardOpenOption.READ)) {
                chunks = new SpooledChunks(channel, offsets, lengths, info.chunkCount());
                try {
                    StateSnapshotImporter.importVerified(chunks, stateStore, committedRoot.toBytes(), adapter);
                } catch (StateSnapshotImporter.SnapshotVerificationException e) {
                    log.warn("Snapshot verification failed: {}", e.getMessage());
                    return false;
                }
            } catch (java.io.IOException e) {
                log.warn("Snapshot spool could not be read: {}", e.toString());
                return false;
            }
        } finally {
            try {
                java.nio.file.Files.deleteIfExists(spool);
            } catch (java.io.IOException e) {
                log.warn("Snapshot spool {} could not be deleted", spool);
            }
        }
        // From here on we mutate several independent stores that commit separately. Mark the
        // bootstrap in progress so an interrupted seed is detected at the next boot instead of
        // running on half-written, inconsistent state (audit M8). The marker lives in the node
        // store and is cleared only after the final commit below succeeds.
        store.beginBootstrap();
        adapter.flush(pivot);
        // Durability barrier for the contract seed: import wrote code/storage slots unsynced
        // (batched WAL syncs only bound the tail), so fsync before the bootstrap marker can
        // clear — a power loss must not drop seeded slots the pivot root commits to.
        contractStore.syncToDisk();
        stateStore.putRoot(pivot, committedRoot.toBytes());

        // Adopt the chain: genesis with its body, then validated headers (body-less) to the
        // pivot; the nonces imported above are current exactly as of the pivot.
        store.chainStore().append(genesis);
        store.bootstrapHeaders(headers.subList(0, (int) (pivot - 1)));
        store.nonceStore().markSyncedThrough(pivot);
        store.endBootstrap();

        log.info("Snap-sync bootstrap complete: pivot={} stateRoot={} ({} chunks); body sync resumes above pivot",
            pivot, committedRoot.toHexString(), chunks.size());
        return true;
    }

    private static List<BlockHeader> fetchHeaders(PeerSource peer, long from, long to) {
        List<BlockHeader> out = new ArrayList<>();
        try {
            for (long start = from; start <= to; start += Constants.BLOCK_HEADERS_PER_FETCH) {
                long end = Math.min(to, start + Constants.BLOCK_HEADERS_PER_FETCH - 1);
                out.addAll(peer.headers(start, end));
            }
        } catch (RuntimeException e) {
            log.warn("Header fetch for bootstrap failed: {}", e.toString());
            return null;
        }
        return out;
    }

    /**
     * A read-only {@link List} view over the spooled chunk file: {@code get(i)} re-reads and
     * decodes chunk {@code i} from disk, so the importer's two passes (root verification, then
     * replay) never hold more than one decoded chunk on the heap (audit: snapshot bootstrap
     * heap). Backed by positional reads on the already-open channel, which the caller closes
     * after the import completes.
     */
    private static final class SpooledChunks extends java.util.AbstractList<SnapshotChunk> {
        private final java.nio.channels.FileChannel channel;
        private final long[] offsets;
        private final long[] lengths;
        private final int size;

        SpooledChunks(java.nio.channels.FileChannel channel, long[] offsets, long[] lengths, int size) {
            this.channel = channel;
            this.offsets = offsets;
            this.lengths = lengths;
            this.size = size;
        }

        @Override
        public SnapshotChunk get(int index) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException(index);
            }
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(Math.toIntExact(lengths[index]));
            try {
                while (buf.hasRemaining()) {
                    if (channel.read(buf, offsets[index] + buf.position()) < 0) {
                        throw new java.io.EOFException("spool truncated at chunk " + index);
                    }
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("snapshot spool read failed at chunk " + index, e);
            }
            return SnapshotChunk.decode(buf.array());
        }

        @Override
        public int size() {
            return size;
        }
    }
}
