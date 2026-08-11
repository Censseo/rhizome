package rhizome.node;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every route the servlet registers must be classified in {@link RoutePolicy}, and vice versa.
 *
 * <p>The node's request policy — cost, bearer gate, aggregate budgets, Host allowlist side — used
 * to live in five hand-maintained lists of path literals, each with a permissive fallthrough.
 * Nothing connected registering a route to classifying it, so an omission was not a compile error,
 * not a test failure and not visible in review: the route simply became free, or ungated, or
 * Host-checked on the wrong side. Two of the 41 had already drifted that way.
 *
 * <p>The ideal guarantee is a compile error, which needs the handlers themselves to live in the
 * table. Short of that, this test is the tripwire: it reads the servlet's own {@code .with(...)}
 * registrations out of the source and compares them to the table, so a route added without a cost
 * and a guard set fails the build.
 */
class RoutePolicyCompletenessTest {

    private static final Pattern REGISTRATION =
        Pattern.compile("\\.with\\((GET|POST), \"([^\"]+)\"");

    private static Set<String> registeredRoutes() throws Exception {
        Path source = Path.of("src/main/java/rhizome/node/NodeApi.java");
        assertTrue(Files.exists(source), "NodeApi source must be readable from the module directory");
        Matcher m = REGISTRATION.matcher(Files.readString(source));
        Set<String> routes = new LinkedHashSet<>();
        while (m.find()) {
            routes.add(m.group(1) + " " + m.group(2));
        }
        return routes;
    }

    private static Set<String> classifiedRoutes() {
        Set<String> routes = new LinkedHashSet<>();
        for (RoutePolicy.Route r : RoutePolicy.ROUTES) {
            routes.add(r.method().name() + " " + r.path());
        }
        return routes;
    }

    @Test
    void everyRegisteredRouteIsClassified() throws Exception {
        Set<String> registered = registeredRoutes();
        Set<String> classified = classifiedRoutes();

        Set<String> unclassified = new LinkedHashSet<>(registered);
        unclassified.removeAll(classified);
        assertEquals(Set.of(), unclassified,
            "these routes are served but carry no cost or guard declaration — they default to "
            + "cost 1, no bearer gate and no aggregate budget");

        Set<String> orphaned = new LinkedHashSet<>(classified);
        orphaned.removeAll(registered);
        assertEquals(Set.of(), orphaned,
            "these routes are classified but no longer served — stale policy hides which rules "
            + "are live");
    }

    @Test
    void theRouteCountIsWhatWeThinkItIs() throws Exception {
        // A blunt tripwire for a bulk edit that adds or drops routes wholesale.
        assertEquals(41, registeredRoutes().size());
        assertEquals(41, RoutePolicy.ROUTES.size());
    }

    @Test
    void guardMembershipMatchesTheDocumentedPosture() {
        // Transcribed from the pre-refactor predicates. These counts are the security posture:
        // if one changes, it changes because someone decided to change it.
        assertEquals(15, count(RoutePolicy.Guard.PEER_PROTOCOL),
            "the P2P surface exempt from the Host allowlist");
        assertEquals(11, count(RoutePolicy.Guard.READ_BUDGET),
            "9 original consensus-lock reads plus /boxes and /tokens");
        assertEquals(7, count(RoutePolicy.Guard.TOKEN),
            "exactly the POST routes; reads are gated only under protectReads");
        assertEquals(4, count(RoutePolicy.Guard.SPA_SHELL),
            "the static shell a browser navigation cannot bear a token for");
        assertEquals(3, count(RoutePolicy.Guard.PUSH_SHED), "the two tx ingests plus /submit");
    }

    @Test
    void everyTokenGuardedRouteIsAPost() {
        // Reads are public by design unless protectReads is on; a GET landing in the TOKEN set
        // would silently close the explorer for every unauthenticated client.
        for (RoutePolicy.Route r : RoutePolicy.ROUTES) {
            if (r.has(RoutePolicy.Guard.TOKEN)) {
                assertEquals(io.activej.http.HttpMethod.POST, r.method(),
                    r.path() + " is bearer-gated, so it must be a state-changing POST");
            }
        }
    }

    private static long count(RoutePolicy.Guard guard) {
        return RoutePolicy.ROUTES.stream().filter(r -> r.has(guard)).count();
    }
}
