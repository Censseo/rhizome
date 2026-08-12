package rhizome.vm;

import rhizome.core.blockchain.Contracts;
import rhizome.core.ledger.Ledger;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.TransactionAmount;

/**
 * Ties the {@link WasmVm} to persistent state and the ledger: deploys code to a
 * derived address and calls contracts, metering gas and settling it as a fee.
 *
 * <p>Fee model (EVM-style, simplified): the caller must be able to cover
 * {@code value + gasLimit * gasPrice}; it always pays {@code gasUsed * gasPrice}
 * to the fee recipient (even on revert/out-of-gas — the work was done), and the
 * unused gas is simply never charged. Value transfer and storage writes are
 * applied only when the call succeeds; a revert leaves state and balances (beyond
 * the gas fee) untouched.
 *
 * <p><b>Not on the consensus path.</b> Block execution runs through
 * {@link WasmContractProcessor}; this executor is exercised by tests only. Its
 * putCode/putStorage go through the store's straight-through (unsynced) path —
 * acceptable for tests, but it must not be mistaken for a second production
 * executor.
 */
public final class ContractExecutor {

    private final WasmVm vm;
    private final ContractState store;
    private final Ledger ledger;

    public ContractExecutor(WasmVm vm, ContractState store, Ledger ledger) {
        this.vm = vm;
        this.store = store;
        this.ledger = ledger;
    }

    public record DeployOutcome(PublicAddress address, long feeCharged, boolean deployed, String error) {}

    public record CallOutcome(ExecResult result, long feeCharged, boolean applied) {
        public boolean succeeded() {
            return applied;
        }
    }

    /**
     * Stores {@code code} at a freshly derived address, charging a size-based gas fee.
     * (M2: no constructor is run — deploy just installs the code so it is callable.)
     */
    public DeployOutcome deploy(PublicAddress deployer, long nonce, byte[] code,
                                long gasPrice, PublicAddress feeRecipient) {
        PublicAddress address = Contracts.deriveAddress(deployer, nonce);
        long gasUsed = GasSchedule.DEPLOY_BASE + (long) code.length * GasSchedule.DEPLOY_PER_CODE_BYTE;
        long fee;
        try {
            fee = Math.multiplyExact(gasUsed, gasPrice);
        } catch (ArithmeticException overflow) {
            // An overflowing fee is unpayable by construction (no balance reaches 2^63), so the
            // deterministic verdict is "insufficient balance" — never an escaping exception
            // (matches the executor's BALANCE_TOO_LOW abort on fee overflow).
            return new DeployOutcome(address, 0, false, "insufficient balance for deploy gas");
        }
        if (balance(deployer) < fee) {
            return new DeployOutcome(address, 0, false, "insufficient balance for deploy gas");
        }
        chargeFee(deployer, feeRecipient, fee);
        // Validate BEFORE storing: this path stored raw code with no checks, so malformed,
        // oversized or non-deterministic (float/SIMD) bytecode could enter on-chain state and
        // only be discovered on every later call (audit F8). The gas fee is still charged for
        // the validation work, matching the processor's failed-deploy path. Only a
        // RuntimeException is a verdict: an Error (SOE/OOM in the parser) is host-local and
        // must crash rather than become a node-dependent "invalid code" verdict (consensus
        // fork class — see WasmContractProcessor.deploy).
        try {
            // On the fixed-stack worker, exactly like the production processor's deploy: the
            // parser's recursion depth on the caller's thread is JIT/-Xss-dependent, so even
            // this test-only path keeps the fatal path a network constant (audit: deploy
            // validation stack).
            WasmVm.onBoundedStack(() -> {
                WasmVm.validateCode(code);
                return null;
            });
        } catch (RuntimeException e) {
            return new DeployOutcome(address, fee, false, "invalid contract code: " + e.getMessage());
        }
        // Never deploy over live code, matching WasmContractProcessor.deploy: the derived
        // address colliding with an existing contract must revert, not silently overwrite it.
        // The read is HostFault-wrapped exactly like call's code read below: a store failure
        // is node-local and fatal, never a "collision" verdict.
        final boolean collision;
        try {
            collision = store.getCode(address) != null;
        } catch (Throwable t) {
            throw HostFault.wrap("contract code read failed", t);
        }
        if (collision) {
            return new DeployOutcome(address, fee, false, "contract address collision");
        }
        store.putCode(address, code);
        // Record the deployer under the reserved empty storage key, exactly like
        // WasmContractProcessor.deploy, so get_deployer cannot diverge between the two paths.
        store.putStorage(address, PersistentHostState.DEPLOYER_KEY, deployer.toBytes());
        return new DeployOutcome(address, fee, true, null);
    }

    /**
     * Calls {@code contract}'s {@code call} entry point with {@code input} and an
     * optional attached {@code value}. Gas is charged as a fee; on success the
     * value transfer and storage writes are committed.
     */
    public CallOutcome call(PublicAddress caller, PublicAddress contract, byte[] input, long value,
                            long gasLimit, long gasPrice, PublicAddress feeRecipient) {
        long maxFee;
        long required;
        try {
            maxFee = Math.multiplyExact(gasLimit, gasPrice);
            required = Math.addExact(value, maxFee);
        } catch (ArithmeticException overflow) {
            // An overflowing value+fee is unpayable by construction — deterministic verdict,
            // never an escaping exception (see deploy above).
            return new CallOutcome(ExecResult.reverted(0,
                "insufficient balance for value + gas"), 0, false);
        }
        // Balance sufficiency is checked BEFORE any metering, exactly as the consensus executor
        // does before WasmContractProcessor.call runs, so a later gasUsed*gasPrice fee can never
        // exceed what the caller was proven to cover (gasUsed <= gasLimit).
        if (balance(caller) < required) {
            return new CallOutcome(ExecResult.reverted(0,
                "insufficient balance for value + gas"), 0, false);
        }
        GasMeter meter = new GasMeter(gasLimit);
        // Intrinsic CALL cost charged whatever the outcome — the same CALL_BASE
        // WasmContractProcessor.call charges — so a missing-contract or zero-gas call cannot
        // execute fee-free (audit H2): without this a failed call paid nothing at all here.
        try {
            meter.charge(GasSchedule.CALL_BASE);
        } catch (OutOfGasException e) {
            long fee = Math.multiplyExact(meter.used(), gasPrice);
            chargeFee(caller, feeRecipient, fee);
            return new CallOutcome(ExecResult.outOfGas(meter.used()), fee, false);
        }
        byte[] code;
        try {
            code = store.getCode(contract);
        } catch (Throwable t) {
            // Node-local store failure — fatal, never a contract verdict (see HostFault).
            throw HostFault.wrap("contract code read failed", t);
        }
        if (code == null) {
            // The intrinsic charge still applies (CALL_BASE was metered), exactly as in
            // WasmContractProcessor.call's "no contract at address" path.
            long fee = Math.multiplyExact(meter.used(), gasPrice);
            chargeFee(caller, feeRecipient, fee);
            return new CallOutcome(ExecResult.reverted(meter.used(), "no contract at address"), fee, false);
        }

        PersistentHostState host = new PersistentHostState(store, contract, caller.toBytes(), input, value);
        // Run on the fixed-size bounded stack (as WasmContractProcessor does), so the interpreter's
        // 1024-frame depth/locals guard is measured against the consensus stack size, not the host JVM's
        // -Xss. Running directly on the caller thread could let a JVM StackOverflowError fire before the
        // deterministic trap — a node-local outcome, i.e. a consensus-split risk if this path goes live.
        ExecResult result = WasmVm.onBoundedStack(() -> vm.execute(code, host, meter));

        long fee = Math.multiplyExact(result.gasUsed(), gasPrice);
        chargeFee(caller, feeRecipient, fee);

        if (!result.succeeded()) {
            return new CallOutcome(result, fee, false);
        }
        host.commit();
        if (value > 0) {
            withdraw(caller, value);
            deposit(contract, value);
        }
        return new CallOutcome(result, fee, true);
    }

    // ---- ledger helpers (checked arithmetic lives in the ledger) ----

    private long balance(PublicAddress a) {
        return ledger.hasWallet(a) ? ledger.getWalletValue(a).amount() : 0L;
    }

    private void chargeFee(PublicAddress payer, PublicAddress recipient, long fee) {
        if (fee == 0) {
            return;
        }
        withdraw(payer, fee);
        deposit(recipient, fee);
    }

    private void withdraw(PublicAddress a, long amount) {
        ledger.withdraw(a, new TransactionAmount(amount));
    }

    private void deposit(PublicAddress a, long amount) {
        if (!ledger.hasWallet(a)) {
            ledger.createWallet(a);
        }
        ledger.deposit(a, new TransactionAmount(amount));
    }
}
