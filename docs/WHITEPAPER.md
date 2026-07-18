# Rhizome — Livre blanc technique

> Version 0.9 (alpha) · Port Java de Pandanite · Chaîne propre amorcée par snapshot

## Résumé

Rhizome est une blockchain à preuve de travail, réécrite en Java à partir de
[Pandanite](https://github.com/pandanite-crypto/pandanite) (C++). Plutôt que de
maintenir une base traînant des bugs de consensus fossilisés, Rhizome démarre une
**chaîne propre** — nouveau genesis, règles corrigées — dont l'**état initial est un
snapshot assaini des soldes** de la chaîne Pandanite existante. Les détenteurs
conservent leur solde ; le réseau repart sur des règles saines.

Trois objectifs guident la conception :

1. **Correction** — chaque bug de consensus connu du Pandanite C++ est corrigé
   d'emblée (§3), et la validation est ordonnée pour rendre le déni de service coûteux.
2. **Performance** — la cible est **un bloc par seconde** validé par le réseau, avec
   une pile taillée pour le débit (§6).
3. **Preuve de travail Pufferfish2** — dès le genesis, sans phase SHA-256 ni bascule
   d'algorithme, portée en Java pur et validée bit-à-bit contre une référence C.

À ce jour le cœur de nœud est fonctionnel et couvert par **166 tests** : consensus,
exécution, stockage, mempool, API, production de blocs, synchronisation P2P avec
réorganisation, et wallet.

---

## 1. Motivation

Pandanite est une blockchain PoW minimale et lisible, mais son histoire a figé
plusieurs défauts au niveau du consensus : arithmétique `uint64` non vérifiée
provoquant de l'inflation (`invalid.json`), récompense de minage en virgule flottante
divergente entre implémentations, difficulté non déterministe et « rustinée » par des
exceptions codées en dur, validation du chaînage absente sur les premiers milliers de
blocs, malléabilité des signatures Ed25519, entrées réseau non bornées, et un
verrouillage incohérent entre mempool et blockchain. Reproduire fidèlement cette
chaîne impliquerait de reproduire ces bugs.

Rhizome fait le choix inverse : **repartir d'un genesis neuf**. Comme l'histoire n'a
pas à être rejouée, aucun de ces défauts n'a à être reproduit. Le seul lien avec
l'existant est économique : les **soldes** sont importés depuis un snapshot, assaini
des wallets issus des incidents d'inflation.

---

## 2. Vue d'ensemble de l'architecture

Le nœud est assemblé par constructeurs explicites (pas de conteneur d'injection par
réflexion), ce qui garde le graphe de dépendances lisible et compatible avec la
compilation native GraalVM.

```
                +------------------ app-node ------------------+
   HTTP  <----> |  NodeApi (rate-limit + tailles bornées)      |
                |  BlockProducer   PeerBroadcaster/Discovery    |
                |  ChainSynchronizer (sync périodique + reorg)  |
                +----------------------|------------------------+
                                       v
         +-------------------------- lib-core -------------------------+
         |  ChainEngine  (addBlock/popBlock, nonces, travail, diff.)   |
         |  Executor     (application transactionnelle au ledger)       |
         |  MemPool      (admission, sélection par nonce, équité)       |
         |  Pufferfish2  (PoW)   DifficultyAdjustment   MerkleTree      |
         +-----------------------------|------------------------------+
                                       v
         +----------------------- lib-persistence --------------------+
         |  RocksDbNodeStore : ChainStore + Ledger + txdb (1 base,     |
         |  familles de colonnes, WriteBatch atomique append/pop)      |
         +------------------------------------------------------------+
```

Modules : `lib-core`, `lib-persistence`, `lib-net`, `lib-crypto` (réservé),
`app-node`, `app-wallet`, `app-dnsseeder`.

---

## 3. Modèle de consensus

### 3.1 Preimage du hash de bloc

Le hash d'un bloc s'engage sur l'intégralité de son en-tête :

```
hash = H( merkleRoot || lastBlockHash || id || difficulty || numTransactions || timestamp )
```

(entiers en big-endian). Contrairement au Pandanite C++, **le choix de l'algorithme de
PoW et tous les champs de l'en-tête sont dans le préimage** : un bloc réordonné ou
retimestampé produit un hash différent, donc une PoW invalide.

### 3.2 Preuve de travail — Pufferfish2

Pufferfish2 est une fonction de dérivation mémoire-dure (`$PF2$`, `cost_t=0`,
`cost_m=8`, sel entièrement nul → déterministe), portée en Java pur sur HMAC-SHA512
(BouncyCastle) et **validée bit-à-bit** contre une référence C via des vecteurs
« golden ». `verifyNonce` recalcule le hash PoW et exige `difficulty` bits de tête à
zéro. La difficulté étant bornée à 255, un bloc prétendant une difficulté absurde est
rejeté en temps constant sans allocation coûteuse.

### 3.3 Ajustement de difficulté

La difficulté est **recalculée déterministe­ment à partir de l'historique** des
timestamps : difficulté genesis, avancée d'un pas par fenêtre de retarget complète
(60 blocs en mainnet). Étant dérivée (jamais un champ mis en cache qui se périme après
un `popBlock`), elle ne peut pas devenir incohérente lors d'un reorg — le défaut
derrière les exceptions codées en dur de Pandanite. Le pas est **borné et clampé**,
ce qui limite l'effet d'une manipulation des timestamps.

### 3.4 Cadence : un bloc par seconde imposé par le réseau

Le producteur cadence localement sa boucle, mais la **règle de consensus**
`minBlockTimeSec` — un bloc doit être au moins `minBlockTime` après son parent — est
vérifiée par **tous** les nœuds. En mainnet `minBlockTime = desiredBlockTime = 1 s` :
le plancher de consensus est aussi le métronome. Un nœud modifié ne peut pas inonder
le réseau de milliers de blocs valides pour « voler » la chaîne, puisque tout bloc
trop rapproché de son parent est rejeté par le réseau entier (majorité minière
comprise). La borne futur (`maxFutureBlockTime = 15 s`) limite le nombre de blocs
minables « en avance ». Détail dans [`BLOCK_RATE_SECURITY.md`](BLOCK_RATE_SECURITY.md).

### 3.5 Ordre de validation de `addBlock`

Du moins cher au plus cher, la PoW en dernier pour qu'un bloc invalide ne brûle pas de
CPU (leçon DoS de Pandanite) :

1. continuité de l'`id`, nombre de transactions non vide et ≤ max ;
2. checkpoint (si la hauteur est épinglée, seul le hash publié passe) ;
3. `lastBlockHash` chaîne au tip (vérifié dès le bloc 2 — le bug historique #2) ;
4. timestamp > temps médian passé, ≥ parent + `minBlockTime`, ≤ horloge + borne futur ;
5. difficulté = valeur recalculée depuis l'historique ;
6. racine de Merkle = transactions du bloc ;
7. nonces de compte strictement séquentiels par expéditeur ;
8. preuve de travail ;
9. l'`Executor` applique les transactions de façon transactionnelle.

Tous les accès publics du moteur sont sérialisés sur un unique verrou : un seul
écrivain à la fois, des lectures cohérentes (les getters non verrouillés de Pandanite
produisaient des lectures déchirées de son travail cumulé `BigInt`).

### 3.6 Arbre de Merkle

L'arbre **préserve l'ordre d'insertion** des transactions : la racine s'engage sur
l'*ordre*, pas seulement l'*ensemble*. Un tri (comme dans le C++) ferait partager la
même racine — donc le même hash de bloc et la même PoW — à `[t0,t1]` et `[t1,t0]`,
alors que la validation des nonces dépend de l'ordre : un même hash pourrait être
accepté ou rejeté selon l'ordre reçu, un split de consensus. La duplication de la
dernière transaction (CVE-2012-2459) reste neutralisée par la déduplication par
content-hash de l'Executor.

### 3.7 Choix de chaîne et finalité

Le choix de fork est objectif : on adopte la chaîne d'un pair **seulement si son
travail cumulé est strictement supérieur** (2^difficulté par bloc, sommé en
`BigInteger`). Pandanite forkait à répétition faute d'une telle règle.

La synchronisation est durcie contre les pairs hostiles :

- **Fenêtre de finalité** — un reorg plus profond que `maxReorgDepth` (600 blocs) est
  refusé d'office : l'histoire enfouie n'est pas réécrivable, quel que soit le travail
  prétendu.
- **Aucun rollback gratuit** — avant toute mutation locale, un préfixe borné de la
  branche du pair est téléchargé et **validé sans état** (continuité des id, chaînage
  des hash depuis le point de fork, PoW de chaque bloc, et travail vérifié
  strictement supérieur au nôtre). Un pair qui *prétend* un travail énorme ne coûte
  donc qu'un téléchargement borné, jamais un cycle pop/restore.
- **Restauration sur échec** — si l'application avec état échoue malgré tout, la chaîne
  locale est restaurée à l'identique.

---

## 4. Modèle de transaction et exécution

### 4.1 Transaction

Une transaction s'engage, dans son préimage signé, sur le `chainId` **et** un **nonce
de compte** par expéditeur. Le `chainId` empêche le rejeu entre réseaux ; le nonce
séquentiel empêche le rejeu et l'ambiguïté d'ordonnancement au sein d'un même réseau.
L'identité d'une transaction pour la déduplication est son **content-hash sans
signature** — immunisé contre la malléabilité Ed25519.

Les montants et frais sont des entiers (unités de base, échelle 10 000 → « PDN »).
Ils sont conceptuellement **non signés** : tout montant ou frais négatif est rejeté,
car un retrait négatif créerait de la monnaie pour l'expéditeur et un dépôt négatif
rendrait le solde du destinataire négatif.

### 4.2 Exécution transactionnelle

L'`Executor` valide puis applique les transactions d'un bloc :

1. **Passe structurelle** (aucun état touché) : exactement une coinbase dont le montant
   égale la récompense attendue à cette hauteur (entier, jamais de comparaison
   flottante) ; chaque autre transaction cible ce réseau, a une signature valide dont
   la clé correspond à l'adresse expéditrice, n'est pas un doublon (dans le bloc ou
   déjà exécutée), et a montant/frais ≥ 0.
2. **Passe applicative** (transactionnelle) : les soldes passent par un ledger à
   **arithmétique vérifiée**. Un solde insuffisant (underflow) et un dépassement de
   capacité 64 bits (overflow) sont tous deux rejetés proprement avec **rollback
   complet** — l'arithmétique `uint64` non vérifiée du C++ est ce qui a gonflé les
   soldes lors de l'incident `invalid.json`.

La conservation de la monnaie est garantie : chaque transaction est débitée puis
créditée, et la coinbase est fixée à la récompense de la hauteur.

### 4.3 Économie

Récompense initiale 50 PDN, décroissance ×2/3 par époque de 666 666 blocs (entièrement
en arithmétique entière). L'émission est donc bornée et déterministe.

---

## 5. Couche réseau

Transport **HTTP** (parité Pandanite, plus simple à raisonner et compatible native),
la synchronisation tournant sur son propre thread en I/O bloquante hors de
l'eventloop.

- **Gossip actif** — les blocs et transactions acceptés sont re-diffusés aux pairs ;
  les boucles se terminent car un pair qui possède déjà un objet le rejette.
- **Découverte de pairs (PEX)** — chaque nœud sert `/peers`, accepte des annonces, et
  interroge périodiquement ses pairs, si bien que le réseau s'auto-organise depuis
  quelques graines. Les pairs injoignables de façon répétée sont élagués.
- **Bannissement par ban-score** — un pair accumule des points pour violation de
  protocole ; au-delà d'un seuil il est banni pour une fenêtre (score décroissant dans
  le temps). La clé est l'**hôte** (un pair ne peut pas contourner le ban en changeant
  de port/chemin). Servir une chaîne invalide bannit au premier coup ; un reorg
  trop-profond ou un mauvais réseau coûtent moins. Le registre de pairs est l'unique
  point d'admission : un banni ne peut être réintroduit ni par config, ni par
  `/add_peer`, ni par PEX.
- **Limitation de débit et de taille** — chaque client est limité par fenêtre fixe
  (table de clients bornée, correction de la fuite mémoire Pandanite #52), chaque body
  POST est plafonné, et le client de sync **borne la taille des réponses** d'un pair
  (le parse `BigInteger` d'un `/total_work` géant serait un DoS CPU en O(n²)).

---

## 6. Performance (cible 1 bloc/s)

- **Codec binaire à disposition fixe** — sérialisation manuelle en `ByteBuffer` big-endian
  (remplace la génération de code à l'exécution), déterministe et compatible native.
- **Stockage RocksDB** — une base unique à familles de colonnes (chaîne, ledger, txdb) ;
  append et pop sont des `WriteBatch` atomiques, si bien qu'un arrêt ne laisse jamais
  d'état à moitié écrit.
- **Vérification de signatures parallèle + cache** — les signatures sont vérifiées une
  fois à l'admission mempool, mises en cache, et l'exécution de bloc obtient un
  cache-hit ; les lots sont vérifiés en parallèle (mesuré ~140 blocs/s à cache chaud
  contre ~6,6 en séquentiel).

Le détail des arbitrages (RocksDB vs LevelDB, sérialisation, RPC, GraalVM) est dans
[`PERFORMANCE_STACK.md`](PERFORMANCE_STACK.md).

---

## 7. Modèle de sécurité

La revue complète — vecteurs couverts, bugs trouvés et corrigés, risques résiduels
traités ou décidés — est dans [`SECURITY_REVIEW.md`](SECURITY_REVIEW.md). En synthèse,
sont défendus : double-dépense (dans le bloc / déjà exécutée), malléabilité de
signature, usurpation d'expéditeur, rejeu inter-chaînes et de nonce, récompense
gonflée, **montants négatifs** (création de monnaie), **overflow de solde**,
manipulation d'horodatage et de difficulté, reorg profond (finalité), pair menteur
(travail prétendu non prouvé), flood de blocs/requêtes, et DoS réseau (bodies bornés,
ban-score). Un plafond mempool **par expéditeur** assure l'équité entre comptes.

La maturité de coinbase (concept UTXO) n'est pas applicable au ledger par soldes ; le
risque de dépense d'une récompense orpheline est couvert par la fenêtre de finalité.

---

## 8. Amorçage par snapshot

L'état genesis est un snapshot des soldes de la chaîne Pandanite, **assaini** (wallets
issus des incidents d'inflation exclus). Le `genesisCommitment` hashe
`chainId || snapshotCommitment`, de sorte que deux réseaux différents ne partagent
jamais le même genesis même à snapshot vide. La production du snapshot réel se fait via
un outil de dump lisant le ledger LevelDB d'un nœud Pandanite synchronisé.

---

## 9. État & feuille de route

**Fait** — crypto & Pufferfish2 ; modèle de consensus & Executor ; moteur de chaîne
(`addBlock`/`popBlock`, nonces, travail, difficulté) ; stockage RocksDB ; mempool ;
API HTTP ; production de blocs ; synchronisation + reorg par travail cumulé ; wallet
CLI ; gossip & découverte de pairs ; durcissement (checkpoints, finalité, rate
limiting borné, ban-score) ; revue de sécurité. **166 tests, 0 échec.**

**Dépendant de l'environnement** — build natif GraalVM (`native-image` non installé
dans l'environnement de développement actuel) ; production du snapshot Pandanite réel
(nœud C++ synchronisé requis) ; testnet public multi-nœuds.

**Ultérieur** — contrats intelligents, cas d'usage DeFi, protocoles cross-chain et
bridge.

---

## 10. Références

- Plan de migration & audit détaillé des bugs C++ : [`../MIGRATION_PLAN.md`](../MIGRATION_PLAN.md)
- Revue de sécurité : [`SECURITY_REVIEW.md`](SECURITY_REVIEW.md)
- Sécurité de la cadence : [`BLOCK_RATE_SECURITY.md`](BLOCK_RATE_SECURITY.md),
  [`BLOCK_CADENCE.md`](BLOCK_CADENCE.md)
- Pile de performance : [`PERFORMANCE_STACK.md`](PERFORMANCE_STACK.md)
- Origine : [Pandanite (C++)](https://github.com/pandanite-crypto/pandanite)
