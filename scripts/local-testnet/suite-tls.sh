#!/usr/bin/env bash
# Batterie TLS/AUTH/XFF — TLS de bout en bout, jeton porteur et confiance en X-Forwarded-For,
# contre un VRAI relais TLS et de VRAIS processus rhizome-node (pas de double de test).
#
# Trou documenté par TEST-PLAN.md §Périmètre : la campagne principale tourne tout en `http://`
# sans jeton ("le jeton pair n'est envoyé qu'en https://, il est hors sujet sur un testnet local
# en http://" — hors périmètre explicite : chiffrement, RHIZOME_PROTECT_READS). Cette batterie
# COMBLE ce trou en construisant elle-même le relais TLS et les pairs qu'il faut pour exercer ces
# chemins, plutôt que d'attendre une campagne multi-machines.
#
# Outil de terminaison TLS : aucun de nginx/caddy/socat/stunnel n'est installé sur cette machine
# (vérifié ci-dessous par `command -v`, comme documenté dans tools/tls_proxy.py). La batterie
# utilise donc le relais Python maison (tools/tls_proxy.py, relais d'OCTETS après poignée de
# main — pas un analyseur HTTP) plutôt que d'écrire un chemin nginx/caddy/socat qui ne pourrait
# jamais être exécuté ni vérifié sur ce terrain.
#
# Cinq nœuds auxiliaires ISOLÉS (sur le modèle de suite-bootstrap.sh/suite-pow.sh — jamais reliés
# à la campagne principale, chacun sa propre configuration RHIZOME_* que la campagne ne pose
# jamais) :
#   * NODE_A / NODE_B — derrière un relais TLS chacun (PROXY_A/PROXY_B), RHIZOME_TRUST_XFF=false
#     et =true respectivement : TLS-01/02 et XFF-01/02/03.
#   * NODE_TOKEN — RHIZOME_API_TOKEN seul : AUTH-01..04.
#   * NODE_PROTECT — RHIZOME_API_TOKEN + RHIZOME_PROTECT_READS : AUTH-05..07.
#   * NODE_PEERTOK — RHIZOME_PEER_TOKEN, un pair CONFIGURÉ (RHIZOME_PEERS) et un pair APPRIS PAR
#     GOSSIP (/add_peer), tous deux servis par tools/tls_peer.py (certificat auto-signé propre,
#     DISTINCT de celui des relais) : NET-08.
#
# Limite du banc à noter pour une VRAIE campagne multi-VM : les certificats sont jetables et
# générés au vol (jamais commités), aucune rotation, aucune vraie AC publique, et « une autre
# machine » n'est simulée ici que par une AUTRE interface réseau DE CE MÊME hôte (voir XFF-03) —
# une preuve d'inaccessibilité depuis un hôte véritablement distinct exige un second nœud
# physique/VM, hors de portée d'une seule machine de développement.
SUITE_NAME=tls
source "$(dirname "${BASH_SOURCE[0]}")/suite-common.sh"

TOOLS="$ROOT/scripts/local-testnet/tools"
TLS_DIR="$BASE_DIR/tls"
mkdir -p "$TLS_DIR" "$BASE_DIR/logs"

# --- 0. outillage : openssl obligatoire, keytool (JDK) pour le magasin de confiance du contrôle
#        positif NET-08, choix du terminateur TLS -------------------------------------------
if ! command -v openssl >/dev/null 2>&1; then
  echo "ERREUR: openssl introuvable — impossible de générer les certificats jetables de cette batterie" >&2
  exit 1
fi
ensure_jdk25
KEYTOOL="$JAVA_HOME/bin/keytool"

TLS_TOOL=none
for cand in nginx caddy stunnel stunnel4 socat; do
  command -v "$cand" >/dev/null 2>&1 && { TLS_TOOL=$cand; break; }
done
if [[ "$TLS_TOOL" != none ]]; then
  echo "NOTE: '$TLS_TOOL' est installé sur cette machine, mais cette batterie n'a de chemin câblé" >&2
  echo "      que pour le relais Python maison (tools/tls_proxy.py) — voir son en-tête pour le" >&2
  echo "      pourquoi. Étendre suite-tls.sh pour préférer '$TLS_TOOL' est un axe d'amélioration" >&2
  echo "      légitime, non fait ici faute de terrain pour le vérifier." >&2
else
  echo "aucun de nginx/caddy/stunnel/socat trouvé — relais TLS = tools/tls_proxy.py (Python, byte-relay)"
fi

# --- 1. matériel TLS jetable, deux autorités DISTINCTES ------------------------------------
# AC "relais" (proxy-ca) : celle que les cas TLS-01/02 apprennent à faire confiance via --cacert.
# AC "pair TLS" (tlspeer-ca) : DÉLIBÉRÉMENT différente — tls_peer.py doit rester une identité
# qu'aucun relais ni aucun nœud honnête ne reconnaît par accident.
gen_ca() {   # gen_ca <préfixe>
  local name=$1
  [[ -f "$TLS_DIR/$name-ca.pem" ]] && return 0
  openssl req -x509 -newkey rsa:2048 -nodes -keyout "$TLS_DIR/$name-ca.key" -out "$TLS_DIR/$name-ca.pem" \
    -days 2 -subj "/CN=Rhizome suite-tls $name CA" >/dev/null 2>&1
}
gen_leaf() {   # gen_leaf <préfixe-AC> <nom-feuille>
  local ca=$1 leaf=$2
  [[ -f "$TLS_DIR/$leaf.pem" ]] && return 0
  openssl req -x509 -newkey rsa:2048 -nodes -keyout "$TLS_DIR/$leaf.key" -out "$TLS_DIR/$leaf.pem" \
    -days 2 -subj "/CN=127.0.0.1" -addext "subjectAltName=IP:127.0.0.1" \
    -CA "$TLS_DIR/$ca-ca.pem" -CAkey "$TLS_DIR/$ca-ca.key" >/dev/null 2>&1
}
gen_ca proxy && gen_leaf proxy proxy-cert
gen_ca tlspeer && gen_leaf tlspeer tlspeer-cert
if [[ -f "$TLS_DIR/proxy-cert.pem" && -f "$TLS_DIR/tlspeer-cert.pem" ]]; then
  record TLS-00-certs PASS "AC + certificats jetables générés dans $TLS_DIR (relais et pair TLS, deux AC distinctes)"
else
  record TLS-00-certs FAIL "génération openssl échouée — voir $TLS_DIR"
  exit 1   # rien en aval n'a de sens sans matériel TLS
fi
# Magasin de confiance pour le contrôle positif NET-08 (NODE_PEERTOK apprend à faire confiance à
# l'AC "pair TLS", et UNIQUEMENT elle — pas celle du relais, pas le système par défaut).
if [[ ! -f "$TLS_DIR/truststore.jks" ]]; then
  "$KEYTOOL" -importcert -noprompt -alias tlspeerca -file "$TLS_DIR/tlspeer-ca.pem" \
    -keystore "$TLS_DIR/truststore.jks" -storepass changeit >/dev/null 2>&1
fi
[[ -f "$TLS_DIR/truststore.jks" ]] \
  && record TLS-00-truststore PASS "magasin de confiance JKS construit (keytool, AC pair TLS uniquement)" \
  || record TLS-00-truststore FAIL "keytool a échoué à construire $TLS_DIR/truststore.jks"

# --- 2. ports et petites fonctions de lancement (sur le modèle de suite-bootstrap.sh) --------
PROXY_A_PORT=$((BASE_PORT + 93)); NODE_A_PORT=$((BASE_PORT + 94))
PROXY_B_PORT=$((BASE_PORT + 95)); NODE_B_PORT=$((BASE_PORT + 96))
NODE_TOKEN_PORT=$((BASE_PORT + 97))
NODE_PROTECT_PORT=$((BASE_PORT + 98))
NODE_PEERTOK_PORT=$((BASE_PORT + 99))
TLSPEER_CONFIGURED_PORT=$((BASE_PORT + 100))
TLSPEER_GOSSIP_PORT=$((BASE_PORT + 101))

API_TOKEN="suite-tls-api-token"
PEER_TOKEN="suite-tls-peer-token"

launch_node() {   # launch_node <port> <data> <log> <bin_args_str> <env...>
  local port=$1 data=$2 log=$3 bin_args_str=$4; shift 4
  local bin_args=()
  [[ -n "$bin_args_str" ]] && read -ra bin_args <<<"$bin_args_str"
  setsid env RHIZOME_NETWORK="$NETWORK" RHIZOME_PORT="$port" RHIZOME_DATA="$data" \
    RHIZOME_ALLOW_PRIVATE_PEERS=true "$@" \
    "$NODE_BIN" -Xmx128m "${bin_args[@]}" > "$BASE_DIR/logs/$log.log" 2>&1 < /dev/null &
}
# Recherche par RHIZOME_DATA dans /proc/<pid>/environ, PAS par $! : `setsid CMD &` peut forker
# en interne (GNU coreutils), donc $! ne pointe pas forcément vers le nœud final. Copié tel quel
# de suite-bootstrap.sh (kill_node), qui a déjà payé ce piège.
kill_node() {   # kill_node <datadir>
  local pid; pid="$(ps -eo pid,args | grep "[r]hizome-node" | awk '{print $1}' \
    | while read -r p; do tr '\0' '\n' < "/proc/$p/environ" 2>/dev/null \
    | grep -q "RHIZOME_DATA=$1" && echo "$p"; done)"
  [[ -n "$pid" ]] && kill -9 $pid 2>/dev/null
}
wait_up() {   # wait_up <url> [timeout]
  local deadline=$((SECONDS + ${2:-90}))
  while (( SECONDS < deadline )); do [[ -n "$(node_stats "$1")" ]] && return 0; sleep 1; done
  return 1
}
# Sonde de PORT pure — aucune tentative TLS/HTTP, donc pas de -k qui traînerait dans le fichier :
# juste "le processus accepte-t-il une connexion TCP", pour attendre tls_peer.py sans se soucier
# de son certificat (qu'on ne valide délibérément jamais depuis ce script de lancement).
wait_port() {   # wait_port <port> [timeout]
  local port=$1 deadline=$((SECONDS + ${2:-20}))
  while (( SECONDS < deadline )); do
    (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null && { exec 3<&- 3>&-; return 0; }
    sleep 0.3
  done
  return 1
}

# --- petits utilitaires curl locaux : cette batterie a besoin de PLUSIEURS jetons/AC différents
#     à la fois, donc pas des globales SUITE_TOKEN/CURL_TLS_OPTS de suite-common.sh (une seule
#     valeur chacune) mais des appels explicites, cas par cas — sur le modèle de add_peer_raw
#     dans suite-net.sh. Définis ICI (pas plus bas) : le contrôle de disponibilité de NODE_PROTECT
#     en a besoin — RHIZOME_PROTECT_READS gate /stats lui-même, donc node_stats()/wait_up() (qui
#     n'envoient jamais de jeton) ne le verront JAMAIS prêt. ------------------------------------
curl_get() {   # curl_get <url> <chemin> [jeton]
  local url=$1 path=$2 bearer=${3:-}
  local auth=(); [[ -n "$bearer" ]] && auth=(-H "Authorization: Bearer $bearer")
  curl -s --max-time 8 -o /tmp/.rz_tls_body.$$ -w '%{http_code}' -H 'X-Rhizome-Request: 1' \
    "${auth[@]}" "$url$path" 2>/dev/null || echo 000
  printf '|'; cat /tmp/.rz_tls_body.$$ 2>/dev/null; rm -f /tmp/.rz_tls_body.$$
}
curl_post() {   # curl_post <url> <chemin> <corps> [jeton]
  local url=$1 path=$2 body=$3 bearer=${4:-}
  local auth=(); [[ -n "$bearer" ]] && auth=(-H "Authorization: Bearer $bearer")
  curl -s --max-time 8 -o /tmp/.rz_tls_body.$$ -w '%{http_code}' -X POST \
    -H 'Content-Type: application/json' -H 'X-Rhizome-Request: 1' "${auth[@]}" \
    --data-binary "$body" "$url$path" 2>/dev/null || echo 000
  printf '|'; cat /tmp/.rz_tls_body.$$ 2>/dev/null; rm -f /tmp/.rz_tls_body.$$
}
# Prêt = "$path" répond 200 avec le jeton donné (vide = aucun jeton) — pour NODE_PROTECT, dont
# TOUTE route (y compris /stats) exige désormais le jeton.
wait_up_authed() {   # wait_up_authed <url> <jeton_ou_vide> [timeout]
  local url=$1 bearer=$2 deadline=$((SECONDS + ${3:-90}))
  while (( SECONDS < deadline )); do
    [[ "$(curl_get "$url" /stats "$bearer")" == 200\|* ]] && return 0
    sleep 1
  done
  return 1
}

# Processus Python (relais TLS, pairs TLS) : PAS de setsid, `$!` désigne directement le
# processus lancé (comme tools/hostile_peer.py dans suite-net.sh) — pas le piège ci-dessus.
PY_PIDS=()
start_py() {   # start_py <log> <argv...>
  local log=$1; shift
  "$PY" "$@" >> "$BASE_DIR/logs/$log.log" 2>&1 &
  PY_PIDS+=("$!")
}

cleanup() {
  local p
  for p in "${PY_PIDS[@]:-}"; do kill -9 "$p" 2>/dev/null; done
  kill_node "$BASE_DIR/tls-node-a"; kill_node "$BASE_DIR/tls-node-b"
  kill_node "$BASE_DIR/tls-node-token"; kill_node "$BASE_DIR/tls-node-protect"
  kill_node "$BASE_DIR/tls-node-peertok"
}
trap cleanup EXIT

# --- 3. lancement : les deux nœuds derrière relais, avec l'allowlist Host pointée sur le PORT
#        DU RELAIS (le nœud voit Host: 127.0.0.1:<port relais>, pas son propre port — sans ça
#        toute requête via le relais essuie un 403 host-not-allowed, mesuré en prototype) -----
rm -rf "$BASE_DIR/tls-node-a" "$BASE_DIR/tls-node-b" "$BASE_DIR/tls-node-token" \
       "$BASE_DIR/tls-node-protect" "$BASE_DIR/tls-node-peertok"
launch_node "$NODE_A_PORT" "$BASE_DIR/tls-node-a" tls-node-a \
  "" RHIZOME_ALLOWED_HOSTS="127.0.0.1:$PROXY_A_PORT"
launch_node "$NODE_B_PORT" "$BASE_DIR/tls-node-b" tls-node-b \
  "" RHIZOME_ALLOWED_HOSTS="127.0.0.1:$PROXY_B_PORT" RHIZOME_TRUST_XFF=true
launch_node "$NODE_TOKEN_PORT" "$BASE_DIR/tls-node-token" tls-node-token \
  "" RHIZOME_API_TOKEN="$API_TOKEN"
launch_node "$NODE_PROTECT_PORT" "$BASE_DIR/tls-node-protect" tls-node-protect \
  "" RHIZOME_API_TOKEN="$API_TOKEN" RHIZOME_PROTECT_READS=true
launch_node "$NODE_PEERTOK_PORT" "$BASE_DIR/tls-node-peertok" tls-node-peertok \
  "-Djavax.net.ssl.trustStore=$TLS_DIR/truststore.jks -Djavax.net.ssl.trustStorePassword=changeit" \
  RHIZOME_PEER_TOKEN="$PEER_TOKEN" RHIZOME_PEERS="https://127.0.0.1:$TLSPEER_CONFIGURED_PORT"

NODES_UP=1
for u in "http://127.0.0.1:$NODE_A_PORT" "http://127.0.0.1:$NODE_B_PORT" \
         "http://127.0.0.1:$NODE_TOKEN_PORT" "http://127.0.0.1:$NODE_PEERTOK_PORT"; do
  wait_up "$u" 90 || NODES_UP=0
done
# NODE_PROTECT seul : RHIZOME_PROTECT_READS gate /stats lui-même, donc la sonde doit porter le
# jeton (voir wait_up_authed ci-dessus) — plain wait_up() ne le verrait JAMAIS prêt.
wait_up_authed "http://127.0.0.1:$NODE_PROTECT_PORT" "$API_TOKEN" 90 || NODES_UP=0
if (( NODES_UP )); then
  record TLS-00-nodes PASS "cinq nœuds auxiliaires démarrés (relais A/B, jeton, protect-reads, jeton pair)"
else
  record TLS-00-nodes FAIL "au moins un nœud auxiliaire n'a jamais répondu — voir $BASE_DIR/logs/tls-node-*.log"
fi

start_py tls-proxy-a "$TOOLS/tls_proxy.py" "$PROXY_A_PORT" "$NODE_A_PORT" \
  "$TLS_DIR/proxy-cert.pem" "$TLS_DIR/proxy-cert.key"
start_py tls-proxy-b "$TOOLS/tls_proxy.py" "$PROXY_B_PORT" "$NODE_B_PORT" \
  "$TLS_DIR/proxy-cert.pem" "$TLS_DIR/proxy-cert.key"
# Journaux purgés AVANT de démarrer les processus (pas après) : $TLS_DIR survit d'une exécution
# à l'autre (seuls les certificats y sont réutilisés s'ils existent déjà), donc un journal d'une
# campagne précédente doit disparaître avant que NET-08 ne compte ses lignes, jamais après —
# sans quoi une requête légitime de CE run pourrait être purgée avec le résidu.
rm -f "$TLS_DIR/configured-headers.log" "$TLS_DIR/gossip-headers.log"
start_py tls-peer-configured "$TOOLS/tls_peer.py" "$TLSPEER_CONFIGURED_PORT" \
  "$TLS_DIR/tlspeer-cert.pem" "$TLS_DIR/tlspeer-cert.key" "$TLS_DIR/configured-headers.log"
start_py tls-peer-gossip "$TOOLS/tls_peer.py" "$TLSPEER_GOSSIP_PORT" \
  "$TLS_DIR/tlspeer-cert.pem" "$TLS_DIR/tlspeer-cert.key" "$TLS_DIR/gossip-headers.log"
wait_port "$TLSPEER_CONFIGURED_PORT" 20 || true
wait_port "$TLSPEER_GOSSIP_PORT" 20 || true

PROXIES_UP=1
for _ in $(seq 1 30); do
  curl -sf -o /dev/null --max-time 2 --cacert "$TLS_DIR/proxy-ca.pem" "https://127.0.0.1:$PROXY_A_PORT/stats" && break
  sleep 0.5
done
curl -sf -o /dev/null --max-time 2 --cacert "$TLS_DIR/proxy-ca.pem" "https://127.0.0.1:$PROXY_A_PORT/stats" || PROXIES_UP=0
for _ in $(seq 1 30); do
  curl -sf -o /dev/null --max-time 2 --cacert "$TLS_DIR/proxy-ca.pem" "https://127.0.0.1:$PROXY_B_PORT/stats" && break
  sleep 0.5
done
curl -sf -o /dev/null --max-time 2 --cacert "$TLS_DIR/proxy-ca.pem" "https://127.0.0.1:$PROXY_B_PORT/stats" || PROXIES_UP=0
if (( PROXIES_UP )); then
  record TLS-00-proxies PASS "les deux relais TLS répondent (AC de confiance)"
else
  record TLS-00-proxies FAIL "au moins un relais TLS n'a jamais répondu — voir $BASE_DIR/logs/tls-proxy-*.log"
fi

if (( ! NODES_UP || ! PROXIES_UP )); then
  echo "prérequis manquants — le reste de la batterie est marqué FAIL plutôt que de tourner à l'aveugle" >&2
  for id in TLS-01-status TLS-01-network TLS-01-chainid TLS-02-untrusted-cert-rejected \
            AUTH-01-no-token AUTH-02-wrong-token AUTH-03-correct-token AUTH-03-status-ok \
            AUTH-04-peer-protocol-open AUTH-05-protect-reads-blocks-stats \
            AUTH-06-protect-reads-with-token AUTH-07-spa-shell-open \
            NET-08-gossip-peer-presented NET-08-configured-reached NET-08-configured-gets-token \
            NET-08-gossip-reached NET-08-gossip-never-gets-token \
            XFF-01-spoofing-does-not-evade XFF-02-spoofing-evades \
            XFF-03-unreachable-from-other-interface \
            TLS-FINAL-healthy-a TLS-FINAL-healthy-b TLS-FINAL-healthy-peertok; do
    record "$id" FAIL "prérequis TLS-00 manquant — voir ci-dessus"
  done
  suite_summary
  exit 1
fi

echo
echo "== TLS-01/02 — le relais TLS, avec et sans confiance dans l'AC =========================="

# TLS-01 : AC de confiance (--cacert) -> 200, corps cohérent avec /stats en direct (modulo la
# hauteur — ces nœuds isolés n'ont pas de mineur, donc elle ne bouge de toute façon pas ici,
# mais on ne la compare pas : ce n'est pas ce que ce cas prouve).
r="$(curl -s --max-time 5 -o /tmp/.rz_tls_body.$$ -w '%{http_code}' --cacert "$TLS_DIR/proxy-ca.pem" \
  "https://127.0.0.1:$PROXY_A_PORT/stats" 2>/dev/null || echo 000)"
body="$(cat /tmp/.rz_tls_body.$$ 2>/dev/null)"; rm -f /tmp/.rz_tls_body.$$
expect_code TLS-01-status 200 "$r|" "relais TLS, AC de confiance (--cacert)"
direct="$(curl -s --max-time 5 "http://127.0.0.1:$NODE_A_PORT/stats" 2>/dev/null)"
expect_eq TLS-01-network "$(json_get "$direct" network)" "$(json_get "$body" network)" \
  "même réseau vu via le relais ou en direct sur le nœud"
expect_eq TLS-01-chainid "$(json_get "$direct" chainId)" "$(json_get "$body" chainId)" \
  "même chainId via le relais ou en direct"

# TLS-02 : AUCUNE AC de confiance, et surtout AUCUN -k — sans quoi ce cas ne prouverait rien.
out="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "https://127.0.0.1:$PROXY_A_PORT/stats" 2>/dev/null)"
rc=$?
if (( rc != 0 )) && [[ "$out" != "200" ]]; then
  record TLS-02-untrusted-cert-rejected PASS \
    "curl a échoué à la couche TLS sans --cacert ni -k (rc curl=$rc, code=${out:-<vide>}) — certificat auto-signé, à raison non approuvé"
else
  record TLS-02-untrusted-cert-rejected FAIL \
    "curl a réussi malgré l'absence de --cacert (rc=$rc, code=$out) — le certificat auto-signé n'aurait jamais dû être accepté"
fi

echo
echo "== AUTH-01..04 — RHIZOME_API_TOKEN sur une route état-changeante, ouverte sur une route protocole-pair =="
TOKEN_NODE="http://127.0.0.1:$NODE_TOKEN_PORT"
ADD_PEER_BODY='{"url":"http://127.0.0.1:1"}'

expect_code AUTH-01-no-token 401 "$(curl_post "$TOKEN_NODE" /add_peer "$ADD_PEER_BODY")" \
  "/add_peer sans jeton porteur, RHIZOME_API_TOKEN configuré (RoutePolicy.Guard.TOKEN)"
expect_code AUTH-02-wrong-token 401 "$(curl_post "$TOKEN_NODE" /add_peer "$ADD_PEER_BODY" "not-the-token")" \
  "/add_peer avec un jeton incorrect"
r="$(curl_post "$TOKEN_NODE" /add_peer "$ADD_PEER_BODY" "$API_TOKEN")"
expect_code AUTH-03-correct-token 200 "$r" "/add_peer avec le jeton correct"
expect_contains AUTH-03-status-ok '"status":"OK"' "${r#*|}" "corps de la réponse admise"
expect_code AUTH-04-peer-protocol-open 200 "$(curl_get "$TOKEN_NODE" /peers)" \
  "/peers (protocole-pair) reste ouvert SANS jeton même si RHIZOME_API_TOKEN est configuré — les pairs ne portent pas le jeton opérateur"

echo
echo "== AUTH-05..07 — RHIZOME_PROTECT_READS étend le jeton à TOUTE route, sauf la coquille SPA =="
PROTECT_NODE="http://127.0.0.1:$NODE_PROTECT_PORT"
expect_code AUTH-05-protect-reads-blocks-stats 401 "$(curl_get "$PROTECT_NODE" /stats)" \
  "RHIZOME_PROTECT_READS : /stats (normalement public) exige désormais le jeton"
expect_code AUTH-06-protect-reads-with-token 200 "$(curl_get "$PROTECT_NODE" /stats "$API_TOKEN")" \
  "... et redevient accessible avec le jeton correct"
expect_code AUTH-07-spa-shell-open 200 "$(curl_get "$PROTECT_NODE" /)" \
  "coquille SPA (\"/\") exemptée de RHIZOME_PROTECT_READS (Guard.SPA_SHELL) — un navigateur ne peut porter aucun jeton sur une simple navigation"

echo
echo "== NET-08 — RHIZOME_PEER_TOKEN : jamais vers un pair appris par gossip, uniquement le pair configuré (https) =="
PEERTOK_NODE="http://127.0.0.1:$NODE_PEERTOK_PORT"
r="$(curl_post "$PEERTOK_NODE" /add_peer "{\"url\":\"https://127.0.0.1:$TLSPEER_GOSSIP_PORT\"}")"
record NET-08-gossip-peer-presented "$([[ "${r%%|*}" == 200 ]] && echo PASS || echo FAIL)" \
  "pair appris par gossip présenté via /add_peer (PAS dans RHIZOME_PEERS) -> ${r%%|*}"

# Laisse tourner plusieurs rounds de sync/PEX (période par défaut 10 s) pour que le nœud ait le
# temps de solliciter les deux pairs TLS plusieurs fois.
run_for 45 || true

configured_log="$TLS_DIR/configured-headers.log"
gossip_log="$TLS_DIR/gossip-headers.log"
# `grep -c` sort du code 1 (aucune correspondance) même en imprimant "0" — chaîné avec `||` ça
# ajoutait un second "0" en double ligne (constaté en vérification : `attendu '0', obtenu '0\n0'`).
# D'où l'affectation en deux temps ci-dessous plutôt qu'un `[[ ... ]] && cmd || echo 0` unique.
configured_hits=0; [[ -f "$configured_log" ]] && configured_hits="$(wc -l < "$configured_log")"
configured_auth=0; [[ -f "$configured_log" ]] && configured_auth="$(grep -c 'Authorization=Bearer' "$configured_log")"
gossip_hits=0; [[ -f "$gossip_log" ]] && gossip_hits="$(wc -l < "$gossip_log")"
gossip_auth=0; [[ -f "$gossip_log" ]] && gossip_auth="$(grep -c 'Authorization=Bearer' "$gossip_log")"

expect_ge NET-08-configured-reached 1 "$configured_hits" \
  "le pair CONFIGURÉ (RHIZOME_PEERS, https, AC de confiance) a bien été contacté ($configured_hits requêtes) — sans quoi le contrôle positif ci-dessous ne prouverait rien"
expect_ge NET-08-configured-gets-token 1 "$configured_auth" \
  "... et au moins une de ces requêtes portait le jeton porteur (contrôle positif : le mécanisme fonctionne bien sur ce terrain, $configured_auth/$configured_hits)"
expect_ge NET-08-gossip-reached 1 "$gossip_hits" \
  "le pair APPRIS PAR GOSSIP (même AC de confiance, donc TLS aboutit) a bien été contacté ($gossip_hits requêtes) — la négative ci-dessous porte donc sur une vraie tentative de connexion, pas sur un échec de transport"
expect_eq NET-08-gossip-never-gets-token 0 "$gossip_auth" \
  "AUCUNE des $gossip_hits requêtes vers le pair gossip ne portait le jeton (PeerTokenPolicy.tokenFor : seul un pair explicitement configuré en https:// le reçoit)"

echo
echo "== XFF-01/02 — RHIZOME_TRUST_XFF change QUI le limiteur par-client identifie ============="
# /features (aucune garde, ni jeton ni budget agrégé) pour isoler le limiteur PAR CLIENT : /stats
# est aussi soumis au budget de lecture AGRÉGÉ (Guard.READ_BUDGET, partagé par tout le monde),
# qui aurait pollué la mesure — mesuré en prototype (les deux configurations trust=false/true
# affichaient alors le MÊME taux de 429, preuve que ce n'était pas le bon verrou qu'on observait).
BURST_TOOL="$TOOLS/xff_burst.py"

r1="$("$PY" "$BURST_TOOL" 127.0.0.1 "$PROXY_A_PORT" /features 1500 50 "$TLS_DIR/proxy-ca.pem" rotate 2>/dev/null)"
limited1="$(json_get "$r1" count429)"; total1="$(json_get "$r1" total)"
record_metric XFF-01-limited-count "${limited1:-0}" "/${total1:-?}" "trustXff=false, via le relais, X-Forwarded-For tournant"
expect_ge XFF-01-spoofing-does-not-evade 1 "${limited1:-0}" \
  "RHIZOME_TRUST_XFF=false : la clé du limiteur reste l'adresse socket (celle du relais), l'en-tête usurpé n'a AUCUN effet — $limited1/$total1 refusées"

r2="$("$PY" "$BURST_TOOL" 127.0.0.1 "$PROXY_B_PORT" /features 1500 50 "$TLS_DIR/proxy-ca.pem" rotate 2>/dev/null)"
limited2="$(json_get "$r2" count429)"; total2="$(json_get "$r2" total)"
record_metric XFF-02-limited-count "${limited2:-0}" "/${total2:-?}" "trustXff=true, via le relais, X-Forwarded-For tournant"
if [[ -n "$limited1" && -n "$limited2" ]] && (( limited2 < limited1 )); then
  record XFF-02-spoofing-evades PASS \
    "RHIZOME_TRUST_XFF=true : $limited2/$total2 refusées contre $limited1/$total1 sous CHARGE IDENTIQUE — l'en-tête EST CRU, la limite par client s'éparpille sur les adresses usurpées. PIÈGE OPÉRATIONNEL DÉLIBÉRÉ : RHIZOME_TRUST_XFF=true ne doit JAMAIS être activé derrière un relais qui ne réécrit pas lui-même X-Forwarded-For (celui de cette batterie, tools/tls_proxy.py, est un relais d'octets TRANSPARENT — il ne le fait pas, exprès, pour rendre ce piège observable) ; en production le relais doit ÉCRASER l'en-tête entrant par l'adresse socket réelle du client avant de le transmettre."
else
  record XFF-02-spoofing-evades FAIL \
    "aucune évasion mesurée ($limited2 vs $limited1 sur $total1/$total2 requêtes) — le piège documenté ne s'est pas manifesté sur ce terrain"
fi

echo
echo "== XFF-03 — le contrôle mitigant réel : NODE_B (trustXff=true) ne parle qu'à la boucle locale =="
# `ss -ltn` n'a pas de visibilité fiable dans ce bac à sable (constaté en prototype : aucune
# sortie même pour un port dont on sait, par ailleurs, qu'il répond) — traité en MESURE, jamais
# en verdict bloquant. Le verdict qui compte est la tentative de connexion RÉELLE ci-dessous.
ss_line="$(ss -ltn 2>/dev/null | grep -E "127\.0\.0\.1:$NODE_B_PORT\b" || true)"
record_metric XFF-03-ss-visibility "$([[ -n "$ss_line" ]] && echo 1 || echo 0)" "" \
  "ss -ltn a vu le port en écoute (1) ou pas (0) — pas de visibilité réseau fiable dans ce bac à sable, indicatif seulement: '${ss_line:-<rien>}'"

# « Un autre processus de CE poste » : la meilleure approximation à un seul poste d'un attaquant
# distant est une AUTRE interface DU MÊME hôte (Docker bridges, tailscale, ...) plutôt que
# 127.0.0.1. Une VRAIE campagne multi-VM prouverait ceci depuis un second hôte physique — hors de
# portée ici, noté explicitement plutôt que masqué.
OTHER_IP="$(hostname -I 2>/dev/null | tr ' ' '\n' | grep -v '^127\.' | grep -v '^$' | head -1)"
if [[ -n "$OTHER_IP" ]]; then
  out="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://$OTHER_IP:$NODE_B_PORT/stats" 2>/dev/null)"
  rc=$?
  if (( rc != 0 )); then
    record XFF-03-unreachable-from-other-interface PASS \
      "connexion directe depuis $OTHER_IP:$NODE_B_PORT refusée (rc curl=$rc) — le nœud n'écoute QUE sur 127.0.0.1 (RHIZOME_BIND_ADDRESS par défaut), le relais est le SEUL point d'entrée effectif ; une vraie campagne multi-VM referait cette preuve depuis un second hôte physique, pas seulement une autre interface locale"
  else
    record XFF-03-unreachable-from-other-interface FAIL \
      "joignable directement depuis $OTHER_IP:$NODE_B_PORT (code=$out) — la mitigation par liaison boucle-locale est absente, RHIZOME_TRUST_XFF=true n'a alors AUCUN garde-fou"
  fi
else
  record XFF-03-unreachable-from-other-interface METRIC "aucune interface non-loopback trouvée sur cet hôte (hostname -I) — cas non exerçable ici"
fi

echo
echo "== santé finale ==========================================================================="
# Refroidissement AVANT de mesurer : XFF-01 vient de conduire délibérément la fenêtre glissante du
# limiteur (RateLimiter, 1000 ms) au-delà de son budget sous la clé "127.0.0.1" — la même que ce
# contrôle de santé interroge EN DIRECT (pas via le relais). L'interroger à chaud confondrait
# « encore sous l'effet du 429 attendu il y a une seconde » avec « nœud dégradé » (constaté en
# vérification : un FAIL isolé et non reproductible sans ce délai). Deux fenêtres pleines
# suffisent à ce que le RateLimiter réinitialise "prev" (voir son code : elapsed >= 2*windowMs).
sleep 3
expect_eq TLS-FINAL-healthy-a "degraded=null reorg=false" "$(node_healthy "http://127.0.0.1:$NODE_A_PORT")" \
  "NODE_A intact après la rafale XFF-01"
expect_eq TLS-FINAL-healthy-b "degraded=null reorg=false" "$(node_healthy "http://127.0.0.1:$NODE_B_PORT")" \
  "NODE_B intact après la rafale XFF-02"
expect_eq TLS-FINAL-healthy-peertok "degraded=null reorg=false" "$(node_healthy "$PEERTOK_NODE")" \
  "NODE_PEERTOK intact après les échanges avec les deux pairs TLS hostiles"

suite_summary
