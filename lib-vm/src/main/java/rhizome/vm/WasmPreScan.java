package rhizome.vm;

import java.util.List;

import com.dylibso.chicory.wasm.types.OpCode;
import com.dylibso.chicory.wasm.types.WasmEncoding;

/**
 * The byte-level pre-scan that bounds every attacker-declared count Chicory's parser turns into
 * an eager allocation, by reading the raw WASM byte stream BEFORE handing it to
 * {@code Parser.parse}. Extracted from {@link WasmVm} (archi-review lot L20): the decoder is a
 * pure function of the bytes, so it owns no state — the runtime's pools and module cache stay
 * on {@code WasmVm}, static out of isolation necessity.
 *
 * <p>This is the load-bearing half of the module-size guards: Chicory 1.7.5's parser
 * materialises several count-sized structures <em>as it parses</em>, before
 * {@link WasmModuleGuard#rejectOversizedAllocations} (which reads the already-built module) can
 * reject it — and, critically, <em>before</em> the buffer underflow that rejects a count the
 * section cannot actually contain:
 *
 * <ul>
 *   <li>type section: {@code new ArrayList<>(paramCount/returnCount)} per functype, {@code
 *       new int[count]} per sub type, {@code new SubType[count]} per recursion group;
 *   <li>element section: {@code new ArrayList<>(initCnt)} per segment;
 *   <li>data section: {@code new byte[len]} per segment payload;
 *   <li>import/export/custom sections: {@code new byte[len]} per length-prefixed name
 *       (including the internals of the {@code "name"} custom section, which Chicory decodes
 *       eagerly by default);
 *   <li>code section: the per-local expansion {@link WasmVm#MAX_MODULE_TOTAL_LOCALS} exists for
 *       (audit V1).
 * </ul>
 *
 * <p>So a &lt;{@link WasmVm#MAX_CODE_SIZE} module can declare e.g. {@code paramCount = 2^30} in
 * a handful of bytes and force a multi-GB transient allocation inside {@code Parser.parse} —
 * heap-dependent: a big-heap node rejects the module (underflow after the allocation), a
 * small-heap validator hits {@link OutOfMemoryError}, which the deploy path deliberately
 * propagates as fatal — a poison block that wedges memory-constrained validators while the rest
 * of the network accepts it (audit: poison DEPLOY, type-section residual of V1).
 *
 * <p>The scan therefore enforces, ahead of the parse: (a) functype param counts {@literal <=}
 * {@link WasmVm#MAX_FUNCTION_PARAMS} — the exact cap
 * {@link WasmModuleGuard#rejectOversizedAllocations} already applies post-parse, so the deploy
 * verdict is unchanged, only moved ahead of the allocation; (b) every other dangerous
 * count/length {@literal <=} the bytes actually remaining in its section — a condition any
 * <em>successful</em> parse must satisfy (each element consumes at least one byte), so this only
 * ever rejects modules the parser itself would reject, merely before the allocation instead of
 * after it.
 *
 * <p>Framing the scan cannot interpret is left to {@code Parser.parse}: every bail-out below
 * happens at a position where Chicory itself throws (truncated LEB, unknown opcode/form, section
 * overrun), which is <em>before</em> it can reach any later count this scan bounds — so
 * deferring never re-opens the allocation vector, and this method only ever <em>adds</em> a
 * rejection, never masks one. Expression and type skipping consume bytes exactly as Chicory's
 * own {@code OpCode} signature table and value-type reader do, keeping the two byte-aligned by
 * construction.
 *
 * <p>Verified against the pinned 1.7.5 sources (see lib-vm/build.gradle): the bare section
 * counts (type/import/export/element/data/code/function) are only loop bounds in {@code
 * Parser} — every count-sized allocation is enumerated above — and the two whole-section
 * {@code new byte[sectionSize]} sites (raw/custom) are safe because the section loop rejects
 * {@code sectionStart + sectionSize > capacity} before allocating, bounding them by the
 * already-capped module size. {@code WasmPreScanCountsTest} pins this contract against the
 * pinned Chicory and must pass unmodified.
 */
final class WasmPreScan {

    private WasmPreScan() {
    }

    /** Runs the whole-buffer pre-scan; see the class javadoc. */
    static void scan(byte[] code) {
        // magic(4) + version(4); if absent, let the parser produce the canonical error.
        if (code.length < 8) {
            return;
        }
        int[] p = {8};
        long[] moduleLocals = {0};
        while (p[0] < code.length) {
            int sectionId = code[p[0]] & 0xFF;
            p[0]++;
            long sectionSize = readVarU32(code, p);
            if (sectionSize < 0) {
                return; // truncated LEB — defer to Parser
            }
            int sectionStart = p[0];
            long sectionEnd = (long) sectionStart + sectionSize;
            if (sectionEnd > code.length) {
                return; // declared size overruns the buffer — Parser will reject
            }
            switch (sectionId) {
                case 0 -> { if (!preScanCustomSection(code, p, sectionEnd)) return; }
                case 1 -> { if (!preScanTypeSection(code, p, sectionEnd)) return; }
                case 2 -> { if (!preScanImportSection(code, p, sectionEnd)) return; }
                case 7 -> { if (!preScanExportSection(code, p, sectionEnd)) return; }
                case 9 -> { if (!preScanElementSection(code, p, sectionEnd)) return; }
                case 10 -> { if (!preScanCodeSection(code, p, sectionEnd, moduleLocals)) return; }
                case 11 -> { if (!preScanDataSection(code, p, sectionEnd)) return; }
                default -> { } // function/table/memory/global/start/tag/datacount: no pre-sized allocations
            }
            p[0] = (int) sectionEnd; // skip to the next section frame
        }
    }

    /**
     * The code-section locals scan (audit V1): Chicory caps each local-declaration group at
     * 50 000 but not the number of groups, and expands every group into a per-local list as it
     * parses. Rejects as soon as a function's locals exceed {@link WasmVm#MAX_FUNCTION_LOCALS}
     * or the module total exceeds {@link WasmVm#MAX_MODULE_TOTAL_LOCALS}. Returns false to defer
     * framing it cannot interpret to {@code Parser.parse} (which fails closed at the same
     * position).
     */
    private static boolean preScanCodeSection(byte[] code, int[] p, long sectionEnd, long[] moduleLocals) {
        long funcCount = readVarU32(code, p);
        if (funcCount < 0) {
            return false;
        }
        for (long f = 0; f < funcCount; f++) {
            long bodySize = readVarU32(code, p);
            if (bodySize < 0) {
                return false;
            }
            long bodyEnd = (long) p[0] + bodySize;
            if (bodyEnd > sectionEnd) {
                return false;
            }
            long groupCount = readVarU32(code, p);
            if (groupCount < 0) {
                return false;
            }
            long funcLocals = 0;
            for (long g = 0; g < groupCount; g++) {
                long n = readVarU32(code, p);
                if (n < 0 || p[0] >= code.length) {
                    return false; // truncated locals vec — Parser rejects before expanding it
                }
                p[0]++; // valtype byte
                funcLocals += n;
                moduleLocals[0] += n;
                if (funcLocals > WasmVm.MAX_FUNCTION_LOCALS) {
                    throw new IllegalArgumentException("contract function declares too many locals: "
                        + funcLocals + " (max " + WasmVm.MAX_FUNCTION_LOCALS + ")");
                }
                if (moduleLocals[0] > WasmVm.MAX_MODULE_TOTAL_LOCALS) {
                    throw new IllegalArgumentException("contract declares too many total locals: "
                        + moduleLocals[0] + " (max " + WasmVm.MAX_MODULE_TOTAL_LOCALS + ")");
                }
            }
            p[0] = (int) bodyEnd; // skip the function's expression to the next body
        }
        return true;
    }

    /**
     * Walks the type section, bounding the counts Chicory presizes allocations from: recursion
     * groups ({@code new SubType[count]}), sub-type supertype lists ({@code new int[count]}),
     * and functype param/result counts ({@code new ArrayList<>(count)}). Struct field lists are
     * <em>not</em> pre-sized by Chicory, so they are walked only to stay byte-aligned.
     */
    private static boolean preScanTypeSection(byte[] code, int[] p, long sectionEnd) {
        long typeCount = readVarU32(code, p, sectionEnd);
        if (typeCount < 0) {
            return false;
        }
        for (long i = 0; i < typeCount; i++) {
            long discriminator = readVarU32(code, p, sectionEnd);
            if (discriminator < 0) {
                return false;
            }
            if (discriminator == 0x4E) { // rec group: a counted run of sub types
                long count = readVarU32(code, p, sectionEnd);
                if (count < 0) {
                    return false;
                }
                requireFitsSection(count, p, sectionEnd, "subtypes in a recursion group");
                for (long s = 0; s < count; s++) {
                    if (!preScanSubType(code, p, sectionEnd)) {
                        return false;
                    }
                }
            } else if (!preScanSubTypeForm(code, p, sectionEnd, discriminator)) {
                return false;
            }
        }
        return true;
    }

    private static boolean preScanSubType(byte[] code, int[] p, long sectionEnd) {
        long id = readVarU32(code, p, sectionEnd);
        if (id < 0) {
            return false;
        }
        return preScanSubTypeForm(code, p, sectionEnd, id);
    }

    private static boolean preScanSubTypeForm(byte[] code, int[] p, long sectionEnd, long id) {
        if (id == 0x50 || id == 0x4F) { // sub / sub final: supertype index list, then a comptype
            long idxCount = readVarU32(code, p, sectionEnd);
            if (idxCount < 0) {
                return false;
            }
            requireFitsSection(idxCount, p, sectionEnd, "supertypes in a sub type");
            for (long k = 0; k < idxCount; k++) {
                if (readVarU32(code, p, sectionEnd) < 0) {
                    return false;
                }
            }
            long comp = readVarU32(code, p, sectionEnd);
            if (comp < 0) {
                return false;
            }
            return preScanCompType(code, p, sectionEnd, comp);
        }
        return preScanCompType(code, p, sectionEnd, id);
    }

    private static boolean preScanCompType(byte[] code, int[] p, long sectionEnd, long id) {
        if (id == 0x60) { // functype
            long paramCount = readVarU32(code, p, sectionEnd);
            if (paramCount < 0) {
                return false;
            }
            // Chicory presizes new ArrayList<>(paramCount) BEFORE reading the params. The same
            // cap rejectOversizedAllocations enforces post-parse, so the deploy verdict is
            // unchanged — only moved ahead of the allocation.
            if (paramCount > WasmVm.MAX_FUNCTION_PARAMS) {
                throw new IllegalArgumentException("contract function type declares too many params: "
                    + paramCount + " (max " + WasmVm.MAX_FUNCTION_PARAMS + ")");
            }
            // Bytes-present bound under the cap: each param is a >= 1-byte valtype, so a count
            // past the section end can never parse — rejecting it here is verdict-identical and
            // skips Chicory's presized ArrayList(paramCount) entirely.
            requireFitsSection(paramCount, p, sectionEnd, "params in a function type");
            for (long j = 0; j < paramCount; j++) {
                if (!skipValType(code, p, sectionEnd)) {
                    return false;
                }
            }
            long resultCount = readVarU32(code, p, sectionEnd);
            if (resultCount < 0) {
                return false;
            }
            // No post-parse result cap exists, so only the bytes-present bound applies (Chicory
            // presizes new ArrayList<>(resultCount); each result is a >= 1-byte LEB).
            requireFitsSection(resultCount, p, sectionEnd, "results in a function type");
            for (long j = 0; j < resultCount; j++) {
                if (!skipValType(code, p, sectionEnd)) {
                    return false;
                }
            }
            return true;
        }
        if (id == 0x5F) { // struct: unpresized field list — walked only to stay aligned
            long fieldCount = readVarU32(code, p, sectionEnd);
            if (fieldCount < 0) {
                return false;
            }
            for (long j = 0; j < fieldCount; j++) {
                if (!skipValType(code, p, sectionEnd)) {
                    return false;
                }
                if (p[0] >= sectionEnd) { // mutability byte
                    return false;
                }
                p[0]++;
            }
            return true;
        }
        if (id == 0x5E) { // array: one field type (valtype or packed), one mutability byte
            if (!skipValType(code, p, sectionEnd)) {
                return false;
            }
            if (p[0] >= sectionEnd) {
                return false;
            }
            p[0]++;
            return true;
        }
        return false; // unknown comptype form — Parser rejects it as malformed at this position
    }

    /**
     * Walks the import section, bounding the length-prefixed module/import names Chicory turns
     * into {@code new byte[len]} before reading them, and skipping each descriptor exactly as
     * Chicory consumes it.
     */
    private static boolean preScanImportSection(byte[] code, int[] p, long sectionEnd) {
        long importCount = readVarU32(code, p, sectionEnd);
        if (importCount < 0) {
            return false;
        }
        for (long i = 0; i < importCount; i++) {
            if (!skipLengthPrefixed(code, p, sectionEnd, "module name")
                    || !skipLengthPrefixed(code, p, sectionEnd, "import name")) {
                return false;
            }
            long kind = readVarU32(code, p, sectionEnd);
            if (kind < 0) {
                return false;
            }
            if (kind == 0x00) { // function: type index
                if (readVarU32(code, p, sectionEnd) < 0) {
                    return false;
                }
            } else if (kind == 0x01) { // table: reference type + limits
                if (!skipValType(code, p, sectionEnd) || !skipLimits(code, p, sectionEnd)) {
                    return false;
                }
            } else if (kind == 0x02) { // memory: limits
                if (!skipLimits(code, p, sectionEnd)) {
                    return false;
                }
            } else if (kind == 0x03) { // global: valtype + mutability byte
                if (!skipValType(code, p, sectionEnd) || p[0] >= sectionEnd) {
                    return false;
                }
                p[0]++;
            } else if (kind == 0x04) { // tag: attribute byte + type index
                if (p[0] >= sectionEnd) {
                    return false;
                }
                p[0]++;
                if (readVarU32(code, p, sectionEnd) < 0) {
                    return false;
                }
            } else {
                return false; // ExternalType.byId throws at this position
            }
        }
        return true;
    }

    /** Walks the export section, bounding name lengths as in the import section. */
    private static boolean preScanExportSection(byte[] code, int[] p, long sectionEnd) {
        long exportCount = readVarU32(code, p, sectionEnd);
        if (exportCount < 0) {
            return false;
        }
        for (long i = 0; i < exportCount; i++) {
            if (!skipLengthPrefixed(code, p, sectionEnd, "export name")) {
                return false;
            }
            if (readVarU32(code, p, sectionEnd) < 0) { // kind — Parser's byId rejects invalid ones
                return false;
            }
            if (readVarU32(code, p, sectionEnd) < 0) { // index
                return false;
            }
        }
        return true;
    }

    /**
     * Bounds a custom section's own length-prefixed name and, for the {@code "name"} section
     * Chicory decodes eagerly (its default custom parser), every length-prefixed string inside
     * it. Subsection sizes themselves cannot overrun the payload — Chicory's {@code slice()}
     * throws first — so only the inner name lengths need the bytes-present bound.
     */
    private static boolean preScanCustomSection(byte[] code, int[] p, long sectionEnd) {
        long nameLen = readVarU32(code, p, sectionEnd);
        if (nameLen < 0) {
            return false;
        }
        requireFitsSection(nameLen, p, sectionEnd, "custom section name");
        boolean isNameSection = nameLen == 4
            && code[p[0]] == 'n' && code[p[0] + 1] == 'a' && code[p[0] + 2] == 'm' && code[p[0] + 3] == 'e';
        p[0] += (int) nameLen;
        if (!isNameSection) {
            return true; // undecoded payload — no further allocations inside
        }
        while (p[0] < sectionEnd) {
            int subsectionId = code[p[0]] & 0xFF;
            p[0]++;
            long subSize = readVarU32(code, p, sectionEnd);
            if (subSize < 0 || subSize > sectionEnd - p[0]) {
                return false; // Chicory's slice() throws out of bounds here — no allocation first
            }
            long subEnd = (long) p[0] + subSize;
            if (subsectionId == 0) { // module name
                if (!skipLengthPrefixed(code, p, subEnd, "module name")) {
                    return false;
                }
            } else if (subsectionId == 2 || subsectionId == 3) { // local/label names: two-level
                long listCnt = readVarU32(code, p, subEnd);
                if (listCnt < 0) {
                    return false;
                }
                for (long i = 0; i < listCnt; i++) {
                    if (readVarU32(code, p, subEnd) < 0) { // group index
                        return false;
                    }
                    long cnt = readVarU32(code, p, subEnd);
                    if (cnt < 0) {
                        return false;
                    }
                    for (long j = 0; j < cnt; j++) {
                        if (readVarU32(code, p, subEnd) < 0
                                || !skipLengthPrefixed(code, p, subEnd, "local name")) {
                            return false;
                        }
                    }
                }
            } else if (subsectionId == 1 || (subsectionId >= 5 && subsectionId <= 9) || subsectionId == 11) {
                // one-level name maps: count, then (index, name) pairs
                long cnt = readVarU32(code, p, subEnd);
                if (cnt < 0) {
                    return false;
                }
                for (long i = 0; i < cnt; i++) {
                    if (readVarU32(code, p, subEnd) < 0
                            || !skipLengthPrefixed(code, p, subEnd, "name")) {
                        return false;
                    }
                }
            } // else: unknown subsection — Chicory ignores it (forwards compatibility)
            p[0] = (int) subEnd;
        }
        return true;
    }

    /**
     * Walks the element section, bounding each segment's initializer count ({@code new
     * ArrayList<>(initCnt)}) by the bytes the inits must occupy. Segment layout mirrors
     * Chicory's bit-flag decoding exactly, and offset/initializer expressions are skipped with
     * Chicory's own opcode signature table, so the two stay byte-aligned.
     */
    private static boolean preScanElementSection(byte[] code, int[] p, long sectionEnd) {
        long elementCount = readVarU32(code, p, sectionEnd);
        if (elementCount < 0) {
            return false;
        }
        for (long i = 0; i < elementCount; i++) {
            long flags = readVarU32(code, p, sectionEnd);
            if (flags < 0) {
                return false;
            }
            boolean active = (flags & 0b001) == 0;
            boolean hasTableIdx = active && (flags & 0b010) != 0;
            boolean alwaysFuncRef = active && !hasTableIdx;
            boolean exprInit = (flags & 0b100) != 0;
            boolean hasElemKind = !exprInit && !alwaysFuncRef;
            boolean hasRefType = exprInit && !alwaysFuncRef;
            if (hasTableIdx && readVarU32(code, p, sectionEnd) < 0) {
                return false;
            }
            if (active && !skipExpression(code, p, sectionEnd)) {
                return false;
            }
            if (hasElemKind) {
                long ek = readVarU32(code, p, sectionEnd);
                if (ek < 0) {
                    return false;
                }
                if (ek != 0) {
                    return false; // Parser throws "Invalid element kind" at this position
                }
            }
            if (hasRefType && !skipValType(code, p, sectionEnd)) {
                return false;
            }
            long initCnt = readVarU32(code, p, sectionEnd);
            if (initCnt < 0) {
                return false;
            }
            // Each init consumes >= 1 byte (a funcidx LEB, or an expression of >= 2 bytes), so a
            // successful parse needs initCnt <= bytes left — checked before Chicory presizes it.
            requireFitsSection(initCnt, p, sectionEnd, "element initializers");
            for (long e = 0; e < initCnt; e++) {
                if (exprInit) {
                    if (!skipExpression(code, p, sectionEnd)) {
                        return false;
                    }
                } else if (readVarU32(code, p, sectionEnd) < 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Walks the data section, bounding each segment's payload length ({@code new byte[len]})
     * by the bytes the section has left.
     */
    private static boolean preScanDataSection(byte[] code, int[] p, long sectionEnd) {
        long segmentCount = readVarU32(code, p, sectionEnd);
        if (segmentCount < 0) {
            return false;
        }
        for (long i = 0; i < segmentCount; i++) {
            long mode = readVarU32(code, p, sectionEnd);
            if (mode < 0) {
                return false;
            }
            if (mode == 0) {
                if (!skipExpression(code, p, sectionEnd)) {
                    return false;
                }
            } else if (mode == 2) {
                if (readVarU32(code, p, sectionEnd) < 0 // memory index
                        || !skipExpression(code, p, sectionEnd)) {
                    return false;
                }
            } else if (mode != 1) {
                return false; // Parser throws "Failed to parse data segment" at this position
            }
            if (!skipLengthPrefixed(code, p, sectionEnd, "data segment payload")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Skips one value type exactly as Chicory's {@code readValueTypeBuilder} consumes it: a uLEB
     * type opcode (the packed i8/i16 storage types included — they change validity, not byte
     * length), plus a trailing heap-type LEB (same LEB width as Chicory's s32 read) for the
     * 0x63/0x64 ref forms. Returns false only where the encoding is truncated — the parser
     * throws at the same position.
     */
    private static boolean skipValType(byte[] code, int[] p, long sectionEnd) {
        long t = readVarU32(code, p, sectionEnd);
        if (t < 0) {
            return false;
        }
        if (t == 0x63 || t == 0x64) { // ref null / ref: trailing heap type
            return skipLeb(code, p, sectionEnd, 5);
        }
        return true;
    }

    /** Table/memory limits: a kind byte, a min LEB, and a max LEB for the 0x01/0x03 kinds. */
    private static boolean skipLimits(byte[] code, int[] p, long sectionEnd) {
        if (p[0] >= sectionEnd) {
            return false;
        }
        int limitType = code[p[0]] & 0xFF;
        p[0]++;
        if (limitType != 0x00 && limitType != 0x01 && limitType != 0x03) {
            // Every other kind makes Chicory throw (0x02 is "shared memory must have maximum",
            // the rest are malformed) — defer to the parser's canonical error.
            return false;
        }
        if (readVarU32(code, p, sectionEnd) < 0) { // min
            return false;
        }
        if (limitType != 0x00 && readVarU32(code, p, sectionEnd) < 0) { // max
            return false;
        }
        return true;
    }

    /**
     * Bounds a length-prefixed byte string (a name or a data payload): Chicory allocates
     * {@code byte[len]} BEFORE reading the bytes, so a length the section cannot contain must be
     * rejected here — the parser itself only underflows <em>after</em> the allocation.
     */
    private static boolean skipLengthPrefixed(byte[] code, int[] p, long sectionEnd, String what) {
        long len = readVarU32(code, p, sectionEnd);
        if (len < 0) {
            return false;
        }
        requireFitsSection(len, p, sectionEnd, what);
        p[0] += (int) len;
        return true;
    }

    /**
     * Rejects a declared count/length that exceeds the bytes remaining in its section. Any
     * successful parse must satisfy this (each element consumes at least one byte), and Chicory
     * presizes an allocation from the count <em>before</em> discovering the shortfall — so the
     * rejection is verdict-identical to the parser's, minus the heap-dependent allocation.
     */
    private static void requireFitsSection(long count, int[] p, long sectionEnd, String what) {
        if (count > sectionEnd - p[0]) {
            throw new IllegalArgumentException("contract declares too many " + what + ": " + count
                + " (section has " + (sectionEnd - p[0]) + " bytes left)");
        }
    }

    /**
     * Skips a constant expression exactly as Chicory's {@code parseExpression} consumes it:
     * instructions up to the first END opcode (Chicory does not track block nesting here —
     * neither does this, so the two stay byte-aligned). Immediate operands are skipped per
     * Chicory's own {@link OpCode#signature} table. Returns false where the stream is truncated
     * or uses an opcode Chicory's table doesn't define: the parser throws at the same position,
     * before reaching any later count this scan bounds.
     */
    private static boolean skipExpression(byte[] code, int[] p, long sectionEnd) {
        while (p[0] < sectionEnd) {
            int b = code[p[0]] & 0xFF;
            p[0]++;
            int opId = b;
            if (b >= 0xFB && b < 0xFF) { // multi-byte opcode: prefix byte + uLEB sub-opcode
                long sub = readVarU32(code, p, sectionEnd);
                if (sub < 0) {
                    return false;
                }
                // Same 32-bit truncation as Chicory's (int) cast, so a wrapped sub-opcode selects
                // the same table entry (or the same out-of-bounds failure).
                opId = (b << 8) + (int) sub;
            }
            if (opId < 0 || opId >= 0xFF00) {
                return false; // past Chicory's opcode table — it throws out of bounds here
            }
            OpCode op = OpCode.byOpCode(opId);
            if (op == null) {
                return false; // illegal opcode — Parser rejects with MalformedException here
            }
            if (op == OpCode.END) {
                return true;
            }
            List<WasmEncoding> signature = OpCode.signature(op);
            if (signature == null) {
                return false; // Chicory NPEs on a missing signature — it throws here either way
            }
            for (WasmEncoding enc : signature) {
                if (!skipImmediate(code, p, sectionEnd, enc)) {
                    return false;
                }
            }
        }
        return false; // no END before the section ended — Parser reports "expected end opcode"
    }

    /** Consumes one instruction immediate exactly as Chicory's {@code parseInstruction} does. */
    private static boolean skipImmediate(byte[] code, int[] p, long sectionEnd, WasmEncoding enc) {
        switch (enc) {
            case BYTE -> {
                if (p[0] >= sectionEnd) {
                    return false;
                }
                p[0]++;
                return true;
            }
            case VARUINT -> {
                return readVarU32(code, p, sectionEnd) >= 0;
            }
            case VARSINT32 -> {
                return skipLeb(code, p, sectionEnd, 5);
            }
            case VARSINT64 -> {
                return skipLeb(code, p, sectionEnd, 10);
            }
            case FLOAT32 -> {
                return skipBytes(code, p, sectionEnd, 4);
            }
            case FLOAT64 -> {
                return skipBytes(code, p, sectionEnd, 8);
            }
            case V128 -> {
                return skipBytes(code, p, sectionEnd, 16);
            }
            case VEC_VARUINT -> {
                long n = readVarU32(code, p, sectionEnd);
                if (n < 0) {
                    return false;
                }
                for (long j = 0; j < n; j++) {
                    if (readVarU32(code, p, sectionEnd) < 0) {
                        return false;
                    }
                }
                return true;
            }
            case VEC_CATCH -> {
                long n = readVarU32(code, p, sectionEnd);
                if (n < 0) {
                    return false;
                }
                for (long j = 0; j < n; j++) {
                    if (p[0] >= sectionEnd) {
                        return false;
                    }
                    int catchOp = code[p[0]] & 0xFF;
                    p[0]++;
                    if (catchOp > 3) {
                        return false; // past Chicory's catch table — it throws out of bounds here
                    }
                    // All four forms read a label LEB; CATCH/CATCH_REF read a tag LEB first —
                    // two uLEBs either way, so the read order is irrelevant to skipping.
                    if (readVarU32(code, p, sectionEnd) < 0) {
                        return false;
                    }
                    if (catchOp <= 1 && readVarU32(code, p, sectionEnd) < 0) {
                        return false;
                    }
                }
                return true;
            }
            case BLOCK_TYPE -> {
                long t = readVarU32(code, p, sectionEnd);
                if (t < 0) {
                    return false;
                }
                // A value-type block type consumes a trailing heap type for the 0x63/0x64 ref
                // forms and nothing otherwise; anything else is a bare type index, already read.
                if (t == 0x63 || t == 0x64) {
                    return skipLeb(code, p, sectionEnd, 5);
                }
                return true;
            }
            case VEC_VALUE_TYPE -> {
                long n = readVarU32(code, p, sectionEnd);
                if (n < 0) {
                    return false;
                }
                for (long j = 0; j < n; j++) {
                    if (!skipValType(code, p, sectionEnd)) {
                        return false;
                    }
                }
                return true;
            }
            case MEMARG -> {
                long flags = readVarU32(code, p, sectionEnd);
                if (flags < 0) {
                    return false;
                }
                if ((flags >> 6) != 0 && readVarU32(code, p, sectionEnd) < 0) { // memory index
                    return false;
                }
                return readVarU32(code, p, sectionEnd) >= 0; // offset
            }
            default -> {
                return true; // Chicory's switch consumes nothing for unknown encodings either
            }
        }
    }

    /** Skips a LEB of at most {@code maxBytes} bytes (5 for 32-bit, 10 for 64-bit immediates). */
    private static boolean skipLeb(byte[] code, int[] p, long sectionEnd, int maxBytes) {
        for (int i = 0; i < maxBytes; i++) {
            if (p[0] >= sectionEnd) {
                return false;
            }
            boolean cont = (code[p[0]] & 0x80) != 0;
            p[0]++;
            if (!cont) {
                return true;
            }
        }
        return false; // over-long LEB — Parser throws "integer too long" here
    }

    private static boolean skipBytes(byte[] code, int[] p, long sectionEnd, int n) {
        if (sectionEnd - p[0] < n) {
            return false;
        }
        p[0] += n;
        return true;
    }

    /** Whole-buffer variant used by the code-section locals scan. */
    private static long readVarU32(byte[] data, int[] p) {
        return readVarU32(data, p, data.length);
    }

    /**
     * Reads an unsigned LEB128 uint32 at {@code p[0]}, advancing it, never past {@code limit}.
     * Returns {@code -1} (rather than throwing) on a truncated or over-long encoding so
     * {@link #scan} can defer that module to {@code Parser.parse} for the canonical
     * malformed-module error.
     */
    private static long readVarU32(byte[] data, int[] p, long limit) {
        long result = 0;
        int shift = 0;
        while (shift < 35) {
            if (p[0] >= limit) {
                return -1;
            }
            int b = data[p[0]] & 0xFF;
            p[0]++;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result & 0xFFFF_FFFFL;
            }
            shift += 7;
        }
        return -1; // more than 5 bytes — malformed
    }
}
