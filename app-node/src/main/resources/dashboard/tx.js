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

  const KIND = {
    TRANSFER: 0, DEPLOY: 1, CALL: 2,
    BOX_CREATE: 3, BOX_UPDATE: 4, BOX_SPEND: 5, BOX_COLLECT: 6,
    TOKEN_MINT: 7, TOKEN_TRANSFER: 8, TOKEN_BURN: 9,
  };

  /** Box register type tags (BoxRegisterType codes). */
  const REGISTER = { BYTES: 0, I64: 1, BOOL: 2, ADDRESS: 3, HASH32: 4, STRING: 5 };

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

  // ---- data boxes (BoxPayload wire format) ----

  /**
   * Encodes one register value to its payload bytes. reg = {type, value}:
   * BYTES/HASH32 take hex, ADDRESS takes a 50-hex address, I64 a decimal
   * (8 bytes big-endian, matching the chain's integer convention), BOOL
   * "true"/"false", STRING UTF-8 text.
   */
  function registerPayload(reg) {
    switch (reg.type) {
      case 'I64': return be(BigInt(reg.value), 8);
      case 'BOOL': return new Uint8Array([reg.value === true || reg.value === 'true' ? 1 : 0]);
      case 'ADDRESS': return addressBytes(reg.value);
      case 'HASH32': {
        const b = hexToBytes(reg.value);
        if (b.length !== 32) throw new Error('HASH32: 32 octets attendus');
        return b;
      }
      case 'STRING': return new TextEncoder().encode(reg.value);
      default: return hexToBytes(reg.value || '');
    }
  }

  function encodeRegisters(regs) {
    const pieces = [new Uint8Array([regs.length])];
    for (const reg of regs) {
      const payload = registerPayload(reg);
      pieces.push(new Uint8Array([REGISTER[reg.type] ?? 0]), be(payload.length, 2), payload);
    }
    return C.concat(...pieces);
  }

  /** BOX_CREATE data: regCount(1) || regs. */
  function encodeBoxCreate(regs) { return encodeRegisters(regs); }

  /** BOX_UPDATE data: boxId(32) || regCount(1) || regs. */
  function encodeBoxUpdate(boxIdHex, regs) {
    const id = hexToBytes(boxIdHex);
    if (id.length !== 32) throw new Error('box id: 32 octets attendus');
    return C.concat(id, encodeRegisters(regs));
  }

  /** BOX_SPEND / BOX_COLLECT data: boxId(32). */
  function encodeBoxTarget(boxIdHex) {
    const id = hexToBytes(boxIdHex);
    if (id.length !== 32) throw new Error('box id: 32 octets attendus');
    return id;
  }

  /** Box id for a BOX_CREATE: SHA256(creator(25) || nonce(8 BE) || "rzbox"). */
  function deriveBoxId(creatorHex, nonce) {
    return bytesToHex(C.sha256(C.concat(addressBytes(creatorHex), be(nonce, 8),
      new TextEncoder().encode('rzbox'))));
  }

  /**
   * Serialized box size for a register set (the min-value / rent base):
   * id(32)+owner(25)+value(8)+created(8)+rentPaid(8)+regCount(1) + Σ(tag+len+payload).
   */
  function boxSerializedSize(regs) {
    let size = 32 + 25 + 8 + 8 + 8 + 1;
    for (const reg of regs) size += 3 + registerPayload(reg).length;
    return size;
  }

  // ---- native tokens (TokenPayload wire format) ----

  /** TOKEN_MINT data: amount(8 BE) || decimals(1) || symLen(1) || symbol || nameLen(1) || name. */
  function encodeTokenMint(amount, decimals, symbol, name) {
    const sym = new TextEncoder().encode(symbol);
    const nm = new TextEncoder().encode(name);
    if (sym.length > 255 || nm.length > 255) throw new Error('symbole/nom trop long');
    return C.concat(be(BigInt(amount), 8), new Uint8Array([Number(decimals) & 0xff]),
      new Uint8Array([sym.length]), sym, new Uint8Array([nm.length]), nm);
  }

  /** TOKEN_TRANSFER / TOKEN_BURN data: tokenId(32) || amount(8 BE). */
  function encodeTokenAmount(tokenIdHex, amount) {
    const id = hexToBytes(tokenIdHex);
    if (id.length !== 32) throw new Error('token id: 32 octets attendus');
    return C.concat(id, be(BigInt(amount), 8));
  }

  /** Token id for a TOKEN_MINT: SHA256(minter(25) || nonce(8 BE) || "rztoken"). */
  function deriveTokenId(minterHex, nonce) {
    return bytesToHex(C.sha256(C.concat(addressBytes(minterHex), be(nonce, 8),
      new TextEncoder().encode('rztoken'))));
  }

  return {
    KIND, REGISTER, hexToBytes, bytesToHex, be, le64,
    addressFromPublicKey, addressBytes, buildSigned, deriveContractAddress, buildCallPayload,
    encodeBoxCreate, encodeBoxUpdate, encodeBoxTarget, deriveBoxId, boxSerializedSize,
    encodeTokenMint, encodeTokenAmount, deriveTokenId,
  };
})();

if (typeof module !== 'undefined') module.exports = RzTx;
