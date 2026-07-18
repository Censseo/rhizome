package rhizome.node;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The node's live set of known peer base URLs. Thread-safe and bounded; never
 * contains this node's own advertised URL. Feeds sync, gossip and discovery.
 */
public final class PeerRegistry {

    private final String self;
    private final int maxPeers;
    private final Set<String> peers = ConcurrentHashMap.newKeySet();

    public PeerRegistry(String selfUrl, int maxPeers) {
        this.self = normalize(selfUrl);
        this.maxPeers = maxPeers;
    }

    static String normalize(String url) {
        if (url == null) {
            return null;
        }
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /** Adds a peer if it is a well-formed URL, not ourselves, and we're under capacity. */
    public boolean add(String url) {
        String u = normalize(url);
        if (u == null || u.isEmpty() || !u.startsWith("http") || u.equals(self)) {
            return false;
        }
        if (peers.size() >= maxPeers && !peers.contains(u)) {
            return false;
        }
        return peers.add(u);
    }

    public void addAll(Iterable<String> urls) {
        for (String url : urls) {
            add(url);
        }
    }

    public void remove(String url) {
        peers.remove(normalize(url));
    }

    public List<String> snapshot() {
        return List.copyOf(peers);
    }

    public int size() {
        return peers.size();
    }

    public String self() {
        return self;
    }
}
