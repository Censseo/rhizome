#!/usr/bin/env bash
# Arrête les nœuds du testnet (SIGTERM, arrêt propre).
# Usage : stop.sh                tous les nœuds
#         stop.sh -n <idx>       un seul nœud (injection de panne, cf. S5/S7)
#         stop.sh -p <lo>-<hi>   une plage de nœuds (partition, cf. S7)
set -euo pipefail
source "$(dirname "$0")/common.sh"

lo=0
hi=$((NODES - 1))
while getopts "n:p:" opt; do
  case "$opt" in
    n) lo=$OPTARG; hi=$OPTARG ;;
    p) lo=${OPTARG%-*}; hi=${OPTARG#*-} ;;
    *) echo "usage: $0 [-n <idx>] [-p <lo>-<hi>]" >&2; exit 2 ;;
  esac
done

pids=()
for i in $(seq "$lo" "$hi"); do
  f="$(pid_file "$i")"
  [[ -s "$f" ]] && pids+=("$(cat "$f")")
done

if (( ${#pids[@]} == 0 )); then
  echo "rien à arrêter dans $lo..$hi (pas de pid dans $PID_DIR)" >&2
  exit 0
fi

# SIGTERM propre : l'arrêt laisse les stores RocksDB cohérents.
kill "${pids[@]}" 2>/dev/null || true
for _ in $(seq 1 30); do
  kill -0 "${pids[@]}" 2>/dev/null || break
  sleep 0.5
done
# Arrêt sale volontairement TESTÉ une fois (scénario S0, note « arrêt sale ») :
# pas de kill -9 automatique, signaler plutôt les survivants.
if kill -0 "${pids[@]}" 2>/dev/null; then
  echo "AVERTISSEMENT: nœuds encore vivants après 15 s (arret sale): ${pids[*]}" >&2
fi

for i in $(seq "$lo" "$hi"); do rm -f "$(pid_file "$i")"; done
echo "nœuds $lo..$hi arrêtés."
