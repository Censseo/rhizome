# Rhizome

Port Java de [Pandanite](https://github.com/pandanite-crypto/pandanite) (C++) — une
blockchain PoW simple, réécrite comme une **chaîne propre** : nouveau genesis, règles
de consensus corrigées, et un ledger de départ **amorcé depuis un snapshot** des soldes
de la chaîne Pandanite existante.

L'objectif de conception est la **performance brute** (cible : **1 bloc/seconde** validé
par le réseau) avec une preuve de travail **Pufferfish2** dès le genesis.

> État : **cœur de nœud fonctionnel et testé** (consensus, exécution, stockage,
> mempool, API, production de blocs, synchronisation P2P avec reorg, wallet).
> Suite de tests : **166 tests, 0 échec**. Reste dépendant de l'environnement :
> build natif GraalVM et production du snapshot Pandanite réel.

---

## Pourquoi une chaîne propre

Le Pandanite C++ traîne des bugs de consensus fossilisés (underflow `uint64`
inflationniste, récompense en `double`, difficulté non déterministe, validation de
chaînage absente sur les premiers blocs, malléabilité Ed25519, entrées réseau non
bornées…). Plutôt que de les rejouer, Rhizome repart d'un genesis neuf dont l'état
initial est un **snapshot assaini** des soldes, et corrige chaque bug d'emblée. Le
détail est dans [`MIGRATION_PLAN.md`](MIGRATION_PLAN.md) (§4) et le
[livre blanc](docs/WHITEPAPER.md).

## Architecture

Projet Gradle multi-modules (Java 21) :

| Module | Rôle |
|---|---|
| `lib-core` | Consensus, exécution, mempool, PoW Pufferfish2, difficulté, modèle de bloc/transaction, moteur de chaîne et synchronisation |
| `lib-persistence` | Stockage RocksDB (chaîne + ledger + txdb) via un magasin unique à familles de colonnes |
| `lib-net` | Couche réseau/decouverte (support) |
| `lib-crypto` | (réservé — le crypto vit pour l'instant dans `lib-core`) |
| `app-node` | Nœud exécutable : API HTTP, production de blocs, gossip, découverte de pairs, sync |
| `app-wallet` | Wallet en ligne de commande |
| `app-dnsseeder` | Amorçage réseau |

## Prérequis

```
JDK 21
Gradle 8.5+
```

## Construire et tester

```bash
./gradlew build          # compile tous les modules
./gradlew test           # exécute la suite complète (166 tests)
```

## Lancer un nœud

Le nœud se configure par variables d'environnement :

| Variable | Défaut | Rôle |
|---|---|---|
| `RHIZOME_NETWORK` | `mainnet` | `mainnet` (chaîne propre) ou `testnet` (difficulté basse) |
| `RHIZOME_PORT` | `3000` | port de l'API HTTP |
| `RHIZOME_DATA` | `./data` | répertoire RocksDB |
| `RHIZOME_SNAPSHOT` | — | fichier snapshot amorçant le genesis (optionnel) |
| `RHIZOME_MINER` | — | adresse recevant les récompenses (active la production de blocs) |
| `RHIZOME_PEERS` | — | pairs initiaux, séparés par des virgules |
| `RHIZOME_ADVERTISE` | — | URL publique annoncée aux pairs |

```bash
# Nœud mineur local
RHIZOME_NETWORK=testnet RHIZOME_MINER=<adresse> ./gradlew :app-node:run
```

### API HTTP du nœud

Lecture : `GET /block_count`, `/total_work`, `/difficulty`, `/mempool`, `/info`,
`/peers`, `/block?blockId=N`, `/wallet?address=HEX`, `/sync?start=A&end=B`.
Écriture : `POST /add_transaction` (binaire), `/add_transaction_json`, `/submit`
(bloc), `/add_peer`. Chaque endpoint répond toujours (entrée invalide → 400), les
requêtes sont limitées en débit par client et bornées en taille.

## Wallet (CLI)

Via Gradle (`./gradlew :app-wallet:run --args="..."`) ou l'exécutable assemblé :

```bash
keygen  <keyfile>                              # crée une paire de clés
address <keyfile>                              # affiche l'adresse
balance <nodeUrl> <address>                    # solde + prochain nonce
send    <nodeUrl> <keyfile> <to> <amount> [fee]  # signe et soumet un transfert
```

## Paramètres de consensus (mainnet propre)

| Paramètre | Valeur |
|---|---|
| PoW | Pufferfish2 (dès le genesis) |
| Cadence cible | 1 bloc/s (`minBlockTime` = 1 s, imposé par le réseau) |
| Difficulté genesis / min / max | 16 / 16 / 255 |
| Fenêtre de retarget | 60 blocs |
| Borne futur / fenêtre temps médian | 15 s / 11 blocs |
| Profondeur max de reorg (finalité) | 600 blocs |
| Max transactions / bloc | 25 000 |
| Récompense initiale | 50 PDN, décroissance ×2/3 tous les 666 666 blocs |
| Anti-rejeu | `chainId` + nonce de compte dans le préimage signé |

## Sécurité

La surface d'attaque et la couverture de tests sont documentées dans
[`docs/SECURITY_REVIEW.md`](docs/SECURITY_REVIEW.md) : double-dépense, malléabilité
de signature, rejeu, montants négatifs, overflow de solde, manipulation
d'horodatage/difficulté, reorg profond, pairs hostiles (ban-score), flood et DoS
réseau. La sécurité de la cadence 1 bloc/s est traitée dans
[`docs/BLOCK_RATE_SECURITY.md`](docs/BLOCK_RATE_SECURITY.md).

## Documentation

- [`docs/WHITEPAPER.md`](docs/WHITEPAPER.md) — livre blanc technique
- [`MIGRATION_PLAN.md`](MIGRATION_PLAN.md) — plan de migration & audit des bugs C++
- [`docs/SECURITY_REVIEW.md`](docs/SECURITY_REVIEW.md) — revue de sécurité
- [`docs/PERFORMANCE_STACK.md`](docs/PERFORMANCE_STACK.md) — choix techniques de performance
- [`docs/BLOCK_CADENCE.md`](docs/BLOCK_CADENCE.md) / [`docs/BLOCK_RATE_SECURITY.md`](docs/BLOCK_RATE_SECURITY.md) — cadence des blocs

## Feuille de route

**Étape 1 — Cœur (fait)** : crypto & Pufferfish2, modèle de consensus, Executor,
moteur de chaîne, stockage RocksDB, mempool, API HTTP, production de blocs,
synchronisation + reorg par travail cumulé, wallet, gossip & découverte de pairs,
durcissement (checkpoints, finalité, rate limiting, ban-score, revue de sécurité).

**Étape 2 — Lancement** : build natif GraalVM, snapshot Pandanite réel, testnet
public, puis mainnet ; stabilisation du réseau, expérience wallet, pool officiel.

**Étape 3 — Expansion** : contrats intelligents, cas d'usage DeFi, protocoles
cross-chain et bridge.

## Contribuer

Modèle de branches gitflow (`master` / `develop` / `feature/*` / `release` /
`hotfix`). Les messages de commit commencent par un sujet court ; les pull requests
décrivent le changement et lient les issues concernées.
