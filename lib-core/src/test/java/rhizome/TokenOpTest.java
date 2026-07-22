package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static rhizome.crypto.Crypto.generateKeyPair;

import java.util.Set;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.Executor;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.token.DefaultTokenProcessor;
import rhizome.core.token.InMemoryTokenStore;
import rhizome.core.token.TokenMeta;
import rhizome.core.token.TokenPayload;
import rhizome.core.token.TokenProcessor;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.core.transaction.TransactionKind;

class TokenOpTest {

    private final NetworkParameters params = NetworkParameters.testnet();

    private InMemoryLedger ledger;
    private InMemoryTokenStore tokenStore;
    private TokenProcessor tokens;
    private PublicKey senderKey;
    private PrivateKey senderPrivate;
    private PublicAddress sender;
    private PublicAddress bob;
    private PublicAddress miner;

    @BeforeEach
    void setUp() {
        ledger = new InMemoryLedger();
        tokenStore = new InMemoryTokenStore();
        tokens = new DefaultTokenProcessor(tokenStore, params);
        var pair = generateKeyPair();
        senderKey = PublicKey.of(pair.getPublic());
        senderPrivate = new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate());
        sender = PublicAddress.of(senderKey);
        bob = PublicAddress.random();
        miner = PublicAddress.random();
        ledger.createWallet(sender);
        ledger.deposit(sender, new TransactionAmount(1_000_000L));
    }

    private Transaction tokenTx(TransactionKind kind, PublicAddress to, byte[] data, long fee, long nonce) {
        var tx = TransactionImpl.builder()
            .from(sender).to(to).signingKey(senderKey)
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(fee))
            .chainId(params.chainId()).nonce(nonce).timestamp(1L)
            .kind(kind).data(data).gasLimit(0).gasPrice(0).build();
        tx.sign(senderPrivate);
        return tx;
    }

    private Transaction mint(long amount, long nonce) {
        return tokenTx(TransactionKind.TOKEN_MINT, sender,
            TokenPayload.encodeMint(amount, 2, "PNDA", "Panda"), 0, nonce);
    }

    private Transaction transfer(byte[] tokenId, PublicAddress to, long amount, long nonce) {
        return tokenTx(TransactionKind.TOKEN_TRANSFER, to, TokenPayload.encodeAmount(tokenId, amount), 0, nonce);
    }

    private Transaction burn(byte[] tokenId, long amount, long nonce) {
        return tokenTx(TransactionKind.TOKEN_BURN, sender, TokenPayload.encodeAmount(tokenId, amount), 0, nonce);
    }

    private Transaction coinbase(long height) {
        return Transaction.of(miner, new TransactionAmount(params.miningReward(height)));
    }

    private Block block(long height, Transaction... txs) {
        var b = BlockImpl.builder().id((int) height).timestamp(5000)
            .difficulty(params.genesisDifficulty()).lastBlockHash(SHA256Hash.empty()).build();
        for (Transaction t : txs) {
            b.addTransaction(t);
        }
        return b;
    }

    private ExecutionStatus execute(Block b) {
        return Executor.executeBlock(b, ledger, (SHA256Hash h) -> false, params, null, null, null, tokens);
    }

    @Test
    void mintCreatesSupplyAndCreditsMinter() {
        assertEquals(ExecutionStatus.SUCCESS, execute(block(2, coinbase(2), mint(1_000_000, 0))));
        byte[] id = TokenMeta.deriveId(sender, 0);
        TokenMeta meta = tokens.meta(id);
        assertEquals("PNDA", meta.symbol());
        assertEquals(1_000_000, meta.totalSupply());
        assertEquals(1_000_000, tokens.balance(id, sender.toBytes()));
    }

    @Test
    void transferMovesBalance() {
        execute(block(2, coinbase(2), mint(1_000, 0)));
        byte[] id = TokenMeta.deriveId(sender, 0);
        assertEquals(ExecutionStatus.SUCCESS, execute(block(3, coinbase(3), transfer(id, bob, 400, 1))));
        assertEquals(600, tokens.balance(id, sender.toBytes()));
        assertEquals(400, tokens.balance(id, bob.toBytes()));
    }

    @Test
    void selfTransferIsNoOpAndDoesNotMint() {
        // Regression: a self-transfer aliases the debit and credit keys; computing the credit from
        // the pre-debit balance used to blind-overwrite the debit and DOUBLE the balance (token
        // counterfeiting). Sending the full balance to yourself must leave supply and balance intact.
        execute(block(2, coinbase(2), mint(1_000, 0)));
        byte[] id = TokenMeta.deriveId(sender, 0);
        assertEquals(ExecutionStatus.SUCCESS, execute(block(3, coinbase(3), transfer(id, sender, 1_000, 1))));
        assertEquals(1_000, tokens.balance(id, sender.toBytes()));
        assertEquals(1_000, tokens.meta(id).totalSupply());
    }

    @Test
    void transferOnShortBalanceSoftRevertsAndKeepsBlockValid() {
        // A token op whose precondition fails is a soft revert (Ethereum-style): the block stays
        // valid and the token state is untouched. The mempool cannot see token balances, so a tx
        // spending more than it holds would otherwise abort every candidate block forever — a free
        // production halt (audit: mempool-poisoning halt). The overspend must simply not apply.
        execute(block(2, coinbase(2), mint(100, 0)));
        byte[] id = TokenMeta.deriveId(sender, 0);
        assertEquals(ExecutionStatus.SUCCESS,
            execute(block(3, coinbase(3), transfer(id, bob, 101, 1))));
        assertEquals(100, tokens.balance(id, sender.toBytes()));
        assertEquals(0, tokens.balance(id, bob.toBytes()));
    }

    @Test
    void transferOfUnknownTokenSoftRevertsAndKeepsBlockValid() {
        // Same anti-poisoning rule: transferring a token that does not exist does not invalidate
        // the block; it is a no-op that still consumes the sender's nonce so it clears the pool.
        assertEquals(ExecutionStatus.SUCCESS,
            execute(block(2, coinbase(2), transfer(new byte[32], bob, 1, 0))));
        assertEquals(0, tokens.balance(new byte[32], bob.toBytes()));
    }

    @Test
    void burnReducesSupplyAndBalance() {
        execute(block(2, coinbase(2), mint(1_000, 0)));
        byte[] id = TokenMeta.deriveId(sender, 0);
        assertEquals(ExecutionStatus.SUCCESS, execute(block(3, coinbase(3), burn(id, 250, 1))));
        assertEquals(750, tokens.balance(id, sender.toBytes()));
        assertEquals(750, tokens.meta(id).totalSupply());
    }

    @Test
    void rejectsNonzeroGasAndAmount() {
        // Nonzero PDN amount on a token tx.
        var badAmount = TransactionImpl.builder()
            .from(sender).to(sender).signingKey(senderKey)
            .amount(new TransactionAmount(5)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(0).timestamp(1L)
            .kind(TransactionKind.TOKEN_MINT).data(TokenPayload.encodeMint(1, 0, "X", "x"))
            .gasLimit(0).gasPrice(0).build();
        badAmount.sign(senderPrivate);
        assertEquals(ExecutionStatus.TOKEN_PAYLOAD_INVALID, execute(block(2, coinbase(2), badAmount)));
    }

    @Test
    void rejectedBeforeActivation() {
        NetworkParameters gated = params.toBuilder().tokenActivationHeight(100).build();
        var status = Executor.executeBlock(block(2, coinbase(2), mint(1, 0)),
            ledger, (SHA256Hash h) -> false, gated, null, null, null, tokens);
        assertEquals(ExecutionStatus.TOKEN_UNAVAILABLE, status);
    }

    @Test
    void supplyIsConservedAcrossTransfers() {
        execute(block(2, coinbase(2), mint(1_000, 0)));
        byte[] id = TokenMeta.deriveId(sender, 0);
        execute(block(3, coinbase(3), transfer(id, bob, 300, 1)));
        long total = tokens.balance(id, sender.toBytes()) + tokens.balance(id, bob.toBytes());
        assertEquals(tokens.meta(id).totalSupply(), total);
    }

    @Test
    void rollbackRestoresTokenState() {
        execute(block(2, coinbase(2), mint(1_000, 0)));
        byte[] id = TokenMeta.deriveId(sender, 0);
        Block b = block(3, coinbase(3), transfer(id, bob, 400, 1));
        execute(b);
        assertEquals(400, tokens.balance(id, bob.toBytes()));

        Executor.rollbackBlock(b, ledger, null, null, 3, params);
        tokens.revertBlock(3);
        assertEquals(0, tokens.balance(id, bob.toBytes()));
        assertEquals(1_000, tokens.balance(id, sender.toBytes()));

        // Reverting the mint block removes the token entirely.
        Executor.rollbackBlock(block(2, coinbase(2), mint(1_000, 0)), ledger, null, null, 2, params);
        tokens.revertBlock(2);
        assertNull(tokens.meta(id));
    }
}
