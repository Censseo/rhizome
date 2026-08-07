#!/usr/bin/env bash
# État du testnet : une ligne par nœud (hauteur, difficulté, pairs, mempool, dégradé)
# + divergences de hauteur entre nœuds.
set -euo pipefail
source "$(dirname "$0")/common.sh"

heights=()
for i in $(seq 0 $((NODES - 1))); do
  s="$(node_stats "$i")"
  if [[ -z "$s" ]]; then
    printf 'node %d  %-21s  DOWN\n' "$i" "$(node_url "$i")"
    heights+=(-1)
    continue
  fi
  h=$(json_get "$s" height)
  d=$(json_get "$s" difficulty)
  p=$(json_get "$s" peers)
  m=$(json_get "$s" mempool)
  rg=$(json_get "$s" reorgInProgress)
  deg=$(json_get "$s" degraded)
  stall=$(json_get "$s" syncRoundsWithoutProgress)
  bann=$(json_get "$s" syncPeersBanned)
  printf 'node %d  %-21s  h=%-5s diff=%-3s peers=%-3s mem=%-3s reorg=%-5s degraded=%s stallRounds=%-4s bannPeers=%s\n' \
    "$i" "$(node_url "$i")" "$h" "$d" "$p" "$m" "$rg" "$deg" "$stall" "$bann"
  if [[ "$bann" != "0" && -n "$bann" ]]; then
    echo "ALERTE: node $i — $bann pair(s) sauté(s) car bannis"
  fi
  if [[ "$stall" != "0" && -n "$stall" && "${stall#-}" -ge 6 ]]; then
    echo "ALERTE: node $i — $stall rounds sans avance de hauteur (~1 min+)"
  fi
  heights+=("$h")
done

max=-1; min=999999999
for h in "${heights[@]}"; do
  (( h > max )) && max=$h
  (( h < min )) && min=$h
done
echo "---"
echo "hauteur: min=$min max=$max écart=$((max - min))"
if (( max - min > 5 )); then
  echo "ALERTE: écart de hauteur > 5 (hors scénario de partition)"
fi
