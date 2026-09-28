#!/usr/bin/env bash
# Maillage de tunnels SSH pour une campagne multi-machines — chantier 2 (voir TEST-PLAN.md et
# inventory.tsv.example pour le format d'inventaire).
#
# Exercé contre un hôte réel en phase 0 de la campagne 11 (2026-09-25, rejeu ciblé le 2026-09-28,
# voir TEST-PLAN.md). Vérifié : chaque tunnel sert le bon nœud distant ; `check` signale un nœud
# gelé derrière un tunnel vivant ; un `ssh -N` tué est rapporté « aucun tunnel actif » pendant
# que l'autre reste OK ; `up` relancé ne rouvre que le tunnel mort ; `up` vérifie chaque tunnel
# 3 s après lancement et échoue si l'un est déjà mort (au premier run, un tunnel résiduel de
# campagne 10 tenait le port 13001 et faisait lire seed-1 à la place du nœud B — d'où la base
# surchargeable RHIZOME_TUNNELS_BASE). Jamais vérifié : la mort CÔTÉ DISTANT d'un tunnel
# (ServerAliveInterval/CountMax), qui demanderait de tuer un sshd de l'hôte.
#
# Principe (voir le plan, section « Adressage ») : au lieu de réécrire chaque batterie pour
# parler à N hôtes, on rend l'hypothèse « curl sur 127.0.0.1 » à nouveau VRAIE — un tunnel de
# contrôle par nœud distant, `127.0.0.1:<port de contrôle local> -> 127.0.0.1:<port du nœud sur
# l'hôte distant>`. Le harnais existant (common.sh, les suites) continue de taper du loopback ;
# seule la RÉSOLUTION du port change. Port de contrôle local : 13000 + index de ligne dans
# l'inventaire (0-based, en ignorant les commentaires/lignes vides) — fixe et prévisible d'une
# campagne à l'autre pour le même inventaire.
#
# Usage :
#   tunnels.sh up    <inventory.tsv>   # ouvre un tunnel par ligne non-`local`
#   tunnels.sh check <inventory.tsv>   # sonde chaque tunnel ouvert — PRÉ-VOL OBLIGATOIRE avant
#                                       # toute campagne (voir le plan : un tunnel mort se lit
#                                       # comme « nœud DOWN » et transforme une campagne en bruit)
#   tunnels.sh down  <inventory.tsv>   # ferme tous les tunnels ouverts par `up`
#   tunnels.sh port-of <inventory.tsv> <ssh-target>   # imprime le port de contrôle local pour
#                                       # une cible ssh donnée (aide les scripts appelants)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUN_DIR="${RHIZOME_TUNNELS_DIR:-$ROOT/.testnet-tunnels}"
# Surchargeable : en phase 0, un tunnel d'une campagne précédente tenait encore 13001 sur la
# machine locale, et le tunnel de la ligne 1 mourait au démarrage.
CONTROL_BASE="${RHIZOME_TUNNELS_BASE:-13000}"

usage() {
  echo "usage: $0 up|check|down <inventory.tsv>" >&2
  echo "       $0 port-of <inventory.tsv> <ssh-target>" >&2
  exit 2
}

# Lit l'inventaire en sautant commentaires (#) et lignes vides ; imprime un enregistrement par
# ligne utile sous la forme "index<TAB>ssh<TAB>p2p_ip<TAB>advertise<TAB>role<TAB>port<TAB>heap<TAB>prune<TAB>reachable".
read_inventory() {
  local file=$1
  [[ -f "$file" ]] || { echo "ERREUR: inventaire introuvable: $file" >&2; return 1; }
  local idx=0
  while IFS=$'\t' read -r ssh p2p_ip advertise role port heap prune reachable; do
    [[ -z "${ssh:-}" || "$ssh" == \#* ]] && continue
    printf '%d\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$idx" "$ssh" "$p2p_ip" "$advertise" "$role" "$port" "$heap" "$prune" "$reachable"
    idx=$((idx + 1))
  done < "$file"
}

control_port_for_index() { printf '%d' "$((CONTROL_BASE + $1))"; }

mkdir -p "$RUN_DIR"

cmd_up() {
  local inv=$1
  local idx ssh p2p_ip advertise role port heap prune reachable cport pidfile
  local -a started=()
  while IFS=$'\t' read -r idx ssh p2p_ip advertise role port heap prune reachable; do
    if [[ "$ssh" == "local" ]]; then
      echo "up: ligne $idx ($ssh) — pas de tunnel, adressage direct" >&2
      continue
    fi
    cport="$(control_port_for_index "$idx")"
    pidfile="$RUN_DIR/$idx.pid"
    if [[ -f "$pidfile" ]] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
      echo "up: ligne $idx ($ssh) — tunnel déjà ouvert (pid $(cat "$pidfile")), ignoré" >&2
      continue
    fi
    # ServerAliveInterval/CountMax : détecte un hôte distant mort plutôt que de laisser le
    # tunnel pendre indéfiniment ; ExitOnForwardFailure : échoue fort si le port local est déjà
    # pris, plutôt qu'un ssh qui tourne sans jamais forwarder — silencieusement inutile.
    setsid ssh -N \
      -o ExitOnForwardFailure=yes -o ServerAliveInterval=10 -o ServerAliveCountMax=3 \
      -o StreamLocalBindUnlink=yes \
      -L "127.0.0.1:$cport:127.0.0.1:$port" \
      "$ssh" >"$RUN_DIR/$idx.log" 2>&1 &
    echo $! > "$pidfile"
    started+=("$idx")
    echo "up: ligne $idx ($ssh) — 127.0.0.1:$cport -> $ssh:127.0.0.1:$port (pid $!)" >&2
  done < <(read_inventory "$inv")

  # Un ssh qui échoue à ouvrir son forward (port local pris, hôte injoignable) meurt en quelques
  # secondes grâce à ExitOnForwardFailure. Sans cette vérification, up annonçait quand même le
  # tunnel ouvert, et l'erreur n'apparaissait qu'au check suivant sous la forme « aucun tunnel actif ».
  local fail=0
  (( ${#started[@]} )) && sleep 3
  for idx in "${started[@]}"; do
    pidfile="$RUN_DIR/$idx.pid"
    if ! kill -0 "$(cat "$pidfile")" 2>/dev/null; then
      echo "up: ligne $idx — ÉCHEC, le tunnel est mort au démarrage : $(tail -n 2 "$RUN_DIR/$idx.log" | tr '\n' ' ')" >&2
      rm -f "$pidfile"
      fail=1
    fi
  done
  return "$fail"
}

cmd_check() {
  local inv=$1
  local idx ssh p2p_ip advertise role port heap prune reachable cport
  local fail=0
  while IFS=$'\t' read -r idx ssh p2p_ip advertise role port heap prune reachable; do
    if [[ "$ssh" == "local" ]]; then
      if curl -sf --max-time 3 "http://127.0.0.1:$port/stats" >/dev/null 2>&1; then
        echo "check: ligne $idx ($ssh) OK (direct)" >&2
      else
        echo "check: ligne $idx ($ssh) ÉCHEC (direct, port $port injoignable)" >&2
        fail=1
      fi
      continue
    fi
    cport="$(control_port_for_index "$idx")"
    local pidfile="$RUN_DIR/$idx.pid"
    if [[ ! -f "$pidfile" ]] || ! kill -0 "$(cat "$pidfile" 2>/dev/null)" 2>/dev/null; then
      echo "check: ligne $idx ($ssh) ÉCHEC (aucun tunnel actif — lancer '$0 up $inv')" >&2
      fail=1
      continue
    fi
    if curl -sf --max-time 3 "http://127.0.0.1:$cport/stats" >/dev/null 2>&1; then
      echo "check: ligne $idx ($ssh) OK (tunnel 127.0.0.1:$cport)" >&2
    else
      echo "check: ligne $idx ($ssh) ÉCHEC (tunnel ouvert mais /stats ne répond pas — nœud down ou tunnel mort)" >&2
      fail=1
    fi
  done < <(read_inventory "$inv")
  return "$fail"
}

cmd_down() {
  local inv=$1
  local idx ssh p2p_ip advertise role port heap prune reachable pidfile pid
  while IFS=$'\t' read -r idx ssh p2p_ip advertise role port heap prune reachable; do
    [[ "$ssh" == "local" ]] && continue
    pidfile="$RUN_DIR/$idx.pid"
    [[ -f "$pidfile" ]] || continue
    pid="$(cat "$pidfile")"
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null
      echo "down: ligne $idx ($ssh) — tunnel fermé (pid $pid)" >&2
    fi
    rm -f "$pidfile"
  done < <(read_inventory "$inv")
}

cmd_port_of() {
  local inv=$1 target=$2
  local idx ssh p2p_ip advertise role port heap prune reachable
  while IFS=$'\t' read -r idx ssh p2p_ip advertise role port heap prune reachable; do
    if [[ "$ssh" == "$target" ]]; then
      if [[ "$ssh" == "local" ]]; then printf '%s\n' "$port"; else control_port_for_index "$idx"; fi
      return 0
    fi
  done < <(read_inventory "$inv")
  echo "ERREUR: cible ssh '$target' absente de $inv" >&2
  return 1
}

[[ $# -ge 1 ]] || usage
sub=$1; shift
case "$sub" in
  up)      [[ $# -eq 1 ]] || usage; cmd_up "$1" ;;
  check)   [[ $# -eq 1 ]] || usage; cmd_check "$1" ;;
  down)    [[ $# -eq 1 ]] || usage; cmd_down "$1" ;;
  port-of) [[ $# -eq 2 ]] || usage; cmd_port_of "$1" "$2" ;;
  *) usage ;;
esac
