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

    private SnapshotBootstrap() {}

    /**
     * Attempts to bootstrap the given (empty) stores from {@code peer}.
     * Returns {@code true} on success; {@code false} when the peer offers no usable
     * snapshot (none materialised, pivot not buried, or verification failed).
     */
    static boolean bootstrap(NetworkParameters params, LedgerSnapshot genesisSnapshot,
                             RocksDbNodeStore store, RocksDbBoxStore boxStore, RocksDbTokenStore tokenStore,
                             RocksDbContractStore contractStore, RocksDbStateStore stateStore,
                             PeerSource peer, long nowMillis) {
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
        List<SnapshotChunk> chunks = new ArrayList<>(info.chunkCount());
        try {
            for (int i = 0; i < info.chunkCount(); i++) {
                chunks.add(SnapshotChunk.decode(peer.snapshotChunk(i)));
            }
        } catch (RuntimeException e) {
            log.warn("Snapshot chunk fetch/decode failed: {}", e.toString());
            return false;
        }

        // Rebuild the state tree and require root equality BEFORE seeding any store.
        var contracts = new ContractStateAdapter(contractStore);
        var adapter = new DomainStateAdapter(store.ledger(), store.nonceStore(), boxStore, tokenStore,
            contracts, contracts);
        try {
            StateSnapshotImporter.importVerified(chunks, stateStore, committedRoot.toBytes(), adapter);
        } catch (StateSnapshotImporter.SnapshotVerificationException e) {
            log.warn("Snapshot verification failed: {}", e.getMessage());
            return false;
        }
        // From here on we mutate several independent stores that commit separately. Mark the
        // bootstrap in progress so an interrupted seed is detected at the next boot instead of
        // running on half-written, inconsistent state (audit M8). The marker lives in the node
        // store and is cleared only after the final commit below succeeds.
        store.beginBootstrap();
        adapter.flush(pivot);
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
}
