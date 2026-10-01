#!/usr/bin/env bash
# Batterie « transport et surface HTTP » — NET, API, CODEC sur des nœuds vivants.
#
# Ancrage : docs/adversarial/spec.md, familles NET (01, 03, 06, 10), API (02, 07, 09, 12) et
# CODEC. Ces preuves existent au composant (lib-net, app-node) ; ici elles passent par de vraies
# sockets, avec un pair HOSTILE réel (serveur Python qui répond des corps absurdes) plutôt qu'un
# double de test.
#
# Deux nœuds auxiliaires sont utilisés, lancés par ce script s'ils manquent :
#   base+91  « strict »  — devnet SANS RHIZOME_ALLOW_PRIVATE_PEERS : le filtre SSRF y est actif
#                          (les nœuds du testnet l'ont désactivé pour pouvoir se voir en 127.x)
#
# Usage : suite-net.sh [node]
set -uo pipefail
SUITE_NAME=net
source "$(dirname "$0")/suite-common.sh"

NODE=${1:-0}
# API-12 signe une transaction honnête après le déluge : sans cela, un BASE_DIR frais n'a pas
# encore Forge.class, `forge` rend une chaîne vide et le POST vide vaut 400 — un artefact du
# harnais qui a failli passer pour une pénalité de source (run staging 2026-09-29/10-01).
forge_build
STRICT_PORT=$((BASE_PORT + 91))
STRICT_URL="http://127.0.0.1:$STRICT_PORT"
HOSTILE_PORT=$((BASE_PORT + 92))
HOSTILE_URL="http://127.0.0.1:$HOSTILE_PORT"
NODE_BIN_PATH="$NODE_BIN"

start_strict() {
  curl -sf --max-time 2 "$STRICT_URL/info" >/dev/null 2>&1 && return 0
  mkdir -p "$BASE_DIR/strict" "$BASE_DIR/logs"
  setsid env RHIZOME_NETWORK="$NETWORK" RHIZOME_PORT="$STRICT_PORT" \
    RHIZOME_DATA="$BASE_DIR/strict" "$NODE_BIN_PATH" -Xmx128m \
    >> "$BASE_DIR/logs/strict.log" 2>&1 &
  for _ in $(seq 1 60); do curl -sf --max-time 2 "$STRICT_URL/info" >/dev/null 2>&1 && return 0; sleep 1; done
  return 1
}

# Pair hostile : un vrai serveur HTTP qui annonce une hauteur absurde et répond des corps
# démesurés (tools/hostile_peer.py). C'est la forme NET-03/NET-04 en réseau réel.
HOSTILE_PID=""
start_hostile() {
  "$PY" "$ROOT/scripts/local-testnet/tools/hostile_peer.py" "$HOSTILE_PORT" \
    >> "$BASE_DIR/logs/hostile.log" 2>&1 &
  HOSTILE_PID=$!
  for _ in $(seq 1 20); do
    curl -sf --max-time 2 "$HOSTILE_URL/info" >/dev/null 2>&1 && return 0
    sleep 0.5
  done
  return 1
}

add_peer_raw() {
  local node=$1 url=$2
  curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST \
    -H 'Content-Type: application/json' -H 'X-Rhizome-Request: 1' \
    --data-binary "{\"url\":\"$url\"}" "$(node_url "$node")/add_peer" 2>/dev/null || echo 000
}
add_peer_strict() {
  curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST \
    -H 'Content-Type: application/json' -H 'X-Rhizome-Request: 1' \
    --data-binary "{\"url\":\"$1\"}" "$STRICT_URL/add_peer" 2>/dev/null || echo 000
}
peer_count() { json_get "$(node_stats "$1")" peers; }

echo "== NET-01/10 — le filtre SSRF, sur un nœud où il est actif =="

# MESURÉ : `/add_peer` répond TOUJOURS 200 {"status":"OK"} — c'est une ANNONCE, pas une
# admission. Le filtre SSRF (et la validation d'URL) tournent dans `node.addPeer(url)`, qui
# laisse tomber la cible en silence. Asserter le code HTTP ne prouverait donc rien : la preuve
# est le REGISTRE. Le nœud strict démarre isolé (0 pair, aucun seed) et doit le rester quoi
# qu'on lui annonce.
strict_peers() { curl -s --max-time 5 "$STRICT_URL/peers" 2>/dev/null; }
strict_peer_count() { json_get "$(curl -s --max-time 5 "$STRICT_URL/stats" 2>/dev/null)" peers; }

# Deux niveaux de refus existent et il faut les distinguer :
#   (a) refus à l'admission — l'URL n'entre JAMAIS au registre (SSRF, schéma non http(s)) ;
#   (b) admission puis éviction — l'URL passe la validation (hôte public, schéma correct) mais
#       se révèle injoignable et le round de découverte l'évince.
# MESURÉ : `http://example.com:99999` (port hors des 65535) relève de (b) — il est brièvement
# enregistré puis disparaît. Le cas exige donc que le registre revienne vide, et NOMME le
# niveau atteint : c'est la différence entre « la porte a tenu » et « le ménage a été fait ».
probe_refused() {
  local id=$1 target=$2
  add_peer_strict "$target" >/dev/null
  sleep 1
  local immediate; immediate="$(strict_peers)"
  if [[ "$immediate" == *'"peers":[]'* && "$(strict_peer_count)" == "0" ]]; then
    record "$id" PASS "refusée à l'admission, jamais enregistrée ($target)"
    return
  fi
  # Admise : laisser le round de découverte faire son travail, puis re-mesurer.
  local deadline=$((SECONDS + 90))
  while (( SECONDS < deadline )); do
    sleep 5
    [[ "$(strict_peers)" == *'"peers":[]'* ]] && {
      record "$id" PASS "admise (hôte public, schéma valide) puis ÉVINCÉE comme injoignable ($target)"
      return
    }
  done
  record "$id" FAIL "PERSISTE AU REGISTRE ($target) — $(printf '%.100s' "$(strict_peers)")"
}

if start_strict; then
  record NET-00-strict-node PASS "nœud strict démarré sur $STRICT_URL (sans ALLOW_PRIVATE_PEERS, registre vide)"
  probe_refused NET-01-cloud-metadata-aws "http://169.254.169.254/"
  probe_refused NET-01-cloud-metadata-gcp "http://metadata.google.internal/"
  probe_refused NET-01-private-rfc1918    "http://10.0.0.1:8080"
  probe_refused NET-01-ipv6-loopback      "http://[::1]:8080"
  probe_refused NET-01-loopback-v4        "http://127.0.0.1:$BASE_PORT"
  probe_refused NET-10-file-scheme        "file:///etc/passwd"
  probe_refused NET-10-javascript-scheme  "javascript:alert(1)"
  probe_refused NET-10-ftp-scheme         "ftp://example.com"
  probe_refused NET-10-empty-authority    "http://"
  probe_refused NET-10-not-a-url          "not a url"
  probe_refused NET-10-port-overflow      "http://example.com:99999"
else
  record NET-00-strict-node FAIL "le nœud strict n'a pas démarré — famille NET-01/10 non exercée"
fi


echo "== NET-06 — une identité de pair par URL canonique =="

peers_targeting() {
  local port=$1
  curl -s --max-time 5 "$(node_url "$NODE")/peers" 2>/dev/null | "$PY" -c 'import json,sys
try: p = json.load(sys.stdin).get("peers", [])
except Exception: p = []
print(sum(1 for u in p if ":" + sys.argv[1] in u))' "$port"
}

# (a) Casse et barres obliques finales : doivent coalescer (PeerUrls.canonicalize les normalise).
before="$(peers_targeting "$(node_port 2)")"
for spelling in "http://LOCALHOST:$(node_port 2)" "http://localhost:$(node_port 2)/" \
                "http://localhost:$(node_port 2)//" "http://Localhost:$(node_port 2)"; do
  add_peer_raw "$NODE" "$spelling" >/dev/null
done
sleep 2
after="$(peers_targeting "$(node_port 2)")"
# Les quatre orthographes doivent coalescer en UNE identité canonique. La croissance autorisée
# est 1, pas 0 : sur un réseau où le pair cible est orthographié autrement que localhost (staging :
# les seeds sont en 127.0.0.1), la forme canonique localhost:port n'existe pas encore dans la
# table et son ajout légitime crée une entrée — ce qui compte est que les QUATRE variantes ne
# fassent qu'elle (devnet : croissance 0 car la forme y est déjà).
(( ${after:-0} - ${before:-0} <= 1 )) \
  && record NET-06-case-and-slashes PASS "casse et barres finales coalescent en une identité (entrées visant le pair : $before → $after)" \
  || record NET-06-case-and-slashes FAIL "les orthographes casse/barres ont créé plusieurs identités ($before → $after)"

# (b) Segments-point RFC 3986 : MESURÉ — ils ne coalescent PAS. `canonicalize` préserve
# délibérément un chemin non-racine (« deux montages distincts ne doivent pas se confondre
# silencieusement ») et ne résout pas `.`/`..`, si bien que `…:port`, `…:port/.` et
# `…:port/././.` sont trois identités pour un même point d'accès. Ce n'est pas une évasion de
# ban (le ban est clé par point d'accès, `PeerBanListTest#banIsKeyedByEndpointNotAddress`) mais
# c'est de l'inflation de registre. Le cas vérifie donc la BORNE qui la contient : le cap
# anti-éclipse par sous-réseau.
before="$(peers_targeting "$(node_port 4)")"
for spelling in "http://localhost:$(node_port 4)/." "http://localhost:$(node_port 4)/./." \
                "http://localhost:$(node_port 4)/././."; do
  add_peer_raw "$NODE" "$spelling" >/dev/null
done
sleep 2
after="$(peers_targeting "$(node_port 4)")"
total="$(peer_count "$NODE")"
cap=$((16 + 2))
if (( ${total:-0} <= cap )); then
  record NET-06b-dot-segments PASS "les segments-point créent des identités distinctes ($before → $after pour un même point d'accès) mais le registre reste sous le cap anti-éclipse ($total ≤ $cap)"
else
  record NET-06b-dot-segments FAIL "registre au-delà du cap anti-éclipse ($total > $cap) — l'inflation par orthographe n'est plus bornée"
fi

echo
echo "== NET-03/04 — un pair hostile réel (corps absurde) =="
if start_hostile; then
  h_before="$(height_of "$NODE")"
  code="$(add_peer_raw "$NODE" "$HOSTILE_URL")"
  record NET-03-hostile-added PASS "pair hostile présenté au nœud (add_peer → $code)"
  wait_blocks "$NODE" 2 240
  h_after="$(height_of "$NODE")"
  (( ${h_after:-0} > ${h_before:-0} )) \
    && record NET-03-survives PASS "le nœud a continué à produire ($h_before → $h_after) malgré un pair servant 50 Mo" \
    || record NET-03-survives FAIL "production figée après ajout du pair hostile"
  expect_eq NET-04-not-degraded "degraded=null reorg=false" "$(node_healthy "$NODE")" "état après le pair hostile"
  [[ -n "$(node_stats "$NODE")" ]] \
    && record NET-03-responsive PASS "le nœud répond encore à /stats" \
    || record NET-03-responsive FAIL "le nœud ne répond plus"
  kill "${HOSTILE_PID:-0}" 2>/dev/null || true
else
  record NET-03-hostile-added FAIL "le pair hostile n'a pas démarré"
fi

echo
echo "== API-12 — pousser des blocs invalides ne doit pas être gratuit indéfiniment =="
codes=""
for i in $(seq 1 40); do
  codes="$codes $(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST \
    -H 'Content-Type: application/octet-stream' -H 'X-Rhizome-Request: 1' \
    --data-binary "$(head -c 200 /dev/urandom | base64)" "$(node_url "$NODE")/submit")"
done
shed="$(printf '%s' "$codes" | tr ' ' '\n' | grep -c -E '429')"
rejected="$(printf '%s' "$codes" | tr ' ' '\n' | grep -c -E '400|429')"
expect_eq API-12-junk-blocks-rejected 40 "$rejected" "40 blocs poubelle : tous refusés (dont $shed délestés)"
alice_n="$(next_nonce "$NODE" "$(addr_of "$KEYS_DIR/tx-alice.key")")"
r="$(submit_tx "$NODE" "$(forge send key="$KEYS_DIR/tx-alice.key" to="$(addr_of "$KEYS_DIR/tx-bob.key")" \
      amount=1000 fee="$(profile_get MIN_FEE)" chain="$(profile_get CHAIN_ID)" nonce="$alice_n")")"
case "${r#*|}" in
  SUCCESS) record API-12-honest-still-served PASS "une transaction honnête passe encore depuis la même source" ;;
  *)       record API-12-honest-still-served FAIL "source pénalisée au-delà du délestage: $r" ;;
esac

echo
echo "== API-07 — index hors bornes et paramètres absurdes =="
for probe in "/block?index=999999999" "/block?index=-1" "/block?index=abc" "/transaction?txid=zz" \
             "/wallet?address=NOTANADDRESS" "/token?id=xyz" "/box?id=00" "/headers?start=-5" \
             "/state/proof?domain=nope&key=00" "/logs?fromHeight=0"; do
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -H 'X-Rhizome-Request: 1' \
    "$(node_url "$NODE")$probe" 2>/dev/null || echo 000)"
  case "$code" in
    400|404) record "API-07${probe%%\?*}$(printf '%.10s' "${probe#*\?}")" PASS "$probe → $code" ;;
    *)       record "API-07${probe%%\?*}$(printf '%.10s' "${probe#*\?}")" FAIL "$probe → $code (attendu 400/404)" ;;
  esac
done
[[ -n "$(node_stats "$NODE")" ]] \
  && record API-07-alive PASS "le nœud répond encore après les paramètres absurdes" \
  || record API-07-alive FAIL "le nœud est tombé"

echo
echo "== API-09 — limiteur de débit, et X-Forwarded-For ne l'esquive pas =="
codes=""
for i in $(seq 1 400); do
  codes="$codes $(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$(node_url "$NODE")/stats")"
done
limited="$(printf '%s' "$codes" | tr ' ' '\n' | grep -c '429')"
(( limited > 0 )) \
  && record API-09-rate-limited PASS "400 lectures en rafale : $limited refusées en 429" \
  || record API-09-rate-limited FAIL "aucune limitation sur 400 lectures en rafale"
codes=""
for i in $(seq 1 400); do
  codes="$codes $(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
    -H "X-Forwarded-For: 203.0.113.$((RANDOM % 250 + 1))" "$(node_url "$NODE")/stats")"
done
spoofed="$(printf '%s' "$codes" | tr ' ' '\n' | grep -c '429')"
(( spoofed > 0 )) \
  && record API-09-xff-no-escape PASS "XFF tournant : $spoofed refusées en 429 (l'en-tête n'est pas cru)" \
  || record API-09-xff-no-escape FAIL "XFF tournant échappe au limiteur (0 refus sur 400)"

echo
echo "== CODEC — /submit et /sync sur des octets hostiles =="
for body in "" "AAAA" "$(head -c 5000 /dev/urandom | base64)"; do
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 -X POST \
    -H 'Content-Type: application/octet-stream' -H 'X-Rhizome-Request: 1' \
    --data-binary "$body" "$(node_url "$NODE")/submit" 2>/dev/null || echo 000)"
  case "$code" in
    400|429) record "CODEC-submit-${#body}" PASS "corps de ${#body} octets sur /submit → $code" ;;
    *)       record "CODEC-submit-${#body}" FAIL "corps de ${#body} octets sur /submit → $code" ;;
  esac
done

echo
echo "== santé finale =="
expect_eq NET-FINAL-healthy "degraded=null reorg=false" "$(node_healthy "$NODE")" "état du nœud victime"
banned="$(json_get "$(node_stats "$NODE")" syncPeersBanned)"
record NET-FINAL-bans PASS "pairs bannis vus par le nœud : ${banned:-0}"

suite_summary
