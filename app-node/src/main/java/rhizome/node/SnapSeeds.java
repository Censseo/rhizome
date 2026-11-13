package rhizome.node;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which seeds a snap-sync joiner may adopt state from, and what work each must prove first. A
 * single seed whose snapshot merely verified used to be adopted outright, so one malicious (or
 * man-in-the-middled) seed could hand a joiner a fabricated ledger. The plan removes that: https
 * only (unless the operator opts in for a local testnet), at least two reachable seeds unless
 * exactly one is configured, and each candidate must prove — see {@link SnapshotBootstrap} — the
 * work the best OTHER reachable seed reports.
 */
final class SnapSeeds {

    private static final Logger log = LoggerFactory.getLogger(SnapSeeds.class);

    /** Seeds probed at most; a longer list is truncated (each probe is a bounded scalar fetch). */
    static final int MAX_PROBED_SEEDS = 8;

    /** One seed to try, and the work its validated chain must reach (tolerance applied by the bootstrap). */
    record Attempt(String url, BigInteger requiredWork) {}

    private SnapSeeds() {}

    /**
     * Builds the ordered attempt list, or an empty list when snap must be refused (the node then
     * full-syncs). {@code workProbe} reads a seed's {@code /total_work} and may throw for an
     * unreachable one; every call is bounded by the transport's own scalar deadline.
     */
    static List<Attempt> plan(List<String> configured, boolean allowHttp,
                              Function<String, BigInteger> workProbe) {
        List<String> usable = new ArrayList<>();
        for (String url : configured) {
            if (!allowHttp && !url.regionMatches(true, 0, "https://", 0, 8)) {
                log.warn("Snap bootstrap: refusing plaintext seed {} (https only; set "
                    + "RHIZOME_SNAP_ALLOW_HTTP=true for a local testnet)", url);
                continue;
            }
            usable.add(url);
        }
        if (usable.isEmpty()) {
            return List.of();
        }
        boolean lone = configured.size() == 1;
        if (lone) {
            log.warn("Snap bootstrap with ONE configured seed: nothing corroborates its chain. A "
                + "malicious or compromised seed could serve a fabricated ledger. Configure at least "
                + "two independent seeds in RHIZOME_PEERS.");
        } else if (usable.size() < 2) {
            log.warn("Snap bootstrap needs at least two usable seeds, have {} of {} configured; "
                + "falling back to full sync", usable.size(), configured.size());
            return List.of();
        }

        List<Attempt> reachable = new ArrayList<>();
        List<BigInteger> work = new ArrayList<>();
        for (String url : usable.subList(0, Math.min(usable.size(), MAX_PROBED_SEEDS))) {
            try {
                BigInteger w = workProbe.apply(url);
                if (w != null) {
                    reachable.add(new Attempt(url, w));
                    work.add(w);
                }
            } catch (RuntimeException e) {
                log.warn("Snap bootstrap: seed {} unreachable for /total_work: {}", url, e.toString());
            }
        }
        if (!lone && reachable.size() < 2) {
            log.warn("Snap bootstrap needs at least two reachable seeds, {} answered; falling back to "
                + "full sync", reachable.size());
            return List.of();
        }

        // Highest reported work first; each candidate's bar is the best OTHER seed's report.
        List<Attempt> ordered = new ArrayList<>(reachable);
        ordered.sort(Comparator.comparing(Attempt::requiredWork).reversed());
        List<Attempt> plan = new ArrayList<>();
        for (Attempt candidate : ordered) {
            BigInteger bar = BigInteger.ZERO;
            for (Attempt other : reachable) {
                if (other != candidate && other.requiredWork().compareTo(bar) > 0) {
                    bar = other.requiredWork();
                }
            }
            plan.add(new Attempt(candidate.url(), bar));
        }
        return plan;
    }
}
