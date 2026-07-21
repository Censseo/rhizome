package rhizome.core.blockchain;

import java.util.List;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.TransactionKind;

/**
 * Runs contract transactions for the {@link Executor}, without touching the ledger
 * (the executor applies the gas fee and value transfer via its own rolled-back
 * ledger ops). Implemented in the VM module, so consensus depends only on this
 * interface — never on the WASM runtime.
 *
 * <p>Contract state writes are staged in a per-block <em>session</em>: {@link #begin()}
 * opens it, {@link #run} accumulates the successful calls' writes, and the executor
 * ends the block with exactly one of {@link #commit()} (block accepted) or
 * {@link #discard()} (block rejected), so contract state moves atomically with the
 * block.
 */
public interface ContractProcessor {

    /** Opens a fresh state session for one block. */
    void begin();

    /**
     * Executes one contract transaction against the open session. Must not mutate the
     * ledger. A revert or out-of-gas is reported via {@link ContractResult#success()}
     * (false) with the gas actually consumed — it does not throw.
     *
     * @param nonce the sender's account nonce, used to derive a DEPLOY address
     */
    ContractResult run(PublicAddress from, TransactionKind kind, PublicAddress to,
                       byte[] data, long value, long gasLimit, long nonce);

    /**
     * Persists the session (block accepted) and records an undo journal for
     * {@code blockHeight}, so the block's contract-state changes can be reverted
     * later on a reorg.
     */
    void commit(long blockHeight);

    /** Drops the session (block rejected), committing nothing. */
    void discard();

    /**
     * Undoes the contract-state changes committed for {@code blockHeight}, restoring
     * the exact pre-block state. Called by the engine when a block is popped in a
     * reorg. A height with no recorded journal is a no-op.
     */
    void revertBlock(long blockHeight);

    /**
     * Per-contract-transaction receipts for {@code blockHeight}, in block order — the
     * runtime facts (gas used, whether it succeeded) the ledger cannot re-derive from
     * the transaction alone, needed to reverse a contract tx's gas fee and value
     * transfer on a reorg. Empty for a height with no contract transactions.
     */
    List<ContractReceipt> receipts(long blockHeight);

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
    default ContractResult dryRun(PublicAddress from, PublicAddress to, byte[] input,
                                  long value, long gasLimit) {
        throw new UnsupportedOperationException("dry-run not supported");
    }

    /** Contract code/storage writes committed by {@code blockHeight}, for the authenticated state root. */
    default List<ContractChange> changes(long blockHeight) {
        return List.of();
    }

    /**
     * One committed contract write with its final value. {@code code} distinguishes a code
     * write (deploy — {@code key} null) from a storage write. Contracts never delete forward
     * (a storage write of empty bytes is a value, not a deletion), so {@code value} is non-null.
     */
    record ContractChange(boolean code, PublicAddress contract, byte[] key, byte[] value) {}

    /**
     * A native-coin (PDN) transfer a contract made out of its own balance via the {@code
     * transfer_value} host function — e.g. a launchpad paying its creator the sale proceeds. The
     * VM records the intent (affordability checked live against the contract's committed balance);
     * the executor moves the value on success and reverses it on a reorg.
     */
    record NativeTransfer(PublicAddress from, PublicAddress to, long amount) {}

    /** Reads a contract's committed native balance, so the VM can bound {@code transfer_value}. */
    @FunctionalInterface
    interface NativeBalance {
        long balanceOf(PublicAddress address);
    }

    /**
     * Wires the committed-balance source the VM uses to bound {@code transfer_value} (a contract
     * cannot pay out more native coin than it holds). Called once at engine assembly. Processors
     * with no native-transfer support ignore it.
     */
    default void useNativeBalance(NativeBalance source) { }

    /** Runtime outcome of one contract transaction, recorded for reorg reversal. */
    record ContractReceipt(long gasUsed, boolean success, List<NativeTransfer> transfers) {
        public ContractReceipt(long gasUsed, boolean success) {
            this(gasUsed, success, List.of());
        }
    }

    /** One event a contract emitted: the emitting contract, an indexable topic, and data. */
    record ContractLog(PublicAddress contract, byte[] topic, byte[] data) {}

    /**
     * Outcome of one contract execution. {@code gasUsed} is charged regardless of
     * success; {@code contractAddress} is the new address for a DEPLOY (null for CALL);
     * {@code logs} are the events it emitted (empty unless it succeeded).
     */
    record ContractResult(boolean success, long gasUsed, byte[] output,
                          PublicAddress contractAddress, String error, List<ContractLog> logs,
                          List<NativeTransfer> transfers) {

        public static ContractResult ok(long gasUsed, byte[] output, PublicAddress contractAddress) {
            return new ContractResult(true, gasUsed, output, contractAddress, null, List.of(), List.of());
        }

        public static ContractResult ok(long gasUsed, byte[] output, PublicAddress contractAddress,
                                        List<ContractLog> logs) {
            return new ContractResult(true, gasUsed, output, contractAddress, null, List.copyOf(logs), List.of());
        }

        public static ContractResult ok(long gasUsed, byte[] output, PublicAddress contractAddress,
                                        List<ContractLog> logs, List<NativeTransfer> transfers) {
            return new ContractResult(true, gasUsed, output, contractAddress, null,
                List.copyOf(logs), List.copyOf(transfers));
        }

        public static ContractResult reverted(long gasUsed, String error) {
            return new ContractResult(false, gasUsed, new byte[0], null, error, List.of(), List.of());
        }
    }
}
