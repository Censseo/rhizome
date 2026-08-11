#!/usr/bin/env bash
# Simulateur de contrats : déploie un compteur et un token WASM sur le réseau, puis appelle
# les deux en boucle depuis des nœuds tirés au hasard, en vérifiant périodiquement que l'état
# lu est IDENTIQUE sur un échantillon de nœuds (déterminisme d'exécution, S9).
#
# Usage : sim-contract.sh start [-i <intervalle s>] [-c <clés>]
#         sim-contract.sh stop
#         sim-contract.sh status
#         sim-contract.sh check          # comparaison d'état immédiate sur tout le réseau
#
# Gaz : le protocole accepte gasPrice 0 (le mempool n'impose aucun plancher, MemPool L163)
# mais le wallet CLI le REFUSE (« invalid gasPrice: 0, must be between 1 and 1e11 ») — un
# garde-fou côté client contre une tx qu'aucun mineur n'aurait intérêt à inclure. On travaille
# donc à gasPrice 1, ce qui impose de dimensionner gasLimit : le montant RÉSERVÉ à l'admission
# est gasLimit × gasPrice (MemPool L378), soit 100 000 unités de base = 10 PDN ici. À ~2,78 PDN
# de récompense par bloc, un gasLimit de plusieurs millions dépasserait le solde du
# portefeuille et le simulateur se ferait refuser pour insuffisance de fonds sans jamais
# atteindre la VM. Le portefeuille est doté par plusieurs mineurs pour la même raison.
set -euo pipefail
source "$(dirname "$0")/common.sh"

SIM_DIR="$BASE_DIR/sim"
CSV="$SIM_DIR/contract.csv"
STATE="$SIM_DIR/contracts.env"
INTERVAL="${RHIZOME_SIM_CONTRACT_INTERVAL:-5}"
GAS_LIMIT="${RHIZOME_SIM_GAS_LIMIT:-100000}"
GAS_PRICE="${RHIZOME_SIM_GAS_PRICE:-1}"
FUND="${RHIZOME_SIM_CONTRACT_FUND:-5}"
TEMPLATES="$ROOT/app-node/src/main/resources/dashboard/templates"
KEY="$KEYS_DIR/sim-contract.key"
PIDF="$PID_DIR/sim-contract.pid"

# Encodage de l'ABI des templates (templates/manifest.json) : « payload = octet de sélecteur
# suivi des arguments », u64 en little-endian sur 8 octets, adresse = 25 octets bruts.
u64le() { "$PY" -c 'import sys;print(int(sys.argv[1]).to_bytes(8,"little").hex().upper())' "$1"; }

wallet_units() {
  "$WALLET_BIN" balance "$(node_url "$1")" "$2" 2>/dev/null \
    | sed -n 's/.*(\([0-9]*\) base units)/\1/p'
}

# Attend qu'un appel/déploiement soit effectivement exécuté : le contrat n'existe qu'une fois
# la tx minée, et enchaîner deux tx sans attendre réutiliserait le nonce confirmé.
wait_nonce() {
  local addr=$1 from=$2 deadline=$((SECONDS + 90)) n
  while (( SECONDS < deadline )); do
    n="$("$WALLET_BIN" balance "$(node_url 0)" "$addr" 2>/dev/null | sed -n 's/^nextNonce: //p')"
    [[ -n "$n" && "$n" != "$from" ]] && return 0
    sleep 1
  done
  return 1
}

# Dote le portefeuille de contrats. Chaque appel réserve gasLimit × gasPrice à l'admission :
# sans solde, la tx est refusée avant même d'atteindre la VM. On tire sur PLUSIEURS mineurs
# parce qu'un seul ne gagne que ~2,78 PDN par bloc produit.
fund_owner() {
  local me=$1 need=$2 m units got=0
  for m in "${MINERS[@]}"; do
    units="$(wallet_units 0 "$me")"
    [[ -n "$units" ]] && (( units >= need * 10000 )) && return 0
    units="$(wallet_units "$m" "$("$WALLET_BIN" address "$KEYS_DIR/miner-$m.key")")"
    # `A || B && continue` renvoie un statut d'échec quand ni A ni B ne sont vrais — sous
    # `set -e` cela tuait le script SANS un mot au premier mineur suffisamment doté.
    if [[ -z "$units" ]] || (( units < FUND * 10000 )); then
      continue
    fi
    "$WALLET_BIN" send "$(node_url "$m")" "$KEYS_DIR/miner-$m.key" "$me" "$FUND" >/dev/null 2>&1 \
      && got=$((got + FUND))
  done
  echo "dotation du portefeuille de contrats : $got PDN demandés à ${#MINERS[@]} mineurs"
  local deadline=$((SECONDS + 300))
  while (( SECONDS < deadline )); do
    units="$(wallet_units 0 "$me")"
    [[ -n "$units" ]] && (( units >= need * 10000 )) && return 0
    sleep 3
  done
  echo "ERREUR: portefeuille de contrats non doté (besoin ${need} PDN)" >&2
  return 1
}

deploy_all() {
  mkdir -p "$SIM_DIR" "$KEYS_DIR" "$PID_DIR"
  [[ -f "$KEY" ]] || "$WALLET_BIN" keygen "$KEY" --plaintext >/dev/null
  local me nonce out counter token
  me="$("$WALLET_BIN" address "$KEY")"
  # Réserve nécessaire : gasLimit × gasPrice unités de base, converties en PDN, avec de la
  # marge pour enchaîner déploiements et appels sans re-doter à chaque tour.
  fund_owner "$me" "$(( GAS_LIMIT * GAS_PRICE / 10000 * 4 + 1 ))"

  nonce="$("$WALLET_BIN" balance "$(node_url 0)" "$me" | sed -n 's/^nextNonce: //p')"
  out="$("$WALLET_BIN" deploy "$(node_url 0)" "$KEY" "$TEMPLATES/counter.wasm" \
        "$GAS_LIMIT" "$GAS_PRICE")"
  counter="$(sed -n 's/^contract: //p' <<<"$out")"
  wait_nonce "$me" "$nonce" || { echo "ERREUR: déploiement du compteur jamais miné" >&2; return 1; }

  nonce="$("$WALLET_BIN" balance "$(node_url 0)" "$me" | sed -n 's/^nextNonce: //p')"
  out="$("$WALLET_BIN" deploy "$(node_url 1)" "$KEY" "$TEMPLATES/token.wasm" \
        "$GAS_LIMIT" "$GAS_PRICE")"
  token="$(sed -n 's/^contract: //p' <<<"$out")"
  wait_nonce "$me" "$nonce" || { echo "ERREUR: déploiement du token jamais miné" >&2; return 1; }

  # init(supply) — sélecteur 0 : mint tout le supply au caller.
  nonce="$("$WALLET_BIN" balance "$(node_url 0)" "$me" | sed -n 's/^nextNonce: //p')"
  "$WALLET_BIN" call "$(node_url 2)" "$KEY" "$token" "00$(u64le 1000000000)" \
    "$GAS_LIMIT" "$GAS_PRICE" >/dev/null
  wait_nonce "$me" "$nonce" || { echo "ERREUR: init du token jamais miné" >&2; return 1; }

  printf 'COUNTER=%s\nTOKEN=%s\nOWNER=%s\n' "$counter" "$token" "$me" > "$STATE"
  echo "compteur déployé : $counter"
  echo "token déployé    : $token (supply 1 000 000 000 au propriétaire $me)"
}

STATE_CHECK="$ROOT/scripts/local-testnet/statecheck.py"

# Compare l'état du compteur ET le solde token du propriétaire sur tous les nœuds. Le
# détail — lecture parallèle, groupement par tip, réjection des nœuds qui bougent pendant
# leur propre lecture — est dans statecheck.py, qui explique aussi pourquoi la version
# séquentielle via le wallet ne pouvait pas conclure.
check_state() {
  source "$STATE"
  "$PY" "$STATE_CHECK" "$BASE_PORT" "${1:-$NODES}" "$COUNTER" "$TOKEN" "$OWNER"
}

loop() {
  source "$STATE"
  # Voir sim-tx.sh : sous `errexit`, le premier appel wallet en échec (nœud arrêté pendant une
  # partition) tuerait la boucle sans un mot.
  set +e
  trap 'exit 0' TERM INT
  local n round=0 status target nonce0
  while :; do
    round=$((round + 1))
    n=$((RANDOM % NODES))
    nonce0="$("$WALLET_BIN" balance "$(node_url 0)" "$OWNER" 2>/dev/null | sed -n 's/^nextNonce: //p')"
    # Alternance compteur / transfert de token : le premier écrit une clé de storage, le
    # second en écrit deux (débit + crédit), donc des journaux d'undo de tailles différentes
    # à rejouer en cas de reorg.
    if (( round % 2 == 1 )); then
      status=$("$WALLET_BIN" call "$(node_url "$n")" "$KEY" "$COUNTER" "" "$GAS_LIMIT" "$GAS_PRICE" \
        2>&1 | sed -n 's/^status: //p'); status="${status:-FAILED}"
      printf '%s,counter,%d,%s\n' "$(date +%H:%M:%S)" "$n" "$status" >> "$CSV"
    else
      target="$("$WALLET_BIN" address "$KEYS_DIR/miner-${MINERS[$((RANDOM % ${#MINERS[@]}))]}.key")"
      status=$("$WALLET_BIN" call "$(node_url "$n")" "$KEY" "$TOKEN" "01$target$(u64le 1000)" \
        "$GAS_LIMIT" "$GAS_PRICE" 2>&1 | sed -n 's/^status: //p'); status="${status:-FAILED}"
      printf '%s,token-transfer,%d,%s\n' "$(date +%H:%M:%S)" "$n" "$status" >> "$CSV"
    fi
    # Chaque appel réserve gasLimit × gasPrice à l'admission : le portefeuille se vide en
    # quelques dizaines d'appels et la boucle tournerait ensuite à vide sur BALANCE_TOO_LOW.
    if [[ "$status" == "BALANCE_TOO_LOW" ]]; then
      fund_owner "$OWNER" "$(( GAS_LIMIT * GAS_PRICE / 10000 * 4 + 1 ))" >> "$SIM_DIR/refund.log" 2>&1
    fi
    # Le nonce confirmé doit avancer avant l'appel suivant (même contrainte que sim-tx : le
    # nonce servi par le nœud est le confirmé, pas celui du mempool).
    [[ -n "$nonce0" ]] && { wait_nonce "$OWNER" "$nonce0" || true; }
    # Contrôle de déterminisme périodique sur un échantillon (tout le réseau une fois sur dix).
    if (( round % 10 == 0 )); then
      check_state "$NODES" >> "$SIM_DIR/check.log" 2>&1 || true
    fi
    sleep "$INTERVAL"
  done
}

case "${1:-start}" in
  start)
    shift || true
    while getopts "i:" opt; do
      case "$opt" in i) INTERVAL=$OPTARG ;; *) echo "usage: $0 start [-i sec]" >&2; exit 2 ;; esac
    done
    [[ -f "$STATE" ]] || deploy_all
    [[ -f "$CSV" ]] || echo "time,action,node,status" > "$CSV"
    setsid bash -c 'echo $$ > "$1"; shift; exec env "$@"' _ \
      "$PIDF" "RHIZOME_SIM_CONTRACT_INTERVAL=$INTERVAL" "$0" loop \
      >> "$SIM_DIR/contract.log" 2>&1 &
    for _ in $(seq 1 20); do [[ -s "$PIDF" ]] && break; sleep 0.1; done
    source "$STATE"
    echo "simulateur de contrats démarré (compteur $COUNTER, token $TOKEN, intervalle ${INTERVAL}s)."
    ;;
  stop)
    if [[ -s "$PIDF" ]]; then
      kill "$(cat "$PIDF")" 2>/dev/null || true
      sleep 1
      kill -9 "$(cat "$PIDF")" 2>/dev/null || true
      rm -f "$PIDF"
      echo "simulateur de contrats arrêté."
    else
      echo "aucun simulateur de contrats en cours"
    fi
    ;;
  status)
    [[ -f "$CSV" ]] || { echo "aucun appel enregistré"; return 0 2>/dev/null || exit 0; }
    echo "appels: $(( $(wc -l < "$CSV") - 1 ))"
    tail -n +2 "$CSV" | cut -d, -f2,4 | sort | uniq -c | sort -rn
    [[ -f "$SIM_DIR/check.log" ]] && { echo "derniers contrôles d'état:"; tail -3 "$SIM_DIR/check.log"; }
    ;;
  check)  check_state "$NODES" ;;
  loop)   loop ;;
  *)      echo "usage: $0 {start|stop|status|check}" >&2; exit 2 ;;
esac
