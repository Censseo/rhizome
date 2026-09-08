#!/usr/bin/env bash
# Batterie « déni de service applicatif » — inonder /submit d'un vrai nœud avec des blocs
# structurellement plausibles mais dont le PoW n'est PAS payé, et mesurer l'effet sur la
# production honnête et la santé du nœud.
#
# Ancrage : docs/adversarial/spec.md, famille API, scénario API-02 (« Occupy the single
# event-loop thread with a flood of cheap submissions that each trigger proof-of-work
# verification. » — DEFENDED par NodeApiTest#submitPowGateShedsBlocksBeforeTheBodyIsDecoded).
# Cette preuve existe au composant, contre un budget synthétique (1 requête / heure) et un
# `NodeService` isolé ; cette batterie la rejoue contre le budget de PRODUCTION
# (`AdmissionControl.SUBMIT_POW_MAX_PER_SEC = 25`, app-node/src/main/java/rhizome/node/
# AdmissionControl.java) et un nœud VIVANT, avec de vrais pairs et un vrai mineur autour de lui —
# c'est ce qui rend la question « la production continue-t-elle pendant l'inondation ? »
# vérifiable, alors qu'un `NodeService` de test seul ne mine ni ne synchronise avec personne.
#
# Terrain : un nœud de la campagne déjà lancée par start.sh (comme suite-net.sh), PAS une paire
# isolée façon suite-pow.sh/suite-bootstrap.sh — cette batterie a justement besoin d'un nœud qui
# reçoit de VRAIS blocs (mineur local et/ou gossip de pairs) PENDANT l'inondation, pour que
# « la production honnête continue » soit une mesure et non une hypothèse.
#
# Le bloc poubelle : `Anvil --no-pow --dump <fichier>` (tools/Anvil.java) forge un bloc qui PREND
# le corps réel du prochain bloc attendu par la cible (vraies transactions, bon hash parent, bonne
# difficulté déclarée) et n'en ré-mine PAS le nonce — exactement la forme que décrit le Javadoc du
# budget visé : « public parent hash, in-window id, garbage nonce ». Il est écrit UNE fois sur
# disque puis REJOUÉ tel quel par `curl`, plutôt que reforgé à chaque coup : forger coûte une JVM
# (plusieurs centaines de ms), le rejouer ne coûte qu'une socket. C'est aussi la forme exacte de
# l'attaque que vise `AdmissionControl.SUBMIT_POW_MAX_PER_SEC` (« a single IP resending ONE
# PoW-free block »). Un second processus le RAFRAÎCHIT en tâche de fond pendant l'inondation, pour
# qu'une partie des requêtes reste « fraîche » (id/hash parent à jour) et atteigne donc bien la
# vérification PoW elle-même — le reste devient un id périmé (INVALID_BLOCK_ID), rejeté encore
# plus tôt, ce qui ne fausse rien : le budget visé est consommé AVANT même le décodage du corps,
# quel que soit son contenu (voir NodeApi.java, "Aggregate submit gate, consumed BEFORE the
# /submit handler decodes the block body").
#
# GRANDE LIMITE, à écrire noir sur blanc plutôt qu'à cacher : cette machine UNIQUE est à la fois
# l'attaquant et l'observateur. Le débit de soumission mesuré ici (curl séquentiel/en rafales
# depuis un seul processus, une seule IP) est donc un plancher, pas un plafond : un attaquant
# RÉEL, réparti sur de nombreuses IP/machines, échappe en plus au limiteur PAR IP et à la table de
# strikes anti-abus (PushStrikeTable, par CLIENT) qui, ICI, finissent par éteindre notre propre
# flot après quelques dizaines de rejets — un vrai deuxième palier de défense, mais qui MASQUE
# partiellement le palier agrégé qu'on visait initialement. Une campagne multi-machines devrait
# reproduire ceci depuis plusieurs adresses sources pour isoler le budget agrégat des deux
# défenses par-client, et viser un débit soutenu très supérieur à celui qu'un seul cœur curl peut
# atteindre sur loopback.
#
# Usage : suite-dos.sh [node] (défaut 0 — le nœud « victime », mineur par construction : voir
#         common.sh, MINERS contient toujours l'indice 0)
set -uo pipefail
SUITE_NAME=dos
source "$(dirname "$0")/suite-common.sh"

NODE=${1:-0}
NODE_URL_TARGET="$(node_url "$NODE")"

# Durée de la fenêtre d'inondation. Assez longue pour couvrir plusieurs secondes du budget
# (25/s) ET pour laisser une chance à un bloc honnête d'être observé dans la fenêtre elle-même
# (le paramètre par défaut de la campagne, 25 s/bloc réseau, rend 45 s raisonnable) ; réglable
# pour un devnet local plus rapide (voir vérification, plus bas, avec RHIZOME_TESTNET_BLOCK_MS
# réduit).
FLOOD_SECONDS="${RHIZOME_DOS_FLOOD_SECONDS:-45}"
# Rafraîchit le bloc poubelle toutes les REFRESH_SEC secondes : assez fréquent pour qu'une partie
# du flot reste « fraîche » (id courant + hash parent courant) sur un devnet à cadence rapide,
# assez rare pour ne pas faire concurrence en CPU/JVM au flot lui-même.
REFRESH_SEC="${RHIZOME_DOS_REFRESH_SEC:-4}"
# Requêtes concurrentes par rafale du flot : au-delà, sur une seule machine, on mesure surtout la
# limite de `curl`/du shell, pas celle du nœud.
PARALLEL="${RHIZOME_DOS_PARALLEL:-8}"

# Miroir TEXTE SEULEMENT de AdmissionControl.SUBMIT_POW_MAX_PER_SEC (app-node) — pas d'endpoint
# HTTP ne l'expose (ce n'est pas une constante de NetworkParameters, donc pas dans profiles/*.env
# comme suite-pow.sh) : cette valeur n'entre dans AUCUN calcul de verdict ci-dessous, seulement
# dans le texte d'un `record_metric`. Si elle change côté Java, seul le commentaire devient périmé.
SUBMIT_POW_MAX_PER_SEC_NOTE="25/s (AdmissionControl.SUBMIT_POW_MAX_PER_SEC)"

JUNK_FILE="$BASE_DIR/dos-junk-block.bin"
CODE_FILE=""
HEALTH_FILE=""
REFRESH_PID=""
HEALTH_PID=""

# Arrête une tâche de fond ET ses enfants directs, silencieusement — jamais un `wait` nu (voir
# `flood_for` plus bas pour ce que ça casse). `pkill -P "$pid"` AVANT `kill "$pid"` : `refresh_junk`
# lance Anvil en tâche de fond DANS le sous-shell de `refresher_loop` (un `&` fork un processus
# séparé), donc une variable qui y capturerait le PID d'Anvil n'existerait QUE dans ce sous-shell,
# jamais ici — mesuré : un `ANVIL_PID` posé côté `refresh_junk` restait vide côté appelant, et la
# JVM Anvil en cours de forge survivait, orpheline, après la fin de la batterie. Cibler les ENFANTS
# du PID plutôt qu'un PID capturé règle le problème sans dépendre d'un partage d'état entre
# sous-shells. Utilisée aussi bien à la fin normale de l'inondation qu'en filet de sécurité dans
# `cleanup` : arrêter la tâche puis vider la variable au SEUL endroit qui le fait évite qu'un appel
# précoce (juste après `flood_for`) ne vide la variable avant que le filet `cleanup` n'ait eu la
# main — c'est ce qui laissait échapper l'Anvil orphelin même avec `cleanup` déjà en place.
stop_bg() {
  local pid=$1
  [[ -z "$pid" ]] && return 0
  local grandchild; grandchild="$(pgrep -P "$pid" | head -1)"
  pkill -P "$pid" 2>/dev/null
  kill "$pid" 2>/dev/null
  wait "$pid" 2>/dev/null
  # `pkill` ne fait que SIGNALER le petit-fils, il n'attend pas sa mort : sans ceci, `cleanup`
  # peut nettoyer AVANT qu'un `Files.write` déjà entamé côté Anvil ne recrée le `.tmp` juste
  # après (mesuré : le fichier survivait au `rm -f` de cleanup). `wait` ne s'applique qu'aux
  # enfants DIRECTS du shell appelant, donc on sonde plutôt — borné, jamais indéfiniment.
  if [[ -n "$grandchild" ]]; then
    local i
    for ((i = 0; i < 40; i++)); do
      kill -0 "$grandchild" 2>/dev/null || break
      sleep 0.05
    done
  fi
}

# Nettoyage : les deux tâches de fond ET les fichiers temporaires doivent disparaître même si la
# batterie s'arrête en cours de route (variable non liée, nœud injoignable, ...).
cleanup() {
  stop_bg "$REFRESH_PID"
  stop_bg "$HEALTH_PID"
  rm -f "$CODE_FILE" "$HEALTH_FILE" "$JUNK_FILE" "$JUNK_FILE.tmp"
}
trap cleanup EXIT

# --- Anvil : forgeur de blocs (voir suite-pow.sh, même outil, même compilation à la demande) ----
ANVIL_SRC="$ROOT/scripts/local-testnet/tools/Anvil.java"
NODE_LIB="$ROOT/app-node/build/install/app-node/lib/*"
anvil_build() {
  ensure_jdk25
  if [[ ! -f "$TOOLS_DIR/Anvil.class" || "$ANVIL_SRC" -nt "$TOOLS_DIR/Anvil.class" ]]; then
    mkdir -p "$TOOLS_DIR"
    "$JAVA_HOME/bin/javac" -cp "$NODE_LIB" -d "$TOOLS_DIR" "$ANVIL_SRC" || return 1
  fi
}
# Pas de fonction `anvil()` d'enveloppe ici (contrairement à suite-pow.sh) : `refresh_junk`
# lance la JVM EN LIGNE, à dessein — voir son commentaire pour la raison (capturer le PID de la
# JVM elle-même, pas celui d'un sous-shell intermédiaire).

# Source ET cible sont le MÊME nœud : `--source` fournit le corps réel (vraies transactions) du
# prochain bloc attendu, `--url` fournit la hauteur/difficulté attendues — les deux questions
# portent sur la même chaîne, celle de la victime elle-même. Pas besoin d'un second nœud : la
# victime est déjà un participant normal du réseau (pairs + éventuellement son propre mineur),
# donc `Anvil` voit apparaître le bloc suivant par la voie normale (gossip/minage), exactement
# comme le ferait un attaquant guettant les blocs publics de sa cible.
refresh_junk() {
  # La JVM est lancée ICI EN LIGNE (pas via la fonction `anvil()`) et backgroundée ELLE-MÊME :
  # `anvil ... &` backgrounderait un APPEL DE FONCTION, ce que bash exécute dans un sous-shell —
  # `$!` capturerait alors le PID de ce sous-shell, pas celui de la JVM qu'il lance à son tour (un
  # PETIT-FILS de `refresher_loop`, hors de portée d'un `pkill -P` sur `refresher_loop` lui-même).
  # Mesuré : avec l'appel de fonction backgroundé, `stop_bg` tuait le sous-shell intermédiaire et
  # la JVM survivait, orpheline. En ligne, `$!` est directement le PID de la JVM — un enfant DIRECT
  # de `refresher_loop`, celui que `stop_bg`/`pkill -P` visent.
  local anvil_pid
  "$JAVA_HOME/bin/java" -cp "$TOOLS_DIR:$NODE_LIB" Anvil --network "$NETWORK" \
    --source "$NODE_URL_TARGET" --url "$NODE_URL_TARGET" --no-pow --quiet \
    --dump "$JUNK_FILE.tmp" >> "$BASE_DIR/logs/dos-anvil.log" 2>&1 &
  anvil_pid=$!
  wait "$anvil_pid"
  local rc=$?
  (( rc == 0 )) && mv -f "$JUNK_FILE.tmp" "$JUNK_FILE"
  return $rc
}

refresher_loop() {
  while true; do
    refresh_junk
    sleep "$REFRESH_SEC"
  done
}

# --- santé, échantillonnée EN CONTINU pendant l'inondation (pas seulement avant/après) ----------
health_loop() {
  while true; do
    { node_healthy "$NODE"; echo; } >> "$HEALTH_FILE"
    sleep 1
  done
}

# --- cadence de bloc, mesurée en dehors de toute inondation : le témoin ------------------------
# Retourne des ms/bloc sur `blocks` blocs, ou vide si le nœud ne progresse pas dans `timeout` s.
measure_interval() {
  local node=$1 blocks=$2 timeout=$3
  local h0 t0; h0="$(height_of "$node")"; t0="$(date +%s%3N)"
  [[ -z "$h0" ]] && return 1
  local deadline=$((SECONDS + timeout)) h h1 t1
  while (( SECONDS < deadline )); do
    h="$(height_of "$node")"
    if [[ -n "$h" ]] && (( h >= h0 + blocks )); then h1=$h; t1="$(date +%s%3N)"; break; fi
    sleep 1
  done
  [[ -z "${h1:-}" ]] && return 1
  local delta=$((h1 - h0)) elapsed=$((t1 - t0))
  (( delta <= 0 )) && return 1
  printf '%d' "$((elapsed / delta))"
}

# `height_of`, patient : juste après une rafale de flot venue de CETTE MÊME adresse IP, le
# limiteur PAR IP (fenêtre glissante de 1 s, RhizomeNode.java) peut encore refuser nos propres
# lectures de contrôle pendant qu'il se vide — mesuré : `/stats` répond 429 à la lecture immédiate
# suivant `flood_for`, alors que le nœud lui-même est parfaitement sain. Ce n'est pas une propriété
# du NŒUD qu'on cherche à borner ici (c'est justement le comportement voulu du limiteur), donc on
# retente plutôt que de le compter comme un échec de lecture.
height_of_retry() {
  local node=$1 tries=${2:-6} h i
  for ((i = 0; i < tries; i++)); do
    h="$(height_of "$node")"
    [[ -n "$h" ]] && { printf '%s' "$h"; return 0; }
    sleep 1
  done
  return 1
}

# --- le flot lui-même : rafales de `curl` en parallèle pendant `seconds` secondes --------------
# Rejoue systématiquement le CONTENU COURANT de $JUNK_FILE (curl le relit à chaque requête —
# `refresher_loop` le remplace par un `mv` atomique en tâche de fond, jamais de lecture partielle).
# Chaque requête ajoute son code HTTP (une ligne) à CODE_FILE ; les écritures concurrentes restent
# de petites lignes en O_APPEND, ce que Linux traite atomiquement en pratique pour cette taille.
flood_for() {
  local seconds=$1
  local deadline=$((SECONDS + seconds))
  local p burst_pids
  while (( SECONDS < deadline )); do
    burst_pids=()
    for ((p = 0; p < PARALLEL; p++)); do
      curl -s -o /dev/null -w '%{http_code}\n' --max-time 5 -X POST \
        -H 'Content-Type: application/octet-stream' -H 'X-Rhizome-Request: 1' \
        --data-binary "@$JUNK_FILE" "$NODE_URL_TARGET/submit" >> "$CODE_FILE" 2>/dev/null &
      burst_pids+=($!)
    done
    # `wait` avec la liste EXPLICITE des PID de cette rafale, jamais un `wait` nu : celui-ci
    # attendrait TOUT job de fond du shell, y compris `refresher_loop`/`health_loop` qui ne
    # terminent jamais — c'est le bug qui a bloqué la toute première version de cette fonction
    # (mesuré : le flot s'arrêtait net après la première rafale, `SECONDS` ne progressant plus).
    wait "${burst_pids[@]}" 2>/dev/null
  done
}

echo "=== DOS : inondation de /submit par des blocs PoW-invalides (nœud $NODE_URL_TARGET) ==="

anvil_build || { echo "compilation d'Anvil impossible" >&2; exit 1; }
mkdir -p "$(dirname "$JUNK_FILE")"

if ! refresh_junk || [[ ! -s "$JUNK_FILE" ]]; then
  record DOS-00-junk-forged FAIL "impossible de forger le bloc poubelle initial (voir $BASE_DIR/logs/dos-anvil.log)"
  suite_summary
  exit 1
fi
record DOS-00-junk-forged PASS "bloc poubelle forgé ($(stat -c%s "$JUNK_FILE" 2>/dev/null || wc -c <"$JUNK_FILE") octets, nonce non payé)"

echo "== témoin : cadence de bloc hors inondation =="
BASELINE_MS="$(measure_interval "$NODE" 2 300)"
if [[ -n "$BASELINE_MS" ]]; then
  record_metric API-02-interval-baseline "$BASELINE_MS" ms/bloc \
    "hors inondation, 2 blocs — le témoin auquel comparer la mesure pendant l'inondation"
else
  record DOS-01-baseline-progress FAIL "le nœud n'a produit aucun bloc en 300 s hors inondation — terrain non exploitable"
  suite_summary
  exit 1
fi

echo "== inondation ($FLOOD_SECONDS s, rafales de $PARALLEL, rafraîchi toutes les ${REFRESH_SEC}s) =="
CODE_FILE="$(mktemp "$BASE_DIR/dos-codes.XXXXXX")"
HEALTH_FILE="$(mktemp "$BASE_DIR/dos-health.XXXXXX")"

refresher_loop & REFRESH_PID=$!
health_loop & HEALTH_PID=$!

H_BEFORE="$(height_of_retry "$NODE")"
T_BEFORE_MS="$(date +%s%3N)"
flood_for "$FLOOD_SECONDS"
T_AFTER_MS="$(date +%s%3N)"
H_AFTER="$(height_of_retry "$NODE")"

stop_bg "$REFRESH_PID"; REFRESH_PID=""
stop_bg "$HEALTH_PID"; HEALTH_PID=""

TOTAL="$(wc -l < "$CODE_FILE" | tr -d ' ')"
OK="$(grep -c '^200$' "$CODE_FILE" || true)"
SHED="$(grep -c '^429$' "$CODE_FILE" || true)"
REJECTED="$(grep -c '^400$' "$CODE_FILE" || true)"
OTHER=$((TOTAL - OK - SHED - REJECTED))
ELAPSED_MS=$((T_AFTER_MS - T_BEFORE_MS))

echo "== verdicts =="

# Débit RÉELLEMENT atteint par cette machine unique — voir la limite documentée en tête de
# fichier : c'est un plancher pour ce que peut faire l'attaquant, pas ce que le nœud subit dans le
# pire des cas distribué.
RATE_PER_SEC=0
(( ELAPSED_MS > 0 )) && RATE_PER_SEC=$(( TOTAL * 1000 / ELAPSED_MS ))
record_metric API-02-flood-rate "$RATE_PER_SEC" req/s \
  "$TOTAL requêtes en ${ELAPSED_MS}ms depuis UNE machine/IP (200:$OK 429:$SHED 400:$REJECTED autre:$OTHER) — un attaquant distribué sur plusieurs IP dépasserait ce chiffre, voir la note de fichier"

# La preuve que le budget agrégat (ou son voisin par-client, la table de strikes) a bien mordu :
# si l'inondation dépasse 25/s soutenu sur 45 s et qu'AUCUNE requête n'est délestée, le budget
# n'a rien fait — c'est le signal d'alarme que ce cas existe pour capter.
expect_ge API-02-budget-engaged 1 "$SHED" \
  "requêtes délestées (429) sur $TOTAL envoyées à $RATE_PER_SEC req/s — la borne de $SUBMIT_POW_MAX_PER_SEC_NOTE mord"

echo "== santé pendant l'inondation =="
HEALTH_SAMPLES="$(wc -l < "$HEALTH_FILE" | tr -d ' ')"
HEALTH_BAD="$(grep -cE 'degraded=true|reorg=true' "$HEALTH_FILE" || true)"
expect_eq API-02-not-degraded 0 "$HEALTH_BAD" \
  "sur $HEALTH_SAMPLES échantillons/stats pendant l'inondation, aucun degraded=true ni reorg=true"

echo "== production honnête pendant la fenêtre d'inondation =="
if [[ -n "${H_BEFORE:-}" && -n "${H_AFTER:-}" ]]; then
  expect_ge API-02-height-advances $((H_BEFORE + 1)) "$H_AFTER" \
    "hauteur $H_BEFORE → $H_AFTER pendant les ${FLOOD_SECONDS}s d'inondation — la production honnête n'a pas gelé"
  DURING_DELTA=$((H_AFTER - H_BEFORE))
  if (( DURING_DELTA > 0 )); then
    DURING_MS=$((ELAPSED_MS / DURING_DELTA))
    record_metric API-02-interval-during "$DURING_MS" ms/bloc \
      "pendant l'inondation, $DURING_DELTA bloc(s) — à comparer au témoin API-02-interval-baseline ($BASELINE_MS ms/bloc)"
  else
    record_metric API-02-interval-during "" "" \
      "aucun bloc observé dans la fenêtre de ${FLOOD_SECONDS}s (cadence témoin ${BASELINE_MS}ms/bloc supérieure à la fenêtre) — augmenter FLOOD_SECONDS ou réduire RHIZOME_TESTNET_BLOCK_MS pour l'observer"
  fi
else
  record DOS-02-height-readable FAIL "hauteur illisible avant ($H_BEFORE) ou après ($H_AFTER) l'inondation"
fi

echo "== le nœud répond-il encore ? =="
[[ -n "$(node_stats "$NODE")" ]] \
  && record API-02-responsive PASS "le nœud sert encore /stats après l'inondation" \
  || record API-02-responsive FAIL "le nœud ne répond plus après l'inondation"

expect_eq API-02-final-healthy "degraded=null reorg=false" "$(node_healthy "$NODE")" \
  "état final du nœud victime, après extinction du flot"

suite_summary
