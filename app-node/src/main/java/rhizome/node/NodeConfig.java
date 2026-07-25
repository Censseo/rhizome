package rhizome.node;

import java.util.List;
import java.util.Optional;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.PublicAddress;

/**
 * Runtime configuration for a {@link RhizomeNode}.
 *
 * @param params         the network (consensus parameters)
 * @param dataDir        RocksDB directory
 * @param apiPort        HTTP API listen port
 * @param snapshotPath   genesis balance snapshot JSON, or empty for a premine-free chain
 * @param miner          if present, the node mines blocks paying this address
 * @param peers          seed peer base URLs to bootstrap discovery from
 * @param advertisedUrl  how peers should reach this node (empty → http://localhost:apiPort)
 * @param syncPeriodMs   how often to run a sync/discovery round
 * @param blockIntervalMs producer pacing target
 * @param mempoolSize    max pooled transactions
 * @param allowPrivatePeers  opt out of the SSRF host filter so the node may peer over loopback /
 *                       private IPs discovered via PEX (local dev / devnets). Off by default —
 *                       secure-by-default (audit F4); the env var RHIZOME_ALLOW_PRIVATE_PEERS=true
 *                       also forces it. Configured seed peers bypass the filter regardless.
 * @param bindAddress    HTTP API bind address (env RHIZOME_BIND_ADDRESS). Default 127.0.0.1 —
 *                       secure-by-default (audit H-2): peering still works for outbound sync, and a
 *                       public-facing node must opt in explicitly (bind 0.0.0.0 AND set
 *                       RHIZOME_API_TOKEN, or RHIZOME_ALLOW_OPEN_API=true for a pure relay).
 * @param apiToken       optional bearer token (env RHIZOME_API_TOKEN): when present, the
 *                       state-changing/operator routes require
 *                       {@code Authorization: Bearer <token>} (audit F4). Empty = open API
 *                       (loopback binds only — see {@code bindAddress}).
 */
public record NodeConfig(
    NetworkParameters params,
    String dataDir,
    int apiPort,
    Optional<String> snapshotPath,
    Optional<PublicAddress> miner,
    List<String> peers,
    Optional<String> advertisedUrl,
    long syncPeriodMs,
    long blockIntervalMs,
    int mempoolSize,
    boolean allowPrivatePeers,
    String bindAddress,
    Optional<String> apiToken) {

    public static NodeConfig defaults(NetworkParameters params, String dataDir, int apiPort) {
        return new NodeConfig(params, dataDir, apiPort, Optional.empty(), Optional.empty(),
            List.of(), Optional.empty(), 10_000L, params.desiredBlockTimeSec() * 1000L, 100_000, false,
            "127.0.0.1", Optional.empty());
    }

    /** The URL peers use to reach this node. */
    public String selfUrl() {
        return advertisedUrl.orElse("http://localhost:" + apiPort);
    }

    public NodeConfig withMiner(PublicAddress address) {
        return new NodeConfig(params, dataDir, apiPort, snapshotPath, Optional.of(address),
            peers, advertisedUrl, syncPeriodMs, blockIntervalMs, mempoolSize, allowPrivatePeers,
            bindAddress, apiToken);
    }

    public NodeConfig withPeers(List<String> peerUrls) {
        return new NodeConfig(params, dataDir, apiPort, snapshotPath, miner,
            peerUrls, advertisedUrl, syncPeriodMs, blockIntervalMs, mempoolSize, allowPrivatePeers,
            bindAddress, apiToken);
    }

    public NodeConfig withSnapshot(String path) {
        return new NodeConfig(params, dataDir, apiPort, Optional.of(path), miner,
            peers, advertisedUrl, syncPeriodMs, blockIntervalMs, mempoolSize, allowPrivatePeers,
            bindAddress, apiToken);
    }

    public NodeConfig withBlockIntervalMs(long intervalMs) {
        return new NodeConfig(params, dataDir, apiPort, snapshotPath, miner,
            peers, advertisedUrl, syncPeriodMs, intervalMs, mempoolSize, allowPrivatePeers,
            bindAddress, apiToken);
    }

    public NodeConfig withAdvertisedUrl(String url) {
        return new NodeConfig(params, dataDir, apiPort, snapshotPath, miner,
            peers, Optional.of(url), syncPeriodMs, blockIntervalMs, mempoolSize, allowPrivatePeers,
            bindAddress, apiToken);
    }

    public NodeConfig withAllowPrivatePeers(boolean allow) {
        return new NodeConfig(params, dataDir, apiPort, snapshotPath, miner,
            peers, advertisedUrl, syncPeriodMs, blockIntervalMs, mempoolSize, allow,
            bindAddress, apiToken);
    }

    public NodeConfig withBindAddress(String address) {
        return new NodeConfig(params, dataDir, apiPort, snapshotPath, miner,
            peers, advertisedUrl, syncPeriodMs, blockIntervalMs, mempoolSize, allowPrivatePeers,
            address, apiToken);
    }

    public NodeConfig withApiToken(String token) {
        return new NodeConfig(params, dataDir, apiPort, snapshotPath, miner,
            peers, advertisedUrl, syncPeriodMs, blockIntervalMs, mempoolSize, allowPrivatePeers,
            bindAddress, Optional.of(token));
    }
}
