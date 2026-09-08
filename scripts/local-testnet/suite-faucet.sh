#!/usr/bin/env bash
# Batterie « faucet » — le service autonome scripts/local-testnet/faucet/faucet.py, contre un
# nœud VIVANT du testnet local.
#
# Contrairement aux autres batteries de ce répertoire, elle n'est PAS ancrée dans
# docs/adversarial/spec.md : le faucet n'est pas une règle de consensus, c'est un service
# opérationnel ajouté à côté du nœud (délibérément PAS une route de app-node, PAS une page du
# dashboard — voir l'en-tête de faucet.py pour le choix de conception). Elle en garde la forme —
# un cas, un verdict, le STATUT HTTP EXACT vérifié, jamais un « ça a échoué » générique — parce
# que c'est la forme qui distingue ici aussi « la porte anti-abus a tenu » de « la requête a été
# refusée avant même de l'atteindre ».
#
# Ce que chaque cas prouve :
#   FAUCET-01     /challenge répond avec un nonce et une difficulté exploitables
#   FAUCET-02/03  une entrée mal formée (adresse invalide, défi non résolu/inconnu) est refusée
#                 en 400 SANS jamais invoquer le CLI wallet — la preuve est le budget quotidien
#                 inchangé, pas seulement le code HTTP
#   FAUCET-04     le chemin nominal : /challenge -> résolution -> /drip crédite RÉELLEMENT
#                 l'adresse sur la chaîne (vérifié en lisant le solde depuis le nœud)
#   FAUCET-05     la MÊME adresse, resollicitée immédiatement, est refusée en 429 (cooldown)
#   FAUCET-06     le budget quotidien épuisé refuse une AUTRE adresse en 503, sans soumission
#   FAUCET-07     un redémarrage du service PRÉSERVE le cooldown (l'état persiste sur disque,
#                 pas seulement en mémoire) — la preuve qu'on ne peut pas vider le faucet en le
#                 redémarrant
#   FAUCET-08     un nœud injoignable fait échouer /drip proprement en 503 : le service reste
#                 vivant et répond encore, il ne se bloque pas et ne perd pas la requête en
#                 silence
#   HYG-*         aucune clé — ni la vraie clé de staging, ni la clé jetable que CETTE batterie
#                 génère elle-même — ne finit dans git ; la clé jetable reste hors du dépôt
#                 (répertoire $BASE_DIR, déjà ignoré) et son fichier est 0600
#
# La clé du faucet utilisée ici est JETABLE, générée par cette batterie (--plaintext, comme les
# clés de mineur de start.sh) et financée depuis les mineurs du devnet — jamais la vraie clé
# HOT/COLD de staging (voir scripts/local-testnet/faucet/README.md : cette dernière ne doit
# jamais apparaître sur un chemin automatisé).
#
# Usage : suite-faucet.sh [nœud] (défaut 0)
set -uo pipefail
SUITE_NAME=faucet
source "$(dirname "$0")/suite-common.sh"

NODE=${1:-0}
LAB="$BASE_DIR/faucet-lab"          # bac à sable : clé jetable + état persisté, jamais dans keys/
rm -rf "$LAB"; mkdir -p "$LAB"

FAUCET_PY="$ROOT/scripts/local-testnet/faucet/faucet.py"
FAUCET_KEY="$LAB/faucet.key"
FAUCET_LOG="$LAB/faucet.log"

# Ports dédiés à cette batterie : NODE_PORT+95 (instance principale) et +96 (instance pointée
# sur un nœud injoignable, cas FAUCET-08) — hors de la plage que start.sh attribue aux nœuds du
# testnet et de celles que suite-net.sh/suite-tls.sh réservent déjà (base+91/92, base+9xxx TLS).
FAUCET_PORT=$((BASE_PORT + 95))
FAUCET_URL="http://127.0.0.1:$FAUCET_PORT"
FAUCET_STATE="$LAB/state.json"

UNREACH_PORT=$((BASE_PORT + 96))
UNREACH_URL="http://127.0.0.1:$UNREACH_PORT"
UNREACH_STATE="$LAB/state-unreachable.json"

# Difficulté PoW volontairement BASSE pour que la batterie reste rapide (~2^10 essais, quelques
# dizaines de ms en Python) — un vrai déploiement public utilise le défaut de faucet.py (18
# bits). Budget quotidien fixé pile à une dotation : le second drip DOIT être refusé, quelle que
# soit l'adresse visée, et c'est précisément ce que FAUCET-06 vérifie.
DRIP_PDN="1"
COOLDOWN_SECONDS=600
POW_BITS=10

FAUCET_PID=""
UNREACH_PID=""
cleanup() {
  stop_faucet "$FAUCET_PID"
  stop_faucet "$UNREACH_PID"
  rm -rf "$LAB"
}
trap cleanup EXIT

stop_faucet() {
  local pid=$1
  [[ -z "$pid" ]] && return 0
  kill -TERM "$pid" 2>/dev/null
  local waited=0
  while kill -0 "$pid" 2>/dev/null && (( waited < 20 )); do sleep 0.5; waited=$((waited + 1)); done
  kill -0 "$pid" 2>/dev/null && kill -KILL "$pid" 2>/dev/null
  return 0
}

# Lance une instance de faucet.py, toute sa configuration passée en variables d'environnement
# (comme le ferait un déploiement réel, cf. README.md) ; imprime son PID.
start_faucet() {
  local state=$1 port=$2 node_url=$3 budget_pdn=$4
  RHIZOME_FAUCET_KEY_FILE="$FAUCET_KEY" \
  RHIZOME_FAUCET_NODE_URL="$node_url" \
  RHIZOME_FAUCET_STATE_FILE="$state" \
  RHIZOME_FAUCET_WALLET_BIN="$WALLET_BIN" \
  RHIZOME_FAUCET_HOST="127.0.0.1" \
  RHIZOME_FAUCET_PORT="$port" \
  RHIZOME_FAUCET_DRIP_PDN="$DRIP_PDN" \
  RHIZOME_FAUCET_FEE_PDN="0" \
  RHIZOME_FAUCET_COOLDOWN_SECONDS="$COOLDOWN_SECONDS" \
  RHIZOME_FAUCET_DAILY_BUDGET_PDN="$budget_pdn" \
  RHIZOME_FAUCET_POW_DIFFICULTY_BITS="$POW_BITS" \
  RHIZOME_FAUCET_NODE_PROBE_TIMEOUT_SECONDS="3" \
    "$PY" "$FAUCET_PY" >> "$FAUCET_LOG" 2>&1 &
  echo $!
}

wait_faucet_up() {
  local url=$1 timeout=${2:-30} deadline
  deadline=$((SECONDS + timeout))
  while (( SECONDS < deadline )); do
    curl -sf --max-time 2 "$url/status" >/dev/null 2>&1 && return 0
    sleep 0.5
  done
  return 1
}

# Résout un défi hashcash EXACTEMENT comme le fait le JS embarqué dans GET / (voir
# faucet.py:INDEX_HTML) et le serveur lui-même (leading_zero_bits(sha256(nonce+":"+solution))).
# Réimplémenté ici plutôt qu'invoqué en pilotant un navigateur : la batterie n'a pas de moteur
# JS, et c'est le MÊME calcul, donc une preuve tout aussi valable que le résoudre client-side.
solve_pow() {
  local nonce=$1 difficulty=$2
  "$PY" -c '
import hashlib, sys
nonce, difficulty = sys.argv[1], int(sys.argv[2])
i = 0
while True:
    i += 1
    d = hashlib.sha256(f"{nonce}:{i}".encode()).digest()
    bits = 0
    for b in d:
        if b == 0:
            bits += 8
            continue
        bits += 8 - b.bit_length()
        break
    if bits >= difficulty:
        print(i)
        break
' "$nonce" "$difficulty"
}

# "<nonce> <difficultyBits>" — un défi frais depuis l'instance `url`.
faucet_challenge() {
  local url=$1 resp
  resp="$(get_json "$url" /challenge)"
  printf '%s %s' "$(json_get "$resp" nonce)" "$(json_get "$resp" difficultyBits)"
}

# Réponse "<code>|<corps>", même convention que post_json (suite-common.sh) — url accepte un
# index OU une URL littérale, comme resolve_base_url.
drip() {
  local url=$1 address=$2 nonce=$3 solution=$4 body
  body="$("$PY" -c 'import json,sys; print(json.dumps({"address": sys.argv[1], "nonce": sys.argv[2], "solution": sys.argv[3]}))' \
    "$address" "$nonce" "$solution")"
  post_json "$url" /drip "$body"
}

# Défi résolu + /drip en un appel : le cas nominal de bout en bout pour `address` sur `url`.
solved_drip() {
  local url=$1 address=$2
  local ch nonce diff sol
  ch="$(faucet_challenge "$url")"
  nonce="${ch% *}"; diff="${ch#* }"
  sol="$(solve_pow "$nonce" "$diff")"
  drip "$url" "$address" "$nonce" "$sol"
}

# "<code>|<status>" — même convention que submit_tx (suite-common.sh) : extrait le champ JSON
# "status" du corps AVANT de le rendre, pour que expect_reject compare le champ, pas le corps
# entier. drip() brut ("<code>|<corps>") reste ce qu'utilisent les cas de refus (expect_code ne
# regarde que le code, il n'a pas besoin de ce parsing).
solved_drip_status() {
  local url=$1 address=$2
  local r; r="$(solved_drip "$url" "$address")"
  local code="${r%%|*}" body="${r#*|}"
  printf '%s|%s' "$code" "$(json_get "$body" status)"
}

daily_spent() { json_get "$(get_json "$1" /status)" dailyBudgetSpentBaseUnits; }

echo "== préparatifs : clé jetable, financée depuis les mineurs devnet =="
"$WALLET_BIN" keygen "$FAUCET_KEY" --plaintext >/dev/null
FAUCET_ADDR="$(addr_of "$FAUCET_KEY")"
fund_from_miners "$FAUCET_ADDR" 20
wait_balance "$NODE" "$FAUCET_ADDR" 100000 300 || { echo "dotation du faucet impossible" >&2; exit 1; }
record FAUCET-00-funded PASS "clé jetable $FAUCET_ADDR financée ($(balance_units "$NODE" "$FAUCET_ADDR") u)"

# Trois adresses de réception JETABLES, distinctes de la clé du faucet, pour ne jamais faire
# dépendre deux cas l'un de l'autre via un cooldown ou un solde partagé.
"$WALLET_BIN" keygen "$LAB/r1.key" --plaintext >/dev/null
"$WALLET_BIN" keygen "$LAB/r2.key" --plaintext >/dev/null
"$WALLET_BIN" keygen "$LAB/r3.key" --plaintext >/dev/null
R1="$(addr_of "$LAB/r1.key")"; R2="$(addr_of "$LAB/r2.key")"; R3="$(addr_of "$LAB/r3.key")"

echo
echo "== lancement du service faucet =="
FAUCET_PID="$(start_faucet "$FAUCET_STATE" "$FAUCET_PORT" "$(node_url "$NODE")" "$DRIP_PDN")"
if wait_faucet_up "$FAUCET_URL" 30; then
  record FAUCET-START PASS "faucet.py lancé (pid $FAUCET_PID, $FAUCET_URL)"
else
  record FAUCET-START FAIL "faucet.py n'a jamais répondu à /status — voir $FAUCET_LOG"
  suite_summary; exit 1
fi

echo
echo "== FAUCET-01 — un défi exploitable =="
ch="$(faucet_challenge "$FAUCET_URL")"
CHAL_NONCE="${ch% *}"; CHAL_DIFF="${ch#* }"
if [[ -n "$CHAL_NONCE" && "$CHAL_DIFF" =~ ^[0-9]+$ ]]; then
  record FAUCET-01-challenge PASS "nonce=$CHAL_NONCE difficultyBits=$CHAL_DIFF"
else
  record FAUCET-01-challenge FAIL "réponse inexploitable: '$ch'"
fi

echo
echo "== FAUCET-02/03 — entrée mal formée : refusée AVANT toute soumission =="
spent_before="$(daily_spent "$FAUCET_URL")"

# Adresse à somme de contrôle invalide, avec un défi par ailleurs correctement résolu — le refus
# doit venir de la validation d'adresse, pas du PoW.
ch="$(faucet_challenge "$FAUCET_URL")"; nonce="${ch% *}"; diff="${ch#* }"
sol="$(solve_pow "$nonce" "$diff")"
bad_addr="$(printf '%s' "$R1" | sed 's/.\{2\}$/00/')"
expect_code FAUCET-02-bad-address 400 "$(drip "$FAUCET_URL" "$bad_addr" "$nonce" "$sol")" \
  "adresse à somme de contrôle invalide"

# Nonce jamais émis par le serveur : refusé quelle que soit la "solution" fournie.
expect_code FAUCET-03-unknown-challenge 400 "$(drip "$FAUCET_URL" "$R1" "deadbeef0011deadbeef0011deadbeef" "1")" \
  "défi inconnu du serveur"

spent_after="$(daily_spent "$FAUCET_URL")"
expect_eq FAUCET-02-03-no-spend "$spent_before" "$spent_after" \
  "budget quotidien inchangé après les deux refus (CLI wallet jamais invoqué)"

echo
echo "== FAUCET-04 — chemin nominal : /challenge -> résolution -> /drip crédite la chaîne =="
r1_before="$(balance_units "$NODE" "$R1")"
expect_reject FAUCET-04-admission SUCCESS 200 "$(solved_drip_status "$FAUCET_URL" "$R1")" "drip vers $R1"
wait_balance "$NODE" "$R1" $((${r1_before:-0} + 10000)) 180 || true
r1_after="$(balance_units "$NODE" "$R1")"
expect_eq FAUCET-04-credited "$((${r1_before:-0} + 10000))" "$r1_after" \
  "1 PDN (10000 u) crédité sur la chaîne, lu depuis le nœud"

echo
echo "== FAUCET-05 — la MÊME adresse, resollicitée, est en cooldown =="
r="$(solved_drip "$FAUCET_URL" "$R1")"
expect_code FAUCET-05-cooldown 429 "$r" "$R1" "second drip immédiat sur la même adresse"

echo
echo "== FAUCET-06 — budget quotidien épuisé : refuse une AUTRE adresse, sans soumission =="
spent_before="$(daily_spent "$FAUCET_URL")"
r2_before="$(balance_units "$NODE" "$R2")"
r="$(solved_drip "$FAUCET_URL" "$R2")"
expect_code FAUCET-06-budget-exhausted 503 "$r" "$R2, budget déjà consommé par FAUCET-04"
sleep 3   # laisse un éventuel (mauvais) drip le temps d'être miné avant de re-lire le solde
r2_after="$(balance_units "$NODE" "$R2")"
spent_after="$(daily_spent "$FAUCET_URL")"
if [[ "${r2_before:-0}" == "${r2_after:-0}" && "$spent_before" == "$spent_after" ]]; then
  record FAUCET-06-no-submit PASS "aucun mouvement de solde ni de budget après le refus 503"
else
  record FAUCET-06-no-submit FAIL "mouvement inattendu: solde $r2_before→$r2_after, budget $spent_before→$spent_after"
fi

echo
echo "== FAUCET-07 — un redémarrage préserve le cooldown (l'état est sur disque, pas en mémoire) =="
stop_faucet "$FAUCET_PID"
FAUCET_PID="$(start_faucet "$FAUCET_STATE" "$FAUCET_PORT" "$(node_url "$NODE")" "$DRIP_PDN")"
if wait_faucet_up "$FAUCET_URL" 30; then
  record FAUCET-07-restarted PASS "faucet.py redémarré sur le même état ($FAUCET_STATE)"
  r="$(solved_drip "$FAUCET_URL" "$R1")"
  expect_code FAUCET-07-cooldown-holds 429 "$r" "$R1 toujours en cooldown juste après le redémarrage"
else
  record FAUCET-07-restarted FAIL "le redémarrage n'a jamais répondu — voir $FAUCET_LOG"
fi

echo
echo "== FAUCET-08 — un nœud injoignable échoue proprement, sans bloquer le service =="
UNREACH_PID="$(start_faucet "$UNREACH_STATE" "$UNREACH_PORT" "http://127.0.0.1:1" "$DRIP_PDN")"
if wait_faucet_up "$UNREACH_URL" 30; then
  record FAUCET-08-start PASS "instance pointée sur un nœud injoignable démarrée"
  r="$(solved_drip "$UNREACH_URL" "$R3")"
  expect_code FAUCET-08-unreachable 503 "$r" "http://127.0.0.1:1 injoignable"
  curl -sf --max-time 3 "$UNREACH_URL/status" >/dev/null 2>&1 \
    && record FAUCET-08-alive PASS "le service répond encore après l'échec (pas de blocage silencieux)" \
    || record FAUCET-08-alive FAIL "le service ne répond plus après l'échec du nœud"
else
  record FAUCET-08-start FAIL "l'instance n'a jamais répondu — voir $FAUCET_LOG"
fi

echo
echo "== FAUCET-09 — /status ne fuite aucun secret =="
status_body="$(get_json "$FAUCET_URL" /status)"
if [[ "$status_body" != *"$FAUCET_KEY"* && "$status_body" != *"$WALLET_BIN"* ]]; then
  record FAUCET-09-no-secrets PASS "ni le chemin de clé ni celui du binaire wallet dans /status"
else
  record FAUCET-09-no-secrets FAIL "/status contient un chemin local: $status_body"
fi

echo
echo "== hygiène : ni la clé jetable de cette batterie ni scripts/local-testnet/faucet/keys/ dans git =="
tracked_bad="$( { git -C "$ROOT" ls-files -- '*.key'; git -C "$ROOT" ls-files -- 'scripts/local-testnet/faucet/keys/*'; } 2>/dev/null)"
status_bad="$( { git -C "$ROOT" status --porcelain -- '*.key'; git -C "$ROOT" status --porcelain -- 'scripts/local-testnet/faucet/keys/*'; } 2>/dev/null)"
if [[ -z "$tracked_bad" && -z "$status_bad" ]]; then
  record HYG-01-no-key-in-git PASS "git ls-files et git status --porcelain ne montrent aucun *.key ni faucet/keys/*"
else
  record HYG-01-no-key-in-git FAIL "matériel de clé visible dans git — tracked=[$tracked_bad] status=[$status_bad]"
fi

perms="$(stat -c '%a' "$FAUCET_KEY" 2>/dev/null || echo '?')"
expect_eq HYG-02-key-perms 600 "$perms" "clé jetable du faucet, permissions sur disque"

suite_summary
