#!/usr/bin/env bash
# Batterie CLOCK — la borne future (maxFutureBlockTimeSec, famille TIME) contre un VRAI
# PROCESSUS dont l'horloge SYSTÈME est réellement décalée, pas seulement un JVM ou un champ de
# bloc forgé.
#
# Ce que cette batterie ajoute par rapport à ce qui existe déjà :
#   * `ClockDriftAttackTest` (lib-core, TIME-06) construit deux moteurs à la main, chacun avec
#     son propre SEAM d'horloge Java (un `Supplier<Instant>` substitué) — la preuve tient au
#     niveau du composant, jamais sur un système d'exploitation réel.
#   * `suite-pow.sh` (TIME-01a/b, via `Anvil --ts-offset`) et E2E-92/93 soumettent à un VRAI
#     nœud un bloc VRAIMENT re-miné dont le SEUL champ altéré est l'horodatage déclaré — le
#     processus qui l'a produit, lui, n'a jamais eu une notion du temps différente de la nôtre.
#
# Aucune de ces preuves ne fait tourner `clock_gettime()`/`gettimeofday()` eux-mêmes en dehors de
# leur valeur réelle — or c'est exactement l'appel que la borne future de `ChainEngine`, les
# délais de connexion d'ActiveJ, l'horodatage des écritures RocksDB et le planificateur de sync
# font tous. Cette batterie ferme ce trou-là : un processus `rhizome-node` RÉEL, assemblé, dont
# l'horloge OS est décalée via LD_PRELOAD (libfaketime), produit un bloc VRAIMENT miné sous cette
# horloge décalée, et un second processus RÉEL — horloge intacte — juge s'il l'accepte.
#
# --- Sécurité : machine PARTAGÉE, pas une VM dédiée ------------------------------------------
# Interdiction absolue : ne JAMAIS toucher l'horloge SYSTÈME de cette machine (pas de `date -s`,
# pas de `timedatectl`) — cela affecterait tous les autres utilisateurs/sessions de cette boîte.
# `faketime_setup` ci-dessous décide dans cet ordre, et documente pourquoi chaque option est sûre :
#   1. `faketime` déjà installé système ? -> son `.so` (résolu via `dpkg -L`, pas un chemin
#      distro codé en dur) affecte UNIQUEMENT le processus enfant qu'on lance avec — jamais
#      l'hôte.
#   2. Sinon : `apt-get download` (pas `install` — aucun accès root requis, AUCUNE mutation de
#      `/var/lib/dpkg`) puis `dpkg-deb -x` (extraction PURE, hors de tout système de paquets)
#      dans `$TOOLS_DIR/faketime`, un répertoire à nous. Zéro état partagé touché : ni le
#      registre de paquets, ni l'horloge, ni un autre processus. C'est la lecture retenue de
#      « installable sans affecter les autres utilisateurs de cette machine partagée » — on
#      n'installe RIEN au sens système, on extrait des fichiers dans NOTRE répertoire.
#   3. Ni l'un ni l'autre (pas de réseau, pas de cache apt) ? `FAKETIME_LIB` reste vide, et la
#      moitié « horloge PROCESSUS » de la batterie est marquée verdict METRIC portant "SKIP"
#      (jamais un faux PASS, jamais compté en échec — voir `record` dans suite-common.sh) au
#      lieu d'être exécutée. Le repli couvre alors SEULEMENT la moitié « attaquant » du scénario
#      (un horodatage forgé sur un processus dont l'horloge, elle, n'a jamais bougé) via
#      `Anvil --ts-offset`, exactement ce que suite-pow.sh prouve déjà — donc redondant mais
#      honnête, plutôt que de prétendre couvrir ce que ce repli ne peut pas prouver.
#
# --- Ce qu'une VRAIE campagne multi-VM prouverait EN PLUS de cette version mono-machine --------
# Ici, `REF` (horloge réelle) et `SKEW` (horloge décalée) tournent sur le MÊME noyau : leur
# CLOCK_REALTIME sous-jacent est un seul et même compteur matériel, et libfaketime ne fait
# qu'intercepter les appels de LA LIBC DE SKEW — le noyau, lui, voit toujours l'heure réelle. Une
# vraie campagne multi-VM ferait tourner SKEW sur une VM dont l'horloge NTP est désynchronisée
# pour de vrai (`timedatectl set-ntp false` puis `date -s`, licite là car la VM n'est prêtée à
# personne d'autre), ce qui exercerait EN PLUS des chemins que LD_PRELOAD ne peut pas atteindre :
# les horodatages posés par le noyau lui-même (mtime des fichiers RocksDB, l'ordre causal que TCP
# et le scheduler du noyau donnent aux paquets), et une dérive qui SURVIT à un redémarrage du
# processus (LD_PRELOAD doit être reposé à chaque lancement ; une VM désynchronisée reste
# désynchronisée). Cette version mono-machine est néanmoins une preuve strictement plus forte que
# JUnit ou qu'un simple champ forgé : le PROCESSUS entier — VM Chicory, RocksDB, planificateur de
# sync ActiveJ compris — tourne sous une horloge qui ment.
#
# Usage : suite-clock.sh [nœud de campagne]   (argument ACCEPTÉ pour la convention d'appel de
#         run-campaign.sh, mais ignoré : cette batterie est totalement auto-contenue — deux
#         nœuds isolés à elle, jamais pairés à la campagne ni entre eux, jugés par rejeu manuel
#         exact via /sync + /submit, comme suite-pow.sh le fait pour le même besoin de calendrier
#         imposé au lieu de laisser du vrai gossip introduire de la latence non maîtrisée).
set -uo pipefail
SUITE_NAME=clock
source "$(dirname "$0")/suite-common.sh"

# --- disponibilité de faketime -----------------------------------------------------------
FAKETIME_LIB=""
faketime_setup() {
  # 1. Système : déjà là, on l'utilise tel quel (dpkg -L, pas un chemin de distro codé en dur).
  if command -v faketime >/dev/null 2>&1; then
    FAKETIME_LIB="$(dpkg -L faketime 2>/dev/null | grep -m1 'libfaketimeMT\.so')"
    [[ -n "$FAKETIME_LIB" && -f "$FAKETIME_LIB" ]] && return 0
    FAKETIME_LIB=""
  fi
  # 2. Extraction locale, pure, sans root, sans toucher /var/lib/dpkg — voir bandeau ci-dessus.
  local dir="$TOOLS_DIR/faketime" lib
  lib="$dir/extracted/usr/lib/x86_64-linux-gnu/faketime/libfaketimeMT.so.1"
  if [[ -f "$lib" ]]; then FAKETIME_LIB="$lib"; return 0; fi
  mkdir -p "$dir"
  ( cd "$dir" && apt-get download faketime libfaketime >/dev/null 2>&1 )
  local deb any=0
  for deb in "$dir"/*.deb; do
    [[ -f "$deb" ]] || continue
    any=1
    dpkg-deb -x "$deb" "$dir/extracted" 2>/dev/null
  done
  (( any )) && [[ -f "$lib" ]] && FAKETIME_LIB="$lib"
  [[ -n "$FAKETIME_LIB" ]]
}
faketime_setup || true
if [[ -n "$FAKETIME_LIB" ]]; then
  echo "faketime : disponible ($FAKETIME_LIB) — chemin PRIMAIRE (horloge de PROCESSUS décalée)"
else
  echo "faketime : indisponible (ni système, ni extractible localement — pas de réseau ?)"
  echo "           repli sur Anvil --ts-offset : voir CLOCK-SKIP-VICTIM pour ce qui reste NON couvert"
fi

# --- topologie : deux nœuds isolés, à des ports jamais utilisés par les autres batteries -------
# (90/91/92 sont pris par suite-wallet.sh/suite-net.sh ; 4406/4407/4411-4413/4420-4422 par la
# paire pow/bootstrap et par la campagne — voir TEST-PLAN.md.)
REF_PORT=$((BASE_PORT + 94)); REF="http://127.0.0.1:$REF_PORT"; REF_DATA="$BASE_DIR/clock-ref"
SKEW_PORT=$((BASE_PORT + 95)); SKEW="http://127.0.0.1:$SKEW_PORT"; SKEW_DATA="$BASE_DIR/clock-skew"
SKEW_KEY="$KEYS_DIR/miner-0.key"     # SKEW mine sa PROPRE chaîne isolée depuis la genèse — aucune
                                      # dotation requise, seule l'adresse compte comme bénéficiaire.
SKEW_ADDR="$(addr_of "$SKEW_KEY")"

MAX_FUTURE=$(profile_get MAX_FUTURE_BLOCK_TIME_SEC)
WITHIN_OFFSET=$((MAX_FUTURE - 30))   # < borne, marge 30 s
BEYOND_OFFSET=$((MAX_FUTURE * 2))    # > borne, marge = la borne elle-même

kill_by_data() {   # kill_by_data <RHIZOME_DATA> — même technique que suite-pow.sh/bootstrap.sh :
                    # tous les nœuds partagent le même argv (binaire natif), seul RHIZOME_DATA
                    # distingue leur environnement.
  local pid; pid="$(ps -eo pid,args | grep "[r]hizome-node" | awk '{print $1}' \
    | while read -r p; do tr '\0' '\n' < "/proc/$p/environ" 2>/dev/null \
      | grep -q "RHIZOME_DATA=$1" && echo "$p"; done)"
  [[ -n "$pid" ]] && kill $pid 2>/dev/null
  sleep 2
}
wait_up() {   # wait_up <url> [timeout]
  local deadline=$((SECONDS + ${2:-90}))
  while (( SECONDS < deadline )); do [[ -n "$(node_stats "$1")" ]] && return 0; sleep 1; done
  return 1
}
wait_height_ge() {   # wait_height_ge <url> <n> [timeout]
  local deadline=$((SECONDS + ${3:-90})) h
  while (( SECONDS < deadline )); do
    h="$(json_get "$(node_stats "$1")" height)"
    [[ -n "$h" ]] && (( h >= $2 )) && return 0
    sleep 1
  done
  return 1
}

# REF : horloge RÉELLE, jamais mineur, jamais pairé — ne peut avancer que via /submit. C'est le
# JUGE de chaque cas (le rôle que suite-pow.sh donne à sa « victime »).
reset_ref() {
  kill_by_data "$REF_DATA"; rm -rf "$REF_DATA"
  setsid env RHIZOME_NETWORK="$NETWORK" RHIZOME_PORT="$REF_PORT" RHIZOME_DATA="$REF_DATA" \
    "$NODE_BIN" -Xmx256m > "$BASE_DIR/logs/clock-ref.log" 2>&1 < /dev/null &
  wait_up "$REF"
}

# SKEW : mineur solo, jamais pairé, chaîne RÉINITIALISÉE. `spec` vide = horloge réelle (mode
# repli : SKEW ne sert alors QUE de fournisseur de gabarit pour Anvil, comme la source de
# suite-pow.sh). FAKETIME_DONT_FAKE_MONOTONIC=1 : documenté par libfaketime lui-même comme requis
# pour les applications Java/JVM (sans quoi certaines attentes sur CLOCK_MONOTONIC peuvent se
# bloquer) — vérifié manuellement ici sans lui, le nœud restait opérationnel, mais on le garde
# par prudence : seule l'horloge MURALE compte pour la borne future, donc c'est sans coût.
reset_skew() {
  local spec=${1:-}
  kill_by_data "$SKEW_DATA"; rm -rf "$SKEW_DATA"
  launch_skew "$spec" > "$BASE_DIR/logs/clock-skew.log"
  wait_up "$SKEW"
}
# Redémarre SKEW SANS effacer ses données (persistance sous horloge toujours faussée — CLOCK-03).
restart_skew() {
  local spec=${1:-}
  kill_by_data "$SKEW_DATA"
  launch_skew "$spec" >> "$BASE_DIR/logs/clock-skew.log"
  wait_up "$SKEW" 60
}
launch_skew() {   # lance SKEW en arrière-plan ; stdout+stderr redirigés par l'appelant (>/>>)
  local spec=$1
  if [[ -n "$spec" ]]; then
    setsid env RHIZOME_NETWORK="$NETWORK" RHIZOME_PORT="$SKEW_PORT" RHIZOME_DATA="$SKEW_DATA" \
      RHIZOME_MINER="$SKEW_ADDR" RHIZOME_BLOCK_INTERVAL_MS=2000 \
      FAKETIME="$spec" LD_PRELOAD="$FAKETIME_LIB" FAKETIME_DONT_FAKE_MONOTONIC=1 \
      "$NODE_BIN" -Xmx256m 2>&1 < /dev/null &
  else
    setsid env RHIZOME_NETWORK="$NETWORK" RHIZOME_PORT="$SKEW_PORT" RHIZOME_DATA="$SKEW_DATA" \
      RHIZOME_MINER="$SKEW_ADDR" RHIZOME_BLOCK_INTERVAL_MS=2000 \
      "$NODE_BIN" -Xmx256m 2>&1 < /dev/null &
  fi
}

fetch_block_bytes() { curl -sf --max-time 10 "$1/sync?start=$2&end=$2" -o "$3"; }   # brut, self-framing

# Rejeu manuel exact, comme post_json mais pour un CORPS BINAIRE (/submit attend l'encodage
# BlockCodec, pas du JSON — voir Anvil.submit). Rend "code|statut", le contrat de post_json.
submit_block_bytes() {
  local node=$1 file=$2
  local auth=(); [[ -n "$SUITE_TOKEN" ]] && auth=(-H "Authorization: Bearer $SUITE_TOKEN")
  local code body_file; body_file="$BASE_DIR/.clock_submit_body.$$"
  code="$(curl -s --max-time 20 -o "$body_file" -w '%{http_code}' \
    -X POST -H 'Content-Type: application/octet-stream' -H 'X-Rhizome-Request: 1' \
    "${auth[@]}" "${CURL_TLS_OPTS[@]}" \
    --data-binary "@$file" "$(resolve_base_url "$node")/submit" 2>/dev/null || echo 000)"
  local body; body="$(cat "$body_file" 2>/dev/null)"; rm -f "$body_file"
  printf '%s|%s' "$code" "$(json_get "$body" status)"
}

# Fait tourner UN scénario complet : REF frais, SKEW frais sous horloge décalée de `offset`
# secondes, attend son bloc #2. Fatal (exit 1) si REF/SKEW ne démarrent pas du tout : comme
# `reset_victim || exit 1` dans suite-pow.sh, la paire isolée est une PRÉCONDITION de la
# batterie, pas un cas dont le verdict se discute.
#
# Piège vécu en écrivant cette batterie : un premier jet appelait cette fonction via `$(...)`
# pour récupérer le "code|statut" du rejeu. `$(...)` FORKE toujours un sous-shell — toute
# variable globale posée À L'INTÉRIEUR (le décalage mesuré, la prémisse) disparaissait donc au
# retour ("unbound variable" sous `set -u`), et tout `record`/echo émis depuis l'intérieur se
# serait en plus retrouvé CAPTURÉ dans la valeur de retour au lieu de s'afficher. D'où : jamais
# de `$(...)` autour de `skew_case` — elle est appelée comme un ÉNONCÉ ordinaire et communique
# tout par ces trois variables globales, jamais par stdout.
SKEW_CASE_RESULT=""; SKEW_CASE_DELTA=""; SKEW_CASE_OK=0
skew_case() {   # skew_case <décalage-s>
  local offset=$1
  reset_ref || { echo "REF injoignable" >&2; exit 1; }
  reset_skew "+${offset}s" || { echo "SKEW (+${offset}s) injoignable" >&2; exit 1; }
  SKEW_CASE_RESULT="000|"; SKEW_CASE_DELTA=""; SKEW_CASE_OK=0
  if ! wait_height_ge "$SKEW" 2 90; then
    return
  fi
  local real_now ts_ms
  real_now="$(date +%s)"
  ts_ms="$(json_get "$(curl -sf --max-time 5 "$SKEW/block?blockId=2" 2>/dev/null)" timestamp)"
  SKEW_CASE_DELTA=$(( ts_ms / 1000 - real_now ))
  if (( SKEW_CASE_DELTA >= offset - 20 && SKEW_CASE_DELTA <= offset + 40 )); then SKEW_CASE_OK=1; fi
  local blk="$BASE_DIR/.clock-blk.$$"
  if fetch_block_bytes "$SKEW" 2 "$blk"; then
    SKEW_CASE_RESULT="$(submit_block_bytes "$REF" "$blk")"
    rm -f "$blk"
  fi
}

echo "=== CLOCK : borne future (maxFutureBlockTimeSec=${MAX_FUTURE}s) contre un processus réel ==="

if [[ -n "$FAKETIME_LIB" ]]; then

  # --- Côté accepté : l'horloge de SKEW tourne en avance mais reste DANS la marge -------------
  skew_case "$WITHIN_OFFSET"
  # Preuve de PRÉMISSE : sans elle, un CLOCK-02 « ACCEPTÉ » ne prouverait rien — il faut d'abord
  # savoir si faketime a VRAIMENT décalé l'horloge du processus, ou s'il a échoué en silence
  # (auquel cas le bloc porterait une heure quasi réelle, acceptée pour une tout autre raison).
  # Marge large (mine + démarrage), pas une tolérance serrée.
  if (( SKEW_CASE_OK )); then
    record CLOCK-01 PASS "horloge SKEW décalée de ${SKEW_CASE_DELTA}s (visé +${WITHIN_OFFSET}s) — faketime agit bien sur le PROCESSUS"
  else
    record CLOCK-01 FAIL "décalage observé ${SKEW_CASE_DELTA:-<vide>}s, attendu ~+${WITHIN_OFFSET}s — CLOCK-02 est ininterprétable"
  fi
  expect_reject CLOCK-02 SUCCESS 200 "$SKEW_CASE_RESULT" \
    "bloc RÉELLEMENT miné par un processus dont l'horloge est avancée de +${WITHIN_OFFSET}s (< borne ${MAX_FUTURE}s)"

  # --- CLOCK-03 : SKEW survit à un redémarrage SOUS horloge toujours décalée -------------------
  # Reprend le MÊME spec ("+${WITHIN_OFFSET}s", relatif au réel au moment du relancement). SKEW
  # est un mineur VIVANT (contrairement à la victime de suite-pow.sh, pacée exclusivement par
  # Anvil) : son TIP avance en continu, donc le comparer avant/après serait une course perdue
  # d'avance. On compare à la place le hash du bloc #2 — déjà miné, déjà immuable — pour
  # vérifier que RocksDB le reconstruit bit pour bit après redémarrage sous horloge toujours
  # faussée.
  H2_BEFORE="$(json_get "$(curl -sf --max-time 5 "$SKEW/block?blockId=2" 2>/dev/null)" hash)"
  if [[ -n "$H2_BEFORE" ]] && restart_skew "+${WITHIN_OFFSET}s"; then
    expect_eq CLOCK-03 "$H2_BEFORE" \
      "$(json_get "$(curl -sf --max-time 5 "$SKEW/block?blockId=2" 2>/dev/null)" hash)" \
      "RocksDB reconstruit le même bloc #2 après redémarrage sous horloge toujours faussée"
  else
    record CLOCK-03 FAIL "SKEW n'a jamais redémarré, ou n'avait pas de bloc #2 avant (voir $BASE_DIR/logs/clock-skew.log)"
  fi

  # --- CLOCK-04 : la surface HTTP (ActiveJ) reste réactive sous horloge décalée -----------------
  T0=$(date +%s%3N)
  RESP="$(node_stats "$SKEW")"
  T1=$(date +%s%3N)
  if [[ -n "$RESP" ]]; then
    expect_le CLOCK-04 5000 "$((T1 - T0))" "latence /stats sous horloge décalée (ActiveJ non bloqué par le décalage)"
  else
    record CLOCK-04 FAIL "aucune réponse de SKEW après redémarrage (voir $BASE_DIR/logs/clock-skew.log)"
  fi

  # --- Côté refusé : l'horloge de SKEW tourne largement AU-DELÀ de la marge --------------------
  skew_case "$BEYOND_OFFSET"
  if (( SKEW_CASE_OK )); then
    record CLOCK-05 PASS "horloge SKEW décalée de ${SKEW_CASE_DELTA}s (visé +${BEYOND_OFFSET}s) — faketime agit bien sur le PROCESSUS"
  else
    record CLOCK-05 FAIL "décalage observé ${SKEW_CASE_DELTA:-<vide>}s, attendu ~+${BEYOND_OFFSET}s — CLOCK-06 est ininterprétable"
  fi
  expect_reject CLOCK-06 BLOCK_TIMESTAMP_IN_FUTURE 400 "$SKEW_CASE_RESULT" \
    "bloc RÉELLEMENT miné par un processus dont l'horloge est avancée de +${BEYOND_OFFSET}s (> borne ${MAX_FUTURE}s)"
  # Le refus doit être GRATUIT : REF n'a pas bougé et n'est pas dégradé — même contrat que
  # POW-CTRL-05 dans suite-pow.sh.
  expect_eq CLOCK-07 "1|degraded=null reorg=false" \
    "$(json_get "$(node_stats "$REF")" height)|$(node_healthy "$REF")" \
    "REF reste à la genèse, non dégradé, après le refus"

else
  # --- Repli : ni faketime système, ni extraction locale possible (pas de réseau) --------------
  # On documente honnêtement la moitié NON couverte plutôt que de la faire disparaître.
  record CLOCK-SKIP-VICTIM METRIC \
    "SKIP — faketime indisponible sur cette machine : la moitié « horloge de PROCESSUS en avance » du scénario (TIME-06, côté victime) n'est PAS exercée ici. Voir ClockDriftAttackTest (JUnit) pour la preuve au niveau composant."

  # Ce qui reste sûr sans toucher aucune horloge : un bloc VRAIMENT miné dont le SEUL champ altéré
  # est l'horodatage déclaré (côté attaquant), soumis à un vrai nœud. SKEW sert alors juste de
  # fournisseur de gabarit pour Anvil — exactement le rôle de la « source » dans suite-pow.sh —
  # via son propre nœud, sans dépendre de la paire externe 4406/4407.
  ANVIL_SRC="$ROOT/scripts/local-testnet/tools/Anvil.java"
  NODE_LIB="$ROOT/app-node/build/install/app-node/lib/*"
  anvil_build() {
    ensure_jdk25
    if [[ ! -f "$TOOLS_DIR/Anvil.class" || "$ANVIL_SRC" -nt "$TOOLS_DIR/Anvil.class" ]]; then
      mkdir -p "$TOOLS_DIR"
      "$JAVA_HOME/bin/javac" -cp "$NODE_LIB" -d "$TOOLS_DIR" "$ANVIL_SRC" || return 1
    fi
  }
  anvil() {   # même forme que suite-pow.sh : Anvil imprime "<code> <corps-JSON>", et le
              # STATUT applicatif est un CHAMP de ce corps, pas le texte brut après le code.
    local out; out="$("$JAVA_HOME/bin/java" -cp "$TOOLS_DIR:$NODE_LIB" Anvil \
        --source "$SKEW" --url "$REF" --network "$NETWORK" --quiet "$@" 2>&1 | tail -1)"
    local code="${out%% *}" body="${out#* }"
    printf '%s|%s' "$code" "$(json_get "$body" status)"
  }

  if anvil_build && reset_ref && reset_skew ""; then
    expect_reject CLOCK-FB-00 SUCCESS 200 "$(anvil)" \
      "témoin : rejeu honnête d'un bloc SKEW (horloge réelle ici, faketime absent)"
    expect_reject CLOCK-FB-01 SUCCESS 200 "$(anvil --ts-offset $(( (MAX_FUTURE - 20) * 1000 )))" \
      "horodatage FORGÉ dans la fenêtre future (+$((MAX_FUTURE - 20))s < ${MAX_FUTURE}s) — processus non décalé, seul le CHAMP l'est"
    expect_reject CLOCK-FB-02 BLOCK_TIMESTAMP_IN_FUTURE 400 "$(anvil --ts-offset $(( (MAX_FUTURE + 20) * 1000 )))" \
      "horodatage FORGÉ au-delà de la fenêtre future (+$((MAX_FUTURE + 20))s > ${MAX_FUTURE}s)"
  else
    record CLOCK-FB-00 FAIL "impossible de monter le repli (Anvil, ou REF/SKEW injoignables)"
    record CLOCK-FB-01 FAIL "non évalué"
    record CLOCK-FB-02 FAIL "non évalué"
  fi
fi

suite_summary
