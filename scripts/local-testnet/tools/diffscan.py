#!/usr/bin/env python3
"""Rejoue le RETARGET de difficulté d'une chaîne vivante, à côté du nœud, et compare.

La campagne 7 n'a jamais fait bouger la difficulté (6 sur 728 blocs sur 728) : la boucle de
retarget, la défense timewarp et les bornes min/max n'avaient donc jamais été exercées en
conditions réelles. Cet outil est le juge de la campagne 8 — une RÉIMPLÉMENTATION indépendante
de `DifficultyAdjustment.nextDifficulty` + `Retarget.stepWindow`, en Python, confrontée bloc à
bloc à ce que le nœud a réellement accepté. Une divergence est un désaccord de consensus, pas
une mesure approximative.

Trois choses sont vérifiées de front :

  1. la difficulté déclarée par CHAQUE bloc est celle que le repli des fenêtres impose ;
  2. elle ne change qu'en frontière + 1 (`height % lookback == 0` ferme la fenêtre), d'au plus
     MAX_STEP_BITS = 4 bits, et reste dans [minDifficulty, maxDifficulty] ;
  3. la borne de fenêtre est bien la MÉDIANE DE 3 horodatages, pas l'horodatage brut — la
     défense timewarp. Le scan calcule les deux prédictions : quand elles diffèrent (c'est-à-dire
     quand un horodatage aberrant a été présenté), celle qui doit coller est la médiane.

Les constantes de profil ne sont pas devinées : `desiredBlockTimeSec` vient de /stats, le reste
est passé en argument et doit refléter NetworkParameters (devnet : 20 / 6 / 24 / 6).

    diffscan.py <url> [--lookback 20] [--min 6] [--max 24] [--genesis 6] [--from 1]
"""
import json, sys, urllib.request

GENESIS_ID = 1
MAX_STEP_BITS = 4


def get(base, path):
    with urllib.request.urlopen(base + path, timeout=15) as r:
        return json.load(r)


def next_difficulty(current, window_blocks, observed_seconds, desired_sec, dmin, dmax):
    """Transcription littérale de DifficultyAdjustment.nextDifficulty (entiers uniquement)."""
    desired = window_blocks * desired_sec
    observed = max(1, observed_seconds)
    nxt, step = current, 0
    while step < MAX_STEP_BITS and observed * 2 <= desired:
        nxt += 1; observed *= 2; step += 1
    while step < MAX_STEP_BITS and observed >= desired * 2:
        nxt -= 1; desired *= 2; step += 1
    return max(dmin, min(dmax, nxt))


def median3(ts, h):
    """Retarget.medianTimestamp : médiane haute des (au plus) 3 horodatages finissant en h."""
    lo = max(GENESIS_ID, h - 2)
    xs = sorted(ts[x] for x in range(lo, h + 1))
    return xs[len(xs) // 2]


def step_window(ts, difficulty, boundary, lookback, desired_sec, dmin, dmax, bound):
    window_start = boundary - lookback + 1
    measure_start = max(window_start, GENESIS_ID + 1)
    intervals = boundary - measure_start
    if intervals <= 0:
        return difficulty, None
    observed_ms = bound(ts, boundary) - bound(ts, measure_start)
    return (next_difficulty(difficulty, intervals, observed_ms // 1000, desired_sec, dmin, dmax),
            {"boundary": boundary, "intervals": intervals,
             "observedSec": observed_ms // 1000, "desiredSec": intervals * desired_sec})


def main():
    base = sys.argv[1].rstrip("/")
    opt = {"--lookback": 20, "--min": 6, "--max": 24, "--genesis": 6, "--from": 1}
    for i in range(2, len(sys.argv), 2):
        opt[sys.argv[i]] = int(sys.argv[i + 1])
    lookback, dmin, dmax = opt["--lookback"], opt["--min"], opt["--max"]

    stats = get(base, "/stats")
    desired_sec = int(stats["desiredBlockTimeSec"])
    tip = int(stats["height"])

    ts, declared, hashes, parents = {}, {}, {}, {}
    read_errors = []
    for h in range(1, tip + 1):
        try:
            b = get(base, f"/block?blockId={h}")
        except Exception as e:                      # bloc élagué ou nœud occupé : on le dit
            read_errors.append([h, str(e)[:60]])
            continue
        ts[h] = int(b["timestamp"]); declared[h] = int(b["difficulty"])
        hashes[h] = b["hash"]; parents[h] = b["lastBlockHash"]

    out = {"tip": tip, "desiredBlockTimeSec": desired_sec, "lookback": lookback,
           "min": dmin, "max": dmax, "scanned": len(ts), "readErrors": read_errors,
           "mismatches": [], "linkBreaks": [], "offBoundaryChanges": [], "oversizedSteps": [],
           "outOfBounds": [], "ladder": [], "windows": [],
           "medianVsRaw": {"divergentBoundaries": [], "rawWouldMismatch": []}}
    if not ts:
        print(json.dumps(out)); return

    # Repli des fenêtres, exactement comme ChainEngine.computeDifficultyFromChain : la difficulté
    # du bloc h est celle qu'impose la plus haute frontière <= h - 1.
    expected_med = opt["--genesis"]
    expected_raw = opt["--genesis"]
    raw_bound = lambda t, h: t[h]
    prev_declared = None
    for h in range(max(2, opt["--from"]), tip + 1):
        if h not in ts:
            continue
        boundary = h - 1
        if boundary > 0 and boundary % lookback == 0 and all(x in ts for x in
                range(max(GENESIS_ID, boundary - lookback + 1), boundary + 1)):
            expected_med, win = step_window(ts, expected_med, boundary, lookback,
                                            desired_sec, dmin, dmax, median3)
            raw_next, _ = step_window(ts, expected_raw, boundary, lookback,
                                      desired_sec, dmin, dmax, raw_bound)
            if win:
                win["difficulty"] = expected_med
                out["windows"].append(win)
            if raw_next != expected_med:
                out["medianVsRaw"]["divergentBoundaries"].append(
                    {"boundary": boundary, "median": expected_med, "raw": raw_next})
            expected_raw = raw_next
        if declared[h] != expected_med:
            out["mismatches"].append({"height": h, "declared": declared[h], "expected": expected_med})
        if declared[h] != expected_raw and declared[h] == expected_med:
            out["medianVsRaw"]["rawWouldMismatch"].append(h)
        if not (dmin <= declared[h] <= dmax):
            out["outOfBounds"].append(h)
        if prev_declared is not None and declared[h] != prev_declared:
            step = declared[h] - prev_declared
            out["ladder"].append({"height": h, "from": prev_declared, "to": declared[h]})
            if abs(step) > MAX_STEP_BITS:
                out["oversizedSteps"].append({"height": h, "step": step})
            if (h - 1) % lookback != 0:
                out["offBoundaryChanges"].append(h)
        if h - 1 in hashes and parents[h] != hashes[h - 1]:
            out["linkBreaks"].append(h)
        prev_declared = declared[h]

    out["finalDifficulty"] = declared[max(ts)]
    out["distinctDifficulties"] = sorted(set(declared.values()))
    print(json.dumps(out))


if __name__ == "__main__":
    main()
