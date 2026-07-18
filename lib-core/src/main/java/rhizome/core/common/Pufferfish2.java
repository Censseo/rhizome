package rhizome.core.common;

import java.nio.charset.StandardCharsets;

import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * Pure-Java port of Pufferfish2 ($PF2$), the memory-hard KDF that Pandanite uses
 * as its proof-of-work hash. Ported from the reference C implementation
 * (Jeremi M. Gosney, 2015) bundled with Pandanite at
 * {@code src/external/pufferfish/pufferfish.h}, and validated bit-for-bit
 * against golden vectors generated from that C.
 *
 * <p>Every 64-bit word is serialised little-endian, matching the reference,
 * which reinterprets in-memory {@code uint64} arrays as byte buffers on
 * little-endian hosts (for the HMAC inputs and the P/S key material).
 *
 * <p>An instance holds the transient Feistel state for a single hash and is not
 * thread-safe; use one instance per computation. The static {@link #newHash}
 * entry point does exactly that.
 */
final class Pufferfish2 {

    static final int PF_SBOX_N = 4;
    static final int PF_DIGEST_LENGTH = 64; // SHA-512 output
    static final int PF_SALT_SZ = 16;
    static final int PF_SALT_STRUCT_SZ = 2 + PF_SALT_SZ; // {cost_t, cost_m, salt[16]}

    static final byte[] PF_ID = "$PF2$".getBytes(StandardCharsets.US_ASCII);

    private static final byte[] ITOA64 =
        "./ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            .getBytes(StandardCharsets.US_ASCII);

    /** Blowfish P-array init: the first digits of pi (same constants as the reference). */
    private static final long[] P_INIT = {
        0x243f6a8885a308d3L, 0x13198a2e03707344L, 0xa4093822299f31d0L, 0x082efa98ec4e6c89L,
        0x452821e638d01377L, 0xbe5466cf34e90c6cL, 0xc0ac29b7c97c50ddL, 0x3f84d5b5b5470917L,
        0x9216d5d98979fb1bL, 0xd1310ba698dfb5acL, 0x2ffd72dbd01adfb7L, 0xb8e1afed6a267e96L,
        0xba7c9045f12c7f99L, 0x24a19947b3916cf7L, 0x0801f2e2858efc16L, 0x636920d871574e69L,
        0xa458fea3f4933d7eL, 0x0d95748f728eb658L
    };

    static int bin2encLen(int x) {
        return ((x + 2) / 3) * 4;
    }

    static final int PF_SALTSPACE = 2 + PF_ID.length + bin2encLen(PF_SALT_STRUCT_SZ);
    static final int PF_HASHSPACE = PF_SALTSPACE + bin2encLen(PF_DIGEST_LENGTH);

    private final long[] p = new long[18];
    private long[][] s;
    private int sboxSz;
    private int log2SboxSz;
    private long l;
    private long r;

    private Pufferfish2() {}

    /**
     * Reproduces the reference {@code pf_newhash(pass, pass_sz, cost_t, cost_m, hash)}:
     * returns the {@code PF_HASHSPACE}-byte {@code "$PF2$..."} encoded buffer
     * (zero-padded at the tail exactly like the C buffer). This is the value that
     * the proof-of-work then feeds to a final SHA-256.
     */
    static byte[] newHash(byte[] pass, int costT, int costM) {
        byte[] digest = new Pufferfish2().hashpass(new byte[PF_SALT_SZ], costT, costM, pass);

        byte[] hash = new byte[PF_HASHSPACE];
        int pos = 0;
        System.arraycopy(PF_ID, 0, hash, pos, PF_ID.length);
        pos += PF_ID.length;

        byte[] settings = new byte[PF_SALT_STRUCT_SZ];
        settings[0] = (byte) costT;
        settings[1] = (byte) costM;
        // settings[2..] = 16 zero salt bytes
        pos += pfEncode(hash, pos, settings);
        hash[pos] = '$';

        pfEncode(hash, PF_SALTSPACE - 1, digest);
        return hash;
    }

    /** Reference {@code pf_hashpass}: the memory-hard core. Returns a 64-byte digest. */
    private byte[] hashpass(byte[] saltR, int costT, int costM, byte[] keyR) {
        log2SboxSz = costM + 5;
        sboxSz = 1 << log2SboxSz;

        byte[] salt = hmac(new byte[0], saltR);
        byte[] key = hmac(salt, keyR);
        long[] saltU = leLongs(salt);
        long[] keyU = leLongs(key);

        s = new long[PF_SBOX_N][];
        for (int i = 0; i < PF_SBOX_N; i++) {
            s[i] = new long[sboxSz];
            for (int j = 0; j < sboxSz; j += PF_DIGEST_LENGTH / 8) {
                key = hmac(key, salt);
                keyU = leLongs(key);
                for (int k = 0; k < PF_DIGEST_LENGTH / 8; k++) {
                    s[i][j + k] = keyU[k];
                }
            }
        }

        key = hashSbox(key);
        keyU = leLongs(key);

        for (int i = 0; i < 18; i++) {
            p[i] = P_INIT[i] ^ keyU[i % 8];
        }

        l = 0;
        r = 0;
        encryptP(saltU);
        encryptS(saltU);

        int count = (1 << costT) + 1;
        do {
            l = 0;
            r = 0;
            key = hashSbox(key);
            keyU = leLongs(key);
            rekey(keyU);
        } while (--count > 0);

        key = hashSbox(key);
        return key;
    }

    private byte[] hashSbox(byte[] key) {
        for (int i = 0; i < PF_SBOX_N; i++) {
            key = hmac(key, leBytes(s[i]));
        }
        return key;
    }

    private void encryptP(long[] saltU) {
        expandInto(saltU[0], saltU[1], p, 0, 1);
        expandInto(saltU[2], saltU[3], p, 2, 3);
        expandInto(saltU[4], saltU[5], p, 4, 5);
        expandInto(saltU[6], saltU[7], p, 6, 7);
        expandInto(saltU[0], saltU[1], p, 8, 9);
        expandInto(saltU[2], saltU[3], p, 10, 11);
        expandInto(saltU[4], saltU[5], p, 12, 13);
        expandInto(saltU[6], saltU[7], p, 14, 15);
        expandInto(saltU[0], saltU[1], p, 16, 17);
    }

    private void encryptS(long[] saltU) {
        for (int box = 0; box < PF_SBOX_N; box++) {
            for (int i = 0; i < sboxSz; i += 2) {
                expandInto(saltU[i & 7], saltU[(i + 1) & 7], s[box], i, i + 1);
            }
        }
    }

    private void rekey(long[] keyU) {
        for (int i = 0; i < 18; i++) {
            p[i] ^= keyU[i % 8];
        }
        for (int i = 0; i < 18; i += 2) {
            expandNullInto(p, i, i + 1);
        }
        for (int box = 0; box < PF_SBOX_N; box++) {
            for (int i = 0; i < sboxSz; i += 2) {
                expandNullInto(s[box], i, i + 1);
            }
        }
    }

    /** EXPANDSTATE(a, b, arr[i], arr[j]). */
    private void expandInto(long a, long b, long[] arr, int i, int j) {
        l ^= a;
        r ^= b;
        encipher();
        arr[i] = l;
        arr[j] = r;
    }

    /** EXPANDSTATE_NULL(arr[i], arr[j]). */
    private void expandNullInto(long[] arr, int i, int j) {
        encipher();
        arr[i] = l;
        arr[j] = r;
    }

    private long f(long x) {
        long a = s[0][(int) (x >>> (64 - log2SboxSz))];
        long b = s[1][(int) ((x >>> 35) & (sboxSz - 1))];
        long c = s[2][(int) ((x >>> 19) & (sboxSz - 1))];
        long d = s[3][(int) ((x >>> 3) & (sboxSz - 1))];
        return ((a ^ b) + c) ^ d;
    }

    private void encipher() {
        l ^= p[0];
        r = (r ^ f(l)) ^ p[1];
        l = (l ^ f(r)) ^ p[2];
        r = (r ^ f(l)) ^ p[3];
        l = (l ^ f(r)) ^ p[4];
        r = (r ^ f(l)) ^ p[5];
        l = (l ^ f(r)) ^ p[6];
        r = (r ^ f(l)) ^ p[7];
        l = (l ^ f(r)) ^ p[8];
        r = (r ^ f(l)) ^ p[9];
        l = (l ^ f(r)) ^ p[10];
        r = (r ^ f(l)) ^ p[11];
        l = (l ^ f(r)) ^ p[12];
        r = (r ^ f(l)) ^ p[13];
        l = (l ^ f(r)) ^ p[14];
        r = (r ^ f(l)) ^ p[15];
        l = (l ^ f(r)) ^ p[16];
        r ^= p[17];
        long ll = r;
        long rr = l;
        l = ll;
        r = rr;
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        HMac mac = new HMac(new SHA512Digest());
        mac.init(new KeyParameter(key));
        mac.update(data, 0, data.length);
        byte[] out = new byte[PF_DIGEST_LENGTH];
        mac.doFinal(out, 0);
        return out;
    }

    private static long[] leLongs(byte[] b) {
        long[] out = new long[b.length / 8];
        for (int i = 0; i < out.length; i++) {
            long v = 0;
            for (int j = 0; j < 8; j++) {
                v |= (b[i * 8 + j] & 0xffL) << (8 * j);
            }
            out[i] = v;
        }
        return out;
    }

    private static byte[] leBytes(long[] words) {
        byte[] out = new byte[words.length * 8];
        for (int i = 0; i < words.length; i++) {
            long v = words[i];
            for (int j = 0; j < 8; j++) {
                out[i * 8 + j] = (byte) (v >>> (8 * j));
            }
        }
        return out;
    }

    /** Reference {@code pf_encode}. Writes at {@code dst[dstOff..]}, returns bytes written. */
    private static int pfEncode(byte[] dst, int dstOff, byte[] src) {
        int s = 0;
        int d = dstOff;
        int end = src.length;
        do {
            int c1 = src[s++] & 0xff;
            dst[d++] = ITOA64[c1 >>> 2];
            c1 = (c1 & 0x03) << 4;
            if (s >= end) {
                dst[d++] = ITOA64[c1];
                break;
            }
            int c2 = src[s++] & 0xff;
            c1 |= (c2 >>> 4) & 0x0f;
            dst[d++] = ITOA64[c1];
            c1 = (c2 & 0x0f) << 2;
            if (s >= end) {
                dst[d++] = ITOA64[c1];
                break;
            }
            c2 = src[s++] & 0xff;
            c1 |= (c2 >>> 6) & 0x03;
            dst[d++] = ITOA64[c1];
            dst[d++] = ITOA64[c2 & 0x3f];
        } while (s < end);
        return d - dstOff;
    }
}
