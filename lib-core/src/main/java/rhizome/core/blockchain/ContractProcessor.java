package rhizome.core.blockchain;

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

    /** Persists the session (block accepted). */
    void commit();

    /** Drops the session (block rejected). */
    void discard();

    /**
     * Outcome of one contract execution. {@code gasUsed} is charged regardless of
     * success; {@code contractAddress} is the new address for a DEPLOY (null for CALL).
     */
    record ContractResult(boolean success, long gasUsed, byte[] output,
                          PublicAddress contractAddress, String error) {

        public static ContractResult ok(long gasUsed, byte[] output, PublicAddress contractAddress) {
            return new ContractResult(true, gasUsed, output, contractAddress, null);
        }

        public static ContractResult reverted(long gasUsed, String error) {
            return new ContractResult(false, gasUsed, new byte[0], null, error);
        }
    }
}
