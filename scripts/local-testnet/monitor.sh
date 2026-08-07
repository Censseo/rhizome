#!/usr/bin/env bash
# Supervision continue : interroge /stats des 10 nœuds toutes les 2 s,
# affiche un tableau et écrit .testnet/monitor.csv. Ctrl-C pour arrêter.
set -euo pipefail
source "$(dirname "$0")/common.sh"

CSV="$BASE_DIR/monitor.csv"
mkdir -p "$BASE_DIR"
if [[ ! -f "$CSV" ]]; then
  printf 'ts,node,height,difficulty,peers,mempool,avgBlockIntervalMs,reorgInProgress,degraded,syncRoundsWithoutProgress,syncPeersBanned\n' > "$CSV"
fi

trap 'echo; echo "monitor stoppé (csv: $CSV)"' EXIT

printf '%-8s %-21s %5s %5s %5s %5s %8s %6s %-8s %7s %5s\n' \
  "ts" "node" "haut" "diff" "pairs" "mem" "avgMs" "reorg" "degraded" "stallR" "bannP"

while true; do
  ts=$(date +%s)
  for i in $(seq 0 $((NODES - 1))); do
    s="$(node_stats "$i")"
    if [[ -z "$s" ]]; then
      printf '%s %s %-21s  DOWN\n' "$ts" "node $i" "$(node_url "$i")"
      continue
    fi
    h=$(json_get "$s" height)
    d=$(json_get "$s" difficulty)
    p=$(json_get "$s" peers)
    m=$(json_get "$s" mempool)
    a=$(json_get "$s" avgBlockIntervalMs)
    rg=$(json_get "$s" reorgInProgress)
    deg=$(json_get "$s" degraded)
    stall=$(json_get "$s" syncRoundsWithoutProgress)
    bann=$(json_get "$s" syncPeersBanned)
    printf '%s node %d %-21s %5s %5s %5s %5s %8s %6s %-8s %7s %5s\n' \
      "$ts" "$i" "$(node_url "$i")" "$h" "$d" "$p" "$m" "$a" "$rg" "$deg" "$stall" "$bann"
    printf '%s,%d,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
      "$ts" "$i" "$h" "$d" "$p" "$m" "$a" "$rg" "$deg" "$stall" "$bann" >> "$CSV"
    if [[ "$deg" != "0" && "$deg" != "null" ]]; then
      printf '!! node %d: degraded = %s\n' "$i" "$deg" >&2
    fi
    if [[ "$bann" != "0" && -n "$bann" ]]; then
      printf '!! node %d: %s pair(s) sauté(s) car bannis\n' "$i" "$bann" >&2
    fi
    if [[ "$stall" != "0" && -n "$stall" && "${stall#-}" -ge 6 ]]; then
      printf '!! node %d: %s rounds sans avance de hauteur (~1 min+)\n' "$i" "$stall" >&2
    fi
  done
  sleep 2
done
