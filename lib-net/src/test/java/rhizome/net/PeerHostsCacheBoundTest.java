package rhizome.net;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.UnknownHostException;

import org.junit.jupiter.api.Test;

/**
 * The DNS resolution cache must stay bounded. Its key is an attacker-influenced hostname (peers
 * arrive via the unauthenticated {@code /add_peer} and PEX, each resolved before the capacity/dedup
 * checks), so an unbounded cache would let a stream of distinct resolvable names accumulate one
 * permanent entry each and exhaust the heap — a regression of the "every unbounded surface is capped"
 * invariant. This drives many distinct dotted-quad literals (parsed without any DNS lookup) through
 * the resolver and asserts the cache does not grow past its cap.
 */
class PeerHostsCacheBoundTest {

    @Test
    void dnsCacheStaysBoundedUnderManyDistinctHosts() {
        int distinct = 6_000; // comfortably above the 4096 cap
        for (int i = 0; i < distinct; i++) {
            String literal = "10." + ((i >> 16) & 0xFF) + "." + ((i >> 8) & 0xFF) + "." + (i & 0xFF);
            try {
                PeerHosts.resolveAll(literal); // an IPv4 literal is parsed directly, no network lookup
            } catch (UnknownHostException e) {
                // A dotted-quad literal never fails to resolve; ignore defensively.
            }
        }
        int cached = PeerHosts.cachedEntryCount();
        assertTrue(cached <= 4_096,
            "DNS cache grew unbounded: " + cached + " entries after " + distinct + " distinct hosts");
        assertTrue(cached < distinct, "cache should have evicted, not retained every distinct host");
    }
}
