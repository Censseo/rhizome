/*
 * Rhizome node dashboard — single-page app, no build step, no external
 * dependencies. Pages: dashboard (network stats + live events), explorer
 * (blocks / transactions / addresses), wallet (keys stay in the browser,
 * Ed25519 signing in JS), contracts (templates, deploy, call/query), agents
 * (agent-wallet management) and boxes (dormant until the node exposes them).
 */
'use strict';

/* ================= utilities ================= */

const $view = document.getElementById('view');
let pageTimers = [];
let pageSse = null;

function el(tag, attrs, ...children) {
  const node = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (k === 'class') node.className = v;
      else if (k === 'html') node.innerHTML = v;
      else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
      else node.setAttribute(k, v);
    }
  }
  for (const c of children.flat()) {
    if (c === null || c === undefined) continue;
    node.append(c.nodeType ? c : document.createTextNode(c));
  }
  return node;
}

function toast(message, isError) {
  const t = el('div', { class: 'toast' + (isError ? ' err' : '') }, message);
  document.getElementById('toast-zone').append(t);
  setTimeout(() => t.remove(), isError ? 9000 : 5000);
}

async function api(path) {
  const res = await fetch(path);
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.error || ('HTTP ' + res.status));
  return body;
}

async function apiPost(path, json) {
  const res = await fetch(path, { method: 'POST', body: JSON.stringify(json) });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.error || body.status || ('HTTP ' + res.status));
  return body;
}

async function submitWire(wire) {
  const res = await fetch('/add_transaction', { method: 'POST', body: wire });
  const body = await res.json().catch(() => ({}));
  if (!res.ok || body.status !== 'SUCCESS') {
    throw new Error(body.status || body.error || ('HTTP ' + res.status));
  }
  return body;
}

const App = { stats: null, features: null, manifest: null };

function scale() { return BigInt(App.stats ? App.stats.decimalScaleFactor : 1); }

/** base units -> display string. */
function fmtCoins(baseUnits) {
  const s = scale();
  const v = BigInt(baseUnits);
  const whole = v / s;
  const frac = (v % s).toString().padStart(s.toString().length - 1, '0').replace(/0+$/, '');
  return whole.toLocaleString('fr-FR') + (frac ? ',' + frac : '');
}

/** display string ("12,5" or "12.5") -> base units BigInt. */
function parseCoins(str) {
  const s = scale();
  const decimals = s.toString().length - 1;
  const clean = String(str).trim().replace(',', '.');
  if (!/^\d+(\.\d*)?$/.test(clean)) throw new Error('montant invalide');
  const [whole, frac = ''] = clean.split('.');
  if (frac.length > decimals) throw new Error('trop de décimales (max ' + decimals + ')');
  return BigInt(whole) * s + BigInt((frac + '0'.repeat(decimals)).slice(0, decimals) || '0');
}

function short(hex, n = 10) {
  return hex && hex.length > 2 * n ? hex.slice(0, n) + '…' + hex.slice(-6) : (hex || '');
}

function timeAgo(ts) {
  const d = Math.max(0, Date.now() - Number(ts));
  if (d < 60_000) return Math.floor(d / 1000) + ' s';
  if (d < 3_600_000) return Math.floor(d / 60_000) + ' min';
  if (d < 86_400_000) return Math.floor(d / 3_600_000) + ' h';
  return Math.floor(d / 86_400_000) + ' j';
}

function topicAscii(topicHex) {
  try {
    const bytes = RzTx.hexToBytes(topicHex);
    if ([...bytes].every(b => b >= 32 && b < 127)) {
      return String.fromCharCode(...bytes);
    }
  } catch (e) { /* keep hex */ }
  return topicHex;
}

function clearPage() {
  pageTimers.forEach(clearInterval);
  pageTimers = [];
  if (pageSse) { pageSse.close(); pageSse = null; }
  $view.replaceChildren();
}

function every(ms, fn) { fn(); pageTimers.push(setInterval(fn, ms)); }

/* ================= wallet vault (browser-side keys) =================
 * Keys live in IndexedDB (not localStorage), encrypted at rest with a passphrase via
 * WebCrypto (PBKDF2-SHA256 → AES-256-GCM) when a secure context is available (https or
 * localhost — where a wallet should be used). Over a plain-http remote node WebCrypto's
 * subtle API is unavailable; there we fall back to storing the seed unencrypted and warn
 * loudly. The decrypted seed is held only in memory after unlock and never rendered unless
 * the user explicitly reveals it.
 */

const CRYPTO_OK = !!(self.crypto && self.crypto.subtle && self.isSecureContext);
const VAULT_DB = 'rhizome-wallet';
const VAULT_STORE = 'vault';
const VAULT_KEY = 'seed';
const LEGACY_KEY = 'rz.wallet.seed';

function idb() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(VAULT_DB, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(VAULT_STORE);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}
async function idbGet(key) {
  const db = await idb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(VAULT_STORE, 'readonly').objectStore(VAULT_STORE).get(key);
    tx.onsuccess = () => resolve(tx.result || null);
    tx.onerror = () => reject(tx.error);
  });
}
async function idbPut(key, value) {
  const db = await idb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(VAULT_STORE, 'readwrite').objectStore(VAULT_STORE).put(value, key);
    tx.onsuccess = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}
async function idbDel(key) {
  const db = await idb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(VAULT_STORE, 'readwrite').objectStore(VAULT_STORE).delete(key);
    tx.onsuccess = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

async function aesKey(passphrase, salt) {
  const base = await crypto.subtle.importKey('raw', new TextEncoder().encode(passphrase),
    'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey(
    { name: 'PBKDF2', salt, iterations: 200000, hash: 'SHA-256' },
    base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}
async function encryptSeed(seed, passphrase) {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await aesKey(passphrase, salt);
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, seed));
  return { v: 1, enc: 'aes-gcm', salt: [...salt], iv: [...iv], ct: [...ct] };
}
async function decryptSeed(rec, passphrase) {
  const key = await aesKey(passphrase, new Uint8Array(rec.salt));
  const pt = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: new Uint8Array(rec.iv) }, key, new Uint8Array(rec.ct));
  return new Uint8Array(pt);
}

/** Persistent, encrypted key vault (IndexedDB). */
const Vault = {
  async record() { return idbGet(VAULT_KEY); },
  async exists() { return (await this.record()) != null; },
  async isEncrypted() { const r = await this.record(); return !!(r && r.enc); },
  /** Stores a seed, encrypting it under {@code passphrase} when a secure context allows it. */
  async store(seed, passphrase) {
    if (CRYPTO_OK && passphrase) {
      await idbPut(VAULT_KEY, await encryptSeed(seed, passphrase));
    } else {
      await idbPut(VAULT_KEY, { v: 1, enc: null, seed: [...seed] });
    }
  },
  /** Returns the seed bytes, decrypting with {@code passphrase} if the record is encrypted. */
  async open(passphrase) {
    const r = await this.record();
    if (!r) throw new Error('aucun wallet enregistré');
    if (r.enc) return decryptSeed(r, passphrase);
    return new Uint8Array(r.seed);
  },
  async forget() { await idbDel(VAULT_KEY); },
};

// In-memory unlocked state: the decrypted seed lives here only after unlock, so the many
// synchronous seed()/address() callers keep working without touching storage.
const WalletStore = {
  _seed: null,
  seed() { return this._seed; },
  setUnlocked(seedBytes) { this._seed = seedBytes; },
  lock() { this._seed = null; },
  address() {
    return this._seed
      ? RzTx.bytesToHex(RzTx.addressFromPublicKey(RzCrypto.ed25519Public(this._seed))) : null;
  },
};

/** One-time migration of a pre-existing plaintext localStorage seed into the vault. */
async function migrateLegacyWallet() {
  const hex = localStorage.getItem(LEGACY_KEY);
  if (!hex) return;
  try {
    if (!(await Vault.exists())) {
      await Vault.store(RzTx.hexToBytes(hex), null); // unencrypted for now; user can re-encrypt
    }
    localStorage.removeItem(LEGACY_KEY);
  } catch (e) { /* leave legacy key in place if migration fails */ }
}

const AgentStore = {
  list() { return JSON.parse(localStorage.getItem('rz.agents') || '[]'); },
  save(list) { localStorage.setItem('rz.agents', JSON.stringify(list)); },
  add(agent) { const l = this.list(); l.push(agent); this.save(l); },
  remove(address) { this.save(this.list().filter(a => a.address !== address)); },
};

/** Builds, signs and submits a transaction from the browser wallet. */
async function sendFromWallet(fields) {
  const seed = WalletStore.seed();
  if (!seed) throw new Error('aucune clé dans le wallet — ouvrez la page Wallet');
  const address = WalletStore.address();
  const account = await api('/wallet?address=' + address);
  const built = RzTx.buildSigned({
    to: fields.to,
    amount: fields.amount || 0n,
    fee: fields.fee || 0n,
    timestamp: Date.now(),
    chainId: App.stats.chainId,
    nonce: BigInt(account.nextNonce) + BigInt(fields.nonceOffset || 0),
    kind: fields.kind || RzTx.KIND.TRANSFER,
    data: fields.data,
    gasLimit: fields.gasLimit || 0,
    gasPrice: fields.gasPrice || 0,
  }, seed);
  await submitWire(built.wire);
  return built;
}

/* ================= router ================= */

const EMPTY_ADDRESS = '0'.repeat(50);

async function boot() {
  try {
    App.stats = await api('/stats');
    App.features = await api('/features').catch(() => ({ boxes: false }));
    App.manifest = await api('/dashboard/templates/manifest.json').catch(() => ({ templates: [] }));
  } catch (e) {
    $view.replaceChildren(el('div', { class: 'card' },
      el('h3', null, 'Node injoignable'), el('p', { class: 'muted' }, String(e))));
    return;
  }
  document.getElementById('brand-net').textContent =
    App.stats.network + ' · chain ' + App.stats.chainId;
  if (App.features.boxes) document.getElementById('boxes-badge').remove();
  // Migrate any legacy plaintext localStorage key into the vault, then auto-unlock an
  // unencrypted vault so a returning user isn't prompted for a passphrase they never set.
  try {
    await migrateLegacyWallet();
    if ((await Vault.exists()) && !(await Vault.isEncrypted())) {
      WalletStore.setUnlocked(await Vault.open(null));
    }
  } catch (e) { /* vault unavailable; wallet page will surface it */ }
  setInterval(async () => {
    try {
      App.stats = await api('/stats');
      document.getElementById('tip-height').textContent = '#' + App.stats.height;
      document.getElementById('peer-count').textContent = App.stats.peers + ' pair(s)';
    } catch (e) { /* transient */ }
  }, 5000);
  window.addEventListener('hashchange', route);
  route();
}

function route() {
  const hash = location.hash || '#/dashboard';
  const [, page, ...rest] = hash.split('/');
  document.querySelectorAll('#nav a').forEach(a =>
    a.classList.toggle('active', a.dataset.page === page));
  clearPage();
  const pages = {
    dashboard: renderDashboard, explorer: renderExplorer, wallet: renderWallet,
    contracts: renderContracts, agents: renderAgents, boxes: renderBoxes,
  };
  (pages[page] || renderDashboard)(rest.map(decodeURIComponent));
}

/* ================= page: dashboard ================= */

function renderDashboard() {
  const tiles = el('div', { class: 'tiles' });
  const blocksCard = el('div', { class: 'card' }, el('h3', null, 'Derniers blocs'));
  const blocksBody = el('div');
  blocksCard.append(blocksBody);
  const feed = el('div', { class: 'feed' });
  const feedCard = el('div', { class: 'card' },
    el('h3', null, 'Événements de contrats (live)'), feed);

  $view.append(
    el('h1', null, 'Dashboard'),
    el('p', { class: 'sub' }, 'Vue d’ensemble du réseau vue par ce node.'),
    tiles,
    el('div', { class: 'grid2' }, blocksCard, feedCard),
  );

  every(5000, async () => {
    try {
      const s = await api('/stats');
      App.stats = s;
      const tileData = [
        ['Hauteur', '#' + s.height, timeAgo(s.lastBlockTimestamp) + ' depuis le dernier bloc'],
        ['Difficulté', s.difficulty + ' bits', 'cible ' + s.desiredBlockTimeSec + ' s/bloc'],
        ['Temps de bloc', s.avgBlockIntervalMs > 0 ? (s.avgBlockIntervalMs / 1000).toFixed(1) + ' s' : '—',
          'moyenne sur ' + s.windowBlocks + ' blocs'],
        ['Mempool', s.mempool + ' tx', 'en attente'],
        ['Pairs', s.peers, 'connus'],
        ['Récompense', fmtCoins(s.miningReward), 'par bloc'],
        ['Transactions', s.windowTxCount, 'sur ' + s.windowBlocks + ' blocs'],
        ['Travail total', BigInt(s.totalWork).toString(2).length + ' bits', 'log2 du cumul'],
      ];
      if (s.stateRoot) {
        tileData.push(['State root', short(s.stateRoot, 6), 'état authentifié (SMT)']);
      }
      tiles.replaceChildren(...tileData.map(([k, v, sub]) =>
        el('div', { class: 'tile' },
          el('div', { class: 'k' }, k), el('div', { class: 'v' }, String(v)),
          el('div', { class: 's' }, sub))));

      const start = Math.max(1, s.height - 9);
      const res = await api('/blocks?start=' + start + '&end=' + s.height);
      const rows = res.blocks.reverse().map(b => blockRow(b));
      blocksBody.replaceChildren(el('table', null,
        el('thead', null, el('tr', null,
          el('th', null, 'Bloc'), el('th', null, 'Âge'), el('th', null, 'Tx'), el('th', null, 'Hash'))),
        el('tbody', null, rows)));
    } catch (e) { /* transient */ }
  });

  if (App.features.logStream) {
    startEventFeed(feed, null);
  } else {
    feed.append(el('div', { class: 'muted' }, 'Flux SSE indisponible sur ce node.'));
  }
}

function blockRow(b) {
  return el('tr', { class: 'rowlink', onclick: () => location.hash = '#/explorer/block/' + b.height },
    el('td', { class: 'num' }, '#' + b.height),
    el('td', { class: 'muted' }, timeAgo(b.timestamp)),
    el('td', { class: 'num' }, String(b.txCount)),
    el('td', { class: 'mono muted' }, short(b.hash, 14)));
}

function startEventFeed(feedNode, contractFilter) {
  pageSse = new EventSource('/logs/stream');
  pageSse.onmessage = ev => {
    try {
      const log = JSON.parse(ev.data);
      if (contractFilter && log.contract.toLowerCase() !== contractFilter.toLowerCase()) return;
      feedNode.prepend(el('div', { class: 'feed-item' },
        el('span', { class: 'badge blue' }, topicAscii(log.topic)), ' ',
        el('a', { href: '#/explorer/address/' + log.contract, class: 'mono' }, short(log.contract, 8)),
        el('span', { class: 'muted' }, ' @ #' + log.height),
        el('div', { class: 'mono muted' }, short(log.data, 24))));
      while (feedNode.children.length > 50) feedNode.lastChild.remove();
    } catch (e) { /* heartbeat/comment */ }
  };
  feedNode.append(el('div', { class: 'muted' }, 'En attente d’événements…'));
}

/* ================= page: explorer ================= */

function renderExplorer(sub) {
  if (sub[0] === 'block') return renderBlockDetail(Number(sub[1]));
  if (sub[0] === 'tx') return renderTxDetail(sub[1]);
  if (sub[0] === 'address') return renderAddressDetail(sub[1]);

  const input = el('input', { class: 'mono', placeholder: 'Hauteur de bloc, txid (64 hex) ou adresse (50 hex)…' });
  const search = () => {
    const q = input.value.trim().toLowerCase(); // node hex output is uppercase; parsing accepts both
    if (/^\d+$/.test(q)) location.hash = '#/explorer/block/' + q;
    else if (/^[0-9a-f]{64}$/.test(q)) location.hash = '#/explorer/tx/' + q;
    else if (/^[0-9a-f]{50}$/.test(q)) location.hash = '#/explorer/address/' + q;
    else toast('Format non reconnu : hauteur, txid 64 hex ou adresse 50 hex', true);
  };
  input.addEventListener('keydown', e => { if (e.key === 'Enter') search(); });

  const tableZone = el('div');
  const pager = el('div', { class: 'pager' });
  $view.append(
    el('h1', null, 'Explorer'),
    el('p', { class: 'sub' }, 'Navigation dans la chaîne : blocs, transactions, adresses.'),
    el('div', { class: 'searchbar' }, input, el('button', { onclick: search }, 'Rechercher')),
    el('div', { class: 'card' }, el('h3', null, 'Blocs'), tableZone, pager),
  );

  const PAGE = 15;
  let end = App.stats.height;
  async function load() {
    const start = Math.max(1, end - PAGE + 1);
    const res = await api('/blocks?start=' + start + '&end=' + end);
    tableZone.replaceChildren(el('table', null,
      el('thead', null, el('tr', null,
        el('th', null, 'Bloc'), el('th', null, 'Âge'), el('th', null, 'Tx'),
        el('th', null, 'Difficulté'), el('th', null, 'Oncles'), el('th', null, 'Hash'))),
      el('tbody', null, res.blocks.reverse().map(b =>
        el('tr', { class: 'rowlink', onclick: () => location.hash = '#/explorer/block/' + b.height },
          el('td', { class: 'num' }, '#' + b.height),
          el('td', { class: 'muted' }, timeAgo(b.timestamp)),
          el('td', { class: 'num' }, String(b.txCount)),
          el('td', { class: 'num' }, String(b.difficulty)),
          el('td', { class: 'num' }, String(b.uncles)),
          el('td', { class: 'mono muted' }, short(b.hash, 16)))))));
    pager.replaceChildren(
      el('button', { class: 'secondary small', onclick: () => { end = Math.min(App.stats.height, end + PAGE); load(); } }, '← Plus récents'),
      el('button', { class: 'secondary small', onclick: () => { end = Math.max(PAGE, end - PAGE); load(); } }, 'Plus anciens →'),
      el('span', { class: 'muted' }, 'blocs ' + start + ' à ' + end + ' / ' + App.stats.height));
  }
  load().catch(e => toast(String(e), true));
}

async function renderBlockDetail(height) {
  $view.append(el('h1', null, 'Bloc #' + height),
    el('p', { class: 'sub' }, el('a', { href: '#/explorer' }, '← Explorer')));
  try {
    const b = await api('/block?blockId=' + height);
    const txs = b.transactions || [];
    $view.append(
      el('div', { class: 'card' }, el('dl', { class: 'kv' },
        el('dt', null, 'Horodatage'), el('dd', null, new Date(Number(b.timestamp)).toLocaleString('fr-FR') + ' (' + timeAgo(b.timestamp) + ')'),
        el('dt', null, 'Difficulté'), el('dd', null, String(b.difficulty)),
        el('dt', null, 'Merkle root'), el('dd', { class: 'mono' }, b.merkleRoot || ''),
        el('dt', null, 'Bloc parent'), el('dd', { class: 'mono' }, b.lastBlockHash || ''),
        el('dt', null, 'Nonce'), el('dd', { class: 'mono' }, String(b.nonce)),
        el('dt', null, 'Transactions'), el('dd', null, String(txs.length)))),
      el('div', { class: 'card' }, el('h3', null, 'Transactions'),
        txs.length === 0 ? el('p', { class: 'muted' }, 'Aucune transaction.') :
        el('table', null,
          el('thead', null, el('tr', null, el('th', null, 'Txid'), el('th', null, 'Type'),
            el('th', null, 'De'), el('th', null, 'Vers'), el('th', null, 'Montant'))),
          el('tbody', null, txs.map(t => txRow(t, height))))),
    );
  } catch (e) {
    $view.append(el('div', { class: 'card bad' }, String(e)));
  }
}

function txKindBadge(t) {
  const kind = t.kind || (t.from === '' ? 'COINBASE' : 'TRANSFER');
  const cls = kind === 'DEPLOY' ? 'blue' : kind === 'CALL' ? 'green' : '';
  return el('span', { class: 'badge ' + cls }, t.from === '' ? 'COINBASE' : kind);
}

function txRow(t) {
  return el('tr', { class: 'rowlink', onclick: () => location.hash = '#/explorer/tx/' + t.txid },
    el('td', { class: 'mono' }, short(t.txid, 10)),
    el('td', null, txKindBadge(t)),
    el('td', { class: 'mono' }, t.from ? short(t.from, 8) : el('span', { class: 'muted' }, 'coinbase')),
    el('td', { class: 'mono' }, short(t.to, 8)),
    el('td', { class: 'num' }, fmtCoins(t.amount)));
}

async function renderTxDetail(txid) {
  $view.append(el('h1', null, 'Transaction'),
    el('p', { class: 'sub' }, el('a', { href: '#/explorer' }, '← Explorer')));
  const zone = el('div');
  $view.append(zone);
  async function load(depth) {
    zone.replaceChildren(el('div', { class: 'card muted' }, 'Recherche (scan de ' + depth + ' blocs depuis la pointe)…'));
    try {
      const res = await api('/transaction?txid=' + txid + '&depth=' + depth);
      const t = res.transaction;
      zone.replaceChildren(el('div', { class: 'card' }, el('dl', { class: 'kv' },
        el('dt', null, 'Txid'), el('dd', { class: 'mono' }, t.txid),
        el('dt', null, 'Bloc'), el('dd', null, el('a', { href: '#/explorer/block/' + res.height }, '#' + res.height)),
        el('dt', null, 'Type'), el('dd', null, txKindBadge(t)),
        el('dt', null, 'De'), el('dd', { class: 'mono' }, t.from ?
          el('a', { href: '#/explorer/address/' + t.from }, t.from) : 'coinbase'),
        el('dt', null, 'Vers'), el('dd', { class: 'mono' },
          el('a', { href: '#/explorer/address/' + t.to }, t.to)),
        el('dt', null, 'Montant'), el('dd', null, fmtCoins(t.amount)),
        el('dt', null, 'Frais'), el('dd', null, fmtCoins(t.fee)),
        el('dt', null, 'Nonce compte'), el('dd', null, String(t.accountNonce)),
        el('dt', null, 'Horodatage'), el('dd', null, new Date(Number(t.timestamp)).toLocaleString('fr-FR')),
        ...(t.kind && t.kind !== 'TRANSFER' ? [
          el('dt', null, 'Gas limit'), el('dd', null, String(t.gasLimit)),
          el('dt', null, 'Gas price'), el('dd', null, String(t.gasPrice)),
          el('dt', null, 'Data'), el('dd', { class: 'mono' }, short(t.data || '', 48)),
        ] : []))));
    } catch (e) {
      zone.replaceChildren(el('div', { class: 'card' },
        el('p', { class: 'bad' }, String(e)),
        depth < 2000 ? el('button', { class: 'secondary', onclick: () => load(2000) },
          'Chercher plus profond (2000 blocs)') : null));
    }
  }
  load(250);
}

async function renderAddressDetail(address) {
  $view.append(el('h1', null, 'Adresse'),
    el('p', { class: 'sub mono' }, address));
  try {
    const [account, contract] = await Promise.all([
      api('/wallet?address=' + address),
      api('/contract?address=' + address).catch(() => null),
    ]);
    const isContract = contract && contract.exists;
    $view.append(el('div', { class: 'tiles' },
      el('div', { class: 'tile' }, el('div', { class: 'k' }, 'Solde'),
        el('div', { class: 'v' }, fmtCoins(account.balance))),
      el('div', { class: 'tile' }, el('div', { class: 'k' }, 'Nonce'),
        el('div', { class: 'v' }, String(account.nextNonce))),
      el('div', { class: 'tile' }, el('div', { class: 'k' }, 'Type'),
        el('div', { class: 'v' }, isContract ? 'Contrat' : 'Compte'),
        isContract ? el('div', { class: 's' }, contract.codeSize + ' octets de code') : null)));
    if (isContract) {
      $view.append(el('div', { class: 'card' }, el('h3', null, 'Contrat'),
        el('dl', { class: 'kv' },
          el('dt', null, 'Taille du code'), el('dd', null, contract.codeSize + ' octets'),
          el('dt', null, 'Hash du code'), el('dd', { class: 'mono' }, contract.codeHash)),
        el('button', { class: 'secondary', onclick: () => location.hash = '#/contracts/interact/' + address },
          'Interagir avec ce contrat')));
    }
    const histZone = el('div', { class: 'card' }, el('h3', null, 'Historique (scan borné)'),
      el('p', { class: 'muted' }, 'Chargement…'));
    $view.append(histZone);
    const hist = await api('/address_txs?address=' + address + '&depth=2000');
    histZone.replaceChildren(el('h3', null, 'Historique'),
      el('p', { class: 'muted' }, 'Scan des blocs ' + hist.scannedFrom + ' à ' + hist.scannedTo +
        ' — ' + hist.transactions.length + ' transaction(s) trouvée(s).'),
      hist.transactions.length ? el('table', null,
        el('thead', null, el('tr', null, el('th', null, 'Bloc'), el('th', null, 'Txid'),
          el('th', null, 'Type'), el('th', null, 'De'), el('th', null, 'Vers'), el('th', null, 'Montant'))),
        el('tbody', null, hist.transactions.map(t =>
          el('tr', { class: 'rowlink', onclick: () => location.hash = '#/explorer/tx/' + t.txid },
            el('td', { class: 'num' }, '#' + t.height),
            el('td', { class: 'mono' }, short(t.txid, 8)),
            el('td', null, txKindBadge(t)),
            el('td', { class: 'mono' }, t.from ? short(t.from, 6) : 'coinbase'),
            el('td', { class: 'mono' }, short(t.to, 6)),
            el('td', { class: 'num' }, fmtCoins(t.amount)))))) : null);
  } catch (e) {
    $view.append(el('div', { class: 'card bad' }, String(e)));
  }
}

/* ================= page: wallet ================= */

async function renderWallet() {
  $view.append(el('h1', null, 'Wallet'),
    el('p', { class: 'sub' }, 'Les clés restent dans ce navigateur (IndexedDB, chiffrées par une passphrase) — le node ne les voit jamais. La signature Ed25519 est faite localement.'));

  if (WalletStore.seed()) { renderWalletUnlocked(); return; }

  let exists = false;
  let encrypted = false;
  try {
    exists = await Vault.exists();
    encrypted = exists && await Vault.isEncrypted();
  } catch (e) {
    $view.append(el('div', { class: 'callout warn' }, 'Stockage du wallet indisponible : ' + e.message));
    return;
  }

  // Existing encrypted wallet → ask for the passphrase to unlock.
  if (exists && encrypted) {
    const pass = el('input', { type: 'password', placeholder: 'Passphrase' });
    const out = el('div');
    const unlock = async () => {
      try {
        WalletStore.setUnlocked(await Vault.open(pass.value));
        route();
      } catch (e) {
        out.replaceChildren(el('div', { class: 'result-box err' }, 'Passphrase incorrecte ou clé corrompue.'));
      }
    };
    pass.addEventListener('keydown', e => { if (e.key === 'Enter') unlock(); });
    $view.append(el('div', { class: 'card' }, el('h3', null, 'Déverrouiller le wallet'),
      el('label', { class: 'f' }, 'Passphrase'), pass,
      el('button', { onclick: unlock }, 'Déverrouiller'), out,
      el('details', null, el('summary', null, 'Oublier ce wallet'),
        el('button', {
          class: 'danger', onclick: async () => {
            if (confirm('Oublier la clé de ce navigateur ? Sans sauvegarde, les fonds sont perdus.')) {
              await Vault.forget(); WalletStore.lock(); route();
            }
          },
        }, 'Oublier la clé'))));
    return;
  }

  // No wallet yet → create or import, encrypting with a passphrase when the context allows it.
  const importInput = el('input', { class: 'mono', placeholder: 'Clé privée (64 hex)…' });
  const passCreate = el('input', { type: 'password', placeholder: 'Passphrase' });
  const passImport = el('input', { type: 'password', placeholder: 'Passphrase' });
  async function persistAndUnlock(seed, passphrase) {
    if (CRYPTO_OK && !passphrase) throw new Error('choisissez une passphrase');
    await Vault.store(seed, passphrase);
    WalletStore.setUnlocked(seed);
    route();
  }
  $view.append(
    el('div', { class: 'grid2' },
      el('div', { class: 'card' }, el('h3', null, 'Créer un wallet'),
        el('p', { class: 'muted' }, 'Génère une nouvelle clé Ed25519 aléatoire dans le navigateur.'),
        CRYPTO_OK ? el('label', { class: 'f' }, 'Passphrase (chiffre la clé au repos)') : null,
        CRYPTO_OK ? passCreate : null,
        el('button', {
          onclick: async () => {
            try { await persistAndUnlock(RzCrypto.randomSeed(), passCreate.value); }
            catch (e) { toast(e.message, true); }
          },
        }, 'Générer une clé')),
      el('div', { class: 'card' }, el('h3', null, 'Importer une clé'),
        el('label', { class: 'f' }, 'Clé privée (seed Ed25519, 32 octets hex)'), importInput,
        CRYPTO_OK ? el('label', { class: 'f' }, 'Passphrase (chiffre la clé au repos)') : null,
        CRYPTO_OK ? passImport : null,
        el('button', {
          class: 'secondary', onclick: async () => {
            try {
              const seed = RzTx.hexToBytes(importInput.value);
              if (seed.length !== 32) throw new Error('32 octets attendus');
              await persistAndUnlock(seed, passImport.value);
            } catch (e) { toast('Clé invalide : ' + e.message, true); }
          },
        }, 'Importer'))),
    el('div', { class: 'callout warn' }, CRYPTO_OK
      ? 'La clé est chiffrée au repos (AES-256-GCM, passphrase via PBKDF2). Pour un trésor, préférez le wallet CLI et un fichier de clé hors-ligne.'
      : 'Contexte non sécurisé (http distant) : le chiffrement du navigateur est indisponible et la clé serait stockée en clair. Ouvrez le dashboard via https ou http://localhost pour activer le chiffrement au repos.'));
}

function renderWalletUnlocked() {
  const address = WalletStore.address();
  const balanceTile = el('div', { class: 'v' }, '…');
  const nonceTile = el('div', { class: 'v' }, '…');
  every(5000, async () => {
    try {
      const account = await api('/wallet?address=' + address);
      balanceTile.textContent = fmtCoins(account.balance);
      nonceTile.textContent = account.nextNonce;
    } catch (e) { /* transient */ }
  });

  const toInput = el('input', { class: 'mono', placeholder: 'Adresse destinataire (50 hex)' });
  const amountInput = el('input', { placeholder: 'Montant (ex : 12,5)' });
  const feeInput = el('input', { placeholder: 'Frais (unités de base)', value: '0' });
  const resultZone = el('div');

  $view.append(
    el('div', { class: 'tiles' },
      el('div', { class: 'tile' }, el('div', { class: 'k' }, 'Adresse'),
        el('div', { class: 'v mono', style: 'font-size:13px' }, address),
        el('div', { class: 's' }, el('a', { href: '#/explorer/address/' + address }, 'Voir dans l’explorer'))),
      el('div', { class: 'tile' }, el('div', { class: 'k' }, 'Solde'), balanceTile),
      el('div', { class: 'tile' }, el('div', { class: 'k' }, 'Nonce'), nonceTile)),
    el('div', { class: 'card' }, el('h3', null, 'Envoyer'),
      el('label', { class: 'f' }, 'Destinataire'), toInput,
      el('div', { class: 'row' },
        el('div', null, el('label', { class: 'f' }, 'Montant'), amountInput),
        el('div', null, el('label', { class: 'f' }, 'Frais (unités de base)'), feeInput)),
      el('button', {
        onclick: async ev => {
          const btn = ev.target;
          btn.disabled = true;
          try {
            const built = await sendFromWallet({
              to: toInput.value.trim(),
              amount: parseCoins(amountInput.value),
              fee: BigInt(feeInput.value || '0'),
            });
            resultZone.replaceChildren(el('div', { class: 'result-box ok' },
              'Transaction acceptée par le mempool — txid ',
              el('a', { href: '#/explorer/tx/' + built.txid, class: 'mono' }, built.txid)));
            amountInput.value = '';
          } catch (e) {
            resultZone.replaceChildren(el('div', { class: 'result-box err' }, 'Échec : ' + e.message));
          } finally { btn.disabled = false; }
        },
      }, 'Signer et envoyer'), resultZone),
    App.features.tokens ? tokensCard(address) : null,
    el('div', { class: 'row', style: 'margin-top:12px' },
      el('button', { class: 'secondary', onclick: () => { WalletStore.lock(); route(); } }, 'Verrouiller')),
    walletSecurityCard());
}

/** Key-security controls: reveal (opt-in), (re)encrypt with a passphrase, forget. */
function walletSecurityCard() {
  const seedReveal = el('div');
  const passSet = el('input', { type: 'password', placeholder: 'Nouvelle passphrase' });
  return el('details', null, el('summary', null, 'Sécurité de la clé'),
    el('div', { class: 'card' },
      el('p', { class: 'muted' }, 'La clé n’est jamais affichée automatiquement. Révélez-la seulement pour la sauvegarder hors-ligne.'),
      el('button', {
        class: 'secondary', onclick: () => {
          seedReveal.replaceChildren(el('input', { class: 'mono', readonly: '',
            value: RzTx.bytesToHex(WalletStore.seed()) }));
        },
      }, 'Révéler la clé privée'), seedReveal,
      CRYPTO_OK ? el('label', { class: 'f' }, '(Re)chiffrer / changer la passphrase') : null,
      CRYPTO_OK ? passSet : null,
      CRYPTO_OK ? el('button', {
        class: 'secondary', onclick: async () => {
          try {
            if (!passSet.value) throw new Error('passphrase vide');
            await Vault.store(WalletStore.seed(), passSet.value);
            passSet.value = '';
            toast('Clé chiffrée dans ce navigateur.');
          } catch (e) { toast(e.message, true); }
        },
      }, 'Chiffrer') : null,
      el('button', {
        class: 'danger', style: 'margin-top:10px', onclick: async () => {
          if (confirm('Oublier la clé de ce navigateur ? Sans sauvegarde, les fonds sont perdus.')) {
            await Vault.forget(); WalletStore.lock(); route();
          }
        },
      }, 'Oublier la clé')));
}

/**
 * Native-token panel of the wallet: holdings, mint, transfer, burn. Token
 * amounts here are raw units — each token carries its own decimals, shown in
 * the holdings list.
 */
function tokensCard(address) {
  const holdingsZone = el('div');
  const out = el('div');

  async function refreshHoldings() {
    try {
      const res = await api('/tokens?holder=' + address);
      holdingsZone.replaceChildren(res.tokens.length === 0
        ? el('p', { class: 'muted' }, 'Aucun token détenu.')
        : el('table', null,
          el('thead', null, el('tr', null, el('th', null, 'Token'), el('th', null, 'Id'),
            el('th', null, 'Solde (brut)'), el('th', null, 'Décimales'), el('th', null, 'Supply'))),
          el('tbody', null, res.tokens.map(t => el('tr', null,
            el('td', null, t.symbol + ' — ' + t.name),
            el('td', { class: 'mono' }, el('a', {
              href: '#', onclick: ev => { ev.preventDefault(); transferId.value = t.id; },
            }, short(t.id, 8))),
            el('td', { class: 'num' }, String(t.balance)),
            el('td', { class: 'num' }, String(t.decimals)),
            el('td', { class: 'num' }, String(t.totalSupply)))))));
    } catch (e) {
      holdingsZone.replaceChildren(el('div', { class: 'result-box err' }, String(e)));
    }
  }

  const mintSupply = el('input', { placeholder: 'Supply (unités brutes)' });
  const mintDecimals = el('input', { placeholder: 'Décimales (0-18)', value: '0' });
  const mintSymbol = el('input', { placeholder: 'Symbole (ex : PNDA)' });
  const mintName = el('input', { placeholder: 'Nom' });
  const transferId = el('input', { class: 'mono', placeholder: 'Token id (64 hex)' });
  const transferTo = el('input', { class: 'mono', placeholder: 'Destinataire (50 hex)' });
  const transferAmount = el('input', { placeholder: 'Montant (brut)' });

  async function tokenTx(kind, to, data, label) {
    try {
      const built = await sendFromWallet({ to, kind, data });
      out.replaceChildren(el('div', { class: 'result-box ok' },
        label + ' soumis — txid ', el('span', { class: 'mono' }, short(built.txid, 14))));
      setTimeout(refreshHoldings, 3000);
    } catch (e) {
      out.replaceChildren(el('div', { class: 'result-box err' }, label + ' : ' + e.message));
    }
  }

  const card = el('div', { class: 'card' }, el('h3', null, 'Tokens natifs'),
    holdingsZone,
    el('div', { class: 'grid2', style: 'margin-top:14px' },
      el('div', null,
        el('label', { class: 'f' }, 'Émettre un token (TOKEN_MINT — une transaction, pas de contrat)'),
        el('div', { class: 'row' }, mintSymbol, mintName),
        el('div', { class: 'row' }, mintSupply, mintDecimals),
        el('button', {
          class: 'small', onclick: async () => {
            try {
              const account = await api('/wallet?address=' + address);
              const id = RzTx.deriveTokenId(address, BigInt(account.nextNonce));
              await tokenTx(RzTx.KIND.TOKEN_MINT, address,
                RzTx.encodeTokenMint(mintSupply.value.trim(), mintDecimals.value.trim() || '0',
                  mintSymbol.value.trim(), mintName.value.trim()), 'TOKEN_MINT');
              out.append(el('div', { class: 'muted', style: 'margin-top:6px' },
                'Token id (au minage) : ', el('span', { class: 'mono' }, id)));
            } catch (e) { toast(e.message, true); }
          },
        }, 'Émettre')),
      el('div', null,
        el('label', { class: 'f' }, 'Transférer / brûler'),
        transferId, transferTo, transferAmount,
        el('div', { class: 'row' },
          el('button', {
            class: 'small', onclick: () => tokenTx(RzTx.KIND.TOKEN_TRANSFER, transferTo.value.trim(),
              RzTx.encodeTokenAmount(transferId.value.trim(), transferAmount.value.trim() || '0'), 'TOKEN_TRANSFER'),
          }, 'Transférer'),
          el('button', {
            class: 'danger small', onclick: () => {
              if (confirm('Brûler ' + transferAmount.value + ' unités ? Le supply total diminue.')) {
                tokenTx(RzTx.KIND.TOKEN_BURN, address,
                  RzTx.encodeTokenAmount(transferId.value.trim(), transferAmount.value.trim() || '0'), 'TOKEN_BURN');
              }
            },
          }, 'Brûler')))),
    out);
  refreshHoldings();
  return card;
}

/* ================= page: contracts ================= */

function renderContracts(sub) {
  $view.append(el('h1', null, 'Smart contracts'),
    el('p', { class: 'sub' }, 'Templates embarqués, déploiement et interaction (transactions CALL ou lectures sans transaction).'));

  const tabs = el('div', { class: 'tabs' });
  const zone = el('div');
  $view.append(tabs, zone);

  const sections = {
    templates: ['Templates', renderTplGallery],
    deploy: ['Déployer', renderDeploy],
    interact: ['Interagir', renderInteract],
  };
  let current = sub[0] && sections[sub[0]] ? sub[0] : 'templates';
  function activate(name, arg) {
    current = name;
    tabs.replaceChildren(...Object.entries(sections).map(([key, [label]]) =>
      el('button', { class: key === current ? '' : 'secondary', onclick: () => activate(key) }, label)));
    zone.replaceChildren();
    sections[name][1](zone, arg);
  }
  activate(current, sub[1]);
}

function renderTplGallery(zone) {
  if (App.features.tokens) {
    zone.append(el('div', { class: 'callout' },
      'Pour émettre un simple token fongible, préférez les ',
      el('a', { href: '#/wallet' }, 'tokens natifs du wallet'),
      ' (TOKEN_MINT : une transaction, pas de contrat, pas de gas). Le template token.wasm ci-dessous reste utile quand un contrat doit composer avec le token — pools AMM, launchpad, agent wallets.'));
  }
  zone.append(el('div', { class: 'callout' },
    'Les templates sont compilés depuis les sources Rust embarquées (no_std → wasm32). Pour écrire votre propre contrat : partez d’une source ci-dessous, compilez avec ',
    el('span', { class: 'mono' }, 'cargo build --target wasm32-unknown-unknown'),
    ', puis déployez le .wasm via l’onglet Déployer.'));
  const grid = el('div', { class: 'tpl-grid' });
  for (const t of App.manifest.templates) {
    grid.append(el('div', { class: 'tpl' },
      el('h4', null, t.name),
      el('p', null, t.description),
      el('div', { class: 'actions' },
        el('button', { class: 'small', onclick: () => location.hash = '#/contracts/deploy/' + t.id }, 'Déployer'),
        el('button', {
          class: 'secondary small', onclick: async () => {
            const src = await fetch('/dashboard/templates/' + t.source).then(r => r.text());
            const modal = el('div', { class: 'card' },
              el('h3', null, t.source),
              el('pre', { class: 'codebox' }, src),
              el('button', { class: 'secondary small', onclick: () => modal.remove() }, 'Fermer'));
            zone.prepend(modal);
            modal.scrollIntoView({ behavior: 'smooth' });
          },
        }, 'Source'))));
  }
  zone.append(grid);
}

function renderDeploy(zone, templateId) {
  const wallet = WalletStore.address();
  if (!wallet) {
    zone.append(el('div', { class: 'callout warn' }, 'Créez d’abord une clé sur la page ',
      el('a', { href: '#/wallet' }, 'Wallet'), ' pour pouvoir déployer.'));
  }
  const tplSelect = el('select', null,
    el('option', { value: '' }, '— code personnalisé (hex ou fichier) —'),
    ...App.manifest.templates.map(t =>
      el('option', { value: t.id, ...(t.id === templateId ? { selected: '' } : {}) }, t.name)));
  const hexArea = el('textarea', { class: 'mono', placeholder: 'Bytecode WASM en hex… (ou choisissez un template / un fichier)' });
  const fileInput = el('input', { type: 'file', accept: '.wasm' });
  fileInput.addEventListener('change', async () => {
    const f = fileInput.files[0];
    if (f) hexArea.value = RzTx.bytesToHex(new Uint8Array(await f.arrayBuffer()));
  });
  const gasInput = el('input', { value: '2000000' });
  const info = el('div', { class: 'muted', style: 'margin-top:8px' });
  const resultZone = el('div');

  async function currentCode() {
    const id = tplSelect.value;
    if (id) {
      const t = App.manifest.templates.find(x => x.id === id);
      const buf = await fetch('/dashboard/templates/' + t.wasm).then(r => r.arrayBuffer());
      return new Uint8Array(buf);
    }
    return RzTx.hexToBytes(hexArea.value);
  }

  async function refreshInfo() {
    try {
      const code = await currentCode();
      const estimate = 500 + code.length * 10;
      let predicted = '';
      if (wallet) {
        const account = await api('/wallet?address=' + wallet);
        predicted = RzTx.deriveContractAddress(wallet, BigInt(account.nextNonce));
      }
      info.replaceChildren(code.length + ' octets — gas déploiement ≈ ' + estimate +
        (predicted ? '' : ''), predicted ? el('div', null, 'Adresse prévue : ',
          el('span', { class: 'mono' }, predicted)) : '');
    } catch (e) { info.textContent = ''; }
  }
  tplSelect.addEventListener('change', refreshInfo);
  hexArea.addEventListener('input', refreshInfo);
  refreshInfo();

  zone.append(el('div', { class: 'card' }, el('h3', null, 'Déployer un contrat'),
    el('label', { class: 'f' }, 'Template'), tplSelect,
    el('label', { class: 'f' }, 'Ou bytecode WASM'), hexArea,
    el('label', { class: 'f' }, 'Ou fichier .wasm'), fileInput,
    el('label', { class: 'f' }, 'Gas limit'), gasInput,
    info,
    el('button', {
      onclick: async ev => {
        ev.target.disabled = true;
        try {
          const code = await currentCode();
          if (!code.length) throw new Error('aucun bytecode');
          const account = await api('/wallet?address=' + wallet);
          const predicted = RzTx.deriveContractAddress(wallet, BigInt(account.nextNonce));
          const built = await sendFromWallet({
            to: EMPTY_ADDRESS, kind: RzTx.KIND.DEPLOY, data: code,
            gasLimit: BigInt(gasInput.value || '0'),
          });
          resultZone.replaceChildren(el('div', { class: 'result-box ok' },
            'DEPLOY soumis — txid ', el('span', { class: 'mono' }, short(built.txid, 16)),
            el('div', null, 'Adresse du contrat (après minage) : ',
              el('a', { href: '#/contracts/interact/' + predicted, class: 'mono' }, predicted))));
        } catch (e) {
          resultZone.replaceChildren(el('div', { class: 'result-box err' }, 'Échec : ' + e.message));
        } finally { ev.target.disabled = false; }
      },
    }, 'Signer et déployer'), resultZone));
}

function renderInteract(zone, presetAddress) {
  const addrInput = el('input', { class: 'mono', placeholder: 'Adresse du contrat (50 hex)', value: presetAddress || '' });
  const tplSelect = el('select', null,
    el('option', { value: '' }, '— payload hex brut —'),
    ...App.manifest.templates.map(t => el('option', { value: t.id }, t.name)));
  const methodSelect = el('select');
  const argsZone = el('div');
  const rawArea = el('textarea', { class: 'mono', placeholder: 'Payload hex (vide = appel sans données)' });
  const valueInput = el('input', { placeholder: 'Valeur attachée (0)', value: '0' });
  const gasInput = el('input', { value: '1000000' });
  const resultZone = el('div');
  const inspectZone = el('div');

  function currentTemplate() {
    return App.manifest.templates.find(t => t.id === tplSelect.value) || null;
  }
  function currentMethod() {
    const t = currentTemplate();
    return t ? t.methods[Number(methodSelect.value)] : null;
  }
  function refreshMethods() {
    const t = currentTemplate();
    methodSelect.replaceChildren(...(t ? t.methods.map((m, i) =>
      el('option', { value: i }, m.name + (m.view ? ' (lecture)' : ''))) : []));
    methodSelect.style.display = t ? '' : 'none';
    rawArea.style.display = t ? 'none' : '';
    refreshArgs();
  }
  function refreshArgs() {
    const m = currentMethod();
    argsZone.replaceChildren();
    if (!m) return;
    if (m.note) argsZone.append(el('p', { class: 'muted', style: 'margin:8px 0 0' }, m.note));
    for (const a of m.args) {
      argsZone.append(el('label', { class: 'f' }, a.name + ' (' + a.type + ')'),
        el('input', { class: 'mono arg-input', 'data-type': a.type,
          placeholder: a.type === 'u64' ? 'entier' : a.type === 'address' ? '50 hex' : 'hex' }));
    }
  }
  tplSelect.addEventListener('change', refreshMethods);
  methodSelect.addEventListener('change', refreshArgs);
  refreshMethods();

  function buildPayload() {
    const m = currentMethod();
    if (!m) return RzTx.hexToBytes(rawArea.value || '');
    const values = [...argsZone.querySelectorAll('.arg-input')];
    return RzTx.buildCallPayload(m.selector, m.args.map((a, i) => ({
      type: a.type, value: values[i].value.trim(),
    })));
  }

  function decodeOutput(outputHex) {
    const m = currentMethod();
    const bytes = RzTx.hexToBytes(outputHex || '');
    const type = m && m.output;
    const u64 = off => {
      let v = 0n;
      for (let i = 7; i >= 0; i--) v = (v << 8n) | BigInt(bytes[off + i] || 0);
      return v.toString();
    };
    if (type === 'u64' && bytes.length >= 8) return 'u64 = ' + u64(0);
    if (type === 'u64pair' && bytes.length >= 16) return 'a = ' + u64(0) + ', b = ' + u64(8);
    if (type === 'session' && bytes.length >= 33) {
      return 'token = ' + RzTx.bytesToHex(bytes.slice(0, 25)) + ', restant = ' + u64(25);
    }
    return outputHex ? 'hex = ' + outputHex : '(sortie vide)';
  }

  zone.append(el('div', { class: 'card' }, el('h3', null, 'Appeler un contrat'),
    el('label', { class: 'f' }, 'Contrat'), addrInput,
    el('button', {
      class: 'secondary small', style: 'margin-top:6px', onclick: async () => {
        try {
          const c = await api('/contract?address=' + addrInput.value.trim());
          inspectZone.replaceChildren(el('div', { class: 'result-box' + (c.exists ? ' ok' : '') },
            c.exists ? 'Contrat déployé — ' + c.codeSize + ' octets, solde ' + fmtCoins(c.balance) +
              ', code ' + short(c.codeHash, 12) : 'Aucun code à cette adresse.'));
        } catch (e) { inspectZone.replaceChildren(el('div', { class: 'result-box err' }, String(e))); }
      },
    }, 'Inspecter'), inspectZone,
    el('label', { class: 'f' }, 'Interface'), tplSelect, methodSelect, argsZone, rawArea,
    el('div', { class: 'row' },
      el('div', null, el('label', { class: 'f' }, 'Valeur attachée (unités de base)'), valueInput),
      el('div', null, el('label', { class: 'f' }, 'Gas limit'), gasInput)),
    el('div', { class: 'row' },
      el('button', {
        class: 'secondary', onclick: async ev => {
          ev.target.disabled = true;
          try {
            const payload = buildPayload();
            const res = await apiPost('/call_readonly', {
              to: addrInput.value.trim(),
              input: RzTx.bytesToHex(payload),
              from: WalletStore.address() || '',
            });
            resultZone.replaceChildren(el('div', { class: 'result-box ' + (res.success ? 'ok' : 'err') },
              res.success ? 'Lecture OK (' + res.gasUsed + ' gas) — ' + decodeOutput(res.output)
                : 'Échec : ' + (res.error || 'revert')));
          } catch (e) {
            resultZone.replaceChildren(el('div', { class: 'result-box err' }, String(e)));
          } finally { ev.target.disabled = false; }
        },
      }, 'Query (lecture, sans transaction)'),
      el('button', {
        onclick: async ev => {
          ev.target.disabled = true;
          try {
            const built = await sendFromWallet({
              to: addrInput.value.trim(), kind: RzTx.KIND.CALL,
              data: buildPayload(),
              amount: BigInt(valueInput.value || '0'),
              gasLimit: BigInt(gasInput.value || '0'),
            });
            resultZone.replaceChildren(el('div', { class: 'result-box ok' },
              'CALL soumis — txid ', el('a', { href: '#/explorer/tx/' + built.txid, class: 'mono' },
                short(built.txid, 16))));
          } catch (e) {
            resultZone.replaceChildren(el('div', { class: 'result-box err' }, 'Échec : ' + e.message));
          } finally { ev.target.disabled = false; }
        },
      }, 'Transaction CALL')),
    resultZone));
}

/* ================= page: agents ================= */

function renderAgents() {
  $view.append(el('h1', null, 'Agents IA on-chain'),
    el('p', { class: 'sub' },
      'Un agent wallet est un compte-contrat : son owner accorde des session keys plafonnées (budget par token, révocables) à un agent IA, qui agit sans jamais détenir les clés du trésor.'));

  const wallet = WalletStore.address();
  const agentTpl = App.manifest.templates.find(t => t.id === 'agent_wallet');
  const listZone = el('div');
  const detailZone = el('div');

  function refreshList() {
    const agents = AgentStore.list();
    listZone.replaceChildren(el('div', { class: 'card' }, el('h3', null, 'Mes agents'),
      agents.length === 0 ? el('p', { class: 'muted' }, 'Aucun agent enregistré.') :
      el('table', null,
        el('thead', null, el('tr', null, el('th', null, 'Nom'), el('th', null, 'Adresse'), el('th', null, ''))),
        el('tbody', null, agents.map(a => el('tr', null,
          el('td', null, a.name),
          el('td', { class: 'mono' }, el('a', {
            href: '#', onclick: ev => { ev.preventDefault(); showAgent(a); },
          }, short(a.address, 12))),
          el('td', null, el('button', {
            class: 'danger small', onclick: () => { AgentStore.remove(a.address); refreshList(); },
          }, 'Retirer'))))))));
  }

  async function showAgent(agent) {
    detailZone.replaceChildren();
    const address = agent.address;
    const info = await api('/contract?address=' + address).catch(() => null);
    const sessionKeyInput = el('input', { class: 'mono', placeholder: 'Adresse de la session key (50 hex)' });
    const sessionOut = el('div');
    const grantKey = el('input', { class: 'mono', placeholder: 'Session key (adresse 50 hex)' });
    const grantToken = el('input', { class: 'mono', placeholder: 'Contrat token (50 hex)' });
    const grantCap = el('input', { placeholder: 'Plafond (unités du token)' });
    const execTarget = el('input', { class: 'mono', placeholder: 'Contrat cible (50 hex)' });
    const execPayload = el('input', { class: 'mono', placeholder: 'Payload hex' });
    const stTo = el('input', { class: 'mono', placeholder: 'Destinataire (50 hex)' });
    const stAmount = el('input', { placeholder: 'Montant (unités du token)' });
    const actionOut = el('div');
    const feed = el('div', { class: 'feed' });

    async function agentCall(payload, label) {
      try {
        const built = await sendFromWallet({
          to: address, kind: RzTx.KIND.CALL, data: payload, gasLimit: 2_000_000n,
        });
        actionOut.replaceChildren(el('div', { class: 'result-box ok' },
          label + ' soumis — txid ', el('span', { class: 'mono' }, short(built.txid, 16))));
      } catch (e) {
        actionOut.replaceChildren(el('div', { class: 'result-box err' }, label + ' : ' + e.message));
      }
    }

    detailZone.append(el('div', { class: 'card' },
      el('h3', null, 'Agent « ' + agent.name + ' »'),
      el('dl', { class: 'kv' },
        el('dt', null, 'Adresse'), el('dd', { class: 'mono' },
          el('a', { href: '#/explorer/address/' + address }, address)),
        el('dt', null, 'Code'), el('dd', null, info && info.exists ?
          el('span', { class: 'ok' }, info.codeSize + ' octets déployés') :
          el('span', { class: 'warn' }, 'pas encore déployé / miné')),
        el('dt', null, 'Solde'), el('dd', null, info ? fmtCoins(info.balance) : '—')),

      el('h3', { style: 'margin-top:18px' }, 'Vérifier une session'),
      el('div', { class: 'row' }, sessionKeyInput, el('button', {
        class: 'secondary small', style: 'flex:0 0 auto;margin-top:0', onclick: async () => {
          try {
            const payload = RzTx.buildCallPayload(5, [{ type: 'address', value: sessionKeyInput.value.trim() }]);
            const res = await apiPost('/call_readonly', {
              to: address, input: RzTx.bytesToHex(payload),
            });
            if (res.success && res.output && res.output.length >= 66) {
              const bytes = RzTx.hexToBytes(res.output);
              let rem = 0n;
              for (let i = 7; i >= 0; i--) rem = (rem << 8n) | BigInt(bytes[25 + i]);
              sessionOut.replaceChildren(el('div', { class: 'result-box ok' },
                'Session active — token ', el('span', { class: 'mono' }, RzTx.bytesToHex(bytes.slice(0, 25))),
                ', budget restant : ' + rem));
            } else {
              sessionOut.replaceChildren(el('div', { class: 'result-box' }, 'Aucune session pour cette clé.'));
            }
          } catch (e) { sessionOut.replaceChildren(el('div', { class: 'result-box err' }, String(e))); }
        },
      }, 'session_of')), sessionOut,

      el('h3', { style: 'margin-top:18px' }, 'Actions owner'),
      wallet ? null : el('p', { class: 'warn' }, 'Connectez un wallet pour agir.'),
      el('label', { class: 'f' }, 'Accorder une session (grant_session)'),
      grantKey, grantToken, grantCap,
      el('div', { class: 'row' },
        el('button', {
          class: 'small', onclick: () => agentCall(RzTx.buildCallPayload(2, [
            { type: 'address', value: grantKey.value.trim() },
            { type: 'address', value: grantToken.value.trim() },
            { type: 'u64', value: grantCap.value.trim() || '0' },
          ]), 'grant_session'),
        }, 'Accorder'),
        el('button', {
          class: 'danger small', onclick: () => agentCall(RzTx.buildCallPayload(3, [
            { type: 'address', value: grantKey.value.trim() },
          ]), 'revoke_session'),
        }, 'Révoquer cette clé')),
      el('label', { class: 'f' }, 'Appel arbitraire via le wallet (exec)'),
      execTarget, execPayload,
      el('button', {
        class: 'small', onclick: () => {
          try {
            const payload = RzCrypto.concat(new Uint8Array([1]),
              RzTx.addressBytes(execTarget.value.trim()), RzTx.hexToBytes(execPayload.value || ''));
            agentCall(payload, 'exec');
          } catch (e) { toast(e.message, true); }
        },
      }, 'Exécuter'),

      el('h3', { style: 'margin-top:18px' }, 'Dépense de session (session_transfer)'),
      el('p', { class: 'muted' }, 'À signer avec la clé de session (le wallet connecté doit être la session key).'),
      stTo, stAmount,
      el('button', {
        class: 'small', onclick: () => agentCall(RzTx.buildCallPayload(4, [
          { type: 'address', value: stTo.value.trim() },
          { type: 'u64', value: stAmount.value.trim() || '0' },
        ]), 'session_transfer'),
      }, 'Dépenser'),
      actionOut,

      el('h3', { style: 'margin-top:18px' }, 'Activité (grant / spend, live)'),
      feed));

    if (App.features.logStream) startEventFeed(feed, address);
    detailZone.scrollIntoView({ behavior: 'smooth' });
  }

  const nameInput = el('input', { placeholder: 'Nom (ex : agent-trading)' });
  const addrInput = el('input', { class: 'mono', placeholder: 'Adresse d’un agent wallet existant (50 hex)' });
  const deployOut = el('div');

  $view.append(listZone, el('div', { class: 'grid2' },
    el('div', { class: 'card' }, el('h3', null, 'Déployer un nouvel agent wallet'),
      el('p', { class: 'muted' }, 'Déploie le template agent_wallet puis envoie init() — deux transactions (nonces consécutifs), votre wallet devient owner.'),
      el('label', { class: 'f' }, 'Nom local'), nameInput,
      el('button', {
        onclick: async ev => {
          ev.target.disabled = true;
          try {
            if (!wallet) throw new Error('créez d’abord un wallet');
            if (!agentTpl) throw new Error('template agent_wallet absent');
            const buf = await fetch('/dashboard/templates/' + agentTpl.wasm).then(r => r.arrayBuffer());
            const code = new Uint8Array(buf);
            const account = await api('/wallet?address=' + wallet);
            const predicted = RzTx.deriveContractAddress(wallet, BigInt(account.nextNonce));
            await sendFromWallet({
              to: EMPTY_ADDRESS, kind: RzTx.KIND.DEPLOY, data: code, gasLimit: 2_000_000n,
            });
            await sendFromWallet({
              to: predicted, kind: RzTx.KIND.CALL, data: new Uint8Array([0]),
              gasLimit: 500_000n, nonceOffset: 1,
            });
            AgentStore.add({ name: nameInput.value.trim() || 'agent', address: predicted });
            refreshList();
            deployOut.replaceChildren(el('div', { class: 'result-box ok' },
              'Agent wallet en cours de déploiement : ', el('span', { class: 'mono' }, predicted),
              ' — init() enverra l’ownership à votre wallet au minage.'));
          } catch (e) {
            deployOut.replaceChildren(el('div', { class: 'result-box err' }, 'Échec : ' + e.message));
          } finally { ev.target.disabled = false; }
        },
      }, 'Déployer + init'), deployOut),
    el('div', { class: 'card' }, el('h3', null, 'Enregistrer un agent existant'),
      el('label', { class: 'f' }, 'Adresse'), addrInput,
      el('button', {
        class: 'secondary', onclick: () => {
          try {
            RzTx.addressBytes(addrInput.value.trim());
            AgentStore.add({ name: 'agent-' + short(addrInput.value.trim(), 4), address: addrInput.value.trim() });
            refreshList();
          } catch (e) { toast(e.message, true); }
        },
      }, 'Ajouter')),
  ), detailZone);
  refreshList();
}

/* ================= page: boxes ================= */

function renderBoxes() {
  $view.append(el('h1', null, 'Data boxes'),
    el('p', { class: 'sub' }, 'Objets d’état de première classe pour le stockage on-chain (mémoire d’agent, oracles, annuaires) — valeur verrouillée proportionnelle à la taille, rente de stockage, registres typés.'));

  if (!App.features.boxes) {
    $view.append(el('div', { class: 'callout warn' },
      'La couche de data boxes n’est pas active sur ce node. Cette page s’activera automatiquement quand ',
      el('span', { class: 'mono' }, 'GET /features'), ' annoncera ', el('span', { class: 'mono' }, 'boxes: true'), '.'));
    return;
  }

  const wallet = WalletStore.address();
  const REGISTER_TYPES = ['STRING', 'BYTES', 'I64', 'BOOL', 'ADDRESS', 'HASH32'];

  /* ---- list ---- */
  const ownerInput = el('input', { class: 'mono', placeholder: 'Adresse propriétaire (50 hex)', value: wallet || '' });
  const listZone = el('div');

  function registerView(reg) {
    const value = reg.type === 'STRING' ? '« ' + reg.string + ' »'
      : reg.type === 'I64' ? BigInt('0x' + (reg.hex || '0')).toString()
      : reg.type === 'BOOL' ? (reg.hex === '01' ? 'true' : 'false')
      : reg.hex;
    return el('div', { class: 'mono muted', style: 'font-size:12px' },
      el('span', { class: 'badge' }, reg.type), ' ', short(String(value), 32));
  }

  function boxCard(b, refresh) {
    const actions = el('div', { class: 'row' });
    const out = el('div');
    const mine = wallet && b.owner.toLowerCase() === wallet.toLowerCase();
    async function boxTx(kind, data, label) {
      try {
        const built = await sendFromWallet({ to: wallet, kind, data });
        out.replaceChildren(el('div', { class: 'result-box ok' },
          label + ' soumis — txid ', el('span', { class: 'mono' }, short(built.txid, 14))));
        setTimeout(refresh, 3000);
      } catch (e) {
        out.replaceChildren(el('div', { class: 'result-box err' }, label + ' : ' + e.message));
      }
    }
    if (mine) {
      actions.append(
        el('button', {
          class: 'secondary small', onclick: () => {
            const regs = editRegistersPrompt(b);
            if (regs) boxTx(RzTx.KIND.BOX_UPDATE, RzTx.encodeBoxUpdate(b.id, regs), 'BOX_UPDATE');
          },
        }, 'Mettre à jour'),
        el('button', {
          class: 'danger small', onclick: () => {
            if (confirm('Détruire cette box ? La valeur verrouillée (' + fmtCoins(b.value) + ') est remboursée au owner.')) {
              boxTx(RzTx.KIND.BOX_SPEND, RzTx.encodeBoxTarget(b.id), 'BOX_SPEND');
            }
          },
        }, 'Détruire'));
    }
    if (App.stats.height >= b.expiresAtHeight) {
      actions.append(el('button', {
        class: 'small', onclick: () => boxTx(RzTx.KIND.BOX_COLLECT, RzTx.encodeBoxTarget(b.id), 'BOX_COLLECT'),
      }, 'Collecter la rente'));
    }
    return el('div', { class: 'tpl' },
      el('div', { class: 'mono', style: 'font-size:12px' }, short(b.id, 14)),
      el('p', { style: 'margin:6px 0' },
        fmtCoins(b.value) + ' verrouillés · ' + b.sizeBytes + ' octets',
        el('br'), el('span', { class: 'muted' },
          'créée #' + b.createdHeight + ' · expire #' + b.expiresAtHeight +
          (App.stats.height >= b.expiresAtHeight ? ' (rente exigible)' : ''))),
      ...(b.registers || []).map(registerView),
      actions, out);
  }

  // BOX_UPDATE replaces the register list wholesale, so the edit prompt starts
  // from the current registers as JSON — crude but complete for v1.
  function editRegistersPrompt(b) {
    const current = (b.registers || []).map(r => ({
      type: r.type, value: r.type === 'STRING' ? r.string : r.type === 'I64' ? BigInt('0x' + (r.hex || '0')).toString() : r.type === 'BOOL' ? (r.hex === '01' ? 'true' : 'false') : r.hex,
    }));
    const raw = prompt('Registres (JSON [{type, value}], types: ' + REGISTER_TYPES.join('/') + ') :',
      JSON.stringify(current));
    if (raw === null) return null;
    try {
      return JSON.parse(raw);
    } catch (e) { toast('JSON invalide : ' + e.message, true); return null; }
  }

  async function refreshList() {
    const owner = ownerInput.value.trim();
    if (!owner) {
      listZone.replaceChildren(el('p', { class: 'muted' },
        'Renseignez une adresse (ou créez un wallet) pour lister ses boxes.'));
      return;
    }
    try {
      const res = await api('/boxes?owner=' + owner + '&limit=50');
      listZone.replaceChildren(
        res.boxes.length === 0 ? el('p', { class: 'muted' }, 'Aucune box pour cette adresse.') :
        el('div', { class: 'tpl-grid' }, res.boxes.map(b => boxCard(b, refreshList))));
    } catch (e) {
      listZone.replaceChildren(el('div', { class: 'result-box err' }, String(e)));
    }
  }
  ownerInput.addEventListener('change', refreshList);

  /* ---- create ---- */
  const regsZone = el('div');
  const valueInput = el('input', { placeholder: 'Valeur verrouillée (unités de base)' });
  const createOut = el('div');
  const minInfo = el('div', { class: 'muted', style: 'margin-top:8px' });

  function regRow() {
    const typeSel = el('select', null, ...REGISTER_TYPES.map(t => el('option', { value: t }, t)));
    const valInput = el('input', { class: 'mono', placeholder: 'valeur' });
    const row = el('div', { class: 'row', style: 'margin-top:6px' }, typeSel, valInput,
      el('button', { class: 'danger small', style: 'flex:0 0 auto', onclick: () => { row.remove(); refreshMin(); } }, '✕'));
    typeSel.addEventListener('change', refreshMin);
    valInput.addEventListener('input', refreshMin);
    return row;
  }
  function currentRegs() {
    return [...regsZone.querySelectorAll('.row')].map(row => ({
      type: row.querySelector('select').value,
      value: row.querySelector('input').value,
    }));
  }
  function refreshMin() {
    try {
      const size = RzTx.boxSerializedSize(currentRegs());
      const min = BigInt(size) * BigInt(App.stats.minValuePerByte || 1);
      minInfo.textContent = size + ' octets sérialisés — valeur minimale : ' + min + ' unités de base';
      if (!valueInput.value || BigInt(valueInput.value) < min) valueInput.value = min.toString();
    } catch (e) { minInfo.textContent = ''; }
  }

  $view.append(
    el('div', { class: 'card' }, el('h3', null, 'Boxes par propriétaire'),
      el('div', { class: 'searchbar' }, ownerInput,
        el('button', { onclick: refreshList }, 'Lister')), listZone),
    el('div', { class: 'grid2' },
      el('div', { class: 'card' }, el('h3', null, 'Créer une box'),
        wallet ? null : el('p', { class: 'warn' }, 'Créez d’abord un wallet.'),
        el('label', { class: 'f' }, 'Registres (max ' + (App.stats.maxBoxRegisters || 6) + ', remplis densément)'),
        regsZone,
        el('button', { class: 'secondary small', onclick: () => { regsZone.append(regRow()); refreshMin(); } }, '+ registre'),
        el('label', { class: 'f' }, 'Valeur verrouillée (remboursée à la destruction)'), valueInput,
        minInfo,
        el('button', {
          onclick: async ev => {
            ev.target.disabled = true;
            try {
              const account = await api('/wallet?address=' + wallet);
              const boxId = RzTx.deriveBoxId(wallet, BigInt(account.nextNonce));
              const built = await sendFromWallet({
                to: wallet, kind: RzTx.KIND.BOX_CREATE,
                data: RzTx.encodeBoxCreate(currentRegs()),
                amount: BigInt(valueInput.value || '0'),
              });
              createOut.replaceChildren(el('div', { class: 'result-box ok' },
                'BOX_CREATE soumis — txid ', el('span', { class: 'mono' }, short(built.txid, 14)),
                el('div', null, 'Box id (au minage) : ', el('span', { class: 'mono' }, boxId))));
              setTimeout(refreshList, 3000);
            } catch (e) {
              createOut.replaceChildren(el('div', { class: 'result-box err' }, 'Échec : ' + e.message));
            } finally { ev.target.disabled = false; }
          },
        }, 'Signer et créer'), createOut),
      el('div', { class: 'card' }, el('h3', null, 'Économie des boxes'),
        el('dl', { class: 'kv' },
          el('dt', null, 'minValuePerByte'), el('dd', null, String(App.stats.minValuePerByte)),
          el('dt', null, 'Rente / période'), el('dd', null, String(App.stats.storageFeeFactor) + ' par octet'),
          el('dt', null, 'Période de rente'), el('dd', null, App.stats.storagePeriodBlocks + ' blocs'),
          el('dt', null, 'State root'), el('dd', { class: 'mono' },
            App.stats.stateRoot ? short(App.stats.stateRoot, 16) : '—')),
        el('p', { class: 'muted', style: 'font-size:12.5px' },
          'Une box verrouille de la valeur proportionnelle à sa taille (anti-dust). Passé la période, la rente devient exigible : n’importe qui peut la collecter (BOX_COLLECT) — une box sans payeur finit détruite, l’état ne grossit pas indéfiniment.'))));
  refreshList();
  regsZone.append(regRow());
  refreshMin();
}

/* ================= go ================= */
boot();
