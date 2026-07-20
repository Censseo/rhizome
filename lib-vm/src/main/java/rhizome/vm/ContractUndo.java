package rhizome.vm;

import rhizome.core.ledger.PublicAddress;

/**
 * One entry in a block's contract-state undo journal: the base value that existed
 * <em>before</em> the block overwrote it. Applying the journal restores the exact
 * pre-block state on a reorg. A {@code null} prior means the key did not exist and
 * must be deleted on revert.
 *
 * @param isCode true for a code entry (key is null), false for a storage entry
 */
record ContractUndo(boolean isCode, PublicAddress contract, byte[] key, byte[] prior) {}
