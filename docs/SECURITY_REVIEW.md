# Revue de sécurité — surface d'attaque & couverture de tests

Revue des scénarios d'attaque d'une blockchain, confrontée à la couverture de
tests réelle du dépôt. Objectif : identifier les vecteurs dangereux non couverts
et durcir le consensus. Deux bugs ont été trouvés et corrigés (dont un critique).

## Vecteurs couverts (test existant → garde)

| Vecteur d'attaque | Garde | Test |
|---|---|---|
| Double-dépense (même bloc / déjà exécutée) | content-hash sans signature (immune malléabilité Ed25519) | `ExecutorTest.rejectsDuplicateInBlock`, `rejectsAlreadyExecuted` |
| Signature falsifiée / rejouée | vérif Ed25519 + `hashContents` | `ExecutorTest.rejectsTamperedSignature` |
| Usurpation d'expéditeur (`from` ≠ clé) | `PublicAddress.of(key) == from` | `ExecutorTest.rejectsSpoofedSender` |
| Rejeu inter-chaînes | `chainId` dans la préimage signée | `ConsensusModelTest.signatureCoversChainIdAndNonce`, `ExecutorTest.rejectsWrongChainId` |
| Rejeu / gap de nonce | nonce de compte strictement séquentiel | `ChainEngineTest.enforcesAccountNonceSequence` |
| Récompense de minage gonflée | coinbase == `miningReward(height)`, une seule | `ExecutorTest.rejectsWrongReward`, `rejectsDuplicateCoinbase` |
| Solde insuffisant | arithmétique vérifiée + rollback transactionnel | `ExecutorTest.insufficientBalanceRollsBackTheWholeBlock` |
| PoW invalide | `verifyNonce` (Pufferfish2, bits de difficulté) | `ChainEngineTest.rejectsBadMerkleAndBadPow` |
| Chaînage rompu (`lastBlockHash`) | vérifié dès le bloc 2 | `ChainEngineTest.rejectsBrokenChaining` |
| Manipulation d'horodatage | median-time-past, borne futur, `minBlockTime` | `MinBlockTimeTest.*`, `ChainEngineTest.rejectsBadTimestamps` |
| Manipulation de difficulté | recalcul déterministe + step borné/clampé | `DifficultyAdjustmentTest.stepIsBoundedAgainstTimestampManipulation` |
| Reorg profond (réécriture d'historique) | fenêtre de finalité `maxReorgDepth` | `HardeningTest.reorgDeeperThanFinalityWindowIsRefused` |
| Peer menteur (« claimed-heavy, proved-light ») | pré-validation stateless avant tout pop | `ChainSynchronizerTest.lyingPeerDoesNotCorruptLocalState`, `HardeningTest.claimedButUnprovenWork...` |
| Mauvais réseau (genesis divergent) | rejet + ban | `ChainSynchronizerTest.incompatibleGenesisIsRejected` |
| Flood de blocs / requêtes | `minBlockTime` consensus + rate limiter borné | `BLOCK_RATE_SECURITY.md`, `RateLimiterTest` |
| Pair abusif | ban-score par host, borné | `PeerBanListTest` |

## Bugs trouvés & corrigés dans cette revue

### 1. CRITIQUE — Montant / fee négatif : création de monnaie et vol

`TransactionAmount` était un `record TransactionAmount(long amount)` sans aucune
validation de signe, et **aucune garde dans l'Executor** (chemin consensus). Une
transaction signée avec `amount = -1000` :

- `withdraw(sender, -1000)` → `solde − (−1000) = solde + 1000` : **l'expéditeur
  crée de la monnaie** ;
- `deposit(destinataire, -1000)` → solde du destinataire **rendu négatif** (vol /
  destruction).

Reachable directement via `/submit` (contourne le mempool) ou un bloc de pair.
Conservation de la monnaie totalement rompue.

**Correctif** : rejet de tout `amount < 0` ou `fee < 0` dans `Executor` (passe 1,
consensus-critique) **et** à l'admission `MemPool` (défense en profondeur). Les
montants sont conceptuellement non signés, donc toute valeur `long` négative
(bit de poids fort inclus) est illégale. Nouveau statut
`INVALID_TRANSACTION_AMOUNT`.
Tests : `ExecutorTest.rejectsNegativeAmountThatWouldMintMoney`,
`rejectsNegativeFeeThatWouldMintMoney`,
`MemPoolTest.rejectsNegativeAmountAndFeeAtAdmission`,
`ChainEngineTest.rejectsNegativeAmountThroughFullConsensusPath`.

### 2. ÉLEVÉ — Overflow de dépôt : mutation partielle non rollback

Le ledger protège l'underflow (`subtract` → `LedgerException`, attrapée →
rollback), mais `add` utilise `Math.addExact` qui lève `ArithmeticException` —
**non attrapée** par le `catch (LedgerException)` de l'Executor. Un dépôt qui
déborde le solde 64 bits (atteignable via un solde de snapshot proche de
`Long.MAX`) laissait le ledger **partiellement muté** (expéditeur déjà débité) et
propageait l'exception hors de `addBlock`.

**Correctif** : `catch (ArithmeticException)` → rollback complet + statut
`BALANCE_OVERFLOW`. Le jumeau overflow du garde-fou underflow existant.
Test : `ExecutorTest.depositOverflowRollsBackCleanlyInsteadOfCorruptingState`.

## Risques résiduels — traités

### 3. DoS de parse `/total_work` (et bodies non bornés) — CORRIGÉ

`HttpPeerSource` lisait la réponse du pair sans cap de taille : un pair
malveillant pouvait renvoyer une chaîne `totalWork` énorme (parse `BigInteger`
en O(n²) = DoS CPU) ou un flux `/sync` illimité (DoS mémoire), avant d'être
banni. Chaque endpoint a désormais un plafond (`readBounded`, lecture en flux
qui avorte au-delà du cap) : 4 Ko pour les scalaires (hauteur, travail total),
1 Mo pour un bloc JSON, et pour `/sync` une borne dérivée des maxima de consensus
(`BLOCKS_PER_FETCH` blocs pleins). Une réponse valide n'est jamais rejetée ; une
réponse hostile est finie.
Test : `HttpPeerSourceTest.oversizedTotalWorkIsRejectedNotParsed`.

### 4. Ordre des transactions non authentifié par le merkle — CORRIGÉ

L'arbre de Merkle triait les transactions par hash, donc la racine n'engageait
que l'*ensemble*, pas l'*ordre*. Deux blocs `[t0,t1]` et `[t1,t0]` partageaient
racine et hash — donc la même PoW — alors que la validation des nonces dépend de
l'ordre : un nœud pouvait accepter ou rejeter le même hash selon l'ordre reçu
(split de consensus / griefing). `MerkleTree.setItems` **préserve désormais
l'ordre d'insertion** : tout réordonnancement produit un hash différent. La
duplication de la dernière tx (CVE-2012-2459) reste bloquée par la déduplication
par content-hash de l'Executor.
Test : `MerkleTreeTest.rootCommitsToTransactionOrder`.

### 5. Flood mempool mono-compte — CORRIGÉ

Seule une borne globale existait ; un expéditeur unique pouvait squatter tout le
pool avec des milliers de nonces en attente. Ajout d'un plafond **par
expéditeur** (`maxPerSender`, défaut 1024) : la borne globale protège la mémoire,
celle-ci protège l'équité entre comptes.
Test : `MemPoolTest.enforcesPerSenderCapSoOneAccountCannotFloodThePool`.

### 6. Maturité de coinbase — DÉCISION : non applicable au modèle par soldes

La maturité de coinbase (interdire la dépense d'une récompense avant N blocs)
est un concept **UTXO** : il suppose de tracer la provenance de chaque pièce.
Rhizome (comme Pandanite) utilise un ledger **par soldes** où la récompense
devient immédiatement fongible avec le reste du solde du mineur — il n'existe pas
de « pièce de coinbase » distincte à faire mûrir. Le risque réel (dépenser une
récompense qu'un reorg rendrait orpheline) est couvert par la **fenêtre de
finalité** `maxReorgDepth` : au-delà, l'historique ne peut plus être réécrit.
Implémenter une maturité imposerait un modèle de provenance étranger à
l'architecture ; décision de ne pas le faire, protection assurée par la finalité.

## Résultat

Suite complète : **164 tests verts** (+7 tests de sécurité au total). Les bugs
sont corrigés et prouvés par des tests qui échouent sans le correctif ; tous les
risques résiduels identifiés sont traités (corrigés ou décidés).
