# Rhizome — Plan de migration vers une blockchain fonctionnelle

> Port Java de [Pandanite](https://github.com/pandanite-crypto/pandanite) (C++).
> Document rédigé après analyse de l'état d'avancement de Rhizome, audit des bugs
> du Pandanite C++, et revue des issues/PR du dépôt d'origine.
> Dernière mise à jour du projet avant reprise : mi-2024.

---

## 0. Décisions verrouillées

L'architecture cible est arrêtée : **chaîne propre, ledger genesis amorcé depuis un
snapshot des soldes de la chaîne Pandanite actuelle.**

| Décision | Choix retenu |
|---|---|
| Type de chaîne | **Nouvelle chaîne** (nouveau genesis, règles corrigées), pas d'interop mainnet historique |
| État initial | **Snapshot des soldes** de la chaîne Pandanite figé dans le genesis |
| Soldes | **Assainis** : wallets d'`invalid.json` (bug d'inflation) exclus du snapshot |
| PoW | **Pufferfish2 dès le genesis** (pas de phase SHA-256, pas de bascule) |
| Source du snapshot | Outil de dump lisant le LevelDB ledger d'un nœud Pandanite synchronisé |

Comme on repart sur une chaîne propre, **aucun bug C++ historique n'est à reproduire** :
préimage de hash incomplet, mining fee flottante, Merkle bugué, `invalid.json`,
`bannedHashes`, hacks de difficulté — tous corrigés d'emblée.

---

## 1. Résumé exécutif

Rhizome est un **squelette d'architecture solide sur la couche « données », mais sans
la couche « vivante » d'un nœud** (consensus, exécution, synchronisation, réseau, API,
minage). Le projet était resté figé au milieu d'une réorganisation de packages qui
**cassait la compilation**.

**État après reprise :** la compilation et la suite de tests sont de nouveau vertes
(34 tests, 0 échec) sans ajout de logique métier — voir §3. Le travail applicatif
peut donc repartir d'une base saine.

**Avancement global estimé : ~30–35 %** d'un nœud fonctionnel, concentré sur le modèle
d'objets, la persistance et la crypto de base (bien testés, compatibles binaire avec
Pandanite). Tout le reste est absent ou à l'état de stub.

| Domaine | Avancement | Commentaire |
|---|---|---|
| Crypto (signatures ed25519, SHA-256, adresses) | ~65 % | OK + vecteurs de compat legacy testés ; **Pufferfish = stub qui jette** |
| Modèle bloc/transaction (+ Merkle, sérialisation) | ~85 % | Quasi-parité C++, testé ; quelques stubs mineurs |
| Ledger | ~70 % | Logique complète, désormais compilable, arithmétique contrôlée ; pas d'intégration executor |
| Persistance (blockstore / txdb) | ~70 % | Parité block_store/tx_store, testée ; bug soupçonné `getTransactionsForWallet` |
| Réseau P2P | ~15 % | Squelette ActiveJ + FloodDiscovery ; transport/handlers/broadcast à refaire |
| Consensus / PoW / sync | ~5 % | `verifyHash` SHA-256 seul ; ni validation de bloc, ni difficulté, ni sync, ni genesis |
| API HTTP nœud | ~10 % | Client `PeerInterface` porté ; serveur = hello-world |
| Wallet | ~5 % | `User` (clés/signature) présent ; `app-wallet` vide |
| Minage | ~0 % | Prototype `mineHash` mort, pas de mineur ni de mining fee |

---

## 2. État détaillé par module

### lib-core — *le plus avancé*
- **Fonctionnel :** `Transaction`/`TransactionImpl` (hash, signature ed25519, JSON + DTO
  binaire), `Block`/`BlockImpl` (hash, `verifyNonce`, sérialisation), `MerkleTree` (testé),
  `User`/`UserImpl` (clés, adresse, `send`, `mine`), types crypto (`SHA256Hash`,
  `PublicKey`, `PrivateKey`, `PublicAddress`, `TransactionSignature`, `TransactionAmount`),
  sérialisation binaire ActiveJ, `BaseService` (services réactifs).
- **Stubs / vides :** `Blockchain.sync()` jette ; `AbstractBlockchain` = champs seulement
  (memPool/persistence commentés) ; **`ChainSync.java` entièrement commenté** ;
  **`MemPool` = interface vide** ; `PufferfishAlgorithm.compute()` **jette** →
  tout bloc mainnet > 124500 est invérifiable ; `engine/*` (machine à états) rattachée à rien.

### lib-crypto — **vide**
Aucun code ; le crypto vit en réalité dans `lib-core`. Module = intention seulement.

### lib-net — *refonte P2P inachevée (~15 %)*
- `Peer`, `PeerInitializer`, `FloodDiscovery` (testé), `MessageCode`, `PeerMessages` présents.
- `GossipSystem` : `cluster()`/`broadcast()`/`getPeers()` jettent ; `MessageHandler` :
  tous les handlers renvoient `null`.
- Les forks RPC ActiveJ-5 (`transport/rpc/*`) et `GossipStrategy` **ont été supprimés**
  lors de la reprise (ne compilaient plus sur ActiveJ 6.0, 100 % de stubs) — récupérables
  dans l'historique git si besoin.

### lib-persistence — *le plus proche de la parité (~70 %)*
- `LevelDBDataStore`, `LevelDBBlockPersistence` (≈ `block_store.cpp`), `TransactionStore`
  (≈ `tx_store.cpp`), et désormais **`LevelDBLedger`** (déplacé depuis lib-core).
- Manquants vs C++ : `pufferfish_cache`, genesis. Bug soupçonné dans `getTransactionsForWallet`.

### app-node — **vide** (module le plus critique, 0 %)
Le nœud réel (serveur HTTP API, blockchain, mempool, request manager) n'existe pas.

### app-wallet — **vide**
Aucun équivalent des outils C++ `cli`, `keygen`, `tx`.

### app-dnsseeder — *seul exécutable câblé, minimal*
Launchers ActiveJ qui démarrent et attendent. `Api` = serveur « Hello World » ;
`PeerManagerService` a le cycle ping/rediscover mais `broadcast()`/`sync()` sont vides ;
`BlockchainService`/`BlockchainSyncService` sont des no-op.

### Composants Pandanite SANS équivalent Java
Serveur HTTP API (`server.cpp` + `api.cpp`), **executor** (validation/exécution des tx),
**blockchain** (addBlock, validation, difficulté, popBlock/reorg, genesis), **mempool**,
`request_manager`, `header_chain` (chaînes virtuelles pour le choix de la meilleure chaîne),
**Pufferfish PoW**, ajustement de difficulté, outils `miner`/`cli`/`keygen`/`tx`,
`rate_limiter`. *(Pandanite n'a pas de smart contracts — rien à porter sur ce plan.)*

---

## 3. Travaux de remise en état déjà effectués

Commit « Restore compilation and green test suite ». Aucune logique métier ajoutée.

- **Dépendances** : ActiveJ et CQEngine résolus depuis Maven Central (JitPack renvoyait 403) ;
  **ASM forcé en 9.7** (le 9.4 tiré par ActiveJ ne lit pas le bytecode Java 21 → le
  sérialiseur plantait) ; BouncyCastle exposé en `api` dans lib-core.
- **lib-core** : import `BigInteger` corrigé dans `AbstractBlockchain` ; **`Ledger` devient
  une interface** et l'implémentation LevelDB part dans lib-persistence → la dépendance
  circulaire lib-core↔lib-persistence qui bloquait la compilation est levée.
- **lib-persistence** : `LevelDBLedger` avec **arithmétique contrôlée** (retrait/revert sous
  zéro et dépôt en overflow **jettent** au lieu de wrapper — cf. bug d'inflation §4.1) ;
  tests Ledger/TransactionStore replacés ici ; suppression du sérialiseur CQEngine mort.
- **lib-net / app-dnsseeder** : usage HTTP porté sur l'API builder d'ActiveJ 6.0 ;
  forks RPC ActiveJ-5 et `GossipStrategy` supprimés ; `BaseServiceTest` corrigé ;
  `NetworkTest`/`GossipProtocolTest` (des démos, pas des tests) retirés.

---

## 4. Bugs du Pandanite C++ à NE PAS reproduire

Consolidé depuis l'audit du code C++, la comparaison avec `pandanite-core`, et les
issues/PR du dépôt. **Ordre = criticité consensus d'abord.** Trois incidents réels sont
fossilisés dans le dépôt (`invalid.json`, `blacklist.txt`, `bannedHashes`, hack de difficulté
codé en dur), preuves de bugs exploités en production.

### 4.1 [CONSENSUS] Underflow `uint64_t` du ledger → inflation monétaire
`TransactionAmount` est un `uint64_t` ; `Ledger::withdraw` fait `value -= amt` **sans
contrôle** `value >= amt`. Cause de l'incident `invalid.json` (des tx `BALANCE_TOO_LOW`
acceptées par tout le réseau).
→ **Java** : montants à arithmétique **contrôlée**, rejet strict des underflows/overflows
**dans la couche ledger elle-même**. *Déjà appliqué dans `LevelDBLedger` (§3).*

### 4.2 [CONSENSUS] Récompense de minage en `double` → fork inter-implémentations
`getCurrentMiningFee` calcule la récompense par multiplications flottantes successives
(`amount *= 2.0/3.0`) puis troncature, et l'executor la compare **par égalité stricte**.
Toute divergence d'arrondi entre C++ et Java = split de chaîne. La formule inclut aussi
des décalages « magiques » dus à 3 forks historiques :
`logicalBlock = blockId + 125180 + 7750 + 18000`, halving tous les `666666`, base `50.0`,
`PDN(x) = (long)(x * 10000)`.
→ **Java** : soit répliquer *exactement* la même séquence flottante + troncature,
soit précalculer une **table entière** des récompenses par palier utilisée des deux côtés.
Ne jamais recalculer en flottant indépendamment.

### 4.3 [CONSENSUS] Difficulté non déterministe + périmée au reorg
`computeDifficulty` : recherche par doublement fragile (plafond `< 254` vs `MAX=255`).
Surtout, après un `popBlock`, `updateDifficulty()` **ne recalcule que si
`numBlocks % DIFFICULTY_LOOKBACK == 0`**, sinon la difficulté reste celle de la chaîne
poppée → origine probable du hack codé en dur pour les blocs 536100–536200.
→ **Java** : la difficulté attendue d'un bloc doit être une **fonction pure et
déterministe** des en-têtes précédents, **recalculée après chaque add ET pop**,
jamais lue depuis un champ stocké.

### 4.4 [CONSENSUS] Merkle : tri en place qui réordonne les transactions du bloc
`MerkleTree::setItems` **trie le vecteur reçu par référence** — appelé avec les
transactions du bloc pendant la validation, il les réordonne. Conséquence :
**l'ordre des transactions n'est pas engagé par le PoW** (un mineur peut réordonner),
et le rollback qui itère en ordre inverse dépend d'un ordre déjà muté.
De plus, l'implémentation Merkle historique était structurellement **fausse pour un
nombre impair de feuilles** (issue #29).
→ **Java** : `MerkleTree` travaille sur une **copie**, ne mute jamais le bloc ; l'ordre
des transactions est canonique et vérifié, ou engagé dans le hash. **Attention compat** :
si l'interopérabilité mainnet est visée, il faut reproduire le comportement effectivement
déployé, pas le comportement « correct ».

### 4.5 [CONSENSUS] Hash de bloc incomplet + choix de PoW hors du préimage
`getHash` ne couvre que `{merkleRoot, lastBlockHash, difficulty, timestamp}` — **ni `id`,
ni `numTransactions`**. Or `verifyNonce` choisit Pufferfish vs SHA256 selon `id >
124500`, une valeur **non engagée** par le PoW. *(Rhizome reproduit aujourd'hui ce même
préimage incomplet — `BlockImpl.hash()`.)*
→ **Java** : décider explicitement — pour la **compat mainnet**, garder le préimage à
l'identique et border proprement la transition d'algorithme au bloc 124500 ; documenter
le choix. Ne pas « corriger » silencieusement (cela forkerait la chaîne existante).

### 4.6 [CONSENSUS] Anti-rejeu fragile : pas de nonce de compte
`hashContents` couvre `{to, from, fee, amount, timestamp}` — **aucun nonce de compte,
aucun chain-id**. L'unicité repose sur `txdb.hasTransaction` + un `set` intra-bloc
(rustine). Deux virements identiques dans la même seconde sont « la même » transaction ;
rien n'empêche le rejeu cross-réseau. Malléabilité ED25519 (issue #37) : la clé anti-rejeu
incluait la signature → double exécution possible ; corrigé en passant au **hash du
contenu sans signature**.
→ **Java** : identifier les tx par le **hash du contenu signé (sans signature)** ;
introduire un **nonce par compte** + un **chain-id** dans le préimage signé ; rejeter les
signatures ED25519 non canoniques.

### 4.7 [CONSENSUS] Validation temporelle faible
« Futur » borné par un temps réseau = **médiane des deltas d'horloge des pairs**
(manipulable par Sybil) ; « median time past » sur seulement 10 blocs. Exploit réel :
des mineurs poussaient des timestamps dans le futur pour faire rejeter les blocs honnêtes
(`BLOCK_TIMESTAMP_TOO_OLD`, issues #19/#22).
→ **Java** : borner le futur avec l'**horloge locale** (pas seulement réseau), élargir la
fenêtre MTP, plafonner l'influence d'un pair sur le temps réseau, documenter le besoin NTP.

### 4.8 [CONSENSUS] Validation du chaînage manquante (LE bug historique #2)
Le nœud n'a longtemps **pas vérifié `lastBlockHash`** à l'acceptation d'un bloc → forks
massifs vers le bloc ~7400, divergence de soldes, réinitialisation de la chaîne.
→ **Java** : valider `block.lastBlockHash == hash(tip)` **et** l'`id` attendu **dès le
bloc 1, avant toute exécution**. Couvrir par un test de fork à deux nœuds.

### 4.9 [CONCURRENCE] Deadlock mempool ↔ blockchain + état partagé non verrouillé
Ordres de verrouillage opposés (`mempool → blockchain` dans `addTransaction`,
`blockchain → mempool` dans `addBlock`) → deadlock sous charge. Getters de la chaîne
(`getBlockCount`, `getDifficulty`, `getTotalWork`…) lus **sans verrou** alors que
`addBlock`/`popBlock` mutent sous verrou (`getTotalWork` copie un `Bigint` pendant sa
mutation → lecture déchirée). `host_manager` : `currPeers.empty()` au lieu de `.clear()`,
structures de pairs itérées sans verrou. La **moitié des PR mergées** de Pandanite sont
des rustines de locks/segfaults.
→ **Java** : modèle de concurrence **central et discipliné** — état de chaîne derrière un
**ordre de verrouillage unique** (ou acteur / `ReentrantReadWriteLock` cohérent + structures
immuables) ; ne jamais appeler la blockchain en tenant le verrou mempool ; jamais de getter
non synchronisé sur un état muté. Le GC élimine l'use-after-free, **pas** les data races.

### 4.10 [ROBUSTESSE/DoS] Entrées réseau non bornées
- `readRawBlocks` fait confiance à `numTransactions` **sans borne** → over-read tas / OOM
  déclenchable par un pair malveillant choisi comme `bestHost`.
- Handlers sans `return` après `res->end()` → double-end (UB) et **gardes de plage/débit
  inopérantes** (un `/sync?start=0&end=10^6` sert quand même tout → amplification).
- Division par zéro distante (`totalWork / (end-start)` sur des timestamps égaux → SIGFPE).
- Cache Pufferfish **non borné** + PoW coûteux calculé **avant** les vérifications bon marché
  → DoS CPU/disque.
- Handler `/block/0` qui ne répond pas → crash ; `stringToAddress` acceptant du hex invalide ;
  rate limiter accumulant les IP sans purge (fuite mémoire, issue #52).
→ **Java** : valider chaque longueur **avant** lecture ; toujours interrompre après avoir
répondu ; appliquer réellement plages et débit ; garder `denominator == 0` ; **PoW coûteux
en dernier**, après id/lastHash/difficulté/timestamp/signatures ; caches **bornés** (LRU) ;
tout handler HTTP répond toujours (400 plutôt que crash) ; validation stricte des adresses.

### 4.11 [ROBUSTESSE] Arrêt et cohérence du stockage
État LevelDB incohérent si le nœud est tué (issue #54) ; crash fatal si `genesis.json`
absent/vide ; sync sans bootstrap quasi impossible avec message trompeur invitant à
supprimer les données (issue #126, toujours ouverte).
→ **Java** : shutdown hook fermant la base proprement ; vérification d'intégrité au
démarrage ; création automatique des répertoires ; messages d'erreur clairs ;
**opération de repair/rescan** plutôt que conseil destructif ; persistance de la liste
de pairs (issue #31) ; sync résiliente (retries + backoff + rotation de pairs, reprise
après crash), parallèle (issues #32/#83/#91/#109).

### Exceptions historiques à reproduire *à l'identique* (uniquement pour rejouer le mainnet)
`invalid.json` (tx figées, blocs 515751→536000), `bannedHashes {143799,…}`, difficulté 27
imposée aux blocs 536100–536200, génésis à difficulté 16, décalages de la mining fee (§4.2).
À encoder comme données de consensus immuables — **sans** réintroduire les bugs pour le
nouveau code. Alternative plus propre : démarrer d'un **checkpoint** postérieur.

---

## 5. Plan de migration par phases

Principe directeur : **construire de bas en haut, chaque phase testable en isolation**,
en refermant les bugs C++ correspondants au passage. Objectif intermédiaire = un nœud
mono-instance qui mine et valide sa propre chaîne ; objectif final = interopérabilité
avec le réseau Pandanite.

### Phase 0 — Fondations (FAIT)
Compilation verte, suite de tests verte, dépendances stabilisées, dépendance circulaire
levée, ledger à arithmétique contrôlée. *Voir §3.*

### Phase 1 — Cœur crypto & PoW Pufferfish2 (FAIT)
1. **Pufferfish2 porté en Java pur** (`Pufferfish2`, `PufferfishAlgorithm.compute`) sur
   HMAC-SHA512 de BouncyCastle, **validé au bit près** contre des vecteurs d'or générés
   depuis le C de référence (`PufferfishTest`). C'est le PoW de la chaîne propre dès le genesis.
2. `Crypto.PUFFERFISH` produit bien `SHA256(pf_newhash(...))` ; `PufferfishConstants` corrigé.
- *Reste de Phase 1 à faire* : figer le **préimage du hash de bloc** en incluant `id` et le
  nombre de transactions (fix §4.5, pas de contrainte de compat puisque chaîne neuve) ;
  brancher `BlockImpl.verifyNonce` sur `NetworkParameters.powAlgorithm` (Pufferfish dès le
  genesis) au lieu du seuil `PUFFERFISH_START_BLOCK` ; compléter `RIPEMD160Hash.toBytes`,
  `BlockSerializer.deserialize`, `UserSerializer`, `Transaction.compareTo`.

### Phase 1b — Genesis amorcé par snapshot (FAIT pour la partie ledger)
1. **`NetworkParameters`** : config de la chaîne propre (chain-id, PoW Pufferfish2,
   récompense entière déterministe, params difficulté). Fabriques `cleanMainnet`/`testnet`.
2. **`LedgerSnapshot`** + **`GenesisLedger.seed`** : format address→solde (uint64 non signé,
   JSON) et amorçage storage-agnostique d'un ledger.
3. **`PandaniteLedgerDumper`** : lit le LevelDB ledger C++ (clé 25o → montant uint64 LE),
   exclut les wallets `invalid.json`, émet un snapshot (+ CLI `main`).
- *Reste à faire* : construire le **bloc genesis** lui-même (en-tête déterministe depuis
  `NetworkParameters` + engagement du snapshot), et l'`initChain` qui applique le genesis.
- **Pour produire le vrai snapshot** : lancer `PandaniteLedgerDumper <data/ledger> out.json
  invalid.json <hauteur> 1` sur un nœud Pandanite synchronisé.

### Phase 2 — Executor & règles de transaction *(cœur du consensus)*
1. **`Executor`** : valider et exécuter une transaction contre le ledger — solde suffisant
   (rejet strict, §4.1), frais, unicité (hash de contenu sans signature, §4.6), signature.
2. **Récompense de minage** déterministe (§4.2) — table entière + tests aux frontières de halving.
3. **Application/rollback d'un bloc transactionnels** (snapshot + rollback atomique, §4.11),
   ordre frais-puis-montant.
4. Nonce de compte + chain-id dans le préimage signé (§4.6) — *décision : rupture de compat
   assumée pour une nouvelle chaîne, ou conservation du format Pandanite pour l'interop.*
- **Sortie testable** : exécuter un bloc de N transactions, vérifier les soldes, annuler, revérifier.

### Phase 3 — Moteur blockchain *(assemblage)*
1. **Bloc genesis** (difficulté 16, soldes initiaux).
2. **`addBlockSync`** : validation complète et ordonnée — `lastBlockHash`/`id` dès le bloc 1
   (§4.8), timestamps bornés (§4.7), difficulté attendue recalculée (§4.3), Merkle sur copie
   (§4.4), PoW, puis exécution via l'Executor.
3. **Ajustement de difficulté** pur et déterministe, recalculé après add ET pop (§4.3).
4. **`popBlock` / reorg** propre ; choix de la meilleure chaîne par **travail cumulé total**.
5. **Checkpoints** + exceptions historiques (§4, encart) si interop mainnet visée.
6. **Concurrence** : un unique moniteur d'état de chaîne, ordre de verrouillage global (§4.9).
- **Sortie testable** : nœud mono-instance qui construit une chaîne, gère un fork simulé à
  deux chaînes, choisit la bonne, et rejette blocs/tx invalides.

### Phase 4 — Mempool
`MemPool` réel (interface aujourd'hui vide) : admission avec validation du **solde cumulé**
(toutes tx en attente comprises, cf. PR #13), compteurs `mempoolOutgoing` contrôlés (§4, 2.4),
limite anti-DoS (≤ N tx, issue #48), `finishBlock` sans deadlock (§4.9).
- **Sortie testable** : soumettre des tx concurrentes, en inclure dans un bloc, vérifier la purge.

### Phase 5 — Serveur HTTP API du nœud (app-node)
Porter les ~15 endpoints de `server.cpp`/`api.cpp` (`/block_count`, `/sync`, `/block`,
`/submit`, `/add_transaction`, `/tx_json`, `/mine`, `/total_work`, `/peers`, `/stats`…) sur
ActiveJ HTTP 6.0. **Impérativement** avec les correctifs robustesse §4.10 (bornes, `return`
après réponse, division protégée, PoW en dernier, rate limiter borné, tout handler répond).
Le client `PeerInterface` existe déjà pour le versant sortant.
- **Sortie testable** : deux nœuds locaux se synchronisent via HTTP.

### Phase 6 — Synchronisation & P2P (lib-net + host manager)
1. **Sync de chaîne résiliente** : téléchargement parallèle d'en-têtes puis de blocs, cache
   disque, retries + backoff + rotation de pairs, reprise après crash, jamais de conseil
   destructif (issue #126).
2. **`header_chain`** (chaînes virtuelles) pour comparer les chaînes candidates avant de
   télécharger les blocs entiers.
3. **Host manager** : persistance de la liste de pairs (issue #31), ping/rediscovery,
   `broadcast`/`sync` (aujourd'hui commentés), `currPeers.clear()` correct, structures
   protégées (§4.9). Découverte d'IP publique avec fallback (issue #33).
4. Finaliser ou remplacer la couche transport (les forks RPC ActiveJ-5 ont été retirés) :
   **recommandation — s'appuyer sur HTTP comme Pandanite** pour l'interop plutôt que sur RPC ActiveJ.
- **Sortie testable** : un nœud neuf se synchronise depuis un pair sur plusieurs milliers de blocs.

### Phase 7 — Mineur & Wallet (app-wallet, tooling)
- **Mineur** : boucle de minage (SHA256/Pufferfish), tolérance aux timeouts et blocs périmés
  sans crash, calcul du hashrate réseau, minage vers une adresse **sans clé privée chargée**
  (PR #39).
- **Wallet / CLI** : `keygen`, `tx`, consultation de solde, envoi — équivalents de `cli.cpp`,
  `keygen.cpp`, `tx.cpp`.
- **Sortie testable** : générer une clé, miner un bloc, envoyer une tx, la voir confirmée.

### Phase 8 — Durcissement & interopérabilité
Tests d'intégration multi-nœuds, fuzzing des entrées réseau, vérification de bout en bout
contre un nœud Pandanite réel (mêmes hashes de blocs, mêmes soldes) si l'interop est retenue,
shutdown/repair propres (§4.11), observabilité (logs de progression de sync).

---

## 6. Décisions

**Tranchées (voir §0) :** nouvelle chaîne propre ; genesis amorcé par snapshot ; soldes
assainis ; PoW **Pufferfish2** (porté en Java pur, §Phase 1) ; snapshot via outil de dump.

**Restant à trancher :**
1. **Nonce de compte** : ajouter un compteur par compte dans le préimage signé (en plus du
   chain-id déjà porté dans `NetworkParameters`) pour l'anti-rejeu robuste (§4.6). Recommandé.
2. **Transport P2P** : HTTP (parité Pandanite, plus simple) vs ActiveJ RPC (les forks retirés
   étaient non fonctionnels). Recommandation : **HTTP**.
3. **`lib-crypto`** vide : le remplir (extraire le crypto de lib-core) ou le retirer de
   `settings.gradle`.
4. **Hauteur du snapshot** : à quelle hauteur de la chaîne Pandanite figer les soldes.

---

## 7. Références

- Bugs consensus historiques : issues Pandanite #2 (lastBlockHash), #19/#22 (timestamp),
  #29 (Merkle), #37 (malléabilité ED25519), #126 (sync, ouverte), #52 (fuite rate limiter).
- Incidents fossilisés : `pandanite/invalid.json`, `blacklist.txt`, `config.cpp` `bannedHashes`,
  hack difficulté `blockchain.cpp` (blocs 536100–536200).
- Fichiers C++ clés : `server/{blockchain,executor,mempool,ledger,server}.cpp`,
  `core/{block,transaction,merkle_tree,crypto}.cpp`, `server/host_manager.cpp`.
