/*
 * The render driver: executes the REAL dashboard sources (app.js, unmodified) under this
 * repo's DOM shim, across the four renders of quickstart §"Verifying the dashboard without
 * a browser", and asserts what is displayed (spec SC-009, FR-019/FR-020, T033 accessibility).
 *
 * Engine-agnostic by construction — QuickJS:
 *
 *     qjs run.js
 *     # or, from this directory with an explicit app path:
 *     qjs run.js ../../main/resources/dashboard/app.js
 *
 * and Node (any LTS):
 *
 *     node run.js
 *
 * Exit code 0 = all four renders pass. Each failure names the render and the assertion.
 */
'use strict';

/** Path of the real dashboard sources, resolvable from any working directory:
 *  `qjs run.js` / `node run.js`, or pass the path explicitly as the first argument. */
const APP_PATH = (function () {
  if (typeof scriptArgs !== 'undefined' && scriptArgs[2]) return scriptArgs[2];
  if (typeof process !== 'undefined' && process.argv && process.argv[2]) return process.argv[2];
  if (typeof __dirname !== 'undefined') {
    return __dirname + '/../../../main/resources/dashboard/app.js';
  }
  return '../../../main/resources/dashboard/app.js';
})();

function readHarnessFile(name) {
  if (typeof std !== 'undefined' && std.loadFile) return std.loadFile(name);
  return require('fs').readFileSync(require('path').join(__dirname, name), 'utf8');
}

(async function main() {
  const SHIM = readHarnessFile('dom-shim.js');
  const STUBS_SRC = readHarnessFile('stubs.js');
  const APP = (function () {
    if (typeof std !== 'undefined' && std.loadFile) return std.loadFile(APP_PATH);
    return require('fs').readFileSync(APP_PATH, 'utf8');
  })();
  // Prime the stub table so the route literals below can reference RZ_STUBS; each render
  // re-evaluates shim + stubs fresh, republishing the same globalThis.RZ_STUBS.
  (0, eval)(SHIM);
  (0, eval)(STUBS_SRC);

  let failures = 0;
  let checks = 0;

  function ok(cond, render, what) {
    checks++;
    if (!cond) {
      failures++;
      console.log('FAIL [' + render + '] ' + what);
    }
  }

  /** Spaces (incl. NBSP/NB-hyphen variants) don't survive engines' Intl differences. */
  const flat = s => String(s).replace(/[\s\u00a0\u202f\u2009]/g, '');

  function tilesText(render) {
    return __harness.allElements()
      .filter(e => e.className === 'tile')
      .map(t => t.textContent);
  }

  function find(cond) {
    return __harness.allElements().find(e => e.nodeType === 1 && cond(e)) || null;
  }

  const byTag = (tag, cls) => e =>
    e.tagName === tag.toUpperCase() && (!cls || (e.attrs.class || '').includes(cls));

  /**
   * One render: fresh shim + fresh app.js evaluation (so the session /emission cache starts
   * empty), scripted fetch routes, boot drained, one 5-second poll fired manually, assertions.
   */
  async function render(name, routes, assert) {
    (0, eval)(SHIM);
    (0, eval)(STUBS_SRC);
    __harness.respondWith(routes);
    try {
      (0, eval)(APP); // app.js boots itself at the end of its source
    } catch (e) {
      ok(false, name, 'app.js threw during evaluation: ' + e.message);
      return;
    }
    await __harness.drain();
    // Fire the dashboard's 5-second poll once (shim intervals never self-fire) and settle.
    for (const fn of __harness.intervals.splice(0)) {
      try {
        await fn();
      } catch (e) {
        ok(false, name, 'poll tick threw: ' + (e && e.message));
      }
    }
    await __harness.drain();
    try {
      assert(name);
    } catch (e) {
      ok(false, name, 'assertions threw: ' + (e && (e.stack || e.message)));
    }
  }

  const GET = prefix => path => path.indexOf(prefix) === 0;
  const base = [
    [GET('/features'), 200, RZ_STUBS.features],
    [GET('/dashboard/templates/manifest.json'), 200, RZ_STUBS.manifest],
    [GET('/blocks?'), 200, RZ_STUBS.blocks],
  ];

  /* ---- Render 1: curve governing — figures populated, chart drawn, marker at the supply ---- */

  await render('curve-governing', base.concat([
    [GET('/stats'), 200, RZ_STUBS.statsCurve],
    [GET('/emission'), 200, RZ_STUBS.emission],
  ]), r => {
    const tiles = tilesText(r).join('\n');
    ok(flat(tiles).indexOf(flat('45 000')) >= 0, r,
      'supply tile shows the formatted circulating supply');
    ok(tiles.indexOf('0,00') < 0 || tiles.indexOf('indisponible') >= 0, r, 'sanity');
    const svg = find(byTag('svg'));
    ok(svg, r, 'the curve card draws an inline SVG');
    if (svg) {
      ok(svg.attrs.role === 'img', r, 'the SVG carries role="img"');
      const title = svg.children.find(byTag('title'));
      const desc = svg.children.find(byTag('desc'));
      ok(title && title.textContent.length > 0, r, 'the SVG has an accessible name');
      ok(desc && desc.textContent.indexOf('45') >= 0, r,
        'the SVG description conveys supply/target/subsidy/position');
      ok(svg.children.some(byTag('polyline', 'curve-line')), r, 'the curve polyline is drawn');
      ok(svg.children.some(byTag('line', 'curve-target')), r, 'the target line is drawn');
      const marker = svg.children.find(byTag('g', 'curve-marker'));
      ok(marker, r, 'the position marker is drawn');
      ok(marker && /translate\(\s*-?[\d.]+[a-z]*\s*,\s*-?[\d.]+[a-z]*\s*\)/.test(marker.attrs.style || ''),
        r, 'the marker is positioned at the reported supply');
    }
    ok(find(byTag('p', 'curve-summary')) !== null, r,
      'an equivalent text summary sits beside the chart');
    ok(find(e => e.nodeType === 1 && e.textContent.indexOf('cible') >= 0
      && (e.attrs.class || '').indexOf('curve-summary') >= 0) !== null, r,
      'the summary conveys the target textually');
  });

  /* ---- Render 2: geometric governing — figures populated, rule labelled, NO chart ---- */

  await render('geometric-governing', base.concat([
    [GET('/stats'), 200, RZ_STUBS.statsGeometric],
    [GET('/emission'), 200, RZ_STUBS.scheduleEmpty],
  ]), r => {
    const tiles = tilesText(r).join('\n');
    ok(flat(tiles).indexOf(flat('45 000')) >= 0, r, 'the supply figure is still populated');
    ok(tiles.indexOf('géométrique') >= 0, r, 'the geometric rule is labelled textually');
    ok(tiles.indexOf('indisponible') >= 0, r,
      'the unavailable distance figure reads "indisponible", never 0');
    ok(!tiles.match(/Distance à la cible\n0\b/), r, 'the distance is not rendered as 0');
    ok(find(byTag('svg')) === null, r, 'no chart for a chain the curve does not govern');
    ok(find(e => (e.attrs.class || '') === 'curve-body'
      && e.textContent.indexOf('géométrique') >= 0) !== null, r,
      'the curve card states the geometric rule in text');
  });

  /* ---- Render 3: supply unavailable — figures "indisponible", no chart, page functional ---- */

  await render('supply-unavailable', base.concat([
    [GET('/stats'), 200, RZ_STUBS.statsSupplyAbsent],
    [GET('/emission'), 200, RZ_STUBS.emission],
  ]), r => {
    const tiles = tilesText(r).join('\n');
    ok(tiles.indexOf('indisponible') >= 0, r, 'the supply figure reads "indisponible"');
    ok(!/Offre en circulation\n0/.test(tiles), r, 'the absent supply is never rendered as 0');
    ok(find(byTag('svg')) === null, r, 'no chart without a supply position');
    ok(find(e => (e.attrs.class || '') === 'curve-body'
      && e.textContent.indexOf('ne commet pas d’offre') >= 0) !== null, r,
      'the supply-unavailable state is stated textually');
    ok(find(byTag('h1')) !== null && tilesText(r).length >= 8, r, 'the page stays functional');
  });

  /* ---- Render 4a: older node — no emission key at all ---- */

  await render('legacy-node-no-emission-key', base.concat([
    [GET('/stats'), 200, RZ_STUBS.statsLegacy],
    [GET('/emission'), 200, RZ_STUBS.emission],
  ]), r => {
    const tiles = tilesText(r).join('\n');
    ok(tiles.indexOf('indisponible') >= 0, r, 'the emission tiles degrade to "indisponible"');
    ok(find(e => (e.attrs.class || '') === 'curve-body'
      && e.textContent.indexOf('n’expose pas la surface émission') >= 0) !== null, r,
      'the curve card explains the degradation in text');
    ok(find(byTag('h1')) !== null, r, 'the page renders, nothing throws');
  });

  /* ---- Render 4b: failed /emission fetch — figures fine, chart marked unavailable ---- */

  await render('failed-schedule-fetch', base.concat([
    [GET('/stats'), 200, RZ_STUBS.statsCurve],
    [GET('/emission'), 500, { error: 'boom' }],
  ]), r => {
    const tiles = tilesText(r).join('\n');
    ok(flat(tiles).indexOf(flat('45 000')) >= 0, r,
      'the figures still render from the /stats fragment');
    ok(find(e => (e.attrs.class || '') === 'curve-body'
      && e.textContent.indexOf('n’a pas pu servir son tracé') >= 0) !== null, r,
      'the chart is marked unavailable without throwing');
    ok(find(byTag('svg')) === null, r, 'no half-drawn chart');
  });

  console.log((failures === 0 ? 'PASS' : 'FAIL') + ': ' + (checks - failures) + '/' + checks
    + ' checks across 5 renders (4 quickstart states, render 4 in both its shapes)');
  if (typeof std !== 'undefined' && std.exit) std.exit(failures === 0 ? 0 : 1);
  if (typeof process !== 'undefined') process.exitCode = failures === 0 ? 0 : 1;
})();
