package rhizome.executor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Wire layouts shared with {@code contracts/conscience.rs} and
 * {@code contracts/executor_registry.rs}. Event payloads are decoded from the log
 * stream; registry call inputs are encoded to drive commit-reveal. Keep the offsets
 * and selectors in lockstep with the two Rust contracts.
 */
final class ConscienceCodec {

    private ConscienceCodec() {}

    // ---- task kinds (executor_registry.rs: task = kind(1) || id(8 LE)) ----
    static final byte KIND_ANSWER = 0;
    static final byte KIND_POST = 1;
    static final byte KIND_TICK = 2;

    // ---- registry selectors ----
    static final byte SEL_REGISTER = 1;
    static final byte SEL_COMMIT = 2;
    static final byte SEL_REVEAL = 3;
    static final byte SEL_CLAIM = 4;

    /** A decoded `conscience.ask` event (see conscience.rs selector 3 log). */
    record Ask(long id, byte[] asker, int tier, long value, byte[] promptHash) {}

    /** conscience.ask data: id(8) || asker(25) || tier(1) || value(8) || prompt_hash(32). */
    static Ask decodeAsk(byte[] data) {
        if (data.length < 74) {
            throw new IllegalArgumentException("short ask payload: " + data.length);
        }
        long id = leU64(data, 0);
        byte[] asker = slice(data, 8, 25);
        int tier = data[33] & 0xFF;
        long value = leU64(data, 34);
        byte[] promptHash = slice(data, 42, 32);
        return new Ask(id, asker, tier, value, promptHash);
    }

    /** conscience.tick data: t(8). Returns the tick counter. */
    static long decodeTick(byte[] data) {
        return leU64(data, 0);
    }

    /** task = kind(1) || id(8 LE). */
    static byte[] task(byte kind, long id) {
        byte[] t = new byte[9];
        t[0] = kind;
        putLeU64(t, 1, id);
        return t;
    }

    /** register() input — value is attached as the transaction amount. */
    static byte[] register() {
        return new byte[] { SEL_REGISTER };
    }

    /** commit(task(9), commitment(32)). */
    static byte[] commit(byte[] task, byte[] commitment) {
        return concat(new byte[] { SEL_COMMIT }, task, commitment);
    }

    /** reveal(task(9), result_hash(32)). */
    static byte[] reveal(byte[] task, byte[] resultHash) {
        return concat(new byte[] { SEL_REVEAL }, task, resultHash);
    }

    /** claim(task(9)). */
    static byte[] claim(byte[] task) {
        return concat(new byte[] { SEL_CLAIM }, task);
    }

    /**
     * Commitment for the commit-reveal round: H(task ‖ result_hash ‖ salt). The
     * registry cannot recompute this on-chain yet (no sha256 host import), so it is
     * the off-chain audit binding — a reveal that does not match its commitment is
     * provable from these bytes and slashable.
     */
    static byte[] commitment(byte[] task, byte[] resultHash, byte[] salt) {
        return sha256(concat(task, resultHash, salt));
    }

    static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static byte[] sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }

    // ---- little-endian + slicing helpers ----

    static long leU64(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (b[off + i] & 0xFFL);
        }
        return v;
    }

    static void putLeU64(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) {
            b[off + i] = (byte) (v & 0xFF);
            v >>>= 8;
        }
    }

    static byte[] slice(byte[] src, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, off, out, 0, len);
        return out;
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int i = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, i, p.length);
            i += p.length;
        }
        return out;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}
