#!/usr/bin/env bash
# Supervision continue : interroge /stats des N nœuds toutes les 2 s, affiche un tableau et
# écrit .testnet/monitor.csv. Ctrl-C pour arrêter.
#
# Les /stats sont échantillonnés EN PARALLÈLE (un curl en arrière-plan par nœud) : sur un
# réseau à blocs rapides, un balayage séquentiel lit le nœud 15 ~2 s après le nœud 0 et
# chaque bloc arrivé entre deux lectures apparaît comme un « tip distinct » fantôme. Le
# verdict de scission (chaincheck.py) vérifie que des tips distincts sont sur des chaînes
# DIFFÉRENTES, et l'alerte exige 3 cycles consécutifs de scission : un fork transitoire
# (1-2 s, normal à cette cadence) n'alerte pas, une scission métastable (S7/S15, minutes)
# alerte en continu.
set -euo pipefail
source "$(dirname "$0")/common.sh"

CSV="$BASE_DIR/monitor.csv"
mkdir -p "$BASE_DIR"
if [[ ! -f "$CSV" ]]; then
  printf 'ts,node,height,tipHash,difficulty,peers,mempool,avgBlockIntervalMs,reorgInProgress,degraded,syncRoundsWithoutProgress,syncPeersBanned,syncEclipsed\n' > "$CSV"
fi

trap 'echo; echo "monitor stoppé (csv: $CSV)"' EXIT

printf '%-10s %-5s %-22s %6s %-13s %5s %5s %5s %8s %6s %-8s %7s %5s %5s\n' \
  "ts" "node" "url" "haut" "tip" "diff" "pairs" "mem" "avgMs" "reorg" "degraded" "stallR" "bannP" "ecl"

# Supervision : `errexit` est DÉSACTIVÉ pour la boucle d'échantillonnage. Le monitor est mort
# deux fois en campagne au pire moment — au `stop.sh` qui ouvre une partition, quand tous les
# /stats échouent d'un coup — en laissant un CSV tronqué juste avant la fenêtre qu'il devait
# documenter. Un cycle qui échoue doit produire une ligne « DOWN », pas la fin de la
# supervision ; toutes les commandes de la boucle sont déjà défensives (json_get avale les
# réponses inattendues, node_stats renvoie vide, chaincheck retombe sur `unknown`).
set +e
split_cycles=0
while true; do
  ts=$(date +%s)
  tips=()
  stats_dir="$(mktemp -d)"
  pids=()
  for i in $(seq 0 $((NODES - 1))); do
    ( node_stats "$i" > "$stats_dir/stats-$i.json" ) &
    pids+=($!)
  done
  for p in "${pids[@]}"; do wait "$p" || true; done
  for i in $(seq 0 $((NODES - 1))); do
    s="$(cat "$stats_dir/stats-$i.json")"
    if [[ -z "$s" ]]; then
      printf '%s node %-3d %-22s  DOWN\n' "$ts" "$i" "$(node_url "$i")"
      continue
    fi
    h=$(json_get "$s" height)
    tip=$(json_get "$s" tipHash)
    if [[ -z "$h" || -z "$tip" ]]; then
      printf '%s node %-3d %-22s  MALFORMÉ (/stats inattendu)\n' "$ts" "$i" "$(node_url "$i")"
      continue
    fi
    d=$(json_get "$s" difficulty)
    p=$(json_get "$s" peers)
    m=$(json_get "$s" mempool)
    a=$(json_get "$s" avgBlockIntervalMs)
    rg=$(json_get "$s" reorgInProgress)
    deg=$(json_get "$s" degraded)
    stall=$(json_get "$s" syncRoundsWithoutProgress)
    bann=$(json_get "$s" syncPeersBanned)
    ecl=$(json_get "$s" syncEclipsed)
    tips+=("$tip")
    printf '%s node %-3d %-22s %6s %-13s %5s %5s %5s %8s %6s %-8s %7s %5s %5s\n' \
      "$ts" "$i" "$(node_url "$i")" "$h" "${tip:0:12}" "$d" "$p" "$m" "$a" "$rg" "${deg:-null}" "$stall" "$bann" "$ecl"
    printf '%s,%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
      "$ts" "$i" "$h" "$tip" "$d" "$p" "$m" "$a" "$rg" "${deg:-null}" "$stall" "$bann" "$ecl" >> "$CSV"
    if [[ -n "$deg" && "$deg" != "null" ]]; then
      printf '!! node %d: degraded = %s\n' "$i" "$deg" >&2
    fi
    if [[ "$ecl" == "true" ]]; then
      printf '!! node %d: ÉCLIPSÉ (aucune source de sync utilisable)\n' "$i" >&2
    elif [[ -n "$bann" && "$bann" != "0" ]]; then
      printf '!! node %d: %s pair(s) sauté(s) car bannis\n' "$i" "$bann" >&2
    fi
    if [[ -n "$stall" && "$stall" != "0" && "${stall#-}" -ge 6 ]]; then
      printf '!! node %d: %s rounds sans avance de hauteur (~1 min+)\n' "$i" "$stall" >&2
    fi
  done
  # Scission silencieuse : deux camps à la même hauteur et à la même cadence ne se
  # distinguent que par leur tip. C'est l'alerte qui manquait à la campagne précédente.
  # Le verdict chaincheck distingue les branches réelles du simple retard sur une même
  # chaîne, et l'alerte exige 3 cycles consécutifs (fork transitoire ≠ scission).
  verdict="$("$PY" "$CHAIN_CHECK" "$stats_dir" "$BASE_PORT" 2>/dev/null || echo unknown)"
  if [[ "$verdict" == "split" ]]; then
    (( split_cycles++ ))
    if (( split_cycles >= 3 )); then
      printf '!! RÉSEAU SCINDÉ: %d branches distinctes parmi %d nœuds répondants (cycle %d/%d)\n' \
        "$(printf '%s\n' "${tips[@]}" | sort -u | grep -c . || true)" "${#tips[@]}" "$split_cycles" "$split_cycles" >&2
    fi
  else
    split_cycles=0
  fi
  rm -rf "$stats_dir"
  sleep 2
done
