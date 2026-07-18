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

## Risques résiduels (recommandations, non bloquants)

- **DoS de parse `/total_work`** : `HttpPeerSource` lit la chaîne `totalWork` du
  pair sans cap de taille ; un pair malveillant peut renvoyer une chaîne énorme
  (parse `BigInteger` en O(n²) = DoS CPU) avant d'être banni. → capper la taille
  de réponse côté client.
- **Ordre des transactions non authentifié par le merkle** (arbre trié par hash) :
  le consensus reste cohérent (tous les nœuds voient le même ordre sérialisé),
  mais la racine n'engage pas l'ordre. Non exploitable pour l'acceptation.
- **Pas de maturité de coinbase** : la récompense est dépensable immédiatement ;
  un reorg (borné par la finalité) pourrait invalider une dépense de récompense
  orpheline. Acceptable vu `maxReorgDepth`.
- **Flood mempool mono-compte** : borne globale de taille présente ; un cap
  par-expéditeur limiterait le squat de nonces.

## Résultat

Suite complète : **162 tests verts** (+5 tests de sécurité ajoutés). Les deux
bugs sont corrigés et prouvés par des tests qui échouent sans le correctif.
