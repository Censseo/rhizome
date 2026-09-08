#!/usr/bin/env bash
# Batterie « reorg trop profonde » — la SEULE façon d'atteindre RÉELLEMENT REORG_TOO_DEEP en
# réseau : deux camps qui minent chacun PLUS de maxReorgDepth (120 blocs, profiles/devnet.env)
# au-delà de leur point de fork COMMUN, avant de tenter de se rebrancher.
#
# Aucune campagne précédente n'y est jamais arrivée. TEST-PLAN.md (S7) documente une partition
# volontairement PEU PROFONDE — « à ~2,5 s/bloc, la fenêtre de finalité (120 blocs ≈ 5 min)
# interdit les partitions > ~4 min » — et l'archive de la campagne 1 (« Défaut 5 ») rapporte un
# vrai passage au-delà de l'horizon, mais AVANT le correctif « REORG_TOO_DEEP sans ban » : chaque
# sync croisée finissait alors en ban mutuel renouvelé à l'heure exacte. Ce que personne n'a
# encore exercé en réseau, et que cette batterie prouve : une fois ce correctif en place, deux
# camps au-delà de l'horizon restent scindés PROPREMENT — aucun ban, aucun `degraded`, chacun
# continue de miner sa propre branche — et un partitionnement nettement plus court (contrôle
# négatif) guérit toujours normalement, ce qui isole la RÈGLE DE PROFONDEUR comme seule cause de
# la non-guérison du cas profond (et non un défaut de plomberie quelconque).
#
# Ancrage : docs/adversarial/spec.md, famille REORG — REORG-02 « rewrite history deeper than the
# finality window » (BOUNDED) et REORG-03 « leave the victim … unable to keep mining » (DEFENDED).
# Preuves JUnit déjà au catalogue : HardeningTest#reorgDeeperThanFinalityWindowIsRefused,
# ReorgAttackTest (les deux bornes exactes de la fenêtre), RhizomeNodeTest#aDeepForkedPeerIsRefusedButNeverBanned.
# Cette batterie les corrobore sur de vrais processus, de vraies sockets, du vrai PoW — pas un
# double de test — exactement le rôle que docs/adversarial/spec.md assigne à ce harnais.
#
# --- Limites d'une seule machine (à documenter, pas à cacher) --------------------------------
# La partition est le même mécanisme que S7/S15 : `start.sh -p <lo>-<hi>` restreint l'ensemencement
# PEX à une moitié de l'anneau, RIEN d'autre — aucun pare-feu n'intervient sur une seule machine ;
# PEX ne traverse pas la coupure simplement parce qu'il n'a jamais reçu l'adresse de l'autre camp.
# Une VRAIE campagne multi-VM ajouterait : une coupure réseau EFFECTIVE (pas seulement l'absence
# de seed), des chemins de latence/bande passante asymétriques entre camps, et surtout une cadence
# de production RÉELLE (5 s sur devnet) plutôt que la cadence accélérée ci-dessous.
#
# --- Cadence accélérée, UNIQUEMENT pour cette batterie ----------------------------------------
# Chaque camp ne porte qu'UN SEUL mineur (répartition ci-dessous) : sans concurrence intra-camp,
# la cadence suit directement RHIZOME_TESTNET_BLOCK_MS. 3 s reste dans la bande morte du retarget
# ([desired/2, 2*desired] = [2.5 s, 10 s] à desired=5 s — cf. suite-pow.sh, où la même cadence sur
# un mineur solo laisse la difficulté au plancher) : la difficulté reste au plancher tout du long,
# donc le seul levier de durée est ce délai — PAS un raccourci sur la RÈGLE de profondeur elle-même
# (`maxReorgDepth` reste 120, non paramétrable par variable d'environnement). Ce que cette batterie
# prouve porte sur la LOGIQUE de profondeur, PAS sur le temps réel qu'un vrai réseau à 5 s mettrait
# à s'y heurter (≈ 10-11 min par camp à cadence nominale sans concurrence).
#
# Campagne dédiée et ÉPHÉMÈRE : répertoire de données et plage de ports PROPRES à cette batterie
# (voir RHIZOME_TESTNET_DIR/RHIZOME_TESTNET_BASE_PORT ci-dessous), pour ne jamais entrer en
# collision avec une campagne par défaut déjà en cours sur la même machine — cette partition dure
# largement plus longtemps que n'importe quel autre scénario du harnais. Comme les autres batteries
# qui lancent leurs propres nœuds auxiliaires (suite-pow.sh, suite-bootstrap.sh), elle laisse ses
# nœuds tourner à la fin pour inspection ; le message final rappelle comment les arrêter.
#
# Usage : suite-deep-reorg.sh
#   RHIZOME_TESTNET_NODES / RHIZOME_TESTNET_MINERS (défaut 4/2, pair, répartis en deux moitiés
#   égales) et RHIZOME_TESTNET_BLOCK_MS (défaut 3000) surchargent la forme par défaut.
set -uo pipefail

# Espace de noms dédié — calculé AVANT de sourcer common.sh (qui lit ces variables au chargement),
# avec le même calcul de racine que common.sh lui-même (scripts/local-testnet/../..).
_SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_ROOT_GUESS="$(cd "$_SELF_DIR/../.." && pwd)"
export RHIZOME_TESTNET_DIR="${RHIZOME_TESTNET_DIR:-$_ROOT_GUESS/.testnet-deep-reorg}"
export RHIZOME_TESTNET_BASE_PORT="${RHIZOME_TESTNET_BASE_PORT:-4700}"
export RHIZOME_TESTNET_NODES="${RHIZOME_TESTNET_NODES:-4}"
export RHIZOME_TESTNET_MINERS="${RHIZOME_TESTNET_MINERS:-2}"
export RHIZOME_TESTNET_BLOCK_MS="${RHIZOME_TESTNET_BLOCK_MS:-3000}"

SUITE_NAME=deep-reorg
source "$(dirname "${BASH_SOURCE[0]}")/suite-common.sh"

mkdir -p "$BASE_DIR/logs" "$BASE_DIR/tmp"
STARTSH="$ROOT/scripts/local-testnet/start.sh"
STOPSH="$ROOT/scripts/local-testnet/stop.sh"

if (( NODES % 2 != 0 || NODES < 2 )); then
  echo "ERREUR: RHIZOME_TESTNET_NODES=$NODES doit être pair et >= 2 (partition en deux moitiés)" >&2
  exit 2
fi
LO=0
MID=$((NODES / 2))
HI=$((NODES - 1))

# Un représentant mineur par camp — le premier mineur de chaque moitié. Générique : fonctionne
# quels que soient RHIZOME_TESTNET_NODES/_MINERS tant que la répartition régulière de common.sh
# (indices k·N/M) pose au moins un mineur de chaque côté, ce qu'un NODES/MINERS pair garantit.
CAMP_A_REP=""; CAMP_B_REP=""
for m in "${MINERS[@]}"; do
  [[ -z "$CAMP_A_REP" && "$m" -lt "$MID" ]] && CAMP_A_REP=$m
  [[ -z "$CAMP_B_REP" && "$m" -ge "$MID" ]] && CAMP_B_REP=$m
done
if [[ -z "$CAMP_A_REP" || -z "$CAMP_B_REP" ]]; then
  echo "ERREUR: la répartition des mineurs (${MINERS[*]}) ne couvre pas les deux moitiés [0,$((MID-1))]/[$MID,$HI]" >&2
  exit 2
fi

MAX_REORG_DEPTH=$(profile_get MAX_REORG_DEPTH)
SHALLOW_DEPTH=15                                   # bien en-deça de l'horizon — contrôle négatif
DEEP_DEPTH=$((MAX_REORG_DEPTH + 8))                 # au-delà, marge de 8 blocs contre la gigue
SHALLOW_TIMEOUT=$(( SHALLOW_DEPTH * BLOCK_MS / 1000 * 4 + 60 ))
DEEP_TIMEOUT=$(( DEEP_DEPTH * BLOCK_MS / 1000 * 4 + 120 ))   # marge ×4 : box partagée, cf. mémoire

echo "=== REORG-DEEP : $NODES nœuds (mineurs ${MINERS[*]}), camps [$LO,$((MID-1))]/[$MID,$HI], cadence ${BLOCK_MS}ms ==="
echo "    horizon maxReorgDepth=$MAX_REORG_DEPTH — cible profonde $DEEP_DEPTH blocs (délai max ${DEEP_TIMEOUT}s/camp)"
echo "    répertoire dédié: $BASE_DIR — ports $BASE_PORT..$((BASE_PORT + NODES - 1))"

# --- petits outils locaux à cette batterie ----------------------------------------------------

all_same_tip() {
  local ref i tip
  ref="$(json_get "$(node_stats 0)" tipHash)"
  [[ -z "$ref" ]] && return 1
  for i in $(seq 1 "$HI"); do
    tip="$(json_get "$(node_stats "$i")" tipHash)"
    [[ "$tip" == "$ref" ]] || return 1
  done
  return 0
}
wait_all_same_tip() {
  local timeout=${1:-90}
  local deadline=$((SECONDS + timeout))
  while (( SECONDS < deadline )); do
    all_same_tip && return 0
    sleep 3
  done
  return 1
}

# Aucun pair hors-camp : ni la moitié basse ne connaît un port de la moitié haute, ni l'inverse
# (le critère « aucun pair hors-camp » de S7, vérifié ici par le PORT, comme suite-net.sh NET-06).
no_cross_camp_peers() {
  local i j peers port
  for i in $(seq "$LO" "$((MID - 1))"); do
    peers="$(get_json "$i" /peers)"
    for j in $(seq "$MID" "$HI"); do
      port="$(node_port "$j")"
      [[ "$peers" == *":$port"* ]] && { echo "nœud $i connaît le port $port (camp haut)"; return 1; }
    done
  done
  for i in $(seq "$MID" "$HI"); do
    peers="$(get_json "$i" /peers)"
    for j in $(seq "$LO" "$((MID - 1))"); do
      port="$(node_port "$j")"
      [[ "$peers" == *":$port"* ]] && { echo "nœud $i connaît le port $port (camp bas)"; return 1; }
    done
  done
  return 0
}

# Pont croisé entre les deux camps : chaque nœud d'un côté est présenté à chaque nœud de l'autre
# (camps à 2 nœuds ici, donc 4 paires — trivial). Le PEX fait le reste, comme bootstrap_pex.
heal_partition() {
  local i j
  for i in $(seq "$LO" "$((MID - 1))"); do
    for j in $(seq "$MID" "$HI"); do
      add_peer "$i" "$j"
      add_peer "$j" "$i"
    done
  done
}

# Attend que `node` dépasse `fork` de `target` blocs, ou le délai. Écrit la profondeur atteinte
# (utile même en échec — le contrôle « à combien on s'est arrêté ») dans `outfile`. Lancée en
# arrière-plan pour les deux camps EN PARALLÈLE (sinon la batterie durerait deux fois plus).
wait_camp_depth() {
  local node=$1 fork=$2 target=$3 timeout=$4 label=$5 outfile=$6
  local deadline=$((SECONDS + timeout)) h depth tick=0
  while (( SECONDS < deadline )); do
    h="$(height_of "$node")"
    if [[ -n "$h" ]]; then
      depth=$((h - fork))
      (( tick % 6 == 0 )) && echo "  camp $label (nœud $node): profondeur $depth/$target (h=$h)" >&2
      tick=$((tick + 1))
      if (( depth >= target )); then printf '%d' "$depth" > "$outfile"; return 0; fi
    fi
    sleep 5
  done
  h="$(height_of "$node")"
  printf '%d' "$(( ${h:-fork} - fork ))" > "$outfile"
  return 1
}

# Réplique l'idiome `launch()` de start.sh pour un redémarrage à environnement personnalisé (S6
# étendu) — nécessaire ici parce que start.sh ne permet pas d'injecter RHIZOME_SYNC/RHIZOME_PEERS
# arbitraires sur un seul nœud.
launch_custom() {
  local idx=$1; shift
  local bin_args=() env_vars=(RHIZOME_NETWORK="$NETWORK" RHIZOME_PORT="$(node_port "$idx")"
    RHIZOME_DATA="$(data_dir "$idx")" RHIZOME_ALLOW_PRIVATE_PEERS=true "$@")
  if [[ "$NATIVE" == "1" ]]; then bin_args+=("-Xmx$NODE_HEAP"); else env_vars+=(APP_NODE_OPTS="-Xmx$NODE_HEAP"); fi
  setsid bash -c 'echo $$ > "$1"; shift; exec env "$@"' _ \
    "$(pid_file "$idx")" "${env_vars[@]}" "$NODE_BIN" "${bin_args[@]}" \
    >> "$(log_file "$idx")" 2>&1 &
  for _ in $(seq 1 20); do [[ -s "$(pid_file "$idx")" ]] && break; sleep 0.1; done
}

# === phase 1 : campagne unifiée =================================================================
echo; echo "== phase 1 : campagne unifiée =="
if ! "$STARTSH" > "$BASE_DIR/logs/start-unified.log" 2>&1; then
  record REORG-DEEP-00-campaign-up FAIL "start.sh a échoué (voir $BASE_DIR/logs/start-unified.log)"
  suite_summary; exit 1
fi
record REORG-DEEP-00-campaign-up PASS "$NODES nœuds up, mineurs ${MINERS[*]}"

wait_blocks "$CAMP_A_REP" 2 120 || record REORG-DEEP-00-mining FAIL "aucune production après 2 min"
if wait_all_same_tip 90; then
  FORK1="$(height_of 0)"
  record REORG-DEEP-00-fork-recorded PASS "point de fork commun h=$FORK1 (tip unique sur $NODES nœuds)"
else
  record REORG-DEEP-00-fork-recorded FAIL "les $NODES nœuds n'ont jamais partagé un seul tip — batterie interrompue"
  suite_summary; exit 1
fi

# === phase 2 : première partition + contrôle négatif (peu profonde) ============================
echo; echo "== phase 2 : partition peu profonde — CONTRÔLE NÉGATIF (doit guérir) =="
"$STOPSH" > "$BASE_DIR/logs/stop-1.log" 2>&1
"$STARTSH" -p "$MID-$HI" >> "$BASE_DIR/logs/start-p1.log" 2>&1
"$STARTSH" -p "$LO-$((MID - 1))" >> "$BASE_DIR/logs/start-p1.log" 2>&1

if no_cross_camp_peers; then
  record REORG-DEEP-00-partitioned PASS "aucun pair hors-camp après partition (camps [$LO,$((MID-1))]/[$MID,$HI])"
else
  record REORG-DEEP-00-partitioned FAIL "un nœud connaît un pair hors-camp — la partition a fui"
fi

depthfile_a="$BASE_DIR/tmp/depth-a-shallow"; depthfile_b="$BASE_DIR/tmp/depth-b-shallow"
wait_camp_depth "$CAMP_A_REP" "$FORK1" "$SHALLOW_DEPTH" "$SHALLOW_TIMEOUT" A "$depthfile_a" & pid_a=$!
wait_camp_depth "$CAMP_B_REP" "$FORK1" "$SHALLOW_DEPTH" "$SHALLOW_TIMEOUT" B "$depthfile_b" & pid_b=$!
wait "$pid_a"; rc_a=$?
wait "$pid_b"; rc_b=$?
depth_a="$(cat "$depthfile_a" 2>/dev/null || echo 0)"; depth_b="$(cat "$depthfile_b" 2>/dev/null || echo 0)"
(( rc_a == 0 )) \
  && record REORG-DEEP-NEG-01-camp-a-mined PASS "camp A: profondeur $depth_a/$SHALLOW_DEPTH atteinte" \
  || record REORG-DEEP-NEG-01-camp-a-mined FAIL "camp A: profondeur $depth_a/$SHALLOW_DEPTH — cible non atteinte dans le délai"
(( rc_b == 0 )) \
  && record REORG-DEEP-NEG-01-camp-b-mined PASS "camp B: profondeur $depth_b/$SHALLOW_DEPTH atteinte" \
  || record REORG-DEEP-NEG-01-camp-b-mined FAIL "camp B: profondeur $depth_b/$SHALLOW_DEPTH — cible non atteinte dans le délai"

heal_partition
if wait_all_same_tip 150; then
  RECONVERGED_H="$(height_of 0)"
  record REORG-DEEP-NEG-02-healed PASS "reconvergence sur un tip unique à h=$RECONVERGED_H (profondeurs $depth_a/$depth_b << horizon $MAX_REORG_DEPTH)"
else
  record REORG-DEEP-NEG-02-healed FAIL "pas de tip unique 150 s après le pont — la guérison de base est cassée, le cas 4 (profond) ne prouverait alors rien"
  suite_summary; exit 1
fi
expect_eq REORG-DEEP-NEG-03-healthy-a "degraded=null reorg=false" "$(node_healthy "$CAMP_A_REP")" "camp A après guérison"
expect_eq REORG-DEEP-NEG-03-healthy-b "degraded=null reorg=false" "$(node_healthy "$CAMP_B_REP")" "camp B après guérison"

# Le second point de fork DOIT être mesuré ICI, pendant que le réseau est encore unifié — pas
# après le second `start.sh -p` : dès que la moitié haute redémarre (avant même que la moitié
# basse ne soit lancée), son mineur reprend SEUL et prend une avance d'un ou deux blocs, donc les
# tips divergent immédiatement une fois repartitionnés et un `wait_all_same_tip` posé APRÈS ne
# retrouverait jamais d'égalité (c'est le bug qu'un premier passage de cette batterie a révélé :
# `REORG-DEEP-02-fork-recorded` échouait alors systématiquement). `RECONVERGED_H` reste un
# ancêtre commun valide des deux futures branches quoi qu'il arrive après cette mesure, puisque
# le réseau était encore un SEUL nœud logique (tip unique) au moment où on l'a lu.
FORK2="$RECONVERGED_H"
record REORG-DEEP-02-fork-recorded PASS "second point de fork h=$FORK2 (mesuré unifié, juste avant le second arrêt/repartition)"

# === phase 3 : seconde partition + cas positif (profonde) ======================================
echo; echo "== phase 3 : partition profonde — le cas qu'aucune campagne n'a atteint =="
"$STOPSH" > "$BASE_DIR/logs/stop-2.log" 2>&1
"$STARTSH" -p "$MID-$HI" >> "$BASE_DIR/logs/start-p2.log" 2>&1
"$STARTSH" -p "$LO-$((MID - 1))" >> "$BASE_DIR/logs/start-p2.log" 2>&1

echo "  mine en parallèle jusqu'à profondeur > $MAX_REORG_DEPTH sur chaque camp (délai max ${DEEP_TIMEOUT}s) ..."
depthfile_a="$BASE_DIR/tmp/depth-a-deep"; depthfile_b="$BASE_DIR/tmp/depth-b-deep"
wait_camp_depth "$CAMP_A_REP" "$FORK2" "$DEEP_DEPTH" "$DEEP_TIMEOUT" A "$depthfile_a" & pid_a=$!
wait_camp_depth "$CAMP_B_REP" "$FORK2" "$DEEP_DEPTH" "$DEEP_TIMEOUT" B "$depthfile_b" & pid_b=$!
wait "$pid_a"; rc_a=$?
wait "$pid_b"; rc_b=$?
depth_a="$(cat "$depthfile_a" 2>/dev/null || echo 0)"; depth_b="$(cat "$depthfile_b" 2>/dev/null || echo 0)"
record_metric REORG-DEEP-03-camp-a-depth "$depth_a" blocs "cible $DEEP_DEPTH, horizon $MAX_REORG_DEPTH, fork h=$FORK2"
record_metric REORG-DEEP-03-camp-b-depth "$depth_b" blocs "cible $DEEP_DEPTH, horizon $MAX_REORG_DEPTH, fork h=$FORK2"
expect_ge REORG-DEEP-03-camp-a-exceeds-horizon $((MAX_REORG_DEPTH + 1)) "$depth_a" "camp A au-delà de l'horizon avant tentative de guérison"
expect_ge REORG-DEEP-03-camp-b-exceeds-horizon $((MAX_REORG_DEPTH + 1)) "$depth_b" "camp B au-delà de l'horizon avant tentative de guérison"

if (( rc_a != 0 || rc_b != 0 )); then
  echo "  AVERTISSEMENT: au moins un camp n'a pas atteint la cible dans le délai — les cas ci-dessous restent valides tant que expect_ge a passé (profondeur > horizon), mais avec moins de marge que prévu." >&2
fi

echo "  tentative de guérison (pont croisé) — DOIT échouer : c'est le résultat CORRECT (finalité tenue)"
tip_a_before="$(json_get "$(node_stats "$CAMP_A_REP")" tipHash)"
tip_b_before="$(json_get "$(node_stats "$CAMP_B_REP")" tipHash)"
h_a_before="$(height_of "$CAMP_A_REP")"; h_b_before="$(height_of "$CAMP_B_REP")"
heal_partition
record REORG-DEEP-04-heal-attempted PASS "pont croisé posé entre les deux camps (comme S7), à profondeur $depth_a/$depth_b"

hb() { echo "  ... reste ${1}s (surveillance de la non-reconvergence)"; }
run_for 120 hb

tip_a_after="$(json_get "$(node_stats "$CAMP_A_REP")" tipHash)"
tip_b_after="$(json_get "$(node_stats "$CAMP_B_REP")" tipHash)"
h_a_after="$(height_of "$CAMP_A_REP")"; h_b_after="$(height_of "$CAMP_B_REP")"
if [[ -n "$tip_a_after" && -n "$tip_b_after" && "$tip_a_after" != "$tip_b_after" ]]; then
  record REORG-DEEP-04-no-reconvergence PASS \
    "les deux camps restent scindés 120 s après le pont (A=${tip_a_after:0:12} ≠ B=${tip_b_after:0:12}) — RÉSULTAT CORRECT : la finalité tient au-delà de l'horizon"
else
  record REORG-DEEP-04-no-reconvergence FAIL \
    "les camps ont reconvergé malgré une profondeur > $MAX_REORG_DEPTH (A=${tip_a_after:-<vide>} B=${tip_b_after:-<vide>}) — REORG_TOO_DEEP n'a pas tenu"
fi
# Chaque camp doit aussi ne pas avoir REGRESSÉ (aucun pop partiel avorté puis restauration ratée) :
# sa propre hauteur ne doit jamais avoir reculé pendant la fenêtre de surveillance — un rejet
# REORG_TOO_DEEP est censé laisser la branche locale strictement intacte (REORG-03).
expect_ge REORG-DEEP-04-camp-a-not-rewound "$h_a_before" "$h_a_after" "camp A: hauteur $h_a_before → $h_a_after pendant la tentative de guérison"
expect_ge REORG-DEEP-04-camp-b-not-rewound "$h_b_before" "$h_b_after" "camp B: hauteur $h_b_before → $h_b_after pendant la tentative de guérison"

# REORG_TOO_DEEP est documenté comme NE PORTANT AUCUN score de ban (SyncDriver, fix « campagne 1 »
# n°5) : la preuve réseau de ce point précis n'existait pas non plus avant cette batterie.
expect_eq REORG-DEEP-04-no-bans-camp-a 0 "$(json_get "$(node_stats "$CAMP_A_REP")" syncPeersBanned)" \
  "un rejet REORG_TOO_DEEP répété ne doit accumuler AUCUN ban"
expect_eq REORG-DEEP-04-no-bans-camp-b 0 "$(json_get "$(node_stats "$CAMP_B_REP")" syncPeersBanned)" \
  "idem, côté camp B"
expect_eq REORG-DEEP-04-not-degraded-a "degraded=null reorg=false" "$(node_healthy "$CAMP_A_REP")" "camp A intact après le refus"
expect_eq REORG-DEEP-04-not-degraded-b "degraded=null reorg=false" "$(node_healthy "$CAMP_B_REP")" "camp B intact après le refus"

# REORG-03 en réseau : un reorg refusé ne doit pas coûter la capacité de continuer à miner SA
# PROPRE branche — chaque camp doit encore avancer après le refus.
h_a_before="$(height_of "$CAMP_A_REP")"; h_b_before="$(height_of "$CAMP_B_REP")"
wait_blocks "$CAMP_A_REP" 1 $((BLOCK_MS / 1000 * 6 + 30)) \
  && record REORG-DEEP-04-camp-a-still-mining PASS "camp A a produit après le refus ($h_a_before → $(height_of "$CAMP_A_REP"))" \
  || record REORG-DEEP-04-camp-a-still-mining FAIL "camp A ne produit plus après le refus"
wait_blocks "$CAMP_B_REP" 1 $((BLOCK_MS / 1000 * 6 + 30)) \
  && record REORG-DEEP-04-camp-b-still-mining PASS "camp B a produit après le refus ($h_b_before → $(height_of "$CAMP_B_REP"))" \
  || record REORG-DEEP-04-camp-b-still-mining FAIL "camp B ne produit plus après le refus"

# === phase 4 (best-effort) : récupération d'un nœud bloqué au-delà de l'horizon ================
echo; echo "== phase 4 (best-effort) : procédure de récupération documentée =="
# Choix arbitraire : le nœud OBSERVATEUR (jamais mineur) du camp A est désigné « bloqué » et
# reconstruit en pointant sur le camp B, désigné « gagnant » — les deux camps sont en réalité
# également valides (finalité tenue des DEUX côtés), le choix ne sert qu'à donner un sens à la
# procédure. Un observateur est choisi pour ne pas avoir à réinjecter RHIZOME_MINER/BLOCK_INTERVAL_MS.
#
# OBSERVÉ EN CAMPAGNE (à ne pas prendre pour un bug de cette batterie) : le pont croisé de la
# phase 3 (`heal_partition`, ci-dessus) a déjà introduit chaque nœud du camp opposé au registre
# PEX de `WINNING_PEER`, MÊME si la reorg elle-même a été refusée. Le nœud reconstruit ici hérite
# donc de CETTE connaissance via PEX dès son premier round et peut se synchroniser avec le camp A
# (perdant nominal) s'il répond en premier — puis refuse à son tour de basculer vers le camp B
# pour la MÊME raison de profondeur. C'est une preuve incidente supplémentaire de la même règle,
# pas la démonstration « resynchronisation propre vers le camp gagnant désigné » visée : un suivi
# dédié isolerait le pair de récupération (nouveau nœud sans pont préalable) pour lever la course.
#
# LIMITE ASSUMÉE : aucun nœud de cette mini-campagne ne matérialise d'instantané
# (RHIZOME_SNAPSHOT_EVERY absent partout, cf. en-tête). RHIZOME_SYNC=snap retombe donc
# silencieusement sur la resynchronisation complète (SnapshotBootstrap le documente : « falls
# back to full sync when no peer offers a usable snapshot ») — ce qui reste un chemin de
# récupération RÉEL et pertinent ici (un nœud VIDE n'a pas de fenêtre de reorg à respecter, sa
# resynchronisation initiale n'est pas bornée par maxReorgDepth). Vérifier l'ADOPTION effective
# d'un instantané par un nœud bloqué demanderait un pair du camp gagnant configuré en fournisseur
# dès le début de la campagne (cf. suite-bootstrap.sh) — laissé en suivi dédié, comme suggéré.
# Cette étape est explicitement du meilleur-effort (voir la tâche) : elle est enregistrée en
# MESURE (record_metric), jamais en PASS/FAIL — un aléa ici (délai de resynchronisation sur une
# machine chargée, par exemple) ne doit pas faire échouer une batterie dont l'objet réel est la
# RÈGLE DE PROFONDEUR ci-dessus, déjà pleinement statuée aux phases 2/3.
if (( MID - LO < 2 )); then
  record_metric REORG-DEEP-05-recovery skipped "" "camp A n'a pas de nœud observateur distinct du mineur (NODES/MINERS trop petits pour cette étape)"
else
  RECOVERY_TARGET=$((MID - 1))
  WINNING_PEER=$CAMP_B_REP
  "$STOPSH" -n "$RECOVERY_TARGET" > "$BASE_DIR/logs/stop-recovery.log" 2>&1
  rm -rf "$(data_dir "$RECOVERY_TARGET")"
  launch_custom "$RECOVERY_TARGET" RHIZOME_SYNC=snap RHIZOME_PEERS="$(node_seed_url "$WINNING_PEER")"

  up_deadline=$((SECONDS + 90)); up=0
  while (( SECONDS < up_deadline )); do [[ -n "$(node_stats "$RECOVERY_TARGET")" ]] && { up=1; break; }; sleep 2; done
  record_metric REORG-DEEP-05-recovery-node-up "$([[ $up == 1 ]] && echo up || echo down)" "" \
    "nœud $RECOVERY_TARGET reconstruit (données purgées) puis relancé avec RHIZOME_SYNC=snap vers le camp gagnant"

  if (( up == 1 )); then
    recon_deadline=$((SECONDS + 300)); same=non
    while (( SECONDS < recon_deadline )); do
      a="$(json_get "$(node_stats "$RECOVERY_TARGET")" tipHash)"
      b="$(json_get "$(node_stats "$WINNING_PEER")" tipHash)"
      [[ -n "$a" && "$a" == "$b" ]] && { same=oui; break; }
      sleep 5
    done
    record_metric REORG-DEEP-05-recovery-synced "$same" "" \
      "h=$(height_of "$RECOVERY_TARGET") vs camp gagnant h=$(height_of "$WINNING_PEER")"
    record_metric REORG-DEEP-05-recovery-pruned-below "$(json_get "$(node_stats "$RECOVERY_TARGET")" prunedBelow)" "" \
      "attendu 0/vide (repli sur resynchronisation complète, aucun fournisseur d'instantané dans cette mini-campagne — voir limite ci-dessus)"
  else
    record_metric REORG-DEEP-05-recovery-synced "non-évalué" "" "le nœud n'est jamais remonté"
  fi
fi

suite_summary
rc=$?
echo
echo "campagne dédiée laissée EN VIE pour inspection (comme suite-pow.sh/suite-bootstrap.sh) :"
echo "  status.sh : RHIZOME_TESTNET_DIR=$BASE_DIR RHIZOME_TESTNET_BASE_PORT=$BASE_PORT RHIZOME_TESTNET_NODES=$NODES $ROOT/scripts/local-testnet/status.sh"
echo "  arrêt     : RHIZOME_TESTNET_DIR=$BASE_DIR RHIZOME_TESTNET_BASE_PORT=$BASE_PORT RHIZOME_TESTNET_NODES=$NODES $ROOT/scripts/local-testnet/stop.sh"
echo "  purge     : rm -rf $BASE_DIR"
exit $rc
