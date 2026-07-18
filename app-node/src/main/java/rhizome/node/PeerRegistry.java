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
    private final PeerBanList banList;
    private final Set<String> peers = ConcurrentHashMap.newKeySet();

    public PeerRegistry(String selfUrl, int maxPeers) {
        this(selfUrl, maxPeers, null);
    }

    public PeerRegistry(String selfUrl, int maxPeers, PeerBanList banList) {
        this.self = normalize(selfUrl);
        this.maxPeers = maxPeers;
        this.banList = banList;
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

    /**
     * Adds a peer if it is a well-formed URL, not ourselves, not banned, and
     * we're under capacity. This is the single admission choke point, so a banned
     * peer cannot be re-introduced via config, /add_peer, or PEX.
     */
    public boolean add(String url) {
        String u = normalize(url);
        if (u == null || u.isEmpty() || !u.startsWith("http") || u.equals(self)) {
            return false;
        }
        if (banList != null && banList.isBanned(u)) {
            return false;
        }
        if (peers.size() >= maxPeers && !peers.contains(u)) {
            return false;
        }
        return peers.add(u);
    }

    /** Records misbehaviour; if it tips the peer over the ban threshold, evicts it. */
    public boolean penalize(String url, int points) {
        if (banList == null) {
            return false;
        }
        boolean banned = banList.misbehave(url, points);
        if (banned) {
            remove(url);
        }
        return banned;
    }

    public boolean isBanned(String url) {
        return banList != null && banList.isBanned(url);
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
