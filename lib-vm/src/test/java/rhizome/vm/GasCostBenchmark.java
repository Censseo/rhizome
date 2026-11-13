package rhizome.vm;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

/**
 * Not a correctness test: measures whether the gas schedule bounds CPU time per gas uniformly (audit
 * VM-24..VM-34 follow-up). For each scenario it reports median ns per gas unit (and wall ms per 1M
 * gas, numerically the same figure) so the worst ratio against a plain {@code i32.add} loop shows
 * which gas constant still under-prices real work.
 *
 * <p>Enable manually: {@code ./gradlew :lib-vm:test -Dbench=on --tests "rhizome.vm.GasCostBenchmark"}.
 * Without {@code -Dbench=on} it returns immediately. The table is printed and written to
 * {@code lib-vm/build/gas-cost-benchmark.txt}. Modules are hand-assembled by {@link Mod}; host state is
 * {@link MapHostState}, so storage cost here excludes the RocksDB/LevelDB backends.
 */
class GasCostBenchmark {

    private static final int WARMUP = 2;
    private static final int RUNS = 5;
    private static final long GAS_LIMIT = 50_000_000L; // NetworkParameters.maxTxGas
    /** TransactionDto.MAX_DATA (128 KiB): the largest deployable module is just under this. */
    private static final int TX_DATA_CAP = 128 * 1024;

    private final WasmVm vm = new WasmVm();
    private final List<String> report = new ArrayList<>();
    private String lastStatus = "";

    // ---------------------------------------------------------------- assembler

    static byte[] leb(long v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        do {
            int b = (int) (v & 0x7F);
            v >>>= 7;
            out.write(v != 0 ? b | 0x80 : b);
        } while (v != 0);
        return out.toByteArray();
    }

    static byte[] sleb(long v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean more = true;
        while (more) {
            int b = (int) (v & 0x7F);
            v >>= 7;
            if ((v == 0 && (b & 0x40) == 0) || (v == -1 && (b & 0x40) != 0)) {
                more = false;
            } else {
                b |= 0x80;
            }
            out.write(b);
        }
        return out.toByteArray();
    }

    static byte[] cat(Object... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (Object p : parts) {
            if (p instanceof byte[] b) {
                o.writeBytes(b);
            } else if (p instanceof Integer i) {
                o.write(i);
            } else {
                throw new IllegalArgumentException(String.valueOf(p));
            }
        }
        return o.toByteArray();
    }

    static byte[] rep(byte[] unit, int n) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(unit.length * n);
        for (int i = 0; i < n; i++) {
            o.writeBytes(unit);
        }
        return o.toByteArray();
    }

    static final int I32 = 0x7F;

    static byte[] i32c(long v) { return cat(0x41, sleb(v)); }
    static byte[] lget(int i) { return cat(0x20, leb(i)); }
    static byte[] lset(int i) { return cat(0x21, leb(i)); }
    static byte[] ltee(int i) { return cat(0x22, leb(i)); }
    static byte[] call(int f) { return cat(0x10, leb(f)); }

    /** A function type: {@code n} i32 params, {@code m} i32 results. */
    static byte[] type(int n, int m) {
        return cat(0x60, leb(n), rep(new byte[] {(byte) I32}, n), leb(m), rep(new byte[] {(byte) I32}, m));
    }

    /** A function body: {@code locals} extra i32 locals in one group, then {@code code}, then end. */
    static byte[] body(int locals, byte[]... code) {
        byte[] decl = locals == 0 ? leb(0) : cat(leb(1), leb(locals), I32);
        return cat(decl, cat((Object[]) code), 0x0B);
    }

    /** Minimal module builder; function index space = host imports first, then {@code funcs}. */
    static final class Mod {
        final List<byte[]> types = new ArrayList<>();
        final List<String> imports = new ArrayList<>();
        final List<Integer> importTypes = new ArrayList<>();
        final List<Integer> funcs = new ArrayList<>();
        final List<byte[]> bodies = new ArrayList<>();
        int tableMin = -1;
        byte[] elem; // func indices of an active segment at offset 0
        int memMin = -1;
        int memMax = -1;
        int exportFunc;

        int type(byte[] t) { types.add(t); return types.size() - 1; }
        int imp(String name, int typeIdx) { imports.add(name); importTypes.add(typeIdx); return imports.size() - 1; }
        int fn(int typeIdx, byte[] body) { funcs.add(typeIdx); bodies.add(body); return imports.size() + funcs.size() - 1; }

        static byte[] sec(int id, byte[] content) { return cat(id, leb(content.length), content); }
        static byte[] vec(List<byte[]> items) { return cat(leb(items.size()), cat(items.toArray())); }

        byte[] build() {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.writeBytes(new byte[] {0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00});
            o.writeBytes(sec(1, vec(types)));
            if (!imports.isEmpty()) {
                List<byte[]> im = new ArrayList<>();
                for (int i = 0; i < imports.size(); i++) {
                    byte[] n = imports.get(i).getBytes();
                    im.add(cat(leb(3), "env".getBytes(), leb(n.length), n, 0x00, leb(importTypes.get(i))));
                }
                o.writeBytes(sec(2, vec(im)));
            }
            List<byte[]> fs = new ArrayList<>();
            for (int t : funcs) { fs.add(leb(t)); }
            o.writeBytes(sec(3, vec(fs)));
            if (tableMin >= 0) {
                o.writeBytes(sec(4, cat(leb(1), 0x70, 0x01, leb(tableMin), leb(tableMin))));
            }
            if (memMin >= 0) {
                o.writeBytes(sec(5, cat(leb(1), memMax >= 0 ? cat(0x01, leb(memMin), leb(memMax)) : cat(0x00, leb(memMin)))));
            }
            o.writeBytes(sec(7, cat(leb(1), leb(4), "call".getBytes(), 0x00, leb(exportFunc))));
            if (elem != null) {
                o.writeBytes(sec(9, cat(leb(1), 0x00, 0x41, 0x00, 0x0B, elem)));
            }
            List<byte[]> cs = new ArrayList<>();
            for (byte[] b : bodies) { cs.add(cat(leb(b.length), b)); }
            o.writeBytes(sec(10, vec(cs)));
            return o.toByteArray();
        }
    }

    /** One function {@code call () -> ()} with the given body. */
    static byte[] simple(int locals, byte[]... code) {
        Mod m = new Mod();
        int t = m.type(type(0, 0));
        m.exportFunc = m.fn(t, body(locals, code));
        return m.build();
    }

    /** Same, with a one-page memory (host calls and bulk ops need it). */
    static byte[] simpleMem(int memMin, int memMax, int locals, byte[]... code) {
        Mod m = new Mod();
        int t = m.type(type(0, 0));
        m.memMin = memMin;
        m.memMax = memMax;
        m.exportFunc = m.fn(t, body(locals, code));
        return m.build();
    }

    /** {@code local[idx] = n; loop { work; local[idx] -= 1; br_if 0 (local[idx] != 0) }}. */
    static byte[] counted(int idx, int n, byte[] work) {
        return cat(i32c(n), lset(idx), 0x03, 0x40, work,
            lget(idx), i32c(-1), 0x6A, ltee(idx), 0x0D, 0, 0x0B);
    }

    // ---------------------------------------------------------------- harness

    /** Executes on the fixed-stack worker with a fresh host, as the node does. */
    private long exec(byte[] code, MapHostState host) {
        ExecResult r = WasmVm.onBoundedStack(() -> vm.execute(code, host, new GasMeter(GAS_LIMIT)));
        lastStatus = r.status() + (r.message() == null ? "" : ":" + trunc(r.message()));
        return r.gasUsed();
    }

    private long exec(byte[] code) {
        return exec(code, new MapHostState(new byte[25], new byte[0], 0));
    }

    private static String trunc(String s) {
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    private record Result(String name, double median, double min, double max, long gas, String status) { }

    /** Times {@code op} (returns gas used); {@code prep} (untimed) runs before every iteration. */
    private Result measure(String name, Runnable prep, LongSupplier op) {
        double[] ns = new double[RUNS];
        long gas = 0;
        for (int i = 0; i < WARMUP + RUNS; i++) {
            if (prep != null) {
                prep.run();
            }
            long t0 = System.nanoTime();
            gas = op.getAsLong();
            long dt = System.nanoTime() - t0;
            if (i >= WARMUP) {
                ns[i - WARMUP] = (double) dt / Math.max(1, gas);
            }
        }
        Arrays.sort(ns);
        Result r = new Result(name, ns[RUNS / 2], ns[0], ns[RUNS - 1], gas, lastStatus);
        String line = String.format("%-52s %10.2f ns/gas  (min %.2f max %.2f)  %9.2f ms/Mgas  gas=%d  %s",
            r.name, r.median, r.min, r.max, r.median, r.gas, r.status);
        report.add(line);
        System.out.println(line);
        return r;
    }

    // ---------------------------------------------------------------- module shapes

    /** Pads a body with {@code seed}-dependent leading constant so every module hashes distinctly. */
    private static byte[] nopModule(int bytes, int seed) {
        return simple(0, i32c(seed), new byte[] {0x1A}, rep(new byte[] {0x01}, bytes - 60));
    }

    private static byte[] nestedBlocks(int bytes) {
        int depth = (bytes - 60) / 3;
        return simple(0, rep(new byte[] {0x02, 0x40}, depth), rep(new byte[] {0x0B}, depth));
    }

    private static byte[] brTableModule(int bytes) {
        int labels = bytes - 80;
        // block (i32.const 0) (br_table [0 x labels] 0) end
        return simple(0, new byte[] {0x02, 0x40}, i32c(0), new byte[] {0x0E}, leb(labels),
            rep(new byte[] {0x00}, labels), new byte[] {0x00, 0x0B});
    }

    /** call: () -> 1000 x i32, body = {@code br 0} repeated. */
    private static byte[] maxResultsBr(int bytes) {
        Mod m = new Mod();
        int t = m.type(type(0, WasmVm.MAX_FUNCTION_PARAMS));
        m.exportFunc = m.fn(t, body(0, new byte[] {0x00}, rep(new byte[] {0x0C, 0x00}, (bytes - 1200) / 2)));
        return m.build();
    }

    // ---------------------------------------------------------------- scenarios

    @Test
    void probe() throws Exception {
        if (!"on".equals(System.getProperty("bench"))) {
            return;
        }
        int big = TX_DATA_CAP - 64; // just under the tx data cap
        List<Result> rs = new ArrayList<>();

        // 1. Baseline: tight i32.add loop (local.get, i32.const, i32.add, local.tee, br_if = 5 instrs/iter).
        byte[] baseline = simple(1, counted(0, 4_000_000, new byte[0]));
        exec(baseline); // fill cache
        rs.add(measure("1 baseline i32.add loop", null, () -> exec(baseline)));

        // 2. Deploy / validation of max-size modules. Cold cache each run; gas = deploy charge.
        long deployGas = GasSchedule.DEPLOY_BASE + (long) big * GasSchedule.DEPLOY_PER_CODE_BYTE;
        byte[][] shapes = {nopModule(big, 0), nestedBlocks(big), brTableModule(big), maxResultsBr(big)};
        String[] shapeNames = {"nops", "nested blocks", "br_table labels", "1000 results + br 0 x N"};
        for (int i = 0; i < shapes.length; i++) {
            byte[] code = shapes[i];
            rs.add(measure("2" + (char) ('a' + i) + " deploy-validate " + shapeNames[i] + " (" + code.length + "B)",
                WasmVm::clearModuleCacheForTest, () -> {
                    try {
                        WasmVm.validateCode(code);
                        lastStatus = "OK";
                    } catch (RuntimeException | StackOverflowError e) {
                        lastStatus = "REJECTED:" + trunc(String.valueOf(e.getMessage()));
                    }
                    return GasSchedule.DEPLOY_BASE + (long) code.length * GasSchedule.DEPLOY_PER_CODE_BYTE;
                }));
            // Cold first CALL of the same module (parse + instantiate + run), gas = parse charge + exec.
            rs.add(measure("2" + (char) ('a' + i) + "' cold call " + shapeNames[i],
                WasmVm::clearModuleCacheForTest, () -> exec(code)));
        }

        // 3. Calls.
        // 3a. wide frames: a callee with MAX_FUNCTION_LOCALS slots called in a loop (frame-slot gas).
        rs.add(measure("3a call fn with 8192 locals x 3000", null, wideFrames(8192, 3000)));
        rs.add(measure("3a' call fn with 1 local x 1M (call overhead)", null, wideFrames(1, 1_000_000)));
        // 3b. recursion to the depth cap (1024) with 255 extra locals/frame, repeated.
        rs.add(measure("3b recursion depth 1000, 255 locals x 100", null, recursion(255, 100)));
        rs.add(measure("3b' recursion depth 1000, 0 locals x 500", null, recursion(0, 500)));
        // 3c. table of 65536 entries: instantiate-only and call_indirect loop.
        rs.add(measure("3c table 65536 entries instantiate", null, tableCalls(0)));
        rs.add(measure("3c' call_indirect x 2M through 65536 table", null, tableCalls(2_000_000)));

        // 4. Memory.
        byte[] fillCopy = simpleMem(512, 512, 0,
            i32c(0), i32c(1), i32c(16 << 20), new byte[] {(byte) 0xFC, 0x0B, 0x00},
            i32c(16 << 20), i32c(0), i32c(16 << 20), new byte[] {(byte) 0xFC, 0x0A, 0x00, 0x00},
            i32c(0), i32c(1), i32c(8 << 20), new byte[] {(byte) 0xFC, 0x0B, 0x00});
        rs.add(measure("4a memory.fill/copy 16+16+8 MiB", null, () -> exec(fillCopy)));
        byte[] fill1 = simpleMem(1024, 1024, 1, counted(0, 40,
            cat(i32c(0), i32c(1), i32c(1 << 20), new byte[] {(byte) 0xFC, 0x0B, 0x00})));
        rs.add(measure("4a' memory.fill 1 MiB x 40", null, () -> exec(fill1)));
        byte[] initBig = simpleMem(1024, 1024, 0);
        rs.add(measure("4b instantiate 1024 initial pages (64 MiB)", null, () -> exec(initBig)));
        byte[] growOnce = simpleMem(1, 1024, 0, i32c(1023), new byte[] {0x40, 0x00, 0x1A});
        rs.add(measure("4c memory.grow 1->1024 pages in one op", null, () -> exec(growOnce)));
        byte[] growMany = simpleMem(1, 1024, 1, counted(0, 1023, cat(i32c(1), new byte[] {0x40, 0x00, 0x1A})));
        rs.add(measure("4c' memory.grow 1 page x 1023", null, () -> exec(growMany)));

        // 5. Host calls (MapHostState; excludes persistent-store cost).
        rs.add(measure("5a storage_write 64KiB key + 120KiB value x 1", null, hostWrite(65_536, 120_000, 1)));
        rs.add(measure("5a' storage_write 32B key + 1KiB value x 150", null, hostWrite(32, 1024, 150)));
        rs.add(measure("5b storage_read 1 MiB value, out_cap 1MiB x 30", null, hostRead(1 << 20, 30)));
        rs.add(measure("5b' storage_read 1 MiB value, out_cap 0 x 30", null, hostRead(0, 30)));
        rs.add(measure("5c emit_log 16 x 64KiB (~MAX_TX_LOG_BYTES)", null, emitLogs(32, 65_536 - 32, 16)));
        rs.add(measure("5c' emit_log empty topic/data x 200000", null, emitLogs(0, 0, 200_000)));

        // 6. Cold vs warm module cache.
        byte[][] distinct = new byte[256][];
        for (int i = 0; i < 256; i++) {
            distinct[i] = nopModule(big, i + 1);
        }
        rs.add(measure("6a 256 distinct modules, cold (cache cleared)", WasmVm::clearModuleCacheForTest, () -> {
            long gas = 0;
            for (byte[] m : distinct) {
                gas += exec(m);
            }
            return gas;
        }));
        byte[] one = distinct[0];
        rs.add(measure("6b same module x 256, warm", () -> exec(one), () -> {
            long gas = 0;
            for (int i = 0; i < 256; i++) {
                gas += exec(one);
            }
            return gas;
        }));

        double base = rs.get(0).median;
        report.add("");
        report.add(String.format("%-56s %10s %10s %10s %8s", "scenario", "median", "min", "max", "x base"));
        for (Result r : rs) {
            report.add(String.format("%-56s %10.2f %10.2f %10.2f %8.1f %s", r.name, r.median, r.min, r.max,
                r.median / base, r.median / base > 20 ? "<-- >20x" : ""));
        }
        report.forEach(System.out::println);
        Files.writeString(Path.of("build", "gas-cost-benchmark.txt"), String.join("\n", report) + "\n");
    }

    /** main loops {@code n} times calling a callee that declares {@code locals} extra slots. */
    private LongSupplier wideFrames(int locals, int n) {
        Mod m = new Mod();
        int t0 = m.type(type(0, 0));
        m.exportFunc = m.fn(t0, body(1, counted(0, n, call(1))));
        m.fn(t0, body(locals));
        byte[] code = m.build();
        exec(code);
        return () -> exec(code);
    }

    /** rec(n): if n != 0 { rec(n - 1) }, with {@code locals} extra locals; main calls rec(1000) {@code reps} times. */
    private LongSupplier recursion(int locals, int reps) {
        Mod m = new Mod();
        int t0 = m.type(type(0, 0));
        int t1 = m.type(type(1, 0));
        m.exportFunc = m.fn(t0, body(1, counted(0, reps, cat(i32c(1000), call(1)))));
        m.fn(t1, body(locals, lget(0), new byte[] {0x04, 0x40}, lget(0), i32c(-1), new byte[] {0x6A}, call(1),
            new byte[] {0x0B}));
        byte[] code = m.build();
        exec(code);
        return () -> exec(code);
    }

    /** 65536-entry funcref table all pointing at a trivial function; loops {@code n} call_indirects. */
    private LongSupplier tableCalls(int n) {
        Mod m = new Mod();
        int t0 = m.type(type(0, 0));
        m.tableMin = 65_536;
        m.elem = cat(leb(65_536), rep(new byte[] {0x01}, 65_536)); // every slot -> func 1
        byte[] work = n == 0 ? new byte[0] : counted(0, n, cat(i32c(12345), 0x11, leb(t0), 0x00));
        m.exportFunc = m.fn(t0, body(1, work));
        m.fn(t0, body(0));
        byte[] code = m.build();
        exec(code);
        if (!lastStatus.startsWith("OK")) {
            System.out.println("tableCalls(" + n + ") setup: " + lastStatus);
        }
        return () -> exec(code);
    }

    private Mod hostMod(int memPages, byte[] work, String... names) {
        Mod m = new Mod();
        int t0 = m.type(type(0, 0));
        int t4v = m.type(type(4, 0));
        int t4i = m.type(type(4, 1));
        for (String n : names) {
            m.imp(n, n.equals("storage_read") ? t4i : t4v);
        }
        m.memMin = memPages;
        m.memMax = memPages;
        m.exportFunc = m.fn(t0, body(1, work));
        return m;
    }

    private LongSupplier hostWrite(int keyLen, int valLen, int n) {
        Mod m = hostMod(4, counted(0, n, cat(i32c(0), i32c(keyLen), i32c(keyLen), i32c(valLen), call(0))),
            "storage_write");
        // key bytes must be non-zero-length; contents irrelevant (memory is zero-filled)
        byte[] code = m.build();
        exec(code);
        return () -> exec(code, new MapHostState(new byte[25], new byte[0], 0));
    }

    private LongSupplier hostRead(int outCap, int n) {
        Mod m = hostMod(32, counted(0, n,
            cat(i32c(0), i32c(32), i32c(1 << 16), i32c(outCap), call(0), new byte[] {0x1A})), "storage_read");
        byte[] code = m.build();
        var storage = new HashMap<String, byte[]>();
        MapHostState probe = new MapHostState(storage, new byte[25], new byte[0], 0);
        probe.storageWrite(new byte[32], new byte[1 << 20]); // pre-seed a 1 MiB value at the all-zero key
        exec(code, probe);
        return () -> exec(code, new MapHostState(storage, new byte[25], new byte[0], 0));
    }

    private LongSupplier emitLogs(int topicLen, int dataLen, int n) {
        Mod m = hostMod(2, counted(0, n,
            cat(i32c(0), i32c(topicLen), i32c(topicLen), i32c(dataLen), call(0))), "emit_log");
        byte[] code = m.build();
        exec(code);
        return () -> exec(code);
    }
}
