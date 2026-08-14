package rhizome.core.blockchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPairTyped;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockImpl;
import rhizome.core.box.BoxPayload;
import rhizome.core.box.BoxRegister;
import rhizome.core.box.DefaultBoxProcessor;
import rhizome.core.box.InMemoryBoxStore;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.token.DefaultTokenProcessor;
import rhizome.core.token.InMemoryTokenStore;
import rhizome.core.token.TokenPayload;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.core.transaction.TransactionKind;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;

/**
 * What {@link ChainEngine#boot} is for.
 *
 * <p>It replaced eight positional {@code init} overloads whose optional tail was a fixed order of
 * nullable slots — verifier, contracts, boxes, tokens, accumulator. The common call passed a run of
 * five {@code null}s to reach the one it wanted, and a {@code null} in the wrong slot compiled,
 * ran, and produced a chain silently missing a consensus domain. This class pins the two halves of
 * the replacement: a domain the caller never names is genuinely absent and its transactions are
 * REJECTED rather than ignored, and a domain the caller does name cannot be handed {@code null}.
 */
class ChainEngineBootTest {

    private NetworkParameters params;
    private AtomicLong clock;
    private PublicKey key;
    private PrivateKey priv;
    private PublicAddress sender;
    private PublicAddress miner;
    private LedgerSnapshot snapshot;

    @BeforeEach
    void setUp() {
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3).build();
        clock = new AtomicLong(1_000_000L);
        var pair = generateKeyPairTyped();
        key = pair.publicKey();
        priv = pair.privateKey();
        sender = PublicAddress.of(key);
        miner = PublicAddress.random();
        snapshot = new LedgerSnapshot("t", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(10_000_000L));
    }

    private ChainEngine.Boot boot() {
        return ChainEngine.boot(params, TestNodeStores.inMemory(), snapshot).clock(clock::get);
    }

    private Transaction tx(TransactionKind kind, byte[] data) {
        var t = TransactionImpl.builder()
            .from(sender).to(sender).signingKey(key)
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(0).timestamp(clock.get())
            .kind(kind).data(data).gasLimit(0).gasPrice(0).build();
        t.sign(priv);
        return t;
    }

    private ExecutionStatus mine(ChainEngine engine, Transaction payload) {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) height).timestamp(clock.addAndGet(1000))
            .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash()).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        b.addTransaction(payload);
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return engine.addBlock(b);
    }

    /**
     * The failure the positional form allowed, now impossible to reach by accident: a chain built
     * without {@code .tokens(...)} refuses token transactions. Under {@code init} this state was
     * one misplaced {@code null} away and produced no diagnostic at construction — the node simply
     * came up unable to process a domain the operator believed was enabled.
     */
    @Test
    void aDomainThatWasNeverNamedRejectsItsTransactionsInsteadOfIgnoringThem() {
        ChainEngine bare = boot().build();
        assertFalse(bare.tokensEnabled());
        assertFalse(bare.boxesEnabled());

        assertEquals(ExecutionStatus.TOKEN_UNAVAILABLE,
            mine(bare, tx(TransactionKind.TOKEN_MINT, TokenPayload.encodeMint(1_000, 2, "PNDA", "Panda"))));
        assertEquals(ExecutionStatus.CONTRACT_EXECUTION_UNAVAILABLE,
            mine(bare, tx(TransactionKind.CALL, new byte[] {1, 2, 3})));
        assertEquals(1, bare.height(), "a rejected domain must leave the chain untouched");
    }

    /** The same chain, with the domain named: the identical transaction is accepted. */
    @Test
    void namingTheDomainIsWhatMakesTheSameTransactionValid() {
        ChainEngine withTokens = boot()
            .tokens(new DefaultTokenProcessor(new InMemoryTokenStore(), params))
            .build();
        assertTrue(withTokens.tokensEnabled());
        assertEquals(ExecutionStatus.SUCCESS,
            mine(withTokens, tx(TransactionKind.TOKEN_MINT,
                TokenPayload.encodeMint(1_000, 2, "PNDA", "Panda"))));
        assertEquals(2, withTokens.height());
    }

    /**
     * Every optional setter rejects {@code null}. Absence is expressed by not calling the setter,
     * so {@code .boxes(maybeNull)} — the shape a caller reaches for when threading an optional
     * processor through a helper — fails loudly at construction instead of yielding a chain that
     * refuses box transactions for a reason nothing records.
     */
    @Test
    void anOptionalDomainCannotBeDisabledByPassingNullToItsSetter() {
        assertThrows(NullPointerException.class, () -> boot().verifier(null));
        assertThrows(NullPointerException.class, () -> boot().contracts(null));
        assertThrows(NullPointerException.class, () -> boot().boxes(null));
        assertThrows(NullPointerException.class, () -> boot().tokens(null));
        assertThrows(NullPointerException.class, () -> boot().stateAccumulator(null));
        assertThrows(NullPointerException.class, () -> boot().clock(null));
        assertThrows(NullPointerException.class, () -> boot().expectedGenesis(null));
    }

    /** The required head is required: none of the three has a defensible default. */
    @Test
    void theRequiredHeadIsRequired() {
        assertThrows(NullPointerException.class,
            () -> ChainEngine.boot(null, TestNodeStores.inMemory(), snapshot));
        assertThrows(NullPointerException.class, () -> ChainEngine.boot(params, null, snapshot));
        assertThrows(NullPointerException.class,
            () -> ChainEngine.boot(params, TestNodeStores.inMemory(), null));
    }

    /**
     * {@code expectedGenesis} had ZERO non-null callers across the whole tree — production passed
     * {@code null} and so did all ninety test sites — which made a consensus-relevant boot check
     * reachable only through {@code GenesisBlock} directly. Now that it is a named setter, pin
     * both directions: the right hash boots, a wrong one refuses rather than forking on block 2.
     */
    @Test
    void expectedGenesisAcceptsTheMatchingHashAndRefusesAnyOther() {
        ChainEngine reference = boot().build();
        var genesisHash = reference.tipHash();

        assertEquals(genesisHash, boot().expectedGenesis(genesisHash).build().tipHash());

        var wrong = rhizome.crypto.SHA256Hash.of(new byte[32]);
        assertThrows(IllegalStateException.class, () -> boot().expectedGenesis(wrong).build());
    }

    /**
     * The head takes ONE {@link NodeStores} so the three views cannot come from three databases.
     * A test that deliberately mixes backends has to say so, and the aggregate that lets it is a
     * test fixture — {@code app-node}'s main source set cannot reference this class at all.
     */
    @Test
    void mixingBackendsIsStillPossibleButHasToBeNamed() {
        var ledger = new rhizome.core.ledger.InMemoryLedger();
        ChainEngine engine = ChainEngine.boot(params,
                TestNodeStores.mixing(ledger, new InMemoryChainStore()), snapshot)
            .clock(clock::get)
            .boxes(new DefaultBoxProcessor(new InMemoryBoxStore(), params))
            .build();
        assertTrue(engine.boxesEnabled());
        assertEquals(ExecutionStatus.SUCCESS,
            mine(engine, tx(TransactionKind.BOX_CREATE, BoxPayload.encodeCreate(List.of(BoxRegister.i64(7))))));
    }
}
