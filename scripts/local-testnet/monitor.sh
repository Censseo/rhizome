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
#
# Alertes par TRANSITION, pas par cycle (chantier 6 du plan staging) : réimprimer la même
# alarme toutes les 2 s est exactement pourquoi personne ne les lit en pratique. `alert`
# n'écrit qu'au déclenchement et au retour à la normale, et livre en plus un webhook si
# RHIZOME_MONITOR_WEBHOOK_URL est posé (best-effort, jamais bloquant pour la boucle).
#
# Trois alarmes s'ajoutent aux règles déjà écrites (degraded/eclipsed/stall/split), parce que
# ce sont celles qui attraperaient un testnet mort silencieusement :
#   - StaleTip   : plus aucun bloc depuis 10x le temps de bloc visé — un réseau d'accord avec
#                  lui-même où PERSONNE ne mine.
#   - DiskLow    : espace libre du répertoire de données d'un nœud < 10 %.
#   - SeedDisagreement : les nœuds ne sont pas d'accord sur chainId, sur la hauteur d'activation
#                  de la courbe d'émission ou sur le hash de genesis — le fil de détente d'un
#                  fork mal coordonné. Le hash de genesis est vérifié par un GET /block?blockId=1
#                  à cadence réduite (SLOW_CHECK_EVERY) plutôt qu'à chaque cycle de 2 s : il ne
#                  varie jamais tant qu'un nœud n'a pas divergé à la racine, l'interroger aussi
#                  souvent que /stats n'apporterait rien et coûterait un aller-retour de plus par
#                  nœud et par cycle.
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

DESIRED_BLOCK_TIME_SEC="$(profile_get DESIRED_BLOCK_TIME_SEC)"
STALE_TIP_FACTOR="${RHIZOME_MONITOR_STALE_TIP_FACTOR:-10}"
DISK_LOW_PCT="${RHIZOME_MONITOR_DISK_LOW_PCT:-10}"
SLOW_CHECK_EVERY="${RHIZOME_MONITOR_SLOW_CHECK_CYCLES:-15}"  # ~30 s à 2 s/cycle
WEBHOOK_URL="${RHIZOME_MONITOR_WEBHOOK_URL:-}"

# Comme json_get, mais lit une clé imbriquée sous "emission" (activationHeight, decayStartHeight,
# ...) : ces champs n'existent qu'à ce niveau dans /stats et /info (voir NetworkParameters /
# EmissionCurve côté Java). Même contrat de robustesse : jamais d'exception, chaîne vide si absent.
json_get_emission() {
  local json=$1 key=$2
  "$PY" -c '
import json, sys
try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)
if not isinstance(data, dict):
    sys.exit(0)
v = data.get("emission")
if not isinstance(v, dict):
    sys.exit(0)
r = v.get(sys.argv[1])
print("" if r is None else r)
' <<<"$json" "$key" 2>/dev/null || true
}

declare -A ALERT_STATE  # clé -> "1" si actuellement déclenchée ; absente/0 sinon

# Émet une alerte par TRANSITION uniquement : un appel avec active=1 alors que la clé était
# déjà active ne réimprime rien, pareil pour la résolution. `key` doit être stable pour la même
# condition d'un cycle à l'autre (ex. "deg:3", "stale-tip", "seed-disagreement:genesis").
alert() {
  local key=$1 active=$2 msg=$3
  local prev="${ALERT_STATE[$key]:-0}"
  if [[ "$active" == "1" && "$prev" != "1" ]]; then
    printf '!! %s\n' "$msg" >&2
    ALERT_STATE[$key]=1
    webhook_notify "triggered" "$key" "$msg"
  elif [[ "$active" != "1" && "$prev" == "1" ]]; then
    printf '.. résolu: %s\n' "$msg" >&2
    ALERT_STATE[$key]=0
    webhook_notify "resolved" "$key" "$msg"
  fi
}

# Livraison best-effort : un webhook injoignable ne doit jamais ralentir ni casser la boucle de
# supervision (mêmes raisons que le `set +e` de la boucle principale, cf. plus bas), donc en
# arrière-plan avec un timeout court et une sortie ignorée.
webhook_notify() {
  [[ -z "$WEBHOOK_URL" ]] && return 0
  local event=$1 key=$2 msg=$3
  local ts_now; ts_now=$(date +%s)
  ( curl -sf --max-time 5 -X POST -H 'Content-Type: application/json' \
      -d "$("$PY" -c 'import json,sys; print(json.dumps({"event":sys.argv[1],"key":sys.argv[2],"message":sys.argv[3],"ts":int(sys.argv[4])}))' \
        "$event" "$key" "$msg" "$ts_now")" \
      "$WEBHOOK_URL" >/dev/null 2>&1 || true ) &
}

# Supervision : `errexit` est DÉSACTIVÉ pour la boucle d'échantillonnage. Le monitor est mort
# deux fois en campagne au pire moment — au `stop.sh` qui ouvre une partition, quand tous les
# /stats échouent d'un coup — en laissant un CSV tronqué juste avant la fenêtre qu'il devait
# documenter. Un cycle qui échoue doit produire une ligne « DOWN », pas la fin de la
# supervision ; toutes les commandes de la boucle sont déjà défensives (json_get avale les
# réponses inattendues, node_stats renvoie vide, chaincheck retombe sur `unknown`).
set +e
split_cycles=0
cycle=0
while true; do
  ts=$(date +%s)
  tips=()
  activation_heights=()
  decay_starts=()
  chain_ids=()
  max_last_block_ts=0
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
    cid=$(json_get "$s" chainId)
    lastBlockTs=$(json_get "$s" lastBlockTimestamp)
    ah=$(json_get_emission "$s" activationHeight)
    ds=$(json_get_emission "$s" decayStartHeight)
    tips+=("$tip")
    [[ -n "$cid" ]] && chain_ids+=("$cid")
    [[ -n "$ah" ]] && activation_heights+=("$ah")
    [[ -n "$ds" ]] && decay_starts+=("$ds")
    if [[ "$lastBlockTs" =~ ^[0-9]+$ ]] && (( lastBlockTs > max_last_block_ts )); then
      max_last_block_ts=$lastBlockTs
    fi
    printf '%s node %-3d %-22s %6s %-13s %5s %5s %5s %8s %6s %-8s %7s %5s %5s\n' \
      "$ts" "$i" "$(node_url "$i")" "$h" "${tip:0:12}" "$d" "$p" "$m" "$a" "$rg" "${deg:-null}" "$stall" "$bann" "$ecl"
    printf '%s,%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
      "$ts" "$i" "$h" "$tip" "$d" "$p" "$m" "$a" "$rg" "${deg:-null}" "$stall" "$bann" "$ecl" >> "$CSV"
    alert "deg:$i" "$([[ -n "$deg" && "$deg" != "null" ]] && echo 1 || echo 0)" \
      "node $i: degraded = ${deg:-null}"
    alert "ecl:$i" "$([[ "$ecl" == "true" ]] && echo 1 || echo 0)" \
      "node $i: ÉCLIPSÉ (aucune source de sync utilisable)"
    alert "banned:$i" "$([[ -n "$bann" && "$bann" != "0" ]] && echo 1 || echo 0)" \
      "node $i: $bann pair(s) sauté(s) car bannis"
    alert "stall:$i" "$([[ -n "$stall" && "$stall" != "0" && "${stall#-}" -ge 6 ]] && echo 1 || echo 0)" \
      "node $i: $stall rounds sans avance de hauteur (~1 min+)"
  done
  # Scission silencieuse : deux camps à la même hauteur et à la même cadence ne se
  # distinguent que par leur tip. C'est l'alerte qui manquait à la campagne précédente.
  # Le verdict chaincheck distingue les branches réelles du simple retard sur une même
  # chaîne, et l'alerte exige 3 cycles consécutifs (fork transitoire ≠ scission).
  verdict="$("$PY" "$CHAIN_CHECK" "$stats_dir" "$BASE_PORT" 2>/dev/null || echo unknown)"
  if [[ "$verdict" == "split" ]]; then
    (( split_cycles++ ))
  else
    split_cycles=0
  fi
  branch_count=$(printf '%s\n' "${tips[@]}" | sort -u | grep -c . || true)
  alert "split" "$(( split_cycles >= 3 ? 1 : 0 ))" \
    "RÉSEAU SCINDÉ: $branch_count branches distinctes parmi ${#tips[@]} nœuds répondants"

  # StaleTip : tous les nœuds peuvent être sains et d'accord entre eux pendant que personne ne
  # mine — rien d'autre ici ne le dit. Comparé au dernier bloc VU PAR N'IMPORTE QUEL nœud
  # (le max), pas au nœud 0 seul, pour ne pas confondre « ce nœud est en retard de sync » avec
  # « le réseau entier a arrêté de produire ».
  if (( max_last_block_ts > 0 )); then
    now_ms=$(( ts * 1000 ))
    stale_threshold_ms=$(( STALE_TIP_FACTOR * DESIRED_BLOCK_TIME_SEC * 1000 ))
    stale_active=$(( (now_ms - max_last_block_ts) > stale_threshold_ms ? 1 : 0 ))
    alert "stale-tip" "$stale_active" \
      "STALE TIP: aucun bloc depuis $(( (now_ms - max_last_block_ts) / 1000 ))s (seuil ${STALE_TIP_FACTOR}x${DESIRED_BLOCK_TIME_SEC}s)"
  fi

  # SeedDisagreement (activation) : chainId et hauteurs d'activation/décroissance de la courbe
  # doivent être un singleton parmi les nœuds répondants — sinon un fork mal coordonné approche
  # sans que rien ne le dise avant qu'il n'arrive.
  chain_uniq=$(printf '%s\n' "${chain_ids[@]}" | sort -u | grep -c . || true)
  ah_uniq=$(printf '%s\n' "${activation_heights[@]}" | sort -u | grep -c . || true)
  ds_uniq=$(printf '%s\n' "${decay_starts[@]}" | sort -u | grep -c . || true)
  alert "seed-disagreement:chainId" "$(( chain_uniq > 1 ? 1 : 0 ))" \
    "SEED DISAGREEMENT: $chain_uniq chainId distincts parmi les nœuds répondants"
  alert "seed-disagreement:activation" "$(( ah_uniq > 1 ? 1 : 0 ))" \
    "SEED DISAGREEMENT: $ah_uniq hauteurs d'activation de courbe distinctes"
  alert "seed-disagreement:decay" "$(( ds_uniq > 1 ? 1 : 0 ))" \
    "SEED DISAGREEMENT: $ds_uniq hauteurs de décroissance distinctes"

  # Vérifications coûteuses (un aller-retour HTTP ou un `df` de plus par nœud) : cadence
  # réduite plutôt qu'à chaque cycle de 2 s, cf. commentaire d'en-tête.
  if (( cycle % SLOW_CHECK_EVERY == 0 )); then
    genesis_hashes=()
    for i in $(seq 0 $((NODES - 1))); do
      gh=$(curl -sf --max-time 3 "$(resolve_base_url "$i")/block?blockId=1" 2>/dev/null \
        | "$PY" -c 'import json,sys
try:
    d=json.load(sys.stdin)
    print(d.get("hash",""))
except Exception:
    pass' 2>/dev/null || true)
      [[ -n "$gh" ]] && genesis_hashes+=("$gh")

      pct_free=""
      if [[ -d "$(data_dir "$i")" ]]; then
        pct_used=$(df -P "$(data_dir "$i")" 2>/dev/null | tail -1 | awk '{gsub("%","",$5); print $5}')
        [[ "$pct_used" =~ ^[0-9]+$ ]] && pct_free=$(( 100 - pct_used ))
      fi
      if [[ -n "$pct_free" ]]; then
        alert "disk-low:$i" "$(( pct_free < DISK_LOW_PCT ? 1 : 0 ))" \
          "DISK LOW: node $i a $pct_free% d'espace libre (< ${DISK_LOW_PCT}%) sur $(data_dir "$i")"
      fi
    done
    gh_uniq=$(printf '%s\n' "${genesis_hashes[@]}" | sort -u | grep -c . || true)
    alert "seed-disagreement:genesis" "$(( gh_uniq > 1 ? 1 : 0 ))" \
      "SEED DISAGREEMENT: $gh_uniq hash de genesis distincts (GET /block?blockId=1)"
  fi

  rm -rf "$stats_dir"
  (( cycle++ ))
  sleep 2
done
