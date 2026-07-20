# Analyse d'Ergo — quelles features intégrer dans Rhizome ?

> Analyse comparative du client de référence Ergo (`ergoplatform/ergo`, Scala) et de
> Rhizome, avec des recommandations d'intégration concrètes. Motivation initiale : le
> système de **boxes** d'Ergo comme support de stockage d'informations pour des agents
> IA autonomes. Juillet 2026.

## TL;DR

| # | Feature Ergo | Verdict | Valeur pour les agents | Effort |
|---|---|---|---|---|
| 1 | **Boxes + registres de données (R4–R9)** | ✅ À adapter (pas de bascule eUTXO : couche « data box » au-dessus du modèle à comptes) | ⭐⭐⭐⭐⭐ | Moyen |
| 2 | **Storage rent (démurrage)** | ✅ À prendre quasi tel quel, appliqué aux boxes | ⭐⭐⭐⭐ | Faible–moyen |
| 3 | **Data inputs (lecture sans consommation)** | ✅ À adapter (host function `box_read` + lectures déclarées) | ⭐⭐⭐⭐⭐ | Faible |
| 4 | **API `/scan` (prédicats d'indexation à chaud)** | ✅ À adapter | ⭐⭐⭐⭐⭐ | Moyen |
| 5 | **Tokens natifs (id = id de box, métadonnées EIP-4)** | ✅ À considérer sérieusement (cohérent avec « cheap token launches ») | ⭐⭐⭐ | Moyen |
| 6 | **minValuePerByte (anti-dust)** | ✅ À prendre | ⭐⭐ | Trivial |
| 7 | **State root authentifié (AVL+) + ADProofs** | 🕐 Plus tard, mais à préparer dès maintenant (réserver le champ dans le header) | ⭐⭐⭐ | Élevé |
| 8 | **Section Extension + vote des mineurs sur les paramètres** | 🕐 Plus tard | ⭐⭐ | Moyen |
| 9 | **Snap-sync UTXO / NiPoPoW** | 🕐 Beaucoup plus tard | ⭐ | Élevé |
| 10 | **DeliveryTracker (machine à états P2P + cache Bloom)** | ✅ Patterns à copier au fil de l'eau | ⭐ | Faible |
| — | ErgoScript / modèle eUTXO complet / Autolykos / EIP-27 | ❌ À ignorer | — | — |

La recommandation centrale : **ne pas migrer Rhizome vers l'eUTXO**, mais ajouter une
**couche de « data boxes » native** (objets d'état de première classe, avec registres,
rente de stockage et lectures déclarées) à côté du ledger à comptes et des contrats
WASM. C'est le sous-ensemble d'Ergo qui sert directement le cas d'usage agents, sans
le coût d'une refonte du modèle de ledger.

---

## 1. Où en est Rhizome (état des lieux)

Rappel factuel, pour cadrer ce qui manque (chemins relatifs au repo) :

- **Modèle à comptes**, pas UTXO : `Ledger` = map adresse→solde
  (`lib-core/.../ledger/Ledger.java`), nonces séquentiels par compte
  (`ChainEngine.java:61`).
- **Contrats WASM** (Chicory) avec stockage clé/valeur **non typé** par contrat
  (`lib-vm/.../ContractStore.java`, column families `contract_code` /
  `contract_storage` dans `RocksDbContractStore.java`), gas par instruction +
  coûts par host call (`GasSchedule.java`), journaux d'annulation par bloc pour
  les reorgs (`WasmContractProcessor.java`).
- **Un transfert ne peut porter aucune donnée** : le champ `data` de
  `TransactionImpl` n'est sérialisé et signé que pour les kinds contrat
  (`TransactionDto.java:110`, `hashContents:135`). Stocker de la donnée on-chain
  aujourd'hui = déployer un contrat et payer des `storage_write`, ou émettre des
  logs (`emit_log` → `/logs`, SSE).
- **Aucune API de lecture d'état contractuel** : `NodeApi.java` n'expose ni
  lecture de `contract_storage`, ni dry-run. Le seul canal d'observabilité est
  `/logs` + `/logs/stream`.
- **Aucun engagement d'état dans le header** : le hash de bloc ne commit que le
  merkle root des transactions (`BlockImpl.hash():78`). Pas de state root, donc
  pas de preuve possible d'existence d'une donnée pour un client léger.
- **Aucune rente de stockage** : un `storage_write` coûte du gas une fois, puis
  occupe l'état pour toujours.

Chaque feature Ergo ci-dessous est donc un **ajout net**, pas l'extension d'un
équivalent existant. Les points d'insertion sont identifiés en §4.

---

## 2. Le système de boxes d'Ergo, en détail

C'est la feature qui a motivé l'analyse ; en voici la mécanique exacte telle
qu'implémentée (classes de base dans la dépendance sigma v6.0.3, validation dans
`ergo-core/.../mempool/ErgoTransaction.scala`, état dans
`src/.../nodeView/state/ErgoState.scala`).

### 2.1 Structure d'une box

Une box est un UTXO enrichi, **immuable**, identifié par `Blake2b256(bytes)` :

| Registre | Contenu | Obligatoire |
|---|---|---|
| R0 | `value` (nanoERG) | oui |
| R1 | `ergoTree` — le script qui garde la box | oui |
| R2 | tokens : liste de `(tokenId 32B, montant)`, max **255** | oui |
| R3 | référence de création : `(txId, index, creationHeight)` | oui |
| **R4–R9** | **6 registres libres, typés** | non (remplissage dense, pas de trou) |

Les registres libres portent des valeurs **typées** du système sigma : entiers,
booléens, `Coll[Byte]` (le plus courant : hashes, chaînes, blobs sérialisés),
points de courbe elliptique (clés publiques), sigma-propositions, collections
imbriquées, tuples. Limites dures : **box entière ≤ 4096 octets** (y compris le
script, lui-même ≤ 4096), pas de plafond par registre.

### 2.2 Ce qui rend le modèle intéressant pour du stockage de données

1. **La donnée est un objet d'état de première classe**, adressable par id,
   indexable, prouvable — pas une entrée anonyme dans le K/V d'un contrat.
2. **Data inputs** : une transaction liste trois ensembles — `inputs`
   (consommées, preuve exigée), **`dataInputs` (lues sans être consommées, aucune
   preuve, coût forfaitaire ~100)**, `outputs`. N transactions du même bloc
   peuvent lire la même box sans se marcher dessus : c'est le mécanisme des
   oracles Ergo (une box singleton, identifiée par un NFT, R4 = dernière valeur ;
   les consommateurs la référencent en data input).
3. **Cellules auto-répliquantes** : le script d'une box peut exiger que toute
   dépense recrée une box au même script avec une transition de registres
   validée. On obtient une cellule de données mutable *gouvernée par contrat*
   (oracle, registre d'agents, config). L'interpréteur (`ErgoInterpreter.scala:52`)
   a même une règle de continuité : seuls R0 (valeur) et R3 (référence) peuvent
   changer par défaut, R4–R9 et le script doivent être préservés.
4. **La rente de stockage est triviale à facturer** parce que chaque box porte sa
   taille et sa hauteur de création (voir §3.1) — c'est un argument structurel
   fort en faveur d'un objet « box » par rapport au K/V diffus d'un contrat.
5. **Indexation et scan** : le wallet indexe n'importe quelle box à partir de
   prédicats déclaratifs enregistrés à chaud (§3.3), et l'indexeur optionnel
   sert boxes/soldes par adresse et par token.

### 2.3 Tokens natifs

L'id d'un nouveau token = l'id de la **première input** de la transaction
d'émission (`ErgoTransaction.scala:190`) — unicité globale gratuite, pas de
registre central. Règle de conservation : par token, `sortie ≤ entrée`, sauf
l'unique id nouvellement autorisé ; brûler = sortir moins. Métadonnées par
convention EIP-4 dans les registres de la box d'émission : R4 = nom, R5 =
description, R6 = décimales ; un NFT = un token de supply 1. Coût d'accès par
token (`tokenAccessCost`) pour borner la validation.

---

## 3. Les autres mécanismes d'Ergo qui valent le détour

### 3.1 Storage rent (démurrage) — le complément indispensable des boxes

Mécanique exacte (`ergo-wallet/.../ErgoInterpreter.scala:42-88`,
`Constants.scala:17-23`) :

- Une box âgée de **≥ 1 051 200 blocs (~4 ans)** devient « expirée ».
- N'importe qui (en pratique le mineur) peut alors la dépenser **sans satisfaire
  son script** : preuve vide + une variable de contexte (id 127) pointant vers la
  box recréée en sortie.
- La charge = `storageFeeFactor × taille_en_octets` (défaut 1 250 000 nanoERG/octet
  par période, **paramètre votable par les mineurs**). La box doit être recréée à
  l'identique — script, tokens, R4–R9 préservés ; seuls la valeur (diminuée d'au
  plus la rente) et la hauteur de création (remise à « maintenant ») changent.
- Si `value ≤ rente`, le mineur prend tout et la box disparaît : **le dust et
  l'état abandonné sont garbage-collectés économiquement**, avec un revenu mineur
  qui survit à la fin de l'émission.

Pour une chaîne qui invite des agents IA à écrire des données on-chain, c'est la
différence entre un état borné et une décharge à croissance monotone.

### 3.2 Anti-dust : `minValuePerByte`

Toute sortie doit satisfaire `value ≥ taille_sérialisée × minValuePerByte`
(défaut 360 nanoERG/octet, votable). Écrire gros coûte proportionnellement à
l'occupation d'état, dès la création. Trivial à implémenter, gros effet.

### 3.3 API `/scan` : le nœud comme indexeur programmable (EIP-1)

Une application enregistre à chaud un **prédicat déclaratif** —
`ContainsAsset(tokenId)`, `Equals(R_n, valeur)`, `Contains(R_n, octets)`,
combinés par And/Or (`ScanningPredicate.scala`) — et le nœud suit toutes les
boxes correspondantes : `POST /scan/register`, `GET /scan/unspentBoxes/{id}`,
etc. (`ScanApiRoute.scala`). C'est l'infrastructure qui fait tourner les oracles
et les bots off-chain d'Ergo **sans code spécifique dans le nœud**. Pour des
agents Rhizome, c'est le pendant « état » du `/logs/stream` existant : « préviens-moi
de toute box portant tel token / tel registre ».

### 3.4 État authentifié : arbre AVL+, state root, ADProofs

Tout l'ensemble UTXO vit dans un **arbre AVL+ authentifié** (module `avldb/`,
clés = box ids, valeurs = boxes sérialisées). Le header commit le **digest de
33 octets** (racine + hauteur d'arbre) dans `stateRoot`, plus le hash d'une
**preuve de modification par lot (ADProofs)**. Conséquences :

- un client peut vérifier l'inclusion d'une box (donc une donnée d'agent) avec
  une preuve logarithmique, sans faire confiance au nœud ;
- un nœud « digest » valide entièrement la chaîne avec un état O(1)
  (`DigestState.scala`) ;
- le snap-sync devient trustless : le manifest du snapshot est lié au
  `stateRoot` déjà validé via la chaîne de headers
  (`ErgoNodeViewSynchronizer.scala:928-949`, snapshot tous les 52 224 blocs).

Rhizome n'a aujourd'hui **aucun** engagement d'état dans le header — c'est le
plus gros écart structurel, et le plus coûteux à combler.

### 3.5 Section Extension + vote des mineurs

Chaque bloc porte une petite section K/V merkle-committée (clés 2 octets,
valeurs ≤ 64 octets, ≤ 32 Ko) : paramètres du protocole réécrits à chaque époque
de vote, interlinks NiPoPoW, flags de soft-fork (`Extension.scala`). Les headers
portent 3 octets de votes ; une majorité simple sur une époque de 1024 blocs
ajuste un paramètre d'un pas borné (`Parameters.scala:158-183`), 90 % sur 32
époques active un soft-fork. C'est ce qui rend `storageFeeFactor`,
`maxBlockCost`, etc. ajustables **sans hard fork**.

### 3.6 Divers à garder en tête

- **NiPoPoW** : bootstrap des headers en temps polylog — pertinent seulement
  quand la chaîne sera longue et qu'il y aura des clients légers.
- **DeliveryTracker** (`scorex/core/network/DeliveryTracker.scala`) : machine à
  états `Unknown → Requested → Received → Held` par modifier, timeouts de
  re-requête, cache Bloom expirant des ids invalides — anti-DoS mémoire-borné,
  patterns copiables dans `lib-net` indépendamment du reste.
- **Coût par transaction décomposé** : coût forfaitaire par input (2000), data
  input (100), output (100), accès token (100), budget de bloc partagé
  (`maxBlockCost`) — un modèle de tarification plus fin que le gas uniforme,
  dont Rhizome pourra s'inspirer pour tarifer les opérations sur boxes.

### 3.7 Ce qu'on n'importe pas, et pourquoi

- **Le modèle eUTXO complet** : basculer le ledger de Rhizome casserait le VM
  WASM (état contractuel mutable), le mempool par nonce, le snapshot Pandanite,
  et 222 tests, pour un bénéfice marginal par rapport à la couche hybride
  proposée en §4.
- **ErgoScript / sigma-protocols** : redondant avec les contrats WASM ; la
  puissance des boxes est récupérable sans leur langage de garde.
- **Autolykos** : Pufferfish2 est un choix assumé de Rhizome.
- **EIP-27 / ré-émission, founders box** : spécifique à l'histoire monétaire
  d'Ergo.

---

## 4. Proposition d'intégration : des « data boxes » dans Rhizome

> **Statut : implémenté.** La proposition ci-dessous a été réalisée (phase 1 +
> rente) et la conception de référence vit désormais dans
> [`WHITEPAPER.md`](../WHITEPAPER.md) §5.5. Cette section est conservée comme
> trace de l'analyse qui y a mené.

Design cible : un **objet box natif** à côté des comptes et des contrats — le
sous-ensemble d'Ergo utile aux agents, sans changer le modèle de ledger.

### 4.1 L'objet `Box`

```
Box {
  id            : 32B   // SHA256(bytes sérialisés) — dérivé, immuable
  owner         : union { adresse (25B) | contrat (25B) }
  value         : long  // PDN verrouillés, ≥ taille × minValuePerByte
  creationHeight: int
  registers     : R0..R5 — 6 slots byte[] avec tag de type minimal
                  (BYTES / INT / LONG / ADDRESS / HASH), remplissage dense
  // taille totale sérialisée ≤ 64 KiB (le plafond de 4 Ko d'Ergo borne la
  // taille des preuves AVL+ et des contextes de script — contraintes qui ne
  // s'appliquent pas ici, cf. WHITEPAPER.md §5.5)
}
```

Simplifications assumées par rapport à Ergo : pas d'ergoTree (la garde est soit
une signature du propriétaire, soit l'approbation du contrat contrôleur — la
logique « auto-répliquante » d'Ergo devient simplement *une box possédée par un
contrat*, dont le WASM valide les transitions de registres) ; typage réduit à
quelques tags plutôt que le système sigma complet ; tokens absents de la v1
(réintroduits si la feature 5 est retenue).

### 4.2 Cycle de vie — nouveaux `TransactionKind`

- `BOX_CREATE` : crée une box (data = registres), verrouille `value` ≥
  `taille × minValuePerByte`. Coût gas proportionnel à la taille.
- `BOX_UPDATE` : remplace le contenu (nouvelle box, même id logique ou nouvel id
  — à trancher ; le plus simple, fidèle à Ergo : nouvel id, l'ancienne box est
  consommée). Autorisé par signature du owner, ou par le contrat contrôleur.
- `BOX_SPEND` : détruit la box, restitue `value` au owner.
- **Collecte de rente** : après `storagePeriod` blocs (à calibrer — 4 ans Ergo ≈
  25 M de blocs à 5 s ; viser plutôt ~6–12 mois), le mineur peut prélever
  `storageFeeFactor × taille` en recréant la box à l'identique (registres
  préservés, `creationHeight` remis à jour), ou la détruire si `value ≤ rente`.
  Reprendre les règles de `checkExpiredBox` d'Ergo telles quelles — elles sont
  simples et testées (`ExpirationSpecification.scala`).

### 4.3 Lecture par les contrats — l'équivalent des data inputs

Nouvelle host function dans `WasmVm` : `box_read(idPtr, outPtr, outCap) -> i32`,
avec coût forfaitaire type `dataInputCost`. Deux options de granularité :

1. *Simple* (v1) : lecture directe de l'état courant pendant l'exécution —
   suffisant tant que l'exécution des blocs est séquentielle.
2. *Fidèle à Ergo* (v2) : les ids de boxes lues sont **déclarés dans la
   transaction** (`dataBoxIds`). Avantages : validation d'existence avant
   exécution, tarification explicite, parallélisation future de l'exécution, et
   inclusion des lectures dans les preuves d'état quand le state root arrivera.

Le pattern oracle d'Ergo devient : un contrat oracle possède une box, la met à
jour à chaque tick ; tout contrat consommateur fait `box_read(oracleBoxId)` —
zéro contention, zéro `call_contract`.

### 4.4 API et indexation

- `GET /box?id=` — lecture d'une box.
- `GET /boxes?owner=` / `GET /boxes?register0=...` — nouvelles column families
  d'index dans `RocksDbNodeStore` (par owner ; par valeur de registre indexée à
  la demande, cf. `/scan`).
- `POST /scan/register` + `GET /scan/stream` — prédicats déclaratifs style EIP-1
  (owner, préfixe de registre, contrat contrôleur), branchés sur le hub SSE
  existant (`SseLogHub`) : les agents s'abonnent aux boxes qui les concernent.
- `POST /call_readonly` — dry-run d'un `CALL` sans inclusion (manque déjà
  aujourd'hui, indépendamment des boxes).

### 4.5 Points d'insertion dans le code

| Chantier | Modules / fichiers |
|---|---|
| Objet `Box`, sérialisation, kinds | `lib-core/.../transaction/`, nouveau `lib-core/.../box/` |
| Validation, rente, anti-dust | `Executor.java` (executeBlock/applyContract), `MemPool.java` (admission) |
| Persistance + index | `RocksDbNodeStore.java` (CF `boxes`, `box_owner_index`, `box_expiry_index` triée par hauteur d'expiration pour la collecte) |
| Host function `box_read` | `lib-vm/.../WasmVm.java`, `HostState.java`, `GasSchedule.java` |
| Reorg | journaux d'annulation par bloc, même mécanique que `WasmContractProcessor.flushWithJournal` |
| API + scan | `app-node/.../NodeApi.java`, `NodeService.java`, `SseLogHub.java` |
| Wallet | `app-wallet` : `box-create`, `box-read`, `box-spend` |

Un index trié par hauteur d'expiration (`box_expiry_index`) rend la collecte de
rente O(boxes expirées) pour le `BlockAssembler` — Ergo n'a pas ce luxe (il
scanne), autant le prévoir d'emblée.

### 4.6 Ce que ça apporte aux agents, concrètement

- **Mémoire persistante adressable** : un agent écrit un souvenir/état dans une
  box (≤ 64 KiB — un embedding, un document, un état sérialisé ; au-delà :
  hash on-chain + blob off-chain, ou chunking multi-boxes), le retrouve par id,
  le prouve à un tiers (une fois le state root en place).
- **Tableaux d'affichage / annuaires** : registre d'agents (box par agent :
  endpoint, clé publique, capacités), places de marché de services — lisibles
  par tous en data input, mutables uniquement par le owner.
- **Oracles sans contention** : données de prix/état du monde consommables par N
  contrats par bloc.
- **Économie saine** : la rente garantit que la mémoire abandonnée par des
  agents morts est recyclée, et `minValuePerByte` que le spam de données paie
  son poids — indispensable quand les écrivains sont des programmes.
- Combiné à l'« agent wallet » existant (M7, session keys + spend caps), Rhizome
  couvrirait identité + fonds + mémoire + observabilité (scan/SSE) : la boucle
  complète d'un agent on-chain.

---

## 5. Roadmap suggérée

**Phase 1 — Data boxes (le cœur)**
Objet `Box`, kinds `BOX_CREATE/UPDATE/SPEND`, `minValuePerByte`, host function
`box_read` (option simple), CF + index owner, endpoints `/box`, `/boxes`,
journaux de reorg. Livrable : un agent stocke et relit une info on-chain.

**Phase 2 — Économie et observabilité**
Storage rent (règles `checkExpiredBox`, index d'expiration, collecte dans
`BlockAssembler`), API `/scan` + flux SSE de boxes, `POST /call_readonly`,
`dataBoxIds` déclarés dans les transactions.

**Phase 3 — Tokens natifs (optionnel mais aligné « memecoins »)**
Id de token = id de box d'émission, conservation par transaction, métadonnées
style EIP-4 dans les registres, index par token. Réduit le coût d'un lancement
de token à une transaction, sans contrat.

**Phase 4 — État authentifié**
State root (arbre merkleisé ou AVL+ à la `avldb`) couvrant boxes + soldes +
stockage contractuel, ajouté au préimage du header ; preuves d'inclusion pour
clients légers ; ensuite seulement snap-sync, et section Extension + vote des
mineurs pour rendre `storageFeeFactor`/`minValuePerByte` ajustables sans fork.
À défaut de l'implémenter tôt, **réserver dès maintenant un champ `stateRoot`
dans le format de header** pour éviter un hard fork de format plus tard.

---

## Annexe — sources dans les deux dépôts

Côté Ergo : `ErgoTransaction.scala` (structure et validation, data inputs,
tokens), `ErgoInterpreter.scala:42-88` + `wallet/protocol/Constants.scala`
(storage rent), `Parameters.scala` (paramètres votables),
`ErgoState.scala`/`UtxoState.scala` + module `avldb/` (état authentifié,
snapshots), `ScanningPredicate.scala`/`ScanApiRoute.scala` (scan EIP-1),
`Extension.scala`/`NipopowAlgos.scala` (extension, interlinks),
`DeliveryTracker.scala` (P2P). Les primitives `ErgoBox`/`Input`/`DataInput`
vivent dans la dépendance sigma (v6.0.3), pas dans le repo.

Côté Rhizome : `Ledger.java`, `TransactionImpl.java`/`TransactionDto.java`,
`BlockImpl.java`, `Executor.java`, `MemPool.java`, `WasmVm.java`/`HostState.java`,
`WasmContractProcessor.java`, `RocksDbNodeStore.java`/`RocksDbContractStore.java`,
`NodeApi.java`/`SseLogHub.java`.
