package rhizome.node;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Peer exchange (PEX): periodically asks each known peer for its peer list
 * (merging new ones) and announces this node to them. Peers that fail repeatedly
 * are pruned. This lets the network self-organise from a few seed peers instead
 * of a hard-coded list.
 */
public final class PeerDiscovery {

    private static final Logger log = LoggerFactory.getLogger(PeerDiscovery.class);
    private static final int MAX_FAILURES = 3;

    private final PeerRegistry registry;
    private final String selfUrl;
    private final HttpClient http;
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();

    public PeerDiscovery(PeerRegistry registry, String selfUrl) {
        this.registry = registry;
        this.selfUrl = selfUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /** One discovery round across all known peers. */
    public void round() {
        for (String peer : registry.snapshot()) {
            try {
                registry.addAll(fetchPeers(peer));
                announceTo(peer);
                failures.remove(peer);
            } catch (Exception e) {
                int f = failures.merge(peer, 1, Integer::sum);
                if (f >= MAX_FAILURES) {
                    registry.remove(peer);
                    failures.remove(peer);
                    log.debug("dropped unreachable peer {}", peer);
                }
            }
        }
    }

    private List<String> fetchPeers(String peer) throws Exception {
        HttpResponse<String> resp = http.send(
            HttpRequest.newBuilder(URI.create(peer + "/peers")).timeout(Duration.ofSeconds(10)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("/peers -> " + resp.statusCode());
        }
        JSONArray arr = new JSONObject(resp.body()).getJSONArray("peers");
        return arr.toList().stream().map(Object::toString).toList();
    }

    private void announceTo(String peer) throws Exception {
        if (selfUrl == null || selfUrl.isEmpty()) {
            return;
        }
        String body = new JSONObject().put("url", selfUrl).toString();
        http.send(
            HttpRequest.newBuilder(URI.create(peer + "/add_peer"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.discarding());
    }
}
