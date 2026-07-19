package rhizome.vm;

import java.util.List;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.ValType;

/**
 * The smart-contract virtual machine: runs a WebAssembly contract deterministically
 * on Chicory (pure Java, no JNI), metering gas per instruction and per host call so
 * untrusted code can neither hang the node nor escape its sandbox.
 *
 * <p>Contracts import a minimal host ABI from module {@code "env"} — storage
 * read/write, call input, caller, attached value, and a return-data slot — and
 * export a single {@code call} entry point. Everything the contract can touch goes
 * through {@link HostState}; the WASM sandbox denies it any other I/O.
 *
 * <p>This is the M1 core: an in-memory host state and a placeholder gas schedule.
 * Persistence (a contract-code + storage store), the deploy/call transaction types,
 * and ledger-backed value transfer are layered on top without changing this class.
 */
public final class WasmVm {

    private static final String ENV = "env";
    private static final String ENTRY = "call";

    /**
     * Handles a contract-to-contract call requested via the {@code call_contract}
     * host function. Returns the callee's output on success, or {@code null} when
     * the call failed (unknown contract, revert, depth or reentrancy limit) — the
     * caller keeps running either way, its own state untouched by the failure.
     */
    @FunctionalInterface
    public interface ContractCallHandler {
        byte[] call(byte[] calleeAddress, byte[] input);
    }

    /** Runs {@code wasmCode}'s {@code call} export against {@code host} under {@code gas}. */
    public ExecResult execute(byte[] wasmCode, HostState host, GasMeter gas) {
        return execute(wasmCode, host, gas, null);
    }

    /** As above, with {@code calls} dispatching {@code call_contract} (null = calls always fail). */
    public ExecResult execute(byte[] wasmCode, HostState host, GasMeter gas, ContractCallHandler calls) {
        WasmModule module = Parser.parse(wasmCode);
        ImportValues imports = ImportValues.builder()
            .addFunction(hostFunctions(host, gas, calls))
            .build();

        try {
            Instance instance = Instance.builder(module)
                .withImportValues(imports)
                .withStart(false)
                .withUnsafeExecutionListener((instruction, stack) -> gas.charge(GasSchedule.PER_INSTRUCTION))
                .build();

            ExportFunction call = instance.export(ENTRY);
            call.apply();
            return ExecResult.ok(host.output(), host.logs(), gas.used());
        } catch (RuntimeException e) {
            if (isOutOfGas(e)) {
                return ExecResult.outOfGas(gas.used());
            }
            return ExecResult.reverted(gas.used(), e.getMessage());
        }
    }

    private static boolean isOutOfGas(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof OutOfGasException) {
                return true;
            }
        }
        return false;
    }

    private HostFunction[] hostFunctions(HostState host, GasMeter gas, ContractCallHandler calls) {
        HostFunction storageRead = new HostFunction(ENV, "storage_read",
            List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32), List.of(ValType.I32),
            (Instance inst, long... args) -> {
                Memory mem = inst.memory();
                byte[] key = mem.readBytes(asOffset(args[0]), asLen(args[1]));
                byte[] value = host.storageRead(key);
                gas.charge(GasSchedule.STORAGE_READ_BASE + (long) key.length * GasSchedule.PER_BYTE);
                if (value == null) {
                    return new long[] {-1L};
                }
                int outPtr = asOffset(args[2]);
                int outCap = asLen(args[3]);
                int copied = Math.min(value.length, outCap);
                if (copied > 0) {
                    mem.write(outPtr, value, 0, copied);
                }
                gas.charge((long) copied * GasSchedule.PER_BYTE);
                return new long[] {value.length};
            });

        HostFunction storageWrite = new HostFunction(ENV, "storage_write",
            List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32), List.of(),
            (Instance inst, long... args) -> {
                Memory mem = inst.memory();
                byte[] key = mem.readBytes(asOffset(args[0]), asLen(args[1]));
                byte[] value = mem.readBytes(asOffset(args[2]), asLen(args[3]));
                gas.charge(GasSchedule.STORAGE_WRITE_BASE
                    + (long) (key.length + value.length) * GasSchedule.PER_BYTE);
                host.storageWrite(key, value);
                return null;
            });

        HostFunction setOutput = new HostFunction(ENV, "set_output",
            List.of(ValType.I32, ValType.I32), List.of(),
            (Instance inst, long... args) -> {
                byte[] out = inst.memory().readBytes(asOffset(args[0]), asLen(args[1]));
                gas.charge(GasSchedule.OUTPUT_BASE + (long) out.length * GasSchedule.PER_BYTE);
                host.setOutput(out);
                return null;
            });

        HostFunction emitLog = new HostFunction(ENV, "emit_log",
            List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32), List.of(),
            (Instance inst, long... args) -> {
                Memory mem = inst.memory();
                byte[] topic = mem.readBytes(asOffset(args[0]), asLen(args[1]));
                byte[] data = mem.readBytes(asOffset(args[2]), asLen(args[3]));
                gas.charge(GasSchedule.LOG_BASE + (long) (topic.length + data.length) * GasSchedule.PER_BYTE);
                host.emitLog(topic, data);
                return null;
            });

        // Read the call context into contract memory. Each returns the source's true
        // length (so a contract can size its buffer), copying at most out_cap bytes.
        HostFunction getCaller = new HostFunction(ENV, "get_caller",
            List.of(ValType.I32, ValType.I32), List.of(ValType.I32),
            (Instance inst, long... args) -> new long[] {copyOut(inst, host.caller(), args[0], args[1], gas)});

        HostFunction getInput = new HostFunction(ENV, "get_input",
            List.of(ValType.I32, ValType.I32), List.of(ValType.I32),
            (Instance inst, long... args) -> new long[] {copyOut(inst, host.input(), args[0], args[1], gas)});

        HostFunction getValue = new HostFunction(ENV, "get_value",
            List.of(), List.of(ValType.I64),
            (Instance inst, long... args) -> new long[] {host.value()});

        // call_contract(addr_ptr, addr_len, in_ptr, in_len, out_ptr, out_cap) -> i32:
        // the callee's output length (copied up to out_cap bytes), or -1 if the call
        // failed. The dispatcher runs the callee in its own state frame, so a failed
        // call leaves no trace; gas is shared with this meter, so nested work draws
        // from the same budget (forwarded gas, no resurrection).
        HostFunction callContract = new HostFunction(ENV, "call_contract",
            List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32),
            List.of(ValType.I32),
            (Instance inst, long... args) -> {
                Memory mem = inst.memory();
                byte[] callee = mem.readBytes(asOffset(args[0]), asLen(args[1]));
                byte[] input = mem.readBytes(asOffset(args[2]), asLen(args[3]));
                gas.charge(GasSchedule.CALL_BASE + (long) input.length * GasSchedule.PER_BYTE);
                byte[] output = calls == null ? null : calls.call(callee, input);
                if (output == null) {
                    return new long[] {-1L};
                }
                return new long[] {copyOut(inst, output, args[4], args[5], gas)};
            });

        return new HostFunction[] {
            storageRead, storageWrite, setOutput, emitLog, getCaller, getInput, getValue, callContract};
    }

    /** Copies {@code src} into contract memory (at most {@code cap} bytes) and returns its true length. */
    private static long copyOut(Instance inst, byte[] src, long ptr, long cap, GasMeter gas) {
        int copied = Math.min(src.length, asLen(cap));
        if (copied > 0) {
            inst.memory().write(asOffset(ptr), src, 0, copied);
        }
        gas.charge((long) copied * GasSchedule.PER_BYTE);
        return src.length;
    }

    /** A WASM i32 pointer arrives as a long; take the unsigned low 32 bits as a memory offset. */
    private static int asOffset(long i32) {
        return (int) (i32 & 0xFFFF_FFFFL);
    }

    private static int asLen(long i32) {
        int len = (int) (i32 & 0xFFFF_FFFFL);
        if (len < 0) {
            throw new IllegalArgumentException("negative length from contract");
        }
        return len;
    }
}
