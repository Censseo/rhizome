/*
 * Rhizome transaction building, byte-for-byte the node's formats:
 *  - address     = 0x00 || RIPEMD160(SHA256(pubkey)) || SHA256(SHA256(ripemd))[0..4]   (PublicAddress.of)
 *  - preimage    = to(25) || from(25) || fee(8 BE) || amount(8 BE) || timestamp(8 BE)
 *                  || chainId(4 BE) || nonce(8 BE) [|| kind(1) || gasLimit(8) || gasPrice(8) || data]
 *  - txid        = SHA256(preimage)  (TransactionImpl.hashContents)
 *  - signature   = Ed25519(seed, txid)
 *  - wire (POST /add_transaction) = signature(64) || signingKey(32) || timestamp(8)
 *                  || to(25) || amount(8) || fee(8) || isTransactionFee(1) || chainId(4)
 *                  || nonce(8) || kind(1) [|| gasLimit(8) || gasPrice(8) || dataLen(4) || data]
 * The binary endpoint is used (not the JSON one) so 64-bit amounts survive intact.
 */
'use strict';

const RzTx = (() => {
  const C = typeof RzCrypto !== 'undefined' ? RzCrypto : require('./crypto.js');

  const KIND = { TRANSFER: 0, DEPLOY: 1, CALL: 2 };

  function hexToBytes(hex) {
    const clean = (hex || '').trim().replace(/^0x/i, '');
    if (clean.length % 2 !== 0 || /[^0-9a-fA-F]/.test(clean)) {
      throw new Error('hex invalide');
    }
    const out = new Uint8Array(clean.length / 2);
    for (let i = 0; i < out.length; i++) {
      out[i] = parseInt(clean.substr(i * 2, 2), 16);
    }
    return out;
  }

  function bytesToHex(bytes) {
    let s = '';
    for (const b of bytes) s += b.toString(16).padStart(2, '0');
    return s;
  }

  function be(value, len) {
    let v = BigInt(value);
    const out = new Uint8Array(len);
    for (let i = len - 1; i >= 0; i--) { out[i] = Number(v & 0xffn); v >>= 8n; }
    return out;
  }

  function le64(value) {
    let v = BigInt(value);
    const out = new Uint8Array(8);
    for (let i = 0; i < 8; i++) { out[i] = Number(v & 0xffn); v >>= 8n; }
    return out;
  }

  /** 25-byte Rhizome address from a 32-byte Ed25519 public key. */
  function addressFromPublicKey(pub) {
    const ripemd = C.ripemd160(C.sha256(pub));
    const checksum = C.sha256(C.sha256(ripemd)).slice(0, 4);
    return C.concat(new Uint8Array([0]), ripemd, checksum);
  }

  function addressBytes(hex) {
    const b = hexToBytes(hex);
    if (b.length !== 25) throw new Error('adresse: 25 octets attendus (50 hex)');
    return b;
  }

  /**
   * Builds and signs a transaction. tx: {to, amount, fee, timestamp, chainId,
   * nonce, kind, data, gasLimit, gasPrice}, seed = 32-byte private key.
   * Returns {txid, signature, wire, json} — wire is the binary DTO to POST.
   */
  function buildSigned(tx, seed) {
    const pub = C.ed25519Public(seed);
    const from = addressFromPublicKey(pub);
    const to = addressBytes(tx.to);
    const kind = tx.kind || KIND.TRANSFER;
    const data = tx.data instanceof Uint8Array ? tx.data : hexToBytes(tx.data || '');

    const pieces = [
      to, from,
      be(tx.fee || 0, 8), be(tx.amount || 0, 8), be(tx.timestamp, 8),
      be(tx.chainId, 4), be(tx.nonce, 8),
    ];
    if (kind !== KIND.TRANSFER) {
      pieces.push(new Uint8Array([kind]), be(tx.gasLimit || 0, 8), be(tx.gasPrice || 0, 8), data);
    }
    const txid = C.sha256(C.concat(...pieces));
    const signature = C.ed25519Sign(seed, txid);

    const wire = [
      signature, pub, be(tx.timestamp, 8), to,
      be(tx.amount || 0, 8), be(tx.fee || 0, 8),
      new Uint8Array([0]), // isTransactionFee
      be(tx.chainId, 4), be(tx.nonce, 8),
      new Uint8Array([kind]),
    ];
    if (kind !== KIND.TRANSFER) {
      wire.push(be(tx.gasLimit || 0, 8), be(tx.gasPrice || 0, 8), be(data.length, 4), data);
    }
    return {
      txid: bytesToHex(txid),
      from: bytesToHex(from),
      signature: bytesToHex(signature),
      wire: C.concat(...wire),
    };
  }

  /** Contract address for a DEPLOY: first 25 bytes of SHA256(deployer(25) || nonce(8 BE)). */
  function deriveContractAddress(deployerHex, nonce) {
    const digest = C.sha256(C.concat(addressBytes(deployerHex), be(nonce, 8)));
    return bytesToHex(digest.slice(0, 25));
  }

  /** Payload builder: selector byte + typed args (address = 25 bytes, u64 = 8 LE, bytes = hex). */
  function buildCallPayload(selector, args) {
    const pieces = [];
    if (selector !== null && selector !== undefined && selector !== '') {
      pieces.push(new Uint8Array([Number(selector)]));
    }
    for (const arg of args || []) {
      if (arg.type === 'address') pieces.push(addressBytes(arg.value));
      else if (arg.type === 'u64') pieces.push(le64(arg.value));
      else pieces.push(hexToBytes(arg.value || ''));
    }
    return C.concat(...pieces);
  }

  return {
    KIND, hexToBytes, bytesToHex, be, le64,
    addressFromPublicKey, addressBytes, buildSigned, deriveContractAddress, buildCallPayload,
  };
})();

if (typeof module !== 'undefined') module.exports = RzTx;
