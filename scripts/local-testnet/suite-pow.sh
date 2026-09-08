#!/usr/bin/env bash
# Batterie POW/TIME — la boucle de retarget et les bornes temporelles, en réseau réel.
#
# C'est le trou n° 1 de la campagne 7 : la difficulté y était restée collée à son plancher sur
# 728 blocs sur 728, donc le retarget, la défense timewarp et les bornes min/max n'avaient jamais
# tourné ailleurs qu'en JUnit. Ici la chaîne les traverse pour de bon.
#
# Deux terrains, délibérément :
#
#   * le RÉSEAU (6 nœuds, mineurs réels) fournit la dynamique authentique — la difficulté monte
#     sous une cadence trop rapide, se stabilise à la cible, redescend quand on coupe du hashrate.
#     Personne ne choisit les horodatages : ils sortent de vraies horloges et de vrai PoW.
#   * la paire SOURCE/VICTIME isolée (deux nœuds sans pairs, cf. TEST-PLAN) fournit le contrôle
#     exact : la victime ne reçoit que ce que `Anvil` lui soumet, donc on impose le calendrier au
#     millième de seconde et on visite tout le domaine du retarget, plancher compris, en secondes.
#
# Le juge n'est pas le nœud : c'est `tools/diffscan.py`, une réimplémentation indépendante de
# `DifficultyAdjustment` + `Retarget` confrontée bloc à bloc à ce que la chaîne a accepté.
SUITE_NAME=pow
source "$(dirname "${BASH_SOURCE[0]}")/suite-common.sh"

VICTIM=${1:-0}

# Constantes du profil réseau — lues depuis profiles/$NETWORK.env (common.sh), l'artefact CHECKÉ
# et confronté à NetworkParameters.<profil>() par TestnetProfileMirrorTest (app-node). Elles ne
# sont pas lues sur le nœud (il n'expose que desiredBlockTimeSec) : si le profil change sans que
# ce fichier soit régénéré et revu, TestnetProfileMirrorTest fait échouer le build — la batterie,
# elle, doit ÉCHOUER sur le terrain plutôt que de s'ajuster silencieusement à une valeur lue en
# direct sur le nœud ou recalculée depuis NetworkParameters.
LOOKBACK=$(profile_get DIFFICULTY_LOOKBACK); DMIN=$(profile_get MIN_DIFFICULTY)
DMAX=$(profile_get MAX_DIFFICULTY); DGEN=$(profile_get GENESIS_DIFFICULTY)
FUTURE_MS=$(( $(profile_get MAX_FUTURE_BLOCK_TIME_SEC) * 1000 ))
SRC=http://127.0.0.1:4406       # source : mineur solo, sans pairs, cadence 3 s (difficulté au plancher)
VIC=http://127.0.0.1:4407       # victime : sans mineur, sans pairs — n'avance que par /submit
VIC_DATA="$BASE_DIR/solo-vic"
DIFFSCAN="$ROOT/scripts/local-testnet/tools/diffscan.py"

# --- outil de forge de blocs ---------------------------------------------------------------
ANVIL_SRC="$ROOT/scripts/local-testnet/tools/Anvil.java"
NODE_LIB="$ROOT/app-node/build/install/app-node/lib/*"
anvil_build() {
  ensure_jdk25
  if [[ ! -f "$TOOLS_DIR/Anvil.class" || "$ANVIL_SRC" -nt "$TOOLS_DIR/Anvil.class" ]]; then
    mkdir -p "$TOOLS_DIR"
    "$JAVA_HOME/bin/javac" -cp "$NODE_LIB" -d "$TOOLS_DIR" "$ANVIL_SRC" || return 1
  fi
}
# Soumet un bloc à la victime et rend `code|statut`, comme submit_tx pour les transactions.
anvil() {
  local out; out="$("$JAVA_HOME/bin/java" -cp "$TOOLS_DIR:$NODE_LIB" Anvil \
      --source "$SRC" --url "$VIC" --network "$NETWORK" --quiet "$@" 2>&1 | tail -1)"
  local code="${out%% *}" body="${out#* }"
  printf '%s|%s' "$code" "$(json_get "$body" status)"
}

vic_get() { curl -sf --max-time 5 "$VIC$1" 2>/dev/null; }
vic_height() { json_get "$(vic_get /stats)" height; }
vic_difficulty() { json_get "$(vic_get /stats)" difficulty; }

# Médiane des horodatages de la fenêtre MTP, calculée comme ChainEngine.medianTimePast : c'est le
# plancher exact qu'un bloc doit dépasser, pas une approximation.
vic_mtp() {
  "$PY" - "$VIC" <<'EOF'
import json, sys, urllib.request
base = sys.argv[1]
def get(p):
    with urllib.request.urlopen(base + p, timeout=10) as r: return json.load(r)
tip = int(get("/stats")["height"])
window = min(60, tip)
ts = sorted(int(get(f"/block?blockId={h}")["timestamp"]) for h in range(tip - window + 1, tip + 1))
print(ts[window // 2])
EOF
}

# Redémarre la victime SANS toucher à ses données : la difficulté doit être reconstruite à
# l'identique depuis les horodatages stockés. C'est le défaut Pandanite (difficulté mise en cache,
# devenue périmée après un pop, d'où l'exception codée en dur sur les blocs 536100-536200) mesuré
# sur un nœud vivant plutôt qu'en test unitaire.
restart_victim() {
  local pid; pid="$(victim_pid)"
  [[ -n "$pid" ]] && kill $pid 2>/dev/null
  sleep 2
  setsid env RHIZOME_NETWORK="$NETWORK" RHIZOME_PORT=4407 RHIZOME_DATA="$VIC_DATA" \
    "$NODE_BIN" -Xmx256m >> "$BASE_DIR/logs/solo-vic.log" 2>&1 < /dev/null &
  local deadline=$((SECONDS + 60))
  while (( SECONDS < deadline )); do [[ -n "$(vic_height)" ]] && return 0; sleep 1; done
  return 1
}

# Le PID de la victime se lit dans son environnement, pas dans sa ligne de commande : tous les
# nœuds partagent le même argv (le binaire natif), seul RHIZOME_DATA les distingue.
victim_pid() {
  ps -eo pid,args | grep "[r]hizome-node" | awk '{print $1}' \
    | while read -r p; do tr '\0' '\n' < "/proc/$p/environ" 2>/dev/null \
    | grep -q "RHIZOME_DATA=$VIC_DATA" && echo "$p"; done
}

# Repart d'une chaîne vierge : chaque scénario de calendrier impose ses propres horodatages, et
# un reliquat de scénario précédent (un horodatage gonflé, par exemple) fausserait la MTP suivante.
reset_victim() {
  local pid; pid="$(victim_pid)"
  [[ -n "$pid" ]] && kill $pid 2>/dev/null
  sleep 2
  rm -rf "$VIC_DATA"
  setsid env RHIZOME_NETWORK="$NETWORK" RHIZOME_PORT=4407 RHIZOME_DATA="$VIC_DATA" \
    "$NODE_BIN" -Xmx256m > "$BASE_DIR/logs/solo-vic.log" 2>&1 < /dev/null &
  local deadline=$((SECONDS + 60))
  while (( SECONDS < deadline )); do [[ -n "$(vic_height)" ]] && return 0; sleep 1; done
  return 1
}

anvil_build || { echo "compilation d'Anvil impossible" >&2; exit 1; }

echo "=== POW/TIME : retarget et bornes temporelles ==="

# --- 1. La dynamique du réseau réel --------------------------------------------------------
# diffscan rejoue tout l'historique du nœud victime de la campagne et rend son verdict.
SCAN="$($PY "$DIFFSCAN" "$(node_url "$VICTIM")" --lookback $LOOKBACK --min $DMIN --max $DMAX --genesis $DGEN)"
scan_field() { json_get "$SCAN" "$1"; }
scan_py() { "$PY" -c "$1" <<<"$SCAN"; }

expect_eq RETARGET-01 0 "$(scan_py 'import json,sys;print(len(json.load(sys.stdin)["mismatches"]))')" \
  "difficulté de chaque bloc = repli indépendant des fenêtres ($(scan_field scanned) blocs)"
expect_eq RETARGET-02 0 "$(scan_py 'import json,sys;d=json.load(sys.stdin);print(len(d["offBoundaryChanges"])+len(d["oversizedSteps"]))')" \
  "changements hors frontière + pas > MAX_STEP_BITS"
expect_eq RETARGET-03 0 "$(scan_py 'import json,sys;print(len(json.load(sys.stdin)["outOfBounds"]))')" \
  "difficulté toujours dans [$DMIN, $DMAX]"
expect_eq RETARGET-04 0 "$(scan_py 'import json,sys;print(len(json.load(sys.stdin)["linkBreaks"]))')" \
  "continuité des liens parent/enfant sur toute la chaîne scannée"

LADDER="$(scan_py 'import json,sys;d=json.load(sys.stdin);print(" ".join("%d:%d->%d"%(l["height"],l["from"],l["to"]) for l in d["ladder"]))')"
CLIMBED="$(scan_py "import json,sys;d=json.load(sys.stdin);print('oui' if d['finalDifficulty']>$DGEN else 'non')")"
expect_eq RETARGET-05 oui "$CLIMBED" "la difficulté a QUITTÉ son plancher sous cadence trop rapide — échelle: $LADDER"
record RETARGET-06 PASS "échelle observée: $LADDER (final $(scan_field finalDifficulty))"

# La branche DESCENDANTE, en hashrate réel : trois des quatre mineurs coupés, la cadence
# s'effondre, la difficulté doit retomber. C'est le sens qui compte pour un testnet public — un
# réseau perd du hashrate bien plus souvent qu'il n'en gagne, et une difficulté qui ne redescend
# pas fige la chaîne.
DESCENT="$(scan_py 'import json,sys;d=json.load(sys.stdin);print(sum(1 for l in d["ladder"] if l["to"] < l["from"]))')"
DESC_OK=$([[ -n "$DESCENT" && "$DESCENT" -ge 1 ]] && echo oui || echo non)
expect_eq RETARGET-12 oui "$DESC_OK" "au moins un palier descendant après la coupure de hashrate ($DESCENT observés)"

# Convergence : sur les dernières fenêtres fermées, la durée observée doit tenir dans la bande
# morte [desired/2, 2*desired] — c'est la définition opérationnelle de « le retarget a régulé ».
CONVERGED="$(scan_py 'import json,sys
d=json.load(sys.stdin); w=d["windows"][-5:]
ok=[x for x in w if x["desiredSec"]/2 <= max(1,x["observedSec"]) <= 2*x["desiredSec"]]
print("%d/%d"%(len(ok),len(w)))')"
expect_contains RETARGET-07 "/" "$CONVERGED" "fenêtres récentes dans la bande morte (observé vs cible): $CONVERGED"

# --- 2. Les portes, sur la paire isolée -----------------------------------------------------
reset_victim || { echo "victime injoignable" >&2; exit 1; }
expect_eq POW-CTRL-01 1 "$(vic_height)" "victime repartie de la genèse"

# Rejeu honnête : le TÉMOIN. Sans lui, tout rejet mesuré ensuite serait ininterprétable.
expect_reject POW-CTRL-02 SUCCESS 200 "$(anvil --count 5)" "rejeu honnête de 5 blocs source"
expect_eq POW-CTRL-03 6 "$(vic_height)" "hauteur de la victime après rejeu"

expect_reject POW-01 INVALID_NONCE 400 "$(anvil --no-pow)" \
  "bloc revendiquant une difficulté qu'il n'a pas payée"
expect_reject POW-02a INVALID_DIFFICULTY 400 "$(anvil --diff-delta -1)" \
  "difficulté déclarée plus faible que celle imposée par l'historique"
expect_reject POW-02b INVALID_DIFFICULTY 400 "$(anvil --diff-delta 2)" \
  "difficulté déclarée plus forte que celle imposée par l'historique"

expect_reject TIME-01a BLOCK_TIMESTAMP_IN_FUTURE 400 "$(anvil --ts-offset $((FUTURE_MS + 10000)))" \
  "pré-minage au-delà de la fenêtre future (+$((FUTURE_MS/1000+10)) s)"
expect_reject TIME-01b SUCCESS 200 "$(anvil --ts-offset $((FUTURE_MS - 10000)))" \
  "pré-minage DANS la fenêtre future (+$((FUTURE_MS/1000-10)) s) — la borne est une borne, pas un interdit"

MTP="$(vic_mtp)"
expect_reject TIME-02 BLOCK_TIMESTAMP_TOO_OLD 400 "$(anvil --ts-abs "$MTP")" \
  "horodatage à la médiane du passé (MTP=$MTP)"

PARENT_TS="$(json_get "$(vic_get "/block?blockId=$(vic_height)")" timestamp)"
expect_reject TIME-04 BLOCK_TIMESTAMP_TOO_CLOSE 400 "$(anvil --ts-parent -1)" \
  "horodatage antérieur au parent ($PARENT_TS)"

expect_reject POW-CTRL-04 SUCCESS 200 "$(anvil)" "témoin après la série de rejets"
expect_eq POW-CTRL-05 "degraded=null reorg=false" "$(node_healthy "$VICTIM")" "nœud de campagne intact après la série"

# --- 3. Timewarp : la borne de fenêtre est une MÉDIANE, pas un horodatage brut ---------------
# Un seul horodatage de frontière gonflé doit rester sans effet : la médiane de 3 l'écarte.
# On mesure les deux prédictions et on regarde laquelle la chaîne a suivie.
reset_victim || exit 1
T0=$(( $(date +%s%3N) - 900000 ))           # calendrier ancré 15 min dans le passé
STEP=1200
for h in $(seq 2 19); do
  anvil --ts-abs $((T0 + h * STEP)) >/dev/null
done
INFLATED=$((T0 + 20 * STEP + 600000))        # +10 min sur la SEULE frontière (h=20)
R_INFLATED="$(anvil --ts-abs $INFLATED)"
expect_reject TIME-03a SUCCESS 200 "$R_INFLATED" "frontière h=20 gonflée de +600 s (dans la fenêtre future)"
anvil --ts-abs $((INFLATED + 1000)) >/dev/null   # h=21 : porte la difficulté recalculée
D21="$(json_get "$(vic_get "/block?blockId=21")" difficulty)"

WARP="$($PY "$DIFFSCAN" "$VIC" --lookback $LOOKBACK --min $DMIN --max $DMAX --genesis $DGEN)"
warp_py() { "$PY" -c "$1" <<<"$WARP"; }
expect_eq TIME-03b 0 "$(warp_py 'import json,sys;print(len(json.load(sys.stdin)["mismatches"]))')" \
  "la chaîne suit la règle MÉDIANE sur toute sa hauteur"
DIVERGENT="$(warp_py 'import json,sys;d=json.load(sys.stdin)["medianVsRaw"]["divergentBoundaries"];print(json.dumps(d))')"
expect_contains TIME-03c '"boundary": 20' "$DIVERGENT" \
  "difficulté retenue en h=21 : $D21 — médiane vs brut: $DIVERGENT"

# --- 4. Le domaine complet du retarget, jusqu'au plancher ------------------------------------
# La victime est une machine à remonter le temps : on impose un calendrier serré (montée +4 bits
# par fenêtre) puis un calendrier très lâche (descente -4 bits par fenêtre) et on vérifie que la
# difficulté revient exactement au plancher et s'y arrête — le clamp bas, jamais atteint en réseau.
reset_victim || exit 1
T0=$(( $(date +%s%3N) - 8000000 ))
CLIMB_STEP=310
for h in $(seq 2 61); do anvil --ts-abs $((T0 + h * CLIMB_STEP)) >/dev/null; done
D_PEAK="$(vic_difficulty)"
PEAK_TIP="$(json_get "$(vic_get /stats)" tipHash)"
PEAK_H="$(vic_height)"

# POW-03 en direct : redémarrage au sommet de la montée, difficulté NON triviale (≠ genèse).
restart_victim || exit 1
expect_eq POW-03a "$D_PEAK" "$(vic_difficulty)" \
  "difficulté reconstruite depuis les horodatages après redémarrage (chaîne de $PEAK_H blocs, 3 frontières)"
expect_eq POW-03b "$PEAK_TIP" "$(json_get "$(vic_get /stats)" tipHash)" "tip identique après redémarrage"

LAST_TS=$((T0 + 61 * CLIMB_STEP))
DESC_STEP=80000
for h in $(seq 62 141); do anvil --ts-abs $((LAST_TS + (h - 61) * DESC_STEP)) >/dev/null; done
D_FLOOR="$(vic_difficulty)"

SWEEP="$($PY "$DIFFSCAN" "$VIC" --lookback $LOOKBACK --min $DMIN --max $DMAX --genesis $DGEN)"
sweep_py() { "$PY" -c "$1" <<<"$SWEEP"; }
SWEEP_LADDER="$(sweep_py 'import json,sys;d=json.load(sys.stdin);print(" ".join("%d:%d->%d"%(l["height"],l["from"],l["to"]) for l in d["ladder"]))')"
expect_eq RETARGET-08 0 "$(sweep_py 'import json,sys;print(len(json.load(sys.stdin)["mismatches"]))')" \
  "balayage montée/descente conforme au repli indépendant"
expect_eq RETARGET-09 0 "$(sweep_py 'import json,sys;d=json.load(sys.stdin);print(len(d["oversizedSteps"])+len(d["offBoundaryChanges"]))')" \
  "aucun pas > 4 bits, aucun changement hors frontière — échelle: $SWEEP_LADDER"
expect_eq RETARGET-10 "$DMIN" "$D_FLOOR" \
  "descente ramenée au plancher et clampée (pic atteint: $D_PEAK)"
CLIMB_OK=$([[ -n "$D_PEAK" && "$D_PEAK" -gt "$DGEN" ]] && echo oui || echo non)
expect_eq RETARGET-11 oui "$CLIMB_OK" "montée sous calendrier serré (pic $D_PEAK)"

suite_summary
