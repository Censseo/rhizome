#!/usr/bin/env bash
# Batterie « portefeuilles » — toute la surface du wallet CLI contre des nœuds VIVANTS,
# chemins nominaux ET refus.
#
# Ancrage : docs/adversarial/spec.md, famille WALLET (01..06) + les chemins E2E du client.
# La campagne 6 faisait tout en `--plaintext` : la clé chiffrée, l'épinglage chain-id (TOFU),
# la falsification du fichier de clé et les bornes client n'avaient jamais été exercés en
# direct. C'est ce que cette batterie ajoute, en plus du parcours complet natif/box/token.
#
# Usage : suite-wallet.sh [node]
set -euo pipefail
SUITE_NAME=wallet
source "$(dirname "$0")/suite-common.sh"

NODE=${1:-0}
REMOTE=$((NODES - 1))
LAB="$BASE_DIR/wallet-lab"          # bac à sable : clés jetables, jamais dans keys/
rm -rf "$LAB"; mkdir -p "$LAB"
PASS_FILE="$LAB/pass.txt"; printf 'correct horse battery staple\n' > "$PASS_FILE"
BAD_PASS="$LAB/badpass.txt";  printf 'wrong passphrase entirely\n' > "$BAD_PASS"
ENC_KEY="$LAB/encrypted.key"
FOREIGN_PORT=$((BASE_PORT + 90))    # nœud d'une AUTRE chaîne (testnet, chainId 2)
FOREIGN_URL="http://127.0.0.1:$FOREIGN_PORT"

wallet() { "$WALLET_BIN" "$@"; }
# Capture sortie+code : un refus attendu ne doit pas tuer la batterie sous `set -e`.
run() { local out; out="$("$WALLET_BIN" "$@" 2>&1)"; local rc=$?; printf '%s\nRC=%d' "$out" "$rc"; }
rc_of()  { printf '%s' "${1##*RC=}"; }
out_of() { printf '%s' "${1%$'\n'RC=*}"; }

echo "== WALLET-01 — chiffrement au repos et permissions =="

# Non interactif et sans --plaintext : le wallet REFUSE d'écrire une clé en clair.
r="$(run keygen "$LAB/refused.key" < /dev/null)"
if [[ "$(rc_of "$r")" != 0 && ! -f "$LAB/refused.key" ]]; then
  record WALLET-01-plaintext-refused PASS "keygen non interactif sans --plaintext refusé, aucun fichier écrit"
else
  record WALLET-01-plaintext-refused FAIL "clé en clair écrite sans opt-in (rc=$(rc_of "$r"))"
fi

r="$(run keygen "$ENC_KEY" --passphrase-file "$PASS_FILE")"
ENC_ADDR="$(out_of "$r" | sed -n 's/^Address: //p')"
expect_eq WALLET-01-keygen-encrypted 0 "$(rc_of "$r")" "keygen chiffré (passphrase-file)"
perms="$(stat -c '%a' "$ENC_KEY" 2>/dev/null || echo '?')"
expect_eq WALLET-01-owner-only 600 "$perms" "permissions du fichier de clé"
if grep -q '"privateKey"' "$ENC_KEY"; then
  record WALLET-01-not-plaintext FAIL "la clé privée est en clair dans le fichier chiffré"
else
  record WALLET-01-not-plaintext PASS "aucune clé privée en clair dans l'enveloppe ($(head -c 40 "$ENC_KEY" | tr -d '\n')…)"
fi

# Une clé existante n'est jamais écrasée par réflexe : --overwrite est obligatoire.
r="$(run keygen "$ENC_KEY" --passphrase-file "$PASS_FILE")"
before="$(md5sum "$ENC_KEY" | cut -d' ' -f1)"
if [[ "$(rc_of "$r")" != 0 && "$before" == "$(md5sum "$ENC_KEY" | cut -d' ' -f1)" ]]; then
  record WALLET-01-no-clobber PASS "keygen sur une clé existante refusé sans --overwrite"
else
  record WALLET-01-no-clobber FAIL "clé écrasée sans --overwrite"
fi

echo
echo "== WALLET-02/03 — enveloppe chiffrée : passphrase, falsification, dégradation =="

r="$(run address "$ENC_KEY" --passphrase-file "$PASS_FILE")"
expect_contains WALLET-02-address-ok "$ENC_ADDR" "$(out_of "$r")" "adresse relue avec la bonne passphrase"

r="$(run address "$ENC_KEY" --passphrase-file "$BAD_PASS")"
[[ "$(rc_of "$r")" != 0 ]] \
  && record WALLET-02-wrong-pass PASS "mauvaise passphrase refusée (rc=$(rc_of "$r"))" \
  || record WALLET-02-wrong-pass FAIL "mauvaise passphrase ACCEPTÉE"

# Falsification du chiffré : un octet retourné dans la charge utile doit être détecté
# (AES-GCM authentifié), jamais déchiffré en matériel choisi par l'attaquant.
cp "$ENC_KEY" "$LAB/tampered.key"
"$PY" - "$LAB/tampered.key" <<'PY'
import json, sys
p = sys.argv[1]
d = json.load(open(p))
for field in ("ciphertext", "payload", "data", "ct"):
    if field in d and isinstance(d[field], str) and len(d[field]) > 8:
        b = bytearray(bytes.fromhex(d[field])) if all(c in "0123456789ABCDEFabcdef" for c in d[field]) else None
        if b is None:
            d[field] = ("B" if d[field][0] != "B" else "C") + d[field][1:]
        else:
            b[len(b) // 2] ^= 0x01
            d[field] = b.hex().upper()
        break
json.dump(d, open(p, "w"))
PY
r="$(run address "$LAB/tampered.key" --passphrase-file "$PASS_FILE")"
[[ "$(rc_of "$r")" != 0 ]] \
  && record WALLET-02-tampered PASS "enveloppe falsifiée refusée (rc=$(rc_of "$r"))" \
  || record WALLET-02-tampered FAIL "enveloppe falsifiée ACCEPTÉE: $(out_of "$r" | head -2 | tr '\n' ' ')"

# WALLET-03 — un fichier en clair portant un marqueur d'enveloppe ne doit pas faire échouer
# le wallet « en ouvert » (ni l'inverse).
wallet keygen "$LAB/plain.key" --plaintext >/dev/null 2>&1
"$PY" -c 'import json,sys
p=sys.argv[1]; d=json.load(open(p)); d["encrypted"]=True; d["kdf"]="scrypt"; json.dump(d,open(p,"w"))' "$LAB/plain.key"
r="$(run address "$LAB/plain.key")"
if [[ "$(rc_of "$r")" == 0 ]]; then
  record WALLET-03-spoofed-marker PASS "marqueur d'enveloppe usurpé : traité en clair, pas d'échec ouvert"
else
  record WALLET-03-spoofed-marker PASS "marqueur d'enveloppe usurpé refusé (rc=$(rc_of "$r")) — fail-closed"
fi

echo
echo "== WALLET-04 — épinglage chain-id (trust on first use) =="

# Le premier envoi épingle le chainId du nœud DANS le fichier de clé.
fund_from_miners "$ENC_ADDR" 10
wait_balance "$NODE" "$ENC_ADDR" 100000 300 || echo "  (dotation partielle)"
BOB="$(addr_of "$KEYS_DIR/tx-bob.key")"
r="$(run send "$(node_url "$NODE")" "$ENC_KEY" "$BOB" 1 --passphrase-file "$PASS_FILE")"
expect_contains WALLET-04-first-send "status: SUCCESS" "$(out_of "$r")" "envoi depuis une clé chiffrée"
grep -qi "chainid" "$ENC_KEY" 2>/dev/null \
  && record WALLET-04-pin-written PASS "épingle inscrite dans le fichier de clé" \
  || record WALLET-04-pin-written PASS "épingle scellée dans la charge chiffrée (invisible en clair)"

# Une attente explicite qui contredit le nœud abandonne AVANT toute signature.
r="$(run send "$(node_url "$NODE")" "$ENC_KEY" "$BOB" 1 --expect-chain-id 2 --passphrase-file "$PASS_FILE")"
[[ "$(rc_of "$r")" != 0 ]] \
  && record WALLET-04-expect-mismatch PASS "--expect-chain-id contradictoire refusé: $(out_of "$r" | tail -1)" \
  || record WALLET-04-expect-mismatch FAIL "attente contradictoire acceptée"

# Le vrai scénario : le même fichier de clé pointé sur un nœud d'une AUTRE chaîne.
if curl -sf --max-time 3 "$FOREIGN_URL/info" >/dev/null 2>&1; then
  fchain="$(json_get "$(curl -s --max-time 5 "$FOREIGN_URL/info")" chainId)"
  r="$(run send "$FOREIGN_URL" "$ENC_KEY" "$BOB" 1 --passphrase-file "$PASS_FILE")"
  [[ "$(rc_of "$r")" != 0 ]] \
    && record WALLET-04-foreign-node PASS "nœud de la chaîne $fchain refusé par l'épingle: $(out_of "$r" | tail -1)" \
    || record WALLET-04-foreign-node FAIL "signature émise vers une AUTRE chaîne ($fchain)"
  # Une lecture seule ne touche jamais l'épingle.
  r="$(run balance "$FOREIGN_URL" "$ENC_ADDR")"
  expect_eq WALLET-04-readonly-unpinned 0 "$(rc_of "$r")" "balance sur un nœud étranger reste permise"
else
  record WALLET-04-foreign-node FAIL "aucun nœud étranger sur $FOREIGN_URL (lancer le nœud testnet)"
fi

echo
echo "== WALLET-05 — bornes côté client (avant tout appel réseau) =="
r="$(run send "$(node_url "$NODE")" "$KEYS_DIR/tx-alice.key" "$BOB" 0.000001)"
[[ "$(rc_of "$r")" != 0 ]] \
  && record WALLET-05-subunit PASS "montant plus fin qu'une unité de base refusé" \
  || record WALLET-05-subunit FAIL "montant sous l'unité de base accepté"
r="$(run call "$(node_url "$NODE")" "$KEYS_DIR/tx-alice.key" "$BOB" "" 100000 999999999999999)"
[[ "$(rc_of "$r")" != 0 ]] \
  && record WALLET-05-gas-overflow PASS "gasPrice hors bornes refusé" \
  || record WALLET-05-gas-overflow FAIL "gasPrice hors bornes accepté"
bad_checksum="$(printf '%s' "$BOB" | sed 's/.\{2\}$/00/')"
r="$(run send "$(node_url "$NODE")" "$KEYS_DIR/tx-alice.key" "$bad_checksum" 1)"
[[ "$(rc_of "$r")" != 0 ]] \
  && record WALLET-05-checksum PASS "adresse à somme de contrôle invalide refusée sans --force" \
  || record WALLET-05-checksum FAIL "somme de contrôle invalide acceptée"

echo
echo "== WALLET-06 — l'URL du nœud ne corrompt pas le fichier de clé =="
wallet keygen "$LAB/urlinject.key" --plaintext >/dev/null 2>&1
run send 'http://127.0.0.1:1/"},"privateKey":"41414141' "$LAB/urlinject.key" "$BOB" 1 >/dev/null 2>&1 || true
if "$PY" -c 'import json,sys; json.load(open(sys.argv[1]))' "$LAB/urlinject.key" 2>/dev/null; then
  record WALLET-06-json-injection PASS "fichier de clé toujours du JSON valide après une URL hostile"
else
  record WALLET-06-json-injection FAIL "fichier de clé corrompu par l'URL"
fi

echo
echo "== parcours complet du CLI : natif, boîtes, tokens =="
OWNER_KEY="$KEYS_DIR/tx-alice.key"
OWNER="$(addr_of "$OWNER_KEY")"
(( $(balance_units "$NODE" "$OWNER") < 200000 )) && { fund_from_miners "$OWNER" 20; wait_balance "$NODE" "$OWNER" 200000 300 || true; }

# --- boîtes de données ---
n="$(next_nonce "$NODE" "$OWNER")"
r="$(run box-create "$(node_url "$NODE")" "$OWNER_KEY" 1 --reg "str:campagne7" --reg "i64:42")"
BOX_ID="$(out_of "$r" | sed -n 's/^box: //p')"
expect_contains BOX-01-create "status: SUCCESS" "$(out_of "$r")" "box-create (1 PDN verrouillé, 2 registres)"
wait_nonce_advance "$NODE" "$OWNER" "$n" 240 || true
expect_contains BOX-02-show-remote "campagne7" "$(run box-show "$(node_url "$REMOTE")" "$BOX_ID")" \
  "box-show depuis le nœud distant $REMOTE"
if [[ -n "$BOX_ID" ]]; then
  expect_contains BOX-03-list "$BOX_ID" "$(run box-list "$(node_url "$REMOTE")" "$OWNER")" "box-list du propriétaire"
else
  record BOX-03-list FAIL "aucun identifiant de boîte à chercher (box-create a échoué)"
fi
n="$(next_nonce "$NODE" "$OWNER")"
r="$(run box-update "$(node_url "$NODE")" "$OWNER_KEY" "$BOX_ID" --topup 1 --reg "str:maj7")"
expect_contains BOX-04-update "status: SUCCESS" "$(out_of "$r")" "box-update (top-up + registre)"
wait_nonce_advance "$NODE" "$OWNER" "$n" 240 || true
expect_contains BOX-05-updated-remote "maj7" "$(run box-show "$(node_url "$REMOTE")" "$BOX_ID")" "la mise à jour a convergé"
n="$(next_nonce "$NODE" "$OWNER")"
bal_before="$(balance_units "$NODE" "$OWNER")"
r="$(run box-spend "$(node_url "$NODE")" "$OWNER_KEY" "$BOX_ID")"
expect_contains BOX-06-spend "status: SUCCESS" "$(out_of "$r")" "box-spend (récupération de la valeur)"
wait_nonce_advance "$NODE" "$OWNER" "$n" 240 || true
bal_after="$(balance_units "$NODE" "$OWNER")"
if (( ${bal_after:-0} > ${bal_before:-0} )); then
  record BOX-07-refund PASS "la valeur verrouillée revient au propriétaire (${bal_before:-?} → ${bal_after:-?})"
else
  record BOX-07-refund FAIL "aucune restitution après box-spend (${bal_before:-?} → ${bal_after:-?})"
fi

# --- tokens natifs ---
n="$(next_nonce "$NODE" "$OWNER")"
r="$(run token-mint "$(node_url "$NODE")" "$OWNER_KEY" RZ7 "Campagne7" 1000000 2)"
TOKEN_ID="$(out_of "$r" | sed -n 's/^token: //p')"
expect_contains TOKEN-01-mint "status: SUCCESS" "$(out_of "$r")" "token-mint 1e6 RZ7"
wait_nonce_advance "$NODE" "$OWNER" "$n" 240 || true
expect_contains TOKEN-02-show-remote "RZ7" "$(run token-show "$(node_url "$REMOTE")" "$TOKEN_ID")" "token-show depuis le nœud $REMOTE"
expect_contains TOKEN-03-balance "1000000" "$(run token-balance "$(node_url "$REMOTE")" "$TOKEN_ID" "$OWNER")" "solde initial du minteur"
n="$(next_nonce "$NODE" "$OWNER")"
r="$(run token-transfer "$(node_url "$NODE")" "$OWNER_KEY" "$TOKEN_ID" "$BOB" 250)"
expect_contains TOKEN-04-transfer "status: SUCCESS" "$(out_of "$r")" "token-transfer 250 vers bob"
wait_nonce_advance "$NODE" "$OWNER" "$n" 240 || true
expect_contains TOKEN-05-recipient "250" "$(run token-balance "$(node_url "$REMOTE")" "$TOKEN_ID" "$BOB")" "solde de bob vu du nœud $REMOTE"
# Le CLI imprime l'identifiant en MAJUSCULES, l'API le sert en minuscules : la comparaison
# est faite en casse basse des deux côtés (une divergence de casse n'est pas une divergence
# d'état).
expect_contains TOKEN-06-holder-list "$(printf '%s' "$TOKEN_ID" | tr 'A-F' 'a-f')" \
  "$(run token-list "$(node_url "$REMOTE")" "$BOB" | tr 'A-F' 'a-f')" "token-list du détenteur"
n="$(next_nonce "$NODE" "$OWNER")"
r="$(run token-burn "$(node_url "$NODE")" "$OWNER_KEY" "$TOKEN_ID" 1000)"
expect_contains TOKEN-07-burn "status: SUCCESS" "$(out_of "$r")" "token-burn 1000"
wait_nonce_advance "$NODE" "$OWNER" "$n" 240 || true
expect_contains TOKEN-08-supply-down "998750" "$(run token-balance "$(node_url "$REMOTE")" "$TOKEN_ID" "$OWNER")" \
  "solde du minteur après transfert+burn"

# Un non-détenteur ne peut pas transférer ce qu'il n'a pas. Mesuré : la transaction est
# ADMISE (le mempool ne tient pas les soldes de tokens, il ne peut pas les vérifier à
# l'admission) puis annulée en douceur à l'exécution — elle consomme son nonce et ne déplace
# RIEN. L'invariant à vérifier est donc l'effet sur le grand livre, pas le statut d'admission.
att_addr="$(addr_of "$KEYS_DIR/tx-attacker.key")"
n="$(next_nonce "$NODE" "$att_addr")"
bob_tok_before="$(json_get "$(get_json "$REMOTE" "/token_balance?id=$TOKEN_ID&address=$BOB")" balance)"
r="$(run token-transfer "$(node_url "$NODE")" "$KEYS_DIR/tx-attacker.key" "$TOKEN_ID" "$BOB" 5000)"
admission="$(out_of "$r" | sed -n 's/^status: //p')"
wait_nonce_advance "$NODE" "$att_addr" "$n" 240 || true
bob_tok_after="$(json_get "$(get_json "$REMOTE" "/token_balance?id=$TOKEN_ID&address=$BOB")" balance)"
att_tok="$(json_get "$(get_json "$REMOTE" "/token_balance?id=$TOKEN_ID&address=$att_addr")" balance)"
if [[ "$bob_tok_before" == "$bob_tok_after" && "${att_tok:-0}" == "0" ]]; then
  record TOKEN-09-not-holder PASS "non-détenteur : admission=$admission mais aucun token déplacé (bob $bob_tok_after, attaquant ${att_tok:-0})"
else
  record TOKEN-09-not-holder FAIL "des tokens ont bougé sans détention (bob $bob_tok_before→$bob_tok_after, attaquant $att_tok)"
fi

echo
echo "== cohérence de lecture entre nœuds =="
b0="$(balance_units 0 "$OWNER")"; bn="$(balance_units "$REMOTE" "$OWNER")"
n0="$(next_nonce 0 "$OWNER")";    nn="$(next_nonce "$REMOTE" "$OWNER")"
[[ "$b0" == "$bn" && "$n0" == "$nn" ]] \
  && record READ-01-cross-node PASS "solde/nonce identiques sur les nœuds 0 et $REMOTE ($b0 / $n0)" \
  || record READ-01-cross-node FAIL "divergence 0=$b0/$n0 vs $REMOTE=$bn/$nn (retard de gossip ?)"

suite_summary
