#!/usr/bin/env python3
"""Balaye la chaîne d'un nœud et rend un verdict JSON sur la comptabilité des en-têtes.

Ce que le plan affirmait sans jamais le mesurer (campagne 6, section « Couverture non
atteinte » B) : que la répartition régulière des mineurs produit des ONCLES, et que les
récompenses oncle/neveu sont correctement comptées. Ici on lit les blocs un par un et on
vérifie l'identité exacte :

    supply(h) - supply(h-1) == subsidy(h) + Σ_oncles scaled(subsidy(h)/2) + scaled(subsidy(h)/32)

Deux règles que la première version ignorait (les deux ont mordé sur la chaîne réelle de
12k blocs) :

  * la subvention est PAR BLOC : sur un profil à courbe pilotée par l'offre (rule=curve),
    miningReward(h, parentSupply) bouge à mesure que l'offre croît (observé : 26 056 ->
    26 067 sur la fenêtre). On ne la calcule pas ici — on lit la transaction coinbase du
    bloc (sans `from`), déjà validée par consensus, donc l'identité testée est celle des
    oncles/neveux relativement à la subvention réellement versée. La dérive observée est
    publiée dans `subsidyRange` au lieu d'être assumée constante.
  * chaque récompense est pondérée par le travail PROUVÉ de l'oncle (Executor
    .scaleRewardToWork, audit C1) : deficit = difficulté(neveu) - difficulté(oncle),
    récompense >>= deficit (63 bits manquants -> 0). Une paie pleine exige deficit = 0.

Les ratios d'oncle (1/2) et de neveu (1/32) ne sont publiés par aucune route HTTP : ils
reflètent les constantes épinglées de NetworkParameters (uncleRewardNum/uncleRewardDen,
nephewRewardDivisor). Si un profil les change, ce script doit être corrigé en même temps —
un changement de profil doit faire échouer le scénario, pas le rendre faux silencieusement.

Les frais ne sont PAS émis (ils circulent) ; une transaction de burn (avec `from`, sans
`to`) détruit son montant : il est soustrait de l'attendu et journalisé dans `burnTxs`.

    chainscan.py <url du nœud> [premier bloc] [dernier bloc]
"""
import json, sys, urllib.request

BASE = sys.argv[1].rstrip("/")

UNCLE_NUM, UNCLE_DEN, NEPHEW_DIV = 1, 2, 32


def get(path):
    with urllib.request.urlopen(BASE + path, timeout=10) as r:
        return json.load(r)


def scaled(base, deficit):
    """Executor.scaleRewardToWork : base * 2^-deficit, entier, >= 64 bits -> 0."""
    if deficit <= 0:
        return base
    if deficit >= 64:
        return 0
    return base >> deficit


def main():
    stats = get("/stats")
    tip = int(stats["height"])
    lo = int(sys.argv[2]) if len(sys.argv) > 2 else max(2, tip - 300)
    hi = int(sys.argv[3]) if len(sys.argv) > 3 else tip - 2  # marge : le tip peut encore bouger

    out = {
        "from": lo, "to": hi, "tip": tip, "scanned": 0,
        "linkBreaks": [], "blocksWithUncles": 0, "uncleCount": 0,
        "supplyMismatches": [], "difficulties": {}, "uncleMiners": [],
        "subsidies": set(), "coinbaseAnomalies": [], "burnTxs": [],
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
        txs = b.get("transactions") or []
        coinbases = [t for t in txs if not t.get("from")]
        if len(coinbases) != 1:
            out["coinbaseAnomalies"].append({"height": h, "count": len(coinbases)})
            prev = b
            continue
        subsidy = int(coinbases[0].get("amount", 0))
        out["subsidies"].add(subsidy)
        burned = sum(int(t.get("amount", 0)) for t in txs if t.get("from") and not t.get("to"))
        if burned:
            out["burnTxs"].append({"height": h, "burned": burned})
        if prev is not None:
            if b.get("lastBlockHash") != prev.get("hash"):
                out["linkBreaks"].append(h)
            expected = subsidy - burned
            nephew_diff = b.get("difficulty")
            for u in uncles:
                deficit = nephew_diff - u.get("difficulty", nephew_diff)
                if deficit < 0:  # validateUncles garantit deficit >= 0 ; sinon c'est un défaut
                    out["coinbaseAnomalies"].append(
                        {"height": h, "negativeDeficit": deficit})
                    continue
                expected += scaled(subsidy * UNCLE_NUM // UNCLE_DEN, deficit)
                expected += scaled(subsidy // NEPHEW_DIV, deficit)
            actual = int(b["supply"]) - int(prev["supply"])
            fees = sum(t.get("fee", 0) for t in txs if t.get("from"))
            # Les frais ne sont PAS émis : ils circulent. La supply ne bouge que de
            # l'émission (coinbase + oncles/neveux) moins les burns.
            if actual != expected:
                out["supplyMismatches"].append(
                    {"height": h, "uncles": len(uncles), "subsidy": subsidy,
                     "burned": burned, "expected": expected, "actual": actual,
                     "declaredFees": fees})
        prev = b
    out["subsidies"] = sorted(out["subsidies"])
    out["subsidyRange"] = [out["subsidies"][0], out["subsidies"][-1]] if out["subsidies"] else []
    print(json.dumps(out))


if __name__ == "__main__":
    main()
