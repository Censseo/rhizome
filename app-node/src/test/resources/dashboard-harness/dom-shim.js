/*
 * Rhizome dashboard harness — DOM + browser-environment shim.
 *
 * Executes the REAL dashboard sources (app.js) outside a browser: a tiny DOM (elements,
 * text nodes, classList, replaceChildren…), createElementNS so SVG can be exercised at all,
 * a scripted fetch, and engine-agnostic timer queues. Pure ECMAScript: runs identically
 * under QuickJS (qjs) and Node. Nothing here is browser- or engine-specific, and nothing
 * here knows about the dashboard's own logic — it only hosts it.
 *
 * Loaded by run.js BEFORE app.js is evaluated. Exposes its driver-facing internals on
 * globalThis.__harness (drain, intervals, fetch routing).
 */
'use strict';

(function install() {
  const H = { timers: [], intervals: [], fetchRoutes: [], now: 0 };
  globalThis.__harness = H;

  /* ---- timers: never fire on their own; the driver drains them deterministically ---- */

  globalThis.setTimeout = (fn, ms) => { H.timers.push(fn); return H.timers.length; };
  globalThis.clearTimeout = () => {};
  globalThis.setInterval = (fn, ms) => { H.intervals.push(fn); return H.intervals.length; };
  globalThis.clearInterval = () => {};

  let microtaskSweep = 0;
  /** Runs every queued timer, interleaved with microtask drains, until quiescent. */
  H.drain = async function drain() {
    for (let round = 0; round < 200; round++) {
      while (H.timers.length) {
        H.timers.shift()();
      }
      // Let resolved-promise continuations (fetch stubs, async handlers) settle.
      for (let i = 0; i < 25; i++) {
        await Promise.resolve();
      }
      if (!H.timers.length) break;
    }
    await Promise.resolve();
  };

  /* ---- scripted fetch: routes are [matcher(path), status, body] ---- */

  H.respondWith = function respondWith(routes) { H.fetchRoutes = routes; };

  globalThis.fetch = function (path) {
    for (const [match, status, body] of H.fetchRoutes) {
      if (match(path)) {
        return Promise.resolve({
          status,
          ok: status >= 200 && status < 300,
          headers: { get: () => null },
          json: async () => body,
        });
      }
    }
    return Promise.resolve({
      status: 404, ok: false, headers: { get: () => null },
      json: async () => ({ error: 'no stub for ' + path }),
    });
  };

  globalThis.EventSource = function EventSource() {
    this.close = () => {};
    this.onmessage = null;
  };

  /* ---- minimal DOM ---- */

  const pendingBysId = new Map();

  function textNode(data) {
    return { nodeType: 3, textContent: String(data), parent: null };
  }

  function unwrap(node) {
    return node.nodeType ? node : textNode(node);
  }

  function element(tag, ns) {
    const e = {
      nodeType: 1,
      tagName: String(tag).toUpperCase(),
      namespaceURI: ns || 'http://www.w3.org/1999/xhtml',
      childNodes: [],
      attrs: {},
      dataset: {},
      parent: null,
      listeners: [],
      get className() {
        return e.attrs.class || '';
      },
      set className(v) {
        e.attrs.class = String(v);
      },
      get children() {
        return e.childNodes.filter(c => c.nodeType === 1);
      },
      get lastChild() {
        return e.childNodes[e.childNodes.length - 1] || null;
      },
      get textContent() {
        return e.childNodes.map(c => (c.nodeType === 3 ? c.textContent : c.textContent)).join('');
      },
      set textContent(v) {
        e.childNodes = [];
        if (v !== '') e.childNodes.push(textNode(v));
      },
      append(...nodes) {
        for (const n of nodes.map(unwrap)) {
          n.parent = e;
          e.childNodes.push(n);
        }
      },
      prepend(...nodes) {
        for (const n of nodes.map(unwrap).reverse()) {
          n.parent = e;
          e.childNodes.unshift(n);
        }
      },
      replaceChildren(...nodes) {
        e.childNodes = [];
        e.append(...nodes);
      },
      remove() {
        if (e.parent) {
          const i = e.parent.childNodes.indexOf(e);
          if (i >= 0) e.parent.childNodes.splice(i, 1);
        }
      },
      setAttribute(k, v) {
        e.attrs[k] = String(v);
        if (k === 'class') e.className = String(v);
      },
      getAttribute(k) {
        return k in e.attrs ? e.attrs[k] : null;
      },
      addEventListener(type, fn) {
        e.listeners.push([type, fn]);
      },
      classList: {
        _set: new Set(),
        add(c) { e.classList._set.add(c); },
        remove(c) { e.classList._set.delete(c); },
        toggle(c, force) {
          const on = force === undefined ? !e.classList._set.has(c) : !!force;
          if (on) e.classList._set.add(c); else e.classList._set.delete(c);
          return on;
        },
        contains(c) { return e.classList._set.has(c); },
      },
    };
    return e;
  }

  globalThis.document = {
    createElement: tag => element(tag),
    createElementNS: (ns, tag) => element(tag, ns),
    createTextNode: textNode,
    getElementById(id) {
      if (!pendingBysId.has(id)) {
        const e = element('div');
        e.attrs.id = id;
        pendingBysId.set(id, e);
      }
      return pendingBysId.get(id);
    },
    querySelectorAll() {
      return [];
    },
  };

  /** Every element ever created, in creation order — the driver's assertion surface. */
  H.allElements = () => {
    const seen = new Set();
    const out = [];
    for (const e of pendingBysId.values()) {
      (function walk(n) {
        if (seen.has(n)) return;
        seen.add(n);
        out.push(n);
        if (n.childNodes) n.childNodes.forEach(walk);
      })(e);
    }
    return out;
  };
  /** The element whose id attribute matches, searching all mounted trees. */
  H.getElementById = id => {
    for (const e of H.allElements()) {
      if (e.attrs.id === id) return e;
    }
    return pendingBysId.get(id) || null;
  };

  globalThis.window = globalThis;
  globalThis.self = globalThis;
  globalThis.addEventListener = () => {};
  globalThis.removeEventListener = () => {};
  globalThis.location = { hash: '#/dashboard', origin: 'http://harness' };
  // Node ≥21 exposes a getter-only global navigator; the shim's copy is best-effort.
  try {
    globalThis.navigator = { userAgent: 'rhizome-dashboard-harness' };
  } catch (e) { /* keep the host's navigator — nothing in app.js reads it on these paths */ }
  globalThis.localStorage = {
    _m: new Map(),
    getItem(k) { return this._m.has(k) ? this._m.get(k) : null; },
    setItem(k, v) { this._m.set(k, String(v)); },
    removeItem(k) { this._m.delete(k); },
  };
  // No crypto.subtle on purpose: CRYPTO_OK is false, so the dashboard skips the vault
  // migration path entirely — exactly the surface this harness does not exercise.
})();
