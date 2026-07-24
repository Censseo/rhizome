package rhizome.net;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Enforces a wall-clock deadline over an ENTIRE peer exchange — the send AND the full
 * response-body read. {@code HttpRequest.timeout} covers only the exchange up to the response
 * headers; after that a slow-drip peer could stall the caller forever inside
 * {@code InputStream.read} (and the sync loop runs on a single thread, so one dripping peer
 * would wedge synchronization permanently — audit F1/F2).
 *
 * <p>The whole exchange runs on a shared, bounded worker pool of daemon threads and the
 * caller bounds it with {@code Future.get(deadline)}. On expiry the future is cancelled and
 * the response stream the task published is closed, which cancels the underlying JDK exchange
 * and unblocks the worker. Deadline expiry is surfaced as an {@link IOException} so the
 * existing transport-failure handling applies unchanged.
 */
final class BodyReadDeadline {

    private BodyReadDeadline() {}

    /**
     * Hard cap on concurrent in-flight exchanges. Every caller blocks on its own result, so
     * live tasks are naturally bounded by the handful of calling threads (the sync thread and
     * the small PEX pool); this cap is defense in depth, and the threads are daemons so a
     * worker stuck in an unresponsive read can never hold the JVM open.
     */
    private static final int MAX_WORKERS = 16;

    private static final ExecutorService WORKERS = new ThreadPoolExecutor(
        0, MAX_WORKERS, 60L, TimeUnit.SECONDS,
        new SynchronousQueue<>(),
        r -> {
            Thread t = new Thread(r, "peer-body-read");
            t.setDaemon(true);
            return t;
        },
        // Unreachable in practice (callers outnumberable by MAX_WORKERS block on get()); if it
        // ever fired, running inline merely degrades to the pre-fix behaviour rather than
        // dropping the exchange.
        new ThreadPoolExecutor.CallerRunsPolicy());

    /**
     * Runs {@code exchange} on a worker, bounding the whole call (send + full body read) to
     * {@code timeout}. The exchange must publish its open response stream into
     * {@code openBody} as soon as headers arrive, so a deadline expiry can close it and cancel
     * the JDK exchange mid-read.
     *
     * @throws IOException on any I/O failure, on deadline expiry, or if the pool is saturated
     * @throws InterruptedException if the CALLING thread is interrupted while waiting
     */
    static <T> T call(Duration timeout, AtomicReference<AutoCloseable> openBody, Callable<T> exchange)
            throws IOException, InterruptedException {
        Future<T> future;
        try {
            future = WORKERS.submit(exchange);
        } catch (RejectedExecutionException e) {
            throw new IOException("peer exchange rejected", e);
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Slow-drip (or stuck) peer: cancel the worker and close the published body stream —
            // closing the InputStream cancels the underlying JDK exchange, unblocking the read.
            future.cancel(true);
            AutoCloseable body = openBody.get();
            if (body != null) {
                try {
                    body.close();
                } catch (Exception ignored) {
                    // best-effort cancellation; the deadline failure below is what matters
                }
            }
            throw new IOException("peer response body read exceeded " + timeout.toMillis() + " ms");
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            throw new IOException("peer exchange failed: " + cause, cause);
        }
    }
}
