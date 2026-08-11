#!/usr/bin/env python3
"""Vérifie que l'état d'un contrat est IDENTIQUE sur tous les nœuds à un tip donné.

Usage : statecheck.py <base_port> <nodes> <counter_addr> <token_addr> <owner_addr>

Sortie : une ligne de verdict, code 0 si aucun désaccord, 1 sinon.

Pourquoi pas le wallet CLI : chaque `call-readonly` démarre une JVM (~0,5 s) et un balayage
séquentiel de 30 nœuds dure 15 s — soit ~6 blocs à la cadence du devnet. Les nœuds lus en fin
de balayage sont alors à un tip plus récent que ceux du début, et la comparaison ne porte plus
sur le même état : le premier essai n'a trouvé que 3 nœuds « au même tip » sur 30. Ici tout
est interrogé en parallèle par HTTP, et le tip est relu APRÈS les lectures d'état : un nœud
qui a avancé pendant sa propre lecture est écarté au lieu d'être comparé à tort.

Le verdict ne porte que sur les nœuds d'un MÊME groupe de tip : deux valeurs différentes à
deux tips différents sont un retard de gossip, alors que deux valeurs différentes au même tip
sont une divergence d'exécution — le défaut le plus grave que ce testnet puisse produire.
"""
import json
import sys
import urllib.error
import urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor


def get_json(url, payload=None, timeout=5):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data,
                                 headers={"Content-Type": "application/json"} if data else {})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def probe(base, i, counter, token, owner):
    url = f"http://127.0.0.1:{base + i}"
    try:
        tip0 = get_json(f"{url}/stats")["tipHash"]
        cnt = get_json(f"{url}/call_readonly", {"to": counter, "input": ""})
        bal = get_json(f"{url}/call_readonly", {"to": token, "input": "02" + owner})
        tip1 = get_json(f"{url}/stats")["tipHash"]
    except (urllib.error.URLError, OSError, KeyError, ValueError) as e:
        return i, None, f"injoignable: {e}"
    if tip0 != tip1:
        return i, None, "tip changé pendant la lecture"
    return i, tip0, (json.dumps(cnt, sort_keys=True), json.dumps(bal, sort_keys=True))


def main():
    base, nodes, counter, token, owner = (int(sys.argv[1]), int(sys.argv[2]),
                                          sys.argv[3], sys.argv[4], sys.argv[5])
    with ThreadPoolExecutor(nodes) as ex:
        results = list(ex.map(lambda i: probe(base, i, counter, token, owner), range(nodes)))

    groups = defaultdict(list)
    skipped = 0
    for i, tip, val in results:
        if tip is None:
            skipped += 1
            continue
        groups[tip].append((i, val))

    bad = False
    for tip, members in sorted(groups.items(), key=lambda kv: -len(kv[1])):
        distinct = {v for _, v in members}
        state = "OK" if len(distinct) == 1 else "DIVERGENT"
        print(f"tip {tip[:12]} : {len(members):2d} nœuds, {len(distinct)} état(s) — {state}")
        if len(distinct) > 1:
            bad = True
            for i, v in members:
                print(f"   node {i:<3d} {v}")
    print(f"groupes de tip: {len(groups)}, nœuds écartés (tip mouvant/injoignable): {skipped}")
    if bad:
        print("ALERTE: ÉTAT DE CONTRAT DIVERGENT À TIP IDENTIQUE — exécution non déterministe")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
