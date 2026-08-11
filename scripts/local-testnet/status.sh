#!/usr/bin/env bash
# État du testnet : une ligne par nœud (hauteur, difficulté, pairs, mempool, dégradé,
# santé de sync) + divergences de hauteur et de tip entre nœuds.
#
# Les /stats sont échantillonnés EN PARALLÈLE (un curl en arrière-plan par nœud) : sur
# un réseau à blocs rapides, un balayage séquentiel lit le nœud 15 ~2 s après le nœud 0
# et chaque bloc arrivé entre deux lectures apparaît comme un « tip distinct » fantôme
# (les camps suivaient l'ordre de scrutation, pas une topologie). Le verdict de scission
# (chaincheck.py) vérifie en plus que les tips distincts sont sur des chaînes DIFFÉRENTES
# : des hauteurs différentes sur la même chaîne sont un retard de gossip, pas une scission.
set -euo pipefail
source "$(dirname "$0")/common.sh"

stats_dir="$(mktemp -d)"
trap 'rm -rf "$stats_dir"' EXIT

heights=()
declare -A TIPS
pids=()
for i in $(seq 0 $((NODES - 1))); do
  ( node_stats "$i" > "$stats_dir/stats-$i.json" ) &
  pids+=($!)
done
for p in "${pids[@]}"; do wait "$p" || true; done

for i in $(seq 0 $((NODES - 1))); do
  s="$(cat "$stats_dir/stats-$i.json")"
  if [[ -z "$s" ]]; then
    printf 'node %-3d %-22s  DOWN\n' "$i" "$(node_url "$i")"
    # Un nœud arrêté n'entre PAS dans le calcul d'écart : la sentinelle -1 faisait afficher
    # « écart=204 » et déclenchait l'alerte « écart > 5 » dès qu'un nœud était volontairement
    # arrêté (S5/S6/S7), c'est-à-dire précisément quand la lecture doit rester claire. Le
    # décompte des DOWN est déjà imprimé plus bas.
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
  ecl=$(json_get "$s" syncEclipsed)
  tip=$(json_get "$s" tipHash)
  if [[ -z "$h" || -z "$tip" ]]; then
    printf 'node %-3d %-22s  MALFORMÉ (/stats inattendu)\n' "$i" "$(node_url "$i")"
    continue
  fi
  TIPS[$i]="${tip:0:12}"
  printf 'node %-3d %-22s h=%-6s diff=%-3s peers=%-3s mem=%-3s reorg=%-5s degraded=%-4s stallR=%-4s bannP=%-3s eclipsed=%s\n' \
    "$i" "$(node_url "$i")" "$h" "$d" "$p" "$m" "$rg" "${deg:-null}" "$stall" "$bann" "$ecl"
  if [[ "$ecl" == "true" ]]; then
    echo "ALERTE: node $i — ÉCLIPSÉ (aucune source de sync utilisable ce round)"
  elif [[ -n "$bann" && "$bann" != "0" ]]; then
    echo "ALERTE: node $i — $bann pair(s) sauté(s) car bannis"
  fi
  if [[ -n "$stall" && "$stall" != "0" && "${stall#-}" -ge 6 ]]; then
    echo "ALERTE: node $i — $stall rounds sans avance de hauteur (~1 min+)"
  fi
  heights+=("$h")
done

echo "---"
if (( ${#heights[@]} == 0 )); then
  echo "hauteur: aucun nœud répondant"
  exit 0
fi
max=-1; min=999999999
for h in "${heights[@]}"; do
  (( h > max )) && max=$h
  (( h < min )) && min=$h
done
echo "hauteur: min=$min max=$max écart=$((max - min)) (sur ${#heights[@]} nœuds répondants)"
if (( max - min > 5 )); then
  echo "ALERTE: écart de hauteur > 5 (hors scénario de partition)"
fi

# Un écart de hauteur nul ne prouve pas l'unité du réseau : deux camps peuvent miner à la
# même cadence sur des branches différentes (la scission métastable de la campagne 2026-08).
# Le tip est le seul témoin — un réseau uni n'a qu'un tip par hauteur. MAIS des hauteurs
# DIFFÉRENTES sur la même chaîne (retard) produisent aussi des tips distincts : le verdict
# chaincheck.py tranche en vérifiant l'appartenance à la chaîne du nœud le plus haut.
#
# Le décompte des répondants est imprimé avec : « 1 tip distinct » sur 3 nœuds répondants
# quand 16 sont attendus n'est pas un réseau uni, c'est un réseau à moitié mort.
distinct=$(printf '%s\n' "${TIPS[@]}" | sort -u | grep -c . || true)
echo "tips distincts: $distinct (sur ${#TIPS[@]}/$NODES nœuds répondants)"
if (( ${#TIPS[@]} < NODES )); then
  echo "ALERTE: $((NODES - ${#TIPS[@]})) nœud(s) DOWN — l'unité du tip ne porte que sur les répondants"
fi
verdict="$("$PY" "$CHAIN_CHECK" "$stats_dir" "$BASE_PORT" 2>/dev/null || echo unknown)"
case "$verdict" in
  split)
    echo "ALERTE: $distinct branches sur des chaînes distinctes — le réseau est SCINDÉ (voir S7/S15)"
    for i in "${!TIPS[@]}"; do printf '  node %-3d tip=%s\n' "$i" "${TIPS[$i]}"; done | sort -k2
    ;;
  same-chain:*)
    echo "tips distincts sur la MÊME chaîne : retard de ${verdict#same-chain:} bloc(s) — pas une scission"
    ;;
  unknown)
    echo "verdict de scission indisponible (nœud de référence en reorg) — relancer status.sh"
    ;;
esac
