package rhizome.core.blockchain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import rhizome.core.block.Block;
import rhizome.core.box.BoxProcessor;
import rhizome.core.common.Utils;
import rhizome.core.ledger.Ledger;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.state.StateChange;
import rhizome.core.state.StateKeys;
import rhizome.core.token.TokenProcessor;
import rhizome.core.token.TokenStore;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionImpl;

/**
 * Translates one block's committed effects into the {@link StateChange} list the state accumulator
 * folds into the root — the bridge between the domain processors and the authenticated state.
 *
 * <p>Extracted from {@code ChainEngine} not for size but for testability. Every method here decides
 * two things that only ever showed up as a root mismatch against other nodes: the exact bytes of a
 * raw key, and whether a value is a set or a delete. Two of the keys are CONCATENATIONS —
 * {@code tokenId ‖ address} and {@code contract ‖ storageKey} — and transposing either half still
 * produces a well-formed 32-byte SMT key. As free functions over their inputs, they can be asserted
 * directly against a synthetic mutation list, with no engine, no chain and no proof of work.
 *
 * <p>Lives in {@code rhizome.core.blockchain} rather than {@code rhizome.core.state} on purpose:
 * the state package is the pure SMT layer and has no dependency on box, token or contract types
 * today. This class needs all three, and the blockchain package already imports them, so nothing
 * new couples to anything. {@link StateKeys} and every byte of key composition stay in lib-core.
 *
 * <p>The order in which a caller invokes these is the emission order that the root commits to. It
 * is deliberately NOT the same as the domain commit order, and the two must not be unified without
 * an argument: the domain byte keeps the SMT keys disjoint, so reordering is very probably
 * root-neutral, but "very probably" and "state root" do not belong in the same change.
 */
final class BlockStateChanges {

    private BlockStateChanges() {
    }

    /**
     * Domain 0x01 — the final balance of every address this block touched. A zero balance is a
     * DELETE, not a zero value, so an emptied wallet leaves no leaf behind.
     */
    static void ledger(Ledger ledger, Set<PublicAddress> touched, List<StateChange> out) {
        for (PublicAddress a : touched) {
            long balance = ledger.hasWallet(a) ? ledger.getWalletValue(a).amount() : 0;
            byte[] key = a.toBytes();
            out.add(balance == 0
                ? StateChange.delete(StateKeys.LEDGER, key)
                : StateChange.set(StateKeys.LEDGER, key, longBytesBE(balance)));
        }
    }

    /**
     * Domain 0x07 — each sender's next-expected nonce after this block, {@code max(txNonce) + 1}
     * over its transactions.
     *
     * <p>Derived from the block, not from the nonce store: the store is advanced only after this
     * collection runs. Sequentiality was already validated, so this is a deterministic function of
     * block content. Committing it is what lets a snap-synced node obtain nonces verifiably, by
     * root equality, instead of trusting a peer.
     */
    static void nonces(Block block, List<StateChange> out) {
        Map<PublicAddress, Long> newNonces = new HashMap<>();
        for (Transaction tx : block.transactions()) {
            if (!tx.isTransactionFee() && !ChainEngine.isSelfAuthorized(tx)) {
                newNonces.merge(tx.from(), tx.nonce() + 1, Math::max);
            }
        }
        newNonces.forEach((from, nonce) ->
            out.add(StateChange.set(StateKeys.ACCOUNT_NONCE, from.toBytes(), longBytesBE(nonce))));
    }

    /** Domain 0x02 — a mutation with no box is a DELETE; otherwise the box's serialized form. */
    static void box(BoxProcessor boxes, long height, List<StateChange> out) {
        for (var m : boxes.changes(height)) {
            out.add(m.box() == null
                ? StateChange.delete(StateKeys.BOX, m.id())
                : StateChange.set(StateKeys.BOX, m.id(), m.box().serialize()));
        }
    }

    /**
     * Domains 0x03 and 0x04 — token metadata under its id, and holder balances under
     * {@code tokenId ‖ address}. A zero balance is a DELETE, so a spent-out holder leaves no leaf.
     */
    static void token(TokenProcessor tokens, long height, List<StateChange> out) {
        for (var op : tokens.changes(height)) {
            if (op instanceof TokenStore.TokenOp.MetaSet ms) {
                out.add(StateChange.set(StateKeys.TOKEN_META, ms.meta().id(), ms.meta().serialize()));
            } else if (op instanceof TokenStore.TokenOp.BalanceSet bs) {
                byte[] rawKey = StateKeys.tokenBalanceKey(bs.tokenId(), bs.address());
                out.add(bs.amount() == 0
                    ? StateChange.delete(StateKeys.TOKEN_BALANCE, rawKey)
                    : StateChange.set(StateKeys.TOKEN_BALANCE, rawKey, longBytesBE(bs.amount())));
            }
        }
    }

    /**
     * Domains 0x05 and 0x06 — contract code under the contract address, and storage under
     * {@code contract ‖ storageKey}. Neither ever deletes on the forward path: a storage write of
     * empty bytes is a value, not an erasure.
     */
    static void contract(ContractStateSource contracts, long height, List<StateChange> out) {
        for (var ch : contracts.changes(height)) {
            if (ch.code()) {
                out.add(StateChange.set(StateKeys.CONTRACT_CODE, ch.contract().toBytes(), ch.value()));
            } else {
                byte[] rawKey = StateKeys.concat(ch.contract().toBytes(), ch.key());
                out.add(StateChange.set(StateKeys.CONTRACT_STORAGE, rawKey, ch.value()));
            }
        }
    }

    /** Big-endian 8 bytes — the committed encoding of every numeric state value. */
    static byte[] longBytesBE(long value) {
        return Utils.longToBytes(value);
    }
}
