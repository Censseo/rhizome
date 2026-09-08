#!/usr/bin/env python3
"""Générateur de rafale pour les cas XFF-* de suite-tls.sh : mesure ce que change
RHIZOME_TRUST_XFF sur le limiteur PAR CLIENT (RateLimiter, fenêtre glissante 1000
requêtes/1000 ms) quand le trafic passe par un relais TLS et porte un en-tête
X-Forwarded-For usurpé.

Pourquoi pas simplement `curl` en boucle (comme le reste de la batterie) : une nouvelle
poignée de main TLS par requête coûte assez cher en CPU (RSA/ECDHE) pour que ce banc ne
dépasse jamais ~150-200 req/s sur cette machine, très en dessous du budget de 1000/s — la
rafale n'atteindrait jamais le seuil qu'elle est censée tester, quelle que soit la valeur de
RHIZOME_TRUST_XFF (constaté empiriquement en prototype avant d'écrire ce module). Le coût qui
domine est la poignée de main, pas la requête HTTP elle-même : ce script ouvre UNE connexion
TLS par fil d'exécution et y rejoue `count/workers` requêtes GET (réutilisant la session TLS,
comme le ferait un vrai relais gardant ses connexions amont vivantes), ce qui atteint plusieurs
milliers de req/s et rend le seuil du limiteur effectivement franchissable.

Sortie : un objet JSON sur une seule ligne, à plat (le format que `json_get` de common.sh sait
lire) :

    {"elapsedSec":0.52,"total":2000,"count200":980,"count429":1020,"countOther":0}

    xff_burst.py <hôte> <port> <chemin> <total> <fils> <AC_ou_none> <mode: fixed|rotate|none>

<mode> : "rotate" tire une adresse IPv4 factice différente à CHAQUE requête (l'attaque que
RHIZOME_TRUST_XFF=true rend efficace) ; "fixed" en fige une seule (contrôle : même sans usurpation
tournante, une seule fausse identité doit se faire limiter comme n'importe quel client réel) ;
"none" n'envoie aucun en-tête X-Forwarded-For.
"""
import collections
import http.client
import random
import ssl
import sys
import threading
import time
import json

HOST = sys.argv[1]
PORT = int(sys.argv[2])
PATH = sys.argv[3]
TOTAL = int(sys.argv[4])
WORKERS = int(sys.argv[5])
CACERT = sys.argv[6]
XFF_MODE = sys.argv[7]

if CACERT == "none":
    ctx = ssl._create_unverified_context()
else:
    ctx = ssl.create_default_context(cafile=CACERT)

counts = collections.Counter()
lock = threading.Lock()


def worker(n):
    # UNE connexion TLS par fil, réutilisée pour ses `n` requêtes : c'est ce qui amortit le
    # coût de la poignée de main (voir le docstring du module) et rapproche le débit atteignable
    # de ce qu'un relais à connexions persistantes produirait réellement.
    conn = None
    for _ in range(n):
        headers = {}
        if XFF_MODE == "fixed":
            headers["X-Forwarded-For"] = "203.0.113.7"
        elif XFF_MODE == "rotate":
            headers["X-Forwarded-For"] = f"203.0.113.{random.randint(1, 250)}"
        try:
            if conn is None:
                conn = http.client.HTTPSConnection(HOST, PORT, timeout=5, context=ctx)
            conn.request("GET", PATH, headers=headers)
            resp = conn.getresponse()
            resp.read()
            code = resp.status
        except Exception:
            code = "ERR"
            try:
                conn.close()
            except Exception:
                pass
            conn = None
        with lock:
            counts[code] += 1


per_worker = max(1, TOTAL // WORKERS)
threads = [threading.Thread(target=worker, args=(per_worker,)) for _ in range(WORKERS)]
t0 = time.time()
for t in threads:
    t.start()
for t in threads:
    t.join()
elapsed = time.time() - t0

total = sum(counts.values())
count200 = counts.get(200, 0)
count429 = counts.get(429, 0)
count_other = total - count200 - count429
print(json.dumps({
    "elapsedSec": round(elapsed, 3),
    "total": total,
    "count200": count200,
    "count429": count429,
    "countOther": count_other,
}))
