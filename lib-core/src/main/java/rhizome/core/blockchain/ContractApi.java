package rhizome.core.blockchain;

import java.util.List;

import rhizome.core.ledger.PublicAddress;

/**
 * The dashboard audience of the contract domain: the read-only surface the node serves —
 * deployed-code lookup, the per-height event logs, and read-only dry-run calls. Declared
 * apart from {@link ContractProcessor} so the API layer ({@code NodeSources} /
 * {@code NodeService}) depends on queries, never on execution, and a processor-free node
 * answers absence through the defaults instead of a null.
 */
public interface ContractApi {

    /** Deployed code at {@code contract} in the committed state, or {@code null}. */
    default byte[] codeAt(PublicAddress contract) {
        return null;
    }

    /**
     * Event logs emitted by {@code blockHeight}'s contract transactions, in block
     * order — the channel autonomous agents watch to react to on-chain state. Empty
     * for a height with no contract logs. Dropped when the block is reverted.
     */
    default List<ContractLog> logs(long blockHeight) {
        return List.of();
    }

    /**
     * Executes a read-only CALL against committed state and discards all writes — a
     * dry run for querying contract state off-chain. Never mutates the store or the
     * block session, so it is safe to call concurrently with block processing. Returns
     * the would-be output, gas and logs; not wired to the ledger (no value actually
     * moves). Default: unsupported.
     */
    default ContractProcessor.ContractResult dryRun(PublicAddress from, PublicAddress to, byte[] input,
                                                    long value, long gasLimit) {
        throw new UnsupportedOperationException("dry-run not supported");
    }

    /** One event a contract emitted: the emitting contract, an indexable topic, and data. */
    record ContractLog(PublicAddress contract, byte[] topic, byte[] data) {}
}
