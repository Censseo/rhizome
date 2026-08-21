# Plan de test — testnet local (30 nœuds natifs)

> **Campagne 5 (2026-08-20).** Les campagnes 1 (10 nœuds), 2 (16 nœuds), 3 (30 nœuds, première
> campagne native) et 4 (S11-S15) sont closes ; leurs résultats et les correctifs qu'elles ont
> produits sont archivés en fin de document. Cette campagne rejoue S0-S15 à l'identique (30 nœuds
> natifs, 10 mineurs, charge continue) et l'ancre en plus dans la revue adverse
> (`docs/adversarial/spec.md`, passée de 0 à 171 scénarios catalogués depuis la campagne 4) : S16
> et S17, nouveaux, poussent en réseau réel deux fermetures récentes du catalogue — NET-11 (score
> de ban contre un pair réellement confirmé, pas seulement injoignable) et API-13
> (`RHIZOME_API_TOKEN` sur un déploiement multi-nœuds, jusqu'ici hors périmètre de ce plan).
> REORG-11/12 (minage égoïste, grinding du départage) n'a délibérément aucun nouveau scénario
> réseau — voir le journal de cette campagne pour le raisonnement. Elle a aussi débusqué un défaut
> d'outillage (mort silencieuse de `sim-contract.sh start` juste après le genesis) et une dérive
> doc/code sur `RHIZOME_TESTNET_BLOCK_MS`, et reproduit à l'identique le conflit de port 3000 déjà
> documenté par la campagne 4.

## Objectif

Valider le comportement d'un réseau Rhizome de **30 nœuds natifs sur une seule machine**
(loopback) sous charge continue : convergence, gossip de transactions et de blocs, découverte
de pairs (PEX), tolérance aux pannes, reorgs, reprise après redémarrage, déterminisme
d'exécution des contrats. Exécutable avec les scripts de `scripts/local-testnet/`.

### Pourquoi le binaire natif

Un nœud JVM plafonné à 384 Mo de tas occupe ~350-400 Mo de RSS ; le même nœud natif en occupe
**75 à 100 Mo**, démarre en quelques dizaines de ms et n'a ni metaspace ni JIT. C'est ce qui
fait tenir 30 nœuds dans ~3 Go au lieu de ~11, et c'est aussi la seule façon de tester le
chemin que produit `./gradlew :app-node:nativeImage` — la métadonnée de reachability, RocksDB
en JNI sous SubstrateVM, l'absence de fallback. Un test réseau sur JVM ne dit rien de ce
binaire-là.

### Pourquoi 30 nœuds et 10 mineurs

1. **Partition en deux camps égaux de 15**, 5 mineurs chacun : la forme qui fabrique l'égalité
   de travail que le départage par tip hash doit trancher (S7/S15), avec assez de mineurs par
   camp pour que les deux branches avancent à cadence comparable.
2. **Les mineurs sont répartis régulièrement sur l'anneau** (indices `k·N/M` = 0, 3, 6, … 27),
   pas groupés : un bloc miné traverse plusieurs sauts de gossip avant d'atteindre le mineur
   suivant, ce qui produit des oncles — donc du travail GHOST réel à valider.
3. **Le PEX ne peut plus saturer** : au-delà de 18 pairs le cap anti-éclipse mord (voir
   ci-dessous), ce qui est un régime que 16 nœuds ne pouvaient pas atteindre.

## Périmètre

- 30 nœuds complets (RocksDB), réseau `devnet` (PoW SHA256 à faible difficulté ; ne pas
  remplacer par `testnet` pacingé, la difficulté s'emballerait, cf. README).
- 10 mineurs (0, 3, 6, 9, 12, 15, 18, 21, 24, 27), 20 nœuds observateurs.
- Deux simulateurs de charge : `sim-tx.sh` (transferts continus entre 8 portefeuilles) et
  `sim-contract.sh` (compteur + token WASM appelés en boucle, état vérifié sur tous les nœuds).
- P2P HTTP sur loopback, sans jeton : pas de `RHIZOME_API_TOKEN`/`RHIZOME_PEER_TOKEN` (le
  jeton pair n'est envoyé qu'en `https://`, il est hors sujet sur un testnet local en `http://`).

Hors périmètre : chiffrement (https), `RHIZOME_PROTECT_READS`, snap-sync (`RHIZOME_SYNC=snap`),
testnet multi-machines, coût de validation.

## Topologie

| Rôle | Nœuds | Ports | `RHIZOME_MINER` |
|---|---|---|---|
| Mineurs camp A | 0, 3, 6, 9, 12 | base+i | adresse dédiée (clé générée par le wallet CLI) |
| Observateurs camp A | le reste de 0–14 | base+i | — |
| Mineurs camp B | 15, 18, 21, 24, 27 | base+i | adresse dédiée |
| Observateurs camp B | le reste de 15–29 | base+i | — |

- Peering initial en **anneau** : le nœud `i` se seed sur `(i−1) mod 30` et `(i+1) mod 30`,
  puis `start.sh` exécute un **amorçage PEX** (le nœud 0 sert de hub : tous les autres lui
  sont présentés via `/add_peer`, et réciproquement). Le reste du maillage se découvre par PEX.

  > L'amorçage n'est pas cosmétique. `GET /peers` retire délibérément les seeds (audit S-6),
  > or dans un anneau *pur* l'intégralité des pairs de chaque nœud sont ses seeds : chacun
  > annonce une liste vide et le maillage reste bloqué à 2 pairs. Le hub crée les entrées
  > **non-seed** sans lesquelles le PEX ne démarre jamais. Les seeds passent par la forme
  > annoncée (`node_seed_url`, `localhost`), sans quoi chaque voisin existe deux fois au
  > registre (`127.0.0.1` ET `localhost`) et `peers` compte des doublons.
- Les « camps » A = `0..14` et B = `15..29` n'ont aucune existence en régime normal — c'est la
  coupure utilisée par `start.sh -p` en S7/S15.
- `RHIZOME_ALLOW_PRIVATE_PEERS=true` sur **tous** les nœuds : le filtre SSRF est actif par
  défaut et refuserait les pairs 127.0.0.1 appris via PEX.
- Données : `.testnet/node-<i>` (répertoire propre, nettoyable à volonté).

### Le maillage sature à 18 pairs, et c'est normal

À 30 nœuds sur loopback, chaque nœud se stabilise à **exactement 18 pairs** et n'ira jamais à
29. `PeerRegistry.MAX_PER_SUBNET = 16` plafonne les pairs *découverts* par bucket de sous-réseau
(/16 en v4) : sur loopback les 29 autres nœuds tombent tous dans le même bucket, donc 16
découverts + 2 seeds = 18. C'est l'armure anti-éclipse qui fonctionne, pas un défaut de PEX —
mais cela invalide le critère « le maillage atteint N−1 » des campagnes précédentes dès que
N > 18. Le bon critère est : **18 pairs partout, sans doublon et sans auto-référence**.

### Cadence de production

Sur devnet la difficulté est collée à son plancher (6) : le PoW est instantané et **ne régule
rien**. Le seul levier est `RHIZOME_BLOCK_INTERVAL_MS`, posé par `start.sh` sur chaque mineur
depuis `RHIZOME_TESTNET_BLOCK_MS` (défaut 25 s, calibré ci-dessous — pas 10 s, valeur qui a
trainé ici jusqu'à la campagne 5 alors que `common.sh` et le reste de cette section pointaient
déjà vers 25 s). Il faut le régler : au défaut devnet (5 s), 10
mineurs produisent ~1 bloc/s, et la fenêtre de finalité (120 blocs, `maxReorgDepth`) ne dure
alors que 2 minutes — moins qu'une partition utile, donc les deux camps finiraient en
`REORG_TOO_DEEP` mutuel et S7/S15 deviendraient intestables.

La cadence agrégée n'est **pas** `intervalle / mineurs` (modèle testé et démenti) : à 25 s sur
10 mineurs le réseau a produit ~9 s/bloc, et une fois coupé en deux camps de 5 mineurs, 12 s
(camp B) à 30 s (camp A) par bloc. C'est un bouton à calibrer par la mesure, pas une formule.
Ne jamais poser d'assertion sur une cadence absolue ; toutes les tolérances sont relatives
(écart entre nœuds, convergence, unicité du tip).

### Ressources

30 processus natifs. `start.sh` plafonne chaque nœud à `-Xmx256m` (variable
`RHIZOME_TESTNET_HEAP` ; l'image native consomme `-Xmx` comme la JVM) et **refuse de démarrer**
si `NODES × heap × 1,15` dépasse la RAM disponible — la marge hors-tas est de 15 % en natif
contre 40 % sur JVM (ni metaspace, ni JIT, ni code cache). Mesuré : **2,2 à 2,9 Go pour 30
nœuds**, soit 75 à 100 Mo par nœud.

> **Note machine de dev** : les ports 3000/3002/3003 et diverses plages hautes peuvent être
> pris par des outils externes. `start.sh` fait un pré-vol qui refuse de lancer les nœuds sur
> un port occupé ; dans ce cas relancer avec `RHIZOME_TESTNET_BASE_PORT=4300` (vérifier que
> la plage `4300..4329` est libre — 4330 en plus pour le churn S4).

## Prérequis

1. Un JDK 25 **GraalVM** comme SDK courant (`sdk use java 25.0.2-graal`), pour que
   `native-image` se résolve depuis le `PATH`. Gradle 9.6.1 tourne sur ce même JDK.
2. `./gradlew build` — la suite passe avant de tester le réseau.
3. `./gradlew :app-node:nativeImage :app-wallet:installDist` — un seul build (~1 min 15 pour
   l'image native), les 30 nœuds tournent en direct via `app-node/build/native/rhizome-node`.
   `start.sh` le fait lui-même si le binaire manque. **À relancer après chaque correctif** :
   la campagne 1 a perdu 7 h faute de l'avoir fait.
   `RHIZOME_TESTNET_NATIVE=0` retombe sur le chemin JVM (`installDist`) pour comparaison.
4. Clés mineurs : générées automatiquement par `start.sh` (`--plaintext`, non interactif) dans
   `scripts/local-testnet/keys/`.

## Procédure

```bash
scripts/local-testnet/start.sh              # build + clés + lance les 30 nœuds, attend /stats
scripts/local-testnet/start.sh -n 5         # un seul nœud (redémarrage, S6)
scripts/local-testnet/start.sh -p 0-14      # une moitié isolée, seeds internes seulement (S7)
scripts/local-testnet/status.sh             # une ligne par nœud + écart de hauteur + tips distincts
scripts/local-testnet/monitor.sh            # boucle 2 s, CSV dans .testnet/monitor.csv
scripts/local-testnet/sim-tx.sh start       # charge : transferts continus (8 portefeuilles)
scripts/local-testnet/sim-contract.sh start # charge : compteur + token WASM en boucle
scripts/local-testnet/sim-contract.sh check # état des contrats comparé sur les 30 nœuds
scripts/local-testnet/stop.sh               # arrêt propre de tous les nœuds
scripts/local-testnet/stop.sh -p 15-29      # arrêt d'une moitié (partition)
```

Variables d'override : `RHIZOME_TESTNET_NODES` (30), `RHIZOME_TESTNET_MINERS` (10),
`RHIZOME_TESTNET_NATIVE` (1), `RHIZOME_TESTNET_HEAP` (`256m`), `RHIZOME_TESTNET_BLOCK_MS`
(25000), `RHIZOME_TESTNET_DIR` (`.testnet/`), `RHIZOME_TESTNET_BASE_PORT` (3000).

> **Lancement des nœuds** : `start.sh` passe par `setsid`, de sorte qu'un nœud survit à la mort
> du shell qui l'a lancé. Ne pas contourner `start.sh` en lançant le binaire à la main.
>
> **Lancement du monitor** : `setsid nohup … & disown`. Sans `nohup` il meurt avec le shell
> appelant dans un environnement d'orchestration — deux campagnes l'ont constaté.

### Les simulateurs

Les deux simulateurs sont conçus autour de la même contrainte : **`nextNonce` servi par le
nœud est le nonce CONFIRMÉ**, le mempool n'est pas consulté (`NodeService.nextNonce`). Deux
envois concurrents depuis la même adresse signeraient donc le même nonce et le second serait
rejeté. D'où : un portefeuille par worker, et chaque worker attend que son nonce confirmé
avance avant l'envoi suivant. La cadence s'auto-régule sur celle des blocs — une file de
transactions valides, jamais un flot de doublons invalides qui ferait pénaliser le pair.

- **`sim-tx.sh`** — 8 portefeuilles dotés chacun par un mineur *différent* (la récompense est
  de ~2,78 PDN par bloc : un seul mineur mettrait des minutes à financer 8 portefeuilles),
  puis transferts de 0,001 PDN vers un pair tiré au hasard, **soumis à un nœud tiré au
  hasard** — c'est ce qui teste le gossip à l'échelle du réseau plutôt qu'un seul mempool.
  Journal CSV : `.testnet/sim/tx.csv`.
- **`sim-contract.sh`** — déploie `counter.wasm` et `token.wasm` (les templates du dashboard),
  initialise le token, puis alterne incréments du compteur et transferts de token depuis des
  nœuds tirés au hasard. Le compteur écrit une clé de storage, le token en écrit deux (débit +
  crédit) : deux tailles de journal d'undo à rejouer en cas de reorg. Toutes les 10 itérations,
  `statecheck.py` compare l'état sur les 30 nœuds.
  - Le gaz : le protocole accepte `gasPrice 0` mais **le wallet CLI le refuse** (garde-fou
    client). On travaille donc à `gasPrice 1`, ce qui impose de dimensionner `gasLimit` : le
    montant réservé à l'admission est `gasLimit × gasPrice`, soit 10 PDN pour la valeur par
    défaut (100 000). Un `gasLimit` de plusieurs millions dépasserait le solde du portefeuille
    et la transaction serait refusée pour insuffisance de fonds **sans jamais atteindre la VM**.
    Coût mesuré d'un déploiement : ~22 700 unités de gaz.
- **`statecheck.py`** — lit `/stats`, `/call_readonly` (compteur), `/call_readonly` (solde
  token) puis `/stats` à nouveau, **en parallèle sur les 30 nœuds**, et n'inclut un nœud que si
  son tip n'a pas bougé pendant sa propre lecture. Un balayage séquentiel via le wallet CLI
  (une JVM par lecture, ~0,5 s) dure 15 s, soit plusieurs blocs : le premier essai n'a trouvé
  que 3 nœuds « au même tip » sur 30 et ne pouvait rien conclure. Le verdict ne compare que
  des nœuds d'un **même groupe de tip** : deux valeurs différentes à deux tips différents sont
  un retard de gossip, deux valeurs différentes au même tip sont une divergence d'exécution.

## Critères de réussite généraux

- Tous les nœuds : `degraded == null`, `reorgInProgress == false` en régime stable.
- Les 30 nœuds atteignent la même hauteur **et le même `tipHash`** ; les écarts > 2 blocs
  pendant > 30 s sont des anomalies.
- **`status.sh` affiche `tips distincts: 1`** hors fenêtre de partition. À hauteur, difficulté
  et travail égaux, deux camps sur des branches différentes sont indiscernables par tout le
  reste de `/stats`.
- `syncEclipsed == false` et `syncRoundsWithoutProgress == 0` en régime sain (un nœud nourri
  par gossip ne fait légitimement rien en sync).
- **Chaque nœud a exactement 18 pairs** (cap anti-éclipse, voir plus haut), sans doublon ni
  auto-référence.
- Un bloc miné arrive chez tous les pairs en < 10 s (gossip push).
- L'état des contrats est **identique sur tous les nœuds d'un même tip**.

## Scénarios

Notation : hauteur du nœud `i` = `h_i` (via `curl -s http://127.0.0.1:$((BASE+i))/stats`).

### S0 — Lancement et convergence
1. `start.sh` ; attendre la fin de l'amorçage PEX et les premiers blocs.
2. **Passe si** : 30 réponses `/stats` ; `max(h_i) − min(h_i) ≤ 2` ; `tips distincts: 1` ; la
   hauteur croît en continu.

### S1 — Propagation de transactions (gossip)
1. Soumettre une transaction valide sur un nœud quelconque et échantillonner les 30 mempools
   **en parallèle** (un balayage séquentiel est plus lent que la propagation).
2. **Passe si** : la transaction apparaît chez ≥ 28/30 nœuds en < 10 s, puis est minée et
   disparaît des mempools.

### S2 — Propagation de blocs (push)
1. Laisser miner en observant `/stats` des observateurs.
2. **Passe si** : les observateurs suivent à ≤ 2 blocs sans pull manuel. `avgBlockIntervalMs`
   est inutilisable sous 32 blocs (le genesis à timestamp 0 est dans la fenêtre — quirk connu).

### S3 — Découverte de pairs (PEX)
1. `status.sh` à intervalles réguliers ; relever la **progression**.
2. **Passe si** : `peers` croît puis se stabilise à **18** (16 découverts + 2 seeds, cap
   anti-éclipse) ; aucun nœud ne se liste lui-même ; `GET /peers` ne contient aucun doublon.

### S4 — Ajout d'un nœud sur réseau vivant (churn)
1. `RHIZOME_TESTNET_NODES=31 start.sh -n 30`.
2. **Passe si** : il rattrape la hauteur commune en < 90 s ; `degraded == null` ; son `tipHash`
   rejoint celui du réseau.

### S5 — Panne d'un mineur (rejeu du bug 1 de la campagne 1)
1. `stop.sh -n 3`, observer les 29 restants pendant 3 min.
2. **Passe si** : les hauteurs continuent de croître sur les 29 ; `syncEclipsed` reste `false` ;
   `syncRoundsWithoutProgress` reste à 0 ; aucun `degraded` ; aucun ban ; `tips distincts: 1`.

### S6 — Redémarrage d'un nœud (resync)
1. `stop.sh -n 3`, laisser le réseau avancer, puis `start.sh -n 3`.
2. **Passe si** : il rattrape < 90 s ; `reorgInProgress` reste `false` (retard, pas divergence) ;
   son tip rejoint celui du réseau.

### S7 — Partition 15/15 et guérison (le test le plus important)
1. **Arrêter tout, puis `start.sh -p 15-29`, puis `start.sh -p 0-14`.** La séquence littérale
   « stop B ; start B ; stop A ; start A » ne partitionne PAS : les registres vivants du camp A
   fuient vers le camp B pendant son redémarrage et le PEX recolle le maillage.
2. Laisser chaque moitié miner. Vérifier : `tips distincts: 2`, aucun pair hors-camp, les deux
   hauteurs proches.
3. Rétablir **par `/add_peer` croisés** (quelques ponts suffisent, le PEX fait le reste) plutôt
   que par un redémarrage complet : c'est plus rapide et cela préserve l'état de sync.
4. **Passe si** : retour à `tips distincts: 1` en < 2 min ; **aucun** `degraded` ; aucun ban ;
   soldes des mineurs cohérents (récompenses de la branche abandonnée annulées via les journaux
   d'undo) ; les transactions de la branche perdue encore valides reviennent en mempool.

### S8 — De bout en bout via le wallet CLI
1. `app-wallet send <nœud A> …` puis `app-wallet balance <nœud B> …` à l'autre bout du réseau.
2. **Passe si** : la transaction est minée < 30 s et le solde est identique sur n'importe quel
   nœud — exécution déterministe partout.

### S9 — Contrat (VM distribué)
1. `sim-contract.sh check` (compteur + solde token, sur les 30 nœuds).
2. **Passe si** : un seul état par groupe de tip ; aucune divergence à tip identique.

### S10 — Supervision et indicateurs opérateur
1. `monitor.sh` pendant toute la campagne.
2. **Passe si** : `degraded` resté `null` sur les 30 ; les transitions `reorgInProgress` tracées
   dans le CSV ; l'alerte « RÉSEAU SCINDÉ » apparaît **pendant et seulement pendant** les
   fenêtres de partition ; aucune hauteur figée > 60 s hors partitions volontaires.

### S11 — Pair indisponible en plein reorg : la branche locale survit *(P1)*
1. Provoquer une reorg longue (partition courte), rebrancher **un seul** nœud du camp perdant.
2. Pendant sa fenêtre de reorg, **tuer son pair source**.
3. **Passe si** : le nœud ne reste pas tronqué à la hauteur de fork ; sa hauteur et son tip sont
   ceux d'avant la tentative ; `degraded == null` ; le round suivant reprend depuis un autre pair.

### S12 — Éclipse observable, registre vide *(P2)*
1. Lancer un nœud dont le registre reste vide (pairs tous arrêtés), attendre 3 rounds de sync.
2. **Passe si** : `syncEclipsed: true`, `peers: 0`, `syncRoundsWithoutProgress` qui monte, WARN
   « sync eclipsed » au log. Nuance : un nœud seul AVEC seeds n'est pas éclipsé — les seeds sont
   toujours tentées ; l'éclipse est la forme « registre vide ».

### S13 — Pas d'escalade d'adresse sur hôte partagé *(P3)*
1. Faire bannir 3 endpoints `127.0.0.1:<port>` distincts par un nœud.
2. **Passe si** : les 3 endpoints visés sont bannis **et** les autres nœuds de `127.0.0.1`
   restent joignables et syncables ; `syncEclipsed` reste `false`.

### S14 — Dashboard pendant une fenêtre de reorg *(P4)*
1. Interroger les endpoints d'un nœud pendant sa fenêtre de reorg.
2. **Passe si** : un 503 porte le message du nœud (« reorg in progress; retry shortly »), pas un
   503 brut, et les endpoints re-servent les blocs après la fenêtre.

### S15 — Égalité stricte : départage déterministe *(P5 / fix 4)*
1. Partition, en visant l'égalité **stricte** de `totalWork` entre les deux camps.
2. Relever les deux tips, puis rebrancher.
3. **Passe si** : la convergence se fait en un round ; un seul camp reorg ; le camp gagnant est
   celui dont le `tipHash` est **lexicographiquement le plus petit** ; aucune oscillation
   ensuite.
   *Limite connue* : sous course de minage, le camp qui produit le bloc suivant gagne par le
   travail avant que le départage n'ait à trancher. L'égalité stricte doit être capturée au
   moment exact du pontage, sinon le résultat n'est pas attribuable au départage — le test
   exact reste couvert par `HeaderSynchronizerTest`.

### S16 — Pair confirmé mais menteur : le score de ban compose avec l'éviction de découverte *(NET-11)*
1. Lancer un pair hostile autonome (processus Java séparé, hors des scripts du testnet) qui
   réutilise directement `BlockCodec`/`BlockImpl`/`SHA256Hash` de `lib-core` pour servir des blocs
   structurellement valides mais sans preuve de travail réelle — même posture que la fixture JUnit
   `HostilePeer` (`app-node/src/test/java/rhizome/adversarial/e2e/`), mais sur un vrai socket TCP,
   hors du harnais de test. `/total_work` **doit** répondre `{"totalWork":"<décimal>"}` (objet
   JSON), pas un scalaire nu — `HttpPeerSource.totalWork()` décode via
   `PeerJson.parseObject(...).getString("totalWork")` ; un scalaire nu lève une
   `PeerProtocolException` AVANT le bloc `try` de `HeaderSynchronizer.syncFromOrThrow`, donc le
   pair n'est jamais confirmé et `SyncDriver.penalize` le *drop* sans le pénaliser (audit B-3) —
   ce qui prouve autre chose (S13) que ce que ce scénario vise.
2. Présenter le pair hostile via `/add_peer` à un nœud dont le registre a de la place dans son
   bucket `MAX_PER_SUBNET` (16 pairs découverts par /16 — voir « Le maillage sature à 18 pairs »
   ci-dessus) : sur un nœud du maillage principal déjà à 18 pairs, l'admission du pair hostile est
   silencieusement refusée par le cap anti-éclipse avant même d'atteindre le chemin de ban. Un
   nœud fraîchement isolé (0 pair, cf. S12) a toute la place.
3. **Passe si** : le pair hostile est **confirmé** (`registry.isConfirmed`, visible aux lignes de
   log `Penalized peer ... (served an invalid chain)`, pas `Dropped unconfirmed`) puis pénalisé
   d'au moins une frappe PEER_INVALID (+34) — la preuve que le score de ban s'applique bien à un
   pair réel sur un vrai socket, pas seulement dans la fixture à horloge virtuelle
   `BanDiscoveryPartitionAttackTest`. Qu'il atteigne le seuil de ban (100, trois frappes) avant que
   `PeerDiscovery` ne l'évince pour échecs consécutifs n'est **pas** un critère — les deux
   mécanismes ont des horizons différents (score qui décroît sur la fenêtre de ban entière contre
   compteur qui se remet à zéro au prochain contact réussi), et le catalogue documente déjà qu'ils
   ne composent pas en primitive d'éviction longue durée contre un pair honnête ; ce scénario
   corrobore seulement, sur un vrai réseau, que chacun des deux chemins se déclenche correctement
   pour ce qu'il mesure.

### S17 — `RHIZOME_API_TOKEN` sur un déploiement multi-nœuds réel *(API-13, opérationnel)*
1. Ajouter au réseau un nœud supplémentaire avec `RHIZOME_API_TOKEN` positionné (les scripts de ce
   plan ne l'exposent pas par nœud ; lancer le binaire directement, comme `start.sh` le ferait,
   avec cette variable en plus). Le peupler normalement (`/add_peer` vers un nœud existant).
2. **Passe si** : une route état-changeant/opérateur (`/add_peer`, `/submit`, `/add_transaction`,
   …) répond 401 sans jeton et avec un jeton erroné, 200 avec le bon jeton (`Authorization: Bearer
   <token>`) ; les routes du protocole pair-à-pair (`/sync`, `/headers`, `/peers`, `/block_count`,
   `/total_work`) restent servies **sans aucun jeton** ; le nœud rattrape la hauteur du réseau par
   ses propres rounds de sync (GET, non gatées) bien qu'aucun pair ne lui présente de jeton pair —
   la lecture reste ouverte même quand l'écriture est gardée.
   *Hors portée* : la composition avec `RHIZOME_PEER_TOKEN` (gossip poussé entre pairs authentifiés)
   ne peut pas se tester sur ce testnet — le jeton pair n'est envoyé que sur `https://`
   (`RHIZOME_PEER_TOKEN`, README), et ce plan reste volontairement en clair sur loopback (voir
   « Périmètre »). Un nœud token-gaté dans un maillage `http://` non gaté reste donc joignable en
   lecture mais un opérateur qui active le jeton sur un déploiement gossipant doit encore mettre
   `RHIZOME_PEER_TOKEN` sur ses pairs pour que les push `/submit`/`/add_transaction` continuent
   d'être acceptés — non vérifié en direct ici, dérivé du code (`NodeApi`/README).

## Supervision & alertes

`monitor.sh` (boucle 2 s) écrit `monitor.csv` : horodatage, nœud, hauteur, **tipHash**,
difficulté, pairs, mempool, `avgBlockIntervalMs`, `reorgInProgress`, `degraded`,
`syncRoundsWithoutProgress`, `syncPeersBanned`, `syncEclipsed`. Avertir immédiatement si :

- `degraded` ≠ `null` (barrière dure : le nœud refuse tout nouveau bloc tip et cesse de miner) ;
- `syncEclipsed == true` ; `syncRoundsWithoutProgress ≥ 6` ;
- **plus d'un `tipHash` distinct** hors fenêtre de partition (alerte « RÉSEAU SCINDÉ », armée
  après 3 cycles consécutifs pour ignorer les forks transitoires) ;
- `reorgInProgress` ouvert > 5 min ; `peers == 0` ; écart de hauteur > 5 blocs persistant.

## Arrêt et nettoyage

```bash
scripts/local-testnet/sim-tx.sh stop && scripts/local-testnet/sim-contract.sh stop
scripts/local-testnet/stop.sh
rm -rf .testnet          # données RocksDB + logs + CSV + pids
```

## Journal de résultats — campagne 5

Campagne exécutée le 2026-08-20 (base 4300 — même conflit de port que la campagne 4, voir constat
1), 30 nœuds **natifs** devnet, 10 mineurs (0, 3, 6, 9, 12, 15, 18, 21, 24, 27), `-Xmx128m` par
nœud, charge continue des deux simulateurs. Contrairement aux campagnes 1-4, l'objectif n'était
pas seulement de rejouer S0-S15 mais de fonder la campagne sur la revue adverse
(`docs/adversarial/spec.md`) : entre la campagne 4 (2026-08-17) et cette campagne, le catalogue est
passé de 0 à 143 scénarios `lib-core`/`lib-net`/`lib-vm`/etc. plus 28 scénarios `E2E`, et trois
lacunes déclarées ont été fermées la veille et le jour même (API-13, NET-11, REORG-11/12, voir le
changelog de `docs/adversarial/spec.md`). S16 et S17 ci-dessus étendent en réseau réel deux de ces
trois fermetures ; REORG-11/12 n'a délibérément **pas** de nouveau scénario réseau (voir plus bas).
Réseau poussé jusqu'à h≈394, `degraded` resté **`null` sur les 30 nœuds pendant toute la
campagne** (0 occurrence sur ~1950 lignes de `monitor.csv`).

| Scénario | Résultat | Détail |
|---|---|---|
| S0 Lancement/convergence | **PASS** | 30/30 répondent dès le premier `status.sh` ; écart=0, tip unique, 18 pairs déjà atteints |
| S1 Gossip de transactions | **PASS** | Mempool à 7 sur la majorité des 30 nœuds quelques secondes après une rafale de transferts du simulateur, chacun soumis à un nœud tiré au hasard |
| S2 Propagation de blocs | **PASS** | Écart ≤ 2-3 blocs en régime stable ; une bouffée à 3 tips distincts (écart=2) observée en fin de campagne (hors toute partition volontaire) s'est résorbée à `tips distincts: 1` en < 90 s sans intervention — exactement le régime « rafales de fork transitoires » que les campagnes 3/4 ont déjà caractérisé à cette cadence, pas une anomalie |
| S3 PEX | **PASS** | 18 pairs par nœud tout du long |
| S4 Churn (31ᵉ nœud) | **PASS** | Nœud 30 rattrape la hauteur commune en **15-20 s**, 18 pairs, `degraded=null` |
| S5 Panne d'un mineur | **PASS** | Mineur 3 arrêté ~2,5 min : les 29 restants croissent sans arrêt (h 18→25), écart=0, zéro `degraded`, zéro ban, zéro éclipse |
| S6 Redémarrage | **PASS** | Le nœud 3 rattrape en **~20 s**, `reorgInProgress` reste `false` (retard, pas divergence) |
| S7 Partition 15/15 | **PASS** | Partition étanche (pairs de chaque camp confirmés 100 % internes via `/peers`) ; camp B (15-29) en tête de 6 blocs / 576 unités de travail au moment du pont ; guérison en **< 20 s** après quelques ponts croisés seulement (pas un pont exhaustif) ; `tips distincts: 1`, `écart=0` sur les 30 nœuds, aucun `degraded`, aucun ban ; soldes de miner-0 et miner-27 **identiques bit-à-bit** (35,1099 PDN et 33,5923 PDN) vus depuis un nœud de chaque ancien camp |
| S8 Wallet E2E | **PASS** | 1,5 PDN émis via le nœud 3 (`app-wallet send`) vers l'adresse du mineur 9, confirmé sur le nœud 29 en **~12 s**, `status: SUCCESS` |
| S9 Contrat distribué | **PASS** | Compteur + token déployés et exercés en continu (simulateur relancé après un incident d'outillage, voir correctifs) ; `sim-contract.sh check` : 4 groupes de tip (décalage de gossip normal sous charge), **exactement 1 état par groupe** — aucune divergence d'exécution |
| S10 Supervision | **PASS** | `monitor.csv` : `degraded` reste `"null"` sur les ~1950 lignes couvrant toute la campagne (vérifié par lecture directe des valeurs distinctes de la colonne, pas par estimation) |
| S11 Pair perdu en reorg (P1) | **PASS** | Nœud vanguard (5) bridgé en tête-à-tête avec sa seule source (nœud 20) ; guet `grep`-pur sur `reorgInProgress` : source tuée à l'instant exact où la fenêtre s'ouvre (`reorgInProgress:true` capté) → hauteur du vanguard **44 → 59** après coup (pas de troncature), `degraded=null`, `reorgInProgress=false` retombé, `mempool=1`, `peers=18` — reprise complète via un autre pair |
| S12 Éclipse registre vide (P2) | **PASS** | Nœud isolé lancé sans seed (partition à un seul nœud) : `peers=0`, `syncEclipsed=true`, `syncRoundsWithoutProgress` croissant (3 → 46 sur la campagne), WARN « sync eclipsed » au log, `degraded=null` |
| S13 Hôte partagé sans escalade (P3) | **non rejoué cette campagne** | L'effort NET-11 est allé dans S16 (ci-dessous), qui pousse plus loin que S13 : un pair réellement **confirmé** menteur, pas seulement injoignable. Le constat historique de S13 (chemin de ban non atteint par un simple endpoint injoignable, campagnes 2-4) n'a pas été remis en cause, juste pas re-mesuré indépendamment |
| S14 Dashboard en reorg (P4) | **PASS** | **56 réponses** `503 {"error":"reorg in progress; retry shortly"}` capturées sur `/total_work` à travers 7 nœuds du camp perdant pendant la fenêtre de reorg de S7 (poll continu, ~2800 sondages au total), endpoint de nouveau `200` juste après |
| S15 Départage déterministe (P5) | **PARTIEL** *(même verdict que les 4 campagnes précédentes)* | Le pont de S7 est intervenu avec 576 unités de travail d'écart, pas une égalité stricte — la convergence a été décisive par le poids, pas par le départage. Toujours couvert par `HeaderSynchronizerTest` uniquement |
| **S16** Pair confirmé menteur *(NET-11, nouveau)* | **corroboré en réseau réel** | Pair hostile autonome (réutilise `BlockCodec`/`BlockImpl`/`SHA256Hash` de production, cf. description du scénario) ajouté à un nœud isolé : **confirmé** puis pénalisé deux fois (+34, +34 = 68/100) pour « served an invalid chain » avant que `PeerDiscovery` ne l'évince pour échecs consécutifs — le score de ban s'applique bien à un vrai pair sur un vrai socket (pas seulement dans la fixture à horloge virtuelle), et c'est la voie de découverte, pas le seuil de ban, qui a tranché en premier sur un registre neuf. Voir constat 3 |
| **S17** `RHIZOME_API_TOKEN` multi-nœuds *(API-13, nouveau)* | **PASS** | Nœud supplémentaire token-gaté ajouté au réseau vivant : `/add_peer` → 401 sans jeton, 401 avec jeton erroné, 200 avec le bon jeton ; `/sync`, `/peers`, `/block_count`, `/total_work` servis **sans jeton** ; le nœud a rattrapé la hauteur du réseau (1 → 354) par ses propres rounds de sync malgré la garde, 18 pairs, `degraded=null` — la garde token protège l'écriture sans jamais bloquer la lecture ni le rattrapage |
| REORG-11/12 (sélectif/grinding) | **aucun nouveau scénario réseau** | Délibéré, pas un oubli : reproduire le retenue sélective de blocs ou le grinding du nonce exige un mineur hostile qui triche, que ce testnet ne fournit pas (les binaires stock diffusent tout ce qu'ils minent) — en fabriquer un juste pour cette campagne aurait violé la règle 2 du protocole (l'attaque doit atteindre la porte qu'elle prétend nommer). La preuve reste `SelfishMiningModel`/`SelfishMiningAttackTest` (tirage de Bernoulli contrôlé, horloge et hash-rate maîtrisés) ; les reorgs réels de S7/S15 sont cohérents avec ce modèle sans le prouver eux-mêmes, comme dans les 4 campagnes précédentes |

### Constats de campagne

**1. Le conflit de port 3000 de la campagne 4 s'est reproduit à l'identique.** Même signature
exacte (`BindException` sur le nœud 0, un service tiers login-gated ayant bindé le port entre le
pré-vol et le `bind()` du nœud) — voir constat 1 de la campagne 4 ci-dessous, qui documente déjà
le même contournement (`RHIZOME_TESTNET_BASE_PORT=4300`). Ce n'est donc pas un incident isolé sur
cette machine de dev partagée mais une condition récurrente ; le contournement documenté suffit
toujours, mais un opérateur qui rejoue ce plan devrait s'y attendre par défaut plutôt que le
découvrir à chaque campagne.

**2. `sim-contract.sh start` peut mourir silencieusement si on le lance trop tôt après le
genesis.** `deploy_all` → `fund_owner` a un délai de dotation de 300 s ; juste après le genesis,
aucun mineur n'a encore gagné assez pour doter le portefeuille de contrats, `fund_owner` retourne
1, et sous `set -e` cela tuait tout le `start` en arrière-plan avec une seule ligne de log
(« dotation … : 0 PDN demandés ») et rien d'autre — la même classe de silence que les boucles de
`sim-tx.sh` et le `loop()` de ce même script avaient déjà appris à éviter (voir correctifs de la
campagne 3). Corrigé (voir Correctifs livrés) ; contournement immédiat pendant cette campagne :
déploiement manuel via `app-wallet` une fois le réseau à hauteur suffisante.

**3. Sur un registre neuf (un seul pair), l'éviction de `PeerDiscovery` tranche avant le seuil de
ban.** Le pair hostile de S16 a encaissé deux frappes PEER_INVALID confirmées (68/100, sous le
seuil de ban à 100) avant que `PeerDiscovery` ne l'évince pour échecs de contact consécutifs — les
deux mécanismes ont des horizons différents (score qui décroît sur la fenêtre de ban entière,
compteur qui se remet à zéro au prochain contact réussi) et le catalogue documente déjà qu'ils ne
composent pas en primitive d'éviction longue durée. Ce n'est pas une régression : c'est la première
fois que cette interaction est observée contre un pair réellement confirmé sur un vrai socket
plutôt que dans la fixture à horloge virtuelle `BanDiscoveryPartitionAttackTest` — qui prouve
l'horizon complet (48 h simulées) que ce testnet ne peut pas dérouler en temps réel.

**4. Un pair hostile doit imiter le format du fil, pas seulement la forme de l'attaque.**
Premier essai de S16 : `/total_work` renvoyait un scalaire nu (`"340282…"`) au lieu de l'objet JSON
`{"totalWork":"…"}` que `HttpPeerSource.totalWork()` attend — la `PeerProtocolException` qui en
résultait se produisait *avant* le bloc `try` de `HeaderSynchronizer.syncFromOrThrow`, donc le pair
n'était jamais confirmé et `SyncDriver.penalize` le *droppait* sans le pénaliser (audit B-3) :
symptomatiquement identique à S13 (« Dropped unconfirmed … not banned »), mais pour une raison
d'outillage et non de conception. Corrigé en encodant `/total_work` comme l'attend
`HttpPeerSource`. Séparément : présenter le même pair à un nœud du maillage principal (déjà à 18
pairs) échouait silencieusement à l'admission — `PeerRegistry.MAX_PER_SUBNET` (16 pairs découverts
par bucket /16) refuse une nouvelle entrée avant même que le chemin de ban existe, sur un bucket
loopback déjà saturé. Un nœud isolé (S12) a servi de cible à la place.

### Correctifs livrés

| Défaut | Effet | Correctif |
|---|---|---|
| `sim-contract.sh` : `deploy_all` (via `fund_owner`) peut échouer juste après le genesis, faute de solde minier suffisant | Sous `set -e`, le `start` en arrière-plan mourait avec une seule ligne de log et aucune trace de la cause | Le call site échoue maintenant bruyamment (`ERREUR: déploiement des contrats échoué … relancer 'sim-contract.sh start'`) au lieu de disparaître silencieusement |
| `TEST-PLAN.md` : la liste des variables d'override documentait `RHIZOME_TESTNET_BLOCK_MS` à 10000 alors que `common.sh` et le corps de cette section (« Cadence de production ») pointent tous deux vers 25000, la valeur réellement calibrée depuis la campagne 3 | Un opérateur qui ne changeait rien lisait un défaut faux ; la valeur réelle (25 s) n'était documentée que dans la section « Cadence de production », pas dans le résumé des variables | Les deux mentions corrigées à 25000, avec une note expliquant la dérive |

---

# Archive — campagne 4 (30 nœuds natifs, 2026-08-17)

## Journal de résultats — campagne 4

Campagne exécutée le 2026-08-17 (base 4300 — le port 3000 a été pris par un service tiers
*pendant* le lancement, voir constat 1 ci-dessous), 30 nœuds **natifs** devnet, 10 mineurs
(0, 3, 6, 9, 12, 15, 18, 21, 24, 27), `-Xmx128m` par nœud (réduit depuis le défaut 256 m faute de
marge RAM sur la machine, voir constat 2), charge continue des deux simulateurs. Réseau poussé
jusqu'à h≈1100 sur la durée de la campagne, `degraded` resté **`null` sur les 30 nœuds pendant
toute la campagne** (0 occurrence sur 5330+ lignes de `monitor.csv`).

| Scénario | Résultat | Détail |
|---|---|---|
| S0 Lancement/convergence | **PASS** | 30/30 répondent ; écart=0, tip unique, **18 pairs dès la première lecture** (plus rapide qu'en campagne 3, l'amorçage PEX ayant eu plus de temps de propagation avant le premier `status.sh`) |
| S1 Gossip de transactions | **PASS** *(critère adapté)* | `/mempool` ne renvoie que la taille, pas les hachages : la mesure littérale « tx vue sur ≥28/30 mempools » n'est pas possible via l'API. Preuve opérationnelle de substitution : **1788/1876 (95,3 %)** transferts du simulateur, chacun soumis à un nœud **tiré au hasard**, minés avec succès sur tout le réseau |
| S2 Propagation de blocs | **PASS** | Écart de hauteur ≤ 3 en régime stable sur l'ensemble de la campagne (30 nœuds répondants à chaque `status.sh`) |
| S3 PEX | **PASS** | 18 pairs par nœud tout du long, aucun doublon ni auto-référence observés sur les échantillons pris |
| S4 Churn (31ᵉ nœud) | **PASS** | Rattrape la hauteur commune en **< 5 s** (nettement plus vite qu'en campagne 3 — chaîne courte, PEX déjà chaud), 18 pairs, `degraded=null` |
| S5 Panne d'un mineur | **PASS** | Mineur 3 arrêté 3 min : les 29 restants croissent sans arrêt (274→282), `stallR` max=2, zéro `degraded`, zéro ban, zéro alerte de scission |
| S6 Redémarrage | **PASS** | Le nœud 3 rattrape en **< 20 s**, `reorgInProgress` reste `false` (retard, pas divergence), tip rejoint |
| S7 Partition 15/15 | **PASS** | Partition étanche (0 pair hors camp, vérifié via `/peers`) ; guérison en **< 15 s** après les ponts croisés (plus rapide que les 32,5 s de la campagne 3) ; `degraded` resté `null`, aucun ban ; soldes des 4 mineurs testés (0, 3, 15, 27) **identiques bit-à-bit** vus depuis les deux anciens camps |
| S8 Wallet E2E | **PASS** | 1,5 PDN émis via le nœud 3, confirmé sur le nœud 29 en **3 s** |
| S9 Contrat distribué | **PASS** | Compteur + solde token : un seul état par groupe de tip à chaque contrôle (avant et après S7), y compris sur le contrôle final en fin de campagne |
| S10 Supervision | **PASS** | `degraded` resté `null` sur les 30 nœuds sur l'intégralité de la campagne ; l'alerte « RÉSEAU SCINDÉ » n'est apparue que pendant les fenêtres de partition volontaires (S7, S15), jamais en régime stable, confirmé en croisant les horodatages du CSV avec les fenêtres de test |
| S11 Pair perdu en reorg (P1) | **PASS** | Guet resserré (grep pur, sans interpréteur, ~50 ms/poll) sur `reorgInProgress` : source (nœud 5) tuée en pleine fenêtre de reorg du nœud 20 après une divergence locale de 150 s (paire isolée 20+21) → **aucune troncature** (progression continue 699→705→707→715 au `monitor.csv`), `degraded=null`, reprise via d'autres pairs après ajout |
| S12 Éclipse registre vide (P2) | **PASS** | Nœud lancé sans seed (span de partition =1) : `peers=0`, `syncEclipsed=true`, `syncRoundsWithoutProgress=3` et montant, WARN « sync eclipsed » au log, `degraded=null` |
| S13 Hôte partagé sans escalade (P3) | **PARTIEL** *(même verdict qu'en campagne 3)* | 3 faux pairs `127.0.0.1` injoignables : journalisés « peer request failed », **jamais bannis** (le chemin de ban n'est atteint que par un pair confirmé qui se comporte mal, pas par un endpoint simplement injoignable). Propriété de sécurité positive confirmée : un vrai pair `127.0.0.1` ajouté ensuite reste pleinement joignable et permet un rattrapage complet (223→623, 16 pairs, 0 ban) |
| S14 Dashboard en reorg (P4) | **PASS** | Endpoints gardés identifiés dans le code (`/blocks`, `/block`, `/block_count`, `/total_work`, `/sync`, `/headers` — pas `/stats` ni `/peers`, d'où un premier essai infructueux) ; **5 réponses `503 {"error":"reorg in progress; retry shortly"}`** capturées sur `/total_work` pendant la fenêtre exacte de reorg (guet à ~1075 polls en 45 s), endpoint de nouveau `200` juste après |
| S15 Départage déterministe (P5) | **PARTIEL** *(même verdict qu'en campagne 3)* | Égalité stricte de `totalWork` jamais captée en direct sur 1430 sondages (~63 ms/poll, écart final 448 unités de travail) — les deux camps minaient en continu et le prochain bloc tranchait avant l'instant exact de l'égalité, comme en campagne 3. Le pont posé malgré tout a convergé **proprement et vite** (tip identique dès +5 s, aucune oscillation sur 40 s observées) : la mécanique de reconnexion fonctionne, mais le départage strict par tip hash lexicographique reste non isolé en réseau live — toujours couvert par `HeaderSynchronizerTest` |

### Constats de campagne

**1. Sur une machine de dev partagée, l'indisponibilité d'un port peut apparaître *entre* le
contrôle de pré-vol et le `bind()` du nœud.** Le premier lancement a échoué : le port 3000,
libre au moment du contrôle `check_ports_free`, était occupé par un service tiers (login-gated,
sans rapport avec Rhizome) au moment précis où le nœud 0 a tenté de démarrer — `BindException`,
nœud 0 mort, 29 autres nœuds up mais sans hub PEX. Résolu en relançant sur
`RHIZOME_TESTNET_BASE_PORT=4300` (déjà le contournement documenté pour cette machine). Le
pré-vol reste utile (il aurait bloqué un conflit stable) mais ne couvre pas une fenêtre de course
avec un tiers qui bind après coup.

**2. La marge RAM disponible sur une machine de dev partagée peut s'effondrer en quelques
minutes, sans lien avec le testnet.** Entre le premier contrôle d'environnement (8,3 Gio
disponibles) et le premier `start.sh`, la RAM disponible est tombée à 3,0 Gio à cause d'un run de
tests Maven d'un projet tiers (`atelier/backend`, ~4,3 Gio à lui seul) et de plusieurs serveurs de
langage VS Code actifs en tâche de fond — le garde-fou mémoire de `start.sh` a **correctement
refusé de lancer** plutôt que risquer un OOM en cours de campagne. Une attente de 9 min n'a pas
suffi (la RAM a continué de baisser, jusqu'à 5,3 Gio) : la charge concurrente n'était pas un pic
transitoire mais un plateau durable. Solution retenue : `RHIZOME_TESTNET_HEAP=128m` (au lieu du
défaut 256 m) — la topologie (30 nœuds, 10 mineurs) reste inchangée, seul le plafond `-Xmx` est
réduit. Aucun effet secondaire observé sur toute la campagne (RSS réelle mesurée bien en dessous
du plafond, comme en campagne 3 où 192 m suffisaient déjà largement à des nœuds consommant en
réalité 75-100 Mo).

**3. Les endpoints protégés pendant une fenêtre de reorg sont un sous-ensemble précis de l'API**
(`/blocks`, `/block`, `/block_count`, `/total_work`, `/sync`, `/headers`), pas `/stats` ni
`/peers` — ces deux derniers restent servis normalement même quand le nœud est en pleine
reconstruction de sa vue locale. Un premier essai de S14 scrutant `/stats` n'a donc rien capté ;
il a fallu lire `SyncApi`/`NodeApi` pour identifier les bonnes routes.

**4. `reorgInProgress` peut être une fenêtre très étroite (dizaines à centaines de ms).** Un
premier essai de S11 avec un scrutin à ~150 ms (interprète Python par extraction JSON) n'a rien
capté sur deux tentatives malgré une hauteur de fork croissante ; passer à une extraction
`grep`/`sed` pure (sans fork d'interpréteur, ~50 ms/poll) a permis de capter la fenêtre dès la
troisième tentative. Le choix de l'outil d'instrumentation change directement le résultat d'un
scénario réseau chronosensible.

### Correctifs livrés

| Défaut | Effet | Correctif |
|---|---|---|
| `sim-contract.sh` : `TEMPLATES` pointait vers `app-node/src/main/resources/dashboard/templates` | Ce répertoire ne contient **que** `manifest.json` par construction (`stageContractTemplates` copie les `.wasm` dans `build/generated/`, jamais dans les sources) — tout premier appel à `sim-contract.sh start` échouait avec `error: .../counter.wasm` avant même d'atteindre le réseau | `TEMPLATES` pointe désormais vers `lib-vm/src/test/resources`, la source unique checked-in des fixtures `.wasm` documentée dans `CLAUDE.md` |

---

# Archive — campagne 3 (30 nœuds natifs, 2026-08-10)

## Journal de résultats — campagne 3

Campagne exécutée le 2026-08-10 (base 4300 — les ports bas étaient pris par des outils de dev),
30 nœuds **natifs** devnet, 10 mineurs (0, 3, 6, 9, 12, 15, 18, 21, 24, 27), `-Xmx192m` par
nœud, charge continue des deux simulateurs. Empreinte mesurée : **2,2 à 2,9 Go pour les 30
nœuds**, soit 75 à 100 Mo par nœud — un nœud JVM équivalent en occupe 350 à 400.

| Scénario | Résultat | Détail |
|---|---|---|
| S0 Lancement/convergence | **PASS** | 30/30 répondent ; à 25 s d'intervalle le réseau revient à `écart=0` et tip unique, entre des bouffées de fork brèves (voir constat 1) |
| S1 Gossip de transactions | **PASS** | Transaction vue par **≥ 28/30 nœuds à +30 ms** après soumission (échantillonnage parallèle) ; sous charge les 30 mempools portent le même contenu |
| S2 Propagation de blocs | **PASS** | Écart de hauteur 0 sur 30 nœuds en régime stable ; aucun observateur au-delà de 2 blocs |
| S3 PEX | **PASS** *(critère corrigé)* | **18 pairs sur les 30 nœuds**, sans doublon ni auto-référence. Ce n'est pas un maillage incomplet : `MAX_PER_SUBNET = 16` plafonne les pairs découverts par bucket /16, et sur loopback tout le réseau est dans un seul bucket (16 découverts + 2 seeds) |
| S4 Churn (31ᵉ nœud) | **PASS** | Rattrape la hauteur commune en **32 s**, 18 pairs, `degraded=null`, tip identique |
| S5 Panne d'un mineur | **PASS** | Mineur 3 arrêté 3 min : les 29 restants croissent sans arrêt (écart 1 bloc), zéro `degraded`, zéro éclipse, zéro stall, zéro ban |
| S6 Redémarrage | **PASS** | Le nœud 3 rattrape **36 blocs en 15 s**, `reorgInProgress` reste `false` (retard, pas divergence), tip rejoint |
| S7 Partition 15/15 | **PASS** | Partition étanche (0 pair hors camp, un tip par camp) ; divergence A+20 / B+26 blocs depuis h=243 ; **guérison en 32,5 s** par `/add_peer` croisés ; `reorgInProgress` observé sur 4 nœuds du camp perdant ; aucun `degraded`, aucun ban ; bloc 250 identique sur les deux camps après coup ; soldes des mineurs cohérents **dans chaque groupe de tip** (journaux d'undo corrects) |
| S8 Wallet E2E | **PASS** | 1,5 PDN émis via le nœud 3, confirmé sur le nœud 29 en **8 s** |
| S9 Contrat distribué | **PASS** | Compteur + solde token : **un seul état sur les 30 nœuds** d'un même tip, avant comme **après la reorg S7** — les journaux d'undo de la VM se rejouent exactement |
| S10 Supervision | **PASS** | `degraded` resté `null` sur les 30 pendant toute la campagne ; l'alerte « RÉSEAU SCINDÉ » apparaît pendant et seulement pendant les fenêtres de partition ; le monitor tient désormais la durée (voir correctifs d'outillage) |
| S11 Pair perdu en reorg (P1) | **PASS** | Guet à 50 ms : la source (nœud 20) tuée en pleine fenêtre de reorg du nœud 5 → **aucune troncature** (h 352 → 355), `degraded=null`, fenêtre refermée, 18 pairs conservés |
| S12 Éclipse registre vide (P2) | **PASS** | Nœud lancé sans seed : `peers=0`, `syncEclipsed=true`, `syncRoundsWithoutProgress` qui monte, WARN « sync eclipsed » au log, `degraded=null` |
| S13 Hôte partagé sans escalade (P3) | **PARTIEL** | Le chemin de ban n'est **pas atteignable** par un faux pair : un pair jamais confirmé qui sert des données malformées est *« Dropped unconfirmed peer … not a protocol-speaking node, not banned »*, donc l'escalade d'adresse n'est jamais sollicitée. L'intention est néanmoins vérifiée : après trois endpoints `127.0.0.1` hostiles, le nœud resynchronise normalement dès qu'on lui donne un vrai pair (h=328, 16 pairs, `eclipsed=false`) — le loopback n'a pas été blacklisté |
| S14 Dashboard en reorg (P4) | **PASS** | **40/40** réponses `503 {"error":"reorg in progress; retry shortly"}` pendant la fenêtre — le message du nœud, pas un 503 brut |
| S15 Départage déterministe (P5) | **PARTIEL** | Égalité **stricte** capturée (les deux camps à h=224, `totalWork=24768`) : le pontage a convergé sur un tip unique dès le premier échantillon, et le camp gagnant est celui dont le tip était **lexicographiquement le plus petit** (`3C0C…` contre `5A2F…`, la chaîne canonique descend bien du bloc 223 du camp A). Mais les deux camps minaient : le camp gagnant a aussi produit le bloc suivant, donc le résultat n'est pas attribuable au seul départage. Le test exact reste couvert par `HeaderSynchronizerTest` |

### Constats de campagne

**1. La cadence de production pilote le taux de fork, et c'est le vrai réglage du testnet.**
Avec 10 mineurs, `RHIZOME_BLOCK_INTERVAL_MS = 10 s` fait vivre le réseau en **fork permanent** :
3 à 7 tips distincts en continu, hauteurs à ±5, sans jamais de tip unique — les blocs arrivent
plus vite que le gossip ne converge. Le réseau n'est pas malade (aucun `degraded`, aucun ban,
les hauteurs avancent ensemble), mais le critère « tips distincts: 1 » devient inatteignable et
toute la campagne devient illisible.

À 25 s le réseau **revient** régulièrement à `écart=0` / tip unique, sans y rester en
permanence : la production est en rafales et chaque rafale ouvre une bouffée de fork de
quelques dizaines de secondes (relevés consécutifs mesurés : 0/1 tip, 0/1 tip, 5/4 tips). Le
critère utilisable n'est donc pas « tip unique à tout instant » mais « le réseau y revient
entre les rafales, et le verdict de scission ne tient pas 3 cycles de suite » — c'est
exactement pourquoi l'alerte de `monitor.sh` exige 3 cycles consécutifs. C'est le premier
bouton à régler avant toute campagne, et il dépend du nombre de mineurs.

**2. Le maillage ne peut pas dépasser 18 pairs sur loopback.** `PeerRegistry.MAX_PER_SUBNET = 16`
plafonne les pairs découverts par bucket /16 ; les 30 nœuds étant tous en `127.0.0.1`, chacun
plafonne à 16 découverts + 2 seeds. Le critère « le maillage atteint N−1 » des campagnes 1 et 2
n'a de sens que pour N ≤ 18.

**3. Le binaire natif change l'échelle testable.** 75 à 100 Mo de RSS par nœud contre 350 à
400 en JVM, démarrage en dizaines de ms, aucun réglage supplémentaire : `-Xmx` est consommé
par SubstrateVM comme par la JVM, RocksDB en JNI fonctionne avec la métadonnée de reachability
déjà commitée. 30 nœuds tiennent dans ~3 Go. Aucun comportement divergent du chemin JVM n'a été
observé.

**4. Une seule anomalie de sync sur toute la campagne**, à surveiller sans conclure :
`body apply rejected at height 38: INVALID_BLOCK_ID` suivi d'une pénalité +34 sur un pair, une
seule fois sur 30 nœuds et plusieurs heures — la forme attendue d'une course en-tête/corps
(le pair reorg entre la demande d'en-tête et celle du corps). Aucun ban, aucune récidive.

**5. Le simulateur de transactions produit ~20 % de `INVALID_TRANSACTION_NONCE`** — artefact du
simulateur, pas du nœud : le nonce servi est le confirmé, et un worker qui relit trop tôt après
sa propre confirmation réutilise le même. Le nœud rejette correctement. Réduire en allongeant
l'attente de confirmation si ce bruit gêne la lecture.

### Correctifs d'outillage livrés

| Défaut | Effet | Correctif |
|---|---|---|
| `monitor.sh` mourait sous `errexit` quand tous les `/stats` échouaient d'un coup | La supervision s'arrêtait **au `stop.sh` qui ouvre la partition**, c'est-à-dire juste avant la fenêtre qu'elle devait documenter (constaté aussi en campagne 2) | `set +e` sur la boucle d'échantillonnage ; un cycle en échec produit des lignes « DOWN », pas la fin du monitor |
| Les workers de `sim-tx.sh` et la boucle de `sim-contract.sh` mouraient de la même façon | Les 8 workers se sont arrêtés en silence au premier `stop.sh`, la charge disparaissait sans un mot dans le journal | `set +e` dans les boucles ; les appels wallet en échec sont journalisés, pas fatals |
| `fund_owner` : `A \|\| B && continue` renvoie un échec quand ni A ni B ne sont vrais | Sous `errexit`, `sim-contract.sh start` sortait **sans aucune sortie** au premier mineur suffisamment doté | `if … then continue; fi` explicite |
| `status.sh` mettait `-1` dans les hauteurs pour un nœud DOWN | « écart=204 » et alerte « écart > 5 » dès qu'un nœud était volontairement arrêté — c'est-à-dire en S5, S6 et S7 | Les nœuds DOWN sortent du calcul ; le nombre de répondants est imprimé avec l'écart |
| Contrôle d'état des contrats séquentiel via le wallet CLI | 30 lectures × ~0,5 s = 15 s, soit plusieurs blocs : seuls 3 nœuds sur 30 se retrouvaient « au même tip » et le contrôle ne concluait rien | `statecheck.py` : lectures parallèles, tip relu après coup, groupement par tip |
| Le monitor lancé en arrière-plan mourait avec le shell appelant | Perte de supervision à chaque commande d'orchestration | `setsid nohup … & disown` (documenté dans la procédure) |
| Dotation des portefeuilles de simulation : délai de 3 min | Échec de démarrage à tort — au lancement du réseau, l'inclusion d'une transaction peut prendre plusieurs minutes (rafales de blocs, orphelins qui renvoient les tx en mempool) | Délai porté à 5 min |
| Le portefeuille du simulateur de contrats se vidait (chaque appel réserve `gasLimit × gasPrice`) | Après quelques dizaines d'appels la boucle tournait à vide sur `BALANCE_TOO_LOW` | Re-dotation automatique dès que ce statut apparaît |
| Nombre de mineurs figé à 4, binaire figé sur la JVM, cadence non réglable | — | `RHIZOME_TESTNET_MINERS` (mineurs répartis en `k·N/M`), `RHIZOME_TESTNET_NATIVE`, `RHIZOME_TESTNET_BLOCK_MS` |


---

# Archive — campagne 2 (16 nœuds JVM, 2026-08-08)

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
