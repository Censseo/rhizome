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

// el() uses createElement, which cannot produce SVG: SVG nodes live in their own namespace and
// an HTML-namespace <svg> subtree renders nothing. Every chart node goes through this helper.
const SVG_NS = 'http://www.w3.org/2000/svg';

function svgEl(tag, attrs, ...children) {
  const node = document.createElementNS(SVG_NS, tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      node.setAttribute(k, v);
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

// The chain routes answer 503 + Retry-After while a reorg window is open: the node's canonical
// chain sits truncated at the fork height, so it has nothing coherent to serve. That is a normal
// pause, not a failure — and no longer a rare one, since an equal-work tip race is now settled by
// a deterministic reorg rather than left to the next block. Ride out one window before surfacing
// it as an error. The wait is capped well under the header's value (meant for peers, which can
// afford to wait a full round): a page that hangs for seconds is worse than one that says why.
const REORG_RETRY_CAP_MS = 1500;

async function api(path) {
  let res = await fetch(path);
  if (res.status === 503) {
    const after = parseInt(res.headers.get('Retry-After'), 10);
    const waitMs = Math.min(Number.isFinite(after) ? after * 1000 : 500, REORG_RETRY_CAP_MS);
    await new Promise(resolve => setTimeout(resolve, waitMs));
    res = await fetch(path);
  }
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.error || ('HTTP ' + res.status));
  return body;
}

// A non-simple custom header on every state-changing POST. A cross-site or DNS-rebinding page
// cannot set it without a CORS preflight the node never grants, so the browser blocks the request;
// the node refuses any browser POST that lacks it (audit net F2).
const RZ_CSRF = { 'X-Rhizome-Request': '1' };

async function apiPost(path, json) {
  const res = await fetch(path, { method: 'POST', headers: RZ_CSRF, body: JSON.stringify(json) });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.error || body.status || ('HTTP ' + res.status));
  return body;
}

async function submitWire(wire) {
  const res = await fetch('/add_transaction', { method: 'POST', headers: RZ_CSRF, body: wire });
  const body = await res.json().catch(() => ({}));
  if (!res.ok || body.status !== 'SUCCESS') {
    throw new Error(body.status || body.error || ('HTTP ' + res.status));
  }
  return body;
}

const App = { stats: null, features: null, manifest: null };

// /emission was immutable for the life of the node; since the decaying supply target (008) its
// samples are drawn at the live target, so the payload changes once per DECAY EPOCH. The cache
// is keyed by that epoch index — computed from the payload's own decay constants and
// sampleHeight (absent on a node predating 008: then the payload really is immutable and the
// behaviour is exactly the old once-per-session fetch) — and still deliberately NOT fetched on
// every 5-second poll. A failed fetch is not cached: this page renders the chart as
// unavailable, and the next poll retries (FR-020 — degrade, never throw).
let emissionScheduleCache = null;

function decayEpochOf(schedule, height) {
  if (!schedule || !schedule.decay || !(schedule.decay.startHeight > 0) || !height) return 0;
  const d = schedule.decay;
  // STRICTLY less-than, mirroring SupplyTargetSchedule.epochIndexAt: the node rebuilds its
  // memo the moment height reaches startHeight and stamps that height into sampleHeight, so a
  // `<=` here would read the cached payload as epoch 0 forever while the live height already
  // reads 1 — the comparison below would never match and the page would re-fetch /emission on
  // every 5-second poll for a whole epoch.
  return height < d.startHeight ? 0
    : Math.floor((height - d.startHeight) / d.epochBlocks) + 1;
}

async function emissionSchedule(currentHeight) {
  if (emissionScheduleCache
      && decayEpochOf(emissionScheduleCache, currentHeight)
          === decayEpochOf(emissionScheduleCache, emissionScheduleCache.sampleHeight)) {
    return emissionScheduleCache;
  }
  const schedule = await api('/emission');
  emissionScheduleCache = schedule;
  return schedule;
}

function scale() { return BigInt(App.stats ? App.stats.decimalScaleFactor : 1); }

/** base units -> display string. */
function fmtCoins(baseUnits) {
  const s = scale();
  const v = BigInt(baseUnits);
  // 008: a negative distance is a real, expected figure (the target can sit below supply once
  // the decay is engaged), so the formatting must survive the sign instead of emitting a
  // mangled "-1,-2345"-style string from a negative remainder.
  const neg = v < 0n;
  const a = neg ? -v : v;
  const whole = a / s;
  const frac = (a % s).toString().padStart(s.toString().length - 1, '0').replace(/0+$/, '');
  return (neg ? '-' : '') + whole.toLocaleString('fr-FR') + (frac ? ',' + frac : '');
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
 * subtle API is unavailable; there the wallet refuses create/import/unlock entirely and
 * the dashboard runs read-only — warn-and-continue would store the seed unencrypted on a
 * page a MITM could have substituted (audit F8). The decrypted seed is held only in
 * memory after unlock and never rendered unless the user explicitly reveals it.
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
  /** Stores a seed, encrypting it under {@code passphrase}. */
  async store(seed, passphrase) {
    // Never persist a key from a non-secure context (plain-http remote): the SPA itself may
    // have been substituted by a MITM, and there is no WebCrypto to encrypt at rest (audit F8).
    if (!CRYPTO_OK) throw new Error('contexte non sécurisé — stockage de la clé refusé');
    if (passphrase) {
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
  // Migrate any legacy plaintext localStorage key into the vault. An UNENCRYPTED vault is
  // deliberately NOT auto-unlocked here: a seed sitting in cleartext in IndexedDB must require
  // an explicit user gesture (the wallet page offers unlock + encryption migration) rather than
  // being loaded into memory on every dashboard visit (audit: cleartext vault auto-unlock).
  // Skipped entirely on a non-secure context: the wallet refuses to touch keys there (audit F8).
  try {
    if (CRYPTO_OK) {
      await migrateLegacyWallet();
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
    docs: renderDocs,
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
  const curveBody = el('div', { class: 'curve-body' },
    el('p', { class: 'muted' }, 'Chargement de la courbe…'));
  const curveCard = el('div', { class: 'card curve-card' },
    el('h3', null, 'Courbe d’émission'), curveBody);

  $view.append(
    el('h1', null, 'Dashboard'),
    el('p', { class: 'sub' }, 'Vue d’ensemble du réseau vue par ce node.'),
    tiles,
    el('div', { class: 'grid2' }, blocksCard, feedCard, curveCard),
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
      tileData.push(...emissionTiles(s.emission));
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

      // The figures come from /stats's emission fragment; the schedule (chart shape) arrives
      // separately, re-fetched only when the decay epoch turns over. Both degradation paths end
      // in text, never in a throw.
      emissionSchedule(s.height).then(schedule => {
        curveBody.replaceChildren(...curveContents(s.emission, schedule));
      }).catch(() => {
        curveBody.replaceChildren(...curveContents(s.emission, null));
      });
    } catch (e) { /* transient */ }
  });

  if (App.features.logStream) {
    startEventFeed(feed, null);
  } else {
    feed.append(el('div', { class: 'muted' }, 'Flux SSE indisponible sur ce node.'));
  }
}

/* ---- emission readout (007-emission-observability, US3) ---- */

// A figure this page cannot state is rendered as this word, NEVER as 0 — zero is a legal
// committed value and a rendered 0 would be a lie about the chain (FR-019).
const EMISSION_UNAVAILABLE = 'indisponible';

/**
 * The emission tiles: circulating supply, live target (with the peak as secondary text), the
 * distance to the live target, the burn obligation, the tip block's destroyed amount and the
 * carried burn debt, from /stats's emission fragment. Every state is conveyed textually in the
 * sub-label (never by colour alone), and a figure the chain cannot support shows
 * {@link EMISSION_UNAVAILABLE}. The obligation/peak fields may be absent on a node predating
 * 008, the burn/debt fields on one predating 009 — the page renders without them, exactly as it
 * already tolerates a missing emission key (contracts/emission-fragment.md §3).
 *
 * <p>009: a burn of 0 and an unavailable figure are visually AND textually distinct — 0
 * (rendered "0") states "nothing was destroyed / nothing is owed"; indisponible states "this
 * node cannot tell you". The two are never conflated (FR-028).
 */
function emissionTiles(em) {
  if (!em) {
    return [['Émission', EMISSION_UNAVAILABLE, 'ce node n’expose pas la surface émission']];
  }
  const ruleLabel = em.rule === 'curve'
    ? 'règle : courbe logarithmique'
    : 'règle : géométrique (hors courbe)';
  const supplyTile = ['Offre en circulation',
    em.supply !== null ? fmtCoins(em.supply) : EMISSION_UNAVAILABLE,
    em.supply !== null ? ruleLabel : ruleLabel + ' · offre non engagée'];
  // The LIVE target is the figure that governs the next block; the peak is secondary text.
  const peakShown = em.peakTarget !== null && em.peakTarget !== undefined
    ? fmtCoins(em.peakTarget) : null;
  const targetSub = peakShown !== null
    ? (em.decayStartHeight > 0
      ? 'cible vivante (décroissance programmée) · pic : ' + peakShown
      : 'cible vivante · pic : ' + peakShown)
    : ruleLabel;
  const targetTile = ['Cible d’émission',
    em.target !== null && em.target !== undefined ? fmtCoins(em.target) : EMISSION_UNAVAILABLE,
    targetSub];
  let distanceTile;
  if (em.distanceToTarget === null || em.distanceToTarget === undefined) {
    distanceTile = ['Distance à la cible', EMISSION_UNAVAILABLE, 'non convergente vers la cible'];
  } else if (BigInt(em.distanceToTarget) < 0n) {
    // Negative distance, conveyed as TEXT — never by colour alone (spec §Accessibility).
    distanceTile = ['Distance à la cible', fmtCoins(em.distanceToTarget),
      'l’offre dépasse la cible (décroissance engagée)'];
  } else {
    distanceTile = ['Distance à la cible', fmtCoins(em.distanceToTarget),
      'restante avant la cible'];
  }
  const obligationTile = ['Obligation de brûlage',
    em.obligation !== null && em.obligation !== undefined
      ? fmtCoins(em.obligation) : EMISSION_UNAVAILABLE,
    'plafond dérivé pour le prochain bloc'];
  // 009: the tip block's destroyed amount — a real figure now. "0" is a statement
  // ("nothing was destroyed"), indisponible is a different one ("cannot tell you").
  const burnedTile = ['Brûlé (dernier bloc)',
    em.burned !== null && em.burned !== undefined ? fmtCoins(em.burned) : EMISSION_UNAVAILABLE,
    BigInt(em.burned ?? '0') > 0n
      ? 'détruit par le dernier bloc'
      : 'rien n’a été détruit par le dernier bloc'];
  // 009: the carried debt — the stock standing between supply and the live target. null
  // (the curve does not govern the next block) is indisponible; "0" is "nothing is owed".
  let debtTile;
  if (em.burnDebt === null || em.burnDebt === undefined) {
    debtTile = ['Dette de brûlage', EMISSION_UNAVAILABLE,
      'la courbe ne gouverne pas le prochain bloc'];
  } else if (BigInt(em.burnDebt) > 0n) {
    debtTile = ['Dette de brûlage', fmtCoins(em.burnDebt),
      'brûlage restant pour rejoindre la cible'];
  } else {
    debtTile = ['Dette de brûlage', fmtCoins(em.burnDebt),
      'aucun brûlage dû : l’offre est à ou sous la cible'];
  }
  return [supplyTile, targetTile, distanceTile, obligationTile, burnedTile, debtTile];
}

/**
 * The curve card's contents, in the three display states plus the degraded one:
 * - curve governing + schedule served: the inline-SVG plot with the position marker;
 * - geometric governing: a textual statement, no chart (an empty sample set is a statement);
 * - supply unavailable: a textual statement, no chart, no zero;
 * - no emission fragment or no schedule: figures live in the tiles, the card says why it is
 *   empty — the page stays functional (FR-020).
 */
function curveContents(em, schedule) {
  if (!em) {
    return [el('p', { class: 'muted' },
      'Émission indisponible : ce node n’expose pas la surface émission (node plus ancien ?)')];
  }
  if (em.supply === null) {
    return [el('p', { class: 'muted' },
      'Cette chaîne ne commet pas d’offre dans ses en-têtes : pas de position sur la courbe.')];
  }
  if (em.rule !== 'curve') {
    return [el('p', { class: 'muted' },
      'Cette chaîne est régie par la règle géométrique : la courbe logarithmique ne la '
      + 'gouverne pas, il n’y a rien à tracer.')];
  }
  if (!schedule || !schedule.samples || schedule.samples.length === 0) {
    return [el('p', { class: 'muted' },
      'Courbe indisponible : la courbe gouverne cette chaîne mais le noeud n’a pas pu servir '
      + 'son tracé (/emission).')];
  }
  return emissionChart(em, schedule);
}

/** Unique ids for the chart's accessible name/description wiring. */
let emissionChartSeq = 0;

/**
 * The inline-SVG plot of the served samples: curve stroke, target line at S* and a position
 * marker at the reported (supply, subsidy) — the marker is a large diamond, distinguishable
 * by shape and size rather than hue alone. The SVG carries role="img" with an accessible name
 * and description, and the same figures sit in a text summary beside it (SC-006).
 */
function emissionChart(em, schedule) {
  const W = 560, H = 230, L = 52, R = 14, T = 14, B = 30;
  const samples = schedule.samples.map(p => [BigInt(p.supply), BigInt(p.subsidy)]);
  const maxX = samples[samples.length - 1][0];
  const maxY = samples.reduce((m, p) => p[1] > m ? p[1] : m, samples[0][1]);
  const supply = BigInt(em.supply);
  const subsidy = BigInt(em.subsidy);
  const target = BigInt(em.target);
  const x = v => L + Number((v * BigInt(W - L - R)) / maxX);
  const y = v => H - B - Number((v * BigInt(H - T - B)) / maxY);
  const points = samples.map(p => x(p[0]) + ',' + y(p[1])).join(' ');

  const id = 'emission-chart-' + (++emissionChartSeq);
  const pct = Number((supply * 10000n) / target) / 100;
  // The dashed line tracks the LIVE target (S*(h)) — the same figure the tiles and the subsidy
  // are measured against — so the plot and the reported marker cannot disagree. The accessible
  // name/description states the position relative to that live target (spec §Accessibility),
  // and progress past 100 % is a real state once the target decays below supply.
  const name = 'Courbe d’émission : offre, cible vivante et position actuelle';
  const desc = 'Offre en circulation ' + fmtCoins(supply) + ' unités de base ; cible vivante '
    + fmtCoins(target) + ' ; subvention du prochain bloc ' + fmtCoins(subsidy)
    + ' ; position à ' + pct.toLocaleString('fr-FR') + ' % de la cible vivante.';
  const summary = el('p', { class: 'muted curve-summary', id: id + '-summary' },
    'Offre ' + fmtCoins(supply) + ' · cible vivante ' + fmtCoins(target) + ' · subvention prochaine '
    + fmtCoins(subsidy) + ' · à ' + pct.toLocaleString('fr-FR') + ' % de la cible vivante');

  // Positioned statically: the whole chart is rebuilt every poll, so a CSS transition could
  // never animate the marker (a freshly inserted element renders at its final transform) —
  // the chart introduces no motion at all, which `prefers-reduced-motion` needs no handling
  // for (T037).
  const marker = svgEl('g', { class: 'curve-marker', style:
      'transform: translate(' + x(supply) + 'px, ' + y(subsidy) + 'px);' },
    svgEl('rect', { x: -5, y: -5, width: 10, height: 10, transform: 'rotate(45)' }));

  const svg = svgEl('svg', {
    viewBox: '0 0 ' + W + ' ' + H, role: 'img', 'aria-labelledby': id + '-t ' + id + '-d',
  },
    svgEl('title', { id: id + '-t' }, name),
    svgEl('desc', { id: id + '-d' }, desc),
    svgEl('line', { class: 'curve-axis', x1: L, y1: T, x2: L, y2: H - B }),
    svgEl('line', { class: 'curve-axis', x1: L, y1: H - B, x2: W - R, y2: H - B }),
    svgEl('line', { class: 'curve-target', x1: x(target), y1: T, x2: x(target), y2: H - B }),
    svgEl('polyline', { class: 'curve-line', points }));
  svg.append(marker);

  const caption = el('p', { class: 'muted curve-caption' },
    'trait plein : subvention selon l’offre · pointillé : cible vivante · losange : position actuelle');

  return [svg, summary, caption];
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
        depth < 1000 ? el('button', { class: 'secondary', onclick: () => load(1000) },
          'Chercher plus profond (1000 blocs)') : null));
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
    const hist = await api('/address_txs?address=' + address + '&depth=1000');
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

  // Non-secure context (plain-http remote): WebCrypto is unavailable and the served SPA may
  // have been substituted by a MITM — refuse wallet create/import/unlock entirely and leave the
  // dashboard read-only, rather than warn-and-continue with unencrypted key storage (audit F8).
  if (!CRYPTO_OK) {
    $view.append(el('div', { class: 'callout warn' },
      'Wallet désactivé : page servie en HTTP clair hors localhost (contexte non sécurisé). Le chiffrement du navigateur y est indisponible et un intercepteur pourrait avoir remplacé cette application — le dashboard fonctionne en lecture seule. Pour utiliser le wallet, ouvrez un tunnel SSH : ',
      el('span', { class: 'mono' }, 'ssh -L 3000:localhost:3000 <hôte>'),
      ', puis ', el('span', { class: 'mono' }, 'http://localhost:3000/'), '.'));
    return;
  }

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

  // Legacy UNENCRYPTED vault (created before passphrase encryption became mandatory, or
  // migrated from a plaintext localStorage key): never auto-unlocked — require an explicit
  // gesture, and push the user to encrypt it with a passphrase in the same step (audit:
  // cleartext vault auto-unlock). The seed stays on disk in cleartext until they choose.
  if (exists && !encrypted) {
    const pass = el('input', { type: 'password', placeholder: 'Nouvelle passphrase' });
    const out = el('div');
    const unlockWith = async (encrypt) => {
      try {
        const seed = await Vault.open(null);
        if (encrypt) {
          if (!pass.value) {
            out.replaceChildren(el('div', { class: 'result-box err' }, 'Choisissez une passphrase pour chiffrer la clé.'));
            return;
          }
          await Vault.store(seed, pass.value); // migration: cleartext → AES-256-GCM
        }
        WalletStore.setUnlocked(seed);
        route();
      } catch (e) {
        out.replaceChildren(el('div', { class: 'result-box err' }, 'Impossible d\'ouvrir le wallet : ' + e.message));
      }
    };
    pass.addEventListener('keydown', e => { if (e.key === 'Enter') unlockWith(true); });
    $view.append(el('div', { class: 'card' }, el('h3', null, 'Wallet non chiffré détecté'),
      el('p', { class: 'muted' },
        'Ce navigateur détient une clé enregistrée EN CLAIR (wallet ancien ou migré). Elle n\'est jamais chargée automatiquement : déverrouillez-la explicitement, et chiffrez-la avec une passphrase pour la protéger au repos.'),
      el('label', { class: 'f' }, 'Passphrase (chiffre la clé au repos)'), pass,
      el('button', { onclick: () => unlockWith(true) }, 'Déverrouiller et chiffrer'),
      el('button', { class: 'secondary', onclick: () => unlockWith(false) }, 'Déverrouiller sans chiffrer'),
      out,
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
    if (!passphrase) throw new Error('choisissez une passphrase');
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
    el('div', { class: 'callout warn' },
      'La clé est chiffrée au repos (AES-256-GCM, passphrase via PBKDF2). Pour un trésor, préférez le wallet CLI et un fichier de clé hors-ligne.'));
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

/* ================= page: docs =================
 * The node's own documentation. /docs/manifest.json lists the pages the build staged into the
 * jar, /docs/<slug>.md serves each one, md.js renders it. Pages are fetched once and kept: the
 * whole corpus is a few hundred kilobytes, which is what lets the search below scan every
 * document in the browser, with no server-side index and no extra endpoint.
 */

const Docs = { pages: null, bySource: null, text: new Map(), allLoaded: false, query: '' };

async function docsPages() {
  if (!Docs.pages) {
    const res = await fetch('/docs/manifest.json');
    if (!res.ok) throw new Error('manifest indisponible (HTTP ' + res.status + ')');
    Docs.pages = (await res.json()).pages;
    Docs.bySource = new Map(Docs.pages.map(p => [p.source, p]));
  }
  return Docs.pages;
}

async function docsText(page) {
  if (!Docs.text.has(page.slug)) {
    const res = await fetch('/docs/' + page.file);
    if (!res.ok) throw new Error('HTTP ' + res.status);
    Docs.text.set(page.slug, await res.text());
  }
  return Docs.text.get(page.slug);
}

/** Repo-relative path of `rel` resolved against the directory holding `from`. */
function docsResolvePath(from, rel) {
  const out = [];
  for (const part of from.split('/').slice(0, -1).concat(rel.split('/'))) {
    if (!part || part === '.') continue;
    if (part === '..') out.pop();
    else out.push(part);
  }
  return out.join('/');
}

function docsHref(slug, anchor) {
  return '#/docs/' + slug + (anchor ? '/' + encodeURIComponent(anchor) : '');
}

/**
 * Resolves a link found in the markdown. A sibling document becomes an SPA route and an http(s)
 * URL stays a link; everything else — the source files the docs cross-reference, which the jar
 * does not ship — returns null so md.js renders an inert path reference instead of a link that
 * would 404.
 */
function docsResolveLink(href, page) {
  if (/^[a-z][a-z0-9+.-]*:/i.test(href)) return /^https?:/i.test(href) ? href : null;
  const cut = href.indexOf('#');
  const path = cut < 0 ? href : href.slice(0, cut);
  const anchor = cut < 0 ? '' : href.slice(cut + 1);
  if (!path) return anchor ? docsHref(page.slug, anchor) : null;
  const target = Docs.bySource.get(docsResolvePath(page.source, path));
  return target ? docsHref(target.slug, anchor) : null;
}

/**
 * Heading ids exactly as md.js assigns them, so search hits and the TOC land on real anchors.
 * Fenced blocks are skipped for the same reason the renderer skips them: a shell comment inside
 * a ```bash block starts with '#' without being a heading, and counting it would shift every
 * later duplicate-suffixed id.
 */
function docsHeadings(markdown) {
  const seen = new Map();
  const out = [];
  let fenced = false;
  for (const line of markdown.split('\n')) {
    if (/^```/.test(line)) { fenced = !fenced; continue; }
    const h = !fenced && /^(#{1,6})\s+(.*?)\s*#*\s*$/.exec(line);
    if (h) out.push({ level: h[1].length, text: h[2], id: RzMd.slugify(h[2], seen) });
  }
  return out;
}

async function renderDocs(rest) {
  const route = location.hash;
  let pages;
  try {
    pages = await docsPages();
  } catch (e) {
    $view.append(el('div', { class: 'card' },
      el('h3', null, 'Documentation indisponible'), el('p', { class: 'muted' }, String(e.message))));
    return;
  }
  if (location.hash !== route) return;

  const slug = rest[0] || 'index';
  const anchor = rest[1] || '';
  const page = pages.find(p => p.slug === slug) || pages[0];

  const results = el('div', { class: 'doc-results' });
  const search = el('input', {
    type: 'search', value: Docs.query,
    placeholder: 'Rechercher dans toute la documentation…',
    oninput: ev => docsSearch(ev.target.value.trim(), results),
  });

  const nav = el('nav', { class: 'doc-nav' });
  let group = null;
  for (const p of pages) {
    if (p.group !== group) { group = p.group; nav.append(el('div', { class: 'doc-nav-group' }, group)); }
    nav.append(el('a', {
      href: docsHref(p.slug, ''),
      class: 'doc-nav-link' + (p.slug === page.slug ? ' active' : ''),
    }, p.title));
  }

  const body = el('article', { class: 'doc' }, el('p', { class: 'muted' }, 'Chargement…'));

  $view.append(
    el('h1', null, 'Documentation'),
    el('p', { class: 'sub' }, 'Les specs du protocole, servies par le nœud lui-même — '
      + pages.length + ' documents embarqués dans le binaire.'),
    el('div', { class: 'searchbar' }, search),
    results,
    el('div', { class: 'doc-shell' }, nav, el('div', { class: 'card doc-card' }, body)));

  if (Docs.query.length >= 3) docsSearch(Docs.query, results);

  let markdown;
  try {
    markdown = await docsText(page);
  } catch (e) {
    body.replaceChildren(el('p', { class: 'bad' }, 'Lecture impossible : ' + e.message));
    return;
  }
  if (location.hash !== route) return;

  const out = RzMd.render(markdown, {
    resolveLink: href => docsResolveLink(href, page),
    anchorHref: id => docsHref(page.slug, id),
  });
  const sections = out.headings.filter(h => h.level === 2 || h.level === 3);
  body.replaceChildren();
  if (sections.length > 3) {
    // Open by default, but a long spec's outline would push the document off-screen.
    const toc = el('details', sections.length <= 18 ? { class: 'doc-toc', open: 'open' } : { class: 'doc-toc' },
      el('summary', null, 'Sur cette page (' + sections.length + ')'));
    const list = el('div', { class: 'doc-toc-list' });
    for (const h of sections) {
      list.append(el('a', { href: docsHref(page.slug, h.id), class: 'lvl' + h.level }, h.text));
    }
    toc.append(list);
    body.append(toc);
  }
  body.append(out.node,
    el('p', { class: 'doc-source muted' }, 'Source : ', el('code', null, page.source)));

  const target = anchor && document.getElementById(anchor);
  if (target) target.scrollIntoView();
  else if (!anchor) window.scrollTo(0, 0);
}

/** Loads every document once, so search can scan the corpus in the browser. */
async function docsLoadAll() {
  if (Docs.allLoaded) return;
  await Promise.all((await docsPages()).map(docsText));
  Docs.allLoaded = true;
}

const DOCS_SEARCH_MAX = 40;
let docsSearchSeq = 0;

async function docsSearch(query, into) {
  const seq = ++docsSearchSeq;
  Docs.query = query;
  if (query.length < 3) { into.replaceChildren(); return; }
  if (!Docs.allLoaded) into.replaceChildren(el('div', { class: 'card muted' }, 'Indexation…'));
  await docsLoadAll();
  if (seq !== docsSearchSeq) return;   // a later keystroke owns the results now

  const needle = query.toLowerCase();
  const hits = [];
  outer:
  for (const page of Docs.pages) {
    let heading = null;
    let fenced = false;
    for (const line of Docs.text.get(page.slug).split('\n')) {
      if (/^```/.test(line)) { fenced = !fenced; continue; }
      const h = !fenced && /^(#{1,6})\s+(.*?)\s*#*\s*$/.exec(line);
      if (h) { heading = h[2]; continue; }
      const at = line.toLowerCase().indexOf(needle);
      if (at < 0) continue;
      hits.push({ page: page, heading: heading, line: line.trim(),
                  at: at - (line.length - line.trimStart().length) });
      if (hits.length >= DOCS_SEARCH_MAX) break outer;
    }
  }

  if (!hits.length) {
    into.replaceChildren(el('div', { class: 'card' },
      el('p', { class: 'muted' }, 'Aucun résultat pour « ' + query + ' ».')));
    return;
  }

  // Heading ids are computed per page, once, from the same rule the renderer uses.
  const ids = new Map();
  const idFor = (page, headingText) => {
    if (!headingText) return '';
    if (!ids.has(page.slug)) ids.set(page.slug, docsHeadings(Docs.text.get(page.slug)));
    const found = ids.get(page.slug).find(h => h.text === headingText);
    return found ? found.id : '';
  };

  const list = el('div', { class: 'card doc-hits' },
    el('h3', null, hits.length + (hits.length >= DOCS_SEARCH_MAX ? '+' : '') + ' résultat(s)'));
  for (const hit of hits) {
    const from = Math.max(0, hit.at - 60);
    const excerpt = el('div', { class: 'doc-hit-line mono' });
    if (from > 0) excerpt.append('…');
    excerpt.append(hit.line.slice(from, hit.at),
      el('mark', null, hit.line.slice(hit.at, hit.at + query.length)),
      hit.line.slice(hit.at + query.length, hit.at + query.length + 120));
    list.append(el('a', { class: 'doc-hit', href: docsHref(hit.page.slug, idFor(hit.page, hit.heading)) },
      el('div', { class: 'doc-hit-where' },
        hit.page.title, hit.heading ? ' › ' + hit.heading : ''),
      excerpt));
  }
  into.replaceChildren(list);
}

/* ================= go ================= */
boot();
