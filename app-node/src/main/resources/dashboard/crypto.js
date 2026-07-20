/*
 * Self-contained crypto for the embedded dashboard: SHA-256, SHA-512,
 * RIPEMD-160 and Ed25519 signing, in pure JS. No WebCrypto dependency, so the
 * wallet works over plain http:// to a remote node (crypto.subtle requires a
 * secure context). Matches the node's primitives: address derivation
 * (PublicAddress.of) and transaction signing (Ed25519 over the SHA-256
 * content hash).
 */
'use strict';

const RzCrypto = (() => {

  // ---------- SHA-256 ----------
  const K256 = new Uint32Array([
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
  ]);

  function sha256(bytes) {
    const l = bytes.length;
    const padded = new Uint8Array(((l + 9 + 63) >> 6) << 6);
    padded.set(bytes);
    padded[l] = 0x80;
    const bitLen = l * 8;
    const dv = new DataView(padded.buffer);
    dv.setUint32(padded.length - 8, Math.floor(bitLen / 0x100000000));
    dv.setUint32(padded.length - 4, bitLen >>> 0);

    const h = new Uint32Array([0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
      0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]);
    const w = new Uint32Array(64);
    for (let off = 0; off < padded.length; off += 64) {
      for (let i = 0; i < 16; i++) w[i] = dv.getUint32(off + i * 4);
      for (let i = 16; i < 64; i++) {
        const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
        const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
        w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
      }
      let [a, b, c, d, e, f, g, hh] = h;
      for (let i = 0; i < 64; i++) {
        const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
        const ch = (e & f) ^ (~e & g);
        const t1 = (hh + S1 + ch + K256[i] + w[i]) >>> 0;
        const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
        const maj = (a & b) ^ (a & c) ^ (b & c);
        const t2 = (S0 + maj) >>> 0;
        hh = g; g = f; f = e; e = (d + t1) >>> 0; d = c; c = b; b = a; a = (t1 + t2) >>> 0;
      }
      h[0] = (h[0] + a) >>> 0; h[1] = (h[1] + b) >>> 0; h[2] = (h[2] + c) >>> 0; h[3] = (h[3] + d) >>> 0;
      h[4] = (h[4] + e) >>> 0; h[5] = (h[5] + f) >>> 0; h[6] = (h[6] + g) >>> 0; h[7] = (h[7] + hh) >>> 0;
    }
    const out = new Uint8Array(32);
    const odv = new DataView(out.buffer);
    for (let i = 0; i < 8; i++) odv.setUint32(i * 4, h[i]);
    return out;
  }

  function rotr(x, n) { return ((x >>> n) | (x << (32 - n))) >>> 0; }

  // ---------- SHA-512 (BigInt words; messages here are tiny, clarity wins) ----------
  const K512 = [
    '428a2f98d728ae22', '7137449123ef65cd', 'b5c0fbcfec4d3b2f', 'e9b5dba58189dbbc',
    '3956c25bf348b538', '59f111f1b605d019', '923f82a4af194f9b', 'ab1c5ed5da6d8118',
    'd807aa98a3030242', '12835b0145706fbe', '243185be4ee4b28c', '550c7dc3d5ffb4e2',
    '72be5d74f27b896f', '80deb1fe3b1696b1', '9bdc06a725c71235', 'c19bf174cf692694',
    'e49b69c19ef14ad2', 'efbe4786384f25e3', '0fc19dc68b8cd5b5', '240ca1cc77ac9c65',
    '2de92c6f592b0275', '4a7484aa6ea6e483', '5cb0a9dcbd41fbd4', '76f988da831153b5',
    '983e5152ee66dfab', 'a831c66d2db43210', 'b00327c898fb213f', 'bf597fc7beef0ee4',
    'c6e00bf33da88fc2', 'd5a79147930aa725', '06ca6351e003826f', '142929670a0e6e70',
    '27b70a8546d22ffc', '2e1b21385c26c926', '4d2c6dfc5ac42aed', '53380d139d95b3df',
    '650a73548baf63de', '766a0abb3c77b2a8', '81c2c92e47edaee6', '92722c851482353b',
    'a2bfe8a14cf10364', 'a81a664bbc423001', 'c24b8b70d0f89791', 'c76c51a30654be30',
    'd192e819d6ef5218', 'd69906245565a910', 'f40e35855771202a', '106aa07032bbd1b8',
    '19a4c116b8d2d0c8', '1e376c085141ab53', '2748774cdf8eeb99', '34b0bcb5e19b48a8',
    '391c0cb3c5c95a63', '4ed8aa4ae3418acb', '5b9cca4f7763e373', '682e6ff3d6b2b8a3',
    '748f82ee5defb2fc', '78a5636f43172f60', '84c87814a1f0ab72', '8cc702081a6439ec',
    '90befffa23631e28', 'a4506cebde82bde9', 'bef9a3f7b2c67915', 'c67178f2e372532b',
    'ca273eceea26619c', 'd186b8c721c0c207', 'eada7dd6cde0eb1e', 'f57d4f7fee6ed178',
    '06f067aa72176fba', '0a637dc5a2c898a6', '113f9804bef90dae', '1b710b35131c471b',
    '28db77f523047d84', '32caab7b40c72493', '3c9ebe0a15c9bebc', '431d67c49c100d4c',
    '4cc5d4becb3e42b6', '597f299cfc657e2a', '5fcb6fab3ad6faec', '6c44198c4a475817',
  ].map(s => BigInt('0x' + s));
  const M64 = (1n << 64n) - 1n;

  function rotr64(x, n) { return ((x >> n) | (x << (64n - n))) & M64; }

  function sha512(bytes) {
    const l = bytes.length;
    const padded = new Uint8Array(((l + 17 + 127) >> 7) << 7);
    padded.set(bytes);
    padded[l] = 0x80;
    // 128-bit length; byte lengths here never exceed 2^50 bits.
    const bitLen = BigInt(l) * 8n;
    for (let i = 0; i < 8; i++) {
      padded[padded.length - 1 - i] = Number((bitLen >> BigInt(8 * i)) & 0xffn);
    }

    const h = [
      0x6a09e667f3bcc908n, 0xbb67ae8584caa73bn, 0x3c6ef372fe94f82bn, 0xa54ff53a5f1d36f1n,
      0x510e527fade682d1n, 0x9b05688c2b3e6c1fn, 0x1f83d9abfb41bd6bn, 0x5be0cd19137e2179n,
    ];
    const w = new Array(80);
    for (let off = 0; off < padded.length; off += 128) {
      for (let i = 0; i < 16; i++) {
        let v = 0n;
        for (let j = 0; j < 8; j++) v = (v << 8n) | BigInt(padded[off + i * 8 + j]);
        w[i] = v;
      }
      for (let i = 16; i < 80; i++) {
        const s0 = rotr64(w[i - 15], 1n) ^ rotr64(w[i - 15], 8n) ^ (w[i - 15] >> 7n);
        const s1 = rotr64(w[i - 2], 19n) ^ rotr64(w[i - 2], 61n) ^ (w[i - 2] >> 6n);
        w[i] = (w[i - 16] + s0 + w[i - 7] + s1) & M64;
      }
      let [a, b, c, d, e, f, g, hh] = h;
      for (let i = 0; i < 80; i++) {
        const S1 = rotr64(e, 14n) ^ rotr64(e, 18n) ^ rotr64(e, 41n);
        const ch = (e & f) ^ (~e & M64 & g);
        const t1 = (hh + S1 + ch + K512[i] + w[i]) & M64;
        const S0 = rotr64(a, 28n) ^ rotr64(a, 34n) ^ rotr64(a, 39n);
        const maj = (a & b) ^ (a & c) ^ (b & c);
        const t2 = (S0 + maj) & M64;
        hh = g; g = f; f = e; e = (d + t1) & M64; d = c; c = b; b = a; a = (t1 + t2) & M64;
      }
      h[0] = (h[0] + a) & M64; h[1] = (h[1] + b) & M64; h[2] = (h[2] + c) & M64; h[3] = (h[3] + d) & M64;
      h[4] = (h[4] + e) & M64; h[5] = (h[5] + f) & M64; h[6] = (h[6] + g) & M64; h[7] = (h[7] + hh) & M64;
    }
    const out = new Uint8Array(64);
    for (let i = 0; i < 8; i++) {
      for (let j = 0; j < 8; j++) {
        out[i * 8 + j] = Number((h[i] >> BigInt(8 * (7 - j))) & 0xffn);
      }
    }
    return out;
  }

  // ---------- RIPEMD-160 ----------
  const RL = [
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
    7, 4, 13, 1, 10, 6, 15, 3, 12, 0, 9, 5, 2, 14, 11, 8,
    3, 10, 14, 4, 9, 15, 8, 1, 2, 7, 0, 6, 13, 11, 5, 12,
    1, 9, 11, 10, 0, 8, 12, 4, 13, 3, 7, 15, 14, 5, 6, 2,
    4, 0, 5, 9, 7, 12, 2, 10, 14, 1, 3, 8, 11, 6, 15, 13,
  ];
  const RR = [
    5, 14, 7, 0, 9, 2, 11, 4, 13, 6, 15, 8, 1, 10, 3, 12,
    6, 11, 3, 7, 0, 13, 5, 10, 14, 15, 8, 12, 4, 9, 1, 2,
    15, 5, 1, 3, 7, 14, 6, 9, 11, 8, 12, 2, 10, 0, 4, 13,
    8, 6, 4, 1, 3, 11, 15, 0, 5, 12, 2, 13, 9, 7, 10, 14,
    12, 15, 10, 4, 1, 5, 8, 7, 6, 2, 13, 14, 0, 3, 9, 11,
  ];
  const SL = [
    11, 14, 15, 12, 5, 8, 7, 9, 11, 13, 14, 15, 6, 7, 9, 8,
    7, 6, 8, 13, 11, 9, 7, 15, 7, 12, 15, 9, 11, 7, 13, 12,
    11, 13, 6, 7, 14, 9, 13, 15, 14, 8, 13, 6, 5, 12, 7, 5,
    11, 12, 14, 15, 14, 15, 9, 8, 9, 14, 5, 6, 8, 6, 5, 12,
    9, 15, 5, 11, 6, 8, 13, 12, 5, 12, 13, 14, 11, 8, 5, 6,
  ];
  const SR = [
    8, 9, 9, 11, 13, 15, 15, 5, 7, 7, 8, 11, 14, 14, 12, 6,
    9, 13, 15, 7, 12, 8, 9, 11, 7, 7, 12, 7, 6, 15, 13, 11,
    9, 7, 15, 11, 8, 6, 6, 14, 12, 13, 5, 14, 13, 13, 7, 5,
    15, 5, 8, 11, 14, 14, 6, 14, 6, 9, 12, 9, 12, 5, 15, 8,
    8, 5, 12, 9, 12, 5, 14, 6, 8, 13, 6, 5, 15, 13, 11, 11,
  ];
  const KL160 = [0x00000000, 0x5a827999, 0x6ed9eba1, 0x8f1bbcdc, 0xa953fd4e];
  const KR160 = [0x50a28be6, 0x5c4dd124, 0x6d703ef3, 0x7a6d76e9, 0x00000000];

  function rotl(x, n) { return ((x << n) | (x >>> (32 - n))) >>> 0; }

  function rmdF(j, x, y, z) {
    if (j < 16) return (x ^ y ^ z) >>> 0;
    if (j < 32) return ((x & y) | (~x & z)) >>> 0;
    if (j < 48) return ((x | ~y) ^ z) >>> 0;
    if (j < 64) return ((x & z) | (y & ~z)) >>> 0;
    return (x ^ (y | ~z)) >>> 0;
  }

  function ripemd160(bytes) {
    const l = bytes.length;
    const padded = new Uint8Array(((l + 9 + 63) >> 6) << 6);
    padded.set(bytes);
    padded[l] = 0x80;
    const bitLen = l * 8;
    const dv = new DataView(padded.buffer);
    // Little-endian bit length.
    dv.setUint32(padded.length - 8, bitLen >>> 0, true);
    dv.setUint32(padded.length - 4, Math.floor(bitLen / 0x100000000), true);

    let h0 = 0x67452301, h1 = 0xefcdab89, h2 = 0x98badcfe, h3 = 0x10325476, h4 = 0xc3d2e1f0;
    const x = new Uint32Array(16);
    for (let off = 0; off < padded.length; off += 64) {
      for (let i = 0; i < 16; i++) x[i] = dv.getUint32(off + i * 4, true);
      let al = h0, bl = h1, cl = h2, dl = h3, el = h4;
      let ar = h0, br = h1, cr = h2, dr = h3, er = h4;
      for (let j = 0; j < 80; j++) {
        const round = Math.floor(j / 16);
        let t = (al + rmdF(j, bl, cl, dl) + x[RL[j]] + KL160[round]) >>> 0;
        t = (rotl(t, SL[j]) + el) >>> 0;
        al = el; el = dl; dl = rotl(cl, 10); cl = bl; bl = t;

        t = (ar + rmdF(79 - j, br, cr, dr) + x[RR[j]] + KR160[round]) >>> 0;
        t = (rotl(t, SR[j]) + er) >>> 0;
        ar = er; er = dr; dr = rotl(cr, 10); cr = br; br = t;
      }
      const t = (h1 + cl + dr) >>> 0;
      h1 = (h2 + dl + er) >>> 0;
      h2 = (h3 + el + ar) >>> 0;
      h3 = (h4 + al + br) >>> 0;
      h4 = (h0 + bl + cr) >>> 0;
      h0 = t;
    }
    const out = new Uint8Array(20);
    const odv = new DataView(out.buffer);
    odv.setUint32(0, h0, true); odv.setUint32(4, h1, true); odv.setUint32(8, h2, true);
    odv.setUint32(12, h3, true); odv.setUint32(16, h4, true);
    return out;
  }

  // ---------- Ed25519 (RFC 8032, sign + public key only) ----------
  const P = (1n << 255n) - 19n;
  const L = (1n << 252n) + 27742317777372353535851937790883648493n;
  const D = 37095705934669439343138083508754565189542113879843219016388785533085940283555n;
  const D2 = (2n * D) % P;
  const Gx = 15112221349535400772501151409588531511454012693041857206046113283949847762202n;
  const Gy = 46316835694926478169428394003475163141307993866256225615783033603165251855960n;

  function mod(a, m) { const r = a % m; return r >= 0n ? r : r + m; }

  function modpow(b, e, m) {
    let r = 1n;
    b = mod(b, m);
    while (e > 0n) {
      if (e & 1n) r = (r * b) % m;
      b = (b * b) % m;
      e >>= 1n;
    }
    return r;
  }

  // Extended homogeneous coordinates (X, Y, Z, T), T = XY/Z.
  const G = [Gx, Gy, 1n, mod(Gx * Gy, P)];
  const IDENTITY = [0n, 1n, 1n, 0n];

  function ptAdd(p1, p2) {
    const [X1, Y1, Z1, T1] = p1, [X2, Y2, Z2, T2] = p2;
    const A = mod((Y1 - X1) * (Y2 - X2), P);
    const B = mod((Y1 + X1) * (Y2 + X2), P);
    const C = mod(T1 * D2 * T2, P);
    const Dd = mod(2n * Z1 * Z2, P);
    const E = B - A, F = Dd - C, Gg = Dd + C, H = B + A;
    return [mod(E * F, P), mod(Gg * H, P), mod(F * Gg, P), mod(E * H, P)];
  }

  function ptMul(scalar, point) {
    let r = IDENTITY;
    let q = point;
    let k = scalar;
    while (k > 0n) {
      if (k & 1n) r = ptAdd(r, q);
      q = ptAdd(q, q);
      k >>= 1n;
    }
    return r;
  }

  function ptCompress(p) {
    const [X, Y, Z] = p;
    const zInv = modpow(Z, P - 2n, P);
    const x = mod(X * zInv, P);
    const y = mod(Y * zInv, P);
    const out = new Uint8Array(32);
    let v = y;
    for (let i = 0; i < 32; i++) { out[i] = Number(v & 0xffn); v >>= 8n; }
    if (x & 1n) out[31] |= 0x80;
    return out;
  }

  function leToBig(bytes) {
    let v = 0n;
    for (let i = bytes.length - 1; i >= 0; i--) v = (v << 8n) | BigInt(bytes[i]);
    return v;
  }

  function bigToLe(v, len) {
    const out = new Uint8Array(len);
    for (let i = 0; i < len; i++) { out[i] = Number(v & 0xffn); v >>= 8n; }
    return out;
  }

  function clampScalar(h) {
    const s = h.slice(0, 32);
    s[0] &= 248;
    s[31] &= 127;
    s[31] |= 64;
    return leToBig(s);
  }

  /** Ed25519 public key (32 bytes) from a 32-byte seed (the private key). */
  function ed25519Public(seed) {
    const h = sha512(seed);
    return ptCompress(ptMul(clampScalar(h), G));
  }

  /** Ed25519 signature (64 bytes) of msg with the 32-byte seed. */
  function ed25519Sign(seed, msg) {
    const h = sha512(seed);
    const s = clampScalar(h);
    const prefix = h.slice(32);
    const A = ptCompress(ptMul(s, G));
    const r = mod(leToBig(sha512(concat(prefix, msg))), L);
    const R = ptCompress(ptMul(r, G));
    const k = mod(leToBig(sha512(concat(R, A, msg))), L);
    const S = mod(r + k * s, L);
    const out = new Uint8Array(64);
    out.set(R);
    out.set(bigToLe(S, 32), 32);
    return out;
  }

  function concat(...arrays) {
    const total = arrays.reduce((n, a) => n + a.length, 0);
    const out = new Uint8Array(total);
    let off = 0;
    for (const a of arrays) { out.set(a, off); off += a.length; }
    return out;
  }

  function randomSeed() {
    const seed = new Uint8Array(32);
    crypto.getRandomValues(seed);
    return seed;
  }

  return { sha256, sha512, ripemd160, ed25519Public, ed25519Sign, concat, randomSeed };
})();

if (typeof module !== 'undefined') module.exports = RzCrypto; // for the node-based vector tests
