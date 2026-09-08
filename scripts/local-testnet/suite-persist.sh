#!/usr/bin/env bash
# Batterie « persistance » — l'état APPLICATIF (contrats, tokens, boîtes, grand livre)
# survit-il à un arrêt propre puis à un SIGKILL, bit pour bit ?
#
# Ancrage : docs/adversarial/spec.md, famille PERS (01..05, adversaire A6) et E2E-17. La
# campagne 6 a fermé le crash `kill -9` pour le GRAND LIVRE (S19) ; ce qu'aucune campagne n'a
# jamais rejoué en direct, c'est le même crash avec de l'état de CONTRAT et de TOKEN au sol —
# précisément les domaines qui ont leurs propres journaux d'undo et leurs propres stores.
#
# Le témoin est comparé au même TIP qu'un nœud resté vivant : comparer deux nœuds à des
# hauteurs différentes ne prouverait rien.
#
# Usage : suite-persist.sh [nœud à malmener] (défaut 5 — un observateur, jamais un mineur)
set -uo pipefail
SUITE_NAME=persist
source "$(dirname "$0")/suite-common.sh"

TARGET=${1:-5}
WITNESS=$((NODES - 1))
STATE_FILE="$BASE_DIR/sim/contracts.env"

is_miner "$TARGET" && echo "note : le nœud $TARGET est un mineur — le réseau perd un producteur pendant la fenêtre"

# Le témoin d'état : un contrat compteur, un token natif, une boîte, un solde. On réutilise ce
# que les batteries précédentes ont posé sur la chaîne s'il existe, sinon on le crée.
OWNER_KEY="$KEYS_DIR/vm-owner.key"
OWNER="$(addr_of "$OWNER_KEY")"
COUNTER="${RHIZOME_PERSIST_COUNTER:-}"
if [[ -z "$COUNTER" ]]; then
  COUNTER="$(grep -oE '^COUNTER=.*' "$STATE_FILE" 2>/dev/null | cut -d= -f2)"
fi
if [[ -z "$COUNTER" ]]; then
  echo "déploiement d'un compteur témoin ..."
  n="$(next_nonce 0 "$OWNER")"
  out="$("$WALLET_BIN" deploy "$(node_url 0)" "$OWNER_KEY" "$ROOT/lib-vm/src/test/resources/counter.wasm" 200000 1 2>&1)"
  COUNTER="$(printf '%s' "$out" | sed -n 's/^contract: //p')"
  wait_nonce_advance 0 "$OWNER" "$n" 300 >/dev/null
  n="$(next_nonce 0 "$OWNER")"
  "$WALLET_BIN" call "$(node_url 0)" "$OWNER_KEY" "$COUNTER" "" 200000 1 >/dev/null 2>&1
  wait_nonce_advance 0 "$OWNER" "$n" 300 >/dev/null
fi
echo "compteur témoin : $COUNTER"

readonly_output() {
  local node=$1 addr=$2
  json_get "$(curl -s --max-time 15 -X POST -H 'Content-Type: application/json' \
    -H 'X-Rhizome-Request: 1' --data-binary "{\"to\":\"$addr\"}" \
    "$(node_url "$node")/call_readonly" 2>/dev/null)" output
}

# Empreinte d'état d'un nœud, au tip qu'il déclare. Le tip fait partie de l'empreinte : deux
# nœuds ne sont comparables qu'à tip égal.
fingerprint() {
  local node=$1 s
  s="$(node_stats "$node")"
  printf 'tip=%s h=%s root=%s counter=%s owner=%s nonce=%s' \
    "$(json_get "$s" tipHash)" "$(json_get "$s" height)" \
    "$(json_get "$(get_json "$node" /state)" stateRoot)" \
    "$(readonly_output "$node" "$COUNTER")" \
    "$(balance_units "$node" "$OWNER")" "$(next_nonce "$node" "$OWNER")"
}

# Attend que deux nœuds annoncent le MÊME tip (donc comparables).
wait_same_tip() {
  local a=$1 b=$2 deadline=$((SECONDS + ${3:-300}))
  while (( SECONDS < deadline )); do
    local ta tb
    ta="$(json_get "$(node_stats "$a")" tipHash)"
    tb="$(json_get "$(node_stats "$b")" tipHash)"
    [[ -n "$ta" && "$ta" == "$tb" ]] && return 0
    sleep 2
  done
  return 1
}

log_errors() {
  grep -ciE "corrupt|BufferUnderflow|FATAL|failed to open|SIGSEGV" "$(log_file "$1")" 2>/dev/null || echo 0
}

run_cycle() {
  local mode=$1 signal=$2 label=$3
  echo
  echo "== $label =="
  wait_same_tip "$TARGET" "$WITNESS" 300 || echo "  (tips non alignés avant l'arrêt — comparaison au rattrapage)"
  local before_h; before_h="$(height_of "$TARGET")"
  local errs_before; errs_before="$(log_errors "$TARGET")"

  local pid; pid="$(cat "$(pid_file "$TARGET")" 2>/dev/null)"
  if [[ -z "$pid" ]]; then
    record "PERS-$mode-kill" FAIL "aucun pid pour le nœud $TARGET"
    return
  fi
  kill "-$signal" "$pid" 2>/dev/null
  local waited=0
  while kill -0 "$pid" 2>/dev/null && (( waited < 30 )); do sleep 1; waited=$((waited + 1)); done
  kill -0 "$pid" 2>/dev/null \
    && { record "PERS-$mode-kill" FAIL "le nœud $TARGET a survécu au signal $signal"; return; }
  record "PERS-$mode-kill" PASS "nœud $TARGET tué par SIG$signal à la hauteur $before_h"
  rm -f "$(pid_file "$TARGET")"

  # Le réseau survit à la perte du nœud.
  local h_net; h_net="$(height_of "$WITNESS")"
  wait_blocks "$WITNESS" 2 240
  (( $(height_of "$WITNESS") > ${h_net:-0} )) \
    && record "PERS-$mode-network-alive" PASS "les autres nœuds ont continué à produire pendant la fenêtre de mort" \
    || record "PERS-$mode-network-alive" FAIL "le réseau s'est arrêté avec le nœud $TARGET"

  "$ROOT/scripts/local-testnet/start.sh" -n "$TARGET" >/dev/null 2>&1
  local up=0
  for _ in $(seq 1 60); do [[ -n "$(node_stats "$TARGET")" ]] && { up=1; break; }; sleep 2; done
  (( up == 1 )) \
    && record "PERS-$mode-reopen" PASS "base rouverte et /stats servi après redémarrage" \
    || { record "PERS-$mode-reopen" FAIL "le nœud $TARGET n'est pas revenu"; return; }

  local errs_after; errs_after="$(log_errors "$TARGET")"
  (( errs_after == errs_before )) \
    && record "PERS-$mode-no-corruption" PASS "aucune trace de corruption dans le journal (compte inchangé: $errs_after)" \
    || record "PERS-$mode-no-corruption" FAIL "$((errs_after - errs_before)) nouvelle(s) trace(s) de corruption au redémarrage"

  local after_h; after_h="$(height_of "$TARGET")"
  (( ${after_h:-0} >= ${before_h:-0} )) \
    && record "PERS-$mode-not-truncated" PASS "chaîne non tronquée ($before_h → $after_h)" \
    || record "PERS-$mode-not-truncated" FAIL "chaîne revenue en arrière ($before_h → $after_h)"

  if wait_same_tip "$TARGET" "$WITNESS" 300; then
    local fa fb
    fa="$(fingerprint "$TARGET")"; fb="$(fingerprint "$WITNESS")"
    [[ "$fa" == "$fb" ]] \
      && record "PERS-$mode-bit-identical" PASS "état identique au témoin: $fa" \
      || record "PERS-$mode-bit-identical" FAIL "divergence — ressuscité: $fa | témoin: $fb"
  else
    record "PERS-$mode-bit-identical" FAIL "le nœud n'a pas rejoint le tip du témoin en 5 min"
  fi

  local deg; deg="$(node_healthy "$TARGET")"
  expect_eq "PERS-$mode-healthy" "degraded=null reorg=false" "$deg" "état du nœud ressuscité"
}

echo "empreinte de départ (nœud $TARGET) : $(fingerprint "$TARGET")"
run_cycle graceful TERM "arrêt PROPRE (SIGTERM) puis redémarrage — E2E-17"
run_cycle crash    KILL "crash DUR (SIGKILL) en pleine production — PERS/A6"

echo
echo "== l'état applicatif a-t-il survécu aux deux morts ? =="
if wait_same_tip "$TARGET" "$WITNESS" 300; then
  ok=1
  c_a="$(readonly_output "$TARGET" "$COUNTER")"; c_b="$(readonly_output "$WITNESS" "$COUNTER")"
  [[ -n "$c_a" && "$c_a" == "$c_b" ]] || ok=0
  r_a="$(json_get "$(get_json "$TARGET" /state)" stateRoot)"
  r_b="$(json_get "$(get_json "$WITNESS" /state)" stateRoot)"
  [[ -n "$r_a" && "$r_a" == "$r_b" ]] || ok=0
  (( ok == 1 )) \
    && record PERS-FINAL-contract-state PASS "état de contrat et racine SMT identiques au témoin (counter=$c_a root=${r_a:0:16}…)" \
    || record PERS-FINAL-contract-state FAIL "contrat=$c_a/$c_b racine=${r_a:0:16}/${r_b:0:16}"
else
  record PERS-FINAL-contract-state FAIL "tips non alignés en fin de batterie"
fi

suite_summary
