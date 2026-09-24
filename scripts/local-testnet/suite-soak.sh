#!/usr/bin/env bash
# Batterie « soak » — réduit monitor.sh + sim-tx.sh + sim-contract.sh, chacun avec son PROPRE
# CSV hors du système de verdict de ce harnais, en cas PASS/FAIL/METRIC de première classe dans
# .testnet/results/soak.tsv, comme toute autre batterie.
#
# CE N'EST PAS un nouvel échantillonneur : monitor.sh échantillonne déjà /stats de tous les
# nœuds toutes les 2 s (monitor.csv), sim-tx.sh charge le réseau de transactions (sim/tx.csv),
# sim-contract.sh le charge d'appels de contrat en vérifiant le déterminisme périodique
# (sim/contract.csv) — les trois outils existent et tournent déjà. Cette batterie se contente de
# les LANCER, de les laisser tourner une durée BORNÉE (`run_for`, suite-common.sh), de les
# ARRÊTER proprement, puis de RÉDUIRE leurs trois CSV en verdicts : c'est ce qui manquait — un
# run de soak produisait trois fichiers que personne ne relisait, jamais un résultat de campagne.
#
# Ce qu'un run UNIQUE, sur CETTE machine, peut prouver — et ce qu'il ne peut structurellement PAS
# prouver (à écrire noir sur blanc, comme le fait chaque batterie de ce harnais pour son propre
# angle mort) :
#   * DURÉE COURTE. `SUITE_SOAK_SECONDS` vaut quelques MINUTES par défaut — adapté à un run
#     routinier sur une machine partagée avec d'autres charges de travail (voir la mémoire
#     « shared box load spikes » : un `native-image` voisin peut pousser le load average à 400).
#     Une fuite LENTE (tas, descripteurs de fichiers, croissance RocksDB super-linéaire avec la
#     taille de la base plutôt qu'avec le nombre de blocs) ne se révèle qu'après des HEURES ou
#     des JOURS de fonctionnement CONTINU : hors de portée d'un run de quelques minutes, par
#     construction. Un vrai soak de campagne fait tourner ce même trio (monitor + sim-tx +
#     sim-contract) sur des JOURS, avec rotation de journal et alerte d'opérateur — pas un TSV
#     borné qu'on relit une fois.
#   * UNE SEULE MACHINE. Les nœuds observés ici partagent le disque, le cache page, l'horloge et
#     l'ordonnanceur AVEC l'observateur lui-même : la latence de gossip mesurée est optimiste
#     (loopback), et la contention disque/CPU qu'un déploiement multi-VM produirait entre
#     machines RÉELLEMENT distinctes n'est pas exercée. Même mise en garde que suite-net.sh/
#     suite-pow.sh/suite-bootstrap.sh pour leurs propres angles morts single-box.
#   * Le déterminisme d'exécution inter-nœuds (S9) est déjà couvert par sim-contract.sh lui-même
#     (contrôle périodique, sim/check.log) — cette batterie ne le re-vérifie pas ; elle vérifie
#     que la CHARGE a bien tourné et que ses effets de bord (mempool, disque, santé du nœud)
#     restent sains sur toute la durée, pas seulement au dernier relevé.
#
# Usage : suite-soak.sh [nœud]     (accepté pour la convention d'appel de run-campaign.sh — le
#                                    soak porte sur TOUT le réseau, pas sur un nœud unique ;
#                                    l'argument ne sert qu'au bilan de santé final)
#
# Variables d'environnement :
#   SUITE_SOAK_SECONDS          durée de la fenêtre de charge (défaut 180 s = 3 min — un run
#                                routinier, PAS un vrai soak, voir plus haut)
#   RHIZOME_SIM_WORKERS          workers sim-tx.sh (défaut ICI 4, plus léger que le défaut du
#                                script lui-même — 8 — pour que la dotation initiale ne consomme
#                                pas à elle seule toute une fenêtre courte)
#   SOAK_GROWTH_BOUND_BYTES     borne LARGE sur la croissance RocksDB par bloc, en octets (défaut
#                                5 000 000 = 5 Mo/bloc) — volontairement lâche : une métrique à
#                                suivre dans le temps, pas un seuil réglé sur cette machine
#   SOAK_TX_RATIO_MIN            ratio confirmé/soumis minimal pour sim-tx.sh, en % entier
#                                (défaut 90)
#   SOAK_CONTRACT_RATIO_MIN      idem pour sim-contract.sh (défaut 90)
#   SOAK_MEMPOOL_MAX              mempool cumulé (tous nœuds) jugé « vidé » après l'arrêt de la
#                                charge (défaut 20)
#   SOAK_MEMPOOL_DRAIN_TIMEOUT   fenêtre d'attente du vidage, en secondes (défaut 60)
set -uo pipefail
SUITE_NAME=soak
source "$(dirname "$0")/suite-common.sh"

NODE=${1:-0}
SOAK_SECONDS="${SUITE_SOAK_SECONDS:-180}"
export RHIZOME_SIM_WORKERS="${RHIZOME_SIM_WORKERS:-4}"
GROWTH_BOUND="${SOAK_GROWTH_BOUND_BYTES:-5000000}"
TX_RATIO_MIN="${SOAK_TX_RATIO_MIN:-90}"
CONTRACT_RATIO_MIN="${SOAK_CONTRACT_RATIO_MIN:-90}"
MEMPOOL_MAX="${SOAK_MEMPOOL_MAX:-20}"
MEMPOOL_DRAIN_TIMEOUT="${SOAK_MEMPOOL_DRAIN_TIMEOUT:-60}"

MONITOR_CSV="$BASE_DIR/monitor.csv"
TX_CSV="$BASE_DIR/sim/tx.csv"
CONTRACT_CSV="$BASE_DIR/sim/contract.csv"
mkdir -p "$BASE_DIR/logs"
SCRATCH_DIR="$(mktemp -d "$BASE_DIR/soak-scratch.XXXXXX")"

MONITOR_PID=""
STARTED_TX=0
STARTED_CONTRACT=0

# Nettoyage INCONDITIONNEL, sur EXIT : ce que cette batterie a lancé doit mourir avec elle, même
# sur un échec à mi-parcours (variable non liée, réseau injoignable, ...) — même principe que
# suite-dos.sh (health_loop/refresher_loop) et start.sh lui-même. Ne touche NI aux nœuds du
# réseau (pas les siens) NI aux CSV de monitor.sh/sim-*.sh : ce sont des artefacts PARTAGÉS que
# ces outils possèdent — un opérateur qui lance `sim-tx.sh status` après coup doit encore les
# trouver. Le seul répertoire temporaire que cette batterie possède en propre (SCRATCH_DIR, les
# instantanés avant/après par nœud) est, lui, bien supprimé — c'est la partie « pas de CSV
# temporaire qui traîne » de la consigne.
cleanup() {
  [[ -n "$MONITOR_PID" ]] && kill "$MONITOR_PID" 2>/dev/null
  wait 2>/dev/null
  (( STARTED_TX == 1 )) && "$ROOT/scripts/local-testnet/sim-tx.sh" stop >/dev/null 2>&1
  (( STARTED_CONTRACT == 1 )) && "$ROOT/scripts/local-testnet/sim-contract.sh" stop >/dev/null 2>&1
  rm -rf "$SCRATCH_DIR"
}
trap cleanup EXIT

# Un opérateur peut déjà faire tourner sim-tx.sh/sim-contract.sh à la main (le mode d'usage
# documenté par TEST-PLAN.md) au moment où cette batterie démarre. Les relancer sans condition
# créerait un DEUXIÈME lot de workers dont les fichiers pid écraseraient ceux du premier lot dans
# PID_DIR — le premier lot deviendrait orphelin, invisible à `stop`. On réutilise donc un lot déjà
# vivant tel quel (on ne l'arrête pas non plus au nettoyage : il n'est pas à nous).
sim_tx_alive() {
  local f
  for f in "$PID_DIR"/sim-tx-*.pid; do
    [[ -s "$f" ]] && kill -0 "$(cat "$f")" 2>/dev/null && return 0
  done
  return 1
}
sim_contract_alive() {
  [[ -s "$PID_DIR/sim-contract.pid" ]] && kill -0 "$(cat "$PID_DIR/sim-contract.pid")" 2>/dev/null
}

# Instantané par nœud : taille RocksDB (octets apparents, `du -sbL` sur data_dir), hauteur, et
# prunedBelow/snapshotPivot de `/info` (« si présents » — la plupart des nœuds de campagne n'ont
# ni élagage ni instantané configurés et rendent alors 0). Un FICHIER PAR NŒUD sous SCRATCH_DIR,
# écrit par un sous-processus PARALLÈLE — comme monitor.sh/status.sh : un balayage séquentiel sur
# un grand réseau lirait le nœud N plusieurs secondes après le nœud 0, et la fenêtre « avant »/
# « après » se mettrait à chevaucher tout un bloc de production. Un fichier par nœud (jamais un
# fichier partagé avec écritures concurrentes) : même prudence que monitor.sh/status.sh.
snapshot_nodes() {
  local prefix=$1 i pids=()
  for i in $(seq 0 $((NODES - 1))); do
    (
      # -L : suit un lien symbolique si data_dir en est un (la répétition générale staging, lancée
      # par staging-rehearsal.sh, range ses données sous data/node-N et non node-N — voir
      # TEST-PLAN.md, section soak de la campagne 9 — un lien node-N -> data/node-N comble l'écart
      # sans toucher la convention data_dir() partagée par tout le harnais). Sans -L, `du` sur un
      # lien rapporte la taille du LIEN lui-même (quelques octets), jamais celle du répertoire
      # visé — un faux zéro de croissance qui ne se serait jamais vu comme une erreur.
      size="$(du -sbL "$(data_dir "$i")" 2>/dev/null | cut -f1)"
      h="$(height_of "$i")"
      info="$(get_json "$i" /info)"
      pb="$(json_get "$info" prunedBelow)"
      pv="$(json_get "$info" snapshotPivot)"
      printf '%s\t%s\t%s\t%s\n' "${size:-0}" "${h:-0}" "${pb:-0}" "${pv:-0}" > "$SCRATCH_DIR/$prefix-$i"
    ) &
    pids+=($!)
  done
  for p in "${pids[@]}"; do wait "$p" || true; done
}
snap_field() {  # snap_field <prefix> <nœud> <colonne 1=taille|2=hauteur|3=prunedBelow|4=pivot>
  local f="$SCRATCH_DIR/$1-$2"
  [[ -f "$f" ]] && cut -f"$3" "$f" || printf '0'
}

echo "=== SOAK : monitor.sh + sim-tx.sh + sim-contract.sh, réduits en verdicts (fenêtre ${SOAK_SECONDS}s) ==="

echo "== SOAK-00 : instantané AVANT (taille RocksDB, hauteur, /info) =="
snapshot_nodes base
base_up=0
for i in $(seq 0 $((NODES - 1))); do
  [[ "$(snap_field base "$i" 2)" != "0" ]] && base_up=$((base_up + 1))
done
record SOAK-00-baseline PASS "instantané pris sur $NODES nœuds ($base_up répondants) avant la fenêtre de charge"

echo
echo "== SOAK-00 : lancement de monitor.sh / sim-tx.sh / sim-contract.sh =="

MON_LINES_BEFORE=0
[[ -f "$MONITOR_CSV" ]] && MON_LINES_BEFORE=$(wc -l < "$MONITOR_CSV")
"$ROOT/scripts/local-testnet/monitor.sh" >> "$BASE_DIR/logs/soak-monitor.log" 2>&1 &
MONITOR_PID=$!
sleep 1
if kill -0 "$MONITOR_PID" 2>/dev/null; then
  record SOAK-00-monitor-started PASS "monitor.sh lancé (pid $MONITOR_PID)"
else
  record SOAK-00-monitor-started FAIL "monitor.sh n'a pas survécu à son démarrage (voir $BASE_DIR/logs/soak-monitor.log)"
  MONITOR_PID=""
fi

TX_LINES_BEFORE=0
[[ -f "$TX_CSV" ]] && TX_LINES_BEFORE=$(wc -l < "$TX_CSV")
if sim_tx_alive; then
  record SOAK-00-tx-started PASS "sim-tx.sh déjà en cours — réutilisé tel quel, pas redémarré (évite un doublon de workers)"
else
  if "$ROOT/scripts/local-testnet/sim-tx.sh" start >> "$BASE_DIR/logs/soak-sim-tx.log" 2>&1; then
    STARTED_TX=1
    record SOAK-00-tx-started PASS "sim-tx.sh démarré ($RHIZOME_SIM_WORKERS workers)"
  else
    record SOAK-00-tx-started FAIL "sim-tx.sh n'a pas démarré (voir $BASE_DIR/logs/soak-sim-tx.log)"
  fi
fi

CONTRACT_LINES_BEFORE=0
[[ -f "$CONTRACT_CSV" ]] && CONTRACT_LINES_BEFORE=$(wc -l < "$CONTRACT_CSV")
if sim_contract_alive; then
  record SOAK-00-contract-started PASS "sim-contract.sh déjà en cours — réutilisé tel quel, pas redémarré"
else
  if "$ROOT/scripts/local-testnet/sim-contract.sh" start >> "$BASE_DIR/logs/soak-sim-contract.log" 2>&1; then
    STARTED_CONTRACT=1
    record SOAK-00-contract-started PASS "sim-contract.sh démarré"
  else
    record SOAK-00-contract-started FAIL "sim-contract.sh n'a pas démarré (voir $BASE_DIR/logs/soak-sim-contract.log — mineurs pas encore assez dotés ?)"
  fi
fi

echo
echo "== SOAK-01 : fenêtre de charge — ${SOAK_SECONDS}s =="
heartbeat() { echo "   ... ${1}s restantes"; }
if run_for "$SOAK_SECONDS" heartbeat; then
  record SOAK-01-window PASS "fenêtre de ${SOAK_SECONDS}s écoulée sans interruption"
else
  record SOAK-01-window FAIL "fenêtre interrompue par un signal avant son terme — réduction sur ce qui a été observé jusque-là"
fi

echo
echo "== SOAK-02 : instantané APRÈS, puis arrêt propre de la charge =="
# Pris à la fin de la fenêtre, AVANT d'arrêter quoi que ce soit : la croissance mesurée doit
# couvrir exactement la fenêtre de charge, pas la fenêtre + le temps d'arrêt.
snapshot_nodes after

if (( STARTED_TX == 1 )); then
  "$ROOT/scripts/local-testnet/sim-tx.sh" stop >> "$BASE_DIR/logs/soak-sim-tx.log" 2>&1
  STARTED_TX=0
fi
if (( STARTED_CONTRACT == 1 )); then
  "$ROOT/scripts/local-testnet/sim-contract.sh" stop >> "$BASE_DIR/logs/soak-sim-contract.log" 2>&1
  STARTED_CONTRACT=0
fi
sleep 1   # laisse une éventuelle dernière ligne CSV en vol se terminer avant de compter les lignes
TX_LINES_AFTER=0; [[ -f "$TX_CSV" ]] && TX_LINES_AFTER=$(wc -l < "$TX_CSV")
CONTRACT_LINES_AFTER=0; [[ -f "$CONTRACT_CSV" ]] && CONTRACT_LINES_AFTER=$(wc -l < "$CONTRACT_CSV")

if [[ -n "$MONITOR_PID" ]]; then
  kill "$MONITOR_PID" 2>/dev/null
  for _ in $(seq 1 10); do kill -0 "$MONITOR_PID" 2>/dev/null || break; sleep 0.5; done
  kill -9 "$MONITOR_PID" 2>/dev/null || true
  wait "$MONITOR_PID" 2>/dev/null
  MONITOR_PID=""
fi
sleep 1
MON_LINES_AFTER=0; [[ -f "$MONITOR_CSV" ]] && MON_LINES_AFTER=$(wc -l < "$MONITOR_CSV")
record SOAK-02-stopped PASS "monitor.sh et les simulateurs (ceux démarrés par cette batterie) arrêtés"

echo
echo "== SOAK-03 : réduction — monitor.csv, dégradation sur TOUTE la fenêtre =="
# La ligne 1 de monitor.csv est l'en-tête QUE SI le fichier vient d'être créé par notre propre
# lancement (MON_LINES_BEFORE=0) ; sinon MON_LINES_BEFORE compte déjà l'en-tête et toutes les
# lignes d'un monitor antérieur, et la fenêtre à nous commence juste après.
mon_start=$((MON_LINES_BEFORE + 1)); (( MON_LINES_BEFORE == 0 )) && mon_start=2
DEGRADED_ROWS=0
mon_sampled=0
if [[ -f "$MONITOR_CSV" ]] && (( MON_LINES_AFTER >= mon_start )); then
  mon_sampled=$((MON_LINES_AFTER - mon_start + 1))
  DEGRADED_ROWS="$(tail -n +"$mon_start" "$MONITOR_CSV" | awk -F, 'NF>=10 && $10!="" && $10!="null"' | wc -l | tr -d ' ')"
fi
# C'est précisément ce qu'un contrôle de FIN DE COURSE ne peut pas voir : un épisode dégradé
# transitoire qui s'auto-guérit avant le dernier relevé serait invisible à un `node_healthy`
# ponctuel. Ici on regarde CHAQUE ligne de la fenêtre, pas seulement la dernière.
expect_eq SOAK-03-no-degraded-episode 0 "$DEGRADED_ROWS" \
  "aucune ligne 'degraded' non nulle sur les $mon_sampled lignes de monitor.csv couvrant la fenêtre"

echo
echo "== SOAK-04 : réduction — croissance RocksDB par bloc =="
for i in $(seq 0 $((NODES - 1))); do
  bsize="$(snap_field base "$i" 1)"; bh="$(snap_field base "$i" 2)"
  asize="$(snap_field after "$i" 1)"; ah="$(snap_field after "$i" 2)"
  dh=$((ah - bh))
  if (( dh > 0 )); then
    dbytes=$((asize - bsize))
    growth=$((dbytes / dh))
    record_metric "SOAK-GROWTH-node$i" "$growth" "o/bloc" \
      "hauteur +$dh ($bh→$ah), taille +${dbytes}o (${bsize}o→${asize}o)"
    expect_le "SOAK-GROWTH-BOUND-node$i" "$GROWTH_BOUND" "$growth" \
      "borne large ($GROWTH_BOUND o/bloc) — métrique à SUIVRE dans le temps, pas un seuil réglé pour ce harnais"
  else
    record_metric "SOAK-GROWTH-node$i" "n/d" "" \
      "hauteur inchangée pendant la fenêtre ($bh→$ah) — nœud non-mineur inactif ou fenêtre trop courte, pas mesurable"
  fi
done

echo
echo "== SOAK-05 : réduction — sim/tx.csv (confirmé / soumis) =="
tx_start=$((TX_LINES_BEFORE + 1)); (( TX_LINES_BEFORE == 0 )) && tx_start=2
tx_total=0; tx_success=0
if [[ -f "$TX_CSV" ]] && (( TX_LINES_AFTER >= tx_start )); then
  tx_total="$(tail -n +"$tx_start" "$TX_CSV" | awk -F, 'NF>=5' | wc -l | tr -d ' ')"
  tx_success="$(tail -n +"$tx_start" "$TX_CSV" | awk -F, 'NF>=5 && $5=="SUCCESS"' | wc -l | tr -d ' ')"
fi
record_metric SOAK-TX-SUBMITTED "$tx_total" envois "workers=$RHIZOME_SIM_WORKERS, fenêtre ${SOAK_SECONDS}s"
if (( tx_total > 0 )); then
  tx_pct=$((tx_success * 100 / tx_total))
  expect_ge SOAK-TX-RATIO "$TX_RATIO_MIN" "$tx_pct" \
    "$tx_success/$tx_total confirmés — run local, seuil volontairement lâche (contention possible sur une machine partagée)"
else
  record SOAK-TX-RATIO FAIL "aucun envoi observé sur la fenêtre — sim-tx.sh n'a rien produit (voir $BASE_DIR/logs/soak-sim-tx.log)"
fi

echo
echo "== SOAK-06 : réduction — sim/contract.csv (confirmé / soumis) =="
c_start=$((CONTRACT_LINES_BEFORE + 1)); (( CONTRACT_LINES_BEFORE == 0 )) && c_start=2
c_total=0; c_success=0
if [[ -f "$CONTRACT_CSV" ]] && (( CONTRACT_LINES_AFTER >= c_start )); then
  c_total="$(tail -n +"$c_start" "$CONTRACT_CSV" | awk -F, 'NF>=4' | wc -l | tr -d ' ')"
  c_success="$(tail -n +"$c_start" "$CONTRACT_CSV" | awk -F, 'NF>=4 && $4=="SUCCESS"' | wc -l | tr -d ' ')"
fi
record_metric SOAK-CONTRACT-SUBMITTED "$c_total" appels "intervalle ${RHIZOME_SIM_CONTRACT_INTERVAL:-5}s, fenêtre ${SOAK_SECONDS}s"
if (( c_total > 0 )); then
  c_pct=$((c_success * 100 / c_total))
  expect_ge SOAK-CONTRACT-RATIO "$CONTRACT_RATIO_MIN" "$c_pct" "$c_success/$c_total confirmés"
else
  record SOAK-CONTRACT-RATIO FAIL "aucun appel observé sur la fenêtre — sim-contract.sh n'a rien produit (voir $BASE_DIR/logs/soak-sim-contract.log)"
fi

echo
echo "== SOAK-07 : le mempool se vide après l'arrêt de la charge =="
drain_deadline=$((SECONDS + MEMPOOL_DRAIN_TIMEOUT))
last_total=-1
while (( SECONDS < drain_deadline )); do
  total=0
  for i in $(seq 0 $((NODES - 1))); do
    m="$(json_get "$(node_stats "$i")" mempool)"
    total=$((total + ${m:-0}))
  done
  last_total=$total
  (( total <= MEMPOOL_MAX )) && break
  sleep 3
done
expect_le SOAK-MEMPOOL-DRAIN "$MEMPOOL_MAX" "$last_total" \
  "mempool cumulé (tous nœuds) dans les ${MEMPOOL_DRAIN_TIMEOUT}s suivant l'arrêt des simulateurs — pas de fuite évidente"

echo
echo "== santé finale =="
expect_eq SOAK-FINAL-healthy "degraded=null reorg=false" "$(node_healthy "$NODE")" \
  "état du nœud $NODE après la fenêtre de charge"

suite_summary
