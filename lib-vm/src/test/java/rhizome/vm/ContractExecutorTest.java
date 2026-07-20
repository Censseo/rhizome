package rhizome.vm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.Ledger;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.TransactionAmount;

/**
 * End-to-end contract lifecycle through the executor: deploy the real counter
 * contract, call it (state persists in the store), and pay gas as a fee to the
 * miner — plus the failure paths (unknown contract, out-of-gas leaves state and
 * balances untouched beyond the gas fee).
 */
class ContractExecutorTest {

    private static final byte[] COUNTER = load("/counter.wasm");

    private ContractExecutor exec;
    private ContractStore store;
    private Ledger ledger;
    private PublicAddress deployer;
    private PublicAddress miner;

    private static byte[] load(String r) {
        try (var in = ContractExecutorTest.class.getResourceAsStream(r)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] le64(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) (v >>> (8 * i));
        }
        return b;
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryContractStore();
        ledger = new InMemoryLedger();
        exec = new ContractExecutor(new WasmVm(), store, ledger);
        deployer = PublicAddress.random();
        miner = PublicAddress.random();
        ledger.createWallet(deployer);
        // Enough to cover the max-gas reservation (gasLimit x gasPrice) the executor
        // holds for each call, plus fees.
        ledger.deposit(deployer, new TransactionAmount(100_000_000L));
    }

    private long balance(PublicAddress a) {
        return ledger.hasWallet(a) ? ledger.getWalletValue(a).amount() : 0L;
    }

    @Test
    void deployThenCallPersistsStateAndPaysGasToMiner() {
        long before = balance(deployer);

        var deployed = exec.deploy(deployer, 0, COUNTER, 1, miner);
        assertTrue(deployed.deployed());
        assertTrue(deployed.feeCharged() > 0);
        assertEquals(deployed.feeCharged(), balance(miner));
        assertArrayEquals(COUNTER, store.getCode(deployed.address()));

        // First call: counter -> 1, gas paid to miner, state committed.
        var c1 = exec.call(deployer, deployed.address(), new byte[0], 0, 1_000_000, 1, miner);
        assertTrue(c1.succeeded());
        assertArrayEquals(le64(1), c1.result().output());

        // Second call: state persisted, counter -> 2.
        var c2 = exec.call(deployer, deployed.address(), new byte[0], 0, 1_000_000, 1, miner);
        assertTrue(c2.succeeded());
        assertArrayEquals(le64(2), c2.result().output());

        // Miner earned every fee; deployer paid exactly that.
        long totalFees = deployed.feeCharged() + c1.feeCharged() + c2.feeCharged();
        assertEquals(totalFees, balance(miner));
        assertEquals(before - totalFees, balance(deployer));
    }

    @Test
    void callToUnknownContractReverts() {
        var out = exec.call(deployer, PublicAddress.random(), new byte[0], 0, 1_000_000, 1, miner);
        assertFalse(out.succeeded());
        assertEquals(0, out.feeCharged());
        assertEquals(0, balance(miner));
    }

    @Test
    void outOfGasChargesGasButCommitsNoState() {
        var deployed = exec.deploy(deployer, 0, COUNTER, 1, miner);
        long minerAfterDeploy = balance(miner);

        // Tiny gas limit: the call runs out of gas, so no storage is committed but
        // the (capped) gas is still charged to the caller and paid to the miner.
        var out = exec.call(deployer, deployed.address(), new byte[0], 0, 50, 1, miner);
        assertEquals(ExecResult.Status.OUT_OF_GAS, out.result().status());
        assertFalse(out.succeeded());
        assertEquals(50, out.feeCharged());
        assertEquals(minerAfterDeploy + 50, balance(miner));
        // Counter never persisted.
        assertEquals(null, store.getStorage(deployed.address(), new byte[] {0}));
    }
}
