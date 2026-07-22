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
    boolean allowPrivatePeers) {

    public static NodeConfig defaults(NetworkParameters params, String dataDir, int apiPort) {
        return new NodeConfig(params, dataDir, apiPort, Optional.empty(), Optional.empty(),
            List.of(), Optional.empty(), 10_000L, params.desiredBlockTimeSec() * 1000L, 100_000, false);
    }

    /** The URL peers use to reach this node. */
    public String selfUrl() {
        return advertisedUrl.orElse("http://localhost:" + apiPort);
    }

    public NodeConfig withMiner(PublicAddress address) {
        return new NodeConfig(params, dataDir, apiPort, snapshotPath, Optional.of(address),
            peers, advertisedUrl, syncPeriodMs, blockIntervalMs, mempoolSize, allowPrivatePeers);
    }

    public NodeConfig withPeers(List<String> peerUrls) {
        return new NodeConfig(params, dataDir, apiPort, snapshotPath, miner,
            peerUrls, advertisedUrl, syncPeriodMs, blockIntervalMs, mempoolSize, allowPrivatePeers);
    }

    public NodeConfig withSnapshot(String path) {
        return new NodeConfig(params, dataDir, apiPort, Optional.of(path), miner,
            peers, advertisedUrl, syncPeriodMs, blockIntervalMs, mempoolSize, allowPrivatePeers);
    }

    public NodeConfig withBlockIntervalMs(long intervalMs) {
        return new NodeConfig(params, dataDir, apiPort, snapshotPath, miner,
            peers, advertisedUrl, syncPeriodMs, intervalMs, mempoolSize, allowPrivatePeers);
    }

    public NodeConfig withAdvertisedUrl(String url) {
        return new NodeConfig(params, dataDir, apiPort, snapshotPath, miner,
            peers, Optional.of(url), syncPeriodMs, blockIntervalMs, mempoolSize, allowPrivatePeers);
    }

    public NodeConfig withAllowPrivatePeers(boolean allow) {
        return new NodeConfig(params, dataDir, apiPort, snapshotPath, miner,
            peers, advertisedUrl, syncPeriodMs, blockIntervalMs, mempoolSize, allow);
    }
}
