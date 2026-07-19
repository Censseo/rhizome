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

    /** Runs {@code wasmCode}'s {@code call} export against {@code host} under {@code gas}. */
    public ExecResult execute(byte[] wasmCode, HostState host, GasMeter gas) {
        WasmModule module = Parser.parse(wasmCode);
        ImportValues imports = ImportValues.builder()
            .addFunction(hostFunctions(host, gas))
            .build();

        try {
            Instance instance = Instance.builder(module)
                .withImportValues(imports)
                .withStart(false)
                .withUnsafeExecutionListener((instruction, stack) -> gas.charge(GasSchedule.PER_INSTRUCTION))
                .build();

            ExportFunction call = instance.export(ENTRY);
            call.apply();
            return ExecResult.ok(host.output(), gas.used());
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

    private HostFunction[] hostFunctions(HostState host, GasMeter gas) {
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

        return new HostFunction[] {storageRead, storageWrite, setOutput};
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
