#!/usr/bin/env bash
# Partition réseau réelle entre hôtes — chantier 2 (voir TEST-PLAN.md et le plan de mise en
# testnet, section « Partitions » / « Risques à porter »). Filtre le PORT P2P uniquement, dans
# les DEUX sens, via une table nftables dédiée par hôte — jamais l'adresse (SSH doit survivre).
#
# ██ NON EXERCÉ CONTRE DU MATÉRIEL RÉEL. ██ Écrit pour le plan, jamais lancé — aucune VM/machine
# multi-hôtes n'a été disponible pendant cette campagne (voir TEST-PLAN.md, « Couverture non
# atteinte »). C'est le script le plus dangereux de ce harnais : une règle non révoquée retire
# silencieusement et DÉFINITIVEMENT un seed d'un réseau public. NE PAS l'utiliser contre un hôte
# de production ou un seed public sans avoir d'abord relu et vérifié les trois garde-fous
# ci-dessous sur un hôte jetable.
#
# Trois garde-fous, dont AUCUN ne suffit seul (le plan est explicite là-dessus) :
#   1. Watchdog distant : `systemd-run --on-active=<durée>s` programmé sur CHAQUE hôte partitionné
#      pour supprimer la table nftables, indépendamment de ce que fait l'appelant — survit à un
#      Ctrl-C, une perte de lien SSH, ou un crash de ce script.
#   2. `trap` côté appelant : `heal` est invoqué en EXIT quel que soit le chemin de sortie.
#   3. Refus de partitionner tout hôte de rôle `seed` (inventory.tsv, colonne role) — c'est cette
#      règle qui porte le compte réaliste à 5 hôtes (3 seeds + 2 bancs d'essai) plutôt que 3.
#
# Usage :
#   partition.sh apply <inventory.tsv> <durée_s> <camp_A:ligne,ligne,...> <camp_B:ligne,ligne,...>
#     Isole camp_A de camp_B : sur chaque hôte de camp_A, DROP entrée+sortie sur le port p2p
#     vers/depuis le p2p_ip de chaque hôte de camp_B (et symétriquement). <durée_s> arme le
#     watchdog distant ; ce script guérit aussi en EXIT (garde-fou n°2) — les deux doivent être
#     redondants, pas l'un à la place de l'autre.
#   partition.sh heal <inventory.tsv>            # supprime la table sur TOUS les hôtes de l'inventaire
#   partition.sh status <inventory.tsv>           # rapporte, hôte par hôte, si la table existe
#
# Exemple : partition.sh apply inventory.tsv 900 A:0,1 B:2,3   (indices = numéros de ligne 0-based
# de l'inventaire, dans l'ordre où ils apparaissent après filtrage des commentaires/vides —
# mêmes indices que ceux qu'imprime `tunnels.sh up`/`check`)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TABLE=rhizome_partition

usage() {
  echo "usage: $0 apply <inventory.tsv> <durée_s> <A:idx,idx,...> <B:idx,idx,...>" >&2
  echo "       $0 heal  <inventory.tsv>" >&2
  echo "       $0 status <inventory.tsv>" >&2
  exit 2
}

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

# Exécute une commande sur un hôte de l'inventaire, préfixée de `sudo` dans les DEUX branches —
# `nft`/`systemd-run` veulent root aussi bien en local qu'à distance. `local` s'exécute ici même ;
# le reste passe par ssh, ce qui suppose un `sudo` SANS mot de passe interactif configuré côté
# distant pour l'utilisateur ssh (`visudo` : `<user> ALL=(root) NOPASSWD: /usr/sbin/nft,
# /usr/bin/systemd-run` — à documenter dans le guide opérateur une fois ce script exercé). Une
# première version de ce fichier ne sudoait QUE la branche locale, laissant la branche distante
# échouer en permission refusée sur tout hôte où l'utilisateur ssh n'est pas déjà root — corrigé
# ici avant tout premier essai réel.
run_on() {
  local ssh=$1; shift
  if [[ "$ssh" == "local" ]]; then
    sudo "$@"
  else
    # shellcheck disable=SC2029 -- l'expansion côté appelant est voulue : chaque hôte reçoit sa
    # propre commande construite avec SES adresses/ports, pas une chaîne générique.
    ssh "$ssh" "sudo $*"
  fi
}

row_by_index() {
  local inv=$1 want=$2
  read_inventory "$inv" | awk -F'\t' -v w="$want" '$1 == w'
}

heal_host() {
  local ssh=$1
  run_on "$ssh" nft delete table inet "$TABLE" 2>/dev/null || true
  echo "heal: $ssh — table $TABLE supprimée (ou déjà absente)" >&2
}

cmd_heal() {
  local inv=$1 idx ssh p2p_ip advertise role port heap prune reachable
  while IFS=$'\t' read -r idx ssh p2p_ip advertise role port heap prune reachable; do
    heal_host "$ssh"
  done < <(read_inventory "$inv")
}

cmd_status() {
  local inv=$1 idx ssh p2p_ip advertise role port heap prune reachable
  while IFS=$'\t' read -r idx ssh p2p_ip advertise role port heap prune reachable; do
    if run_on "$ssh" nft list table inet "$TABLE" >/dev/null 2>&1; then
      echo "status: $ssh — table $TABLE PRÉSENTE (hôte partitionné)" >&2
    else
      echo "status: $ssh — table $TABLE absente (hôte sain)" >&2
    fi
  done < <(read_inventory "$inv")
}

parse_camp() {
  # "A:0,1" -> imprime "0" puis "1"
  local spec=$1
  local ids=${spec#*:}
  tr ',' '\n' <<<"$ids"
}

cmd_apply() {
  local inv=$1 duration=$2 campA_spec=$3 campB_spec=$4
  local -a campA_idx campB_idx
  mapfile -t campA_idx < <(parse_camp "$campA_spec")
  mapfile -t campB_idx < <(parse_camp "$campB_spec")

  # Garde-fou n°3 : aucun hôte de rôle `seed` dans l'un ou l'autre camp.
  local idx row role
  for idx in "${campA_idx[@]}" "${campB_idx[@]}"; do
    row="$(row_by_index "$inv" "$idx")"
    [[ -n "$row" ]] || { echo "ERREUR: index $idx absent de $inv" >&2; return 1; }
    role="$(cut -f5 <<<"$row")"
    if [[ "$role" == "seed" ]]; then
      echo "ERREUR: index $idx est un seed (role=seed) — refus de le partitionner (garde-fou n°3, voir en-tête de ce script)" >&2
      return 1
    fi
  done

  # Garde-fou n°2 : guérir en sortie, quel que soit le chemin (succès, erreur, signal).
  trap 'echo "apply: guérison (trap EXIT)" >&2; cmd_heal "'"$inv"'"' EXIT

  local ssh_a p2p_a port_a ssh_b p2p_b port_b
  for idx in "${campA_idx[@]}"; do
    row="$(row_by_index "$inv" "$idx")"
    ssh_a="$(cut -f2 <<<"$row")"; port_a="$(cut -f6 <<<"$row")"

    # Garde-fou n°1 : watchdog distant, indépendant de ce process. À défaut de systemd-run
    # (hôte sans systemd utilisateur/root accessible), ce script REFUSE plutôt que de
    # partitionner sans garantie de guérison — un `heal` manuel resterait le seul recours.
    if ! run_on "$ssh_a" systemd-run --on-active="${duration}s" --unit="rhizome-partition-heal-$idx" \
        nft delete table inet "$TABLE" >/dev/null 2>&1; then
      echo "ERREUR: $ssh_a — impossible d'armer le watchdog systemd-run (garde-fou n°1) — abandon, aucune règle posée" >&2
      return 1
    fi

    run_on "$ssh_a" nft add table inet "$TABLE" 2>/dev/null || true
    run_on "$ssh_a" nft add chain inet "$TABLE" in "{ type filter hook input priority 0 \; }" 2>/dev/null || true
    run_on "$ssh_a" nft add chain inet "$TABLE" out "{ type filter hook output priority 0 \; }" 2>/dev/null || true

    for idx2 in "${campB_idx[@]}"; do
      row="$(row_by_index "$inv" "$idx2")"
      ssh_b="$(cut -f2 <<<"$row")"; p2p_b="$(cut -f3 <<<"$row")"; port_b="$(cut -f6 <<<"$row")"
      # Deux sens explicites : un drop en sortie seulement laisse arriver le gossip d'en face
      # (le plan est explicite là-dessus — « la partition n'en est pas une »).
      run_on "$ssh_a" nft add rule inet "$TABLE" in ip saddr "$p2p_b" tcp dport "$port_a" drop
      run_on "$ssh_a" nft add rule inet "$TABLE" out ip daddr "$p2p_b" tcp dport "$port_b" drop
    done
    echo "apply: $ssh_a — isolé du camp B pour ${duration}s (watchdog + règles posées)" >&2
  done

  for idx in "${campB_idx[@]}"; do
    row="$(row_by_index "$inv" "$idx")"
    ssh_b="$(cut -f2 <<<"$row")"; port_b="$(cut -f6 <<<"$row")"

    if ! run_on "$ssh_b" systemd-run --on-active="${duration}s" --unit="rhizome-partition-heal-$idx" \
        nft delete table inet "$TABLE" >/dev/null 2>&1; then
      echo "ERREUR: $ssh_b — impossible d'armer le watchdog systemd-run (garde-fou n°1) — abandon, aucune règle posée" >&2
      return 1
    fi

    run_on "$ssh_b" nft add table inet "$TABLE" 2>/dev/null || true
    run_on "$ssh_b" nft add chain inet "$TABLE" in "{ type filter hook input priority 0 \; }" 2>/dev/null || true
    run_on "$ssh_b" nft add chain inet "$TABLE" out "{ type filter hook output priority 0 \; }" 2>/dev/null || true

    for idx2 in "${campA_idx[@]}"; do
      row="$(row_by_index "$inv" "$idx2")"
      ssh_a="$(cut -f2 <<<"$row")"; p2p_a="$(cut -f3 <<<"$row")"; port_a="$(cut -f6 <<<"$row")"
      run_on "$ssh_b" nft add rule inet "$TABLE" in ip saddr "$p2p_a" tcp dport "$port_b" drop
      run_on "$ssh_b" nft add rule inet "$TABLE" out ip daddr "$p2p_a" tcp dport "$port_a" drop
    done
    echo "apply: $ssh_b — isolé du camp A pour ${duration}s (watchdog + règles posées)" >&2
  done

  echo "apply: partition posée sur $((${#campA_idx[@]} + ${#campB_idx[@]})) hôte(s), guérison programmée dans ${duration}s (+ trap local)" >&2
  # L'appelant est responsable de la durée de vie de la partition (dormir/observer le réseau
  # pendant `duration`, puis laisser ce process sortir — le trap EXIT ci-dessus guérit alors).
  # Ce script ne bloque PAS lui-même : une batterie orchestrant plusieurs partitions successives
  # a besoin de reprendre la main immédiatement après la pose des règles.
}

[[ $# -ge 1 ]] || usage
sub=$1; shift
case "$sub" in
  apply)  [[ $# -eq 4 ]] || usage; cmd_apply "$1" "$2" "$3" "$4" ;;
  heal)   [[ $# -eq 1 ]] || usage; cmd_heal "$1" ;;
  status) [[ $# -eq 1 ]] || usage; cmd_status "$1" ;;
  *) usage ;;
esac
