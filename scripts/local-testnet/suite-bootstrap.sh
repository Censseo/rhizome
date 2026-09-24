#!/usr/bin/env bash
# Batterie BOOTSTRAP — comment un opérateur TIERS rejoint la chaîne : snap-sync et élagage.
#
# Trou n° 3 de la campagne 7 : `RHIZOME_SYNC=snap`, `RHIZOME_PRUNE` et `RHIZOME_SNAPSHOT_EVERY`
# existent dans NodeConfig et sont prouvés en JUnit, mais aucun nœud ne les avait jamais empruntés
# en réseau. C'est pourtant le PREMIER chemin qu'un nouvel opérateur suit — et le plus visible
# s'il casse. Les trois variables sont documentées dans docs/*/spec.md mais absentes du tableau
# d'environnement du README (constaté campagne 8) : la batterie les exerce telles qu'un opérateur
# les trouverait.
#
# Deux fournisseurs distincts, jamais reliés (deux histoires devnet incompatibles) :
#   * le nœud source isolé (4406), qui matérialise un instantané tous les 200 blocs, sert le
#     snap-sync ;
#   * le réseau de campagne (4400...), assez long pour qu'un nœud élagué ait quelque chose à jeter,
#     sert l'élagage.
SUITE_NAME=bootstrap
source "$(dirname "${BASH_SOURCE[0]}")/suite-common.sh"

VICTIM=${1:-0}

PROVIDER=http://127.0.0.1:4406            # source isolée, RHIZOME_SNAPSHOT_EVERY=200
PROVIDER_SEED=http://localhost:4406
SNAP_PORT=4411; SNAP=http://127.0.0.1:$SNAP_PORT; SNAP_DATA="$BASE_DIR/snap"
PRUNE_PORT=4412; PRUNE=http://127.0.0.1:$PRUNE_PORT; PRUNE_DATA="$BASE_DIR/prune"
BAD_PORT=4413; BAD_DATA="$BASE_DIR/prunebad"
# Lues depuis profiles/$NETWORK.env (common.sh) — voir suite-pow.sh pour la raison de ne pas
# les lire en direct sur le nœud ou recalculer depuis NetworkParameters.
MAX_REORG_DEPTH=$(profile_get MAX_REORG_DEPTH)
PRUNE_FLOOR=$(profile_get PRUNE_FLOOR)

api() { curl -sf --max-time 8 "$1$2" 2>/dev/null; }
# Code HTTP seul : c'est le verdict pour /sync (410 GONE sous le filigrane d'élagage).
code_of() { curl -s -o /dev/null -w '%{http_code}' --max-time 8 "$1$2" 2>/dev/null; }
body_of() { curl -s --max-time 8 "$1$2" 2>/dev/null; }
height_at() { json_get "$(api "$1" /stats)" height; }
root_at() { json_get "$(api "$1" /stats)" stateRoot; }
tip_at() { json_get "$(api "$1" /stats)" tipHash; }

launch() {   # launch <port> <datadir> <logname> <env...>
  local port=$1 data=$2 log=$3; shift 3
  setsid env RHIZOME_NETWORK="$NETWORK" RHIZOME_PORT="$port" RHIZOME_DATA="$data" \
    RHIZOME_ALLOW_PRIVATE_PEERS=true "$@" \
    "$NODE_BIN" -Xmx256m > "$BASE_DIR/logs/$log.log" 2>&1 < /dev/null &
}
kill_node() {   # kill_node <datadir>
  local pid; pid="$(ps -eo pid,args | grep "[r]hizome-node" | awk '{print $1}' \
    | while read -r p; do tr '\0' '\n' < "/proc/$p/environ" 2>/dev/null \
    | grep -q "RHIZOME_DATA=$1" && echo "$p"; done)"
  [[ -n "$pid" ]] && kill $pid 2>/dev/null
  sleep 2
}
wait_up() {   # wait_up <url> [timeout]
  local deadline=$((SECONDS + ${2:-90}))
  while (( SECONDS < deadline )); do [[ -n "$(height_at "$1")" ]] && return 0; sleep 1; done
  return 1
}
wait_height() {  # wait_height <url> <cible> [timeout]
  local deadline=$((SECONDS + ${3:-180})) h
  while (( SECONDS < deadline )); do
    h="$(height_at "$1")"; [[ -n "$h" ]] && (( h >= $2 )) && return 0; sleep 2
  done
  return 1
}

echo "=== BOOTSTRAP : snap-sync et élagage ==="

# --- 0. Le fournisseur lui-même ---------------------------------------------------------------
# `PROVIDER` (4406) n'est lancé par AUCUN script tiers — comme suite-pow.sh (même port, même
# défaut trouvé et corrigé campagne 9/staging), cette batterie doit monter sa propre topologie
# isolée, pas la supposer déjà vivante : sans ce lancement, `api "$PROVIDER" ...` rend une chaîne
# vide et BOOT-01 échouerait sur un pivot inexistant plutôt que sur une vraie règle. Distinct du
# nœud source de suite-pow.sh même s'il partage le port (les deux ne tournent jamais en même
# temps — chaque batterie est lancée seule ou séquentiellement par run-campaign.sh) : celui-ci a
# besoin de `RHIZOME_SNAPSHOT_EVERY=200`, que suite-pow.sh n'a aucune raison de poser.
#
# Il doit miner jusqu'à ce qu'un pivot soit enterré sous `maxReorgDepth` (BOOT-02) : premier pivot
# à la hauteur 200, donc au moins 200+$MAX_REORG_DEPTH blocs avant que BOOT-01/02 aient un pivot
# adoptable à juger — plusieurs dizaines de minutes à cadence PF2 plancher, d'où le budget de
# 1800 s que run-campaign.sh donne déjà à cette batterie.
PROVIDER_DATA="$BASE_DIR/solo-src"
kill_node "$PROVIDER_DATA"; rm -rf "$PROVIDER_DATA"
mkdir -p "$KEYS_DIR"
PROVIDER_KEY="$KEYS_DIR/solo-src.key"
[[ -f "$PROVIDER_KEY" ]] || "$WALLET_BIN" keygen "$PROVIDER_KEY" --plaintext >/dev/null
PROVIDER_ADDR="$("$WALLET_BIN" address "$PROVIDER_KEY")"
launch 4406 "$PROVIDER_DATA" solo-src RHIZOME_MINER="$PROVIDER_ADDR" RHIZOME_SNAPSHOT_EVERY=200
wait_up "$PROVIDER" || { echo "fournisseur injoignable (voir $BASE_DIR/logs/solo-src.log)" >&2; exit 1; }
# Recalibré campagne 9 (2026-09-22, deux temps).
#
# Temps 1 — le budget. 2000 s supposait ~3,4 ms/hash-cœur non contenté (le défaut
# `bench.sync.pufferfishMs`, cf. WHITEPAPER/plan de calibrage) — jamais vrai sur cette machine
# PARTAGÉE. Deux campagnes indépendantes lancées à froid s'y sont arrêtées à 245/320 puis 246/320
# blocs, quel que soit l'état des autres nœuds du réseau (pauser les 3 mineurs de
# `.staging-rehearsal` pendant la seconde n'a quasiment rien changé) : ~8,1-8,9 s/bloc réel, pas
# ~3,4 s.
#
# Temps 2 — la cible elle-même était fausse, et le budget élargi l'a enfin révélé : un 3ᵉ
# lancement, propre, a atteint 320/320 (BOOT-01 PASS) mais BOOT-02 a échoué — pivot enterré de 119
# blocs, pas 120. `RhizomeNode` matérialise le premier instantané quand
# `engine.height() >= snapshotEveryBlocks` (200 ici), vérifié par un scheduler `syncPeriodMs`
# (10 s par défaut) — pas au bloc 200 pile. Sous ~8,1 s/bloc, la fenêtre de 10 s peut laisser
# passer un bloc de plus avant que le scheduler ne voie la condition franchie : le pivot observé
# était 201, pas 200. `PROVIDER_TARGET = 200 + MAX_REORG_DEPTH` supposait donc un pivot exact —
# faux par construction, pas seulement par malchance de calendrier. Corrigé en attendant le VRAI
# pivot avant de calculer la cible d'enfouissement, au lieu de le supposer.
wait_height "$PROVIDER" 200 2300 || \
  echo "AVERTISSEMENT: fournisseur à $(height_at "$PROVIDER")/200 avant le premier instantané attendu" >&2

# Le premier instantané se matérialise dans les ~1-2 tours du scheduler qui suivent (voir
# ci-dessus) : une courte attente dédiée, distincte de wait_height, qui ne teste pas une hauteur
# mais l'apparition du pivot lui-même.
PIVOT0=""
pivot_deadline=$((SECONDS + 120))
while (( SECONDS < pivot_deadline )); do
  PIVOT0="$(json_get "$(api "$PROVIDER" /state/snapshot/info)" pivotHeight)"
  [[ -n "$PIVOT0" && "$PIVOT0" -gt 1 ]] 2>/dev/null && break
  PIVOT0=""
  sleep 5
done
if [[ -n "$PIVOT0" ]]; then
  PROVIDER_TARGET=$((PIVOT0 + MAX_REORG_DEPTH))
else
  echo "AVERTISSEMENT: aucun pivot matérialisé après 120 s — repli sur l'ancienne hypothèse (200), BOOT-01 tranchera" >&2
  PROVIDER_TARGET=$((200 + MAX_REORG_DEPTH))
fi
wait_height "$PROVIDER" "$PROVIDER_TARGET" 1400 || \
  echo "AVERTISSEMENT: fournisseur à $(height_at "$PROVIDER")/$PROVIDER_TARGET — BOOT-02 tranchera" >&2

# --- 1. Le fournisseur d'instantanés --------------------------------------------------------
SNAPINFO="$(api "$PROVIDER" /state/snapshot/info)"
PIVOT="$(json_get "$SNAPINFO" pivotHeight)"
PIVOT_ROOT="$(json_get "$SNAPINFO" stateRoot)"
CHUNKS="$(json_get "$SNAPINFO" chunks)"
PROV_H="$(height_at "$PROVIDER")"
PIVOT_OK=$([[ -n "$PIVOT" && "$PIVOT" -gt 1 ]] && echo oui || echo non)
expect_eq BOOT-01 oui "$PIVOT_OK" "instantané matérialisé au pivot $PIVOT ($CHUNKS morceau(x), racine ${PIVOT_ROOT:0:16})"

# Le pivot n'est adoptable QUE s'il est enterré sous maxReorgDepth : c'est la condition qui rend
# l'instantané final. Un opérateur qui règle RHIZOME_SNAPSHOT_EVERY <= maxReorgDepth n'offrira
# jamais d'instantané utilisable — le pivot suit alors le tip de trop près.
BURIED=$(( PROV_H - PIVOT ))
BURIED_OK=$([[ "$BURIED" -ge "$MAX_REORG_DEPTH" ]] && echo oui || echo non)
expect_eq BOOT-02 oui "$BURIED_OK" "pivot enterré de $BURIED blocs sous le tip $PROV_H (exigé: $MAX_REORG_DEPTH)"

# --- 2. Le snap-sync ------------------------------------------------------------------------
kill_node "$SNAP_DATA"; rm -rf "$SNAP_DATA"
launch "$SNAP_PORT" "$SNAP_DATA" snap RHIZOME_SYNC=snap RHIZOME_PEERS="$PROVIDER_SEED"
if ! wait_up "$SNAP"; then
  record BOOT-03 FAIL "le nœud snap n'a jamais répondu (voir $BASE_DIR/logs/snap.log)"
else
  # La preuve d'adoption est le FILIGRANE, pas `/info.snapshotPivot` : ce champ décrit
  # l'instantané que le nœud SERT (il n'en matérialise aucun sans RHIZOME_SNAPSHOT_EVERY), pas
  # celui dont il est parti. Un nœud snap-syncé annonce prunedBelow = pivot + 1 : il détient la
  # genèse et les en-têtes jusqu'au pivot, et les corps seulement au-dessus.
  wait_height "$SNAP" "$((PIVOT + 1))" 120
  expect_eq BOOT-03 "$((PIVOT + 1))" "$(json_get "$(api "$SNAP" /info)" prunedBelow)" \
    "filigrane = pivot + 1 : l'état a bien été adopté au pivot $PIVOT"

  # L'égalité qui compte : l'état adopté par confiance doit être BIT POUR BIT celui du
  # fournisseur. On le lit sur le PREMIER bloc au-dessus du pivot — le nœud snap l'a appliqué
  # contre son propre accumulateur, et un état adopté faux l'aurait fait échouer en
  # INVALID_STATE_ROOT au lieu de produire la même racine. C'est tout ce qui sépare un raccourci
  # d'un fork silencieux.
  expect_eq BOOT-04 \
    "$(json_get "$(api "$PROVIDER" "/block?blockId=$((PIVOT + 1))")" stateRoot)" \
    "$(json_get "$(api "$SNAP" "/block?blockId=$((PIVOT + 1))")" stateRoot)" \
    "racine d'état du bloc $((PIVOT + 1)), appliquée sur l'état adopté"

  # Le suffixe : au-dessus du pivot le nœud repasse par la synchro normale.
  if wait_height "$SNAP" "$PROV_H" 180; then
    record BOOT-05 PASS "suffixe rattrapé jusqu'à $(height_at "$SNAP") (pivot $PIVOT + $((PROV_H - PIVOT)) blocs)"
  else
    record BOOT-05 FAIL "suffixe non rattrapé: $(height_at "$SNAP") < $PROV_H"
  fi

  # Sous le pivot le nœud NE DÉTIENT PAS les corps : c'est le gain du snap-sync, et il doit
  # s'annoncer honnêtement plutôt que de servir du vide.
  PB="$(json_get "$(api "$SNAP" /info)" prunedBelow)"
  PB_OK=$([[ -n "$PB" && "$PB" -gt 1 ]] && echo oui || echo non)
  expect_eq BOOT-06 oui "$PB_OK" "filigrane annoncé sous le pivot: prunedBelow=$PB"
  expect_eq BOOT-07 410 "$(code_of "$SNAP" "/sync?start=2&end=2")" "corps sous le filigrane: 410 GONE"
  expect_contains BOOT-07b '"error":"pruned"' "$(body_of "$SNAP" "/block?blockId=$PIVOT")" \
    "la vue JSON refuse elle aussi, en portant le filigrane"

  # Et il continue de valider : deux blocs plus tard, même tip que le fournisseur.
  TARGET=$(( $(height_at "$PROVIDER") + 2 ))
  if wait_height "$SNAP" "$TARGET" 120; then
    expect_eq BOOT-08 "$(tip_at "$PROVIDER")" "$(tip_at "$SNAP")" "même tip que le fournisseur après $TARGET blocs"
    expect_eq BOOT-09 "$(root_at "$PROVIDER")" "$(root_at "$SNAP")" "même racine d'état que le fournisseur"
  else
    record BOOT-08 FAIL "le nœud snap ne suit plus le fournisseur"
    record BOOT-09 FAIL "non évalué (BOOT-08 en échec)"
  fi
fi

# --- 3. L'élagage ---------------------------------------------------------------------------
# 3a. Une rétention sous le plancher de sûreté doit refuser AU DÉMARRAGE, pas au milieu d'un
#     reorg : le nœud jetterait un corps que le moteur relit encore.
kill_node "$BAD_DATA"; rm -rf "$BAD_DATA"
launch "$BAD_PORT" "$BAD_DATA" prunebad RHIZOME_PRUNE=100
sleep 5
BAD_LOG="$(cat "$BASE_DIR/logs/prunebad.log" 2>/dev/null)"
expect_contains BOOT-10 "below the safe floor of $PRUNE_FLOOR blocks" "$BAD_LOG" \
  "RHIZOME_PRUNE=100 refusé au démarrage"
expect_eq BOOT-11 000 "$(code_of "http://127.0.0.1:$BAD_PORT" /stats)" "aucun service n'écoute après le refus"

# 3b. Une rétention valide : le nœud se synchronise puis jette ce qu'il a le droit de jeter.
kill_node "$PRUNE_DATA"; rm -rf "$PRUNE_DATA"
launch "$PRUNE_PORT" "$PRUNE_DATA" prune RHIZOME_PRUNE=$PRUNE_FLOOR RHIZOME_PEERS="$(node_seed_url "$VICTIM")"
if ! wait_up "$PRUNE"; then
  record BOOT-12 FAIL "le nœud élagué n'a jamais répondu (voir $BASE_DIR/logs/prune.log)"
else
  NET_H="$(height_at "$(node_url "$VICTIM")")"
  # Budget porté de 300 à 600 s (campagne 9/staging, run-4) : un nœud élagué part d'un répertoire de
  # données VIDE et doit rattraper un réseau déjà à des milliers de blocs — sous contention CPU
  # partagée (un autre réseau de test + suite-soak.sh tournaient en même temps sur cette machine),
  # le rattrapage a mesuré ~420 s de bout en bout (300 s d'échéance ici + ~120 s côté BOOT-17
  # ci-dessous), donc 300 s le faisait échouer à tort. Comme pour `suite-pow.sh`/`Anvil` : le budget
  # se cale sur la machine et la contention réelles, pas sur une vitesse devnet supposée.
  if wait_height "$PRUNE" "$((NET_H - 2))" 600; then
    record BOOT-12 PASS "nœud élagué synchronisé à $(height_at "$PRUNE") (réseau $NET_H)"
  else
    record BOOT-12 FAIL "nœud élagué à $(height_at "$PRUNE"), réseau à $NET_H"
  fi
  PPB="$(json_get "$(api "$PRUNE" /info)" prunedBelow)"
  PPB_OK=$([[ -n "$PPB" && "$PPB" -gt 1 ]] && echo oui || echo non)
  expect_eq BOOT-13 oui "$PPB_OK" "filigrane d'élagage annoncé: prunedBelow=$PPB (rétention $PRUNE_FLOOR)"
  expect_eq BOOT-14 410 "$(code_of "$PRUNE" "/sync?start=1&end=1")" "bloc 1 jeté: 410 GONE"
  # BOOT-15/16 relisent prunedBelow ICI plutôt que de réutiliser $PPB (capturé avant BOOT-13) : si le
  # nœud rattrape encore du retard (cas ci-dessus), le filigrane avance en continu, et $PPB devient
  # périmé en dessous de la seconde — vu en pratique (2304 -> 2306 entre la capture et cette ligne,
  # campagne 9/staging, run-4), pris à tort pour un défaut de pruning plutôt qu'une valeur relue tard.
  PPB_NOW="$(json_get "$(api "$PRUNE" /info)" prunedBelow)"
  expect_contains BOOT-15 "$PPB_NOW" "$(body_of "$PRUNE" "/sync?start=1&end=1")" \
    "le 410 porte le filigrane, pour que l'appelant sache où se rabattre"
  RETAINED=$(( PPB_NOW + 10 ))
  expect_eq BOOT-16 200 "$(code_of "$PRUNE" "/sync?start=$RETAINED&end=$RETAINED")" \
    "au-dessus du filigrane, le corps est servi normalement (bloc $RETAINED)"
  # Égalité de tip avec échéance : la cible bouge (un bloc toutes les ~20 s), donc une
  # comparaison instantanée mesurerait la latence de gossip, pas la conformité.
  deadline=$((SECONDS + 120)); same=non
  while (( SECONDS < deadline )); do
    [[ "$(tip_at "$PRUNE")" == "$(tip_at "$(node_url "$VICTIM")")" ]] && { same=oui; break; }
    sleep 3
  done
  expect_eq BOOT-17 oui "$same" "le nœud élagué converge sur le tip du réseau ($(tip_at "$PRUNE" | cut -c1-16))"
fi

# 3c. Le contraste qui donne son sens au 410 : un nœud d'archive sert toujours le bloc 1.
expect_eq BOOT-18 200 "$(code_of "$(node_url "$VICTIM")" "/sync?start=1&end=1")" \
  "nœud d'archive (RHIZOME_PRUNE absent) : le bloc 1 reste servi"
expect_eq BOOT-19 0 "$(json_get "$(api "$(node_url "$VICTIM")" /info)" prunedBelow)" \
  "nœud d'archive : aucun filigrane"

suite_summary
