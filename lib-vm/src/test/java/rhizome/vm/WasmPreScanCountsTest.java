package rhizome.vm;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.Test;

/**
 * Regression tests for the pre-parse module scan (audit: poison DEPLOY). Chicory 1.7.5's parser
 * presizes several count-driven allocations — type-section param/result counts, recursion-group
 * and sub-type counts, element initializer counts, data-segment and name lengths — BEFORE the
 * buffer underflow that would reject a lying count, so a tiny module could force a multi-GB
 * transient allocation inside {@code Parser.parse}: big-heap nodes reject, small-heap validators
 * OOM (a fatal Error on the deploy path) — heap-dependent block validity. {@link
 * WasmVm#validateCode} must reject every one of these from the raw bytes, ahead of the parse,
 * with a deterministic {@link IllegalArgumentException}.
 */
class WasmPreScanCountsTest {

    private static final byte[] WASM_HEADER = {
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00
    };

    private static byte[] leb(long v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long value = v;
        do {
            int b = (int) (value & 0x7F);
            value >>>= 7;
            if (value != 0) {
                b |= 0x80;
            }
            out.write(b);
        } while (value != 0);
        return out.toByteArray();
    }

    /** A module = header + one section of {@code sectionId} carrying {@code contents}. */
    private static byte[] moduleWithSection(int sectionId, byte[] contents) {
        ByteArrayOutputStream mod = new ByteArrayOutputStream();
        mod.writeBytes(WASM_HEADER);
        mod.write(sectionId);
        mod.writeBytes(leb(contents.length));
        mod.writeBytes(contents);
        return mod.toByteArray();
    }

    @Test
    void rejectsFunctypeDeclaringHugeParamCount() {
        // The canonical poison DEPLOY: ~20 bytes declaring paramCount = 2^30 — unguarded, Chicory
        // presizes a ~4 GiB ArrayList inside Parser.parse before any Rhizome cap can run.
        ByteArrayOutputStream type = new ByteArrayOutputStream();
        type.writeBytes(leb(1));                       // 1 type
        type.write(0x60);                              // functype
        type.writeBytes(leb(1L << 30));                // paramCount = 2^30
        byte[] mod = moduleWithSection(0x01, type.toByteArray());
        assertTrue(mod.length < 32, "attack module is tiny");
        var ex = assertThrows(IllegalArgumentException.class, () -> WasmVm.validateCode(mod));
        assertTrue(ex.getMessage().contains("params"), ex.getMessage());
    }

    @Test
    void rejectsFunctypeWhoseParamCountExceedsTheBytesPresent() {
        // paramCount under MAX_FUNCTION_PARAMS but past the section end: no absolute-cap trip,
        // only the bytes-present bound — Chicory would underflow AFTER presizing the list.
        ByteArrayOutputStream type = new ByteArrayOutputStream();
        type.writeBytes(leb(1));
        type.write(0x60);
        type.writeBytes(leb(900));                     // 900 params, only ~1 param byte follows
        type.write(0x7F);                              // one i32, then the section ends
        var ex = assertThrows(IllegalArgumentException.class,
            () -> WasmVm.validateCode(moduleWithSection(0x01, type.toByteArray())));
        assertTrue(ex.getMessage().contains("params in a function type"), ex.getMessage());
    }

    @Test
    void rejectsFunctypeDeclaringHugeResultCount() {
        ByteArrayOutputStream type = new ByteArrayOutputStream();
        type.writeBytes(leb(1));
        type.write(0x60);
        type.write(0x00);                              // 0 params
        type.writeBytes(leb(1L << 30));                // resultCount = 2^30
        var ex = assertThrows(IllegalArgumentException.class,
            () -> WasmVm.validateCode(moduleWithSection(0x01, type.toByteArray())));
        assertTrue(ex.getMessage().contains("results"), ex.getMessage());
    }

    @Test
    void rejectsRecursionGroupDeclaringHugeSubtypeCount() {
        ByteArrayOutputStream type = new ByteArrayOutputStream();
        type.writeBytes(leb(1));                       // 1 rec type
        type.write(0x4E);                              // rec group
        type.writeBytes(leb(1L << 30));                // subtype count = 2^30
        var ex = assertThrows(IllegalArgumentException.class,
            () -> WasmVm.validateCode(moduleWithSection(0x01, type.toByteArray())));
        assertTrue(ex.getMessage().contains("recursion group"), ex.getMessage());
    }

    @Test
    void rejectsSubtypeDeclaringHugeSupertypeCount() {
        ByteArrayOutputStream type = new ByteArrayOutputStream();
        type.writeBytes(leb(1));                       // 1 type
        type.write(0x4F);                              // sub final
        type.writeBytes(leb(1L << 30));                // supertype count = 2^30
        var ex = assertThrows(IllegalArgumentException.class,
            () -> WasmVm.validateCode(moduleWithSection(0x01, type.toByteArray())));
        assertTrue(ex.getMessage().contains("supertypes"), ex.getMessage());
    }

    @Test
    void acceptsParamsAtExactlyTheCap() {
        // MAX_FUNCTION_PARAMS params with the bytes present: must pass the pre-scan (the
        // post-parse cap allows exactly this); the parser then rejects the stub for structure.
        ByteArrayOutputStream type = new ByteArrayOutputStream();
        type.writeBytes(leb(1));
        type.write(0x60);
        type.writeBytes(leb(WasmVm.MAX_FUNCTION_PARAMS));
        for (int i = 0; i < WasmVm.MAX_FUNCTION_PARAMS; i++) {
            type.write(0x7F);
        }
        type.write(0x00);
        try {
            WasmVm.validateCode(moduleWithSection(0x01, type.toByteArray()));
        } catch (Throwable e) {
            assertTrue(e.getMessage() == null || !e.getMessage().contains("params"),
                "params at the cap must not trip the pre-scan: " + e.getMessage());
        }
    }

    @Test
    void rejectsElementSegmentDeclaringHugeInitializerCount() {
        // Passive funcref segment (flags 0x01): elemkind 0, then initCnt = 2^30.
        ByteArrayOutputStream elem = new ByteArrayOutputStream();
        elem.writeBytes(leb(1));                       // 1 segment
        elem.write(0x01);                              // flags: passive, funcidx inits
        elem.write(0x00);                              // elemkind funcref
        elem.writeBytes(leb(1L << 30));                // initCnt = 2^30
        var ex = assertThrows(IllegalArgumentException.class,
            () -> WasmVm.validateCode(moduleWithSection(0x09, elem.toByteArray())));
        assertTrue(ex.getMessage().contains("initializers"), ex.getMessage());
    }

    @Test
    void rejectsActiveElementWhoseOffsetExprEndsBeforeHugeInitializerCount() {
        // Active segment (flags 0x00): offset expr `i32.const 0 end`, then initCnt = 2^30 (the
        // type is implicitly funcref for this flag — no elemkind byte) — exercises expression
        // skipping ahead of the count.
        ByteArrayOutputStream elem = new ByteArrayOutputStream();
        elem.writeBytes(leb(1));
        elem.write(0x00);                              // flags: active, table 0, funcidx inits
        elem.write(0x41); elem.write(0x00);            // i32.const 0
        elem.write(0x0B);                              // end
        elem.writeBytes(leb(1L << 30));                // initCnt = 2^30
        var ex = assertThrows(IllegalArgumentException.class,
            () -> WasmVm.validateCode(moduleWithSection(0x09, elem.toByteArray())));
        assertTrue(ex.getMessage().contains("initializers"), ex.getMessage());
    }

    @Test
    void rejectsDataSegmentDeclaringHugePayload() {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.writeBytes(leb(1));                       // 1 segment
        data.write(0x01);                              // passive
        data.writeBytes(leb(1L << 30));                // payload length = 2^30
        var ex = assertThrows(IllegalArgumentException.class,
            () -> WasmVm.validateCode(moduleWithSection(0x0B, data.toByteArray())));
        assertTrue(ex.getMessage().contains("payload"), ex.getMessage());
    }

    @Test
    void rejectsActiveDataSegmentWithOffsetExprAndHugePayload() {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.writeBytes(leb(1));
        data.write(0x00);                              // active, memory 0
        data.write(0x41); data.write(0x00);            // i32.const 0
        data.write(0x0B);                              // end
        data.writeBytes(leb(1L << 30));
        var ex = assertThrows(IllegalArgumentException.class,
            () -> WasmVm.validateCode(moduleWithSection(0x0B, data.toByteArray())));
        assertTrue(ex.getMessage().contains("payload"), ex.getMessage());
    }

    @Test
    void rejectsImportDeclaringHugeNameLength() {
        ByteArrayOutputStream imp = new ByteArrayOutputStream();
        imp.writeBytes(leb(1));                        // 1 import
        imp.writeBytes(leb(1L << 30));                 // module name length = 2^30
        var ex = assertThrows(IllegalArgumentException.class,
            () -> WasmVm.validateCode(moduleWithSection(0x02, imp.toByteArray())));
        assertTrue(ex.getMessage().contains("module name"), ex.getMessage());
    }

    @Test
    void rejectsExportDeclaringHugeNameLength() {
        ByteArrayOutputStream exp = new ByteArrayOutputStream();
        exp.writeBytes(leb(1));                        // 1 export
        exp.writeBytes(leb(1L << 30));
        var ex = assertThrows(IllegalArgumentException.class,
            () -> WasmVm.validateCode(moduleWithSection(0x07, exp.toByteArray())));
        assertTrue(ex.getMessage().contains("export name"), ex.getMessage());
    }

    @Test
    void rejectsCustomSectionDeclaringHugeNameLength() {
        ByteArrayOutputStream custom = new ByteArrayOutputStream();
        custom.writeBytes(leb(1L << 30));              // custom section name length = 2^30
        var ex = assertThrows(IllegalArgumentException.class,
            () -> WasmVm.validateCode(moduleWithSection(0x00, custom.toByteArray())));
        assertTrue(ex.getMessage().contains("custom section name"), ex.getMessage());
    }

    @Test
    void rejectsNameCustomSectionWhoseInnerNameIsHuge() {
        // A custom section literally named "name" (decoded eagerly by Chicory's default custom
        // parser): subsection 1 (function names) with one entry whose name length is 2^30 —
        // unguarded, NameCustomSection allocates the byte[] before underflowing.
        ByteArrayOutputStream subsection = new ByteArrayOutputStream();
        subsection.writeBytes(leb(1));                 // 1 entry
        subsection.writeBytes(leb(0));                 // function index 0
        subsection.writeBytes(leb(1L << 30));          // name length = 2^30
        ByteArrayOutputStream custom = new ByteArrayOutputStream();
        custom.write(0x04); custom.writeBytes("name".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        custom.write(0x01);                            // subsection 1: function names
        custom.writeBytes(leb(subsection.size()));
        custom.writeBytes(subsection.toByteArray());
        var ex = assertThrows(IllegalArgumentException.class,
            () -> WasmVm.validateCode(moduleWithSection(0x00, custom.toByteArray())));
        assertTrue(ex.getMessage().contains("name"), ex.getMessage());
    }

    @Test
    void acceptsModuleWithBenignSectionsOfEveryScannedKind() {
        // Type (1 functype), import (1 function import), export (call), element (1 funcidx),
        // data (3 payload bytes), a "name" custom section and a code section: the pre-scan must
        // not fire — only the parser's own structural verdicts may reject this stub.
        ByteArrayOutputStream mod = new ByteArrayOutputStream();
        mod.writeBytes(WASM_HEADER);

        ByteArrayOutputStream type = new ByteArrayOutputStream();
        type.writeBytes(leb(1));
        type.write(0x60); type.write(0x00); type.write(0x00);
        writeSection(mod, 0x01, type.toByteArray());

        ByteArrayOutputStream imp = new ByteArrayOutputStream();
        imp.writeBytes(leb(1));
        imp.write(0x03); imp.writeBytes(new byte[] {'e', 'n', 'v'});
        imp.write(0x0C); imp.writeBytes(new byte[] {'s', 't', 'o', 'r', 'a', 'g', 'e', '_', 'r', 'e', 'a', 'd'});
        imp.write(0x00); imp.write(0x00);              // function import, type 0
        writeSection(mod, 0x02, imp.toByteArray());

        ByteArrayOutputStream exp = new ByteArrayOutputStream();
        exp.writeBytes(leb(1));
        exp.write(0x04); exp.writeBytes(new byte[] {'c', 'a', 'l', 'l'});
        exp.write(0x00); exp.write(0x00);
        writeSection(mod, 0x07, exp.toByteArray());

        ByteArrayOutputStream elem = new ByteArrayOutputStream();
        elem.writeBytes(leb(1));
        elem.write(0x01);                              // passive funcref
        elem.write(0x00);
        elem.writeBytes(leb(1));                       // 1 funcidx
        elem.write(0x00);
        writeSection(mod, 0x09, elem.toByteArray());

        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.writeBytes(leb(1));
        data.write(0x01);                              // passive
        data.write(0x03); data.writeBytes(new byte[] {1, 2, 3});
        writeSection(mod, 0x0B, data.toByteArray());

        ByteArrayOutputStream names = new ByteArrayOutputStream();
        names.writeBytes(leb(1)); names.write(0x00);   // 1 entry, func 0
        names.write(0x03); names.writeBytes(new byte[] {'f', 'o', 'o'});
        ByteArrayOutputStream custom = new ByteArrayOutputStream();
        custom.write(0x04); custom.writeBytes(new byte[] {'n', 'a', 'm', 'e'});
        custom.write(0x01); custom.writeBytes(leb(names.size()));
        custom.writeBytes(names.toByteArray());
        writeSection(mod, 0x00, custom.toByteArray());

        ByteArrayOutputStream code = new ByteArrayOutputStream();
        code.writeBytes(leb(1)); code.write(0x02); code.write(0x00); code.write(0x0B);
        writeSection(mod, 0x0A, code.toByteArray());

        try {
            WasmVm.validateCode(mod.toByteArray());
        } catch (Throwable e) {
            String msg = String.valueOf(e.getMessage());
            assertTrue(!msg.contains("too many") && !msg.contains("bytes left"),
                "benign sections must not trip the pre-scan: " + msg);
        }
    }

    @Test
    void bundledTemplatesAllPassThePreScan() {
        // Every checked-in template must sail through validateCode: the pre-scan adding a
        // rejection for a module the network already runs would be a consensus regression.
        String[] templates = {
            "/counter.wasm", "/emitter.wasm", "/router.wasm", "/token.wasm",
            "/pair.wasm", "/amm.wasm", "/launchpad.wasm", "/agent_wallet.wasm"
        };
        for (String t : templates) {
            byte[] wasm = load(t);
            try {
                WasmVm.validateCode(wasm);
            } catch (Throwable e) {
                fail(t + " rejected by validateCode (pre-scan regression?): " + e);
            }
        }
    }

    private static void writeSection(ByteArrayOutputStream mod, int id, byte[] contents) {
        mod.write(id);
        mod.writeBytes(leb(contents.length));
        mod.writeBytes(contents);
    }

    private static byte[] load(String resource) {
        try (var in = WasmPreScanCountsTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + resource);
            }
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
