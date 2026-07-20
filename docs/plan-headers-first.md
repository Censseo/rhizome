# Plan — Sync headers-first, pruning et snap-sync

> Planification détaillée du chantier « couche sync » : synchronisation headers-first,
> nœuds élagués (pruning), et bootstrap par snapshot d'état (snap-sync) adossé à la
> racine d'état authentifiée (§5.7 du whitepaper). Statut : plan validé en interne,
> implémentation par étapes A→E. Version 1.0.

## 0. Résumé exécutif

Trois capacités, dans l'ordre de dépendance :

1. **Headers-first** : valider la chaîne d'en-têtes (PoW, difficulté, timestamps,
   chaînage) *avant* de télécharger les corps de blocs. Gain immédiat : la barrière
   anti-DoS du synchronizer (« claimed heavy, proved light ») coûte un téléchargement
   d'en-têtes (~150 o/bloc) au lieu de blocs complets (jusqu'à 4 MiB/bloc).
2. **Pruning** : supprimer les corps de blocs anciens (garder en-têtes + index de
   transactions), pour un nœud à empreinte disque bornée.
3. **Snap-sync** : un nœud neuf télécharge un snapshot d'état récent, le **vérifie
   contre la racine d'état committée dans un en-tête** (trust-minimisé), puis ne rejoue
   que le suffixe de blocs complets.

Le prérequis transversal, identifié par l'audit ci-dessous : **le moteur doit cesser de
dépendre des corps de blocs historiques** pour son état dérivé. C'est l'étape A, la
plus délicate, et elle contient l'unique changement de consensus du chantier (D2).

---

## 1. Audit — dépendances actuelles à l'historique complet

Inventaire exhaustif des lectures d'historique dans `ChainEngine` et ses satellites,
avec pour chacune le niveau de donnée réellement nécessaire :

| # | Site | Ce qu'il lit aujourd'hui | Ce dont il a besoin | Verdict |
|---|---|---|---|---|
| 1 | `computeDifficultyFromChain` (`ChainEngine:~790`) | `store.blockAt(boundary)` pour chaque frontière de retarget depuis genesis — **O(hauteur) blocs complets à chaque add/pop** | les timestamps d'en-tête aux frontières | **Headers** |
| 2 | `medianTimePast` | timestamps des 60 derniers blocs | timestamps d'en-tête | **Headers** |
| 3 | `rebuildDerivedState` (boot) | **tous les blocs complets** : nonces de compte (niveau transaction !), travail des uncles, travail total, replay des votes | en-têtes (votes, difficultés, refs d'uncles) + **nonces persistés** | **Headers + D2** |
| 4 | `checkAccountNonces` / `commitAccountNonces` / `revertAccountNonces` | map mémoire `nextNonce` reconstruite du replay complet | un store de nonces persisté | **D2 — le point dur** |
| 5 | `uncleContext` / `uncleEligible` / `validateUncles` | hashes de chaîne récente + refs d'uncles on-chain ; `blockMiner(uncle)` lit l'orphan pool (blocs complets, hors chaîne — OK) | en-têtes (les refs d'uncles sont committées dans le hash d'en-tête) | **Headers** |
| 6 | `ChainSynchronizer.findCommonAncestor` + gate anti-DoS (`fetchRange` + `branchChainsFromFork` + `verifiedWork`) | blocs complets du pair pour prouver le travail revendiqué | en-têtes (PoW + difficulté + chaînage se vérifient sans corps) | **Headers** |
| 7 | dédup `alreadyExecuted` (`Executor` via `txindex`) | index hash-de-contenu → hauteur, sur tout l'historique | idem — mais l'index est petit (40 o/tx) | **Conserver non-élagué** |

Dépendances qui ne posent PAS problème : `GenesisBlock.matches` (genesis toujours
conservé), l'accumulateur d'état (racines par hauteur, déjà indépendant),
`BlockAssembler`/mempool (état courant seulement), `/logs` (rétention récente déjà
bornée par `retainDepth`).

**Conclusion de l'audit** : tout devient header-only *sauf* les nonces de compte (#4),
qui sont un état dérivé au niveau transaction. D'où la décision D2.

---

## 2. Décisions de design

### D1 — `BlockHeader` objet de première classe

Un record `BlockHeader` portant exactement le préimage du hash d'en-tête **plus les
refs d'uncles** (elles sont committées dans le hash, donc font partie de l'en-tête
logique même si `BlockCodec` les sérialise aujourd'hui après les transactions) :

```
BlockHeader {
  id(4) ‖ timestamp(8) ‖ difficulty(4) ‖ numTransactions(4)
  ‖ lastBlockHash(32) ‖ merkleRoot(32) ‖ nonce(32) ‖ stateRoot(32) ‖ vote(4)
  ‖ uncleCount(4) ‖ [uncleHash(32) ‖ uncleDifficulty(4) ‖ uncleMiner(25)]*
}
```

- `hash()` et `verifyNonce()` recomputables depuis ce seul objet (le PoW d'un en-tête
  se vérifie sans corps — c'est toute la valeur du headers-first).
- Dérivable d'un `Block` (`BlockHeader.of(block)`) ; codec binaire dédié
  (`HeaderCodec`), auto-délimité par `uncleCount`.
- `numTransactions` reste dans l'en-tête (déjà dans le préimage) — il borne le
  décodage du corps correspondant.

*Alternative rejetée* : réutiliser `BlockDto` + un flag. Rejetée parce que `BlockDto`
n'emporte pas les uncles, et que le hash d'en-tête serait alors non recomputable
depuis la donnée stockée — défaut fatal pour la validation headers-first.

### D2 — Nonces de compte : persistés **et** committés dans la racine d'état ⚠ consensus

Deux obligations distinctes convergent :
- **Pruning** : sans corps historiques, impossible de reconstruire `nextNonce` au boot.
  → persister (CF `nonces`, adresse(25) → nonce(8), écrite par
  `commitAccountNonces`/`revertAccountNonces`).
- **Snap-sync** : un nœud bootstrappé doit obtenir les nonces **de façon vérifiable**,
  sinon il ne peut pas valider les blocs suivants (règle des nonces séquentiels). Une
  donnée non committée dans la racine devrait être crue sur parole — inacceptable.
  → nouveau domaine d'état `ACCOUNT_NONCE = 0x07` dans `StateKeys` : clé = adresse,
  valeur = nonce(8, BE). Alimenté par l'engine dans `collectStateChanges` à partir des
  nonces touchés par le bloc (les transactions listent leurs `from`).

**⚠ Impact consensus** : ajouter un domaine change toutes les racines d'état → tous
les nœuds doivent être à niveau simultanément. La chaîne étant **pré-lancement**
(aucun réseau public), le changement est libre aujourd'hui ; c'est précisément
pourquoi cette étape doit passer **maintenant**, avant tout gel. Après lancement, ce
même changement exigerait une activation coordonnée par hauteur.

*Alternative rejetée* : encoder le nonce dans la valeur du domaine `LEDGER`
(`balance(8) ‖ nonce(8)`). Rejetée : couple deux cycles de vie (un compte peut
recevoir sans jamais émettre, et émettre à solde nul), churn double du domaine
ledger, et asymétrie avec les autres domaines « une donnée = un domaine ».

### D3 — Deux pointeurs de chaîne : `headerHeight ≥ fullHeight`

Le store maintient deux hauteurs : la chaîne d'**en-têtes validés** (statelessly :
chaînage, PoW, difficulté recomputée, MTP, borne future) peut précéder la chaîne de
**blocs exécutés**. En régime nominal (gossip) les deux avancent ensemble ; en sync
initial, les en-têtes filent devant et les corps suivent par lots.

Règle de cohérence : `popBlock` ne recule que `fullHeight` si `headerHeight` est en
avance ; un reorg d'en-têtes au-dessus de `fullHeight` est une simple substitution
d'en-têtes (aucun état à défaire) ; un reorg qui traverse `fullHeight` suit le chemin
actuel (pop des blocs exécutés), borné par `maxReorgDepth` comme aujourd'hui.

### D4 — Pivot de snap-sync = `headerTip − maxReorgDepth − marge`

Le snapshot est pris/consommé à une hauteur **pivot** enfouie sous la fenêtre de
finalité (`maxReorgDepth = 120`, plus une marge de sécurité — proposé : 2× la
fenêtre, soit pivot = tip − 240, ~20 min). Comme tout nœud **refuse** un reorg plus
profond que `maxReorgDepth` (règle `REORG_TOO_DEEP` existante), un reorg traversant
le pivot est impossible par construction : le nœud bootstrappé n'aura jamais à
« dé-importer » son snapshot.

### D5 — Snapshot d'état : dump par domaine, vérifié par ré-insertion — pas de manifest d'arbre

Propriété décisive déjà prouvée par test (`rootIsIndependentOfInsertionOrder`) : la
racine du SMT est une fonction du **seul ensemble** des bindings, indépendante de
l'ordre. Donc, contrairement à Ergo (manifest + subtrees découpés dans la structure
de l'arbre AVL+), le snapshot Rhizome n'a **pas besoin de refléter la structure de
l'arbre** :

- **Export** : pour chaque domaine (ledger, nonces, boxes, token_meta, token_balance,
  contract_code, contract_storage), un dump paginé par curseur des paires
  `(rawKey, value)` **à la hauteur pivot**. Chunks auto-délimités, bornés (~1 MiB).
- **Import** : le récepteur insère toutes les entrées (n'importe quel ordre !) dans un
  SMT vierge et exige `root == header(pivot).stateRoot` — l'en-tête ayant été validé
  en phase headers avec tout le PoW de la chaîne au-dessus. Toute falsification d'un
  chunk fait échouer l'égalité finale.
- L'import alimente **aussi les stores de domaine** (ledger CF, nonces CF, box store
  + reconstruction de ses index owner/expiry, token store + index minter/holder,
  contract store) — les index secondaires sont recalculés depuis les valeurs, jamais
  transférés (non vérifiables).
- Optionnel (optimisation, pas sécurité) : hash par chunk annoncé dans
  `/state/snapshot/info` pour échouer tôt et pénaliser le pair fautif au chunk près.

**Contrainte de cohérence de l'export** : le dump doit être une coupe cohérente à la
hauteur pivot. Deux options : (a) servir depuis un nœud dont `fullHeight` est gelé le
temps de l'export (inacceptable en prod) ; (b) **s'appuyer sur la fenêtre de
finalité** : le serveur exporte l'état *courant* mais le client choisit un pivot
enfoui (D4) et le serveur n'exporte que si `fullHeight − pivot ≤ retainDepth` des
journaux… Trop fragile. Option retenue : **(c) snapshot matérialisé périodique** — le
nœud serveur matérialise un export complet tous les `snapshotEvery` blocs (config,
défaut ~17 280 = 1 jour) dans un répertoire dédié, hors verrou moteur (itération
RocksDB sur un snapshot-lecture natif `db.getSnapshot()` — cohérent par
construction), et annonce `(pivotHeight, stateRoot, chunkCount)` dans
`/state/snapshot/info`. C'est le modèle d'Ergo (`makeSnapshotEvery = 52 224`),
adapté : pas de chunking par arbre, juste des dumps.

### D6 — Pruning : corps supprimés, en-têtes + txindex + genesis conservés

- Config `RHIZOME_PRUNE=<keepBlocks>` (0/absent = archive complet). Plancher :
  `keepBlocks ≥ max(maxReorgDepth, uncleMaxDepth, difficultyLookback, medianTimeWindow) + marge` — imposé au boot.
- `pruneBodiesBelow(h)` : supprime les entrées CF `blocks` sous h, garde `headers`,
  `txindex` (40 o/tx, nécessaire à la dédup — #7 de l'audit), et le bloc genesis
  (identité de chaîne, `GenesisBlock.matches` au boot).
- `/sync` sur une plage élaguée → 410 GONE + JSON explicite ; `/info` annonce
  `prunedBelow` pour que les pairs choisissent leur source sans essais-erreurs.
- Un nœud élagué **sert** : en-têtes complets, blocs récents, snapshots, état courant.

### D7 — Compatibilité protocolaire : additive, avec détection de capacité

Nouveaux endpoints seulement (`/headers`, `/state/snapshot/*`) ; `/sync`, `/block`,
gossip inchangés. `HttpPeerSource` sonde `/headers` ; un pair ancien (404) retombe
sur le chemin blocs-complets actuel. Aucun bloc, aucune transaction ne change de
format — **zéro hard fork wire** (le seul changement de consensus est D2, dans les
racines d'état, pas dans les formats).

---

## 3. Architecture cible

### 3.1 Nouvelles classes (lib-core)

| Classe | Rôle |
|---|---|
| `BlockHeader` (+ `HeaderCodec`) | l'en-tête logique (D1) : préimage + uncles ; `hash()`, `verifyNonce()`, `of(Block)` |
| `HeaderChain` | validation stateless d'une suite d'en-têtes : continuité id/hash, PoW, difficulté recomputée depuis les timestamps d'en-têtes (réutilise `DifficultyAdjustment`), MTP, borne future, cumul de travail (difficulté + uncles) |
| `NonceStore` (+ `InMemoryNonceStore`) | `nextNonce(addr)`, `bump(addr)`, `setAll`, itération pour l'export |
| `StateSnapshotExporter` / `StateSnapshotImporter` | D5 : enumerate/dump par domaine ; import → SMT vierge + stores de domaine + vérif racine |
| `SnapshotChunk` (codec) | `domain(1) ‖ count(4) ‖ [keyLen(2) ‖ key ‖ valLen(4) ‖ val]*` |
| `HeaderSynchronizer` | la machine à états du sync (§3.3), remplace la boucle actuelle de `ChainSynchronizer` en la subsumant |

### 3.2 Interfaces étendues

- **`ChainStore`** : `+ headerHeight()`, `headerAt(h)`, `appendHeader(BlockHeader)`,
  `truncateHeadersAbove(h)`, `hasBody(h)`, `pruneBodiesBelow(h)`, `prunedBelow()`.
  `append(Block)` écrit désormais **aussi** l'en-tête (extrait du bloc) dans le même
  `WriteBatch` — les deux CF ne peuvent pas diverger.
- **`PeerSource`** : `+ headers(start, end)` (List<BlockHeader>), `+ snapshotInfo()`,
  `+ snapshotChunk(domain, after, limit)` — avec défauts « non supporté » pour D7.
- **Énumérateurs de domaine** (pour l'export) : `Ledger + forEachBalance`,
  `NonceStore + forEachNonce`, `BoxStore + forEachBox`, `TokenStore + forEachMeta/forEachBalance`,
  `ContractStore + forEachCode/forEachStorage` — implémentations RocksDB sur
  itérateurs de snapshot-lecture (`db.getSnapshot()`).

### 3.3 Machine à états du sync (HeaderSynchronizer)

```
        ┌────────────────────────────────────────────────────────────┐
        v                                                            │
      IDLE ──(pair avec totalWork > local)──> HEADER_SYNC            │
                                                  │                  │
             en-têtes validés jusqu'au tip pair   │                  │
                                                  v                  │
                              ┌─── fullHeight == 0 && mode=snap ──┐  │
                              v                                   v  │
                          SNAPSHOT                            BODY_SYNC
                     (info → chunks → vérif                (blocs [fullHeight+1
                      racine → seed stores,                 .. headerHeight] par
                      fullHeight := pivot)                  lots, addBlock)
                              │                                   │
                              └────────────> BODY_SYNC ───────────┤
                                                                  v
                                                                LIVE (gossip, boucle actuelle)
```

Détails :
- **HEADER_SYNC** : ancêtre commun cherché sur les *en-têtes* (boucle descendante
  actuelle, mais à ~150 o la comparaison ; bisection = optimisation ultérieure, non
  bloquante). Les en-têtes candidats sont validés par `HeaderChain` **avant** toute
  mutation ; le travail prouvé (headers) remplace l'actuel gate à blocs complets de
  `ChainSynchronizer.reorg`. Un pair « claimed heavy, proved light » coûte désormais
  ~150 o/bloc. Reorg d'en-têtes > `maxReorgDepth` sous `fullHeight` → refus (règle
  inchangée).
- **SNAPSHOT** (mode `snap`, chaîne locale vierge uniquement) : choisir le
  `snapshotInfo` le plus récent parmi ≥ `minSnapshotPeers` pairs (défaut 2, comme
  Ergo) **dont le stateRoot correspond à notre en-tête validé au pivot** — le
  quorum est un confort (disponibilité), pas une hypothèse de confiance : la
  vérification reste l'égalité de racine. Échec/timeout → pair suivant ; aucun pair
  → repli `full`.
- **BODY_SYNC** : l'actuel `applyRange` (lots de `BLOCKS_PER_FETCH`), borné par
  `headerHeight` validé ; chaque corps est vérifié contre son en-tête (hash) avant
  `addBlock` — un corps falsifié est détecté sans toucher l'état.
- **LIVE** : inchangé (gossip de blocs complets ; `append` maintient les deux CF).
- Pénalités ban-score : mêmes hooks qu'aujourd'hui, avec deux nouveaux motifs
  (en-têtes invalides, chunk de snapshot invalide).

### 3.4 Endpoints (app-node)

| Endpoint | Réponse |
|---|---|
| `GET /headers?start=&end=` | flux binaire `HeaderCodec` (≤ `HEADERS_PER_FETCH = 2000`) |
| `GET /state/snapshot/info` | `{pivotHeight, stateRoot, domains: [{domain, entries, chunks}], chunkBytes}` ou 404 si aucun snapshot matérialisé |
| `GET /state/snapshot/chunk?domain=&index=` | chunk binaire `SnapshotChunk` |
| `GET /info` (enrichi) | `+ headerHeight, prunedBelow, snapshotPivot` |

### 3.5 Config

| Variable | Valeurs | Défaut |
|---|---|---|
| `RHIZOME_SYNC` | `full` \| `snap` | `full` |
| `RHIZOME_PRUNE` | entier `keepBlocks` (0 = archive) | `0` |
| `RHIZOME_SNAPSHOT_EVERY` | blocs entre snapshots matérialisés (0 = jamais) | `17280` (~1 j) |

---

## 4. Séquencement — étapes A → E

Chaque étape est un incrément committable : build vert, tous les tests antérieurs
inchangés, migration automatique au boot, et une valeur livrée en soi.

### Étape A — Fondations : régime « headers + nonces persistés » (aucun changement protocole)

Le moteur cesse de lire les corps historiques. **La plus grosse étape, le cœur du risque.**

- **A1** `BlockHeader` + `HeaderCodec` + équivalence `BlockHeader.of(b).hash() == b.hash()`
  et `verifyNonce` (tests golden sur blocs avec/sans uncles/stateRoot/vote).
- **A2** CF `headers` dans `RocksDbNodeStore` (+ `InMemoryChainStore`), écrite dans le
  même batch que `append`/`pop`. **Migration au boot** : CF vide && blocs présents →
  backfill headers depuis les blocs stockés (une passe, log de progression).
- **A3** Bascule des lectures moteur : `computeDifficultyFromChain`, `medianTimePast`,
  `uncleContext`/`uncleEligible`, replay des votes (`applyVotingAt` en rebuild) →
  `headerAt`. Test d'équivalence : sur des chaînes aléatoires (uncles, votes,
  retargets), difficulté/MTP/travail identiques via blocs et via headers.
- **A4** `NonceStore` persisté (CF `nonces`) branché sur
  `commit/revertAccountNonces` ; `rebuildDerivedState` ne parcourt plus les
  transactions (migration au boot : CF vide → reconstruction unique depuis les blocs
  puis persistance). Test : redémarrage + pop + re-add → nonces exacts.
- **A5** ⚠ Domaine `ACCOUNT_NONCE = 0x07` dans la racine d'état (D2) : engine ajoute
  les nonces touchés dans `collectStateChanges` ; mise à jour des tests de racine.
  **Unique changement de consensus du chantier — isolé dans ce commit.**
- Critère de sortie : `rebuildDerivedState` = O(en-têtes) + lecture CF nonces ;
  aucune lecture de corps hors exécution/reorg de blocs récents. 304 tests verts +
  ~12 nouveaux.

### Étape B — Protocole headers + synchronizer v2

- **B1** `GET /headers` + `HttpPeerSource.headers()` (+ détection 404 → fallback D7).
- **B2** `HeaderChain` (validateur stateless) + tests de rejet exhaustifs : PoW
  invalide, difficulté incorrecte, timestamp ≤ MTP, id discontinu, chaînage rompu,
  travail d'uncle gonflé (refs committées ≠ travail réclamé).
- **B3** `HeaderSynchronizer` : HEADER_SYNC + BODY_SYNC (mode `full`), gate de
  travail sur en-têtes remplaçant le prefetch de blocs de `ChainSynchronizer.reorg` ;
  corps vérifiés contre en-têtes avant exécution. `RhizomeNode.syncRound` bascule.
- Critère de sortie : `NodeSyncIntegrationTest` (2 nœuds, reorg compris) vert sur le
  nouveau chemin ; test « pair menteur sur totalWork » ne coûte que des en-têtes.
  ~10 nouveaux tests.

### Étape C — Pruning

- **C1** `pruneBodiesBelow` + plancher de config + élagage incrémental à chaque
  `append` (effacer 1 corps quand on en ajoute 1 — coût amorti O(1)).
- **C2** `/sync` → 410 sur plage élaguée ; `prunedBelow` dans `/info` ;
  `HeaderSynchronizer` évite les sources élaguées pour les plages profondes.
- Critère de sortie : nœud élagué mine, valide, sert les en-têtes et blocs récents ;
  redémarre correctement (migration + rebuild header-only) ; un nœud neuf en mode
  `full` sync depuis un pair archive quand l'élagué ne peut pas servir. ~8 tests.

### Étape D — Snap-sync

- **D1** Énumérateurs de domaine (RocksDB : itération sur snapshot-lecture natif) +
  `StateSnapshotExporter` (matérialisation périodique, hors verrou) + endpoints.
- **D2** `StateSnapshotImporter` : chunks → SMT vierge → **égalité de racine** avec
  l'en-tête pivot → seed des stores de domaine + reconstruction des index
  secondaires → `fullHeight := pivot` → BODY_SYNC du suffixe.
- **D3** Intégration `HeaderSynchronizer` (branche SNAPSHOT), mode `RHIZOME_SYNC=snap`.
- Tests clés : export→import → racine identique **et** stores de domaine
  bit-identiques (balances, nonces, boxes + index, tokens + index, contrats) ; chunk
  falsifié → rejet + pénalité ; pair sans snapshot → repli ; **test d'intégration
  complet** : mineur actif + nœud neuf en `snap` → convergence au même `stateRoot`
  et même tip que le mineur, en ayant téléchargé < X blocs complets. ~14 tests.

### Étape E — Documentation

- Whitepaper : §6.1 réécrit (protocole headers-first, machine à états), nouveau
  §6.x « Pruned nodes and snapshot bootstrap » (miroir du §8 « bootstrapping » d'Ergo,
  en citant D4/D5), §5.7 mis à jour (prérequis levé), abstract + roadmap + compteurs.
- `docs/ergo-analysis.md` : reliquat snap-sync soldé.

**Ordre strict A → B → C → D** (chaque étape consomme la précédente). E au fil de
l'eau, consolidé à la fin.

---

## 5. Plan de tests transverse

Au-delà des tests par étape listés ci-dessus :

1. **Équivalence dérivée** (A) : générateur de chaînes aléatoires (retargets, uncles,
   votes, box/token/contract txs) → l'état dérivé (difficulté, MTP, totalWork,
   nonces, params votés) est identique calculé depuis les blocs et depuis les
   en-têtes + CF nonces. C'est le test qui protège la bascule A3/A4.
2. **Adversarial sync** (B) : pair qui ment sur `totalWork`, sert des en-têtes à PoW
   invalide, sert un corps ≠ en-tête, sert des en-têtes en fourche > finalité —
   chaque cas → statut/pénalité attendus, état local intact.
3. **Crash/restart** (A, C) : kill après append, après prune, après backfill partiel
   (backfill idempotent et repris au boot).
4. **Adversarial snapshot** (D) : chunk altéré (1 octet), entrée dupliquée, entrée
   omise, domaine tronqué → l'égalité de racine échoue, rien n'est committé (import
   dans des stores temporaires, swap atomique en fin d'import réussi).
5. **Reorg au voisinage du pivot** (D) : reorg de profondeur `maxReorgDepth` juste
   au-dessus du pivot → le nœud snap-syncé le traite comme n'importe quel nœud.

---

## 6. Risques et points ouverts

| # | Risque | Sévérité | Mitigation |
|---|---|---|---|
| R1 | **D2 change toutes les racines d'état** (consensus) | Haute si réseau lancé — **nulle aujourd'hui** (pré-lancement) | Isoler dans le commit A5 ; à documenter comme « à activer par hauteur » si un réseau existait |
| R2 | Cohérence crash : CF `ledger`/`nonces`/box/token écrites hors du batch de bloc (préexistant pour le ledger) | Moyenne | Hors périmètre ici ; chantier « batch unique » séparé, noté au whitepaper. Le backfill/rebuild au boot reste le filet |
| R3 | Journaux contrats en mémoire → reorg post-redémarrage limité (préexistant) | Basse | Inchangé par ce chantier ; noté |
| R4 | Import snapshot : O(état) insertions SMT (~log n hashes chacune) — durée sur gros état | Basse | Chunks streamés, import incrémental, mesure dans le test d'intégration ; parallélisation possible plus tard (racine indépendante de l'ordre !) |
| R5 | `findCommonAncestor` linéaire | Basse | Acceptable (en-têtes) ; bisection = optimisation future |
| R6 | Uncles référençant des blocs élagués | Nulle | `uncleMaxDepth = 7` ≪ plancher de `keepBlocks` (imposé C1) |
| R7 | Deux tips (header/full) exposés à l'API → confusion des clients | Basse | `/info` explicite les deux ; `/block_count` reste `fullHeight` (compat) |

Points ouverts assumés (tranchés pendant l'implémentation, sans impact structurel) :
la valeur exacte de la marge du pivot (D4, proposé 2×fenêtre), le format de pagination
des chunks (index vs curseur — proposé index séquentiel, plus simple à matérialiser),
et l'opportunité d'un hash par chunk (optimisation, décidée au benchmark D).

---

## 7. Ce que ce chantier ne fait pas (périmètre exclu)

- **Clients légers réseau** (suivre la chaîne par en-têtes seuls sans exécuter) : les
  briques seront toutes là (`/headers`, `/state/proof`), mais le mode nœud dédié est
  un chantier ultérieur.
- **NiPoPoW** (bootstrap des en-têtes en polylog) : pertinent quand la chaîne sera
  longue ; le format d'en-tête n'y fait pas obstacle (interlinks logeables dans une
  future section extension).
- **Batch d'écriture unique** toutes-CF (R2) : chantier de durcissement séparé.
- **Bisection de l'ancêtre commun** (R5) : optimisation différée.
