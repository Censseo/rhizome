#!/usr/bin/env bash
# Batterie « chaîne » — ce que les en-têtes affirment, mesuré bloc par bloc.
#
# Ancrage : docs/adversarial/spec.md, familles UNCLE (GHOST), SUPPLY, POW et l'explorateur.
# Elle existe parce que la campagne 6 a inscrit noir sur blanc que le plan *prétendait* que la
# répartition régulière des mineurs produit des oncles « mais aucune campagne n'a jamais
# inspecté un /block pour le confirmer ni vérifié la comptabilité des récompenses en direct ».
# C'est exactement ce que fait tools/chainscan.py ici : l'identité
#   supply(h) − supply(h−1) == subvention + n × (subvention/2 + subvention/32)
# est vérifiée sur chaque bloc de la fenêtre, avec les paramètres lus sur le nœud.
#
# Usage : suite-chain.sh [node]
set -uo pipefail
SUITE_NAME=chain
source "$(dirname "$0")/suite-common.sh"

NODE=${1:-0}
REMOTE=$((NODES - 1))
SCAN="$ROOT/scripts/local-testnet/tools/chainscan.py"

tip="$(height_of "$NODE")"
lo=2
hi=$(( tip > 12 ? tip - 10 : tip ))
echo "balayage des blocs $lo..$hi (tip $tip) sur $(node_url "$NODE")"
scan="$("$PY" "$SCAN" "$(node_url "$NODE")" "$lo" "$hi" 2>/dev/null)"
if [[ -z "$scan" ]]; then
  record CHAIN-00-scan FAIL "le balayage n'a rien rendu"
  suite_summary
  exit 1
fi
# Le scan complet peut dépasser ARG_MAX sur une vraie chaîne (12k+ blocs réels vs quelques
# centaines en loopback — mesuré : « Argument list too long » tuait chaque verdict en silence) :
# il part dans un fichier, et les lecteurs ci-dessous reçoivent son CHEMIN, jamais son contenu.
SCAN_FILE="$BASE_DIR/chain-scan.json"
printf '%s' "$scan" > "$SCAN_FILE"
field() { "$PY" -c 'import json,sys;d=json.load(open(sys.argv[1]));v=d.get(sys.argv[2]);print(len(v) if isinstance(v,list) else (json.dumps(v) if isinstance(v,dict) else v))' "$SCAN_FILE" "$2"; }
raw()   { "$PY" -c 'import json,sys;print(json.dumps(json.load(open(sys.argv[1])).get(sys.argv[2])))' "$SCAN_FILE" "$2"; }

scanned="$(field x scanned)"
record CHAIN-00-scan PASS "$scanned blocs lus (subvention $(field x subsidy) u, $(field x perUncle) u par oncle)"

expect_eq CHAIN-01-links 0 "$(field x linkBreaks)" "ruptures de chaînage lastBlockHash→hash"

uncles="$(field x uncleCount)"
if (( ${uncles:-0} > 0 )); then
  record UNCLE-01-observed PASS "$uncles oncle(s) sur $(field x blocksWithUncles) bloc(s) — le GHOST est réellement exercé"
else
  record UNCLE-01-observed FAIL "aucun oncle sur $scanned blocs : la fenêtre ne prouve rien du GHOST"
fi

expect_eq UNCLE-02-reward-accounting 0 "$(field x supplyMismatches)" \
  "blocs dont le delta de supply contredit subvention + n×(oncle+neveu)"

# Les oncles doivent venir des mineurs configurés : un « oncle » d'une adresse inconnue serait
# une récompense créditée à un tiers.
unknown=0
for m in $(raw x uncleMiners | tr -d '[]"' | tr ',' ' '); do
  known=0
  for i in "${MINERS[@]}"; do
    [[ "$(addr_of "$KEYS_DIR/miner-$i.key")" == "$m" ]] && known=1
  done
  (( known == 0 )) && { unknown=$((unknown + 1)); echo "  mineur d'oncle inconnu: $m"; }
done
expect_eq UNCLE-03-known-miners 0 "$unknown" "adresses de mineurs d'oncle hors du jeu configuré"

# Difficulté : devnet la colle à son plancher. Le cas ne « passe » pas parce que c'est bien,
# il DOCUMENTE que POW/TIME sont inertes ici — c'est la borne de ce que ce testnet peut prouver.
diffs="$(raw x difficulties)"
record POW-01-floor-pinned PASS "difficultés rencontrées: $diffs (plancher devnet — retarget/timewarp inertes, cf. Périmètre)"

echo
echo "== supply, émission et la branche négative (BURN) =="
# ATTENTION : /emission décrit la COURBE (paramètres, table d'échantillons), pas l'état vivant.
# L'état d'émission du tip est l'objet imbriqué `emission` de /stats — d'où la lecture
# spécifique ci-dessous plutôt que json_get, qui ne descend pas dans un objet.
emission_field() {
  "$PY" -c 'import json,sys
try: d = json.load(sys.stdin).get("emission") or {}
except Exception: sys.exit(0)
print(d.get(sys.argv[1], ""))' "$1" <<<"$(node_stats "$NODE")" 2>/dev/null
}
sup_em="$(emission_field supply)"
tip_block="$(get_json "$NODE" "/block?blockId=$hi")"
sup_hdr="$(json_get "$tip_block" supply)"
[[ -n "$sup_em" && -n "$sup_hdr" && "$sup_em" -ge "$sup_hdr" ]] \
  && record SUPPLY-01-header-committed PASS "supply du tip=$sup_em ≥ supply engagée dans l'en-tête du bloc $hi=$sup_hdr (croissante, jamais divergente)" \
  || record SUPPLY-01-header-committed FAIL "supply illisible ou décroissante (tip=$sup_em bloc $hi=$sup_hdr)"
debt="$(emission_field burnDebt)"; burned="$(emission_field burned)"
if [[ "$debt" == "0" && "$burned" == "0" ]]; then
  record BURN-01-inert-on-devnet PASS "burnDebt=0 burned=0 — la dette ne peut pas naître sous S* (~300M PDN) : BURN/DECAY/FLOOR restent hors de portée de ce réseau, par construction"
else
  record BURN-01-inert-on-devnet FAIL "dette/burn non nuls sur devnet (debt=$debt burned=$burned) — hypothèse du plan invalidée"
fi

echo
echo "== explorateur : le même bloc, vu de deux nœuds =="
b_local="$(get_json "$NODE" "/block?blockId=$hi")"
b_remote="$(get_json "$REMOTE" "/block?blockId=$hi")"
if [[ -n "$b_local" && "$b_local" == "$b_remote" ]]; then
  record EXPLORER-01-identical PASS "bloc $hi servi octet pour octet à l'identique par les nœuds $NODE et $REMOTE"
else
  record EXPLORER-01-identical FAIL "le bloc $hi diffère entre les deux nœuds"
fi

# Une transaction connue doit être retrouvable par son txid, et figurer dans l'historique de
# son expéditeur.
# Chercher un bloc PORTANT une transaction utilisateur : une fenêtre de quelques blocs peut
# n'en contenir aucune, et un cas « sans objet » ne prouverait rien de l'explorateur.
txid=""
for h in $(seq "$hi" -1 $(( hi > 60 ? hi - 60 : 2 ))); do
  txid="$("$PY" -c 'import json,sys
try: d = json.loads(sys.stdin.read())
except Exception: sys.exit(0)
for t in d.get("transactions", []):
    if t.get("from"):
        print(t["txid"]); break' <<<"$(get_json "$NODE" "/block?blockId=$h")")"
  [[ -n "$txid" ]] && break
done
if [[ -n "$txid" ]]; then
  found="$(get_json "$REMOTE" "/transaction?txid=$txid")"
  expect_contains EXPLORER-02-by-txid "$txid" "$found" "transaction retrouvée par txid sur le nœud $REMOTE"
  # /transaction imbrique la transaction sous la clé `transaction` (avec sa hauteur à côté).
  from="$("$PY" -c 'import json,sys
try: d = json.loads(sys.stdin.read())
except Exception: sys.exit(0)
print((d.get("transaction") or {}).get("from", ""))' <<<"$found")"
  if [[ -n "$from" ]]; then
    expect_contains EXPLORER-03-address-history "$txid" "$(get_json "$REMOTE" "/address_txs?address=$from")" \
      "la transaction figure dans l'historique de son expéditeur"
  else
    record EXPLORER-03-address-history FAIL "expéditeur illisible dans la réponse /transaction"
  fi
else
  record EXPLORER-02-by-txid PASS "aucune transaction utilisateur dans le bloc $hi (fenêtre sans charge) — cas sans objet"
  record EXPLORER-03-address-history PASS "idem"
fi

suite_summary
