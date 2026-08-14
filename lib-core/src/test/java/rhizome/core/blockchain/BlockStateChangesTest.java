package rhizome.core.blockchain;

import org.junit.jupiter.api.Test;

import rhizome.core.box.Box;
import rhizome.core.box.BoxProcessor;
import rhizome.core.box.BoxStore;
import rhizome.core.common.Utils;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.state.StateChange;
import rhizome.core.state.StateKeys;
import rhizome.core.token.TokenBalanceKey;
import rhizome.core.token.TokenId;
import rhizome.core.token.TokenMeta;
import rhizome.core.token.TokenProcessor;
import rhizome.core.token.TokenStore;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The exact bytes each domain contributes to the state root.
 *
 * <p>Until this translation was a free function it could only be tested through a mined chain and a
 * root comparison, which tells you THAT two nodes disagree, never which byte moved. Two of these
 * raw keys are concatenations — {@code tokenId ‖ address} and {@code contract ‖ storageKey} — and
 * swapping either half yields a perfectly well-formed 32-byte SMT key, so a transposition is
 * invisible until a peer rejects the block.
 */
class BlockStateChangesTest {

    private static final TokenId TOKEN_ID = filledTokenId();
    private static final byte[] STORAGE_KEY = {7, 7, 7};

    /** A 32-byte id with a recognizable non-constant pattern (0xA0..0xBF), so a test that
     *  accidentally uses the wrong half (or the wrong length) fails loudly instead of silently
     *  passing on zeros. */
    private static TokenId filledTokenId() {
        byte[] bytes = new byte[TokenId.SIZE];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (0xA0 + i);
        }
        return TokenId.of(bytes);
    }

    @Test
    void aTokenBalanceKeyIsTokenIdThenAddress() {
        PublicAddress holder = PublicAddress.random();
        List<StateChange> out = new ArrayList<>();
        BlockStateChanges.token(tokensEmitting(
            new TokenStore.TokenOp.BalanceSet(TokenBalanceKey.of(TOKEN_ID, holder), 42L)), 5, out);

        assertEquals(1, out.size());
        StateChange change = out.get(0);
        assertEquals(StateKeys.TOKEN_BALANCE, change.domain());
        assertArrayEquals(StateKeys.tokenBalanceKey(TOKEN_ID.toBytes(), holder.toBytes()), change.rawKey(),
            "the raw key is tokenId then address — the reverse is equally well-formed and wrong");
        assertArrayEquals(Utils.longToBytes(42L), change.value(),
            "balances commit as big-endian 8 bytes");
    }

    @Test
    void aZeroTokenBalanceIsADeleteNotAZeroValue() {
        PublicAddress holder = PublicAddress.random();
        List<StateChange> out = new ArrayList<>();
        BlockStateChanges.token(tokensEmitting(
            new TokenStore.TokenOp.BalanceSet(TokenBalanceKey.of(TOKEN_ID, holder), 0L)), 5, out);

        assertEquals(1, out.size());
        assertNull(out.get(0).value(),
            "a spent-out holder must leave no leaf behind, or the root keeps a zero entry forever");
    }

    @Test
    void aContractStorageKeyIsContractThenSlot() {
        PublicAddress contract = PublicAddress.random();
        List<StateChange> out = new ArrayList<>();
        BlockStateChanges.contract(contractsEmitting(new ContractStateSource.ContractChange(
            false, contract, STORAGE_KEY, new byte[] {1})), 5, out);

        assertEquals(1, out.size());
        assertEquals(StateKeys.CONTRACT_STORAGE, out.get(0).domain());
        assertArrayEquals(StateKeys.concat(contract.toBytes(), STORAGE_KEY), out.get(0).rawKey(),
            "the raw key is contract then slot");
    }

    @Test
    void contractCodeUsesItsOwnDomainAndTheBareAddress() {
        PublicAddress contract = PublicAddress.random();
        List<StateChange> out = new ArrayList<>();
        BlockStateChanges.contract(contractsEmitting(new ContractStateSource.ContractChange(
            true, contract, null, new byte[] {9, 9})), 5, out);

        assertEquals(1, out.size());
        assertEquals(StateKeys.CONTRACT_CODE, out.get(0).domain());
        assertArrayEquals(contract.toBytes(), out.get(0).rawKey());
        assertArrayEquals(new byte[] {9, 9}, out.get(0).value());
    }

    @Test
    void aBoxMutationWithoutABoxIsADelete() {
        byte[] boxId = {1, 2, 3};
        List<StateChange> out = new ArrayList<>();
        BlockStateChanges.box(boxesEmitting(BoxStore.BoxMutation.delete(boxId)), 5, out);

        assertEquals(1, out.size());
        assertEquals(StateKeys.BOX, out.get(0).domain());
        assertArrayEquals(boxId, out.get(0).rawKey());
        assertNull(out.get(0).value(), "a mutation carrying no box erases the leaf");
    }

    @Test
    void tokenMetadataCommitsUnderItsIdInItsOwnDomain() {
        PublicAddress minter = PublicAddress.random();
        TokenMeta meta = new TokenMeta(TOKEN_ID, minter, "SYM", "name", 2, 1_000L, 5L);
        List<StateChange> out = new ArrayList<>();
        BlockStateChanges.token(tokensEmitting(new TokenStore.TokenOp.MetaSet(meta)), 5, out);

        assertEquals(1, out.size());
        assertEquals(StateKeys.TOKEN_META, out.get(0).domain());
        assertArrayEquals(TOKEN_ID.toBytes(), out.get(0).rawKey());
        assertArrayEquals(meta.serialize(), out.get(0).value());
    }

    // ---- minimal stubs: only changes(height) is ever reached ------------------------------------

    private static TokenProcessor tokensEmitting(TokenStore.TokenOp... ops) {
        final class Stub extends AbsentTokenProcessorDouble {
            @Override public List<TokenStore.TokenOp> changes(long height) {
                return List.of(ops);
            }
        }
        return new Stub();
    }

    private static BoxProcessor boxesEmitting(BoxStore.BoxMutation... mutations) {
        final class Stub extends AbsentBoxProcessorDouble {
            @Override public List<BoxStore.BoxMutation> changes(long height) {
                return List.of(mutations);
            }
        }
        return new Stub();
    }

    private static ContractProcessor contractsEmitting(ContractStateSource.ContractChange... changes) {
        final class Stub extends AbsentContractProcessorDouble {
            @Override public List<ContractStateSource.ContractChange> changes(long height) {
                return List.of(changes);
            }
        }
        return new Stub();
    }

    /** The absent singletons are package-private in their own packages, so the doubles are local. */
    private abstract static class AbsentTokenProcessorDouble implements TokenProcessor {
        @Override public void begin() { /* unused */ }
        @Override public void commit(long h) { /* unused */ }
        @Override public void discard() { /* unused */ }
        @Override public void revertBlock(long h) { /* unused */ }
        @Override public TokenResult run(rhizome.core.transaction.TransactionKind k, PublicAddress f,
                                         PublicAddress t, long n, byte[] d, long h) {
            throw new UnsupportedOperationException();
        }
        @Override public TokenMeta meta(TokenId id) { return null; }
        @Override public long balance(TokenBalanceKey key) { return 0; }
        @Override public List<TokenId> tokenIdsByMinter(byte[] m, TokenId a, int l) { return List.of(); }
        @Override public List<TokenId> tokenIdsByHolder(byte[] a, TokenId b, int l) { return List.of(); }
    }

    private abstract static class AbsentBoxProcessorDouble implements BoxProcessor {
        @Override public void begin() { /* unused */ }
        @Override public void commit(long h) { /* unused */ }
        @Override public void discard() { /* unused */ }
        @Override public void revertBlock(long h) { /* unused */ }
        @Override public BoxResult run(rhizome.core.transaction.TransactionKind k, PublicAddress f,
                                       PublicAddress t, long amt, long n, byte[] d, long h) {
            throw new UnsupportedOperationException();
        }
        @Override public List<BoxReceipt> receipts(long h) { return List.of(); }
        @Override public Box get(byte[] id) { return null; }
        @Override public Box getCommitted(byte[] id) { return null; }
        @Override public List<byte[]> collectableBoxIds(long h, int l) { return List.of(); }
        @Override public List<byte[]> boxIdsByOwner(byte[] o, byte[] a, int l) { return List.of(); }
        @Override public ScanPage scan(rhizome.core.box.ScanPredicate p, byte[] a, int l, int w) {
            return new ScanPage(List.of(), null);
        }
    }

    private abstract static class AbsentContractProcessorDouble implements ContractProcessor {
        @Override public void begin() { /* unused */ }
        @Override public void commit(long h) { /* unused */ }
        @Override public void discard() { /* unused */ }
        @Override public void revertBlock(long h) { /* unused */ }
        @Override public ContractResult run(PublicAddress f, rhizome.core.transaction.TransactionKind k,
                                            PublicAddress t, byte[] d, long v, long g, long n) {
            throw new UnsupportedOperationException();
        }
        @Override public List<ContractReceipt> receipts(long h) { return List.of(); }
    }
}
