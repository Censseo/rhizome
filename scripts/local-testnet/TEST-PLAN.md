# Plan de test — testnet local (30 nœuds natifs)

> **Campagne 3 (2026-08-10).** Les campagnes 1 (10 nœuds) et 2 (16 nœuds) sont closes ; leurs
> résultats et les correctifs qu'elles ont produits sont archivés en fin de document. Cette
> campagne change trois choses : le **binaire natif GraalVM** au lieu de la JVM, **30 nœuds et
> 10 mineurs** au lieu de 16 et 4, et une **charge continue** produite par deux simulateurs
> (transactions et contrats) au lieu de transactions ponctuelles.

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
depuis `RHIZOME_TESTNET_BLOCK_MS` (défaut 10 s). Il faut le régler : au défaut devnet (5 s), 10
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
(10000), `RHIZOME_TESTNET_DIR` (`.testnet/`), `RHIZOME_TESTNET_BASE_PORT` (3000).

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
