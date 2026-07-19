package rhizome.vm;

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
 */
public final class ContractExecutor {

    private final WasmVm vm;
    private final ContractStore store;
    private final Ledger ledger;

    public ContractExecutor(WasmVm vm, ContractStore store, Ledger ledger) {
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
        long fee = Math.multiplyExact(gasUsed, gasPrice);
        if (balance(deployer) < fee) {
            return new DeployOutcome(address, 0, false, "insufficient balance for deploy gas");
        }
        chargeFee(deployer, feeRecipient, fee);
        store.putCode(address, code);
        return new DeployOutcome(address, fee, true, null);
    }

    /**
     * Calls {@code contract}'s {@code call} entry point with {@code input} and an
     * optional attached {@code value}. Gas is charged as a fee; on success the
     * value transfer and storage writes are committed.
     */
    public CallOutcome call(PublicAddress caller, PublicAddress contract, byte[] input, long value,
                            long gasLimit, long gasPrice, PublicAddress feeRecipient) {
        byte[] code = store.getCode(contract);
        if (code == null) {
            return new CallOutcome(ExecResult.reverted(0, "no contract at address"), 0, false);
        }
        long maxFee = Math.multiplyExact(gasLimit, gasPrice);
        long required = Math.addExact(value, maxFee);
        if (balance(caller) < required) {
            return new CallOutcome(ExecResult.reverted(0, "insufficient balance for value + gas"), 0, false);
        }

        PersistentHostState host = new PersistentHostState(store, contract, caller.toBytes(), input, value);
        ExecResult result = vm.execute(code, host, new GasMeter(gasLimit));

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
