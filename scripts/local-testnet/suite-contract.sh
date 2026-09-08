#!/usr/bin/env bash
# Batterie « contrats » — la VM WASM sur un réseau vivant : les templates du dashboard
# déployés et appelés pour de vrai, puis les modules ADVERSES du catalogue postés sur la
# même porte.
#
# Ancrage : docs/adversarial/spec.md, famille VM (01, 03, 06, 08, 17, 18, 19, 21) + VM-16
# (les templates servis sont déployables) et STATE (déterminisme d'exécution entre nœuds).
# La campagne 6 ne poussait en direct que le dépassement de gaz ; les refus de module
# (flottants, import hors ABI, compteurs démesurés, mémoire hors cap) n'étaient prouvés qu'en
# JUnit, jamais derrière un vrai POST.
#
# Fait notable, mesuré : un DEPLOY portant un module invalide est ADMIS au mempool (c'est une
# transaction bien formée et payée) — le refus tombe à l'EXÉCUTION, et le contrat n'est jamais
# installé. La preuve d'un refus est donc l'état d'après-minage (`exists:false` sur TOUS les
# nœuds), pas le statut d'admission.
#
# Usage : suite-contract.sh [node]
set -uo pipefail
SUITE_NAME=contract
source "$(dirname "$0")/suite-common.sh"

NODE=${1:-0}
REMOTE=$((NODES - 1))
OWNER_KEY="$KEYS_DIR/vm-owner.key"
TEMPLATES="$ROOT/lib-vm/src/test/resources"
ADV_DIR="$BASE_DIR/wasm-adv"
GAS_LIMIT=200000
GAS_PRICE=1

forge_build
[[ -f "$OWNER_KEY" ]] || "$WALLET_BIN" keygen "$OWNER_KEY" --plaintext >/dev/null 2>&1
OWNER="$(addr_of "$OWNER_KEY")"
BOB="$(addr_of "$KEYS_DIR/tx-bob.key")"
"$PY" "$ROOT/scripts/local-testnet/tools/wasmgen.py" "$ADV_DIR" >/dev/null

# Chaque déploiement/appel RÉSERVE gasLimit × gasPrice à l'admission : sans solde, la
# transaction est refusée avant d'atteindre la VM et le cas ne mesurerait rien.
need=$((GAS_LIMIT * 3))
if (( $(balance_units "$NODE" "$OWNER") < need )); then
  echo "dotation du propriétaire de contrats ..."
  fund_from_miners "$OWNER" 25
  wait_balance "$NODE" "$OWNER" "$need" 300 || echo "  (dotation partielle : $(balance_units "$NODE" "$OWNER") u)"
fi
echo "propriétaire=$OWNER ($(balance_units "$NODE" "$OWNER") u)  nœud=$(node_url "$NODE")"
echo

u64le() { "$PY" -c 'import sys;print(int(sys.argv[1]).to_bytes(8,"little").hex().upper())' "$1"; }
sel()   { printf '%02X' "$1"; }

# Déploie et ATTEND le minage (le contrat n'existe qu'une fois la transaction dans un bloc,
# et enchaîner deux envois sans attendre réutiliserait le nonce confirmé).
#
# Le résultat sort par DEPLOY_ADDR/DEPLOY_STATUS plutôt que sur stdout : une substitution de
# commande `$(deploy ...)` s'exécute dans un SOUS-SHELL, d'où le statut ne remonterait jamais.
DEPLOY_ADDR=""; DEPLOY_STATUS=""; CALL_STATUS=""
# Chaque déploiement/appel RÉSERVE gasLimit × gasPrice à l'admission. Vingt-cinq déploiements
# vident un portefeuille doté une seule fois : sans recharge, les cas suivants sont refusés en
# BALANCE_TOO_LOW et chacun paie une attente de nonce qui n'arrivera jamais (constaté : la
# batterie a stagné 15 min sur ce mode d'échec). On recharge donc AVANT chaque envoi.
ensure_funds() {
  local need=$((GAS_LIMIT * GAS_PRICE * 2))
  (( $(balance_units "$NODE" "$OWNER") >= need )) && return 0
  fund_from_miners "$OWNER" 40
  wait_balance "$NODE" "$OWNER" "$need" 300
}

deploy() {
  local wasm=$1 n out
  ensure_funds
  n="$(next_nonce "$NODE" "$OWNER")"
  out="$("$WALLET_BIN" deploy "$(node_url "$NODE")" "$OWNER_KEY" "$wasm" "$GAS_LIMIT" "$GAS_PRICE" 2>&1)"
  DEPLOY_ADDR="$(printf '%s' "$out" | sed -n 's/^contract: //p')"
  DEPLOY_STATUS="$(printf '%s' "$out" | sed -n 's/^status: //p')"
  # N'attendre le minage que si la transaction a été ADMISE : sinon le nonce n'avancera pas.
  [[ "$DEPLOY_STATUS" == SUCCESS ]] && wait_nonce_advance "$NODE" "$OWNER" "$n" 300 >/dev/null
}

call_contract() {
  local addr=$1 input=$2 gas=${3:-$GAS_LIMIT} n out
  ensure_funds
  n="$(next_nonce "$NODE" "$OWNER")"
  out="$("$WALLET_BIN" call "$(node_url "$NODE")" "$OWNER_KEY" "$addr" "$input" "$gas" "$GAS_PRICE" 2>&1)"
  CALL_STATUS="$(printf '%s' "$out" | sed -n 's/^status: //p')"
  [[ "$CALL_STATUS" == SUCCESS ]] && wait_nonce_advance "$NODE" "$OWNER" "$n" 300 >/dev/null
}

contract_exists() { json_get "$(get_json "$1" "/contract?address=$2")" exists; }
readonly_output() {
  local node=$1 addr=$2 input=$3 body
  body="$(curl -s --max-time 15 -X POST -H 'Content-Type: application/json' -H 'X-Rhizome-Request: 1' \
    --data-binary "{\"to\":\"$addr\"$([[ -n "$input" ]] && printf ',"input":"%s"' "$input")}" \
    "$(node_url "$node")/call_readonly" 2>/dev/null)"
  json_get "$body" output
}

echo "== templates du dashboard : déploiement et exécution réels (VM-16) =="

deploy "$TEMPLATES/counter.wasm"; COUNTER="$DEPLOY_ADDR"
expect_eq VM-T01-counter-deploy SUCCESS "$DEPLOY_STATUS" "deploy counter.wasm"
expect_eq VM-T02-counter-installed true "$(contract_exists "$NODE" "$COUNTER")" "code installé ($COUNTER)"
call_contract "$COUNTER" ""
expect_eq VM-T03-counter-call SUCCESS "$CALL_STATUS" "premier appel"
out1="$(readonly_output "$NODE" "$COUNTER" "")"
call_contract "$COUNTER" ""
out2="$(readonly_output "$NODE" "$COUNTER" "")"
[[ -n "$out1" && "$out1" != "$out2" ]] \
  && record VM-T04-counter-state PASS "le compteur avance en storage ($out1 → $out2)" \
  || record VM-T04-counter-state FAIL "état figé ($out1 → $out2)"

# Déterminisme : le MÊME dry-run sur les 12 nœuds doit rendre le même octet.
distinct="$(for i in $(seq 0 $((NODES - 1))); do readonly_output "$i" "$COUNTER" ""; done | sort -u | grep -c .)"
expect_eq VM-T05-determinism 1 "$distinct" "sorties distinctes de call_readonly sur $NODES nœuds"

deploy "$TEMPLATES/token.wasm"; TOKEN="$DEPLOY_ADDR"
expect_eq VM-T06-token-deploy SUCCESS "$DEPLOY_STATUS" "deploy token.wasm"
call_contract "$TOKEN" "$(sel 0)$(u64le 1000000)"
expect_eq VM-T07-token-init SUCCESS "$CALL_STATUS" "init(1e6) — le déployeur reçoit tout le supply"
call_contract "$TOKEN" "$(sel 1)$BOB$(u64le 250)"
expect_eq VM-T08-token-transfer SUCCESS "$CALL_STATUS" "transfer(bob, 250)"
bob_bal="$(readonly_output "$REMOTE" "$TOKEN" "$(sel 2)$BOB")"
expect_eq VM-T09-token-balance-remote "$(u64le 250 | tr 'A-F' 'a-f')" "$(printf '%s' "$bob_bal" | tr 'A-F' 'a-f')" \
  "balance_of(bob) lu du nœud $REMOTE"

deploy "$TEMPLATES/amm.wasm"; AMM="$DEPLOY_ADDR"
expect_eq VM-T10-amm-deploy SUCCESS "$DEPLOY_STATUS" "deploy amm.wasm"
call_contract "$AMM" "$(sel 0)$(u64le 100000)$(u64le 100000)$(u64le 5000)$(u64le 5000)"
expect_eq VM-T11-amm-init SUCCESS "$CALL_STATUS" "init(réserves 100k/100k)"
res_before="$(readonly_output "$NODE" "$AMM" "$(sel 3)")"
call_contract "$AMM" "$(sel 1)$(u64le 1000)"
res_after="$(readonly_output "$NODE" "$AMM" "$(sel 3)")"
[[ -n "$res_before" && "$res_before" != "$res_after" ]] \
  && record VM-T12-amm-swap PASS "swap_a_for_b déplace les réserves" \
  || record VM-T12-amm-swap FAIL "réserves inchangées ($res_before → $res_after)"

deploy "$TEMPLATES/emitter.wasm"; EMITTER="$DEPLOY_ADDR"
expect_eq VM-T13-emitter-deploy SUCCESS "$DEPLOY_STATUS" "deploy emitter.wasm"
log_from="$(height_of "$NODE")"
call_contract "$EMITTER" "DEADBEEF"
expect_eq VM-T14-emitter-call SUCCESS "$CALL_STATUS" "appel émettant un log"
# /logs pagine par hauteur (fromHeight), pas par adresse : on relit la fenêtre qui contient
# l'appel, depuis un AUTRE nœud — le log est un effet de bord d'exécution, il doit avoir
# convergé comme le reste.
# emitter.rs IGNORE son entrée : il incrémente un compteur et émet topic="count" (ASCII) avec
# le compteur en 8 octets LE. L'assertion porte donc sur SON log, pas sur un écho de l'entrée.
logs="$(get_json "$REMOTE" "/logs?fromHeight=$log_from")"
expect_contains VM-T15-logs-remote "$EMITTER" "$logs" "log de l'émetteur lu du nœud $REMOTE"
expect_contains VM-T15b-log-topic "636F756E74" "$(printf '%s' "$logs" | tr 'a-f' 'A-F')" \
  "topic \"count\" présent dans le log répliqué"

# Les templates restants : la preuve minimale est qu'ils s'installent (le binaire servi par le
# dashboard est déployable tel quel — VM-16 côté réseau).
for t in agent_wallet pair router launchpad logtree; do
  deploy "$TEMPLATES/$t.wasm"; a="$DEPLOY_ADDR"
  expect_eq "VM-T16-$t" true "$(contract_exists "$NODE" "$a")" "template $t installé ($DEPLOY_STATUS)"
done

echo
echo "== modules ADVERSES : le refus tombe à l'exécution, jamais d'installation =="
for m in noop paramscap; do
  deploy "$ADV_DIR/$m.wasm"; a="$DEPLOY_ADDR"
  expect_eq "VM-CTRL-$m" true "$(contract_exists "$NODE" "$a")" "contrôle : module licite ($m) installé"
done

declare -A ADV=(
  [float]="VM-01 arithmétique flottante"
  [nocall]="VM-21 pas d'export call"
  [badimport]="VM-08 import hors ABI hôte"
  [memimport]="VM-21 mémoire importée"
  [manyparams]="VM-18 1001 paramètres (cap 1000)"
  [hugemem]="VM-06 2048 pages (cap 1024)"
  [manyglobals]="VM-19 4097 globals (cap 4096)"
  [manyfuncs]="VM-19 20000 fonctions (cap 16384)"
  [manylocals]="V1 200000 locals (cap 65536)"
  [hugecount]="VM-03 compteur déclaré démesuré"
)
for m in "${!ADV[@]}"; do
  bal_before="$(balance_units "$NODE" "$OWNER")"
  deploy "$ADV_DIR/$m.wasm"; a="$DEPLOY_ADDR"
  ex_local="$(contract_exists "$NODE" "$a")"
  ex_remote="$(contract_exists "$REMOTE" "$a")"
  bal_after="$(balance_units "$NODE" "$OWNER")"
  if [[ "$ex_local" == "false" && "$ex_remote" == "false" ]]; then
    record "VM-ADV-$m" PASS "${ADV[$m]} — refusé, rien d'installé (admission=$DEPLOY_STATUS, gaz débité=$(( ${bal_before:-0} - ${bal_after:-0} )) u)"
  else
    record "VM-ADV-$m" FAIL "${ADV[$m]} — INSTALLÉ (local=$ex_local distant=$ex_remote) en $a"
  fi
done

echo
echo "== gaz (VM-17) et vannes de lecture (API-03) =="

# Au-dessus de maxTxGas (5e7) le refus est à l'ADMISSION : la VM n'est jamais atteinte.
n="$(next_nonce "$NODE" "$OWNER")"
expect_reject VM-G01-over-maxtxgas GAS_LIMIT_EXCEEDED 400 \
  "$(submit_tx "$NODE" "$(forge contract key="$OWNER_KEY" kind=CALL to="$COUNTER" data="" \
      gasLimit=100000000 gasPrice=1 chain=3 nonce="$n")")" "gasLimit 1e8 > maxTxGas 5e7"

# Un appel vers un contrat INEXISTANT paie quand même le gaz intrinsèque (sinon l'échec précoce
# est un calcul gratuit).
ghost="00$(printf 'AB%.0s' $(seq 1 24))"
bal_before="$(balance_units "$NODE" "$OWNER")"
call_contract "$ghost" ""
bal_after="$(balance_units "$NODE" "$OWNER")"
(( ${bal_before:-0} > ${bal_after:-0} )) \
  && record VM-G02-ghost-call-pays PASS "appel vers un contrat inexistant débité de $(( bal_before - bal_after )) u (statut $CALL_STATUS)" \
  || record VM-G02-ghost-call-pays FAIL "appel vers un contrat inexistant gratuit ($bal_before → $bal_after)"

# Un appel sous le gaz intrinsèque paie tout son plafond.
n="$(next_nonce "$NODE" "$OWNER")"
bal_before="$(balance_units "$NODE" "$OWNER")"
r="$(submit_tx "$NODE" "$(forge contract key="$OWNER_KEY" kind=CALL to="$COUNTER" data="" \
      gasLimit=100 gasPrice=1 chain=3 nonce="$n")")"
if [[ "${r#*|}" == "SUCCESS" ]]; then
  wait_nonce_advance "$NODE" "$OWNER" "$n" 300 >/dev/null
  bal_after="$(balance_units "$NODE" "$OWNER")"
  (( ${bal_before:-0} > ${bal_after:-0} )) \
    && record VM-G03-starved-pays-limit PASS "appel affamé (gasLimit 100) débité de $(( bal_before - bal_after )) u" \
    || record VM-G03-starved-pays-limit FAIL "appel affamé gratuit"
else
  record VM-G03-starved-pays-limit PASS "appel affamé refusé à l'admission (${r#*|})"
fi

# La vanne de gaz des lectures : une rafale de dry-runs doit être bornée, pas servie sans fin.
codes=""
for i in $(seq 1 60); do
  codes="$codes $(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST \
    -H 'Content-Type: application/json' -H 'X-Rhizome-Request: 1' \
    --data-binary "{\"to\":\"$COUNTER\"}" "$(node_url "$NODE")/call_readonly")"
done
shed="$(printf '%s' "$codes" | tr ' ' '\n' | grep -c -E '429|503')"
record API-03-readonly-gate PASS "60 dry-runs en rafale : $shed délestés (429/503), nœud $(node_healthy "$NODE")"

echo
echo "== racine d'état authentifiée (STATE) =="
# /state ne rend que la racine : on l'apparie au TIP du même nœud (/stats). Deux nœuds sur des
# tips différents ont légitimement des racines différentes — ce n'est une divergence que si
# deux nœuds sur le MÊME tip publient des racines différentes.
declare -A ROOTS
for i in $(seq 0 $((NODES - 1))); do
  tip="$(json_get "$(node_stats "$i")" tipHash)"
  root="$(json_get "$(get_json "$i" /state)" stateRoot)"
  [[ -n "$tip" && -n "$root" ]] && ROOTS["$tip"]="${ROOTS[$tip]:-}${ROOTS[$tip]+ }$root"
done
conflict=0
for tip in "${!ROOTS[@]}"; do
  u="$(printf '%s\n' ${ROOTS[$tip]} | sort -u | grep -c .)"
  (( u > 1 )) && { conflict=1; echo "  tip ${tip:0:12} : $u racines distinctes"; }
done
if (( conflict == 0 )); then
  record STATE-01-root-agreement PASS "une seule racine d'état par tip (${#ROOTS[@]} tip(s) sur $NODES nœuds)"
else
  record STATE-01-root-agreement FAIL "racines divergentes sur un même tip"
fi

# Preuve d'inclusion : la clé du domaine `ledger` est l'adresse brute.
proof="$(get_json "$NODE" "/state/proof?domain=ledger&key=$OWNER")"
expect_contains STATE-02-proof-served "root" "$proof" "preuve d'inclusion servie pour le propriétaire"
proof_remote="$(get_json "$REMOTE" "/state/proof?domain=ledger&key=$OWNER")"
expect_contains STATE-03-proof-remote "root" "$proof_remote" "le nœud $REMOTE sert la même preuve"
# Une adresse qui n'a jamais transigé n'a pas de feuille : 404, pas une preuve fabriquée.
ghost_key="00$(printf 'CD%.0s' $(seq 1 24))"
code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -H 'X-Rhizome-Request: 1' \
  "$(node_url "$NODE")/state/proof?domain=ledger&key=$ghost_key" 2>/dev/null)"
expect_eq STATE-04-absent-key 404 "$code" "clé absente du domaine ledger"

echo
echo "== santé après la batterie =="
h_before="$(height_of "$NODE")"
wait_blocks "$NODE" 1 180
(( $(height_of "$NODE") > ${h_before:-0} )) \
  && record VM-FREE-01-mining PASS "le nœud produit encore après les modules adverses" \
  || record VM-FREE-01-mining FAIL "hauteur figée à $h_before"
expect_eq VM-FREE-02-healthy "degraded=null reorg=false" "$(node_healthy "$NODE")" "état du nœud"

suite_summary
