package rhizome.core.ledger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.json.JSONObject;

import rhizome.core.blockchain.NetworkParameters;

/**
 * Reads a {@link LedgerSnapshot} from a JSON file (operator-supplied via
 * {@code RHIZOME_SNAPSHOT}) or from a classpath resource (a network's shipped default
 * allocation, see {@link NetworkParameters#genesisSnapshotResource()}), or yields an
 * empty snapshot for a premine-free fresh chain.
 */
public final class SnapshotLoader {

    private SnapshotLoader() {}

    /** Hard cap on the snapshot size so a misconfigured path/resource cannot OOM the boot. */
    private static final long MAX_SNAPSHOT_FILE_BYTES = 512L * 1024 * 1024;

    /** Deepest bracket nesting accepted: org.json parses recursively, so a deeply nested
     *  snapshot overflows the boot thread's stack (same guard as the API JSON parser). */
    private static final int MAX_JSON_DEPTH = 64;

    /**
     * Bound on {@link #fromFile}'s open+read, so a path that resolves to something that blocks
     * at the OS level on {@code open()} — a FIFO with no writer being the concrete case this
     * closes — cannot hang the boot thread forever (E2E-57). A local snapshot read, even near
     * the 512 MiB cap, is low seconds of work at worst; this budget is generous against that
     * cost, not tight against it.
     */
    private static final Duration SNAPSHOT_OPEN_TIMEOUT = Duration.ofSeconds(10);

    public static LedgerSnapshot fromFile(Path path) throws IOException {
        // Primary defense (E2E-57): reject anything that resolves — through symlinks;
        // BasicFileAttributes with the default (following) LinkOptions reports the RESOLVED
        // target's type — to something other than a regular file, BEFORE ever calling an open
        // that could block at the OS level. A stat() never blocks the way open() on a FIFO does
        // (verified empirically: readAttributes on a writerless FIFO returns in ~0 ms), so this
        // check is essentially free and closes the realistic "point RHIZOME_SNAPSHOT at a FIFO"
        // hang outright rather than merely bounding it.
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(path, BasicFileAttributes.class);
        } catch (IOException e) {
            throw new IOException("cannot stat snapshot path: " + path, e);
        }
        if (!attrs.isRegularFile()) {
            throw new IOException("snapshot path is not a regular file: " + path);
        }

        // Secondary defense: bound the open+read itself against the residual TOCTOU where the
        // resolved target's type changes between the stat above and the open below (e.g. it is
        // swapped for a FIFO in that narrow window). This is NOT a complete fix for a truly
        // wedged open() — see readFileOnce's caller, withTimeout, for why interrupting the
        // worker thread does not by itself unblock a FIFO stuck without a writer (verified
        // empirically: Thread.interrupt() on a thread inside FileChannel.open() on such a FIFO
        // neither throws nor returns, since open() is not wired into Java NIO's
        // interruptible-channel machinery the way read()/write() are). What this DOES guarantee
        // is that THIS caller — the boot thread — never blocks past the timeout: the worst case
        // left is a single leaked daemon thread stuck in the kernel for the life of the process,
        // not a hung boot. That is a BOUNDED residual for the race case, not a fully closed one;
        // the realistic, unraced case above is fully closed.
        return withTimeout(() -> readFileOnce(path), SNAPSHOT_OPEN_TIMEOUT,
            "snapshot read timed out after " + SNAPSHOT_OPEN_TIMEOUT.toSeconds()
                + "s (possibly blocked opening a non-regular file): " + path);
    }

    /**
     * Opens {@code path} exactly once and derives both the size check and the content read from
     * that SAME handle (E2E-55) — closing the TOCTOU window a separate {@code Files.size(path)}
     * probe followed by a later, independent {@code Files.readString(path)} leaves open. Once
     * {@code open()} resolves a symlink to an inode, a later retarget of the link cannot affect
     * the already-open descriptor on POSIX systems, so there is no window left between "check
     * the size" and "read the content" for a race to land in — both now happen against the one
     * inode this handle was opened against.
     */
    private static LedgerSnapshot readFileOnce(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size > MAX_SNAPSHOT_FILE_BYTES) {
                throw new IOException("snapshot file too large: " + size + " bytes (cap "
                    + MAX_SNAPSHOT_FILE_BYTES + ")");
            }
            ByteBuffer buffer = ByteBuffer.allocate((int) size);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) == -1) {
                    break; // shrank under us after the size() read above; decode what is there
                }
            }
            buffer.flip();
            String content = StandardCharsets.UTF_8.decode(buffer).toString();
            return parseGuarded(content);
        }
    }

    /**
     * Runs {@code task} on a dedicated daemon worker and waits up to {@code timeout}. The worker
     * MUST be a daemon thread: if it is genuinely wedged (the FIFO-open case above), it stays
     * blocked in the kernel for the rest of the process's life, and must never prevent the JVM
     * from exiting once this method gives up on it and the caller decides to abort boot.
     */
    private static LedgerSnapshot withTimeout(Callable<LedgerSnapshot> task, Duration timeout,
            String timeoutMessage) throws IOException {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "snapshot-loader-open");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<LedgerSnapshot> future = executor.submit(task);
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true); // best-effort; does not unblock a wedged open() — see javadoc above
                throw new IOException(timeoutMessage);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException io) {
                    throw io;
                }
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                if (cause instanceof Error er) {
                    throw er;
                }
                throw new IOException("snapshot read failed: " + cause, cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for snapshot read", e);
            }
        } finally {
            // Never awaits termination: if the worker is truly wedged this returns immediately
            // anyway (shutdown() only stops new submissions), and a daemon thread cannot block
            // JVM exit regardless of whether it ever completes.
            executor.shutdown();
        }
    }

    /**
     * Loads a {@link LedgerSnapshot} from a classpath resource — the mechanism a network's
     * shipped default allocation ({@link NetworkParameters#genesisSnapshotResource()}) uses to
     * reach the boot path. Carries the SAME bounds as {@link #fromFile}: shipped-by-default is
     * not a bypass around decode-time bounds (contracts/genesis-allocation-format.md §1).
     *
     * <p>{@link URLConnection#getContentLengthLong()} is a cheap size probe for both {@code file:}
     * (unpacked classes/resources dir) and {@code jar:} (packaged jar / native image) resource
     * URLs — the two shapes this classpath can take — so an oversized resource is rejected before
     * a single content byte is read, exactly like {@link #fromFile}'s handle-derived size check.
     * When the connection cannot report a length (unknown/negative), {@link #readAtMost} is the
     * deterministic backstop: the stream is capped at {@code MAX_SNAPSHOT_FILE_BYTES + 1} bytes,
     * so oversized resources are still rejected even without a usable size probe.
     */
    public static LedgerSnapshot fromResource(String resourcePath) throws IOException {
        ClassLoader loader = SnapshotLoader.class.getClassLoader();
        URL url = loader.getResource(resourcePath);
        if (url == null) {
            throw new IOException("snapshot resource not found: " + resourcePath);
        }
        URLConnection connection = url.openConnection();
        long declaredSize = connection.getContentLengthLong();
        if (declaredSize >= 0 && declaredSize > MAX_SNAPSHOT_FILE_BYTES) {
            throw new IOException("snapshot resource too large: " + declaredSize + " bytes (cap "
                + MAX_SNAPSHOT_FILE_BYTES + "): " + resourcePath);
        }
        try (InputStream in = connection.getInputStream()) {
            // Fallback branch when declaredSize was unknown/negative: this always runs (even a
            // known-good declaredSize still goes through readAtMost with the same limit), but for
            // an oversized resource whose length CAN be probed, the check above already threw
            // before this point was ever reached. Only when the probe fails does this buffer up to
            // MAX_SNAPSHOT_FILE_BYTES + 1 bytes before rejecting. E2E-59/60 (docs/adversarial/spec.md):
            // this branch is safe for an ordinary-sized resource under a constrained heap, but a
            // resource whose SIZE approaches the CONSUMING process's own heap can still exhaust it
            // even after readAtMost's chunked-read fix below — see readAtMost's javadoc for why
            // that residual is bounded, not eliminated, by this change.
            byte[] bytes = readAtMost(in, MAX_SNAPSHOT_FILE_BYTES + 1);
            if (bytes.length > MAX_SNAPSHOT_FILE_BYTES) {
                throw new IOException("snapshot resource too large: exceeds cap "
                    + MAX_SNAPSHOT_FILE_BYTES + " bytes: " + resourcePath);
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            return parseGuarded(content);
        }
    }

    /**
     * Selects the boot-time genesis snapshot per contracts/genesis-allocation-format.md §3:
     * an explicit {@code snapshotPath} (the {@code RHIZOME_SNAPSHOT} override) wins if present;
     * otherwise the profile's shipped default resource, if it declares one; otherwise an empty
     * snapshot for the network. Homed here (not in {@code RhizomeNode}) so the selection logic
     * is unit-testable without booting a node.
     */
    public static LedgerSnapshot forBoot(Optional<String> snapshotPath, NetworkParameters params)
            throws IOException {
        if (snapshotPath.isPresent()) {
            return fromFile(Path.of(snapshotPath.get()));
        }
        Optional<String> resource = params.genesisSnapshotResource();
        if (resource.isPresent()) {
            return fromResource(resource.get());
        }
        return empty(params.chainId());
    }

    /** An empty snapshot for the given network (fresh chain, no initial balances). */
    public static LedgerSnapshot empty(int chainId) {
        return new LedgerSnapshot("empty", 0, chainId);
    }

    /**
     * The guarded read+parse shared by {@link #fromFile} and {@link #fromResource}: the
     * depth-64 nesting guard, then {@link LedgerSnapshot#fromJson}. One code path so the two
     * loaders can never silently drift apart on bounds (research.md Decision 3).
     */
    private static LedgerSnapshot parseGuarded(String content) throws IOException {
        // Same depth guard as the API JSON parser, string-aware so brackets inside a string
        // value (addresses, metadata) don't count as nesting. One O(n) pass at boot, same order
        // as the parse itself.
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                if (++depth > MAX_JSON_DEPTH) {
                    throw new IOException("snapshot JSON nesting too deep (max " + MAX_JSON_DEPTH + ")");
                }
            } else if (c == '}' || c == ']') {
                depth = Math.max(0, depth - 1);
            }
        }
        return LedgerSnapshot.fromJson(new JSONObject(content));
    }

    /**
     * Reads at most {@code limit} bytes of {@code in} into fixed-size (64 KiB) chunks, never
     * reading past that bound regardless of how large the underlying resource actually is, then
     * assembles them into one right-sized array.
     *
     * <p>This is a bounded improvement over a naive {@code ByteArrayOutputStream}, not a
     * complete fix for large resources on a small heap (E2E-60, docs/adversarial/spec.md):
     * {@code ByteArrayOutputStream.write} grows its internal array by doubling, which — combined
     * with its own {@code toByteArray()} making a further right-sized copy — momentarily holds
     * two or three full-size copies of the content alive at once. Reading into fixed-size chunks
     * and merging them exactly once avoids that doubling multiplier, holding roughly the content
     * size twice (the chunks plus the merged array) rather than three to four times over. Measured
     * empirically against this exact fallback branch under a constrained heap: the previous
     * implementation's practical ceiling was content that fully round-tripped (read AND parsed)
     * under about 10 MiB with a 64 MiB heap; this version pushes that to roughly 25-29 MiB before
     * either the merge or {@code org.json}'s DOM parse itself exhausts the same heap — a real but
     * modest improvement, and nowhere near enough to make a 200 MiB resource (E2E-60's case, still
     * comfortably under the real 512 MiB cap) fit in a 64 MiB heap. Closing that gap fully would
     * need this method to never materialize the full content at all — i.e. parsing JSON from a
     * bounded {@code Reader} directly rather than a materialized {@code String} — which is a
     * larger change (effectively a different JSON-parsing strategy) than this fix's scope covers;
     * the residual is declared as such in docs/adversarial/spec.md rather than silently narrowed.
     */
    private static byte[] readAtMost(InputStream in, long limit) throws IOException {
        int chunkSize = 1 << 16; // 64 KiB
        List<byte[]> chunks = new ArrayList<>();
        long total = 0;
        while (total < limit) {
            int toRead = (int) Math.min(chunkSize, limit - total);
            byte[] chunk = new byte[toRead];
            int n = readFully(in, chunk);
            if (n <= 0) {
                break;
            }
            if (n < chunk.length) {
                chunk = Arrays.copyOf(chunk, n); // trim only the final, short chunk
            }
            chunks.add(chunk);
            total += n;
            if (n < toRead) {
                break; // short read: the underlying stream is exhausted
            }
        }
        byte[] result = new byte[(int) total];
        int pos = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, pos, chunk.length);
            pos += chunk.length;
        }
        return result;
    }

    /** Fills {@code buf} completely from {@code in}, short of EOF, without assuming one
     *  {@code read} call returns a full buffer (it generally does not). */
    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n == -1) {
                break;
            }
            total += n;
        }
        return total;
    }
}
