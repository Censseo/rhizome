# Rhizome — Audit sécurité & exploit blockchain + couverture de tests

Date : 2026-07-22
Périmètre : consensus / PoW / fork-choice, transactions / signatures / ledger / mempool,
tokens & boxes natifs, VM WebAssembly (Chicory 1.7.5), réseau / P2P / API / sérialisation,
crypto, keystore, templates de contrats.
Base : `master` @ `c513963` (branche d'audit `claude/blockchain-security-audit-53sc10`).

## Verdict global

Le code est **très mûr et fortement durci** : cinq passes d'audit antérieures ont fermé
les classes d'attaque classiques (double-dépense, malléabilité de signature, spoofing,
replay cross-chain/nonce, minting par montant négatif, overflow de solde, time-warp,
reorg au-delà de la finalité, DoS réseau élémentaire). La suite de tests est réellement
**adversariale** (401 tests, tous verts) et verrouille la plupart des invariants.

L'audit a néanmoins identifié **2 vulnérabilités de sévérité élevée résiduelles** (toutes
deux des dénis de service par épuisement mémoire, non-forkantes mais provoquant le crash
des nœuds), **quelques problèmes Medium/Low**, et surtout — en réponse directe à la
question — des **lacunes de couverture de tests** précises sur les chemins d'attaque les
plus critiques.

Aucune rupture de consensus Critical ni vol de fonds directement exploitable n'a été trouvé.

## Statut de remédiation (mise à jour)

Tous les findings ont été traités. Chaque correctif est accompagné d'un test verrouillant
la régression (sauf mention contraire).

| Réf | Sévérité | Statut | Correctif |
|---|---|---|---|
| V1 | High | **Corrigé** | Pré-scan des locals sur les octets bruts **avant** `Parser.parse` (`WasmVm.preScanLocals`), bornes par-fonction et par-module. Tests : `WasmLocalsGuardTest`. |
| V2 | High | **Corrigé** | `PeerDiscovery.fetchPeers` lit désormais via `ofInputStream()` + `readBounded` (64 KiB). Test : `PeerDiscoveryBodyBoundTest`. |
| V3 | Medium | **Corrigé** | Réservation tree-wide des locals dans `DepthLimitedInterpreterMachine` (`MAX_TREE_LIVE_LOCALS`), revert déterministe. Tests : `WasmLocalsGuardTest`. |
| V4 | Low/Med | **Corrigé** | `MemPool.addTransaction` vérifie la signature **avant** `makeRoomForParkedSlot`. Test : `MemPoolTest.invalidSignatureNewcomerCannotEvictParkedVictims…`. |
| V5 | Low/Med | **Corrigé** | `restoreBlock` fait confiance aux refs d'oncle déjà validées (pas de dépendance au pool churné) dans les deux synchroniseurs. Test : `BlockUnclesTest.restoreBlockRecoversANephewWhoseUncleIsMissing…`. |
| V6c | Low | **Corrigé** | Confinement d'exception autour de `findCommonAncestor` (→ `PEER_INVALID`) dans les deux synchroniseurs. |
| V6d | Low | **Corrigé** | Checkpoint appliqué dans `HeaderChain.validate`. Test : `HeaderChainTest.rejectsBranchThatViolatesACheckpoint…`. |
| V6e | Low | **Corrigé** | Champ `vote` borné au décodage (`HeaderCodec`, `BlockDto`). Test : `CodecBoundsTest.rejectsOutOfRangeVote`. |
| V6g | Low | **Corrigé** | `Helpers.PDN` rejette négatif / NaN / overflow. Test : `HelpersTest`. |
| V6h | Low | **Corrigé** | `SignatureVerifier.markVerified` re-vérifie (ne peut plus empoisonner le cache). Test : `SignatureVerifierTest.markVerifiedReVerifies…`. |
| V6j | Low | **Corrigé** | `rejectNonDeterministic` capture aussi les conversions `I32_TRUNC_F32`/`_REINTERPRET_F64`. |
| V6b | Low | **Mitigé** | `decodeStreamed` lit en chunks de 512 KiB (facteur quadratique ÷8) ; un vrai fix asymptotique demanderait un préfixe de longueur (changement wire). Tests : `BlockCodecStreamTest`. |
| V6a | Low | **Accepté** | Buffering des chunks de snapshot : reachability limitée aux seeds de confiance ; le sink écrit ledger/nonce en write-through, donc un streaming sûr exigerait un adaptateur transactionnel (rollback). Documenté dans le code. |
| V6f | Low | **Accepté** | Recalcul de difficulté O(hauteur) : optimisation de scalabilité (cache de préfixe), à risque de divergence de déterminisme — hors périmètre d'un correctif sécurité. |
| V6i | Low/Info | **Accepté** | Registre STRING reflété dans le JSON `/box` : pas d'XSS côté nœud (CSP `script-src 'self'` + `nosniff`) ; risque uniquement pour des consommateurs tiers non-échappants. |

---

## 1. Vulnérabilités

### V1 — HIGH — OOM au parse : expansion agrégée non bornée des locals de fonction WASM

**Fichier :** `lib-vm/.../WasmVm.java:337-339` (parse avant garde), `:490-499`
(`rejectOversizedAllocations`), `:107` (`MAX_FUNCTION_LOCALS = 8192`) ;
entrée deploy `WasmContractProcessor.java:94-106` (`validateCode` → `moduleFor` → `Parser.parse`).

**Cause :** le garde `MAX_FUNCTION_LOCALS` s'exécute **après** `Parser.parse(wasmCode)`.
Or, vérifié par désassemblage du bytecode de Chicory 1.7.5
(`Parser.parseCodeSectionLocalTypes`) :

- le **nombre de groupes** de locals est lu en `varUInt32` **sans aucun plafond** ;
- le plafond `50000` (`"too many locals"`) est appliqué **par groupe** seulement ;
- chaque groupe est **aplati de façon eager** : boucle interne `ArrayList.add` par local.

Un module ≤ `MAX_CODE_SIZE` (256 KiB) peut donc déclarer ~65 000 groupes × 50 000 =
**~3 milliards** d'entrées locales, matérialisées dans une `ArrayList` pendant le parse,
**avant** que `localTypes().size() > 8192` ne puisse rejeter le module. Un module de 4 KiB
suffit déjà à forcer des centaines de Mo transitoires.

**Exploit :** une transaction `DEPLOY` (~4 KiB) dont le corps déclare des milliers de
groupes de 50 000 locals. Le gas de deploy est purement dérivé de la longueur du code
(`DEPLOY_BASE + len*DEPLOY_PER_CODE_BYTE`), d'où une asymétrie coût/dégâts massive. Chaque
nœud qui **valide le bloc** contenant ce deploy exécute `Parser.parse` → allocation
multi-Go → `OutOfMemoryError` / crash. Vecteur distant, réseau-wide, répétable. Ne forke
pas le consensus (gros et petits tas convergent vers `reverted`) mais fait tomber les nœuds.

**Correctif suggéré :** pré-scanner les octets bruts de la code-section (somme des tailles
de groupes et nombre de groupes) et rejeter **avant** `Parser.parse`, exactement comme le
fait déjà le garde des tables (`MAX_TOTAL_TABLE_ENTRIES`) — sauf que les tables sont
allouées à l'instanciation (après le garde) alors que les locals sont aplaties au parse.

**Couverture de test : AUCUNE.** Aucun test ne déclare un module à locals nombreux/groupes multiples.

### V2 — HIGH — OOM réseau : corps `/peers` non borné dans la découverte de pairs

**Fichier :** `lib-net/.../PeerDiscovery.java:69-71` (`fetchPeers`).

**Cause :** c'est la **seule** lecture sortante du codebase qui utilise
`HttpResponse.BodyHandlers.ofString()` **sans limite de taille**. Toutes les autres
(`HttpPeerSource`, `NodeHttpClient`) passent par `readBounded(...)` avec un plafond
par-endpoint. Le `MAX_PEX_PER_PEER = 16` est appliqué **après** le buffering et le parse
JSON complet du corps — il ne protège pas.

**Exploit :** un attaquant fait tourner un nœud routable (passe le filtre SSRF mainnet) et
entre dans le registre de la victime via `/add_peer` (non authentifié) ou via le PEX d'un
autre pair. `PeerDiscovery.round()` interroge automatiquement chaque pair connu (~toutes les
10 s) ; l'attaquant répond à `GET /peers` par un corps de plusieurs Go dans la fenêtre du
timeout de 10 s. `ofString()` accumule tout puis matérialise un `String` (2 o/car) → ~2×
l'octet-count en tas → OOM / crash. Non authentifié, automatique, répétable.

**Correctif suggéré :** appliquer à `fetchPeers` (et à `announceTo`) le même
`ofInputStream()` + `readBounded(cap)` que le reste de `lib-net`, avec un plafond de
quelques Kio pour une liste de pairs.

**Couverture de test : AUCUNE.** `PeerDiscoveryTest` ne couvre que le happy-path loopback ;
`HttpPeerSourceTest` prouve la borne sur `/total_work` mais ce client-là n'a jamais reçu la
même protection.

### V3 — MEDIUM — VM : produit locals × profondeur d'appel non mesuré (fork de consensus conditionnel + amplificateur)

**Fichier :** `WasmVm.java:396-423` (`meter`, pas de charge par-activation/locals),
`:107` (`MAX_FUNCTION_LOCALS`), `DepthLimitedInterpreterMachine.java:46`
(`MAX_WASM_CALL_DEPTH = 1024`), normalisation OOM `WasmVm.java:193-214`.

**Cause :** Chicory alloue trois tableaux par activation dimensionnés sur (params+locals)
(`long[] locals`, `ValType[] localTypes`, `int[] localIdx`, ~160 Kio pour 8192 locals). Une
fonction récursive à ≤ 8192 locals (qui passe le garde de deploy) récursant jusqu'au cap
tree-wide de 1024 détient ~1024 × 160 Kio ≈ **164 Mo** de tas vivant, pour ~1024 gas. Ce
produit n'est borné par **aucun budget déterministe tree-wide**, contrairement à la mémoire
linéaire (`TREE_MAX_PAGES`) et aux tables (`MAX_TOTAL_TABLE_ENTRIES`).

**Conséquence :** exactement le raisonnement de fork que l'audit a utilisé pour
`TREE_MAX_PAGES` — un validateur à tas contraint OOM en cours de récursion → normalisé en
`outOfGas(limit)` (gas plein) ; un validateur à gros tas atteint la profondeur 1024 →
`WasmCallDepthExceeded` → `reverted(gas.used())` (gas partiel). `gasUsed` différent →
`gasFee` différent → soldes/état différents → **fork**. Réalisé seulement quand le tas libre
des validateurs encadre ~164 Mo, d'où Medium. Indépendamment : amplificateur de tas
bon marché (~1024 gas → 164 Mo transitoires, répétable).

**Couverture de test : AUCUNE.** Aucun test n'exerce `MAX_FUNCTION_LOCALS`, ni les modules
à locals nombreux, ni les locals sous récursion.

### V4 — LOW/MEDIUM — Mempool : éviction avant vérification de signature (censure gratuite)

**Fichier :** `lib-core/.../mempool/MemPool.java:179-185`, `:268-299`
(`makeRoomForParkedSlot`).

**Cause (confirmée) :** dans `addTransaction`, quand le pool est plein,
`makeRoomForParkedSlot(from, tx)` **retire une victime** (ligne 179) **avant** que
`verifier.verify(transaction)` ne s'exécute (ligne 183). Si la signature est invalide, la
méthode retourne `INVALID_SIGNATURE` **sans restaurer** la victime évincée.

**Exploit :** l'attaquant possède un wallet financé (pour passer `senderExists` /
`confirmedBalance`), non dépensé. Pool plein contenant des transactions honnêtes « parkées »
(nonce futur, état légitime). L'attaquant soumet une transaction à nonce « ready »
(`incomingReady == true`, contourne la comparaison de fee) avec une **signature invalide** :
`makeRoomForParkedSlot` évince la victime « parkée » au fee le plus bas, puis la
vérification échoue → victime perdue, attaquant n'a rien payé et n'a laissé aucune trace
on-chain. Répétable pour évincer toutes les transactions parkées.

**Portée limitée** (d'où Low/Medium) : seules les transactions « fully parked » (à nonce
troué, non-minables *actuellement*) sont évinçables ; le pool doit être plein. C'est du
griefing/censure, non un vol. Le point saillant : une **mutation d'état gratuite et non
authentifiée** (sans signature valide).

**Correctif suggéré :** vérifier la signature **avant** `makeRoomForParkedSlot`, ou
restaurer la victime en cas d'échec de vérification.

**Couverture de test : LACUNE.** `MemPoolTest` n'exerce l'éviction qu'avec des signatures
**valides** ; aucun test ne soumet une signature invalide sur un pool plein et n'assure la
survie de la victime parkée.

### V5 — LOW/MEDIUM — Consensus : flooding de l'orphan-pool → resync fail-loud

**Fichier :** `ChainEngine.java:1062-1094` (`registerOrphan`), cap 256 (`:62`) ;
`ChainSynchronizer.java:253-262` / `HeaderSynchronizer.java:239-252` (`restore()` throw).

`registerOrphan` admet tout sibling à PoW valide dès `difficulty >= minDifficulty`. Un
attaquant peut miner des siblings récents au **coût du plancher** et churner la LRU de 256
slots, évinçant l'orphelin qu'un bloc local référence comme uncle. Si un reorg échoue
ensuite, `restore()` réinsère la branche locale, `validateUncles` rejette (uncle évincé
inconnu), et le code **lève `IllegalStateException` « a full resync is required »**.
Fail-loud intentionnel mais **déclenchable par l'attaquant**, dégradant un nœud vers un
resync complet.

**Couverture de test : LACUNE** — aucun test ne remplit/évince l'orphan-pool ni n'exerce
les chemins `restore()` throw.

### V6 — LOW — Divers (statique, robustesse / cohérence)

| Réf | Fichier | Point |
|---|---|---|
| V6a | `SnapshotBootstrap.java:135-143` | Chunks de snapshot entièrement bufferisés (jusqu'à ~16 Go) **avant** la vérification de racine. Reachability limitée (pairs seed configurés = confiance), mais incohérent avec le chemin header-sync fenêtré. |
| V6b | `BlockCodec.java:121-150` | `decodeStreamed` re-parse quadratiquement chaque bloc partiel (O(taille²/64 Kio)). Borné (4 Mio, hors event-loop, pair ban-scoré). |
| V6c | `HeaderSynchronizer.java:163-172` / `ChainSynchronizer.java:126` | `findCommonAncestor` n'encapsule pas les appels pairs (un pair renvoyant vide/levant une exception propage hors de `syncFrom`), contrairement au reste de la classe. |
| V6d | `ChainEngine.java:245-247` vs `HeaderChain.validate` | Checkpoints appliqués seulement dans `addBlock`, pas dans le gate header-sync ni le reorg base-work (pas un bypass ; latent, map de checkpoints vide en mainnet). |
| V6e | `HeaderCodec`/`BlockDto` `vote` | Champ `vote` non borné au décodage ; déterministe et sûr (hors-plage ignoré), mais entrée non validée atteignant un préimage de hash + un décompte de vote. |
| V6f | `ChainEngine.computeDifficultyFromChain:962-983` | Recalcul de difficulté O(hauteur) à chaque add/pop → sync complet O(hauteur²/lookback). Coût, pas exploit. |
| V6g | `Helpers.PDN(double):10-12` | Cast `(long)(amount*SCALE)` : un double négatif/énorme produit un montant négatif/tronqué. Rejeté en aval, mais footgun côté wallet. |
| V6h | `SignatureVerifier.markVerified:130-134` | Empoisonne le cache verify-once **sans vérifier la signature**. Actuellement sans appelant en `main/` (dead code), mais API publique dangereuse à côté du chemin consensus. À supprimer ou re-vérifier. |
| V6i | `BoxApi.java:113-114` | Contenu de registre STRING (créable par n'importe qui) reflété brut dans le JSON `/box`. Pas d'XSS côté nœud (CSP stricte + `nosniff`) ; risque pour des consommateurs tiers non-échappants. |
| V6j | `WasmVm.java:443-445` | `rejectNonDeterministic` filtre par préfixe `F32/F64` ; `I32_TRUNC_F32_S` / `I32_REINTERPRET_F32` échappent au filtre. Inaccessible aujourd'hui (aucun flottant sur la pile) mais défense-en-profondeur fragile. |

---

## 2. Zones vérifiées et jugées SAINES

- **Invariants monétaires** : `Math.addExact`/`multiplyExact` partout, débits gardés `>= 0`,
  montants/fees négatifs rejetés à l'admission **et** au consensus, `maxSpend` saturant.
- **Signatures / replay** : préimage signée complète (immunité malléabilité Ed25519, S
  canonique BouncyCastle), dédup sur `hashContents` (sans signature), `chainId` + nonce
  strictement croissant dans le préimage, `PublicAddress.of(signingKey) == from` lié à
  l'admission et au consensus.
- **Coinbase / BOX_COLLECT auto-autorisés** : épinglés `from == empty`, `fee == 0`,
  `amount == 0` ; ne peuvent jamais nommer un émetteur financé (le fix « BOX_COLLECT fee
  theft » est intact).
- **Tokens / boxes** : id de token dérivé de `SHA256(from‖nonce‖"rztoken")` + garde
  `TOKEN_ALREADY_EXISTS` (pas de contrefaçon) ; self-transfer court-circuité (pas de
  minting) ; burn garde `fromBal >= amount` ; spend/update gardent `owner == from` ; owner
  jamais réécrit ; dust-floor avec cast `(long)` ; rent sans double-collecte ni overflow.
- **VM** : metering `MEMORY_GROW`/bulk-mem lu **pré-exécution** (listener Chicory avant
  dispatch, vérifié par désassemblage) ; gas saturant sans wrap ; `transfer_value` rejette
  `amount <= 0` / destinataire malformé et borne par `balance - reserved` (pas de création
  de monnaie, pas de réentrance) ; garde de réentrance `stack.contains(contract)` +
  `MAX_CALL_DEPTH = 8` ; rollback/savepoint atomique par frame sur trap/OOG ;
  déterminisme (flottants/SIMD rejetés au deploy, gas de parse chargé hit **et** miss).
- **State root** : SMT à séparation de domaine 0x00/0x01, `verify` fail-closed sur entrées
  malformées, nœuds de longueur fixe validés, racine indépendante de l'ordre d'insertion.
- **Réseau** : SSRF/rebinding épinglé à l'IP résolue à chaque envoi + filtre de routabilité ;
  bornes de décodage sur tous les préfixes de longueur (`CodecBoundsTest`) ; rate-limiting
  pondéré + trois gates agrégés (submit-PoW, readonly-gas, reads) fail-closed ; pas de
  traversée de chemin (allow-list exact-match) ; CSRF same-origin + header non-simple.
- **Keystore wallet** : PBKDF2-HMAC-SHA256 600k itérations (plage validée), AES-256-GCM
  authentifié.

---

## 3. Couverture de tests — évaluation

**État :** 401 tests, 0 échec, 0 skip (`gradle test`). Couverture adversariale forte et
explicite : montants négatifs (admission + consensus complet), overflow de dépôt,
malléabilité/tampering de signature, sender spoofé, nonce périmé, double-coinbase, uncles
forgés/gonflés, timestamps (futur / median-time-past), reorg au-delà de la finalité,
réentrance refusée, budget de gas > solde, corps HTTP surdimensionné, SSRF/hôtes internes,
cap de sous-réseau, éviction de pairs bannis, révocation de session-key, slippage AMM, etc.

**Lacunes prioritaires** (chemin présent en code mais **sans test négatif** — une régression
supprimant le contrôle passerait toute la CI) :

| Priorité | Zone | Lacune |
|---|---|---|
| **P0** | Token/Box | **BOX_SPEND par non-propriétaire** non testé : le contrôle `owner == from` de `spend` libère **toute** la valeur verrouillée. `updateByNonOwnerRejected` existe pour UPDATE ; pas d'équivalent pour SPEND. Régression = drain de n'importe quelle box, tests toujours verts. |
| **P0** | VM | **Modules hostiles** quasi non testés : locals nombreux/groupes multiples (V1), locals×profondeur (V3), rejet flottant/SIMD, budget mémoire-linéaire tree-wide (`memory.grow`), metering bulk-mem (`memory.fill/copy/init`, `table.copy/init`), `transfer_value` négatif/malformé/multi-transfert, lectures mémoire hôte OOB. Les tests existants pilotent des templates Rust **de confiance** (logique métier, pas durcissement VM). |
| **P0** | Réseau | Corps `/peers` hostile (V2) : zéro couverture adversariale sur le chemin vulnérable. |
| P1 | Token | **MINT vers un destinataire ≠ minter** non testé (`to == from` toujours) : une régression créditant `from` passerait. |
| P1 | Mempool | Signature invalide combinée à l'éviction sur pool plein (V4) : survie de la victime non assurée. |
| P1 | Consensus | Éviction de l'orphan-pool → chemin fail-loud `restore()` (V5) ; pair levant/renvoyant vide en probing d'ancêtre (V6c). |
| P2 | Token/Box | Double-spend de box explicite ; burn > solde ; `TOKEN_ALREADY_EXISTS`/`BOX_ALREADY_EXISTS` ; UPDATE au-delà de `maxBoxSizeBytes` ; clauses `fee!=0`/`amount!=0` du garde BOX_COLLECT. |
| P2 | Réseau/codec | Bornes DoS de `SnapshotChunk.decode` (count/keyLen/valLen) ; limites de `BlockCodec.decodeStreamed` ; `TransactionDto` `MAX_DATA` + `feeFlag` non-canonique. |
| P2 | Consensus | Fork-choice avec branche uncle-plus-lourde/base-plus-légère ; checkpoint à travers header-sync ; `vote` hors-plage. |

---

## 4. Recommandations (ordre)

1. **V1** — pré-scanner et borner l'agrégat de locals (nombre de groupes + somme) **avant**
   `Parser.parse` ; ajouter un test de module hostile à locals. (High, non-forkant mais crash réseau.)
2. **V2** — borner la lecture de `/peers` (`readBounded`) ; test de corps hostile. (High.)
3. **V3** — introduire un budget déterministe tree-wide sur locals×profondeur (ou charger du
   gas proportionnel aux locals par activation) ; test locals-sous-récursion. (Medium, fork conditionnel.)
4. **Combler P0/P1 de couverture** — surtout le test négatif **BOX_SPEND non-propriétaire**
   et les tests de modules WASM hostiles ; ce sont les gardes à plus haute valeur laissés sans filet.
5. **V4** — réordonner `MemPool.addTransaction` (vérifier la signature avant toute mutation).
6. **V5/V6** — traiter au fil de l'eau (robustesse/cohérence).

Le résiduel irréductible reste l'attaque **51 %** (borne de profondeur = fenêtre de
finalité), inhérente à toute chaîne PoW.
