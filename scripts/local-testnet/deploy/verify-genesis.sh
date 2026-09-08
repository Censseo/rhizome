#!/usr/bin/env bash
# Vérifie qu'un nœud Rhizome joignable par HTTP(S) sert bien LA chaîne attendue, avant de lui
# faire confiance (post-déploiement, ou avant d'ajouter un pair) : compare le hash du bloc 1
# (« /block?blockId=1 ») ainsi que chainId/network (« /info ») à des valeurs attendues fournies
# par l'appelant — jamais codées en dur ici, pour que ce script vérifie N'IMPORTE LEQUEL des
# réseaux épinglés du dépôt (mainnet/testnet/devnet/staging), pas seulement staging.
#
# Dépendances volontairement minimales (bash + curl, PAS de jq ni de python3) : ce script vit
# à côté de rhizome-node.service / nginx-rhizome.conf.example dans deploy/, destiné à tourner
# sur une machine de déploiement qui n'a pas forcément la boîte à outils Python de
# scripts/local-testnet/tools/ (elle, dépend de $PY — voir common.sh). Les deux champs
# recherchés (chainId entier, network et hash chaînes sans espace) sortent proprement d'une
# extraction par expression régulière sur le JSON compact que JsonSink produit ; un vrai
# analyseur JSON serait plus robuste en général mais serait aussi la première dépendance
# externe de ce script précis.
#
# Usage :
#   verify-genesis.sh <url_du_nœud> [options]
#
# Options (chacune a un équivalent en variable d'environnement, utile en CI) :
#   --chain-id <N>        RHIZOME_VERIFY_CHAIN_ID    chainId attendu (entier, /info)
#   --network <nom>        RHIZOME_VERIFY_NETWORK     nom réseau attendu (ex. rhizome-staging, /info)
#   --genesis-hash <hex>   RHIZOME_VERIFY_GENESIS_HASH  hash attendu du bloc 1, hex MAJUSCULE (/block)
#   --token <jeton>        RHIZOME_VERIFY_TOKEN       jeton porteur, si le nœud gate les lectures
#                                                      (RHIZOME_PROTECT_READS=true côté nœud)
#   -k, --insecure                                    passe -k à curl (ignore la vérif TLS) —
#                                                      JAMAIS par défaut : un script de VÉRIFICATION
#                                                      d'identité de chaîne qui accepte n'importe
#                                                      quel certificat se fait usurper le nœud
#                                                      qu'il croit vérifier. N'active que pour un
#                                                      test local à certificat auto-signé connu.
#   -h, --help
#
# Au moins UNE des trois valeurs attendues doit être fournie, sinon il n'y a rien à vérifier
# (erreur d'usage, pas un succès silencieux). Code de sortie : 0 si toutes les valeurs fournies
# correspondent, 1 erreur d'usage, 2 le nœud n'a pas répondu, 3 au moins un désaccord constaté.
#
# Exemple (réseau staging, valeurs à substituer par celles réellement publiées) :
#   verify-genesis.sh https://node.example.org \
#     --network rhizome-staging --chain-id 4 \
#     --genesis-hash 0123...CAFE
set -uo pipefail
# PAS de -e : une réponse HTTP inattendue ou un JSON étrange doit produire un verdict FAIL net,
# pas tuer le script au milieu (même raisonnement que json_get dans common.sh).

NODE_URL=""
EXPECT_CHAIN_ID="${RHIZOME_VERIFY_CHAIN_ID:-}"
EXPECT_NETWORK="${RHIZOME_VERIFY_NETWORK:-}"
EXPECT_HASH="${RHIZOME_VERIFY_GENESIS_HASH:-}"
TOKEN="${RHIZOME_VERIFY_TOKEN:-}"
INSECURE=0

usage() {
  cat <<'EOF'
usage: verify-genesis.sh <node_url> [options]

  --chain-id <N>          expected chainId from /info      (env RHIZOME_VERIFY_CHAIN_ID)
  --network <name>        expected network from /info       (env RHIZOME_VERIFY_NETWORK)
  --genesis-hash <hex>    expected block-1 hash from /block  (env RHIZOME_VERIFY_GENESIS_HASH)
  --token <token>         bearer token, if the node gates reads (env RHIZOME_VERIFY_TOKEN)
  -k, --insecure          pass -k to curl (skip TLS verification) — off by default, see the
                          header comment in this script for why that matters here
  -h, --help              this message

At least one of --chain-id / --network / --genesis-hash (or its env var) must be given.
Exit status: 0 all provided checks matched, 1 usage error, 2 the node did not respond,
3 at least one mismatch.
EOF
}

# Sous `set -u`, une option qui attend un argument mais arrive en dernier ($2 absent) doit
# produire une erreur d'usage PROPRE, pas une trace bash brute sur une "unbound variable" — la
# garde `$# -lt 2` ci-dessous distingue les deux.
while [[ $# -gt 0 ]]; do
  case "$1" in
    --chain-id|--network|--genesis-hash|--token)
      if [[ $# -lt 2 ]]; then
        echo "$1 attend une valeur" >&2; usage >&2; exit 1
      fi
      case "$1" in
        --chain-id) EXPECT_CHAIN_ID=$2 ;;
        --network) EXPECT_NETWORK=$2 ;;
        --genesis-hash) EXPECT_HASH=$2 ;;
        --token) TOKEN=$2 ;;
      esac
      shift 2 ;;
    -k|--insecure) INSECURE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "option inconnue : $1" >&2; usage >&2; exit 1 ;;
    *)
      if [[ -n "$NODE_URL" ]]; then
        echo "un seul URL de nœud attendu, reçu en trop : $1" >&2; exit 1
      fi
      NODE_URL=$1; shift ;;
  esac
done

if [[ -z "$NODE_URL" ]]; then
  echo "usage : $0 <url_du_nœud> [--chain-id N] [--network nom] [--genesis-hash hex] [--token jeton] [-k]" >&2
  exit 1
fi
NODE_URL="${NODE_URL%/}"

if [[ -z "$EXPECT_CHAIN_ID" && -z "$EXPECT_NETWORK" && -z "$EXPECT_HASH" ]]; then
  echo "rien à vérifier : fournir au moins --chain-id, --network ou --genesis-hash" \
    "(ou la variable d'environnement équivalente)" >&2
  exit 1
fi

CURL_OPTS=(-sS --max-time 10)
(( INSECURE )) && CURL_OPTS+=(-k)
[[ -n "$TOKEN" ]] && CURL_OPTS+=(-H "Authorization: Bearer $TOKEN")
# Options TLS additionnelles (ex. --cacert pour une AC interne), mot-scindées volontairement —
# même convention que CURL_TLS_OPTS dans suite-common.sh, mais en variable simple ici plutôt
# qu'un tableau bash : ce script n'est pas sourcé par un harnais qui la déclarerait pour nous.
if [[ -n "${RHIZOME_VERIFY_CURL_OPTS:-}" ]]; then
  # shellcheck disable=SC2206  # scission volontaire sur les espaces, voir commentaire ci-dessus
  CURL_OPTS+=($RHIZOME_VERIFY_CURL_OPTS)
fi

fetch() {
  curl "${CURL_OPTS[@]}" "$NODE_URL$1" 2>/dev/null
}

# Extrait la valeur d'un champ JSON scalaire de premier niveau par expression régulière, sans
# dépendance externe. Sûr ici précisément parce que JsonSink sérialise dans un ordre de champs
# FIXE et que /info comme /block?blockId=1 ne renvoient qu'un objet plat pour les trois champs
# recherchés (chainId, network, hash) — hash en particulier est écrit AVANT le tableau des
# transactions dans le corps du bloc (Block.writeJsonBody), donc la PREMIÈRE occurrence dans le
# texte est garantie être celle de l'en-tête, jamais celle d'une transaction.
json_field() {
  local json=$1 key=$2
  printf '%s' "$json" \
    | grep -oE "\"$key\"[[:space:]]*:[[:space:]]*(\"[^\"]*\"|-?[0-9]+)" \
    | head -n1 \
    | sed -E "s/\"$key\"[[:space:]]*:[[:space:]]*//; s/^\"//; s/\"\$//"
}

PASS=0
FAIL=0

check() {
  local label=$1 expected=$2 actual=$3
  if [[ -z "$expected" ]]; then
    return   # non demandé, on ne juge pas
  fi
  if [[ "$actual" == "$expected" ]]; then
    printf '  \033[32mOK\033[0m   %-12s %s\n' "$label" "$actual"
    PASS=$((PASS + 1))
  else
    printf '  \033[31mFAIL\033[0m %-12s attendu %s, obtenu %s\n' "$label" "$expected" "${actual:-<vide>}"
    FAIL=$((FAIL + 1))
  fi
}

echo "vérification de $NODE_URL"

INFO_JSON=""
if [[ -n "$EXPECT_CHAIN_ID" || -n "$EXPECT_NETWORK" ]]; then
  INFO_JSON="$(fetch /info)"
  if [[ -z "$INFO_JSON" ]]; then
    echo "  échec : /info n'a pas répondu (nœud injoignable, TLS refusé, ou jeton manquant/invalide)" >&2
    exit 2
  fi
  check chainId "$EXPECT_CHAIN_ID" "$(json_field "$INFO_JSON" chainId)"
  check network "$EXPECT_NETWORK" "$(json_field "$INFO_JSON" network)"
fi

if [[ -n "$EXPECT_HASH" ]]; then
  BLOCK_JSON="$(fetch "/block?blockId=1")"
  if [[ -z "$BLOCK_JSON" ]]; then
    echo "  échec : /block?blockId=1 n'a pas répondu (nœud injoignable, TLS refusé, ou jeton manquant/invalide)" >&2
    exit 2
  fi
  # hash sort en hexadécimal MAJUSCULE sans préfixe 0x (JsonSink.hexUpper) ; on normalise la
  # casse des DEUX côtés pour ne pas faire échouer une comparaison sur une simple différence de
  # casse dans la valeur --genesis-hash fournie par l'appelant.
  ACTUAL_HASH="$(json_field "$BLOCK_JSON" hash)"
  check genesis-hash "${EXPECT_HASH^^}" "${ACTUAL_HASH^^}"
fi

echo "---"
printf '%d OK, %d FAIL\n' "$PASS" "$FAIL"
if (( FAIL > 0 )); then
  exit 3
fi
exit 0
