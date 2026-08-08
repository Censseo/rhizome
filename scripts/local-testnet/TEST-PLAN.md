# Plan de test — testnet local (16 nœuds)

> **Campagne 2 (2026-08-07).** La campagne 1 (10 nœuds) est close : ses résultats et les
> 5 correctifs qu'elle a produits sont archivés en fin de document. Ce plan la remplace pour
> le rejeu à plus grande échelle, avec les scénarios qui couvrent les correctifs de revue
> (P1-P5) restés sans validation réseau.

## Objectif

Valider le comportement d'un réseau Rhizome de **16 nœuds sur une seule machine** (loopback) :
convergence, gossip de transactions et de blocs, découverte de pairs (PEX), tolérance aux
pannes, reorgs, reprise après redémarrage — et **rejouer end-to-end la partition** que la
campagne 1 n'a pas pu conclure. Exécutable avec les scripts de `scripts/local-testnet/`.

### Pourquoi 16 et pas 10

1. **Partition en deux camps égaux.** 8 contre 8, deux mineurs chacun : c'est la forme qui
   fabrique l'égalité *stricte* de travail (base ET total) que le départage par tip hash doit
   trancher. À 10 nœuds avec 2 mineurs, une moitié se retrouvait sans mineur et se figeait —
   la scission métastable ne pouvait pas se former à volonté.
2. **PEX à un degré plus réaliste.** À 10 nœuds le maillage se complète (10-12 pairs par
   nœud) et le PEX n'est plus discriminant. À 16, le maillage met plus longtemps à saturer.
3. **Le rejeu de la campagne 1 tenait à 10 nœuds ;** monter à 16 met le compteur de stall,
   les bans par endpoint et le départage sous une charge de sync ~2,5× supérieure (le nombre
   de paires croît en N²).

## Périmètre

- 16 nœuds complets (RocksDB), réseau `devnet` (PoW SHA256 à faible difficulté, cible réelle
  5 s — voir `NetworkParameters.devnet()` ; ne pas remplacer par `testnet` pacingé, la
  difficulté s'emballerait, cf. README).
- 4 mineurs (0, 1, 8, 9), 12 nœuds observateurs purs.
- P2P HTTP sur loopback, sans jeton : pas de `RHIZOME_API_TOKEN`/`RHIZOME_PEER_TOKEN` (le
  jeton pair n'est envoyé qu'en `https://`, il est hors sujet sur un testnet local en
  `http://`).

Hors périmètre : chiffrement (https), `RHIZOME_PROTECT_READS`, snap-sync
(`RHIZOME_SYNC=snap`), testnet multi-machines, coût de validation.

## Topologie

| Rôle | Nœuds | Ports | `RHIZOME_MINER` |
|---|---|---|---|
| Mineurs camp A | 0, 1 | 3000, 3001 | adresse dédiée (clé générée par le wallet CLI) |
| Observateurs camp A | 2–7 | 3002–3007 | — |
| Mineurs camp B | 8, 9 | 3008, 3009 | adresse dédiée |
| Observateurs camp B | 10–15 | 3010–3015 | — |

- Peering initial en **anneau** : le nœud `i` se seed sur `(i−1) mod 16` et `(i+1) mod 16`,
  puis `start.sh` exécute un **amorçage PEX** (le nœud 0 sert de hub : tous les autres lui
  sont présentés via `/add_peer`, et réciproquement). Le reste du maillage se découvre par
  PEX à partir de là.

  > L'amorçage n'est pas cosmétique. `GET /peers` retire délibérément les seeds (audit S-6 :
  > un seed peut être l'infrastructure privée d'un opérateur), or dans un anneau *pur*
  > l'intégralité des pairs de chaque nœud sont ses seeds : chacun annonce une liste vide et
  > le maillage reste bloqué à 2 pairs, indéfiniment. Le hub crée les entrées **non-seed**
  > sans lesquelles le PEX ne démarre jamais. Sur un vrai réseau la question ne se pose pas —
  > les seeds publics portent déjà des pairs appris ailleurs.
  >
  > La campagne 1 ne voyait pas le problème parce qu'elle seedait en `127.0.0.1` alors que
  > les nœuds s'annoncent en `localhost` : chaque voisin existait **deux fois** au registre.
  > Ses « 10-12 pairs par nœud » comptaient ce doublon, pas un maillage. Les seeds passent
  > désormais par la forme annoncée (`node_seed_url`), donc `peers` compte des pairs réels.
- Les « camps » A = `0..7` et B = `8..15` n'ont aucune existence en régime normal — c'est
  seulement la coupure utilisée par `start.sh -p` en S7/S15. Chaque camp porte 2 mineurs pour
  que les deux branches avancent à cadence comparable une fois isolées.
- `RHIZOME_ALLOW_PRIVATE_PEERS=true` sur **tous** les nœuds : le filtre SSRF est actif par
  défaut et refuserait les pairs 127.0.0.1 appris via PEX (les seeds `RHIZOME_PEERS`
  contournent le filtre, pas les pairs PEX — `RhizomeNode` L164-175).
- Données : `.testnet/node-<i>` (répertoire propre, nettoyable à volonté).
- Cadence réelle observée : avec le plancher de difficulté 6 de devnet, le PoW SHA256 est
  quasi instantané — les blocs arrivent en continu. Ne jamais poser d'assertion sur une
  cadence absolue de 5 s ; toutes les tolérances sont relatives (écart entre nœuds,
  convergence, unicité du tip).

### Ressources

16 JVMs sur une machine. `start.sh` plafonne chaque nœud à `-Xmx384m` (variable
`RHIZOME_TESTNET_HEAP`) et **refuse de démarrer** si `NODES × heap × 1,4` dépasse la RAM
disponible : une campagne tuée par l'OOM-killer à mi-parcours coûte plus cher que ce
pré-vol. Compter ~9 Go pour 16 nœuds. Réduire `RHIZOME_TESTNET_NODES` si la machine est
chargée — tous les scripts et scénarios suivent la variable.

> **Note machine de dev** : les ports 3000/3002 peuvent être pris par des outils externes
> (opencode, VS Code…). `start.sh` fait un pré-vol qui refuse de lancer les nœuds sur un
> port occupé ; dans ce cas relancer avec `RHIZOME_TESTNET_BASE_PORT=4100` (base libre —
> vérifier que la plage `4100..4115` l'est entièrement, 16 ports cette fois).

## Prérequis

1. JDK 21 (`java -version`), vérifié par le toolchain Gradle.
2. `./gradlew build` — la suite (798 tests) passe avant de tester le réseau.
3. `./gradlew :app-node:installDist :app-wallet:installDist` — un seul build, les 16 nœuds
   tournent en direct via `app-node/build/install/app-node/bin/app-node` (pas 16 daemons
   Gradle). `start.sh` le fait lui-même si le binaire manque (et force `JAVA_HOME` vers un
   JDK 21 trouvé dans sdkman si `JAVA_HOME` pointe sur un JDK > 24 que Gradle refuse).
   **`installDist` est obligatoire après chaque correctif** : la campagne 1 a perdu une
   exécution entière (7 h) faute de l'avoir relancé.
4. Clés mineurs : générées automatiquement par `start.sh` (`--plaintext`, non interactif)
   dans `scripts/local-testnet/keys/`.

## Procédure

```bash
scripts/local-testnet/start.sh              # build + clés + lance les 16 nœuds, attend /stats
scripts/local-testnet/start.sh -n 5         # un seul nœud (redémarrage, S6)
scripts/local-testnet/start.sh -p 0-7       # une moitié isolée, seeds internes seulement (S7)
scripts/local-testnet/status.sh             # une ligne par nœud + écart de hauteur + tips distincts
scripts/local-testnet/monitor.sh            # boucle 2 s, CSV dans .testnet/monitor.csv
scripts/local-testnet/stop.sh               # arrêt propre de tous les nœuds
scripts/local-testnet/stop.sh -p 8-15       # arrêt d'une moitié (partition)
```

Variables d'override : `RHIZOME_TESTNET_NODES` (défaut 16), `RHIZOME_TESTNET_HEAP` (défaut
`384m`), `RHIZOME_TESTNET_DIR` (données, défaut `.testnet/`), `RHIZOME_TESTNET_BASE_PORT`
(défaut 3000).

> **Lancement des nœuds** : `start.sh` passe par `setsid`, de sorte qu'un nœud survit à la
> mort du shell qui l'a lancé. Sans cela, un orchestrateur qui tue le groupe de processus au
> premier plan emporte les nœuds avec lui — c'est ce qui a saboté le rejeu S7 de la
> campagne 1. Ne pas contourner `start.sh` en lançant `app-node` à la main.

Lancer `status.sh` dans un autre terminal dès que le réseau est up : la convergence est
visible en direct.

### Validation de l'outillage (2026-08-07, 6 nœuds)

L'outillage a été rodé sur un réseau réduit avant la campagne, précisément parce que la
campagne 1 a perdu du temps sur des bugs de script. Séquence exécutée de bout en bout :
lancement → convergence (6/6, tip unique) → amorçage PEX (5/5 pairs) → partition 3+3
isolée → détection de scission → reconnexion → convergence sur un tip unique, sans
`degraded`, sans ban. **La séquence S7 complète fonctionne** — c'est elle que la campagne 1
n'avait pas pu conclure.

Quatre défauts d'outillage corrigés au passage :

| Défaut | Effet | Correctif |
|---|---|---|
| `check_ports_free` / `check_memory` portaient sur tout le réseau en mode `-p` | Impossible de relancer une moitié pendant que l'autre tourne — c'est-à-dire S7 | Les deux contrôles prennent la plage réellement lancée |
| `start.sh -p` purgeait **tous** les fichiers pid | La moitié lancée en premier devenait invisible à `stop.sh` et survivait à l'arrêt | Seuls les pid de la plage lancée sont purgés |
| `status.sh` annonçait « tips distincts: 1 » sans dire combien de nœuds répondaient | Un réseau à moitié mort se lisait comme un réseau uni | Le décompte des répondants est imprimé, avec alerte si < N |
| Seeds en `127.0.0.1`, annonces PEX en `localhost` | Chaque pair compté deux fois ; et une fois dédoublonné, le PEX ne s'amorçait plus du tout | `node_seed_url` + étape `bootstrap_pex` (voir Topologie) |

## Critères de réussite généraux

- Tous les nœuds : `degraded == null`, `reorgInProgress == false` en régime stable.
- Les 16 nœuds atteignent la même hauteur **et le même `tipHash`** ; les écarts > 2 blocs
  pendant > 30 s sont des anomalies.
- **`status.sh` affiche `tips distincts: 1`** hors fenêtre de partition. C'est le critère qui
  manquait à la campagne 1 : à hauteur, difficulté et travail total égaux, deux camps sur des
  branches différentes sont indiscernables par tout le reste de `/stats`.
- `syncEclipsed == false` et `syncRoundsWithoutProgress == 0` sur tous les nœuds en régime
  sain (un nœud nourri par gossip ne fait légitimement rien en sync : le compteur mesure
  l'avance de hauteur entre rounds, pas le travail de sync).
- Chaque nœud a ≥ 3 pairs connues (PEX), y compris après l'arrêt du seed d'origine.
- Un bloc miné par un mineur arrive chez tous les pairs en < 10 s (gossip push).

## Scénarios

Notation : hauteur du nœud `i` = `h_i` (via `curl -s http://127.0.0.1:$((3000+i))/stats`).

### S0 — Lancement et convergence
1. `start.sh` ; attendre ~3 min (PEX + premiers blocs ; plus long qu'à 10 nœuds).
2. **Passe si** : 16 réponses `/stats` ; `max(h_i) − min(h_i) ≤ 2` ; `tips distincts: 1` ;
   la hauteur croît en continu.

### S1 — Propagation de transactions (gossip)
1. Sur le nœud 3 : `POST /add_transaction` avec une tx valide (ou le wallet, S8) depuis
   l'adresse d'un mineur riche en solde.
2. **Passe si** : la tx apparaît dans `GET /mempool` des 16 nœuds en < 10 s ; elle est minée
   dans un bloc ; plus aucun nœud ne la garde en mempool.

### S2 — Propagation de blocs (push)
1. Laisser miner pendant 1 min en observant `/stats` des observateurs.
2. **Passe si** : les 12 observateurs suivent à ≤ 2 blocs sans jamais interroger (aucun pull
   déclenché manuellement) ; `avgBlockIntervalMs` cohérent entre observateurs (tous bas et
   proches — pas d'assertion sur la valeur absolue, et inutilisable sous 32 blocs : la
   fenêtre inclut le genesis à timestamp 0, quirk dashboard connu).

### S3 — Découverte de pairs (PEX)
1. `status.sh` à 1, 3 et 5 min de fonctionnement (relever la **progression**, pas un seuil
   unique : à 16 nœuds le maillage met plus longtemps à saturer qu'à 10).
2. **Passe si** : `peers` croît strictement entre les relevés et atteint **15** (maillage
   complet, chaque nœud connaissant les 15 autres) ; aucun nœud ne se liste lui-même ;
   `GET /peers` d'un nœud ne contient **aucun doublon** (`127.0.0.1` et `localhost` du même
   nœud comptent pour deux entrées — c'est ce qui gonflait le chiffre en campagne 1).
   *Mesuré au smoke test 6 nœuds : 5/5 pairs partout en < 90 s après l'amorçage.*

### S4 — Ajout d'un nœud sur réseau vivant (churn)
1. `RHIZOME_TESTNET_NODES=17 scripts/local-testnet/start.sh -n 16` (port 3016).
2. **Passe si** : il rattrape la hauteur commune en < 90 s (headers-first) ; il apparaît dans
   les `peers` des autres ; `degraded == null` ; son `tipHash` rejoint celui du réseau.

### S5 — Panne d'un mineur (rejeu du bug 1)
1. `stop.sh -n 0` (ou `kill -9` du PID de `.testnet/pids/node-0.pid` pour l'arrêt sale).
2. Observer les 15 restants pendant 3 min.
3. **Passe si** : les hauteurs continuent de croître sur **les 15** ; `syncEclipsed` reste
   `false` partout ; `syncRoundsWithoutProgress` reste à 0 ; aucun `degraded` ; aucun ban
   (`syncPeersBanned == 0`) ; `tips distincts: 1`.
   *Régression visée* : c'est le scénario qui figeait le nœud 9 pendant 12 min en campagne 1.

### S6 — Redémarrage d'un nœud (resync)
1. `stop.sh -n 5`, laisser le réseau avancer 1 min, puis `start.sh -n 5`.
2. **Passe si** : il rattrape la hauteur courante < 90 s ; `reorgInProgress` reste `false`
   (il était en retard, pas en divergence) ; son tip rejoint celui du réseau.

### S7 — Partition 8/8 et guérison (le test le plus important)
1. `stop.sh -p 8-15` puis `start.sh -p 8-15` : la moitié B redémarre **seedée uniquement en
   interne**. Faire de même pour A (`stop.sh -p 0-7` ; `start.sh -p 0-7`) afin qu'aucun camp
   ne conserve de seed vers l'autre — un seul seed transverse suffirait à recoller le réseau
   par PEX.
2. Laisser chaque moitié miner 3–5 min. Vérifier avec `status.sh` : `tips distincts: 2`, les
   deux hauteurs proches (2 mineurs par camp).
3. Rétablir : `stop.sh` puis `start.sh` (anneau complet).
4. **Passe si** : le réseau revient à `tips distincts: 1` en < 2 min ; `reorgInProgress` vrai
   pendant la fenêtre puis `false` ; **aucun** `degraded` ; aucun ban ; soldes des mineurs
   cohérents (la récompense de la branche abandonnée annulée via les journaux d'undo) ; les
   transactions de la branche perdue encore valides reviennent en mempool.
   *Régression visée* : bug 2 de la campagne 1 (scission métastable), et le rejeu end-to-end
   que l'environnement avait sabordé.

### S8 — De bout en bout via le wallet CLI
1. `app-wallet balance http://127.0.0.1:3003 <adresse-mineur-0>` ;
   `app-wallet send http://127.0.0.1:3006 <clé> <adresse-cible> 100`.
2. **Passe si** : la tx est minée < 30 s ; `balance` de la cible +100 sur **n'importe quel**
   nœud (nœud 15, l'autre bout de l'ancienne partition) — exécution déterministe partout.

### S9 — Contrat (VM distribué)
1. Depuis le dashboard du nœud 0, déployer le template « token » ; appeler `mint` ; lire via
   `POST /contract/query`.
2. **Passe si** : l'état du contrat lu sur le nœud 15 est identique ; un `call` de mint sur
   2 nœuds de camps différents aboutit au même solde.

### S10 — Supervision et indicateurs opérateur
1. `monitor.sh` pendant toute la campagne.
2. **Passe si** : `degraded` resté `null` sur les 16 ; les transitions
   `reorgInProgress true→false` tracées dans le CSV ; l'alerte « RÉSEAU SCINDÉ » apparaît
   **pendant et seulement pendant** la fenêtre S7 ; aucune hauteur figée > 60 s hors
   partitions volontaires.

---

Les scénarios suivants sont **nouveaux** : ils couvrent les correctifs de revue (P1-P5) que
la campagne 1 n'a jamais exercés en réseau.

### S11 — Pair indisponible en plein reorg : la branche locale survit *(P1)*
1. Provoquer une reorg longue : partition courte (S7 étapes 1-2, ~2 min de divergence), puis
   rebrancher **un seul** nœud du camp perdant (`start.sh -n 8` avec l'anneau complet) de
   sorte qu'il télécharge une branche de plusieurs centaines de blocs.
2. Pendant sa fenêtre de reorg (`reorgInProgress == true` dans `monitor.sh`, ou dès que le
   téléchargement de corps commence dans son log), **tuer son pair source** :
   `stop.sh -n 7` — le nœud 8 perd sa source en plein flux de corps.
3. **Passe si** : le nœud 8 ne reste **pas** tronqué à la hauteur de fork ; sa hauteur et son
   tip sont exactement ceux d'avant la tentative ; `degraded == null` ;
   `reorgInProgress == false` après coup ; le round suivant reprend depuis un autre pair.
   *Ce que ça vérifie* : un échec transport en phase 2 restaure la branche locale au lieu de
   la perdre. Sans le correctif, le nœud gardait un préfixe partiel de la branche du pair et
   perdait la sienne — blocs minés localement compris — sans marqueur `degraded`.

### S12 — Éclipse observable, registre vide *(P2)*
1. Lancer un nœud seul, sans pair : `RHIZOME_TESTNET_NODES=17 start.sh -n 16` avec la moitié
   voisine arrêtée, ou plus simplement lancer un 17ᵉ nœud alors que tout le reste est stoppé.
2. Attendre 3 rounds de sync (~30 s).
3. **Passe si** : `/stats` du nœud renvoie `syncEclipsed: true`, `peersKnown: 0` et
   `syncRoundsWithoutProgress` qui **monte** ; `status.sh` affiche l'alerte « ÉCLIPSÉ » ; un
   WARN `sync eclipsed` figure dans son log.
   *Ce que ça vérifie* : le round sans aucun pair publie ses compteurs au lieu de sortir en
   silence. Avant le correctif, `/stats` gelait sur les valeurs du round précédent — l'état
   le plus dégradé était le seul invisible.

### S13 — Pas d'escalade d'adresse sur hôte partagé *(P3)*
1. Réseau sain. Bannir successivement 3 nœuds voisins par leur endpoint (provoquer 3
   `PEER_INVALID` sur `127.0.0.1:3002`, `:3003`, `:3004` — le plus simple est de pointer un
   nœud vers un faux pair, cf. `RhizomeNodeTest`, ou d'injecter via un round de sync sur un
   port qui sert du JSON invalide).
2. **Passe si** : les 3 endpoints visés sont bannis **et** les 13 autres nœuds de
   `127.0.0.1` restent joignables et syncables ; `syncEclipsed` reste `false` partout.
   *Ce que ça vérifie* : l'escalade adresse est désactivée pour les adresses non routables
   publiquement. Sur un devnet loopback, escalader reviendrait à bannir la machine entière —
   S5 remonté d'un cran.

### S14 — Dashboard pendant une fenêtre de reorg *(P4)*
1. Ouvrir le dashboard d'un nœud qui va reorg (celui de S11 étape 1) et naviguer sur
   l'explorateur de blocs pendant sa fenêtre.
2. **Passe si** : la page ne montre pas d'erreur — elle rejoue la requête après le 503 et
   affiche les blocs une fois la fenêtre fermée. Un 503 prolongé (> ~1,5 s) affiche le
   message du nœud (« reorg in progress; retry shortly »), pas un `HTTP 503` brut.

### S15 — Égalité stricte : départage déterministe *(P5 / fix 4)*
1. Partition 8/8 (S7 étapes 1-2) en laissant les deux camps miner **exactement le même
   temps** — c'est ce que les 2 mineurs par camp rendent possible. Viser des hauteurs égales
   à ±1 et vérifier `totalWork` identique ou très proche sur `/stats` des deux camps.
2. Rebrancher.
3. **Passe si** : la convergence se fait en **un round de sync** (≤ ~10 s après le premier
   contact, pas en plusieurs minutes) ; un seul camp reorg (l'autre garde son tip) ; le camp
   gagnant est celui dont le `tipHash` est **lexicographiquement le plus petit** — vérifiable
   en relevant les deux tips avant de rebrancher ; aucune oscillation ensuite
   (`tips distincts: 1` stable, `reorgInProgress` ne se rouvre pas).
   *Ce que ça vérifie* : le départage par tip hash. Sans lui, deux camps à cadence égale
   restaient scindés indéfiniment (7 h mesurées en campagne 1) et, passée la fenêtre de
   finalité, le perdant ne pouvait plus jamais rejoindre.

## Supervision & alertes

`monitor.sh` (boucle 2 s) écrit `monitor.csv` : horodatage, nœud, hauteur, **tipHash**,
difficulté, pairs, mempool, `avgBlockIntervalMs`, `reorgInProgress`, `degraded`,
`syncRoundsWithoutProgress`, `syncPeersBanned`, `syncEclipsed`. Avertir immédiatement si :

- `degraded` ≠ `null` (barrière dure : le nœud refuse tout nouveau bloc tip et cesse de
  miner — README « Node health signals ») ;
- `syncEclipsed == true` (aucune source de sync ce round) ;
- `syncRoundsWithoutProgress ≥ 6` (~1 min sans avance de hauteur) ;
- **plus d'un `tipHash` distinct** hors fenêtre de partition — l'alerte « RÉSEAU SCINDÉ » ;
- `reorgInProgress` ouvert > 5 min ;
- `peers == 0` sur un nœud ;
- un écart de hauteur > 5 blocs persistant hors S7.

## Arrêt et nettoyage

```bash
scripts/local-testnet/stop.sh
rm -rf .testnet          # données RocksDB + logs + CSV + pids
```

Un arrêt sale (`kill -9`) est testé une fois : au redémarrage, les spools de snapshot
(`$RHIZOME_DATA/snapshots`) sont balayés et l'état relu sans corruption — signaler tout
`degraded` ou refus de démarrage.

## Journal de résultats — campagne 2

Campagne exécutée le 2026-08-08 (base 4200 — ports 3000/4100 pris par des outils de dev), 16
nœuds devnet, 4 mineurs (0, 1, 8, 9), binaire avec le correctif INVALID_UNCLES (voir ci-dessous).
Deux exécutions ont été perdues avant le réseau stable : la première sur le binaire d'origine
(le bug INVALID_UNCLES a figé un cluster du camp A et scindé le réseau au-delà de la finalité —
c'est ce bug que la campagne a découvert), la seconde à cause d'une ré-exécution différée d'une
commande timeoutée par l'environnement (qui a relancé la séquence S7 en arrière-plan et, plus
tard, purgé les données en direct). La campagne finale s'est déroulée de bout en bout sur le
réseau reconstruit.

| Scénario | Résultat | Détail |
|---|---|---|
| S0 Lancement/convergence | **PASS** | 16/16 up ; convergence (écart=0, tip unique) en ~4 min sur le binaire corrigé ; l'ancien binaire churnait 10+ min (4 mineurs au plancher de difficulté, blocs ~2,5 s) |
| S1 Gossip de transactions | **PASS** | Tx minée avant toute lecture mempool possible (< 0,3 s — cadence ~2,5 s) ; canonique aux mêmes hauteurs sur 16/16 ; mempool vide partout |
| S2 Propagation de blocs | **PASS** | 0/96 échantillons observateur à > 2 blocs de retard ; `avgBlockIntervalMs` cohérent ±100 ms |
| S3 PEX | **PASS** | Registre complet 15/15 par nœud, aucun doublon (seeds en forme annoncée), pas d'auto-pairing |
| S4 Churn (17ᵉ nœud) | **PASS** | Rattrape en 17,6 s, 16 pairs, `degraded=null` |
| S5 Panne d'un mineur | **PASS** | 15/15 croissent sans arrêt, aucun `degraded`/éclipse/stall/ban — le rejeu du bug 1 ne reproduit plus rien |
| S6 Redémarrage | **PASS** | Rattrape en 7-14 s selon le moment, `reorgInProgress` reste false (retard, pas divergence) |
| S7 Partition 8/8 | **PASS** | Partition isolée (0 pair hors-camp), 2 branches à hauteurs proches ; guérison en **28,8 s** avec fenêtre de reorg observée, aucun `degraded`, aucun ban ; soldes des mineurs identiques sur les 5 nœuds sondés (journaux d'undo corrects) |
| S8 Wallet E2E | **PASS** | +100 PDN via nœud 3, confirmé +100 sur le nœud 15 |
| S9 Contrat distribué | **PASS** | Token PDN2 miné via nœud 0 : état identique (symbole, supply, createdHeight) sur 0/3/7/8/15, solde 1000 partout |
| S10 Supervision | **PASS partiel** | `degraded` resté `null` sur les 16 pendant toute la campagne ; zéro ban hors scénarios ; alertes scission correctes via status.sh sur les fenêtres S7/S11/S15. Le monitor en arrière-plan meurt dans cet environnement (lancement background instable) — tourne parfaitement au premier plan (213 lignes CSV/70 s, zéro fausse alerte) |
| S11 Pair perdu en reorg (P1) | **PASS** | Watcher 50 ms : reorg détecté à h=200, source (nœud 7) tuée en plein flux ; nœud 12 intact (aucune troncature, `degraded=null`), reprise par un autre pair (nœud 3) et convergence complète sur le camp A |
| S12 Éclipse registre vide (P2) | **PASS** | `syncEclipsed: true`, `peers: 0`, compteur de stall qui monte, WARN « sync eclipsed » au log. Nuance : un nœud seul AVEC seeds n'est pas éclipsé (seeds toujours tentées, jamais « skipped as banned ») — l'éclipse est la forme « registre vide » |
| S13 Hôte partagé sans escalade (P3) | **PASS** | 3 faux pairs 127.0.0.1 bannis (3 frappes PEER_INVALID chacun) ; les 13 vrais pairs du nœud restent joignables, le nœud continue de syncer, aucun autre nœud affecté |
| S14 Dashboard en reorg (P4) | **PASS** | 193 réponses `503 {"error":"reorg in progress; retry shortly"}` pendant la reconnexion S15 — le message du nœud, pas un 503 brut ; endpoints re-servent les blocs après la fenêtre |
| S15 Départage déterministe (P5) | **PASS** | Deux camps à cadence quasi égale (travail à ~1 bloc près) : convergence en **22,8 s** (un round), sans oscillation (27/30 échantillons à tip unique, forks transitoires < 2 s), vainqueur = branche la plus lourde. L'égalité stricte base+total n'a pas été atteinte (gigue de cadence à 2,5 s/bloc) : le départage exact est couvert par les tests unitaires `HeaderSynchronizerTest` (tiebreak) et l'ancien bug-2 (refus 6+ min) ne se reproduit plus |

### Correctif livré — campagne 2 : `INVALID_UNCLES` sur oncle persistant non poolé

**Bug (découvert en S7) :** un nœud qui avait appliqué un bloc référençant des oncles avant un
redémarrage garde les corps d'oncles PERSISTÉS (`addBlock` les écrit) mais son pool en mémoire
est vide. `applyWithUncleFetch`/`prefetchUncles` sautent le fetch quand `orphanBlock(hash)` est
non nul — pool **ou** store — puis la retentative échoue dans `validateUncles`, qui ne consulte
que `orphans.get(u)` : `INVALID_UNCLES` à chaque round → `PEER_INVALID` → +34 ×3 → **ban d'un
pair honnête** (campagne 1, bug 1, même forme : ban → cluster figé → stallement 27+ rounds
sans marqueur `degraded`, ici sur 3 nœuds pendant 16 min, 90 pénalités sur un pair seed).

**Correctif :** `ChainEngine.validateUncles` retombe sur `store.uncleAt(u)` quand le pool manque
(le corps persisté a été pleinement validé à la première application, l'éligibilité est
re-vérifiée contre le contexte vivant). Test de régression
`UncleSyncRegressionTest.syncingNodeHoldingThePersistedUncleItselfCanStillAdoptTheBranch`
(vérifié en échec sans le correctif). Validation réseau : les nœuds 5/6/7 figés ont rejoint le
réseau en 10-40 s après redémarrage sur le binaire corrigé ; la reprise de la campagne s'est
faite sans une seule pénalité sur les partitions/reconnexions suivantes.

**Correctifs d'outillage livrés au passage :** `chaincheck.py` (verdict d'appartenance à une
chaîne — des hauteurs différentes sur la même chaîne ne sont pas une scission), échantillonnage
parallèle dans `status.sh`/`monitor.sh` (le balayage séquentiel à ~2,5 s/bloc fabriquait des
« camps » fantômes suivant l'ordre de scrutation), alerte de scission conditionnée à 3 cycles
consécutifs, `json_get` durci (une réponse inattendue ne tue plus le script sous `set -e`),
log `body apply rejected at height … : <status>` dans `HeaderSynchronizer` (diagnostic qui a
permis de localiser le bug).

**Notes de procédure :** (1) la séquence littérale du plan S7 (« stop B ; start B ; stop A ;
start A ») ne partitionne pas : les registres vivants du camp A fuient vers le camp B pendant
son redémarrage et le PEX recolle le maillage avant que A ne s'arrête. Procédure effective :
**arrêter tout, puis start B, puis start A**. (2) À ~2,5 s/bloc, la fenêtre de finalité
(120 blocs ≈ 5 min) interdit les partitions > ~4 min : au-delà, aucun camp ne peut rejoindre
l'autre (REORG_TOO_DEEP des deux côtés). (3) Les commandes destructives doivent être
exécutées par petits pas vérifiés : une commande timeoutée par l'orchestrateur a été
ré-exécutée en différé (séquence S7 fantôme à 09:56, puis purge des données en direct à 11:55).

---

# Archive — campagne 1 (10 nœuds, 2026-08-06/07)

Conservée pour le contexte : c'est elle qui a produit les correctifs que la campagne 2
rejoue. Preuves dans `/tmp/opencode/split-evidence/`.

## Résultats

Base 4100 (ports 3000/3002 occupés par des outils de dev), 10 nœuds devnet, 2 mineurs (0, 1)
+ mineur d'appoint 5 en S7. `degraded` est resté `null` sur les 10 nœuds.

| Scénario | Résultat | Détail |
|---|---|---|
| S0 Lancement/convergence | **PASS** | 10/10 up, hauteurs identiques en < 60 s ; écart max transitoire 5 blocs pendant 14 s au démarrage |
| S1 Gossip de transactions | **PASS** | Tx acceptée sur le nœud 3, présente dans le mempool des 10 nœuds en < 1 s, minée à t+4 s |
| S2 Propagation de blocs | **PASS** | Les 8 observateurs suivent à ≤ 2 blocs ; `avgBlockIntervalMs` inutilisable < 32 blocs (genesis à timestamp 0 dans la fenêtre — quirk dashboard) |
| S3 PEX | **PASS** | Maillage complet : 10-12 pairs par nœud, pas d'auto-pairing |
| S4 Churn (11ᵉ nœud) | **PASS** | Rattrape la hauteur en 25 s, 11 pairs |
| S5 Panne du seed | **ÉCHEC** | Bug 1 ci-dessous |
| S6 Redémarrage | **PASS** | Resync + reorg propre en < 30 s |
| S7 Partition/reorg | **ÉCHEC partiel** | Bug 2 ci-dessous ; la reorg elle-même fonctionne (~250 blocs, soldes identiques, journaux d'undo corrects) |
| S8 Wallet E2E | **PASS** | 50 PDN via nœud 3, confirmé +50 sur le nœud 8 |
| S9 Contrat distribué | **PASS** | Counter déployé via nœud 2, état identique sur 1/5/8 |
| S10 Supervision | **PASS partiel** | Fenêtres de reorg jamais observées (250 blocs en < 2 s, plus court que le pas de sondage) |

### Bug 1 — S5 : blocage permanent après un ban « invalid chain »

Le nœud 9 reste **figé à h=39 pendant 12 min** avec 7-9 pairs sains à h=67+,
`degraded=null`, `reorgInProgress=false`. Enchaînement : le nœud 9 synce depuis le nœud 2,
lui-même en reorg → chaîne incohérente → `PEER_INVALID` → `PENALTY_INVALID = 100 =
BAN_THRESHOLD` → ban 1 h à la première frappe. Le ban étant keyé par IP, il emportait tous
les ports de `localhost`. Après le ban, plus aucune activité de sync et aucun log.

### Bug 2 — S7 : égalité de travail de base = scission métastable

Partition 5+5, mesh ré-uni (12 pairs partout) mais la moitié B **refuse de reorg** vers la
branche A pendant 6+ min, écart de travail constant à 64. Cause : les deux branches ont
exactement le même travail de base ; l'unique avantage de A est 1 oncle. La porte
`validated.work() <= localWorkAboveFork(...) → NO_CHANGE` traite l'égalité comme une défaite,
donc le vote GHOST de phase 3 — le seul endroit où le travail d'oncle validé compte — n'est
jamais atteint.

### Défaut 5 (découvert au rejeu) — `REORG_TOO_DEEP` bannissait

Exécution de 7 h : deux camps à cadence égale, base ET total égaux (les oncles se
compensent). Une fois `hauteur − fork > maxReorgDepth`, chaque sync croisée retourne
`REORG_TOO_DEEP` → +25 × 4 = ban 1 h mutuel, **renouvelé à l'heure exacte** (04:57, 05:58,
06:59 dans les logs). Le ban verrouillait la guérison naturelle.

## Correctifs livrés (commits 1-5)

| Fix | Fichiers | Effet |
|---|---|---|
| **1. Round de sync observable** | `RhizomeNode.syncRound`, `NodeService.SyncHealth`, `DashboardApi./stats`, `monitor.sh`/`status.sh` | `/stats` expose `syncRoundsWithoutProgress` (rounds sans progrès de sync **ni avance de hauteur**) et `syncPeersBanned` ; WARN « sync eclipsed » et WARN de stall à 6 rounds |
| **2. Bans par endpoint + escalade adresse** | `PeerBanList`, `PeerRegistry`, `RhizomeNode` (`PENALTY_INVALID=34`, seeds exemptés) | Bannir `localhost:4102` ne bannit plus `:4108` ; la rotation de ports accumule vers un ban d'adresse au seuil escaladé |
| **3. 503 pendant un reorg** | `SyncApi`, `NodeApi`, `PeerUnavailableException` (déplacée en lib-core) | Un nœud en fenêtre de reorg répond 503 + Retry-After ; le pair lit une panne transport (retry, jamais `PEER_INVALID`) |
| **4. Départage du travail égal** | `HeaderSynchronizer`, `ChainSynchronizer`, `HeaderChain` | Égalité de base → descente en phase 3 si le total pair bat le nôtre ; égalité stricte base ET total → départage déterministe par tip hash |
| **5. `REORG_TOO_DEEP` sans ban** | `RhizomeNode` | Une branche au-delà de l'horizon de finalité n'est pas une malveillance : plus aucun score de ban |

## Correctifs de revue (P1-P5), postérieurs à la campagne 1

Issus de la revue de code des 5 fixes ci-dessus. **Aucun n'a été exercé en réseau** — c'est
l'objet des scénarios S11-S15.

| # | Défaut | Correctif |
|---|---|---|
| **P1** | `applyBodies` relançait `PeerUnavailableException` depuis l'intérieur de la fenêtre de reorg, court-circuitant `restore()` : chaîne laissée tronquée avec un préfixe partiel de la branche du pair, branche locale perdue, sans marqueur `degraded`. Rendu probable par le fix 3 (les pairs en reorg 503 par conception) et le fix 4 (reorgs fréquents) | `applyAndAdopt` restaure sous `withConsistentView` avant de laisser l'exception remonter ; test de régression vérifié en échec sans le correctif |
| **P1b** | Le re-throw de `fetchRange` faisait perdre un `REORGED` déjà commité (extension best-effort post-reorg) | Extension encadrée : la panne transport n'annule plus le verdict |
| **P2** | `syncRound` sortait avant de publier quoi que ce soit quand le registre était vide — or `penalize` évince, donc « tous bannis » se traduit par « registre vide », le cas le plus dégradé était le seul muet | Publication extraite et appelée sur tous les chemins ; `SyncHealth` gagne `peersKnown` et `eclipsed`, exposés sur `/stats` |
| **P3** | L'escalade d'adresse additionnait des points sans exiger d'endpoints distincts, et s'appliquait aux adresses loopback/RFC1918 : 3 nœuds bannis sur un devnet localhost bannissaient les 16 | Rotation exigée (3 endpoints distincts), adresses non routables publiquement exemptées, decay calibré sur le seuil de chaque table |
| **P3b** | `http://h:80` et `http://h` keyaient deux entrées de ban différentes | Ports par défaut repliés dans toutes les clés |
| **P4** | Le dashboard affichait une erreur brute pendant chaque fenêtre de reorg | `api()` rejoue une fois après un 503 (attente plafonnée à 1,5 s) |
| **P5** | Le départage par tip hash n'était documenté nulle part | WHITEPAPER §3.7 : règle, portée (toute égalité stricte, y compris les courses à 1 bloc), coût, et limite au-delà de la fenêtre d'en-têtes |
| — | `/stats` ne permettait pas de distinguer un réseau uni d'un réseau scindé à cadence égale | `tipHash` ajouté à `/stats` ; `status.sh`/`monitor.sh` alertent sur plus d'un tip distinct |

Le smoke test 6 nœuds a reproduit exactement la forme aveugle de la campagne 1 : les deux
camps à **h=102, écart de hauteur 0**, et deux tips distincts. Sans `tipHash` dans `/stats`,
aucun indicateur n'aurait bougé.
