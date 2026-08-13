package rhizome.vm;

import rhizome.core.ledger.PublicAddress;

/**
 * One entry in a block's contract-state undo journal: the base value that existed
 * <em>before</em> the block overwrote it. Applying the journal restores the exact
 * pre-block state on a reorg. A {@code null} prior means the key did not exist and
 * must be deleted on revert.
 *
 * <p>Public so {@link ContractJournalCodec} (and stores that decode their own journal
 * on revert) can name it; the type itself is produced only by the session buffers.
 *
 * @param isCode true for a code entry (key is null), false for a storage entry
 */
public record ContractUndo(boolean isCode, PublicAddress contract, byte[] key, byte[] prior) {}
