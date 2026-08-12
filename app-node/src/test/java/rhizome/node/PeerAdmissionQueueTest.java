package rhizome.node;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three bounds of off-loop peer admission, and the shutdown that was missing.
 *
 * <p>None of this was testable before: the executor, the queue bound and the two coalescing sets
 * were private fields of {@code NodeService}, so exercising them meant standing up an engine, a
 * mempool and a registry, and the only observable was whether a peer eventually appeared. The
 * queue bound in particular had no test at all — reaching it required 256 concurrent
 * slow-resolving hostnames.
 */
class PeerAdmissionQueueTest {

    /** Blocks the single worker so the queue can be observed while full. */
    private static final class Blocker {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        void hold() {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void duplicateUrlsAndPortVariantsOfOneHostCoalesceIntoOneAdmission() throws Exception {
        Blocker blocker = new Blocker();
        List<String> admitted = Collections.synchronizedList(new java.util.ArrayList<>());
        try (PeerAdmissionQueue queue = new PeerAdmissionQueue()) {
            queue.enqueue("http://slow.example:3000", url -> {
                admitted.add(url);
                blocker.hold();
            });
            assertTrue(blocker.entered.await(5, TimeUnit.SECONDS), "the worker must run off-loop");

            // Same URL: coalesced by the URL set.
            queue.enqueue("http://slow.example:3000", admitted::add);
            // Case, trailing dot and trailing slash canonicalize onto the same URL.
            queue.enqueue("http://SLOW.example:3000/", admitted::add);
            // Different port and path — a DIFFERENT canonical URL, but the SAME host, which is the
            // variant that used to queue an unbounded number of resolves for one slow name.
            queue.enqueue("http://slow.example:3001", admitted::add);
            queue.enqueue("http://slow.example:3002/x", admitted::add);

            assertEquals(1, queue.inFlight(), "one slow host occupies exactly one slot");
            blocker.release.countDown();
        }
        assertEquals(List.of("http://slow.example:3000"), admitted);
    }

    @Test
    void onlyAnUncanonicalizableUrlIsShedHere() throws Exception {
        // The queue sheds exactly what it cannot key on: canonicalize returning null or empty.
        // Anything else — including a value that is not a URL at all — is forwarded, because
        // validating a peer address is the registry's job and it happens off-loop by design. The
        // division matters: pre-filtering here would duplicate the registry's rules and drift.
        AtomicInteger admitted = new AtomicInteger();
        try (PeerAdmissionQueue queue = new PeerAdmissionQueue()) {
            queue.enqueue("", url -> admitted.incrementAndGet());
            queue.enqueue(null, url -> admitted.incrementAndGet());
            assertEquals(0, queue.inFlight());
            assertEquals(0, admitted.get(), "an unkeyable URL never reaches the worker");
        }
        List<String> forwarded = Collections.synchronizedList(new java.util.ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        try (PeerAdmissionQueue queue = new PeerAdmissionQueue()) {
            queue.enqueue("http://peer.example:3000", url -> {
                forwarded.add(url);
                done.countDown();
            });
            // close() does not drain by design, so the assertion must wait for the worker itself.
            assertTrue(done.await(5, TimeUnit.SECONDS), "the admission must reach the worker");
        }
        assertEquals(List.of("http://peer.example:3000"), List.copyOf(forwarded));
    }

    @Test
    void thePendingSetsNeverOutgrowTheQueueBound() throws Exception {
        // The set of in-flight URLs must be bounded by the queue, not by the caller: once the queue
        // rejects, the entry has to come back out inline or a flood grows the sets without limit —
        // the same unbounded growth the queue bound exists to stop.
        Blocker blocker = new Blocker();
        try (PeerAdmissionQueue queue = new PeerAdmissionQueue()) {
            queue.enqueue("http://host0.example", url -> blocker.hold());
            assertTrue(blocker.entered.await(5, TimeUnit.SECONDS));

            for (int i = 1; i <= 2_000; i++) {
                queue.enqueue("http://host" + i + ".example", url -> { });
            }
            // 1 running + at most the queue's 256 waiting. The exact figure depends on nothing the
            // caller controls, so assert the bound, not a number.
            assertTrue(queue.inFlight() <= 257,
                "in-flight admissions must stay within the queue bound, was " + queue.inFlight());
            blocker.release.countDown();
        }
    }

    @Test
    void closingStopsTheWorkerThread() throws Exception {
        // The leak this type exists to fix: NodeService held the executor and never shut it down,
        // so every node built left its admission thread running for the life of the JVM.
        // The worker identifies itself, so the assertion is about THIS queue's thread and not
        // about whatever else in the JVM happens to share the name.
        CountDownLatch ran = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Thread> worker = new java.util.concurrent.atomic.AtomicReference<>();
        PeerAdmissionQueue queue = new PeerAdmissionQueue();
        queue.enqueue("http://peer.example", url -> {
            worker.set(Thread.currentThread());
            ran.countDown();
        });
        assertTrue(ran.await(5, TimeUnit.SECONDS));
        assertEquals("rhizome-peer-admit", worker.get().getName());

        queue.close();
        worker.get().join(5_000);
        assertFalse(worker.get().isAlive(), "close() must stop the admission worker");
    }
}
