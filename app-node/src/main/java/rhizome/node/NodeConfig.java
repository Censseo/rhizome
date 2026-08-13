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
@lombok.Builder(toBuilder = true)
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
    Optional<String> apiToken,

    // ---- previously env-only (RHIZOME_*), read from inside the lifecycle methods -------------
    // These ten settings never reached this record: they were pulled straight from the
    // environment deep inside start(), startHttp(), startProducerIfConfigured() and
    // startNetworkLoops(). The consequence was not untidiness — it was that NO test could start a
    // node with a prune depth, snap-sync, protectReads, trustXff, a vote or a peer token, because
    // the only way to set them was to mutate the process environment, which JUnit does not do
    // portably. PrunedNodeApiTest still works around it by hand-building a RocksDbNodeStore and a
    // servlet instead of a node.

    /** RHIZOME_PRUNE: block bodies to retain, 0 = archive. Floor-checked against the params. */
    int keepBlocks,
    /** RHIZOME_ALLOW_OPEN_API: permits a non-loopback bind with no api token (relay posture). */
    boolean allowOpenApi,
    /** RHIZOME_PEER_TOKEN: bearer this node presents to its configured peers. */
    Optional<String> peerToken,
    /** RHIZOME_SYNC=snap: bootstrap from a peer snapshot instead of replaying every block. */
    boolean snapSync,
    /** RHIZOME_PROTECT_READS: gate every route behind the api token, not just the POSTs. */
    boolean protectReads,
    /** RHIZOME_TRUST_XFF: key rate limits on the first X-Forwarded-For hop. DANGEROUS unless the
     *  socket is reachable only from the trusted proxy. */
    boolean trustXff,
    /** RHIZOME_VOTE: this miner's vote on the votable params, in [-2, 2]. */
    int vote,
    /** RHIZOME_SNAPSHOT_EVERY: blocks between state-snapshot materialisations, 0 = never. */
    long snapshotEveryBlocks,
    /** RHIZOME_ALLOWED_HOSTS=off disables the Host allowlist entirely; the pair keeps "disabled"
     *  distinct from "enabled and empty", which one Optional&lt;List&gt; would blur. */
    boolean hostAllowlistEnabled,
    List<String> extraAllowedHosts) {

    public static NodeConfig defaults(NetworkParameters params, String dataDir, int apiPort) {
        return new NodeConfig(params, dataDir, apiPort, Optional.empty(), Optional.empty(),
            List.of(), Optional.empty(), 10_000L, params.desiredBlockTimeSec() * 1000L, 100_000, false,
            "127.0.0.1", Optional.empty(),
            0, false, Optional.empty(), false, false, false, 0, DEFAULT_SNAPSHOT_EVERY,
            true, List.of());
    }

    /** Default cadence of state-snapshot materialisation: about one per day at 5 s blocks. */
    public static final long DEFAULT_SNAPSHOT_EVERY = 17_280L;

    /**
     * The whole configuration from an environment ACCESSOR rather than from {@code System.getenv}.
     *
     * <p>The accessor is the point. Ten of these settings used to be read straight from the
     * process environment deep inside the node's lifecycle methods, so the only way to test a
     * prune depth, snap-sync, protectReads, trustXff, a vote or a peer token was to mutate the
     * environment — which JUnit cannot do portably, and which no test therefore did. Parsing
     * against a function makes every variable, every default and every rejection message a plain
     * table test with no process and no environment mutation.
     *
     * <p>Order matters in one place: the network must resolve first, because the prune floor is
     * checked against its parameters.
     */
    public static NodeConfig fromEnv(java.util.function.UnaryOperator<String> env) {
        NetworkParameters params = parseNetwork(env.apply("RHIZOME_NETWORK"));
        NodeConfig config = defaults(params,
            orDefault(env, "RHIZOME_DATA", "./data"),
            parsePort(orDefault(env, "RHIZOME_PORT", "3000")));

        var b = config.toBuilder();
        present(env, "RHIZOME_SNAPSHOT", v -> b.snapshotPath(Optional.of(v)));
        present(env, "RHIZOME_MINER", v -> b.miner(Optional.of(PublicAddress.of(v))));
        present(env, "RHIZOME_BLOCK_INTERVAL_MS",
            v -> b.blockIntervalMs(parseBlockIntervalMs(v)));
        present(env, "RHIZOME_PEERS", v -> b.peers(java.util.Arrays.stream(v.split(","))
            .map(String::trim).filter(p -> !p.isEmpty()).toList()));
        present(env, "RHIZOME_ADVERTISE", v -> b.advertisedUrl(Optional.of(parseAdvertisedUrl(v))));
        present(env, "RHIZOME_BIND_ADDRESS", v -> b.bindAddress(v.trim()));
        present(env, "RHIZOME_API_TOKEN", v -> b.apiToken(Optional.of(v.trim())));
        present(env, "RHIZOME_PEER_TOKEN", v -> b.peerToken(Optional.of(v.trim())));

        b.keepBlocks(parseKeepBlocks(env.apply("RHIZOME_PRUNE"), params));
        b.allowOpenApi(isTrue(env.apply("RHIZOME_ALLOW_OPEN_API")));
        // OR-ed, not overridden: the record field is the programmatic switch (tests use it) and the
        // variable is the operator switch. Documented as a deliberate double source.
        b.allowPrivatePeers(config.allowPrivatePeers() || isTrue(env.apply("RHIZOME_ALLOW_PRIVATE_PEERS")));
        b.snapSync("snap".equalsIgnoreCase(trimmed(env.apply("RHIZOME_SYNC"))));
        b.protectReads(isTrue(env.apply("RHIZOME_PROTECT_READS")));
        b.trustXff(isTrue(env.apply("RHIZOME_TRUST_XFF")));
        b.vote(parseVote(env.apply("RHIZOME_VOTE")));
        b.snapshotEveryBlocks(parseSnapshotEvery(env.apply("RHIZOME_SNAPSHOT_EVERY")));

        String hosts = trimmed(env.apply("RHIZOME_ALLOWED_HOSTS"));
        if ("off".equalsIgnoreCase(hosts)) {
            b.hostAllowlistEnabled(false);
        } else if (hosts != null && !hosts.isEmpty()) {
            b.extraAllowedHosts(java.util.Arrays.stream(hosts.split(","))
                .map(h -> h.trim().toLowerCase(java.util.Locale.ROOT))
                .filter(h -> !h.isEmpty()).toList());
        }
        return b.build();
    }

    /** The production accessor. */
    public static NodeConfig fromEnv() {
        return fromEnv(System::getenv);
    }

    /** Safety headroom above the deepest history the engine reads, when pruning. */
    private static final int PRUNE_MARGIN = 128;

    /**
     * Retention (in blocks) for this node, from {@code RHIZOME_PRUNE}: absent/0 = archive
     * (keep every body). A positive value must be at least the deepest history the engine may
     * read — the reorg window, uncle depth, and the difficulty/median timestamp windows —
     * plus a safety margin, or the node would prune a body it still needs. Enforced here so a
     * misconfiguration fails fast at boot rather than mid-reorg.
     */
    static int parseKeepBlocks(String env, NetworkParameters params) {
        if (env == null || env.isBlank()) {
            return 0;
        }
        int requested;
        try {
            requested = Integer.parseInt(env.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("RHIZOME_PRUNE must be an integer, was: " + env, e);
        }
        if (requested <= 0) {
            return 0; // archive
        }
        int floor = Math.max(Math.max(params.maxReorgDepth(), params.uncleMaxDepth()),
            Math.max(params.difficultyLookback(), params.medianTimeWindow())) + PRUNE_MARGIN;
        if (requested < floor) {
            throw new IllegalArgumentException("RHIZOME_PRUNE=" + requested
                + " is below the safe floor of " + floor + " blocks (reorg/uncle/difficulty/median windows)");
        }
        return requested;
    }

    /**
     * Parses {@code RHIZOME_VOTE} with a clear error and bounds it to the protocol's vote domain
     * (0 abstain, ±1 storageFeeFactor, ±2 minValuePerByte). An out-of-domain value would either
     * crash the producer thread with a raw {@link NumberFormatException} or mint blocks the
     * consensus gate rejects as {@code INVALID_VOTE} (audit: unvalidated config).
     */
    static int parseVote(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0; // abstain
        }
        final int vote;
        try {
            vote = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("RHIZOME_VOTE must be an integer, was: " + raw, e);
        }
        if (vote < -2 || vote > 2) {
            throw new IllegalArgumentException("RHIZOME_VOTE must be in [-2, 2] "
                + "(0 abstain, ±1 storageFeeFactor, ±2 minValuePerByte), was: " + vote);
        }
        return vote;
    }

    /** Blocks between snapshot materialisations, from {@code RHIZOME_SNAPSHOT_EVERY} (default ~1 day). */
    static long parseSnapshotEvery(String env) {
        if (env == null || env.isBlank()) {
            return DEFAULT_SNAPSHOT_EVERY;
        }
        try {
            return Long.parseLong(env.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("RHIZOME_SNAPSHOT_EVERY must be an integer, was: " + env, e);
        }
    }

    /**
     * Parses {@code RHIZOME_PORT} with a clear error and a range check (audit: unvalidated
     * config — a typo'd port previously died with a raw NumberFormatException stack, or bound
     * a nonsense port).
     */
    static int parsePort(String raw) {
        final int port;
        try {
            port = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("RHIZOME_PORT must be an integer, was: " + raw, e);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("RHIZOME_PORT must be in [1, 65535], was: " + port);
        }
        return port;
    }

    /**
     * Validates {@code RHIZOME_ADVERTISE}: it must be an http(s) URL with a host. A malformed
     * value used to be accepted verbatim and then degrade three things at once, silently
     * (audit I-6): {@link rhizome.net.PeerRegistry}'s self URL (canonicalized to something no peer URL can
     * equal, so the self-pairing refusal stops firing and the node syncs from itself), the
     * address handed to peers by PEX, and the {@code Host} allowlist, which drops it and falls
     * back to the loopback names — locking browsers out of the dashboard over the real hostname.
     */
    static String parseAdvertisedUrl(String raw) {
        String url = raw.trim();
        try {
            java.net.URI uri = java.net.URI.create(url);
            String scheme = uri.getScheme();
            boolean http = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            if (http && uri.getHost() != null && !uri.getHost().isEmpty()) {
                return url;
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("RHIZOME_ADVERTISE must be an http(s) URL with a host "
                + "(e.g. https://node.example:3000), was: " + raw, e);
        }
        throw new IllegalArgumentException("RHIZOME_ADVERTISE must be an http(s) URL with a host "
            + "(e.g. https://node.example:3000), was: " + raw);
    }

    /** Parses {@code RHIZOME_BLOCK_INTERVAL_MS} with a clear error and a positivity check. */
    static long parseBlockIntervalMs(String raw) {
        final long interval;
        try {
            interval = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("RHIZOME_BLOCK_INTERVAL_MS must be an integer, was: " + raw, e);
        }
        if (interval <= 0) {
            throw new IllegalArgumentException("RHIZOME_BLOCK_INTERVAL_MS must be positive, was: " + interval);
        }
        return interval;
    }

    /**
     * Resolves {@code RHIZOME_NETWORK} (absent/blank → mainnet). An unrecognised value is a hard
     * failure, not a fall-through to mainnet: {@code RHIZOME_NETWORK=testnett} used to start the
     * node on MAINNET without a word — wrong chain, wasted mining, mainnet peers dialled — while
     * every other config variable already fails fast on a typo (audit B-4). Fail-unsafe defaults
     * are the one kind of default this node does not get to have.
     */
    static NetworkParameters parseNetwork(String raw) {
        String name = raw == null || raw.isBlank() ? "mainnet" : raw.trim();
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "mainnet" -> NetworkParameters.cleanMainnet();
            case "testnet" -> NetworkParameters.testnet();
            case "devnet" -> NetworkParameters.devnet();
            default -> throw new IllegalArgumentException(
                "RHIZOME_NETWORK must be one of mainnet, testnet, devnet — was: " + raw);
        };
    }

    private static String trimmed(String raw) {
        return raw == null ? null : raw.trim();
    }

    private static boolean isTrue(String raw) {
        return "true".equalsIgnoreCase(trimmed(raw));
    }

    private static String orDefault(java.util.function.UnaryOperator<String> env, String key, String fallback) {
        String raw = env.apply(key);
        return raw == null || raw.isBlank() ? fallback : raw;
    }

    private static void present(java.util.function.UnaryOperator<String> env, String key,
                                java.util.function.Consumer<String> apply) {
        String raw = env.apply(key);
        if (raw != null && !raw.isBlank()) {
            apply.accept(raw);
        }
    }

    /** The URL peers use to reach this node. */
    public String selfUrl() {
        return advertisedUrl.orElse("http://localhost:" + apiPort);
    }

    // Each of these rebuilt all components positionally. At 22 components that is 176 argument
    // positions per file, several same-typed (dataDir and bindAddress are both String,
    // syncPeriodMs and blockIntervalMs both long), so a transposition compiles silently.
    public NodeConfig withMiner(PublicAddress address) {
        return toBuilder().miner(Optional.of(address)).build();
    }

    public NodeConfig withPeers(List<String> peerUrls) {
        return toBuilder().peers(List.copyOf(peerUrls)).build();
    }

    public NodeConfig withSnapshot(String path) {
        return toBuilder().snapshotPath(Optional.of(path)).build();
    }

    public NodeConfig withBlockIntervalMs(long intervalMs) {
        return toBuilder().blockIntervalMs(intervalMs).build();
    }

    public NodeConfig withAdvertisedUrl(String url) {
        return toBuilder().advertisedUrl(Optional.of(url)).build();
    }

    public NodeConfig withAllowPrivatePeers(boolean allow) {
        return toBuilder().allowPrivatePeers(allow).build();
    }

    public NodeConfig withBindAddress(String address) {
        return toBuilder().bindAddress(address).build();
    }

    public NodeConfig withApiToken(String token) {
        return toBuilder().apiToken(Optional.of(token)).build();
    }
}
