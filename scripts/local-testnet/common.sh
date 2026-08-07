#!/usr/bin/env bash
# Configuration partagée du testnet local (N nœuds devnet sur loopback, N=16 par défaut).
# Doc : TEST-PLAN.md (à côté de ce script — hors docs/, qui est la doc servie par le nœud)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASE_DIR="${RHIZOME_TESTNET_DIR:-$ROOT/.testnet}"
NODE_BIN="$ROOT/app-node/build/install/app-node/bin/app-node"
WALLET_BIN="$ROOT/app-wallet/build/install/app-wallet/bin/app-wallet"
BASE_PORT="${RHIZOME_TESTNET_BASE_PORT:-3000}"

# Taille du réseau. 16 par défaut : au-delà de 10 (la campagne précédente) et pair, donc
# partitionnable en deux moitiés égales — la forme qui fabrique l'égalité stricte de travail
# que le départage par tip hash doit trancher (S7/S15).
NODES="${RHIZOME_TESTNET_NODES:-16}"

# Mineurs : deux par moitié, pour qu'une partition {0..N/2-1} vs {N/2..N-1} laisse chaque
# camp produire des blocs à cadence comparable. Sans cela une moitié se fige et la scission
# métastable — le scénario le plus intéressant — ne peut pas se former.
MINERS=(0 1 "$((NODES / 2))" "$((NODES / 2 + 1))")

# Plafond de tas par nœud. Sans lui chaque JVM prend -Xmx = 1/4 de la RAM machine : à 16
# nœuds la machine sature avant que le réseau ne converge. 384 Mo suffisent largement à un
# nœud devnet (chaîne de quelques milliers de blocs, mempool vide).
NODE_HEAP="${RHIZOME_TESTNET_HEAP:-384m}"

KEYS_DIR="$ROOT/scripts/local-testnet/keys"
PID_DIR="$BASE_DIR/pids"
PY="$(command -v python3 || command -v python)"

# Gradle (8.14) doit tourner sur un JDK ≤ 24 ; le toolchain impose 21 pour compiler.
# Si JAVA_HOME pointe sur un JDK trop récent, on tente le JDK 21 de sdkman.
ensure_jdk21() {
  local maj
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    maj="$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
  fi
  if [[ -z "${maj:-}" || "$maj" -gt 24 ]]; then
    for cand in "$HOME"/.sdkman/candidates/java/21*; do
      if [[ -x "$cand/bin/java" ]]; then
        export JAVA_HOME="$cand"
        echo "JAVA_HOME forcé vers $cand (Gradle exige un JDK ≤ 24)" >&2
        return 0
      fi
    done
    echo "JDK 21 requis pour Gradle (JAVA_HOME=$JAVA_HOME invalide)" >&2
    exit 1
  fi
}

node_port() { printf '%d' "$((BASE_PORT + $1))"; }
node_url()  { printf 'http://127.0.0.1:%s' "$(node_port "$1")"; }
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
    printf '%s' "$(node_url "$prev")"
  else
    printf '%s,%s' "$(node_url "$prev")" "$(node_url "$next")"
  fi
}

# Extrait une clé JSON de /stats sans dépendre de jq. Les booléens sortent normalisés en
# "true"/"false" (Python imprimerait "True"), une clé absente en chaîne vide : les scripts
# peuvent comparer directement sans se soucier de la casse.
json_get() {
  local json=$1 key=$2
  "$PY" -c '
import json, sys
data = json.load(sys.stdin)
v = data.get(sys.argv[1])
if isinstance(v, bool):
    print("true" if v else "false")
elif isinstance(v, list):
    print(len(v))
elif v is None:
    print("")
else:
    print(v)
' <<<"$json" "$key"
}

# /stats d'un nœud, vide si le nœud ne répond pas.
node_stats() {
  curl -sf --max-time 3 "$(node_url "$1")/stats" 2>/dev/null || true
}

# Vérifie que les ports de la plage sont libres avant de lancer les JVMs.
# Arg optionnel : en mode nœud unique (-n <idx>), seul ce port est vérifié.
check_ports_free() {
  local single=${1:--1}
  local i port
  for i in $(seq 0 $((NODES - 1))); do
    if (( single >= 0 )) && (( i != single )); then
      continue
    fi
    port="$(node_port "$i")"
    if ss -ltn 2>/dev/null | grep -qE "[:.]$port\b"; then
      echo "ERREUR: port $port déjà occupé — libérez-le ou relancez avec RHIZOME_TESTNET_BASE_PORT=<base>" >&2
      return 1
    fi
  done
  return 0
}

# Garde-fou mémoire : N × NODE_HEAP doit tenir dans la RAM disponible, avec ~40 % de marge
# pour le hors-tas (metaspace, piles, cache de blocs RocksDB). Une campagne qui meurt d'un
# OOM-killer à mi-parcours coûte plus cher que ce test.
check_memory() {
  local heap_mb avail_mb needed_mb
  case "$NODE_HEAP" in
    *g|*G) heap_mb=$(( ${NODE_HEAP%[gG]} * 1024 )) ;;
    *m|*M) heap_mb=${NODE_HEAP%[mM]} ;;
    *)     heap_mb=$(( NODE_HEAP / 1024 / 1024 )) ;;
  esac
  needed_mb=$(( NODES * heap_mb * 14 / 10 ))
  avail_mb=$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo 2>/dev/null || echo 0)
  if (( avail_mb > 0 && needed_mb > avail_mb )); then
    echo "ERREUR: $NODES nœuds × $NODE_HEAP ≈ ${needed_mb} Mo requis, ${avail_mb} Mo disponibles." >&2
    echo "        Réduire RHIZOME_TESTNET_NODES ou RHIZOME_TESTNET_HEAP." >&2
    return 1
  fi
  return 0
}
