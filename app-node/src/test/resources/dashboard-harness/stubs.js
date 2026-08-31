/*
 * Stub responses for the four dashboard renders of quickstart §"Verifying the dashboard
 * without a browser" (spec SC-009, FR-019/FR-020). Values are illustrative mainnet-shaped
 * figures; what matters is the SHAPE the handlers see, per contracts/emission-observability.md.
 *
 * Built with post-literal assembly because each variant derives from the base: a
 * self-referencing `const` literal would be a TDZ error under indirect eval.
 */
'use strict';

const FEATURES = { dashboard: true, logStream: false, boxes: false, tokens: false, contracts: false };
const MANIFEST = { templates: [] };
const BLOCKS = { blocks: [], height: 42 };

const EMISSION = {
  network: 'rhizome-mainnet',
  rule: 'curve',
  activationHeight: 1,
  supplyTarget: '2997924580000',
  coefficient: '23750',
  steps: 256,
  floor: '800',
  genesisSupply: '1000000000000',
  decimalScaleFactor: 10000,
  // A reduced but ascending, floored sample set — the shape, not the calibration.
  samples: [
    { supply: '58553214453', subsidy: '93472' },
    { supply: '117106428906', subsidy: '77010' },
    { supply: '292766072265', subsidy: '49088' },
    { supply: '585532144531', subsidy: '26072' },
    { supply: '1171064289062', subsidy: '9479' },
    { supply: '2342128578125', subsidy: '800' },
    { supply: '3747405724992', subsidy: '800' },
  ],
};

/** Curve-governing chain mid-flight — the base every other /stats variant derives from. */
const STATS_CURVE = {
  network: 'rhizome-mainnet', chainId: 1, height: 42, difficulty: 22, mempool: 3,
  prunedBelow: 0, snapshotPivot: 0, storageFeeFactor: 1, minValuePerByte: 1,
  tipHash: 'aa'.repeat(32), totalWork: '123456789', peers: 4, desiredBlockTimeSec: 5,
  decimalScaleFactor: 10000, miningReward: 26072, maxReorgDepth: 500,
  lastBlockTimestamp: 1756550000000, avgBlockIntervalMs: 5000, windowBlocks: 32,
  windowTxCount: 41, degraded: null, reorgInProgress: false,
  syncRoundsWithoutProgress: 0, syncPeersBanned: 0, syncEclipsed: false,
  emission: {
    rule: 'curve', activationHeight: 1, supply: '450000000000', subsidy: '26072',
    target: '2997924580000', distanceToTarget: '2547924580000', progressBps: 1501,
    floor: '800', burned: '0', decimalScaleFactor: 10000,
  },
};

const STATS_GEOMETRIC = Object.assign({}, STATS_CURVE, {
  emission: {
    rule: 'geometric', activationHeight: 0, supply: '450000000000', subsidy: '26072',
    target: '2997924580000', distanceToTarget: null, progressBps: null,
    floor: '800', burned: '0', decimalScaleFactor: 10000,
  },
});

/** Supply unavailable: the chain commits no supply — null, never zero. */
const STATS_SUPPLY_ABSENT = Object.assign({}, STATS_CURVE, {
  emission: {
    rule: 'curve', activationHeight: 1, supply: null, subsidy: '26072',
    target: '2997924580000', distanceToTarget: null, progressBps: null,
    floor: '800', burned: '0', decimalScaleFactor: 10000,
  },
});

/** Older node: no emission key at all. */
const STATS_LEGACY = (function () {
  const s = Object.assign({}, STATS_CURVE);
  delete s.emission;
  return s;
})();

globalThis.RZ_STUBS = {
  features: FEATURES,
  manifest: MANIFEST,
  blocks: BLOCKS,
  emission: EMISSION,
  scheduleEmpty: Object.assign({}, EMISSION, { samples: [], rule: 'geometric' }),
  statsCurve: STATS_CURVE,
  statsGeometric: STATS_GEOMETRIC,
  statsSupplyAbsent: STATS_SUPPLY_ABSENT,
  statsLegacy: STATS_LEGACY,
};
