#!/usr/bin/env bash
# Configuration partagée du testnet local (N nœuds devnet sur loopback, N=30 par défaut).
# Doc : TEST-PLAN.md (à côté de ce script — hors docs/, qui est la doc servie par le nœud)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASE_DIR="${RHIZOME_TESTNET_DIR:-$ROOT/.testnet}"
BASE_PORT="${RHIZOME_TESTNET_BASE_PORT:-3000}"

# Binaire de nœud : natif (GraalVM) par défaut depuis la campagne 3. Un nœud natif démarre en
# quelques dizaines de ms et occupe ~3-4× moins de mémoire qu'une JVM — c'est ce qui rend 30
# nœuds tenables sur une seule machine. RHIZOME_TESTNET_NATIVE=0 retombe sur installDist
# (JVM), utile pour comparer les deux chemins ou déboguer avec un agent JVM.
NATIVE="${RHIZOME_TESTNET_NATIVE:-1}"
NATIVE_BIN="$ROOT/app-node/build/native/rhizome-node"
JVM_BIN="$ROOT/app-node/build/install/app-node/bin/app-node"
if [[ "$NATIVE" == "1" ]]; then NODE_BIN="$NATIVE_BIN"; else NODE_BIN="$JVM_BIN"; fi
WALLET_BIN="$ROOT/app-wallet/build/install/app-wallet/bin/app-wallet"

# Taille du réseau. 30 par défaut (campagne 3) : pair, donc partitionnable en deux moitiés
# égales — la forme qui fabrique l'égalité stricte de travail que le départage par tip hash
# doit trancher (S7/S15) — et assez grand pour que le PEX mette du temps à saturer.
NODES="${RHIZOME_TESTNET_NODES:-30}"

# Mineurs : 10 par défaut, répartis RÉGULIÈREMENT sur l'anneau (indices k·N/M). La régularité
# garantit deux propriétés : (a) chaque moitié {0..N/2-1} / {N/2..N-1} porte le même nombre de
# mineurs, donc une partition laisse les deux camps avancer à cadence comparable (sans cela
# une moitié se fige et la scission métastable ne peut pas se former) ; (b) les mineurs ne
# sont pas voisins, donc un bloc miné traverse plusieurs sauts de gossip avant d'atteindre
# l'autre mineur — c'est ce qui produit des oncles, et donc du travail GHOST à valider.
MINER_COUNT="${RHIZOME_TESTNET_MINERS:-10}"
MINERS=()
for _k in $(seq 0 $((MINER_COUNT - 1))); do MINERS+=("$((_k * NODES / MINER_COUNT))"); done

# Cadence de production (RHIZOME_BLOCK_INTERVAL_MS, posé sur chaque mineur). Sur devnet la
# difficulté est collée à son plancher (6) : le PoW SHA256 est instantané et la difficulté ne
# régule RIEN — le seul levier de cadence est le pacing du producteur. Il FAUT le régler : au
# défaut devnet (5 s), 10 mineurs produisent ~1 bloc/s, et comme la fenêtre de finalité fait
# 120 blocs (maxReorgDepth) elle ne dure alors que 2 min — moins qu'une partition utile, donc
# les deux camps finiraient en REORG_TOO_DEEP mutuel et S7/S15 deviendraient intestables.
#
# La cadence agrégée est bruitée : elle suit grossièrement intervalle/mineurs, mais les phases
# de rattrapage de sync produisent des rafales qui la font varier d'un facteur 3 à 5 (mesures :
# 25 s → 5 à 10 s/bloc réseau, 12 à 30 s/bloc par camp de 5 mineurs ; 10 s → ~2 s/bloc). Le
# critère qui tranche n'est pas la cadence elle-même mais le taux de fork qu'elle produit :
# à 10 s le réseau vit en fork permanent (3 à 7 tips distincts en continu, hauteurs à ±5) —
# les blocs arrivent plus vite que le gossip ne converge, et « tips distincts: 1 » devient
# inatteignable, donc inutilisable comme critère. À 25 s le réseau tient un tip unique.
# Valeur par défaut calibrée là-dessus, à revoir si MINER_COUNT change beaucoup.
BLOCK_MS="${RHIZOME_TESTNET_BLOCK_MS:-25000}"

# Plafond de tas par nœud. Sans lui chaque nœud prend -Xmx = 1/4 de la RAM machine : à 30
# nœuds la machine sature avant que le réseau ne converge. 256 Mo suffisent à un nœud devnet
# natif (chaîne de quelques milliers de blocs, mempool léger) ; le chemin JVM garde 384 Mo,
# le metaspace et les piles de threads y pèsent en plus.
if [[ "$NATIVE" == "1" ]]; then
  NODE_HEAP="${RHIZOME_TESTNET_HEAP:-256m}"
else
  NODE_HEAP="${RHIZOME_TESTNET_HEAP:-384m}"
fi

KEYS_DIR="$ROOT/scripts/local-testnet/keys"
CHAIN_CHECK="$ROOT/scripts/local-testnet/chaincheck.py"
PID_DIR="$BASE_DIR/pids"
PY="$(command -v python3 || command -v python)"

# Gradle 9.6.1 et le toolchain imposent 25 pour compiler ; Gradle 9.6.1 tourne sur JDK 25.
# Si JAVA_HOME pointe sur un JDK trop vieux, on tente le JDK 25 de sdkman.
ensure_jdk25() {
  local maj
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    maj="$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
  fi
  if [[ -z "${maj:-}" || "$maj" -lt 25 ]]; then
    for cand in "$HOME"/.sdkman/candidates/java/25*; do
      if [[ -x "$cand/bin/java" ]]; then
        export JAVA_HOME="$cand"
        echo "JAVA_HOME forcé vers $cand (Gradle exige un JDK ≥ 25)" >&2
        return 0
      fi
    done
    echo "JDK 25 requis pour Gradle (JAVA_HOME=$JAVA_HOME invalide)" >&2
    exit 1
  fi
}

node_port() { printf '%d' "$((BASE_PORT + $1))"; }
node_url()  { printf 'http://127.0.0.1:%s' "$(node_port "$1")"; }

# URL utilisée pour SEEDER un nœud — délibérément `localhost`, pas `127.0.0.1`.
# Un nœud s'annonce en `http://localhost:<port>` (NodeConfig.selfUrl), et
# PeerUrls.canonicalize normalise la casse mais NE RÉSOUT PAS le nom : `127.0.0.1:4704` et
# `localhost:4704` sont donc deux entrées de registre pour un seul nœud. Seeder sous la forme
# annoncée les fait coalescer — sinon `peers` compte double et le critère « ≥ 3 pairs » ne
# mesure plus rien (observé au smoke test : peers=4 pour 2 pairs réels).
node_seed_url() { printf 'http://localhost:%s' "$(node_port "$1")"; }
data_dir()  { printf '%s/node-%d' "$BASE_DIR" "$1"; }
log_file()  { printf '%s/logs/node-%d.log' "$BASE_DIR" "$1"; }
pid_file()  { printf '%s/node-%d.pid' "$PID_DIR" "$1"; }

is_miner() {
  local i=$1 m
  for m in "${MINERS[@]}"; do [[ "$m" == "$i" ]] && return 0; done
  return 1
}

# Peering initial en anneau : le nœud i se seed sur (i-1) et (i+1) mod NODES ;
# le reste du maillage est découvert par PEX.
#
# Arguments optionnels lo hi : restreint l'anneau à [lo..hi] (mode partition). Le nœud ne
# reçoit alors AUCUN seed hors de sa moitié, ce qui est la seule façon de partitionner sans
# iptables — un seed hors moitié serait re-découvert par PEX et recollerait le réseau.
node_seeds() {
  local i=$1 lo=${2:-0} hi=${3:-$((NODES - 1))}
  local span=$((hi - lo + 1)) prev next
  prev=$((lo + (i - lo - 1 + span) % span))
  next=$((lo + (i - lo + 1) % span))
  if (( span <= 1 )); then
    printf ''
  elif (( span == 2 )); then
    printf '%s' "$(node_seed_url "$prev")"
  else
    printf '%s,%s' "$(node_seed_url "$prev")" "$(node_seed_url "$next")"
  fi
}

# Extrait une clé JSON de /stats sans dépendre de jq. Les booléens sortent normalisés en
# "true"/"false" (Python imprimerait "True"), une clé absente en chaîne vide : les scripts
# peuvent comparer directement sans se soucier de la casse. Toute réponse inattendue (JSON
# invalide, structure non-{}) sort aussi en chaîne vide : un /stats étrange pendant un boot
# ou un reorg ne doit JAMAIS tuer le script appelant via `set -e` (deux morts de monitor.sh
# en campagne, au milieu d'un cycle, pendant un démarrage de réseau).
json_get() {
  local json=$1 key=$2
  "$PY" -c '
import json, sys
try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)
if not isinstance(data, dict):
    sys.exit(0)
v = data.get(sys.argv[1])
if isinstance(v, bool):
    print("true" if v else "false")
elif isinstance(v, list):
    print(len(v))
elif v is None:
    print("")
else:
    print(v)
' <<<"$json" "$key" 2>/dev/null || true
}

# /stats d'un nœud, vide si le nœud ne répond pas.
node_stats() {
  curl -sf --max-time 3 "$(node_url "$1")/stats" 2>/dev/null || true
}

# Présente le nœud `peer` au nœud `i` via /add_peer.
add_peer() {
  local i=$1 peer=$2
  curl -sf --max-time 5 -X POST -H 'X-Rhizome-Request: 1' \
    -d "{\"url\":\"$(node_seed_url "$peer")\"}" \
    "$(node_url "$i")/add_peer" >/dev/null 2>&1 || true
}

# Amorce le PEX sur la plage [lo..hi].
#
# Nécessaire parce qu'un anneau PUR ne peut pas s'amorcer : `GET /peers` retire délibérément
# les seeds (audit S-6 — un seed peut être l'infrastructure privée d'un opérateur), or dans
# un anneau l'intégralité des pairs de chaque nœud SONT ses seeds. Chacun annonce donc une
# liste vide et le maillage ne dépasse jamais 2. Sur un vrai réseau le problème ne se pose
# pas : les seeds publics portent déjà des pairs non-seed appris ailleurs.
#
# On reproduit cette condition en désignant `lo` comme hub : tous les autres lui sont
# présentés via /add_peer, ce qui crée chez lui des entrées NON-seed, donc annonçables, et
# le PEX se propage à partir de là.
#
# (La campagne 1 ne voyait pas le problème : ses seeds étaient en `127.0.0.1` et le PEX
# annonçait `localhost`, donc chaque nœud existait en double au registre — les « 10-12
# pairs » mesurés comptaient ce doublon, pas un vrai maillage.)
bootstrap_pex() {
  local lo=${1:-0} hi=${2:-$((NODES - 1))} i
  for i in $(seq $((lo + 1)) "$hi"); do
    add_peer "$lo" "$i"
    add_peer "$i" "$lo"
  done
}

# Vérifie que les ports des nœuds RÉELLEMENT lancés sont libres avant de démarrer les JVMs.
# Args : lo hi — la plage effectivement lancée (un seul nœud en -n, une moitié en -p, tout
# le réseau sinon). Vérifier au-delà refuserait de relancer une moitié pendant que l'autre
# tourne, c'est-à-dire précisément le scénario de partition S7.
check_ports_free() {
  local lo=${1:-0} hi=${2:-$((NODES - 1))}
  local i port
  for i in $(seq "$lo" "$hi"); do
    port="$(node_port "$i")"
    if ss -ltn 2>/dev/null | grep -qE "[:.]$port\b"; then
      echo "ERREUR: port $port déjà occupé — libérez-le ou relancez avec RHIZOME_TESTNET_BASE_PORT=<base>" >&2
      return 1
    fi
  done
  return 0
}

# Garde-fou mémoire : count × NODE_HEAP doit tenir dans la RAM disponible, avec ~40 % de
# marge pour le hors-tas (metaspace, piles, cache de blocs RocksDB). Une campagne qui meurt
# d'un OOM-killer à mi-parcours coûte plus cher que ce pré-vol.
# Arg : nombre de nœuds à lancer (défaut : tout le réseau).
check_memory() {
  local count=${1:-$NODES}
  local heap_mb avail_mb needed_mb overhead
  case "$NODE_HEAP" in
    *g|*G) heap_mb=$(( ${NODE_HEAP%[gG]} * 1024 )) ;;
    *m|*M) heap_mb=${NODE_HEAP%[mM]} ;;
    *)     heap_mb=$(( NODE_HEAP / 1024 / 1024 )) ;;
  esac
  # Marge hors-tas : ~40 % sur la JVM (metaspace, piles, code cache, cache de blocs RocksDB),
  # ~15 % sur l'image native — pas de metaspace, pas de JIT, le code est dans le binaire.
  if [[ "$NATIVE" == "1" ]]; then overhead=115; else overhead=140; fi
  needed_mb=$(( count * heap_mb * overhead / 100 ))
  avail_mb=$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo 2>/dev/null || echo 0)
  if (( avail_mb > 0 && needed_mb > avail_mb )); then
    echo "ERREUR: $count nœuds × $NODE_HEAP ≈ ${needed_mb} Mo requis, ${avail_mb} Mo disponibles." >&2
    echo "        Réduire RHIZOME_TESTNET_NODES ou RHIZOME_TESTNET_HEAP." >&2
    return 1
  fi
  return 0
}
