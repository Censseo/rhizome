package rhizome.adversarial;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ChainEngineTestAccess;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.TestNodeStores;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.Crypto;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;

/**
 * The victim node an adversarial scenario attacks: a booted {@link ChainEngine} over in-memory
 * stores, a clock the attacker drives, and pre-funded accounts to spend from.
 *
 * <p>This exists because every attack test in the repository used to open with the same thirty
 * lines — build a ledger, seed a snapshot, boot an engine, hand-roll a {@code nextBlock} helper
 * that assembles a coinbase, folds a Merkle tree and mines a nonce. Six copies of that helper had
 * already drifted apart (different clock steps, different difficulty sources), and a drifted helper
 * is worse than no helper in an adversarial suite: it silently changes which gate a forged block
 * reaches, so a test can pass while proving nothing about the rule it names.
 *
 * <p>The contract this fixture keeps is therefore narrow and load-bearing:
 * <b>an unmutated {@link #forge()} always seals into a block the engine accepts.</b> A scenario
 * mutates exactly one field and asserts the exact rejection status, so a failure names the gate
 * that fired. {@link BlockForge#seal()} re-mines after every mutation for the same reason — an
 * attack that dies on {@code INVALID_NONCE} because the forger forgot to redo the proof of work
 * has tested the PoW check, not the rule under attack.
 *
 * <p>Defaults are {@link NetworkParameters#testnet()}: SHA-256 at difficulty 6 (64 hashes per
 * block, so a scenario can mine a long branch cheaply), no minimum block time, no fee floor. A
 * scenario that attacks one of those rules names its own parameters via {@link #on(NetworkParameters)}.
 *
 * @see BlockForge
 */
public final class AdversarialChain {

    /** Where the controlled clock starts, in epoch milliseconds. Far from any real date. */
    public static final long EPOCH = 1_000_000_000_000L;

    private final NetworkParameters params;
    private final InMemoryLedger ledger;
    private final AtomicLong clock;
    private final ChainEngine engine;
    private final PublicAddress miner;
    private final Map<String, Account> accounts;

    private AdversarialChain(NetworkParameters params, InMemoryLedger ledger,
                             AtomicLong clock, ChainEngine engine,
                             PublicAddress miner, Map<String, Account> accounts) {
        this.params = params;
        this.ledger = ledger;
        this.clock = clock;
        this.engine = engine;
        this.miner = miner;
        this.accounts = accounts;
    }

    /** A chain on {@link NetworkParameters#testnet()} — cheap SHA-256 PoW, no timing floors. */
    public static Builder testnet() {
        return new Builder(NetworkParameters.testnet());
    }

    /** A chain on the caller's own parameters, for scenarios that attack a parameterised rule. */
    public static Builder on(NetworkParameters params) {
        return new Builder(params);
    }

    // ---- the node under attack ----

    public ChainEngine engine() {
        return engine;
    }

    public NetworkParameters params() {
        return params;
    }

    /** The address every honest coinbase in this fixture pays. */
    public PublicAddress miner() {
        return miner;
    }

    public long height() {
        return engine.height();
    }

    public long balanceOf(PublicAddress address) {
        return ledger.balanceOrZero(address);
    }

    /**
     * Every balance in the ledger, summed. The single strongest anti-inflation statement a
     * scenario can make is about how this number moves: a valid block may raise it by exactly the
     * block reward (plus any uncle issuance), and nothing else — fees are internal transfers.
     */
    public long totalSupply() {
        long[] total = {0};
        ledger.forEachBalance((address, balance) -> total[0] += balance);
        return total[0];
    }

    /**
     * The earliest timestamp the next block may legally carry — the median-time-past floor and
     * the minimum-block-time floor, whichever binds. An attacker compressing the apparent time
     * between blocks (to drive difficulty up) can do no better than this.
     */
    public long minimalTimestamp() {
        return engine.nextBlockTimestamp(0);
    }

    // ---- the clock the attacker drives ----

    public long now() {
        return clock.get();
    }

    /** Sets the node's clock outright — for scenarios that attack the future-time bound. */
    public void setClock(long millis) {
        clock.set(millis);
    }

    // ---- funded identities ----

    /**
     * A pre-funded account named at build time. Unknown names are a programming error rather
     * than a silently unfunded address, which would make a scenario fail on BALANCE_TOO_LOW
     * instead of the rule it means to test.
     */
    public Account account(String name) {
        Account account = accounts.get(name);
        if (account == null) {
            throw new IllegalArgumentException("no account named '" + name + "' was funded; known: "
                + accounts.keySet());
        }
        return account;
    }

    /** A key pair with a balance, and the one signing path scenarios should use. */
    public final class Account {

        private final PublicKey publicKey;
        private final PrivateKey privateKey;
        private final PublicAddress address;

        private Account(PublicKey publicKey, PrivateKey privateKey) {
            this.publicKey = publicKey;
            this.privateKey = privateKey;
            this.address = PublicAddress.of(publicKey);
        }

        public PublicAddress address() {
            return address;
        }

        public PublicKey publicKey() {
            return publicKey;
        }

        public PrivateKey privateKey() {
            return privateKey;
        }

        public long balance() {
            return ledger.balanceOrZero(address);
        }

        /** A signed transfer on this chain's id, stamped with the current clock. */
        public Transaction send(PublicAddress to, long amount, long fee, long nonce) {
            Transaction t = Transaction.of(address, to, new TransactionAmount(amount), publicKey,
                new TransactionAmount(fee), clock.get(), params.chainId(), nonce);
            t.sign(privateKey);
            return t;
        }

    }

    // ---- honest progress, so a scenario can reach the state it wants to attack ----

    /**
     * A block template for the current tip: correct id, difficulty, parent hash and coinbase,
     * with a timestamp one target interval past the parent. Sealing it unmutated always yields
     * a block {@link ChainEngine#addBlock} accepts.
     */
    public BlockForge forge() {
        return new BlockForge(this, params, engine.height() + 1, engine.difficulty(), engine.tipHash(), clock);
    }

    /** Mines and applies one honest block carrying {@code transactions}. */
    public ExecutionStatus extend(Transaction... transactions) {
        BlockForge forge = forge();
        for (Transaction t : transactions) {
            forge.transaction(t);
        }
        return engine.addBlock(forge.seal());
    }

    /**
     * Mines and applies {@code count} honest empty blocks, failing loudly on the first rejection —
     * a scenario that silently mined fewer blocks than it asked for would go on to assert a
     * finality or retarget property about the wrong height.
     */
    public void extendBy(int count) {
        for (int i = 0; i < count; i++) {
            ExecutionStatus status = extend();
            if (status != ExecutionStatus.SUCCESS) {
                throw new IllegalStateException("honest block " + (i + 1) + " of " + count
                    + " was rejected with " + status + " — the fixture is not producing valid blocks");
            }
        }
    }

    /** Pops the tip, exactly as a reorg would. */
    public void pop() {
        ChainEngineTestAccess.popBlock(engine);
    }

    /** Fluent construction: parameters, funded accounts, then {@link #build()}. */
    public static final class Builder {

        private final NetworkParameters params;
        private final Map<String, Long> funded = new LinkedHashMap<>();
        private PublicAddress miner;

        private Builder(NetworkParameters params) {
            this.params = params;
        }

        public Builder fund(String name, long amount) {
            funded.put(name, amount);
            return this;
        }

        /**
         * Fixes the address every honest coinbase in this chain pays, instead of the default
         * random one. Scenarios that need a deterministic, reproducible chain (selfish-mining
         * revenue simulation, chiefly) cannot tolerate a per-build random miner address: it lands
         * in the coinbase transaction, which is a Merkle leaf, so a random miner makes every block
         * hash — and therefore every tie-break outcome — non-reproducible run to run.
         */
        public Builder miner(PublicAddress miner) {
            this.miner = miner;
            return this;
        }

        public AdversarialChain build() {
            InMemoryLedger ledger = new InMemoryLedger();
            InMemoryChainStore store = new InMemoryChainStore();
            AtomicLong clock = new AtomicLong(EPOCH);
            PublicAddress miner = this.miner != null ? this.miner : PublicAddress.random();

            LedgerSnapshot snapshot = new LedgerSnapshot("adversarial", 0, params.chainId());
            Map<String, Crypto.KeyPair> pairs = new LinkedHashMap<>();
            for (Map.Entry<String, Long> entry : funded.entrySet()) {
                Crypto.KeyPair pair = Crypto.generateKeyPairTyped();
                pairs.put(entry.getKey(), pair);
                snapshot.put(PublicAddress.of(pair.publicKey()), new TransactionAmount(entry.getValue()));
            }

            ChainEngine engine = ChainEngine.boot(params, TestNodeStores.mixing(ledger, store), snapshot)
                .clock(clock::get)
                .build();

            AdversarialChain chain = new AdversarialChain(params, ledger, clock, engine,
                miner, new LinkedHashMap<>());
            pairs.forEach((name, pair) ->
                chain.accounts.put(name, chain.new Account(pair.publicKey(), pair.privateKey())));
            return chain;
        }
    }
}
