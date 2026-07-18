# Un bloc par seconde validé par le réseau : analyse de sécurité et de faisabilité

> Question posée : le cadencement du producteur (`BlockProducer.targetIntervalMs`)
> ressemble à du bricolage local — un nœud modifié pourrait-il flooder des
> milliers de blocs valides et « voler » la blockchain ? Comment obtenir ~1
> bloc/seconde comme **propriété du réseau**, pas comme politesse d'un nœud ?

---

## 1. Le constat de départ est juste

Le cadencement du producteur est **de la politesse locale, pas de la sécurité** :
il empêche *notre* nœud de s'auto-spammer quand la difficulté est triviale
(dev/test), rien d'autre. Un nœud modifié le supprime en une ligne. **Aucune
propriété de sécurité ne doit reposer dessus** — et de fait, aucune ne repose
dessus. La cadence réseau est (et doit être) imposée par trois règles de
consensus, analysées ci-dessous contre notre propre code.

## 2. Ce qui empêche déjà le « vol par flood »

### 2.1 Le fork choice compte le travail, pas les blocs
`ChainSynchronizer` n'adopte une chaîne concurrente que si son **travail cumulé**
(`Σ 2^difficulté`) est strictement supérieur (`ChainEngine.totalWork`). Or le
travail d'un bloc est *payé en hashes réels* : la difficulté affichée est validée
(`INVALID_DIFFICULTY` si ≠ valeur dérivée de l'historique) et le PoW doit la
satisfaire (`INVALID_NONCE` sinon).

Conséquence arithmétique : **10 000 blocs à difficulté 6 = 10 000 × 2⁶ = 640k
hashes ≈ le travail d'UN SEUL bloc à difficulté ~19,3.** Un flood de blocs bon
marché ne pèse rien face à une chaîne honnête à difficulté réelle. Le nombre de
blocs est sans effet sur le fork choice ; seul le total de hashes dépensés
compte. *« Voler la chaîne » exige donc > 50 % du hashrate, flood ou pas flood —
c'est la borne théorique de tout PoW Nakamoto, pas une faiblesse de l'implémentation.*

### 2.2 L'horodatage couple la chaîne au temps réel
Deux règles dans `ChainEngine.addBlock` :
- `timestamp > MTP` (médiane des 11 derniers blocs) → le temps de chaîne est
  strictement croissant ;
- `timestamp ≤ horloge_locale + maxFutureBlockTimeSec` → **le temps de chaîne ne
  peut pas avancer plus vite que le temps réel** (+ dérive bornée).

Un attaquant qui veut miner vite a deux options, toutes deux perdantes :
- **Estampiller à la cadence cible** (1 bloc/s affiché) en minant plus vite en
  réalité → son temps de chaîne dépasse `now + borne` au bout de `borne`
  secondes de flood → **bloqué par la règle du futur**. Avec la borne actuelle
  de 120 s : au plus ~120 blocs d'avance « en rafale », puis throttlé au temps réel.
- **Estampiller serré** (+1 ms par bloc) pour rester sous la borne → le retarget
  voit des blocs 1000× trop rapides → **+4 bits par fenêtre de 100 blocs = coût
  ×16 tous les 100 blocs**. Trajectoire : d=6 → d=26 en 500 blocs (coût ×1M).
  Sur Pufferfish2 (~1-3 ms/hash CPU), même un très gros mineur cale après
  quelques centaines de blocs — chacun *payé* en vrais hashes qui, cf. 2.1, ne
  lui donnent aucun avantage de fork choice.

### 2.3 La difficulté est dérivée, pas déclarée
`computeDifficultyFromChain` recalcule la difficulté attendue depuis les
timestamps stockés — un bloc ne peut ni sous-déclarer (PoW vérifié) ni
sur-déclarer (dérivation vérifiée) son travail. Et la manipulation inverse
(« estampiller lent pour faire baisser la difficulté et miner pas cher ») est
bornée par la même règle du futur : faire semblant d'être lent avance le temps de
chaîne plus vite que le temps réel → plafonné.

**Bilan : le flood de blocs valides est déjà (a) non rentable pour le fork
choice, (b) auto-étranglé par difficulté × temps-réel. Le cadencement du
producteur peut disparaître sans affaiblir le réseau.**

---

## 3. Les vraies failles à corriger (honnêteté sur notre code)

L'analyse révèle trois points faibles réels — aucun n'est le pacing :

### 3.1 [Important] Le synchroniseur applique AVANT de comparer le travail
`ChainSynchronizer.reorg()` : on **pop notre chaîne et on applique toute la
branche du pair** (PoW compris), et seulement *ensuite* on compare le travail
(`worthBeating`). Un pair menteur qui annonce un `totalWork` énorme et sert une
longue chaîne bon-marché-mais-valide nous fait : dérouler notre chaîne, valider
des milliers de PoW, tout re-dérouler. L'état final est correct (restauration
testée), mais c'est un **vecteur de DoS CPU** — précisément la classe d'attaque
que le C++ subissait.
→ **Correctif : sync « headers-first »** (la raison d'être du `header_chain` de
Pandanite) : télécharger d'abord les **en-têtes** (116 octets/bloc), vérifier
chaînage + PoW + difficulté dérivée + **travail cumulé réel** sur les en-têtes
seuls, et ne télécharger/appliquer les corps **que si** la branche bat
réellement la nôtre. Coût : quelques Ko par comparaison au lieu d'un reorg complet.

### 3.2 [Important] Pas de profondeur de reorg maximale
Avec le travail cumulé pur, un attaquant à >50 % (ou un très gros mineur
patient) peut réécrire la chaîne **depuis n'importe quelle profondeur**. Les
chaînes matures s'en protègent par une **fenêtre de finalité** : refuser tout
reorg plus profond que N blocs (checkpoint glissant).
→ **Correctif : `maxReorgDepth`** dans `NetworkParameters` (ex. 600 blocs = 10
min à 1 bloc/s), appliqué par le synchroniseur. Trade-off assumé : un nœud
hors-ligne plus longtemps que la fenêtre doit re-bootstrapper depuis une source
de confiance (« weak subjectivity » — standard aujourd'hui).

### 3.3 [Réglage] Les paramètres actuels ne vis