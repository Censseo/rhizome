package rhizome.core.state.snapshot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import rhizome.core.blockchain.NonceStore;
import rhizome.core.box.Box;
import rhizome.core.box.BoxStore;
import rhizome.core.common.Utils;
import rhizome.core.ledger.Ledger;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.state.StateKeys;
import rhizome.core.token.TokenMeta;
import rhizome.core.token.TokenStore;
import rhizome.core.transaction.TransactionAmount;

/**
 * Bridges the node's concrete stores to the snapshot {@link StateSource}/{@link StateSink}
 * seam, producing and consuming exactly the bindings the engine commits to the state root:
 * same key composition, same value encoding, zero balances omitted. The two contract domains
 * (code and storage, whose store lives outside lib-core) are delegated to an optional
 * secondary source/sink; without one those domains are simply empty.
 *
 * <p>On import, box and token bindings are buffered and applied in one store transaction
 * each ({@link #flush(long)}), which also rebuilds their secondary indexes (owner, expiry,
 * minter, holder) from the verified values — indexes are derived locally, never transferred.
 */
public final class DomainStateAdapter implements StateSource, StateSink {

    private final Ledger ledger;
    private final NonceStore nonces;
    private final BoxStore boxes;
    private final TokenStore tokens;
    private final StateSource contractSource;
    private final StateSink contractSink;

    private final List<BoxStore.BoxMutation> pendingBoxes = new ArrayList<>();
    private final List<TokenStore.TokenOp> pendingTokens = new ArrayList<>();

    public DomainStateAdapter(Ledger ledger, NonceStore nonces, BoxStore boxes, TokenStore tokens,
                              StateSource contractSource, StateSink contractSink) {
        this.ledger = ledger;
        this.nonces = nonces;
        this.boxes = boxes;
        this.tokens = tokens;
        this.contractSource = contractSource;
        this.contractSink = contractSink;
    }

    // ---- export ----

    @Override
    public void forEach(byte domain, EntryConsumer out) {
        switch (domain) {
            case StateKeys.LEDGER -> ledger.forEachBalance((addr, balance) -> {
                if (balance != 0) {
                    out.accept(addr.toBytes(), Utils.longToBytes(balance));
                }
            });
            case StateKeys.ACCOUNT_NONCE -> nonces.forEach((addr, next) -> {
                if (next > 0) {
                    out.accept(addr.toBytes(), Utils.longToBytes(next));
                }
            });
            case StateKeys.BOX -> boxes.forEachBox(box -> out.accept(box.id(), box.serialize()));
            case StateKeys.TOKEN_META -> tokens.forEachMeta(meta -> out.accept(meta.id(), meta.serialize()));
            case StateKeys.TOKEN_BALANCE -> tokens.forEachBalance((tokenId, addr, amount) -> {
                if (amount != 0) {
                    out.accept(concat(tokenId, addr), Utils.longToBytes(amount));
                }
            });
            case StateKeys.CONTRACT_CODE, StateKeys.CONTRACT_STORAGE -> {
                if (contractSource != null) {
                    contractSource.forEach(domain, out);
                }
            }
            default -> throw new IllegalArgumentException("unknown state domain " + domain);
        }
    }

    // ---- import ----

    @Override
    public void put(byte domain, byte[] key, byte[] value) {
        switch (domain) {
            case StateKeys.LEDGER -> setBalance(PublicAddress.of(key), Utils.bytesToLong(value));
            case StateKeys.ACCOUNT_NONCE -> nonces.set(PublicAddress.of(key), Utils.bytesToLong(value));
            case StateKeys.BOX -> pendingBoxes.add(BoxStore.BoxMutation.write(Box.deserialize(value)));
            case StateKeys.TOKEN_META ->
                pendingTokens.add(new TokenStore.TokenOp.MetaSet(TokenMeta.deserialize(value)));
            case StateKeys.TOKEN_BALANCE ->
                // Balance keys are tokenId(32) ‖ address(25).
                pendingTokens.add(new TokenStore.TokenOp.BalanceSet(
                    Arrays.copyOfRange(key, 0, 32), Arrays.copyOfRange(key, 32, key.length),
                    Utils.bytesToLong(value)));
            case StateKeys.CONTRACT_CODE, StateKeys.CONTRACT_STORAGE -> {
                if (contractSink == null) {
                    throw new IllegalStateException("snapshot carries contract state but no contract sink is wired");
                }
                contractSink.put(domain, key, value);
            }
            default -> throw new IllegalArgumentException("unknown state domain " + domain);
        }
    }

    /** Applies the buffered box and token bindings in one store transaction each, at {@code height}. */
    public void flush(long height) {
        if (!pendingBoxes.isEmpty()) {
            boxes.applyBlock(height, pendingBoxes);
            pendingBoxes.clear();
        }
        if (!pendingTokens.isEmpty()) {
            tokens.applyBlock(height, pendingTokens);
            pendingTokens.clear();
        }
    }

    private void setBalance(PublicAddress wallet, long target) {
        if (!ledger.hasWallet(wallet)) {
            ledger.createWallet(wallet);
        }
        long current = ledger.getWalletValue(wallet).amount();
        if (target > current) {
            ledger.deposit(wallet, new TransactionAmount(target - current));
        } else if (target < current) {
            ledger.withdraw(wallet, new TransactionAmount(current - target));
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
