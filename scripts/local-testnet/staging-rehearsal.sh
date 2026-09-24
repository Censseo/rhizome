#!/usr/bin/env bash
# Rehearsal launcher for the `staging` network profile (chain id 4, Pufferfish2, real 5 s target)
# — DELIBERATELY DIFFERENT from start.sh, which is devnet/testnet-shaped: it forces
# RHIZOME_BLOCK_INTERVAL_MS to pace SHA256 devnet blocks, which staging must NEVER do (it would
# make every retarget window look artificially fast — node.env.example's own warning on that
# variable). This script leaves block pacing untouched so nodes mine at their real Pufferfish2
# rate and the difficulty retarget loop does real work.
#
# profiles/staging.env's own header says this profile is "multi-VM by construction" and not
# meant for a local battery run — true for the real public rehearsal (chantier 2). This script
# is the single-host STAND-IN for that: every node is a real, separate OS process (native
# binary), doing real HTTP, real gossip, real PUFFERFISH2 PoW — the thing a local battery
# harness intentionally does NOT do for staging. It exists so the profile can be exercised for
# real before multi-VM access is available, not to replace the eventual multi-VM campaign.
#
# Peering is a full mesh of CONFIGURED seeds (RHIZOME_PEERS), not PEX discovery. CORRECTION
# (found by actually running this): the SSRF filter (PeerHosts.pin, blockPrivate) is NOT
# seed-aware — it blocks any peer that resolves to a non-routable address regardless of whether
# it came from RHIZOME_PEERS or PEX, so on a single host every connection is loopback and the
# default filtered path leaves every node completely unpeered (confirmed: 6 nodes each mining
# their own isolated chain, /peers empty on all of them, zero actual gossip). A same-host
# rehearsal therefore has to set RHIZOME_ALLOW_PRIVATE_PEERS=true, exactly like start.sh does —
# the default filtered path genuinely requires distinct routable hosts to exercise for real
# (chantier 2, still blocked on multi-VM access), not something this script can stand in for.
#
# Usage:
#   staging-rehearsal.sh start [N] [K]   # N nodes (default 6), first K are miners (default 3)
#   staging-rehearsal.sh stop
#   staging-rehearsal.sh status
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASE_DIR="${RHIZOME_STAGING_DIR:-$ROOT/.staging-rehearsal}"
BASE_PORT="${RHIZOME_STAGING_BASE_PORT:-4500}"
NATIVE_BIN="$ROOT/app-node/build/native/rhizome-node"
WALLET_BIN="$ROOT/app-wallet/build/install/app-wallet/bin/app-wallet"

cmd="${1:-start}"
N="${2:-6}"
K="${3:-3}"

mkdir -p "$BASE_DIR/logs" "$BASE_DIR/keys" "$BASE_DIR/pids"

node_data() { echo "$BASE_DIR/data/node-$1"; }
node_port() { echo "$((BASE_PORT + $1))"; }
node_url()  { echo "http://127.0.0.1:$(node_port "$1")"; }
node_pidfile() { echo "$BASE_DIR/pids/node-$1.pid"; }

start() {
  [[ -x "$NATIVE_BIN" ]] || { echo "ERREUR: binaire natif absent ($NATIVE_BIN) — ./gradlew :app-node:nativeImage" >&2; exit 1; }
  [[ -x "$WALLET_BIN" ]] || { echo "ERREUR: wallet absent ($WALLET_BIN) — ./gradlew :app-wallet:installDist" >&2; exit 1; }

  # Mesh de pairs: chaque nœud connaît tous les autres, configuré (pas PEX) — évite tout besoin
  # de RHIZOME_ALLOW_PRIVATE_PEERS puisque chaque pair est un seed explicite.
  peers_for() {
    local self=$1 out=""
    for ((j = 0; j < N; j++)); do
      [[ "$j" == "$self" ]] && continue
      out+="$(node_url "$j"),"
    done
    echo "${out%,}"
  }

  echo "démarrage de $N nœuds staging ($K mineurs) sur ports $BASE_PORT..$((BASE_PORT + N - 1))"
  for ((i = 0; i < N; i++)); do
    mkdir -p "$(node_data "$i")"
    env_args=(
      RHIZOME_NETWORK=staging
      RHIZOME_PORT="$(node_port "$i")"
      RHIZOME_DATA="$(node_data "$i")"
      RHIZOME_PEERS="$(peers_for "$i")"
      RHIZOME_ADVERTISE="$(node_url "$i")"
      RHIZOME_ALLOW_PRIVATE_PEERS=true
    )
    if (( i < K )); then
      key="$BASE_DIR/keys/miner-$i.key"
      [[ -f "$key" ]] || "$WALLET_BIN" keygen "$key" --plaintext >/dev/null
      addr="$("$WALLET_BIN" address "$key")"
      env_args+=(RHIZOME_MINER="$addr")
      echo "  node-$i: port $(node_port "$i"), MINEUR, addr=$addr"
    else
      echo "  node-$i: port $(node_port "$i"), relais"
    fi
    env "${env_args[@]}" "$NATIVE_BIN" > "$BASE_DIR/logs/node-$i.log" 2>&1 &
    echo $! > "$(node_pidfile "$i")"
  done
  echo "lancé. logs: $BASE_DIR/logs/node-*.log — statut: $0 status"
}

stop() {
  shopt -s nullglob
  for pidfile in "$BASE_DIR"/pids/*.pid; do
    pid="$(cat "$pidfile")"
    if kill -0 "$pid" 2>/dev/null; then
      kill -TERM "$pid"
      echo "arrêt de $(basename "$pidfile" .pid) (pid $pid)"
    fi
    rm -f "$pidfile"
  done
}

status() {
  shopt -s nullglob
  for pidfile in "$BASE_DIR"/pids/*.pid; do
    name="$(basename "$pidfile" .pid)"
    pid="$(cat "$pidfile")"
    if kill -0 "$pid" 2>/dev/null; then
      echo "$name: up (pid $pid)"
    else
      echo "$name: down"
    fi
  done
}

case "$cmd" in
  start) start ;;
  stop) stop ;;
  status) status ;;
  *) echo "usage: $0 {start [N] [K]|stop|status}" >&2; exit 2 ;;
esac
