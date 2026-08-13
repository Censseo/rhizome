package rhizome.node;

import java.util.List;
import java.util.function.Function;
import java.util.function.LongFunction;

import rhizome.core.blockchain.ContractApi;
import rhizome.core.box.BoxProcessor;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.token.TokenProcessor;
import rhizome.net.PeerRegistry;
import rhizome.core.blockchain.ContractApi.ContractLog;

/**
 * The read-side collaborators a {@link NodeService} serves from: the peer set, the per-height
 * event and log sources, the contract domain's dashboard audience (dry-runs), and the snapshot
 * service.
 *
 * <p>These were seven {@code volatile} fields with seven setters, which said something untrue
 * about them: that they change while the node runs. They do not — every one is written once,
 * during assembly, and never again. The {@code volatile} was there to publish a field the
 * constructor could not set, and the setters existed because the assembly was a sequence of
 * statements rather than a value. Now that {@code RhizomeNode.assemble} builds a graph and returns
 * it, they can be what they always were: constructor arguments.
 *
 * <p>Every component is nullable and every consumer already handles absence — a node with no
 * contract VM, no boxes or no snapshot export is a legal configuration, not a half-built one, and
 * {@link #none()} is what a test that cares about none of them passes. {@code snapshots} is the one
 * exception in form only: {@link NodeService} normalises a null to {@link SnapshotService#none},
 * which answers "no snapshot" to everything, so the three snapshot calls need no null check.
 */
@lombok.Builder(toBuilder = true)
public record NodeSources(
    PeerRegistry peers,
    LongFunction<List<ContractLog>> logSource,
    Function<PublicAddress, byte[]> codeSource,
    LongFunction<List<BoxProcessor.BoxEvent>> boxEventSource,
    LongFunction<List<TokenProcessor.TokenEvent>> tokenEventSource,
    ContractApi contracts,
    SnapshotService snapshots) {

    /** No sources at all: the shape a test that only exercises the chain endpoints needs. */
    public static NodeSources none() {
        return NodeSources.builder().build();
    }
}
