#!/usr/bin/env bash
# Lance le testnet local : N nœuds devnet (16 par défaut), 4 mineurs, anneau de peering.
# Usage : start.sh                  tout le réseau
#         start.sh -n <idx>         un seul nœud (redémarrage, cf. S6)
#         start.sh -p <lo>-<hi>     une moitié isolée, seedée UNIQUEMENT en interne (S7)
#         start.sh -n <idx> -p a-b  un nœud, seedé uniquement dans [a..b]
set -euo pipefail
source "$(dirname "$0")/common.sh"

cd "$ROOT"

single=-1
part_lo=0
part_hi=$((NODES - 1))
while getopts "n:p:" opt; do
  case "$opt" in
    n) single=$OPTARG ;;
    p) part_lo=${OPTARG%-*}; part_hi=${OPTARG#*-} ;;
    *) echo "usage: $0 [-n <idx>] [-p <lo>-<hi>]" >&2; exit 2 ;;
  esac
done

if (( part_lo < 0 || part_hi >= NODES || part_lo > part_hi )); then
  echo "plage de partition hors borne: $part_lo-$part_hi (0..$((NODES - 1)))" >&2; exit 2
fi

# 1. Un seul build : les nœuds tournent via un binaire déjà construit (pas N daemons Gradle).
#    En mode natif c'est build/native/rhizome-node (GraalVM) ; le wallet reste sur la JVM,
#    il n'est pas dans la boucle chaude du réseau.
ensure_jdk25
if [[ ! -x "$WALLET_BIN" ]]; then
  ./gradlew :app-wallet:installDist
fi
if [[ ! -x "$NODE_BIN" ]]; then
  if [[ "$NATIVE" == "1" ]]; then
    ./gradlew :app-node:nativeImage
  else
    ./gradlew :app-node:installDist
  fi
fi

mkdir -p "$BASE_DIR/logs" "$KEYS_DIR" "$PID_DIR"

# Pré-vol : ne pas lancer les JVMs si un port est déjà pris ou si la RAM ne suit pas. Les
# deux contrôles portent EXACTEMENT sur les nœuds à lancer — un contrôle sur tout le réseau
# refuserait de relancer une moitié pendant que l'autre tourne, c'est-à-dire S7.
if (( single >= 0 )); then
  check_ports_free "$single" "$single"
  check_memory 1
else
  check_ports_free "$part_lo" "$part_hi"
  check_memory "$((part_hi - part_lo + 1))"
fi

# 2. Clés des mineurs (plaintext, non interactif) + adresses.
declare -A MINER_ADDR
for i in "${MINERS[@]}"; do
  key="$KEYS_DIR/miner-$i.key"
  if [[ ! -f "$key" ]]; then
    "$WALLET_BIN" keygen "$key" --plaintext >/dev/null
  fi
  MINER_ADDR[$i]="$("$WALLET_BIN" address "$key")"
done

# 3. Lancement.
#
# setsid : le nœud doit survivre à la mort du shell qui le lance. Sans lui, un orchestrateur
# (ou un simple Ctrl-C) qui tue le groupe de processus au premier plan emporte les nœuds avec
# lui — c'est exactement ce qui a saboté le rejeu S7 de la campagne précédente. Le PID écrit
# est celui du nœud lui-même (`exec` depuis un sous-shell qui s'annonce d'abord), pas celui
# d'un wrapper : stop.sh peut donc signaler proprement.
launch() {
  local i=$1
  local env_vars=(RHIZOME_NETWORK=devnet RHIZOME_PORT="$(node_port "$i")"
    RHIZOME_DATA="$(data_dir "$i")" RHIZOME_ALLOW_PRIVATE_PEERS=true)
  # Plafond de tas : le lanceur JVM le lit dans APP_NODE_OPTS, l'image native le prend en
  # argument (SubstrateVM consomme -Xmx avant main ; RhizomeNode.main ignore argv de toute
  # façon, donc l'argument est inoffensif si un jour l'option disparaît).
  local bin_args=()
  if [[ "$NATIVE" == "1" ]]; then
    bin_args+=("-Xmx$NODE_HEAP")
  else
    env_vars+=(APP_NODE_OPTS="-Xmx$NODE_HEAP")
  fi
  if [[ -n "${MINER_ADDR[$i]:-}" ]]; then
    env_vars+=(RHIZOME_MINER="${MINER_ADDR[$i]}" RHIZOME_BLOCK_INTERVAL_MS="$BLOCK_MS")
  fi
  env_vars+=(RHIZOME_PEERS="$(node_seeds "$i" "$part_lo" "$part_hi")")
  setsid bash -c 'echo $$ > "$1"; shift; exec env "$@"' _ \
    "$(pid_file "$i")" "${env_vars[@]}" "$NODE_BIN" "${bin_args[@]}" \
    >> "$(log_file "$i")" 2>&1 &
  # Laisse le sous-shell écrire son PID avant que stop.sh ne puisse le lire.
  for _ in $(seq 1 20); do [[ -s "$(pid_file "$i")" ]] && break; sleep 0.1; done
  printf 'node %d -> %s (%s, data %s, log %s)\n' \
    "$i" "$(node_url "$i")" "$(is_miner "$i" && echo mineur || echo observateur)" \
    "$(data_dir "$i")" "$(log_file "$i")"
}

if [[ "$single" -ge 0 ]]; then
  if (( single >= NODES )); then
    echo "index hors borne: $single (0..$((NODES - 1)))" >&2; exit 2
  fi
  launch "$single"
  nodes=("$single")
else
  nodes=($(seq "$part_lo" "$part_hi"))
  # Ne purger QUE les pid de la plage lancée : en mode -p, effacer tout le répertoire
  # rendait l'autre moitié — bien vivante — invisible à stop.sh, qui la laissait tourner.
  for i in "${nodes[@]}"; do rm -f "$(pid_file "$i")"; done
  for i in "${nodes[@]}"; do launch "$i"; done
fi

# 4. Attente que chaque nœud lancé réponde sur /stats (démarrage + sync). En mode -n ou -p,
#    seuls les nœuds effectivement lancés sont vérifiés : les autres peuvent être
#    volontairement down (partition S7).
echo "attente de la convergence /stats ..."
deadline=$((SECONDS + 180))
for i in "${nodes[@]}"; do
  while [[ -z "$(node_stats "$i")" && $SECONDS -lt $deadline ]]; do sleep 1; done
  s="$(node_stats "$i")"
  if [[ -z "$s" ]]; then
    echo "ERREUR: node $i ne répond pas — voir $(log_file "$i")" >&2
    exit 1
  fi
  echo "node $i up: hauteur $(json_get "$s" height), pairs $(json_get "$s" peers)"
done

# 5. Amorçage du PEX : sans lui le maillage reste bloqué à 2 pairs par nœud (voir
#    bootstrap_pex). En mode -n on ne réamorce rien : le nœud relancé retrouve le maillage
#    par ses seeds et par les pairs qui le connaissent déjà.
if (( single < 0 )); then
  echo "amorçage PEX (hub $part_lo) ..."
  bootstrap_pex "$part_lo" "$part_hi"
fi

if (( part_lo != 0 || part_hi != NODES - 1 )); then
  echo "moitié $part_lo-$part_hi lancée (isolée : seeds internes uniquement)."
else
  echo "testnet lancé ($NODES nœuds $([[ "$NATIVE" == 1 ]] && echo natifs || echo JVM), ports $BASE_PORT..$((BASE_PORT + NODES - 1)), tas $NODE_HEAP/nœud)."
  echo "mineurs (${#MINERS[@]}): ${MINERS[*]}"
fi
echo "status.sh pour l'état, stop.sh pour arrêter, monitor.sh pour la supervision."
