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
import java.util.concurrent.atomic.AtomicLong;
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
 *
 * <p>Public because the CLI-side node client (in app-wallet) runs the same whole-exchange
 * deadline over its own requests; the peer-side users are {@link HttpPeerSource},
 * {@link PeerDiscovery} and {@link PeerBroadcaster}.
 */
public final class BodyReadDeadline {

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
        // Reject when all workers are busy: running the exchange inline on the CALLER (the old
        // CallerRunsPolicy) meant future.get() returned an already-computed result, silently
        // disabling the wall-clock deadline exactly under load — a slow-drip peer could then
        // stall the caller unbounded. Saturation is surfaced as a BodyReadSaturatedException so
        // it is distinguishable from a peer failure and never counted against the peer.
        new ThreadPoolExecutor.AbortPolicy());

    /**
     * Runs {@code exchange} on a worker, bounding the whole call (send + full body read) to
     * {@code timeout}. The exchange must publish its open response stream into
     * {@code openBody} as soon as headers arrive, so a deadline expiry can close it and cancel
     * the JDK exchange mid-read.
     *
     * @throws IOException on any I/O failure or on deadline expiry
     * @throws BodyReadSaturatedException if the LOCAL worker pool is saturated (not a peer fault)
     * @throws InterruptedException if the CALLING thread is interrupted while waiting
     */
    public static <T> T call(Duration timeout, AtomicReference<AutoCloseable> openBody, Callable<T> exchange)
            throws IOException, InterruptedException {
        Future<T> future = submit(exchange);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Slow-drip (or stuck) peer: cancel the worker and close the published body stream —
            // closing the InputStream cancels the underlying JDK exchange, unblocking the read.
            abort(future, openBody);
            throw new IOException("peer response body read exceeded " + timeout.toMillis() + " ms");
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw rethrow(e);
        }
    }

    /**
     * As {@link #call}, but the bound is an IDLE deadline: the exchange fails only when no
     * progress has been observed for {@code idleTimeout} — the exchange signals progress by
     * stamping {@code lastActivityNanos} on every byte it reads. For legitimately huge bodies
     * (the /sync block window) whose total duration scales with the window: a fixed
     * whole-exchange deadline never converges on a slow link, while an idle bound still kills
     * a stalled drip.
     *
     * @throws IOException on any I/O failure or on idle-deadline expiry
     * @throws BodyReadSaturatedException if the LOCAL worker pool is saturated (not a peer fault)
     * @throws InterruptedException if the CALLING thread is interrupted while waiting
     */
    public static <T> T callIdle(Duration idleTimeout, AtomicReference<AutoCloseable> openBody,
                          AtomicLong lastActivityNanos, Callable<T> exchange)
            throws IOException, InterruptedException {
        Future<T> future = submit(exchange);
        long idleNanos = idleTimeout.toNanos();
        long pollMillis = Math.max(10, Math.min(250, idleTimeout.toMillis() / 8));
        while (true) {
            try {
                return future.get(pollMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (System.nanoTime() - lastActivityNanos.get() < idleNanos) {
                    continue; // progress within the window: slow but alive, not stalled
                }
                abort(future, openBody);
                throw new IOException("peer response body idle for over " + idleTimeout.toMillis() + " ms");
            } catch (InterruptedException e) {
                future.cancel(true);
                throw e;
            } catch (ExecutionException e) {
                throw rethrow(e);
            }
        }
    }

    private static <T> Future<T> submit(Callable<T> exchange) throws BodyReadSaturatedException {
        try {
            return WORKERS.submit(exchange);
        } catch (RejectedExecutionException e) {
            // LOCAL backpressure, not a peer failure: callers must not penalise the peer for it.
            throw new BodyReadSaturatedException("peer exchange rejected: body-read pool saturated", e);
        }
    }

    /** Cancels the worker and closes the published body stream (cancels the JDK exchange). */
    private static void abort(Future<?> future, AtomicReference<AutoCloseable> openBody) {
        future.cancel(true);
        AutoCloseable body = openBody.get();
        if (body != null) {
            try {
                body.close();
            } catch (Exception ignored) {
                // best-effort cancellation; the deadline failure raised by the caller is what matters
            }
        }
    }

    /** Unwraps a worker failure to the caller-visible exception taxonomy. */
    private static IOException rethrow(ExecutionException e) throws IOException, InterruptedException {
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
        return new IOException("peer exchange failed: " + cause, cause);
    }
}
