package rhizome.node;

import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.activej.http.HttpMethod.GET;
import static io.activej.http.HttpMethod.POST;

/**
 * What each HTTP route costs the node and which cross-cutting checks apply to it — declared once,
 * as data.
 *
 * <p>This policy used to live in five hand-maintained lists of path literals inside the middleware
 * ({@code requestCost}, {@code isConsensusLockRead}, {@code isTokenProtectedRoute},
 * {@code isPeerProtocolRequest}, and a since-removed SPA-shell check), each with a permissive
 * fallthrough. Nothing
 * tied registering a route to classifying it, so a route omitted from a list was not a compile
 * error, not a test failure, and not visible in review — it simply became free, or ungated, or
 * Host-checked on the wrong side. Two of the 41 routes had drifted that way: {@code /boxes} and
 * {@code /tokens} sat at the default cost of 1 while fanning out into ~101 and ~201 consensus-locked
 * store reads.
 *
 * <p>{@code RoutePolicyCompletenessTest} asserts this table and the servlet's routing table describe
 * exactly the same set of routes, so adding a route without classifying it fails the build.
 *
 * <p>Costs are in rate-limiter units. The project's weighting rule is roughly one unit per four
 * bounded store reads, which is where {@code /scan/boxes} gets {@code BOX_SCAN_WINDOW / 4} and
 * {@code /boxes} and {@code /tokens} get 25 and 50. Routes whose work depends on the request carry
 * {@link Cost#DYNAMIC} and are priced by {@code NodeApi.requestCost}.
 */
final class RoutePolicy {

    private RoutePolicy() {
    }

    /** A cross-cutting check the middleware applies to a route. */
    enum Guard {
        /** Gated by RHIZOME_API_TOKEN even when protectReads is off. */
        TOKEN,
        /**
         * Static SPA/docs content: exempt from protectReads, since a navigation carries no
         * bearer. Covers {@code GET /}, {@code /dashboard} and {@code /docs} (no trailing slash —
         * ActiveJ hangs a wildcard route off the node itself, so both reach the same static
         * handler as the {@code /dashboard/*}/{@code /docs/*} trees), and the asset trees
         * themselves.
         */
        SPA_SHELL,
        /** Exempt from the Host allowlist — peers legitimately send arbitrary Host headers. */
        PEER_PROTOCOL,
        /** Charged against the process-wide consensus-lock read budget. */
        READ_BUDGET,
        /** Charged against the aggregate submit budget. */
        SUBMIT_BUDGET,
        /** Charged against the aggregate mempool-signature budget. */
        MEMPOOL_BUDGET,
        /** Subject to the push-abuse early shed. */
        PUSH_SHED,
    }

    /** Flat rate-limit cost, or {@link #DYNAMIC} when the request itself determines it. */
    static final class Cost {
        static final int DYNAMIC = -1;

        private Cost() {
        }
    }

    record Route(HttpMethod method, String path, int cost, Set<Guard> guards) {
        Route {
            guards = guards.isEmpty() ? Set.of() : EnumSet.copyOf(guards);
        }

        boolean has(Guard g) {
            return guards.contains(g);
        }

        /** "/dashboard/*" matches "/dashboard" and everything under it. */
        boolean wildcard() {
            return path.endsWith("/*");
        }

        String base() {
            return wildcard() ? path.substring(0, path.length() - 2) : path;
        }
    }

    private static Route get(String path, int cost, Guard... guards) {
        return new Route(GET, path, cost, guards.length == 0 ? Set.of() : EnumSet.of(guards[0], guards));
    }

    private static Route post(String path, int cost, Guard... guards) {
        return new Route(POST, path, cost, guards.length == 0 ? Set.of() : EnumSet.of(guards[0], guards));
    }

    /**
     * Every route the node serves. Order is the servlet's registration order, purely for reading;
     * lookup is by (method, path).
     */
    static final List<Route> ROUTES = List.of(
        get("/", 1, Guard.SPA_SHELL),
        get("/dashboard", 1, Guard.SPA_SHELL),
        get("/dashboard/*", 1, Guard.SPA_SHELL),
        get("/docs/*", 1, Guard.SPA_SHELL),
        get("/stats", DashboardApi.STATS_WINDOW, Guard.READ_BUDGET),
        get("/features", 1),
        get("/blocks", Cost.DYNAMIC, Guard.READ_BUDGET, Guard.PEER_PROTOCOL),
        get("/block", 1, Guard.READ_BUDGET, Guard.PEER_PROTOCOL),
        get("/transaction", Cost.DYNAMIC, Guard.READ_BUDGET),
        get("/address_txs", Cost.DYNAMIC, Guard.READ_BUDGET),
        get("/contract", 1),
        get("/wallet", 1),
        get("/block_count", 1, Guard.PEER_PROTOCOL),
        get("/total_work", 1, Guard.PEER_PROTOCOL),
        get("/difficulty", 1),
        get("/mempool", 1),
        get("/info", 1, Guard.PEER_PROTOCOL),
        get("/peers", 1, Guard.PEER_PROTOCOL),
        post("/add_peer", NodeApi.ADD_PEER_COST, Guard.TOKEN, Guard.PEER_PROTOCOL),
        get("/box", 1),
        get("/boxes", NodeApi.BOXES_COST, Guard.READ_BUDGET),
        post("/scan/register", 1, Guard.TOKEN),
        post("/scan/deregister", 1, Guard.TOKEN),
        get("/scan/list", 1),
        // Weighted but NOT read-gated on purpose: ChainEngine.scanBoxes runs outside the consensus
        // lock behind the stamp seqlock, so it contends nothing that budget protects.
        get("/scan/boxes", NodeService.BOX_SCAN_WINDOW / 4),
        get("/token", 1),
        get("/token_balance", 1),
        get("/tokens", NodeApi.TOKENS_COST, Guard.READ_BUDGET),
        get("/state", 1),
        get("/state/proof", 1),
        get("/state/snapshot/info", 1, Guard.PEER_PROTOCOL),
        get("/state/snapshot/chunk", Cost.DYNAMIC, Guard.READ_BUDGET, Guard.PEER_PROTOCOL),
        // Weighted by its bounded cursor scan, ungated for the same reason as /scan/boxes: these
        // are per-height map reads, not lock-guarded block decodes.
        get("/logs", Cost.DYNAMIC),
        get("/logs/stream", 1),
        post("/call_readonly", NodeApi.CALL_READONLY_COST, Guard.TOKEN),
        get("/sync", Cost.DYNAMIC, Guard.READ_BUDGET, Guard.PEER_PROTOCOL),
        get("/headers", Cost.DYNAMIC, Guard.READ_BUDGET, Guard.PEER_PROTOCOL),
        get("/orphan", NodeApi.ORPHAN_COST, Guard.READ_BUDGET, Guard.PEER_PROTOCOL),
        post("/add_transaction_json", NodeApi.TX_SUBMIT_COST,
            Guard.TOKEN, Guard.PUSH_SHED, Guard.MEMPOOL_BUDGET, Guard.PEER_PROTOCOL),
        post("/add_transaction", NodeApi.TX_SUBMIT_COST,
            Guard.TOKEN, Guard.PUSH_SHED, Guard.MEMPOOL_BUDGET, Guard.PEER_PROTOCOL),
        post("/submit", NodeApi.SUBMIT_COST,
            Guard.TOKEN, Guard.PUSH_SHED, Guard.SUBMIT_BUDGET, Guard.PEER_PROTOCOL));

    private static final Map<String, Map<HttpMethod, Route>> EXACT = index();
    private static final List<Route> WILDCARDS = ROUTES.stream()
        .filter(Route::wildcard)
        // Longest prefix first, so a nested wildcard would win over its ancestor.
        .sorted((a, b) -> Integer.compare(b.base().length(), a.base().length()))
        .toList();

    private static Map<String, Map<HttpMethod, Route>> index() {
        Map<String, Map<HttpMethod, Route>> byPath = new LinkedHashMap<>();
        for (Route r : ROUTES) {
            if (!r.wildcard()) {
                byPath.computeIfAbsent(r.path(), p -> new java.util.EnumMap<>(HttpMethod.class))
                    .put(r.method(), r);
            }
        }
        return byPath;
    }

    /**
     * The route a request will be dispatched to, or {@code null} when nothing matches.
     *
     * <p>{@code path} must be a {@code NodeApi.routingKey}, i.e. the decoded path the ActiveJ
     * router itself matches on — not {@code HttpRequest.getPath()}.
     */
    static Route lookup(HttpMethod method, String path) {
        if (path == null) {
            return null;
        }
        Map<HttpMethod, Route> byMethod = EXACT.get(path);
        if (byMethod != null && byMethod.containsKey(method)) {
            return byMethod.get(method);
        }
        for (Route r : WILDCARDS) {
            if (r.method() == method && (path.equals(r.base()) || path.startsWith(r.base() + "/"))) {
                return r;
            }
        }
        return null;
    }

    static Route lookup(HttpRequest request, String path) {
        return lookup(request.getMethod(), path);
    }

    /** Whether {@code route} carries {@code guard}; an unmatched route carries none. */
    static boolean guarded(Route route, Guard guard) {
        return route != null && route.has(guard);
    }
}
