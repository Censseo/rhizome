#!/usr/bin/env bash
# Simulateur de transactions : W portefeuilles indépendants qui s'envoient en boucle de
# petits montants, chacun via un nœud tiré au hasard dans le réseau.
#
# Usage : sim-tx.sh start [-w <workers>] [-a <montant PDN>] [-f <dotation PDN>]
#         sim-tx.sh stop
#         sim-tx.sh status
#
# Pourquoi un portefeuille PAR worker : `nextNonce` est le nonce CONFIRMÉ (NodeService
# L909 — le mempool n'est pas consulté). Deux envois concurrents depuis la même adresse
# signeraient donc le même nonce et le second serait rejeté. Chaque worker possède sa clé et
# attend la confirmation de son envoi précédent (le nonce qui avance) avant le suivant : la
# cadence s'auto-régule sur celle des blocs, ce qui est exactement la charge qu'on veut —
# une file de txs valides, jamais un flot de doublons invalides qui pénaliserait le pair.
#
# Le nœud cible est tiré à chaque envoi : c'est ce qui teste le gossip de transactions à
# l'échelle du réseau (S1) plutôt que le mempool d'un seul nœud.
set -euo pipefail
source "$(dirname "$0")/common.sh"

SIM_DIR="$BASE_DIR/sim"
CSV="$SIM_DIR/tx.csv"
WORKERS="${RHIZOME_SIM_WORKERS:-8}"
AMOUNT="${RHIZOME_SIM_AMOUNT:-0.001}"
FUND="${RHIZOME_SIM_FUND:-1}"

sim_key()  { printf '%s/sim-%d.key' "$KEYS_DIR" "$1"; }
sim_pid()  { printf '%s/sim-tx-%d.pid' "$PID_DIR" "$1"; }
sim_addr() { "$WALLET_BIN" address "$(sim_key "$1")" 2>/dev/null; }

# Solde confirmé (en unités de base) d'une adresse, vu par le nœud `n`.
wallet_units() {
  local n=$1 addr=$2
  "$WALLET_BIN" balance "$(node_url "$n")" "$addr" 2>/dev/null \
    | sed -n 's/.*(\([0-9]*\) base units)/\1/p'
}
wallet_nonce() {
  local n=$1 addr=$2
  "$WALLET_BIN" balance "$(node_url "$n")" "$addr" 2>/dev/null \
    | sed -n 's/^nextNonce: //p'
}

ensure_keys() {
  local k
  mkdir -p "$SIM_DIR" "$KEYS_DIR" "$PID_DIR"
  for k in $(seq 0 $((WORKERS - 1))); do
    [[ -f "$(sim_key "$k")" ]] || "$WALLET_BIN" keygen "$(sim_key "$k")" --plaintext >/dev/null
  done
}

# Dote chaque portefeuille de simulation depuis un mineur DIFFÉRENT (round-robin sur MINERS) :
# la récompense est de ~2,78 PDN par bloc, un seul mineur mettrait des minutes à financer 8
# portefeuilles. On attend que le mineur ait le solde avant de tirer dessus — au démarrage du
# réseau les coinbases ne sont pas encore mûres.
fund_workers() {
  local k m addr units deadline
  for k in $(seq 0 $((WORKERS - 1))); do
    addr="$(sim_addr "$k")"
    units="$(wallet_units 0 "$addr")"
    if [[ -n "$units" && "$units" != "0" ]]; then
      echo "sim-$k déjà doté ($units unités)"
      continue
    fi
    m="${MINERS[$((k % ${#MINERS[@]}))]}"
    deadline=$((SECONDS + 300))
    while :; do
      units="$(wallet_units "$m" "${MINER_ADDR[$m]}")"
      [[ -n "$units" ]] && (( units >= FUND * 10000 )) && break
      (( SECONDS > deadline )) && { echo "ERREUR: mineur $m sans solde après 5 min" >&2; return 1; }
      sleep 2
    done
    "$WALLET_BIN" send "$(node_url "$m")" "$KEYS_DIR/miner-$m.key" "$addr" "$FUND" >/dev/null \
      || { echo "ERREUR: dotation de sim-$k depuis le mineur $m refusée" >&2; return 1; }
    echo "sim-$k doté de $FUND PDN par le mineur $m"
  done
  # Attente de confirmation : un worker qui démarre sans solde échouerait ses premiers envois.
  # 5 min : au démarrage du réseau les blocs arrivent par rafales (un mineur au plancher de
  # difficulté produit d'un coup, puis rien pendant son intervalle de pacing) et les blocs
  # orphelins renvoient leurs transactions en mempool. Une dotation a mis ~4 min à être
  # incluse dans ces conditions ; un délai de 3 min faisait échouer le démarrage à tort.
  echo "attente de confirmation des dotations ..."
  deadline=$((SECONDS + 300))
  for k in $(seq 0 $((WORKERS - 1))); do
    addr="$(sim_addr "$k")"
    while :; do
      units="$(wallet_units 0 "$addr")"
      [[ -n "$units" && "$units" != "0" ]] && break
      (( SECONDS > deadline )) && { echo "ERREUR: dotation de sim-$k jamais confirmée" >&2; return 1; }
      sleep 2
    done
  done
}

# Boucle d'un worker. Sous-commande interne (lancée par `start`), pas destinée à l'appel direct.
worker_loop() {
  local k=$1 addr me nonce0 nonce node target t0 t1 status
  # `errexit` désactivé pour la boucle : chaque appel au wallet est une commande susceptible
  # d'échouer (nœud arrêté pendant une partition, tx refusée), et sous `set -e` la première
  # substitution `nonce0="$(...)"` qui échoue tue le worker EN SILENCE. C'est ce qui a arrêté
  # les 8 workers au premier `stop.sh` de la campagne, sans une ligne de journal.
  set +e
  me="$(sim_addr "$k")"
  trap 'exit 0' TERM INT
  while :; do
    node=$((RANDOM % NODES))
    target=$(( RANDOM % WORKERS ))
    (( target == k )) && target=$(( (target + 1) % WORKERS ))
    addr="$(sim_addr "$target")"
    nonce0="$(wallet_nonce "$node" "$me")"
    t0=$(date +%s%3N)
    if "$WALLET_BIN" send "$(node_url "$node")" "$(sim_key "$k")" "$addr" "$AMOUNT" \
         >"$SIM_DIR/last-$k.out" 2>&1; then
      status=SUCCESS
    else
      status="$(sed -n 's/^status: //p;s/^error: /ERR:/p' "$SIM_DIR/last-$k.out" | head -1)"
      status="${status:-FAILED}"
    fi
    t1=$(date +%s%3N)
    printf '%s,%d,%d,%s,%s,%s\n' "$(date +%H:%M:%S)" "$k" "$node" "$AMOUNT" "$status" \
      "$((t1 - t0))" >> "$CSV"
    # Attendre la confirmation (le nonce confirmé avance) avant l'envoi suivant : sans cela le
    # suivant réutiliserait le même nonce. Plafonné à 60 s pour qu'une reorg ou une partition
    # ne fige pas le worker définitivement.
    if [[ "$status" == "SUCCESS" && -n "$nonce0" ]]; then
      local wait_until=$((SECONDS + 60))
      while (( SECONDS < wait_until )); do
        nonce="$(wallet_nonce "$node" "$me")"
        [[ -n "$nonce" && "$nonce" != "$nonce0" ]] && break
        sleep 1
      done
    else
      sleep 2
    fi
    sleep "0.$((RANDOM % 9))"
  done
}

start() {
  ensure_keys
  # Adresses des mineurs (les clés existent : start.sh les a créées).
  declare -gA MINER_ADDR
  local m k
  for m in "${MINERS[@]}"; do
    MINER_ADDR[$m]="$("$WALLET_BIN" address "$KEYS_DIR/miner-$m.key")"
  done
  fund_workers
  [[ -f "$CSV" ]] || echo "time,worker,node,amount,status,latency_ms" > "$CSV"
  for k in $(seq 0 $((WORKERS - 1))); do
    # WORKERS/AMOUNT repassés par l'environnement : le sous-processus `worker` ne revoit pas
    # les options de `start`, et un worker qui croirait le réseau plus petit tirerait ses
    # destinataires dans un sous-ensemble (voire s'enverrait à lui-même).
    setsid bash -c 'echo $$ > "$1"; shift; exec env "$@"' _ \
      "$(sim_pid "$k")" "RHIZOME_SIM_WORKERS=$WORKERS" "RHIZOME_SIM_AMOUNT=$AMOUNT" \
      "$0" worker "$k" >> "$SIM_DIR/worker-$k.log" 2>&1 &
    for _ in $(seq 1 20); do [[ -s "$(sim_pid "$k")" ]] && break; sleep 0.1; done
  done
  echo "simulateur de transactions démarré : $WORKERS workers, $AMOUNT PDN par envoi."
  echo "journal : $CSV — arrêt : $0 stop"
}

stop() {
  # Balayage par fichier pid, pas par WORKERS : `stop` peut être lancé sans les options du
  # `start` correspondant, et un worker oublié continuerait à charger le réseau.
  local f pids=()
  for f in "$PID_DIR"/sim-tx-*.pid; do
    [[ -s "$f" ]] && pids+=("$(cat "$f")")
  done
  (( ${#pids[@]} == 0 )) && { echo "aucun worker à arrêter"; return 0; }
  kill "${pids[@]}" 2>/dev/null || true
  # Le wallet en vol peut retenir le worker quelques centaines de ms ; laisser sortir.
  sleep 1
  kill -9 "${pids[@]}" 2>/dev/null || true
  rm -f "$PID_DIR"/sim-tx-*.pid
  echo "simulateur de transactions arrêté (${#pids[@]} workers)."
}

status() {
  [[ -f "$CSV" ]] || { echo "aucun envoi enregistré ($CSV absent)"; return 0; }
  echo "envois: $(( $(wc -l < "$CSV") - 1 ))"
  echo "par statut:"
  tail -n +2 "$CSV" | cut -d, -f5 | sort | uniq -c | sort -rn
  echo "derniers:"
  tail -5 "$CSV"
}

case "${1:-start}" in
  start)  shift || true
          while getopts "w:a:f:" opt; do
            case "$opt" in w) WORKERS=$OPTARG ;; a) AMOUNT=$OPTARG ;; f) FUND=$OPTARG ;;
              *) echo "usage: $0 start [-w workers] [-a montant] [-f dotation]" >&2; exit 2 ;;
            esac
          done
          start ;;
  stop)   stop ;;
  status) status ;;
  worker) worker_loop "$2" ;;
  *)      echo "usage: $0 {start|stop|status}" >&2; exit 2 ;;
esac
