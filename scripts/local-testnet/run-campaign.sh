#!/usr/bin/env bash
# Campagne complète : convergence du réseau, puis les batteries de scénarios.
#
# C'est le point d'entrée de la campagne « revue adverse en réseau réel » : il vérifie d'abord
# que le testnet est en état d'être testé (sinon tout verdict en aval est du bruit), puis
# enchaîne transactions, portefeuilles, contrats et transport. Chaque batterie écrit son TSV
# dans .testnet/results/ ; le récapitulatif final les agrège.
#
# Usage : run-campaign.sh [-n <nœud victime>] [suite ...]      (défaut : DEFAULT_SUITES ci-dessous)
#
# `pow`, `bootstrap`, `tls` et `clock` (campagnes 8/9) ont besoin en plus d'une PAIRE/topologie
# ISOLÉE qu'elles montent elles-mêmes (voir TEST-PLAN.md et l'en-tête de chaque batterie) : un
# nœud source/REF et un nœud victime/SKEW sur des ports jamais posés par la campagne principale.
# Sans elle les batteries échouent en annonçant ce qui manque, plutôt que de rendre des verdicts
# sur un terrain absent.
#
# `deep-reorg` et `soak` ne sont volontairement PAS dans la liste par défaut : la première monte
# sa PROPRE mini-campagne éphémère (répertoire `.testnet-deep-reorg`, cf. son en-tête) et dure de
# l'ordre de 30-50 min (deux camps qui minent chacun >120 blocs au-delà de l'horizon de reorg) ;
# la seconde n'a de sens qu'avec une fenêtre de charge choisie par l'opérateur (`SUITE_SOAK_SECONDS`,
# quelques minutes en routine, des heures/jours pour un VRAI soak) et n'apporte rien à rejouer à
# chaque campagne courte. Ce sont des batteries « nommées explicitement » — le mécanisme
# suite ci-dessous les accepte déjà telles quelles :
#   run-campaign.sh deep-reorg
#   run-campaign.sh soak tx persist
set -uo pipefail
source "$(dirname "$0")/common.sh"
set +e

VICTIM=0
while getopts "n:" opt; do
  case "$opt" in
    n) VICTIM=$OPTARG ;;
    *) echo "usage: $0 [-n <node>] [tx|wallet|contract|chain|net|dos|pow|bootstrap|tls|clock|persist|deep-reorg|soak ...]" >&2; exit 2 ;;
  esac
done
shift $((OPTIND - 1))
SUITES=("$@")
# Ordre voulu de la liste par défaut :
#  - tx/wallet/contract/chain : logique/état, sur le réseau principal, aucune dépendance externe.
#  - net puis dos : même terrain (un nœud de la campagne déjà lancée par start.sh), transport puis
#    admission — dos rejoue la même famille API-* que net vient d'exercer, contre un flot réel.
#  - pow/bootstrap/tls/clock : même famille « topologie isolée montée par la batterie elle-même »
#    (paire source/victime ou nœuds auxiliaires TLS/faketime), regroupées ensemble.
#  - persist en DERNIER — c'est la seule batterie qui tue un nœud de la campagne principale.
DEFAULT_SUITES=(tx wallet contract chain net dos pow bootstrap tls clock persist)
(( ${#SUITES[@]} == 0 )) && SUITES=("${DEFAULT_SUITES[@]}")

# --- budgets par batterie -----------------------------------------------------------------------
# Aucun garde-fou de durée n'existait avant cette section : une batterie bloquée (nœud qui ne
# répond jamais, boucle d'attente sans issue) pouvait geler une campagne entière sans jamais
# rendre la main. Chaque suite est désormais lancée sous `timeout`, avec un budget GÉNÉREUX —
# volontairement large par rapport à la durée observée en pratique, pour ne jamais faire échouer
# une batterie simplement lente sur une machine chargée (cf. mémoire « shared box load spikes »)
# — mais fini, pour que « campagne complète » ne devienne jamais « campagne qui ne finit jamais ».
# `-k 30` : un SIGTERM d'abord (laisse les pièges EXIT des batteries nettoyer leurs sous-process
# transitoires), puis un SIGKILL 30 s plus tard si le SIGTERM n'a pas suffi.
#
# Les nœuds `setsid` que pow/bootstrap/tls/clock/deep-reorg laissent VIVRE après coup (pour
# inspection post-mortem, comme documenté dans leurs propres en-têtes) ne sont pas des enfants du
# groupe de processus de la batterie : un timeout qui tue la batterie ne les arrête pas plus qu'un
# Ctrl-C ou une fin normale ne le ferait déjà — comportement inchangé, pas une régression.
declare -A SUITE_TIMEOUT=(
  [tx]=1200            # transferts, INFL/SIG/REPLAY/POOL/CODEC/API — réseau principal seul
  [wallet]=1200         # boîtes/tokens via le wallet CLI — réseau principal seul
  [contract]=1500       # plusieurs déploiements/appels WASM (templates + modules adverses)
  [chain]=1200          # en-têtes, oncles/GHOST, supply, explorateur
  [net]=1200            # transport/HTTP, pairs hostiles (hostile_peer.py)
  [dos]=1200            # inondation /submit bornée (RHIZOME_DOS_FLOOD_SECONDS, défaut 45 s) + mesures
  [pow]=1800            # paire isolée, retarget/timewarp sur plusieurs fenêtres de blocs
  [bootstrap]=4800      # paire isolée, snap-sync + élagage — le fournisseur mine seul jusqu'à
                         # 200 blocs, PUIS jusqu'au VRAI pivot observé + maxReorgDepth (~3820 s
                         # combinés sous charge réelle sur cette machine PARTAGÉE, cf. le
                         # commentaire de recalibrage daté dans suite-bootstrap.sh — deux temps :
                         # le budget d'abord, ~8,1-8,9 s/bloc réel contre ~3,4 s supposés ; puis un
                         # 3ᵉ lancement propre a révélé que la CIBLE elle-même supposait un pivot
                         # exact à 200, alors que `RhizomeNode` le matérialise au premier passage
                         # du scheduler ≥ 200, observé à 201) + les étapes 2/3 en aval (~850 s
                         # cumulés de wait_height/wait_up).
  [tls]=1800            # 5 nœuds auxiliaires isolés, relais TLS, plusieurs cycles AUTH/XFF
  [clock]=2400          # paire isolée, faketime (extraction .deb possible) + plusieurs cycles skew
  [persist]=2700        # deux cycles arrêt/SIGKILL + redémarrage + resync, chacun jusqu'à ~990 s
  [deep-reorg]=5400      # mini-campagne dédiée, deux camps minant >120 blocs au-delà de l'horizon
  # [soak] : calculé plus bas, dépend de SUITE_SOAK_SECONDS (l'opérateur choisit la fenêtre).
)
SUITE_TIMEOUT[soak]=$(( ${SUITE_SOAK_SECONDS:-180} + 900 ))
DEFAULT_SUITE_TIMEOUT=1200   # filet pour une batterie future non encore listée ci-dessus

# `deep-reorg` monte sa PROPRE campagne éphémère et n'écrit donc PAS dans "$BASE_DIR/results/" —
# voir l'export RHIZOME_TESTNET_DIR en tête de suite-deep-reorg.sh. Le récapitulatif doit aller
# chercher son TSV au bon endroit, sinon il rapportera à tort « aucun résultat » pour une batterie
# qui en a pourtant produit.
results_file_for() {
  local s=$1
  if [[ "$s" == "deep-reorg" ]]; then
    printf '%s/results/deep-reorg.tsv' "${RHIZOME_TESTNET_DIR:-$ROOT/.testnet-deep-reorg}"
  else
    printf '%s/results/%s.tsv' "$BASE_DIR" "$s"
  fi
}

echo "=== pré-vol : le réseau est-il en état d'être testé ? ==="
up=0
for i in $(seq 0 $((NODES - 1))); do [[ -n "$(node_stats "$i")" ]] && up=$((up + 1)); done
echo "nœuds répondants : $up/$NODES"
if (( up < NODES )); then
  echo "ATTENTION : $((NODES - up)) nœud(s) DOWN — les verdicts « distant » et « déterminisme » portent sur moins de nœuds." >&2
fi
"$(dirname "$0")/status.sh" | tail -6
echo

start=$SECONDS
declare -A SUITE_RC=()
for s in "${SUITES[@]}"; do
  budget=${SUITE_TIMEOUT[$s]:-$DEFAULT_SUITE_TIMEOUT}
  echo
  echo "################ batterie $s (budget ${budget}s) ################"
  timeout -k 30 "${budget}s" "$(dirname "$0")/suite-$s.sh" "$VICTIM"
  rc=$?
  SUITE_RC[$s]=$rc
  if (( rc == 124 || rc == 137 )); then
    echo "ATTENTION : batterie $s tuée après dépassement de son budget (${budget}s, timeout rc=$rc)" \
      "— verdict INCOMPLET pour cette batterie." >&2
    resfile="$(results_file_for "$s")"
    mkdir -p "$(dirname "$resfile")"
    printf '%s\t%s\t%s\t%s\t%s\n' "CAMPAIGN-$s-TIMEOUT" FAIL \
      "budget de ${budget}s dépassé, batterie tuée par timeout (rc=$rc) — augmenter SUITE_TIMEOUT[$s] si ce délai est réellement trop court sur cette machine" \
      "$(date -u +%Y-%m-%dT%H:%M:%SZ)" 0 >> "$resfile"
  fi
done

echo
echo "================ RÉCAPITULATIF DE CAMPAGNE ================"
total_pass=0; total_fail=0
for s in "${SUITES[@]}"; do
  f="$(results_file_for "$s")"
  [[ -f "$f" ]] || { printf '%-10s (aucun résultat)\n' "$s"; continue; }
  p=$(grep -cP '\tPASS\t' "$f"); q=$(grep -cP '\tFAIL\t' "$f")
  total_pass=$((total_pass + p)); total_fail=$((total_fail + q))
  printf '%-10s %3d PASS  %3d FAIL\n' "$s" "$p" "$q"
  grep -P '\tFAIL\t' "$f" | awk -F'\t' '{printf "           ↳ %-26s %s\n", $1, $3}'
done
printf 'TOTAL      %3d PASS  %3d FAIL   (%d s)\n' "$total_pass" "$total_fail" "$((SECONDS - start))"
(( total_fail == 0 ))
