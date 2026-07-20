# Spécification — Data boxes (Phase 1)

> Spécification d'implémentation de la couche de « data boxes » inspirée d'Ergo,
> décrite dans [`ergo-analysis.md`](ergo-analysis.md). Objets d'état de première
> classe pour le stockage d'informations on-chain par des agents, avec anti-dust
> (`minValuePerByte`) et rente de stockage. Statut : proposition, v0.1.

## 1. Objectifs et non-objectifs

**Objectifs (Phase 1)**
- Un objet `Box` natif : identifiant stable, propriétaire, registres de données
  typés, valeur verrouillée proportionnelle à la taille.
- Quatre opérations protocole : création, mise à jour, destruction, collecte de
  rente — sans exécution WASM (déterministes, O(taille)).
- Lecture par les contrats via une host function `box_read`.
- API de lecture (`/box`, `/boxes`) + événements poussés sur le flux SSE existant.
- Réversibilité exacte sur reorg, même mécanique que l'état contractuel.

**Non-objectifs (Phase 1)** — écriture de boxes depuis les contrats (host
functions `box_create/update/spend`, Phase 2), prédicats `/scan` (Phase 2),
`dataBoxIds` déclarés dans les transactions (Phase 2), tokens dans les boxes,
state root authentifié (Phase 4).

## 2. Divergences assumées par rapport à Ergo

| Ergo | Rhizome | Pourquoi |
|---|---|---|
| Box immuable, id = hash du contenu ; « mise à jour » = dépense + recréation avec un **nouvel id** ; identité stable via un NFT singleton | **Id stable** dérivé du créateur + nonce, contenu mutable par le owner | Les agents référencent une cellule (« ma mémoire », « l'oracle X ») ; Ergo doit recourir au pattern NFT précisément parce que ses ids changent. La preuve d'intégrité du contenu reviendra avec le state root (Phase 4), pas besoin d'id = hash. |
| Garde par script ErgoTree (R1) | Garde par **propriétaire** : adresse (signature Ed25519) ou contrat (Phase 2 : host functions) | Rhizome a déjà un langage de contrats (WASM) ; dupliquer un langage de garde est du coût sans gain. « Box auto-répliquante » ⇒ « box possédée par un contrat ». |
| Types sigma complets | 6 tags de type simples | Suffisant pour les usages (blobs, hashes, entiers, adresses) ; pas de VM de types à écrire. |
| Rente via dépense à preuve vide + variable de contexte 127 | Kind protocole `BOX_COLLECT` explicite | Pas de système de preuve scriptable à détourner ; un kind dédié est plus simple et plus lisible. |
| `dataInputs` déclarés dans la tx | Phase 1 : `box_read` lit l'état courant pendant l'exécution ; déclaration dans la tx en Phase 2 | L'exécution des blocs est séquentielle aujourd'hui ; la déclaration ne paie que lorsqu'on veut paralléliser ou prouver les lectures. |

## 3. Modèle de données

### 3.1 L'objet `Box`

```
Box {
  id             : 32 octets   // SHA256(creator(25) || nonce(8, BE) || "rzbox") — cf. §3.2
  owner          : 25 octets   // PublicAddress — adresse externe OU adresse de contrat
  value          : int64       // unités de base verrouillées (remboursées au spend)
  createdHeight  : int64       // hauteur de la création initiale (jamais modifiée)
  rentPaidHeight : int64       // début de la période de rente courante
  registers      : 0..6 entrées typées (cf. §3.3)
}
```

**Sérialisation canonique** (stockage, taille facturée, et — plus tard —
feuilles de l'arbre d'état) :

```
id(32) || owner(25) || value(8) || createdHeight(8) || rentPaidHeight(8)
|| regCount(1) || reg[0] || ... || reg[regCount-1]

reg[i] = typeTag(1) || len(2, BE, non signé) || payload(len)
```

Tous les entiers en big-endian (convention `BinaryIO` existante).
`serializedSize(box)` = taille de cette forme ; c'est l'assiette de
`minValuePerByte` et de la rente.

### 3.2 Dérivation de l'id

```
boxId = SHA256( creator.toBytes(25) || nonce(8, BE) || 0x72 0x7A 0x62 0x6F 0x78 )   // "rzbox"
```

où `creator` = `tx.from` et `nonce` = `tx.nonce` de la transaction `BOX_CREATE`.
Même logique que `Contracts.deriveAddress` (déployeur + nonce), avec un suffixe
de domaine pour qu'un box id ne puisse jamais coïncider avec une dérivation
d'adresse de contrat. Le nonce étant strictement croissant par compte, l'id est
unique par construction ; une collision avec une box existante invalide la
transaction (`BOX_ALREADY_EXISTS`, ceinture et bretelles).

L'id est **stable pour toute la vie de la box** — updates et collectes de rente
ne le changent pas.

### 3.3 Registres

Au plus **6 registres** (`maxBoxRegisters`), remplis **densément** (le registre
`i` n'existe que si `0..i-1` existent — même règle qu'Ergo, ça simplifie la
sérialisation et les prédicats de scan futurs). Une mise à jour remplace la
liste **entièrement** (pas d'update partiel en v1).

| Tag | Nom | Contrainte sur `len` | Usage type |
|---|---|---|---|
| 0 | `BYTES` | 0..4032 | blob libre, données sérialisées |
| 1 | `I64` | exactement 8 | compteur, timestamp, prix |
| 2 | `BOOL` | exactement 1 (0x00/0x01) | flag |
| 3 | `ADDRESS` | exactement 25 | référence à un compte/contrat |
| 4 | `HASH32` | exactement 32 | hash de contenu off-chain, box id |
| 5 | `STRING` | 0..4032, UTF-8 valide | nom, URL, endpoint d'agent |

Les tags sont **validés structurellement** (longueur, UTF-8 pour STRING) mais le
protocole n'attache aucune sémantique aux valeurs — ce sont des annotations pour
les lecteurs (agents, indexeurs, wallets). Un tag inconnu invalide la
transaction (`BOX_PAYLOAD_INVALID`) — l'espace de tags s'étend par activation de
version, pas silencieusement.

### 3.4 Limites

| Constante | Valeur | Commentaire |
|---|---|---|
| `MAX_BOX_SIZE_BYTES` | **4096** | taille sérialisée totale (§3.1), comme Ergo. Au-delà : hash on-chain (`HASH32`) + blob off-chain. |
| `maxBoxRegisters` | 6 | |
| taille max d'un registre | bornée par la box (≤ 4032 utiles) | pas de plafond individuel, comme Ergo |

## 4. Paramètres réseau (ajouts à `NetworkParameters`)

```java
// --- Data boxes ---
private final long boxActivationHeight;   // kinds box invalides avant cette hauteur
private final int  maxBoxSizeBytes;       // 4096
private final int  maxBoxRegisters;       // 6
private final long minValuePerByte;       // unités de base à verrouiller par octet sérialisé
private final long storagePeriodBlocks;   // âge (en blocs) avant qu'une box soit collectable
private final long storageFeeFactor;      // rente en unités de base par octet et par période
private final int  maxBoxCollectsPerBlock;// borne le travail de collecte par bloc
```

Valeurs proposées pour `cleanMainnet()` (placeholders à calibrer, comme le
`GasSchedule`) :

| Paramètre | Valeur | Justification |
|---|---|---|
| `boxActivationHeight` | fixée au déploiement | cf. §10 |
| `minValuePerByte` | **5** (0.0005 PDN/octet) | une box pleine (4 Ko) verrouille ~2 PDN, une petite box de 200 o ~0.1 PDN. Remboursé au spend — c'est une caution, pas un coût. |
| `storagePeriodBlocks` | **6 307 200** (~1 an à 5 s) | Ergo prend 4 ans ; pour une chaîne d'agents on veut un GC plus agressif, sans pour autant harceler les utilisateurs. |
| `storageFeeFactor` | **3** (0.0003 PDN/octet/an) | une box pleine paie ~1.2 PDN/an. Une box financée au minimum (5/octet) tombe sous le plancher dust après ~1 période : l'abandon se recycle en ~1 an. |
| `maxBoxCollectsPerBlock` | **32** | borne le coût de validation ; l'index d'expiration (§7) rend la sélection O(1) par box. |

Ces paramètres sont des constantes de consensus en Phase 1 ; ils deviendront
votables par les mineurs quand la section Extension arrivera (Phase 4, cf.
analyse §3.5).

## 5. Transactions

### 5.1 Nouveaux kinds

```java
public enum TransactionKind {
    TRANSFER,      // 0
    DEPLOY,        // 1
    CALL,          // 2
    BOX_CREATE,    // 3
    BOX_UPDATE,    // 4
    BOX_SPEND,     // 5
    BOX_COLLECT;   // 6

    public boolean isContract() { return this == DEPLOY || this == CALL; }
    public boolean isBox()      { return code() >= 3; }
    /** Kinds qui sérialisent le suffixe gasLimit||gasPrice||dataLen||data. */
    public boolean hasPayload() { return this != TRANSFER; }
}
```

**Attention migration** : les usages actuels de `isContract()` se répartissent
en deux intentions qu'il faut séparer :
- *sérialisation / préimage de signature* (`TransactionDto.writeTo/readFrom`,
  `TransactionImpl.hashContents`, `getSize`) → utiliser **`hasPayload()`**. Le
  format wire des kinds existants est inchangé octet pour octet ; les box kinds
  réutilisent le suffixe contrat tel quel.
- *routage d'exécution* (`Executor`, `MemPool`, `WasmContractProcessor`) →
  `isContract()` reste réservé à DEPLOY/CALL ; les box kinds prennent leur
  propre chemin (§6).

### 5.2 Sémantique des champs par kind

Champs communs : `from`, `signingKey`, `signature`, `chainId`, `nonce` (séquence
de compte, comme partout), `timestamp`, `fee` (marché libre, payée au mineur,
comme un transfert). **`gasLimit` et `gasPrice` doivent valoir 0** pour tous les
box kinds (champ réservé ; ≠ 0 ⇒ `BOX_PAYLOAD_INVALID`) — les opérations box
n'exécutent pas de WASM, leur coût est plat et payé par `fee`.

| Kind | `to` | `amount` | `data` |
|---|---|---|---|
| `BOX_CREATE` | **owner** de la box (souvent `from` ; peut être un contrat ou un autre agent) | valeur verrouillée dans la box | `regCount(1) || regs...` |
| `BOX_UPDATE` | ignoré (doit être `from`) | top-up ajouté à `box.value` (≥ 0) | `boxId(32) || regCount(1) || regs...` |
| `BOX_SPEND` | ignoré (doit être `from`) | doit être 0 | `boxId(32)` |
| `BOX_COLLECT` | ignoré (doit être `from`) | doit être 0 | `boxId(32)` |

Convention « ignoré (doit être `from`) » : imposer `to == from` fige la valeur
dans la préimage signée sans introduire de champ optionnel.

### 5.3 Règles de validation et effets

Notations : `H` = hauteur du bloc, `size(b)` = taille sérialisée §3.1, `P` =
`NetworkParameters`. Nouveaux `ExecutionStatus` : `BOX_NOT_FOUND`,
`BOX_ALREADY_EXISTS`, `BOX_NOT_OWNER`, `BOX_PAYLOAD_INVALID`,
`BOX_VALUE_TOO_LOW`, `BOX_NOT_EXPIRED`, `BOX_LIMIT_EXCEEDED`,
`BOX_UNAVAILABLE` (pendant `H < boxActivationHeight`).

**Communes à tous les box kinds** — `H ≥ P.boxActivationHeight` ;
`gasLimit == 0 && gasPrice == 0` ; payload bien formé (longueurs cohérentes,
aucun octet excédentaire, tags valides §3.3, registres denses) ; signature et
nonce comme toute transaction.

**`BOX_CREATE`**
1. `boxId = derive(from, nonce)` ; aucune box existante à cet id.
2. Box candidate : `owner = to`, `value = amount`, `createdHeight =
   rentPaidHeight = H`, registres du payload.
3. `size(box) ≤ P.maxBoxSizeBytes`, sinon `BOX_PAYLOAD_INVALID`.
4. `amount ≥ size(box) × P.minValuePerByte`, sinon `BOX_VALUE_TOO_LOW`.
5. Ledger : `withdraw(from, amount + fee)` ; `deposit(miner, fee)`. La valeur de
   la box **sort du ledger** (elle vit dans la box, comme la valeur envoyée à un
   contrat vit sur son adresse — mais ici hors de tout compte : le total
   monétaire = soldes + valeurs des boxes).
6. Écrit la box ; met à jour les index owner et expiry.

**`BOX_UPDATE`**
1. La box existe, sinon `BOX_NOT_FOUND` ; `from == box.owner`, sinon
   `BOX_NOT_OWNER`. (Une box possédée par un contrat n'est donc pas modifiable
   par transaction signée — Phase 2.)
2. Box candidate : registres remplacés par le payload, `value += amount`,
   `rentPaidHeight = H` (**la mise à jour remet le compteur de rente à zéro**,
   comme la recréation chez Ergo : toucher sa box = la maintenir en vie),
   `createdHeight` inchangé.
3. Contraintes 3–4 de `BOX_CREATE` sur la box candidate (la nouvelle taille peut
   exiger un top-up).
4. Ledger : `withdraw(from, amount + fee)` ; `deposit(miner, fee)`.
5. Réécrit la box ; met à jour l'index expiry (ancienne clé supprimée).

**`BOX_SPEND`**
1. Box existe ; `from == box.owner`.
2. Ledger : `withdraw(from, fee)` ; `deposit(miner, fee)` ; `deposit(from,
   box.value)`.
3. Supprime la box et ses entrées d'index.

**`BOX_COLLECT`** — n'importe qui peut l'émettre ; en pratique le mineur inclut
les siennes (§8).
1. Box existe ; `H − box.rentPaidHeight ≥ P.storagePeriodBlocks`, sinon
   `BOX_NOT_EXPIRED`.
2. `rent = P.storageFeeFactor × size(box)`.
3. Si `box.value − rent < size(box) × P.minValuePerByte` → **collecte totale** :
   `deposit(from, box.value)` ; box supprimée. (Reprend la règle
   `storageFeeNotCovered` d'Ergo, durcie : une box qui ne peut plus payer *et*
   rester au-dessus du plancher dust est recyclée entièrement.)
4. Sinon → **prélèvement** : `box.value −= rent` ; `rentPaidHeight = H` ;
   `deposit(from, rent)` ; registres, owner, `createdHeight`, id **inchangés**
   (l'équivalent de la préservation des registres de `checkExpiredBox`, garantie
   ici par construction puisque l'opération est protocolaire).
5. `fee` comme d'habitude (le mineur qui s'auto-collecte mettra 0).
6. Au plus `P.maxBoxCollectsPerBlock` transactions `BOX_COLLECT` par bloc, sinon
   le bloc est invalide (`BOX_LIMIT_EXCEEDED`).

### 5.4 Préimage de signature

`TransactionImpl.hashContents()` : la branche `kind.isContract()` devient
`kind.hasPayload()` — pour les box kinds, la préimage inclut donc
`kind || gasLimit(=0) || gasPrice(=0) || data`, exactement comme les kinds
contrat. Aucun changement pour TRANSFER/DEPLOY/CALL (compatibilité totale des
signatures existantes).

## 6. Intégration dans l'exécution (`lib-core`)

### 6.1 Interface `BoxProcessor`

Miroir de `ContractProcessor`, définie dans `lib-core` (le consensus ne dépend
jamais de RocksDB), implémentée dans `lib-persistence` :

```java
public interface BoxProcessor {
    void begin();                                  // ouvre la session du bloc
    BoxResult run(BoxOp op);                       // applique une op box à la session
    void commit(long blockHeight);                 // persiste + journal d'annulation
    void discard();                                // bloc rejeté
    void revertBlock(long blockHeight);            // reorg : restaure l'état antérieur
    List<BoxReceipt> receipts(long blockHeight);   // faits runtime pour la réversion ledger

    /** Vue de lecture cohérente avec la session en cours (pour box_read, l'API lit le store committé). */
    Box get(byte[] boxId);

    record BoxOp(TransactionKind kind, PublicAddress from, PublicAddress to,
                 long amount, long nonce, byte[] data, long height) {}
    /** status = SUCCESS ou le code d'échec ; les montants permettent au caller de faire les ops ledger. */
    record BoxResult(ExecutionStatus status, long valueLocked, long valueReleased, byte[] boxId) {}
    /** Ce que le ledger ne peut pas re-dériver seul au reorg (montant effectivement collecté/rendu). */
    record BoxReceipt(TransactionKind kind, long valueMoved) {}
}
```

Le `BoxProcessor` **ne touche jamais le ledger** (même contrat que
`ContractProcessor`) : il valide et mute les boxes, retourne les montants, et
`Executor` fait les `withdraw/deposit` via ses `AppliedOp` réversibles.

### 6.2 `Executor`

- Pass 1 (structurel) : pour `tx.kind().isBox()`, valider `gasLimit==0 &&
  gasPrice==0`, `amount ≥ 0`, la bonne forme statique du payload, et rejeter si
  `boxProcessor == null` (`BOX_UNAVAILABLE`) ou `H < boxActivationHeight`.
  Compter les `BOX_COLLECT` et rejeter au-delà de `maxBoxCollectsPerBlock`.
- Pass 2 : nouvelle branche `applyBox(tx, ...)` avant la branche transfert,
  symétrique d'`applyContract` :

```
result = boxProcessor.run(op)
si result.status != SUCCESS → abort(bloc invalide)      // contrairement aux contrats :
                                                        // pas de « revert toléré », une op box
                                                        // invalide invalide le bloc (elle est
                                                        // entièrement vérifiable à l'admission)
BOX_CREATE : withdraw(from, amount+fee) ; deposit(miner, fee)
BOX_UPDATE : withdraw(from, amount+fee) ; deposit(miner, fee)
BOX_SPEND  : withdraw(from, fee) ; deposit(miner, fee) ; deposit(from, receipt.valueMoved)
BOX_COLLECT: withdraw(from, fee) ; deposit(miner, fee) ; deposit(from, receipt.valueMoved)
```

- `begin/commit/discard` du `BoxProcessor` sont appelés aux mêmes points que
  ceux du `ContractProcessor` (un seul cycle par bloc, les deux sessions
  committent ensemble).
- `rollbackBlock` : consomme les `BoxReceipt` en ordre inverse (comme les
  `ContractReceipt`) pour inverser les mouvements ledger, puis
  `boxProcessor.revertBlock(height)` restaure les boxes.

### 6.3 Ordre intra-bloc

Les transactions s'exécutent dans l'ordre du bloc (inchangé). Une box créée puis
lue/modifiée dans le même bloc est visible des transactions suivantes — la
session par bloc du `BoxProcessor` garantit le même comportement que le
`SessionContractStore` des contrats.

## 7. Persistance (`lib-persistence`)

Nouvelles column families dans **le même RocksDB que le node store**
(`RocksDbNodeStore`), pour que boxes et chaîne avancent dans le même
`WriteBatch` :

| CF | Clé | Valeur |
|---|---|---|
| `boxes` | `boxId(32)` | box sérialisée (§3.1) |
| `box_owner` | `owner(25) || boxId(32)` | vide — index de scan par propriétaire |
| `box_expiry` | `expiryHeight(8, BE) || boxId(32)` | vide — `expiryHeight = rentPaidHeight + storagePeriodBlocks` ; itération par préfixe croissant = boxes collectables en tête |
| `box_journal` | `height(8, BE)` | liste sérialisée d'entrées d'annulation `(boxId, étatAntérieur | ABSENT)` |

`RocksDbBoxStore implements BoxProcessor` : session en mémoire par bloc
(overlay `Map<boxId, Box|TOMBSTONE>` au-dessus du store, même pattern que
`SessionContractStore`), `commit(height)` écrit la session + le journal dans un
batch, supprime les journaux plus vieux que `maxReorgDepth`. **Le journal est
persisté** (contrairement aux journaux contrats, en mémoire) : un reorg qui suit
un redémarrage doit pouvoir inverser les boxes ; c'est aussi le modèle à suivre
pour durcir les journaux contrats plus tard.

`revertBlock(height)` : applique le journal en ordre inverse (restaure ou
supprime chaque box, recale `box_owner`/`box_expiry`), puis efface le journal.

## 8. Production de blocs (`BlockAssembler`)

Le producteur, après avoir rempli le bloc depuis le mempool :

1. Itère `box_expiry` depuis le début tant que `expiryHeight ≤ H` et que le
   compte < `maxBoxCollectsPerBlock`.
2. Pour chaque box collectable, fabrique une transaction `BOX_COLLECT` signée
   par la clé du mineur, `fee = 0`, nonces séquentiels sur son propre compte.
3. Les insère dans le bloc (elles comptent dans `maxTransactionsPerBlock` et la
   taille de bloc).

La collecte est une **opportunité**, pas une obligation : un bloc sans collecte
est valide. `maxBoxCollectsPerBlock` borne seulement le maximum.

## 9. Mempool

Admission d'un box kind (`MemPool`) :
- validations statiques du §5.3 (payload, gas à 0, tailles) — rejet immédiat ;
- `maxSpend = amount + fee` (au lieu de `amount + gasLimit×gasPrice` des kinds
  contrat) dans le calcul de solde cumulé par expéditeur ;
- `BOX_UPDATE`/`BOX_SPEND` : vérification d'ownership *best effort* contre
  l'état confirmé (rejet des évidents `BOX_NOT_FOUND`/`BOX_NOT_OWNER`) — la
  vérité reste l'exécution du bloc ;
- `BOX_COLLECT` : accepté seulement si la box est déjà expirée à la hauteur
  courante (sinon rejet — les collectes futures n'ont rien à faire dans le
  mempool, le producteur les génère lui-même) ;
- politique locale optionnelle (non-consensus) : `minRelayBoxFee = base +
  perByte × |data|`, configurable, défaut permissif.

## 10. Activation

Un nœud pré-upgrade rejette tout bloc contenant un kind inconnu
(`TransactionKind.fromCode` lève). Le déploiement suit donc le schéma classique :

1. Livrer la version avec `boxActivationHeight = X` (X choisi confortablement
   dans le futur) ; avant X, les box kinds sont invalides même pour les nœuds à
   jour (`BOX_UNAVAILABLE`) — le réseau reste homogène.
2. À X, les nœuds à jour acceptent les box kinds ; les nœuds non mis à jour
   forkent — X doit être annoncé assez tôt. (Testnet : `X = 0`.)

## 11. VM — host function `box_read` (`lib-vm`)

Ajout à l'ABI `env` de `WasmVm` :

```
box_read(idPtr: i32, outPtr: i32, outCap: i32) -> i32
```

- Lit 32 octets à `idPtr` (le box id), résout la box dans la vue de la session
  courante (boxes modifiées plus tôt dans le bloc incluses — déterministe).
- Absente → retourne **-1**, rien n'est écrit.
- Présente → écrit la forme sérialisée §3.1 dans `outPtr` (tronquée à `outCap`,
  pattern des host functions existantes) et retourne la taille totale. Le
  contrat appelle typiquement deux fois (taille puis lecture), ou alloue 4096.
- Gas : `BOX_READ_BASE = 100` + `PER_BYTE × min(taille, outCap)` (aligné sur le
  `dataInputCost` d'Ergo : lire doit rester bon marché).
- `HostState` gagne `Box boxRead(byte[] id)` (défaut : `null`), câblé par
  `PersistentHostState` sur la vue du `BoxProcessor`.

Pattern oracle résultant : l'agent oracle (une adresse) fait `BOX_UPDATE` sur sa
box à chaque tick ; tout contrat consommateur fait `box_read(oracleBoxId)` —
zéro contention, zéro `call_contract`, coût plat.

## 12. API HTTP (`app-node`)

| Endpoint | Réponse |
|---|---|
| `GET /box?id=<hex64>` | `{id, owner, value, createdHeight, rentPaidHeight, expiresAtHeight, sizeBytes, registers: [{type: "BYTES"\|"I64"\|..., hex, string?}]}` ; 404 si absente |
| `GET /boxes?owner=<hex50>&limit=&after=<boxIdHex>` | page d'ids + résumés via l'index `box_owner` (limite dure 100, curseur = dernier id) |
| `GET /boxes/expired?limit=` | boxes collectables (tête de `box_expiry`) — pour les collecteurs et le debugging |

**Événements** : chaque op box réussie émet un événement sur le flux de logs
existant (`/logs`, `/logs/stream` SSE), forme `ContractLog(contract = owner,
topic = "box.created" | "box.updated" | "box.spent" | "box.collected",
data = boxId)`. Les agents réutilisent tel quel leur abonnement SSE actuel ;
les prédicats riches (`/scan`) arrivent en Phase 2.

## 13. Wallet (`app-wallet`)

```
box-create <nodeUrl> <keyfile> <value> [--owner <addr>] [--reg <type>:<valeur>]...
box-update <nodeUrl> <keyfile> <boxId> [--topup <montant>] [--reg <type>:<valeur>]...
box-spend  <nodeUrl> <keyfile> <boxId>
box-show   <nodeUrl> <boxId>
box-list   <nodeUrl> <ownerAddr>
```

`--reg` répétable, ordre = ordre des registres ; `<type>` ∈
`bytes|i64|bool|addr|hash|str`, valeur en hex sauf `i64|bool|str`.

## 14. Plan de tests

**lib-core** (`BoxOpTest`, extension d'`ExecutorTest`)
- create : happy path ; valeur < plancher ; taille > 4096 ; registres non denses /
  tag inconnu / longueur incohérente ; id déjà pris ; gas ≠ 0 ; avant activation.
- update : par owner ; par non-owner ; par personne pour une box owned par
  contrat ; top-up requis quand la taille grandit ; reset du compteur de rente.
- spend : restitution exacte ; non-owner rejeté.
- collect : à `storagePeriod − 1` rejeté (miroir du test « too early » d'Ergo) ;
  prélèvement exact et préservation id/registres/owner ; collecte totale quand
  `value − rent` passe sous le plancher ; plafond par bloc.
- conservation monétaire : invariant `Σ soldes + Σ box.value` constant hors
  émission, vérifié sur des séquences aléatoires d'ops (property test).
- reorg : pop d'un bloc avec create/update/spend/collect → état des boxes et du
  ledger strictement identique à l'état antérieur (comparaison exhaustive) ;
  reorg après redémarrage (journal persisté).

**lib-persistence** — round-trip sérialisation ; cohérence des trois index après
chaque op ; itération `box_expiry` ; pruning des journaux.

**lib-vm** — `box_read` : présente/absente/tronquée ; gas facturé ; lecture
d'une box modifiée plus tôt dans le même bloc.

**app-node** — endpoints (404, pagination, curseur) ; événements SSE ;
`NodeSyncIntegrationTest` étendu : deux nœuds convergent sur un état de boxes à
travers une réorganisation.

**app-wallet** — cycle complet create → show → update → spend contre un nœud.

## 15. Questions ouvertes (à trancher avant implémentation)

1. **Update partiel des registres** — v1 remplace tout ; si les agents mettent à
   jour un registre parmi six à haute fréquence, un opcode « set register i »
   économiserait de la bande passante. Attendre un usage réel.
2. **Transfert d'ownership** (`BOX_TRANSFER`) — utile (vendre un annuaire, migrer
   un agent) et trivial à ajouter ; exclu de la v1 pour garder la surface
   minimale.
3. **Plafond de boxes par owner** — aucun en v1 (le verrouillage de valeur est la
   borne économique). À reconsidérer si l'index owner devient un vecteur d'abus.
4. **Calibration économique** — `minValuePerByte`/`storageFeeFactor`/période à
   valider contre des scénarios d'usage agents (coût d'une mémoire de 1 Ko sur
   3 ans, coût d'un registre d'agents de 10 000 entrées…) avant le gel mainnet.
