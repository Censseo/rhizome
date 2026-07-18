package rhizome.node;

import java.util.List;
import java.util.Optional;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.PublicAddress;

/**
 * Runtime configuration for a {@link RhizomeNode}.
 *
 * @param params        the network (consensus parameters)
 * @param dataDir       RocksDB directory
 * @param apiPort       HTTP API listen port
 * @param snapshotPath  genesis balance snapshot JSON, or empty for a premine-free chain
 * @param miner         if present, the node mines blocks paying this address
 * @param peers         base URLs of peers to periodically sync from
 * @param syncPeriodMs  how often to run a sync round
 * @param mempoolSize   max pooled transactions
 */
public record NodeConfig(
    NetworkParameters params,
    String dataDir,
    int apiPort,
    Optional<String> snapshotPath,
    Optional<PublicAddress> miner,
    List<String> peers,
    long syncPeriodMs,
    long blockIntervalMs,
    int mempoolSize) {

    public static NodeConfig defaults(NetworkParameters params, String dataDir, int apiPort) {
        return new NodeConfig(params, dataDir, apiPort, Optional.empty(), Optional.empty(),
            List.of(), 10_000L, params.desiredBlockTimeSec() * 1000L, 100_000);
    }

    public NodeConfig withMiner(PublicAddress address) {
        return new NodeConfig(params, dataDir, apiPort, snapshotPath, Optional.of(address),
            peers, syncPeriodMs, blockIntervalMs, mempoolSize);
    }

    public NodeConfig withPeers(List<String> peerUrls) {
        return new NodeConfig(params, dataDir, apiPort, snapshotPath, miner,
            peerUrls, syncPeriodMs, blockIntervalMs, mempoolSize);
    }

    public NodeConfig withSnapshot(String path) {
        return new NodeConfig(params, dataDir, apiPort, Optional.of(path), miner,
            peers, syncPeriodMs, blockIntervalMs, mempoolSize);
    }

    public NodeConfig withBlockIntervalMs(long intervalMs) {
        return new NodeConfig(params, dataDir, apiPort, snapshotPath, miner,
            peers, syncPeriodMs, intervalMs, mempoolSize);
    }
}
