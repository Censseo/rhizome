# Débit de blocs : ce qui le limite vraiment, et comment viser « 1 bloc/s »

> Analyse de la question : peut-on garantir qu'un seul bloc par seconde soit
> validé par le réseau, et empêcher un nœud modifié de flooder des milliers de
> blocs valides pour « voler » la chaîne ?

## 0. Le constat de départ est correct

Le cadencement du producteur (`BlockProducer.targetIntervalMs`) est de la
**politesse locale, pas de la sécurité**. C'est un `Thread.sleep` dans la boucle
de minage d'un nœud honnête. Un nœud modifié le retire en une ligne. Il n'a
jamais eu vocation à protéger le réseau — il existe uniquement pour qu'en
difficulté triviale (tests), un nœud ne parte pas en vrille. **Aucune règle de
débit ne doit vivre dans le producteur ; elle doit vivre dans la validation,
que tous les nœuds appliquent.**

La vraie question est donc : *dans les règles de consensus, qu'est-ce qui borne
le débit de blocs valides ?*

---

## 1. Ce qui limite le débit aujourd'hui

### 1.1 La preuve de travail EST le rate-limiter (mais un rate-limiter mou)
Produire un bloc valide exige un nonce tel que `hash(header‖nonce)` ait
`difficulty` bits de zéro en tête → en moyenne **2^difficulty évaluations
Pufferfish2**, chacune coûteuse (mémoire-dure). À hashrate réseau `H`
(hashes/s), le temps moyen par bloc est `2^difficulty / H`.

C'est un rate-limiter **probabiliste et relatif** :
- **Probabiliste** : les blocs suivent un processus de Poisson. On ne peut
  jamais garantir un intervalle *exact*, seulement une *moyenne*. (Bitcoin
  « 10 min » est une moyenne ; l'écart-type est ~10 min.)
- **Relatif au hashrate** : si un attaquant apporte 10× le hashrate, il produit
  10× plus de blocs — jusqu'au prochain réajustement.

### 1.2 Le réajustement de difficulté vise la moyenne — mais réagit lentement
`DifficultyAdjustment` vise `desiredBlockTimeSec` (90 s aujourd'hui) sur une
fenêtre de `difficultyLookback` (100 blocs), avec un pas borné à ±4 bits.
Entre deux réajustements, **la difficulté est figée pendant 100 blocs**.

Conséquence concrète : un attaquant qui amène soudainement un gros hashrate
produit les ~100 blocs suivants trop vite *avant* que la difficulté ne monte.
Il ne vole pas la chaîne, mais il crée un **burst** — et à intervalle cible
court (1 s), 100 blocs de burst, c'est beaucoup.

### 1.3 Le cadencement du producteur ne limite rien au niveau réseau
Comme dit au §0 : contournable. À retirer de l'équation de sécurité.

**Donc, en l'état, RIEN dans le consensus ne borne le débit bloc-à-bloc.** La
seule borne est la PoW moyenne + un réajustement lent. C'est exactement le trou
que pointe la question.

---

## 2. Peut-on garantir « 1 bloc/s validé par le réseau » ?

Il faut séparer deux garanties très différentes :

| Garantie | En PoW pur ? | Comment |
|---|---|---|
| **Au plus** 1 bloc/s (borne haute, anti-flood) | **OUI** | règle de consensus de temps minimum entre blocs |
| **Au moins** 1 bloc/s (borne basse, rythme régulier) | **NON** | impossible : personne ne peut être *forcé* à miner ; nécessite des slots à producteur désigné (PoA/PoS) |

La bonne nouvelle : **l'inquiétude exprimée (le flood) porte sur la borne haute,
et celle-là est garantissable côté consensus.**

### La brique manquante : `minBlockTime` comme règle de consensus
Rejeter *à la validation* (dans `ChainEngine.addBlock`, appliqué par tous les
nœuds) tout bloc dont le timestamp est trop proche de son parent :

```
si block.timestamp < parent.timestamp + minBlockTimeSec  →  REJET
```

Combiné à la borne future déjà présente
(`block.timestamp ≤ now + maxFutureBlockTimeSec`), cela impose un **débit
soutenu maximal réel** :

- Un mineur peut au plus produire des blocs dont les timestamps vont de `now` à
  `now + maxFutureBlockTimeSec`, espacés d'au moins `minBlockTimeSec`.
- Soit au plus `maxFutureBlockTimeSec / minBlockTimeSec` blocs « en avance »,
  **puis il doit attendre que le temps réel rattrape.**

Exemple avec `minBlockTime = 1 s` et `maxFuture = 15 s` : un attaquant, **même
avec 100 % du hashrate**, ne peut soutenir plus de **1 bloc/s** ; il peut au
plus « prendre 15 s d'avance » une fois, jamais accélérer durablement. Ses blocs
« trop rapprochés » sont rejetés par chaque nœud honnête — un nœud modifié ne
change rien à ce que *les autres* acceptent.

C'est la différence de nature avec le cadencement producteur : **le producteur
demande gentiment ; `minBlockTime` fait rejeter par le réseau.**

---

## 3. Les vrais vecteurs d'attaque sur notre code actuel

Par ordre d'importance pour l'objectif « 1 bloc/s sûr » :

### 3.1 [À corriger] Pas de temps minimum entre blocs
Analysé au §2. **C'est la correction centrale.** Sans elle, l'anti-flood repose
uniquement sur la PoW moyenne, contournable en burst.

### 3.2 [À corriger] Borne future trop large pour une cible courte
`maxFutureBlockTimeSec = 120` s. Avec `minBlockTime = 1 s`, ça autorise 120
blocs d'avance d'un coup. Pour une cible de 1 s, resserrer à ~10–15 s.

### 3.3 [À durcir] Manipulation de la difficulté par les timestamps (time-warp)
`computeDifficultyFromChain` mesure la durée d'une fenêtre via
`timestamp[fin] − timestamp[début]`. Un mineur majoritaire qui pose des
timestamps qui avancent lentement fait *croire* que les blocs sont lents → la
difficulté baisse → il mine encore plus vite. Nos protections actuelles
(median-time-past en borne basse, pas borné à ±4 bits) atténuent mais ne
suppriment pas l'attaque. `minBlockTime` la referme largement : la durée d'une
fenêtre de `N` blocs est alors forcément ≥ `N × minBlockTime`, donc la
difficulté ne peut pas être tirée arbitrairement vers le bas.

### 3.4 [À revoir] Réajustement lent + plancher de difficulté bas
- Fenêtre de 100 blocs : à cible 1 s, c'est 100 s de latence de réaction. Une
  fenêtre plus courte (p. ex. 20–60) réagit plus vite à un afflux de hashrate.
- `minDifficulty = 6` → 2^6 = 64 essais/bloc au plancher : trivial. Ce plancher
  n'a de sens qu'en test ; en mainnet il doit être calibré pour que
  `2^minDifficulty` reste coûteux face à un GPU isolé.

### 3.5 [Irréductible] L'attaque 51 %
Si un attaquant détient la majorité du hashrate, il peut réécrire la chaîne
récente (double-dépense, censure) — **quelle que soit la conception PoW.** C'est
le modèle de sécurité de tout PoW, pas un bug. `minBlockTime` a toutefois un
effet notable : il empêche l'attaquant 51 % d'**accélérer** sa chaîne (ses blocs
trop rapprochés sont rejetés), donc de « distancer » le réseau honnête en débit.
Il reste limité par le temps réel comme tout le monde.

### 3.6 [Annexe réseau] DoS de reorg par travail annoncé
`ChainSynchronizer.syncFrom` décide de tenter un reorg sur la foi du
`totalWork` *annoncé* par le pair (re-vérifié seulement après téléchargement).
Un pair peut mentir pour nous faire télécharger/pop/rollback inutilement. Non
lié au débit de blocs, mais à border côté couche P2P (plafonner l'effort par
pair, bannir les menteurs).

---

## 4. Recommandation

### 4.1 Court terme — rendre le débit *sûr* (consensus, pas producteur)
1. **`minBlockTimeSec` en règle de consensus** dans `ChainEngine.addBlock` :
   rejeter `block.timestamp < parent.timestamp + minBlockTimeSec`. Nouveau code
   d'erreur `BLOCK_TIMESTAMP_TOO_CLOSE`. C'est la correction qui répond
   directement à l'inquiétude « flood de blocs valides ».
2. **Resserrer `maxFutureBlockTimeSec`** en cohérence avec la cible (≈ 10–15 s
   pour 1 s).
3. **Reléguer le cadencement producteur au rang d'optimisation** : ne plus le
   présenter comme une limite ; il sert juste à ne pas gâcher de PoW sur des
   blocs qui seraient de toute façon rejetés (timestamp trop tôt).

Effet : borne haute **1 bloc/s garantie par le réseau**, y compris contre un
mineur majoritaire. Reste probabiliste en dessous (rythme réel = hashrate vs
difficulté), ce qui est le comportement attendu d'une chaîne PoW.

### 4.2 Moyen terme — rendre le rythme *régulier*
Si l'objectif est un bloc **exactement** chaque seconde (borne basse *et*
haute), le PoW pur ne suffit pas — c'est mathématiquement hors de portée d'un
processus de Poisson. Deux options, qui changent le modèle de confiance :

- **Slots temporels à producteur désigné (PoA/PoS)** : le temps est découpé en
  slots de 1 s ; à chaque slot, un producteur élu (par enjeu ou par autorisation)
  a le droit de signer *un* bloc. Rythme régulier garanti, mais on quitte le
  « tout le monde peut miner » du PoW.
- **Hybride PoW + slots** : PoW pour l'anti-Sybil et le fork-choice, plus une
  règle de slot (`minBlockTime` = taille de slot) pour le plafond. C'est
  l'évolution naturelle du §4.1 et le meilleur compromis pour garder l'esprit
  Pandanite tout en cadençant dur.

### 4.3 Ce qu'il faut acter
- Le fork-choice par **travail cumulé** (déjà en place) est correct : le flood
  de blocs ne « vole » rien sans majorité de travail. Le risque réel n'est pas
  le vol par flood, c'est le **burst** (difficulté figée) et la **manipulation
  de difficulté** — tous deux refermés par `minBlockTime`.
- « 1 bloc/s validé par le réseau » est atteignable **comme maximum dur** dès
  §4.1. « 1 bloc/s garanti régulier » nécessite §4.2 (changement de modèle).

---

## 4.4 État : §4.1 implémenté

- `NetworkParameters.minBlockTimeSec` (règle de consensus). `cleanMainnet` :
  `desiredBlockTimeSec=1`, `minBlockTimeSec=1`, `maxFutureBlockTimeSec=15`,
  `difficultyLookback=60`, `minDifficulty=16`. `testnet` garde un profil relâché
  (floor 0) pour les tests à horloge contrôlée.
- `ChainEngine.addBlock` rejette `timestamp < parent.timestamp + minBlockTimeSec`
  → `ExecutionStatus.BLOCK_TIMESTAMP_TOO_CLOSE`. `nextBlockTimestamp` respecte le
  floor pour que les blocs d'un producteur honnête soient valides.
- Le cadencement du producteur est rétrogradé en optimisation (éviter de gâcher
  de la PoW sur des blocs qui seraient rejetés).
- `MinBlockTimeTest` prouve : rejet d'un bloc trop rapproché, et plafond dur du
  nombre de blocs minables « en avance » via la borne future.

Le §4.2 (rythme *régulier* garanti via slots PoA/PoS) reste une évolution
ouverte.

## 5. Résumé en une phrase

Le cadencement du producteur ne protège rien ; la protection contre le flood se
met en **règle de consensus** (`minBlockTime` + borne future resserrée), ce qui
plafonne le débit à 1 bloc/s pour *tout* le monde — mineur majoritaire compris —
tandis que le PoW + travail cumulé continuent d'assurer l'anti-Sybil et le
choix de chaîne ; un rythme *exactement* régulier, lui, exigerait un modèle à
slots (PoA/PoS).
