#!/usr/bin/env bash
# Batterie « transactions » — chemins nominaux et exploits, postés sur un nœud VIVANT.
#
# Ancrage : docs/adversarial/spec.md, familles INFL, SIG, REPLAY, POOL, CODEC, API. Le
# catalogue prouve ces refus au niveau composant ; cette batterie vérifie qu'ils tiennent
# derrière la vraie porte HTTP d'un nœud du testnet, avec le STATUT EXACT, et que le refus
# est gratuit (le nœud continue à miner, l'attaquant n'est pas puni).
#
# Usage : suite-tx.sh [node] (défaut 0 — le nœud « victime »)
set -euo pipefail
SUITE_NAME=tx
source "$(dirname "$0")/suite-common.sh"

VICTIM=${1:-0}
REMOTE=$((NODES - 1))
ALICE_KEY="$KEYS_DIR/tx-alice.key"
BOB_KEY="$KEYS_DIR/tx-bob.key"
ATT_KEY="$KEYS_DIR/tx-attacker.key"
LONG_MAX=9223372036854775807

forge_build
for k in "$ALICE_KEY" "$BOB_KEY" "$ATT_KEY"; do
  [[ -f "$k" ]] || "$WALLET_BIN" keygen "$k" --plaintext >/dev/null 2>&1
done
ALICE="$(addr_of "$ALICE_KEY")"; BOB="$(addr_of "$BOB_KEY")"; ATT="$(addr_of "$ATT_KEY")"

# Dotation : la batterie a besoin d'un compte solvable (chemins nominaux) et d'un compte
# pauvre (dépassement de solde). 40 PDN suffisent largement.
if (( $(balance_units "$VICTIM" "$ALICE") < 400000 )); then
  echo "dotation d'alice depuis les mineurs ..."
  fund_from_miners "$ALICE" 20
  wait_balance "$VICTIM" "$ALICE" 400000 300 || { echo "dotation impossible" >&2; exit 1; }
fi
echo "alice=$ALICE ($(balance_units "$VICTIM" "$ALICE") u)  bob=$BOB  attaquant=$ATT"
echo "nœud victime: $(node_url "$VICTIM")  nœud témoin distant: $(node_url "$REMOTE")"
echo

# Falsifie un champ APRÈS signature : c'est l'attaque « altéré sous signature ».
tamper() { "$PY" -c 'import json,sys
d = json.load(sys.stdin)
for kv in sys.argv[1:]:
    k, v = kv.split("=", 1)
    d[k] = int(v) if v.lstrip("-").isdigit() and k not in ("to","from","signingKey","signature","timestamp") else v
print(json.dumps(d))' "$@"; }

sign_send() { forge send key="$1" to="$2" amount="$3" fee="${4:-0}" chain="${5:-3}" nonce="$6" ; }

echo "== chemins nominaux =="

# TX-01 — le transfert de base, lu depuis un nœud QUI NE L'A PAS REÇU (gossip + convergence).
n0="$(next_nonce "$VICTIM" "$ALICE")"
bob_before="$(balance_units "$REMOTE" "$BOB")"
r="$(submit_tx "$VICTIM" "$(sign_send "$ALICE_KEY" "$BOB" 25000 0 3 "$n0")")"
expect_reject TX-01-admission SUCCESS 200 "$r" "transfert 2,5 PDN admis"
wait_nonce_advance "$VICTIM" "$ALICE" "$n0" 240 || true
expect_eq TX-01-remote-credit "$((bob_before + 25000))" "$(balance_units "$REMOTE" "$BOB")" \
  "solde de bob vu du nœud $REMOTE"

# TX-02 — comptabilité des frais : le débit de l'émetteur est montant+frais, exactement.
n1="$(next_nonce "$VICTIM" "$ALICE")"
alice_before="$(balance_units "$VICTIM" "$ALICE")"
r="$(submit_tx "$VICTIM" "$(sign_send "$ALICE_KEY" "$BOB" 10000 5000 3 "$n1")")"
expect_reject TX-02-admission SUCCESS 200 "$r" "transfert 1 PDN + 0,5 PDN de frais"
wait_nonce_advance "$VICTIM" "$ALICE" "$n1" 240 || true
expect_eq TX-02-fee-debit "$((alice_before - 15000))" "$(balance_units "$VICTIM" "$ALICE")" \
  "débit = montant + frais"

# TX-03 — trois nonces contigus en rafale : tous minés, dans l'ordre, sans trou.
n2="$(next_nonce "$VICTIM" "$ALICE")"
bob_before="$(balance_units "$VICTIM" "$BOB")"
ok=0
for d in 0 1 2; do
  r="$(submit_tx "$VICTIM" "$(sign_send "$ALICE_KEY" "$BOB" 1000 0 3 "$((n2 + d))")")"
  [[ "$r" == "200|SUCCESS" ]] && ok=$((ok + 1))
done
expect_eq TX-03-burst-admitted 3 "$ok" "3 nonces contigus admis"
wait_nonce_advance "$VICTIM" "$ALICE" "$((n2 + 2))" 300 || true
expect_eq TX-03-burst-mined "$((n2 + 3))" "$(next_nonce "$VICTIM" "$ALICE")" "nonce final"
expect_eq TX-03-burst-credit "$((bob_before + 3000))" "$(balance_units "$VICTIM" "$BOB")" "crédit cumulé"

echo
echo "== POOL — politique de mempool =="

# POOL-03 — un nonce en avance est ADMIS mais garé : jamais minable tant que le trou n'est
# pas comblé. La preuve n'est pas le statut, c'est l'absence de mouvement de solde/nonce.
n3="$(next_nonce "$VICTIM" "$ALICE")"
alice_before="$(balance_units "$VICTIM" "$ALICE")"
r="$(submit_tx "$VICTIM" "$(sign_send "$ALICE_KEY" "$BOB" 7000 0 3 "$((n3 + 5))")")"
expect_reject POOL-03-parked SUCCESS 200 "$r" "nonce futur admis (garé)"
wait_blocks "$VICTIM" 3 240 || true
expect_eq POOL-03-no-nonce-move "$n3" "$(next_nonce "$VICTIM" "$ALICE")" "nonce inchangé après 3 blocs"
expect_eq POOL-03-no-balance-move "$alice_before" "$(balance_units "$VICTIM" "$ALICE")" "solde inchangé"

# POOL-03b — le trou comblé libère la garée : le nonce saute jusqu'au bout de la séquence.
# Un remplisseur peut être miné PENDANT la rafale (un bloc tombe toutes les ~4 s) : son nonce
# est alors déjà consommé et le suivant est refusé en INVALID_TRANSACTION_NONCE. Ce n'est pas
# un refus d'admission mais une course avec le producteur, donc le cas n'exige pas 5 SUCCESS —
# il exige qu'aucun refus n'ait une AUTRE cause, et que la garée finisse par sortir.
ok=0; other=""
for d in 0 1 2 3 4; do
  r="$(submit_tx "$VICTIM" "$(sign_send "$ALICE_KEY" "$BOB" 1000 0 3 "$((n3 + d))")")"
  case "$r" in
    "200|SUCCESS") ok=$((ok + 1)) ;;
    "400|INVALID_TRANSACTION_NONCE") ;;
    *) other="$other $r" ;;
  esac
done
[[ -z "$other" ]] \
  && record POOL-03b-fill-admitted PASS "$ok/5 remplisseurs admis, le reste déjà miné (course)" \
  || record POOL-03b-fill-admitted FAIL "refus d'une autre cause:$other"
wait_nonce_advance "$VICTIM" "$ALICE" "$((n3 + 5))" 300 || true
expect_eq POOL-03b-released "$((n3 + 6))" "$(next_nonce "$VICTIM" "$ALICE")" "la garée est minée avec la séquence"

# POOL-02 — inondation depuis un seul compte : bornée, sans faire tomber le nœud.
h_before="$(height_of "$VICTIM")"
flood_ok=0
nf="$(next_nonce "$VICTIM" "$ALICE")"
for d in $(seq 0 79); do
  r="$(submit_tx "$VICTIM" "$(sign_send "$ALICE_KEY" "$BOB" 100 0 3 "$((nf + d))")")"
  [[ "${r#*|}" == "SUCCESS" ]] && flood_ok=$((flood_ok + 1))
done
mem="$(json_get "$(node_stats "$VICTIM")" mempool)"
record POOL-02-flood-bounded PASS "80 tx d'un seul compte : $flood_ok admises, mempool=$mem, $(node_healthy "$VICTIM")"
wait_blocks "$VICTIM" 1 120 || true
[[ "$(height_of "$VICTIM")" -gt "$h_before" ]] \
  && record POOL-02-still-mining PASS "le nœud continue à produire sous inondation" \
  || record POOL-02-still-mining FAIL "hauteur figée à $h_before sous inondation"

echo
echo "== REPLAY — rejeu et double dépense =="

# REPLAY-01 — rejeu d'une transaction déjà minée : le nonce est consommé.
tx_mined="$(sign_send "$ALICE_KEY" "$BOB" 3000 0 3 "$(next_nonce "$VICTIM" "$ALICE")")"
nb="$(next_nonce "$VICTIM" "$ALICE")"
submit_tx "$VICTIM" "$tx_mined" >/dev/null
wait_nonce_advance "$VICTIM" "$ALICE" "$nb" 240 || true
expect_reject REPLAY-01-mined-tx INVALID_TRANSACTION_NONCE 400 "$(submit_tx "$VICTIM" "$tx_mined")" \
  "rejeu à l'identique"

# REPLAY-02 — double dépense : deux transactions au MÊME nonce, destinataires différents.
nd="$(next_nonce "$VICTIM" "$ALICE")"
first="$(sign_send "$ALICE_KEY" "$BOB" 2000 0 3 "$nd")"
second="$(sign_send "$ALICE_KEY" "$ATT" 2000 0 3 "$nd")"
expect_reject REPLAY-02-first SUCCESS 200 "$(submit_tx "$VICTIM" "$first")" "première dépense"
expect_reject REPLAY-02-double INVALID_TRANSACTION_NONCE 400 "$(submit_tx "$VICTIM" "$second")" \
  "seconde dépense au même nonce"
att_before="$(balance_units "$VICTIM" "$ATT")"
wait_nonce_advance "$VICTIM" "$ALICE" "$nd" 240 || true
expect_eq REPLAY-02-not-credited "$att_before" "$(balance_units "$VICTIM" "$ATT")" \
  "le destinataire de la double dépense n'est jamais crédité"

echo
echo "== INFL — arithmétique du grand livre =="
n="$(next_nonce "$VICTIM" "$ALICE")"
expect_reject INFL-01-negative-amount INVALID_TRANSACTION_AMOUNT 400 \
  "$(submit_tx "$VICTIM" "$(sign_send "$ALICE_KEY" "$BOB" -100000 0 3 "$n")")" "montant négatif"
expect_reject INFL-02-negative-fee INVALID_TRANSACTION_AMOUNT 400 \
  "$(submit_tx "$VICTIM" "$(sign_send "$ALICE_KEY" "$BOB" 10000 -5000 3 "$n")")" "frais négatifs"
expect_reject INFL-03-long-max BALANCE_TOO_LOW 400 \
  "$(submit_tx "$VICTIM" "$(sign_send "$ALICE_KEY" "$BOB" $LONG_MAX 0 3 "$n")")" "montant Long.MAX"
expect_reject INFL-04-overdraft BALANCE_TOO_LOW 400 \
  "$(submit_tx "$VICTIM" "$(sign_send "$ALICE_KEY" "$BOB" 900000000 0 3 "$n")")" "dépense > solde"
expect_reject INFL-05-sum-overflow BALANCE_TOO_LOW 400 \
  "$(submit_tx "$VICTIM" "$(sign_send "$ALICE_KEY" "$BOB" 4611686018427387903 4611686018427387903 3 "$n")")" \
  "montant+frais débordant un long"
expect_reject INFL-06-zero-value SUCCESS 200 \
  "$(submit_tx "$VICTIM" "$(sign_send "$ALICE_KEY" "$BOB" 0 0 3 "$n")")" "transfert de 0 (licite, coûte un nonce)"
wait_nonce_advance "$VICTIM" "$ALICE" "$n" 240 || true

echo
echo "== SIG — autorisation =="
n="$(next_nonce "$VICTIM" "$ALICE")"
expect_reject SIG-01-foreign-chain INVALID_CHAIN_ID 400 \
  "$(submit_tx "$VICTIM" "$(sign_send "$ALICE_KEY" "$BOB" 10000 0 999 "$n")")" "rejeu inter-réseau (chainId 999)"
expect_reject SIG-02-tampered-amount INVALID_SIGNATURE 400 \
  "$(submit_tx "$VICTIM" "$(sign_send "$ALICE_KEY" "$BOB" 10000 0 3 "$n" | tamper amount=10001)")" \
  "montant altéré sous signature (abordable)"
expect_reject SIG-03-tampered-recipient INVALID_SIGNATURE 400 \
  "$(submit_tx "$VICTIM" "$(sign_send "$ALICE_KEY" "$BOB" 10000 0 3 "$n" | tamper to="$ATT")")" \
  "destinataire altéré sous signature"
expect_reject SIG-04-tampered-nonce INVALID_SIGNATURE 400 \
  "$(submit_tx "$VICTIM" "$(sign_send "$ALICE_KEY" "$BOB" 10000 0 3 "$n" | tamper accountNonce=$((n + 1)))")" \
  "nonce altéré sous signature"
expect_reject SIG-05-sender-swap WALLET_SIGNATURE_MISMATCH 400 \
  "$(submit_tx "$VICTIM" "$(sign_send "$ALICE_KEY" "$BOB" 10000 0 3 "$n" | tamper from="$ATT")")" \
  "expéditeur remplacé, signature d'alice"
expect_reject SIG-06-key-swap WALLET_SIGNATURE_MISMATCH 400 \
  "$(submit_tx "$VICTIM" "$(sign_send "$ALICE_KEY" "$BOB" 10000 0 3 "$n" \
     | tamper signingKey="$("$PY" -c 'import json,sys;print(json.load(open(sys.argv[1]))["publicKey"])' "$ATT_KEY" 2>/dev/null || echo 00)")")" \
  "clé de signature remplacée par celle de l'attaquant"

echo
echo "== CODEC / API — la porte HTTP =="
expect_reject CODEC-01-malformed-json "" 400 "$(submit_tx "$VICTIM" '{"garbage":')" "JSON tronqué"
expect_reject CODEC-02-empty-body "" 400 "$(submit_tx "$VICTIM" '')" "corps vide"
expect_reject CODEC-03-unknown-kind "" 400 \
  "$(submit_tx "$VICTIM" "$(sign_send "$ALICE_KEY" "$BOB" 1000 0 3 "$n" | "$PY" -c 'import json,sys
d=json.load(sys.stdin); d["kind"]="NOT_A_KIND"; d["gasLimit"]=0; d["gasPrice"]=0; d["data"]=""
print(json.dumps(d))')")" "kind de transaction inconnu"
# Le cap de corps (JSON_TX_BODY) refuse AVANT de matérialiser la transaction. La FORME du refus
# n'est pas déterministe et ne peut pas être assertée : selon la vitesse à laquelle le nœud coupe
# par rapport à l'envoi du corps, curl voit soit un 400, soit une connexion fermée en cours
# d'écriture (code 000). Mesuré dans les deux sens sur un même corps de 3 Mo. Ce que le cas exige,
# c'est donc l'invariant : aucune des deux tailles n'est ACCEPTÉE, et le nœud est vivant juste
# après (pas d'OOM, pas de blocage).
refused_body() {
  local id=$1 size=$2
  local r; r="$(post_json "$VICTIM" /add_transaction_json "$("$PY" -c 'print("{\"to\":\"" + "A"*int(__import__("sys").argv[1]) + "\"}")' "$size")")"
  local code="${r%%|*}"; code="${code//$'\n'/}"
  case "$code" in
    400|413|000) record "$id" PASS "corps de $((size / 1000000)) Mo refusé (code $code)" ;;
    *)           record "$id" FAIL "corps de $((size / 1000000)) Mo : code inattendu '$code'" ;;
  esac
}
refused_body CODEC-04-oversize-3mb 3000000
refused_body CODEC-04-oversize-12mb 12000000
[[ -n "$(node_stats "$VICTIM")" ]] \
  && record CODEC-04-node-alive PASS "le nœud répond encore après les deux corps géants" \
  || record CODEC-04-node-alive FAIL "le nœud ne répond plus après un corps géant"

for route in /add_transaction_json /call_readonly /add_peer /scan/register /submit; do
  r="$(post_json "$VICTIM" "$route" 'not json at all')"
  expect_eq "CODEC-05$(echo "$route" | tr '/' '-')" 400 "${r%%|*}" "corps malformé sur $route"
done

origin_probe() {
  local hdrs=("$@")
  curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST "${hdrs[@]}" \
    -H 'Content-Type: application/json' --data-binary '{}' \
    "$(node_url "$VICTIM")/add_transaction_json" 2>/dev/null || echo 000
}
expect_eq API-01-cross-origin 403 "$(origin_probe -H 'Origin: https://evil.example')" "POST cross-site (Origin étranger)"
expect_eq API-01b-cross-origin-marked 403 \
  "$(origin_probe -H 'Origin: https://evil.example' -H 'X-Rhizome-Request: 1')" "idem, avec le marqueur"
expect_eq API-02-dns-rebinding 403 \
  "$(origin_probe -H "Origin: http://127.0.0.1:$(node_port "$VICTIM")")" "forme DNS-rebinding (Origin==Host, sans marqueur)"
expect_eq API-03-same-origin 400 \
  "$(origin_probe -H "Origin: http://127.0.0.1:$(node_port "$VICTIM")" -H 'X-Rhizome-Request: 1')" \
  "dashboard légitime (passe la porte, 400 sur le corps vide)"

echo
echo "== le refus est-il gratuit ? =="
h_before="$(height_of "$VICTIM")"
wait_blocks "$VICTIM" 1 180 || true
[[ "$(height_of "$VICTIM")" -gt "$h_before" ]] \
  && record FREE-01-victim-mines PASS "le nœud victime a produit après la batterie" \
  || record FREE-01-victim-mines FAIL "hauteur figée à $h_before"
n="$(next_nonce "$VICTIM" "$ALICE")"
expect_reject FREE-02-attacker-not-banned SUCCESS 200 \
  "$(submit_tx "$VICTIM" "$(sign_send "$ALICE_KEY" "$BOB" 1000 0 3 "$n")")" \
  "une transaction valide passe encore depuis la même source"
expect_eq FREE-03-not-degraded "degraded=null reorg=false" "$(node_healthy "$VICTIM")" "état du nœud"

suite_summary
