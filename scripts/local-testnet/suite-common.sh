#!/usr/bin/env bash
# Socle commun des batteries de scénarios (suite-tx / suite-wallet / suite-contract / suite-api).
#
# Chaque batterie est une liste de cas nommés d'après le catalogue de la revue adverse
# (docs/adversarial/spec.md) : l'identifiant porte la famille, de sorte qu'un PASS ici se lit
# comme « la preuve composant du catalogue tient aussi sur un nœud vivant ». Un cas ne vaut que
# s'il vérifie le STATUT EXACT du rejet (pas un « ça a échoué » générique) : c'est ce qui
# distingue une porte de consensus atteinte d'un corps mal formé refusé avant elle.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
# common.sh pose `set -e` : c'est le bon réglage pour start/stop, le mauvais ici. Une batterie
# doit exécuter TOUS ses cas — un `grep` sans correspondance ou un wallet qui refuse (ce qui est
# précisément ce que la moitié des cas provoque) tuerait la campagne au milieu et masquerait les
# cas suivants. Chaque cas porte son propre verdict ; l'échec se lit dans le récapitulatif.
set +e

RESULTS_DIR="$BASE_DIR/results"
mkdir -p "$RESULTS_DIR"
# `start.sh` creates `$BASE_DIR/logs` for the main campaign, but a suite run standalone against
# an externally-launched network (no start.sh in the loop — e.g. staging-rehearsal.sh) never gets
# it: `refresh_junk` (suite-dos.sh) and the isolated-pair helpers (suite-pow.sh/suite-bootstrap.sh)
# write there unconditionally and fail on ENOENT the first time a log line is appended. Idempotent
# either way, so this costs nothing when start.sh already made the directory.
mkdir -p "$BASE_DIR/logs"
SUITE_NAME="${SUITE_NAME:-suite}"
RESULT_FILE="$RESULTS_DIR/$SUITE_NAME.tsv"
: > "$RESULT_FILE"
PASS_COUNT=0
FAIL_COUNT=0

# --- forgeur de transactions signées -----------------------------------------------------
# Le wallet CLI refuse côté client ce qu'une attaque doit produire ; le forgeur signe
# exactement ce qu'on lui demande. Compilé à la demande, une seule fois par répertoire de
# testnet.
TOOLS_DIR="$BASE_DIR/tools"
FORGE_SRC="$ROOT/scripts/local-testnet/tools/Forge.java"
WALLET_LIB="$ROOT/app-wallet/build/install/app-wallet/lib/*"

forge_build() {
  if [[ ! -f "$TOOLS_DIR/Forge.class" || "$FORGE_SRC" -nt "$TOOLS_DIR/Forge.class" ]]; then
    mkdir -p "$TOOLS_DIR"
    javac -cp "$WALLET_LIB" -d "$TOOLS_DIR" "$FORGE_SRC"
  fi
}

forge() { java -cp "$TOOLS_DIR:$WALLET_LIB" Forge "$@"; }

# --- HTTP --------------------------------------------------------------------------------
# SUITE_TOKEN : jeton porteur optionnel (RHIZOME_API_TOKEN côté nœud). Vide par défaut, pour ne
# rien changer aux batteries actuelles — elles parlent toutes à des nœuds sans jeton configuré.
# CURL_TLS_OPTS : options curl supplémentaires pour un nœud derrière un relais TLS (ex.
# `CURL_TLS_OPTS=(--cacert /path/to/ca.pem)`). Volontairement PAS de `-k` ici ni ailleurs : une
# batterie qui veut prouver que TLS est appliqué a besoin qu'un certificat invalide reste refusé.
SUITE_TOKEN="${SUITE_TOKEN:-}"
[[ -v CURL_TLS_OPTS ]] || CURL_TLS_OPTS=()

# Réponse sous la forme "<code>|<corps>" : les cas vérifient les deux (un 400 portant le mauvais
# statut ne prouve rien). `node` accepte un index (résolu via node_url, comme avant) ou une URL
# littérale ("http://"/"https://"), auquel cas elle est utilisée telle quelle — voir
# resolve_base_url (common.sh).
post_json() {
  local node=$1 path=$2 body=$3
  local auth=(); [[ -n "$SUITE_TOKEN" ]] && auth=(-H "Authorization: Bearer $SUITE_TOKEN")
  curl -s --max-time 20 -o /tmp/.rz_body.$$ -w '%{http_code}' \
    -X POST -H 'Content-Type: application/json' -H 'X-Rhizome-Request: 1' \
    "${auth[@]}" "${CURL_TLS_OPTS[@]}" \
    --data-binary "$body" "$(resolve_base_url "$node")$path" 2>/dev/null || echo 000
  printf '|'
  cat /tmp/.rz_body.$$ 2>/dev/null || true
  rm -f /tmp/.rz_body.$$
}

get_json() {
  local auth=(); [[ -n "$SUITE_TOKEN" ]] && auth=(-H "Authorization: Bearer $SUITE_TOKEN")
  curl -s --max-time 20 -H 'X-Rhizome-Request: 1' "${auth[@]}" "${CURL_TLS_OPTS[@]}" \
    "$(resolve_base_url "$1")$2" 2>/dev/null || true
}

# Statut applicatif d'une soumission ("SUCCESS", "INVALID_SIGNATURE", ...). Vide si absent.
submit_tx() {
  local node=$1 json=$2
  local r; r="$(post_json "$node" /add_transaction_json "$json")"
  local code="${r%%|*}" body="${r#*|}"
  printf '%s|%s' "$code" "$(json_get "$body" status)"
}

# --- état du portefeuille ------------------------------------------------------------------
wallet_field() {
  local node=$1 addr=$2 field=$3
  json_get "$(get_json "$node" "/wallet?address=$addr")" "$field"
}
balance_units() { wallet_field "$1" "$2" balance; }
next_nonce()    { wallet_field "$1" "$2" nextNonce; }
addr_of()       { "$WALLET_BIN" address "$1"; }

height_of() { json_get "$(node_stats "$1")" height; }

# Attend l'avance de `n` blocs sur le nœud `node` (défaut 1) : une transaction n'est confirmée
# qu'une fois minée, et la cadence est bruitée — jamais d'assertion sur une durée absolue.
wait_blocks() {
  local node=${1:-0} n=${2:-1} timeout=${3:-180}
  local start; start="$(height_of "$node")"
  [[ -z "$start" ]] && return 1
  local deadline=$((SECONDS + timeout)) h
  while (( SECONDS < deadline )); do
    h="$(height_of "$node")"
    [[ -n "$h" ]] && (( h >= start + n )) && return 0
    sleep 1
  done
  return 1
}

# Attend qu'un nonce avance (transaction réellement minée, pas seulement admise au mempool).
wait_nonce_advance() {
  local node=$1 addr=$2 from=$3 timeout=${4:-180}
  local deadline=$((SECONDS + timeout)) n
  while (( SECONDS < deadline )); do
    n="$(next_nonce "$node" "$addr")"
    [[ -n "$n" ]] && (( n > from )) && return 0
    sleep 2
  done
  return 1
}

# Attend qu'un solde atteigne un plancher (dotation depuis les mineurs).
wait_balance() {
  local node=$1 addr=$2 min=$3 timeout=${4:-240}
  local deadline=$((SECONDS + timeout)) b
  while (( SECONDS < deadline )); do
    b="$(balance_units "$node" "$addr")"
    [[ -n "$b" ]] && (( b >= min )) && return 0
    sleep 2
  done
  return 1
}

# Dote `addr` depuis les mineurs (chaque mineur envoie `pdn` PDN depuis son propre nœud).
fund_from_miners() {
  local addr=$1 pdn=${2:-20} m
  for m in "${MINERS[@]}"; do
    "$WALLET_BIN" send "$(node_url "$m")" "$KEYS_DIR/miner-$m.key" "$addr" "$pdn" >/dev/null 2>&1 || true
  done
}

# --- exécution à durée fixe ------------------------------------------------------------------
# Contrairement aux wait_* ci-dessus (qui guettent une condition avec un plafond), `run_for`
# tourne pendant une durée FIXE : utile pour « laisser le réseau vivre N secondes puis constater »,
# ce qu'aucun wait_* ne couvre. Dort par petits pas — jamais un seul `sleep $seconds` — pour
# rester réactif à un signal et pouvoir journaliser une pulsation régulière via `heartbeat_fn`
# (appelée PAR SON NOM, avec le nombre de secondes restantes, à chaque incrément).
#
# Piège de campagne visé : un `run_for` lancé sous le délai global de run-campaign.sh, tué au
# milieu par ce délai, laisserait orphelin tout ce qu'il surveille si le signal n'était pas
# intercepté — d'où le piège TERM/INT, retiré avant de rendre la main pour ne pas polluer le
# reste de la batterie (un trap qui survit à la fonction interférerait avec le nettoyage propre
# des cas suivants).
run_for() {
  local seconds=$1 heartbeat_fn=${2:-} step=3
  local interrupted=0
  trap 'interrupted=1' TERM INT
  local deadline=$((SECONDS + seconds)) remaining
  while (( SECONDS < deadline && ! interrupted )); do
    remaining=$((deadline - SECONDS))
    [[ -n "$heartbeat_fn" ]] && "$heartbeat_fn" "$remaining"
    sleep "$(( remaining < step ? remaining : step ))"
  done
  trap - TERM INT
  (( interrupted )) && return 1
  return 0
}

# --- verdicts -------------------------------------------------------------------------------
# Horodatage du DERNIER `record` : la colonne duration_ms d'une ligne est l'écart avec la ligne
# précédente, une approximation du temps qu'a pris CE cas (montage compris) sans obliger tous les
# appelants existants à mesurer et passer une durée explicite — record() garde sa signature.
_RZ_LAST_RECORD_MS="$(date +%s%3N)"

record() {
  local id=$1 verdict=$2 detail=$3
  local now_ms; now_ms="$(date +%s%3N)"
  local duration_ms=$((now_ms - _RZ_LAST_RECORD_MS))
  _RZ_LAST_RECORD_MS=$now_ms
  local ts; ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  # Colonnes 4/5 ajoutées en QUEUE : run-campaign.sh compte via `\tPASS\t` / `\tFAIL\t`, un motif
  # ancré sur la colonne verdict (2) qui ne regarde jamais au-delà — l'ajout ne le perturbe pas.
  printf '%s\t%s\t%s\t%s\t%s\n' "$id" "$verdict" "$detail" "$ts" "$duration_ms" >> "$RESULT_FILE"
  case "$verdict" in
    PASS)
      PASS_COUNT=$((PASS_COUNT + 1)); printf '  \033[32mPASS\033[0m %-28s %s\n' "$id" "$detail" ;;
    METRIC)
      # Ni PASS ni FAIL : une mesure n'est pas un verdict. Ne compte dans aucun des deux totaux.
      printf '  \033[36mMETRIC\033[0m %-26s %s\n' "$id" "$detail" ;;
    *)
      FAIL_COUNT=$((FAIL_COUNT + 1)); printf '  \033[31mFAIL\033[0m %-28s %s\n' "$id" "$detail" ;;
  esac
}

# Cas nominal : la valeur observée doit égaler l'attendue.
expect_eq() {
  local id=$1 expected=$2 actual=$3 note=${4:-}
  if [[ "$actual" == "$expected" ]]; then
    record "$id" PASS "${note:+$note — }$actual"
  else
    record "$id" FAIL "${note:+$note — }attendu '$expected', obtenu '$actual'"
  fi
}

# Cas de rejet : le statut applicatif exact ET le code HTTP attendu.
expect_reject() {
  local id=$1 expected_status=$2 expected_code=$3 result=$4 note=${5:-}
  local code="${result%%|*}" status="${result#*|}"
  if [[ "$status" == "$expected_status" && "$code" == "$expected_code" ]]; then
    record "$id" PASS "${note:+$note — }$code $status"
  else
    record "$id" FAIL "${note:+$note — }attendu $expected_code $expected_status, obtenu $code ${status:-<vide>}"
  fi
}

expect_contains() {
  local id=$1 needle=$2 haystack=$3 note=${4:-}
  if [[ "$haystack" == *"$needle"* ]]; then
    record "$id" PASS "${note:+$note — }contient '$needle'"
  else
    record "$id" FAIL "${note:+$note — }'$needle' absent de: $(printf '%.160s' "$haystack")"
  fi
}

# Cas de rejet allégé : seul le code HTTP compte (pas le statut applicatif combiné que
# expect_reject exige) — utile quand le corps n'a pas de "status" exploitable (404 générique,
# limite de débit, ...). `result` a la même forme "<code>|<corps-ou-statut>" que post_json rend ;
# seule la partie avant le premier '|' est regardée.
expect_code() {
  local id=$1 expected_code=$2 result=$3 note=${4:-}
  local code="${result%%|*}"
  if [[ "$code" == "$expected_code" ]]; then
    record "$id" PASS "${note:+$note — }$code"
  else
    record "$id" FAIL "${note:+$note — }attendu $expected_code, obtenu ${code:-<vide>}"
  fi
}

# Bornes numériques (entières). `actual` non numérique est un FAIL explicite plutôt qu'une
# comparaison bash silencieusement fausse.
expect_ge() {
  local id=$1 min=$2 actual=$3 note=${4:-}
  if [[ "$actual" =~ ^-?[0-9]+$ && "$actual" -ge "$min" ]]; then
    record "$id" PASS "${note:+$note — }$actual ≥ $min"
  else
    record "$id" FAIL "${note:+$note — }attendu ≥ $min, obtenu '${actual:-<vide>}'"
  fi
}

expect_le() {
  local id=$1 max=$2 actual=$3 note=${4:-}
  if [[ "$actual" =~ ^-?[0-9]+$ && "$actual" -le "$max" ]]; then
    record "$id" PASS "${note:+$note — }$actual ≤ $max"
  else
    record "$id" FAIL "${note:+$note — }attendu ≤ $max, obtenu '${actual:-<vide>}'"
  fi
}

# Enregistre une MESURE, pas un verdict : ni PASS ni FAIL, ne compte dans aucun des deux totaux
# (voir record()) — pour des grandeurs qu'une batterie veut publier dans le TSV (débit, cadence
# observée, ...) sans prétendre juger d'un seuil.
record_metric() {
  local id=$1 value=$2 unit=${3:-} note=${4:-}
  record "$id" METRIC "${value}${unit:+ $unit}${note:+ — $note}"
}

suite_summary() {
  echo "---"
  printf '%s: %d PASS, %d FAIL (%s)\n' "$SUITE_NAME" "$PASS_COUNT" "$FAIL_COUNT" "$RESULT_FILE"
  (( FAIL_COUNT == 0 ))
}

# Santé du nœud victime après une batterie : le refus doit être GRATUIT (le nœud continue à
# miner, l'attaquant n'est pas puni, aucun mode dégradé).
node_healthy() {
  local node=$1 s; s="$(node_stats "$node")"
  local deg; deg="$(json_get "$s" degraded)"
  local reorg; reorg="$(json_get "$s" reorgInProgress)"
  printf 'degraded=%s reorg=%s' "${deg:-null}" "${reorg:-?}"
}
