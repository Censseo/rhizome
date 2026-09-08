#!/usr/bin/env python3
"""Balaye la chaîne d'un nœud et rend un verdict JSON sur la comptabilité des en-têtes.

Ce que le plan affirmait sans jamais le mesurer (campagne 6, section « Couverture non
atteinte » B) : que la répartition régulière des mineurs produit des ONCLES, et que les
récompenses oncle/neveu sont correctement comptées. Ici on lit les blocs un par un et on
vérifie l'identité exacte :

    supply(h) - supply(h-1) == subsidy + n_oncles * (subsidy*uncleNum/uncleDen + subsidy/nephewDiv)

La subvention, le ratio d'oncle (1/2) et le diviseur de neveu (32) sont lus sur /stats et
/emission du nœud, jamais codés en dur ici — un changement de profil réseau doit faire échouer
le scénario, pas le rendre faux silencieusement.

    chainscan.py <url du nœud> [premier bloc] [dernier bloc]
"""
import json, sys, urllib.request

BASE = sys.argv[1].rstrip("/")


def get(path):
    with urllib.request.urlopen(BASE + path, timeout=10) as r:
        return json.load(r)


def main():
    stats = get("/stats")
    tip = int(stats["height"])
    lo = int(sys.argv[2]) if len(sys.argv) > 2 else max(2, tip - 300)
    hi = int(sys.argv[3]) if len(sys.argv) > 3 else tip - 2  # marge : le tip peut encore bouger
    subsidy = int(stats["miningReward"])
    uncle_reward = subsidy // 2          # uncleRewardNum/uncleRewardDen = 1/2 (NetworkParameters)
    nephew_reward = subsidy // 32        # nephewRewardDivisor = 32
    per_uncle = uncle_reward + nephew_reward

    out = {
        "from": lo, "to": hi, "tip": tip, "subsidy": subsidy, "perUncle": per_uncle,
        "scanned": 0, "linkBreaks": [], "blocksWithUncles": 0, "uncleCount": 0,
        "supplyMismatches": [], "difficulties": {}, "uncleMiners": [],
    }
    prev = None
    for h in range(lo, hi + 1):
        try:
            b = get(f"/block?blockId={h}")
        except Exception as e:
            out.setdefault("readErrors", []).append([h, str(e)[:60]])
            prev = None
            continue
        out["scanned"] += 1
        d = str(b.get("difficulty"))
        out["difficulties"][d] = out["difficulties"].get(d, 0) + 1
        uncles = b.get("uncles") or []
        if uncles:
            out["blocksWithUncles"] += 1
            out["uncleCount"] += len(uncles)
            for u in uncles:
                if u.get("miner") not in out["uncleMiners"]:
                    out["uncleMiners"].append(u.get("miner"))
        if prev is not None:
            if b.get("lastBlockHash") != prev.get("hash"):
                out["linkBreaks"].append(h)
            expected = subsidy + len(uncles) * per_uncle
            actual = int(b["supply"]) - int(prev["supply"])
            fees = sum(t.get("fee", 0) for t in b.get("transactions", []) if t.get("from"))
            # Les frais ne sont PAS émis : ils circulent. La supply ne bouge que de l'émission.
            if actual != expected:
                out["supplyMismatches"].append(
                    {"height": h, "uncles": len(uncles), "expected": expected,
                     "actual": actual, "declaredFees": fees})
        prev = b
    print(json.dumps(out))


if __name__ == "__main__":
    main()
