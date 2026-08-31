# Dashboard render harness (QuickJS + DOM shim)

Executes the **real** dashboard sources (`app-node/src/main/resources/dashboard/app.js`,
unmodified) outside a browser, across the four renders of
`specs/007-emission-observability/quickstart.md` §"Verifying the dashboard without a browser"
(spec SC-009, FR-019/FR-020). This is the constitution's "Local Verification Is the Gate"
applied to the one surface no Java test can reach.

There is **no** Gradle wiring and **no** dependency on purpose: the dashboard must stay a
dependency-free, no-build-step artifact (FR-021), and this harness must never become a build
step that a dashboard change has to satisfy in a browser-shaped way. Run it by hand when the
dashboard changes.

## Files

| File | Role |
|---|---|
| `dom-shim.js` | Minimal DOM + browser environment: elements, text nodes, `createElementNS` (so SVG can be exercised at all), scripted `fetch`, deterministic timer queues. Pure ECMAScript. |
| `stubs.js` | The stubbed `/stats`, `/emission`, `/features`… responses for the four states. |
| `run.js` | The render driver: fresh shim + fresh app.js per render, boot drained, one poll tick fired, assertions on what is displayed. Engine-agnostic. |

## Running

From this directory, with either engine:

```bash
qjs run.js                          # QuickJS
node run.js                         # Node (any LTS)
```

Optionally pass the app path explicitly (defaults to `../../main/resources/dashboard/app.js`
relative to this directory):

```bash
node run.js ../../main/resources/dashboard/app.js
```

Exit code 0 = every render passes. Failures print `FAIL [<render>] <assertion>`.

## The four renders (plus render 4's second shape)

1. **curve-governing** — figures populated, inline-SVG chart drawn, marker positioned at the
   reported supply, target line present, `role="img"` + name/description + text summary.
2. **geometric-governing** — figures populated, rule labelled textually, distance reads
   "indisponible" (never 0), **no chart** (an empty sample set is a statement).
3. **supply-unavailable** — supply reads "indisponible", never `0`; no chart; page functional.
4. **legacy-node-no-emission-key** and **failed-schedule-fetch** — the two shapes of FR-020:
   a `/stats` without the `emission` key, and a failing `/emission` fetch. Both render the
   page, mark the affected parts unavailable in text, and throw nothing.

Accessibility assertions run inside the same renders (T033/SC-006): the SVG carries
`role="img"` with an accessible name and description conveying supply, target, subsidy and
position; an equivalent text summary sits beside it; every figure is labelled; rule and
availability states are conveyed textually, never by colour.

## Notes

- The shim deliberately provides **no** `crypto.subtle`: the dashboard's `CRYPTO_OK` guard is
  false, so the wallet-vault paths the harness cannot honestly exercise are skipped by the
  production code itself — not stubbed out by the harness.
- The chart is motion-free: it is redrawn each poll with the marker at its final transform, so
  there is no transition for `prefers-reduced-motion` to gate and no media query in `app.css`
  (T037 — the requirement holds vacuously). It needs no runtime assertion here.
- The `/emission` fetch happens **once per page load** and is session-cached (research
  Decision 4); render 4b proves a failed fetch is *not* cached and degrades without throwing.
