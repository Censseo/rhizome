# Plan de test — testnet local (30 nœuds natifs)

> **Campagne 8 (2026-09-04).** Les campagnes 1 (10 nœuds), 2 (16), 3 (30, première campagne
> native), 4 (S11-S15), 5 (ancrage sur la revue adverse, S16-S17), 6 (batterie d'exploits en
> direct, S18-S19) et 7 (six batteries rejouables, S20-S25) sont closes ; leurs résultats et
> correctifs sont archivés en fin de document. Cette campagne ferme le trou que la campagne 7
> avait elle-même laissé au premier rang : la difficulté y était restée **collée à son plancher
> sur 728 blocs sur 728**, de sorte que la boucle de retarget, la défense timewarp et les bornes
> temporelles n'avaient jamais tourné ailleurs qu'en JUnit — et qu'aucun nœud n'avait jamais
> rejoint la chaîne par **snap-sync** ni tourné **élagué**, alors que c'est le premier chemin
> qu'emprunte un opérateur tiers. Elle ajoute deux batteries (**S26** `suite-pow.sh`, **S27**
> `suite-bootstrap.sh`), deux outils (`Anvil.java`, `diffscan.py`), une **paire source/victime
> isolée** qui donne le contrôle exact des horodatages, et une observation sur le **profil
> `testnet`** lui-même (**S28**). Elle rejoue enfin les six batteries de la campagne 7 à
> **difficulté non triviale**, ce qui n'avait jamais été le cas. Son constat central : le retarget
> **régule pour de bon** — la difficulté est montée de 6 à 24 sous une cadence trop rapide, s'est
> arrêtée net sur le plafond du profil, a ramené la cadence à la cible, puis est **redescendue**
> quand on a coupé les trois quarts du hashrate ; et une réimplémentation indépendante du repli
> des fenêtres est d'accord avec la chaîne sur **chaque bloc**.

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

> **Ce que devnet ne peut PAS exercer, par construction.** À difficulté plancher (6) le retarget,
> la défense timewarp et les bornes de difficulté (POW/TIME) sont inertes ; et comme `devnet()`
> démarre à supply ~0 sous une cible S\* ≈300M PDN, la dette de burn reste 0 pour toujours — donc
> **le burn, le franchissement de cible, le plancher R_min et la décroissance (BURN/DECAY/SUPPLY/
> FLOOR, features 008/009) ne se déclenchent jamais** sur ce réseau. Ces familles ne sont prouvées
> qu'en JUnit (`TestNetwork.CURVE_ACTIVE`). L'analyse complète des manques est dans la section
> « Couverture non atteinte » du journal de campagne 6, avec deux scénarios (S18 burn natif en
> réseau réel, S19 crash `kill -9` + recovery) désormais **implémentés et exécutés**.

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

### Les batteries de scénarios (campagne 7)

Les scénarios S0-S19 sont des procédures manuelles ; S20-S25 sont **exécutables**. Chaque
batterie est un script qui joue une famille du catalogue adverse contre le réseau vivant, avec
un verdict par cas, et écrit son TSV dans `.testnet/results/`.

```bash
scripts/local-testnet/run-campaign.sh          # pré-vol + les six batteries + récapitulatif
scripts/local-testnet/run-campaign.sh -n 2 tx  # une batterie, contre le nœud victime 2
scripts/local-testnet/suite-tx.sh              # S20 transactions (INFL, SIG, REPLAY, POOL, CODEC, API)
scripts/local-testnet/suite-wallet.sh          # S21 portefeuilles (WALLET-01..06, boîtes, tokens)
scripts/local-testnet/suite-contract.sh        # S22 contrats (templates + modules adverses, VM-*)
scripts/local-testnet/suite-chain.sh           # S23 en-têtes, oncles/GHOST, supply, explorateur
scripts/local-testnet/suite-net.sh             # S24 transport et surface HTTP (NET-*, API-*)
scripts/local-testnet/suite-persist.sh         # S25 arrêt propre puis SIGKILL, état applicatif
```

Trois outils les servent, dans `scripts/local-testnet/tools/` :

- **`Forge.java`** — forgeur de transactions **signées**. Le wallet CLI refuse par construction
  ce qu'une attaque doit produire (montant négatif, `chainId` étranger, `gasLimit` hors bornes,
  nonce arbitraire) : ses garde-fous *client* masqueraient la porte de consensus qu'on veut
  atteindre. Le forgeur signe exactement ce qu'on lui demande, avec les mêmes primitives que le
  wallet, et imprime la transaction en JSON — la forme qu'accepte `POST /add_transaction_json`.
  Falsifier un champ **après** signature (l'attaque « altéré sous signature ») se fait alors en
  éditant ce JSON, sans code Java. Compilé à la demande dans `.testnet/tools/`.
- **`wasmgen.py`** — émet les modules WASM **adverses** section par section. Les `.wasm` du dépôt
  sont des contrats légitimes ; aucun ne porte les formes que la famille VM décrit (flottants,
  import hors ABI, compteurs déclarés démesurés, mémoire au-delà du cap). Les tests JUnit les
  assemblent en mémoire — pour les POSTER sur un nœud vivant il faut les mêmes octets sur disque,
  et chaque module doit isoler **exactement une** violation, sinon le refus observé ne prouve pas
  ce qu'on croit.
- **`hostile_peer.py`** — un vrai pair hostile : serveur HTTP qui s'annonce à une hauteur absurde
  et répond des corps de 50 Mo (`/peers` : un million d'entrées). C'est NET-03/NET-04 sur une
  vraie socket plutôt que sur un double de test.
- **`chainscan.py`** — balaye les blocs et vérifie l'identité de comptabilité GHOST
  `supply(h) − supply(h−1) == subvention + n × (subvention/2 + subvention/32)`, paramètres lus
  sur le nœud.

> **Lancement des nœuds** : `start.sh` passe par `setsid`, de sorte qu'un nœud survit à la mort
> du shell qui l'a lancé. Ne pas contourner `start.sh` en lançant le binaire à la main.
>
> **Lancement du monitor** : `setsid nohup … & disown`. Sans `nohup` il meurt avec le shell
> appelant dans un environnement d'orchestration — deux campagnes l'ont constaté.

### Les batteries de la campagne 8

Deux batteries de plus, sur le même moule (un TSV par batterie, un verdict par cas), plus un
terrain nouveau : une **paire de nœuds isolés** que la campagne pilote au bloc près.

```bash
scripts/local-testnet/suite-pow.sh        # S26 retarget, bornes temporelles, timewarp (POW-*, TIME-*, RETARGET-*)
scripts/local-testnet/suite-bootstrap.sh  # S27 snap-sync et élagage (BOOT-*)
```

- **`Anvil.java`** — forgeur de **blocs**, ce que `Forge.java` est aux transactions. Il ne
  fabrique **pas** un bloc de zéro : un bloc valide engage une racine d'état que rien hors du nœud
  ne sait calculer (mesuré : un bloc forgé de toutes pièces est refusé en `INVALID_STATE_ROOT`).
  Il **prend** un bloc réel produit par un nœud source, le re-parente sur le tip de la victime,
  mute exactement le champ visé, puis **ré-mine le nonce**. Sans ce ré-minage toute mutation d'un
  champ engagé dans le hash serait rejetée au dernier contrôle (`INVALID_NONCE`) et le scénario
  « passerait » sans rien prouver — c'est la leçon de `BlockForge` (testFixtures), transposée en
  HTTP. L'horodatage n'entrant pas dans l'état (le crédit de coinbase ne dépend que de la hauteur
  et de la supply parente), muter le temps préserve la racine d'état du bloc source : le nœud juge
  alors la règle temporelle, et rien d'autre. Le rejeu **sans** mutation est le témoin de la
  batterie.
- **`diffscan.py`** — le juge du retarget : une **réimplémentation indépendante** de
  `DifficultyAdjustment.nextDifficulty` et de `Retarget.stepWindow` (médiane de 3 comprise),
  confrontée bloc à bloc à ce que la chaîne a réellement accepté. Une divergence est un désaccord
  de consensus, pas une mesure approximative. Il calcule **les deux** prédictions — borne de
  fenêtre médiane et borne brute — et dit laquelle la chaîne a suivie : c'est ainsi que la défense
  timewarp se mesure au lieu de se supposer.

La **paire source/victime** (ports 4406/4407, deux nœuds devnet sans pairs, hors du réseau de
campagne) existe parce que le réseau ne se laisse pas piloter : sur une chaîne minée, les
horodatages sortent d'horloges réelles et personne ne choisit la frontière de fenêtre. La source
mine seule (cadence 3 s, donc difficulté au plancher, donc ré-minage bon marché) et ne sert que de
fournisseur de corps valides ; la victime n'a **pas** de mineur et n'avance que par `/submit`. La
campagne lui impose donc le calendrier à la milliseconde et visite tout le domaine du retarget —
plafond, plancher, pas maximal — en quelques minutes. Une conséquence à connaître : la source ne
produit **aucun oncle** (elle est seule), et c'est ce qui rend le re-parentage licite, la supply
d'en-tête ne dépendant de la difficulté que par les termes oncle/neveu.

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
- Tous les nœuds atteignent la même hauteur **et le même `tipHash`** ; les écarts > 2 blocs
  pendant > 30 s sont des anomalies.
- **`status.sh` affiche `tips distincts: 1`** hors fenêtre de partition. À hauteur, difficulté
  et travail égaux, deux camps sur des branches différentes sont indiscernables par tout le
  reste de `/stats`.
- `syncEclipsed == false` et `syncRoundsWithoutProgress == 0` en régime sain (un nœud nourri
  par gossip ne fait légitimement rien en sync).
- **Chaque nœud atteint le maillage que le cap anti-éclipse autorise**, sans doublon ni
  auto-référence : `min(N − 1, 16 découverts + 2 seeds)` — donc 18 pairs à 30 nœuds (le cap
  mord), et 11 pairs à 12 nœuds (maillage complet, le cap ne mord pas). Le critère est le cap,
  pas la constante 18.
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

### S20 — Batterie « transactions » *(INFL, SIG, REPLAY, POOL, CODEC, API — `suite-tx.sh`)*
1. `suite-tx.sh <nœud victime>` : chemins nominaux d'abord (transfert lu depuis un nœud
   **distant**, comptabilité des frais, rafale de nonces contigus), puis les exploits — montant
   négatif, `Long.MAX`, dépassement de solde, débordement montant+frais, `chainId` étranger,
   montant/destinataire/nonce altérés **sous signature**, expéditeur et clé de signature
   échangés, rejeu d'une transaction déjà minée, double dépense au même nonce, corps malformés
   et surdimensionnés, POST cross-site et forme DNS-rebinding.
2. **Passe si** : chaque refus porte le **statut exact** attendu (`INVALID_TRANSACTION_AMOUNT`,
   `BALANCE_TOO_LOW`, `INVALID_CHAIN_ID`, `INVALID_SIGNATURE`, `WALLET_SIGNATURE_MISMATCH`,
   `INVALID_TRANSACTION_NONCE`) et non un « refusé » générique ; le nonce futur est admis mais
   **ne déplace ni solde ni nonce** tant que le trou n'est pas comblé ; et le refus est
   **gratuit** — le nœud victime continue de produire, une transaction valide de la même source
   passe encore, `degraded` reste `null`.

### S21 — Batterie « portefeuilles » *(WALLET-01..06 — `suite-wallet.sh`)*
1. `suite-wallet.sh` : clé **chiffrée** (passphrase-file), permissions du fichier, refus d'écrire
   une clé en clair sans opt-in, refus d'écraser une clé existante, mauvaise passphrase,
   enveloppe falsifiée d'un octet, marqueur d'enveloppe usurpé sur un fichier en clair,
   épinglage `chainId` **trust-on-first-use**, bornes client (montant sous l'unité de base,
   `gasPrice` hors bornes, somme de contrôle d'adresse), URL de nœud portant des métacaractères
   JSON, puis le parcours complet du CLI : `box-create/update/spend/show/list` et
   `token-mint/transfer/burn/show/balance/list`, chaque effet relu depuis un nœud distant.
2. **Prérequis** : un nœud d'une **autre chaîne** joignable (le script attend `base+90` ;
   `RHIZOME_NETWORK=testnet` suffit, `chainId` 2 contre 3 en devnet) — sans lui l'épinglage TOFU
   n'est pas testable, et le cas échoue plutôt que d'être silencieusement sauté.
3. **Passe si** : le fichier chiffré ne contient aucune clé privée en clair et est en `600` ;
   la mauvaise passphrase et l'enveloppe falsifiée sont refusées ; un envoi vers le nœud de
   l'autre chaîne **abandonne avant de signer** (message nommant les deux `chainId`) alors qu'une
   lecture seule (`balance`) y reste permise ; et tous les effets box/token convergent vers le
   nœud distant.

### S22 — Batterie « contrats » *(VM-01..21, VM-16 — `suite-contract.sh`)*
1. `suite-contract.sh` : déploiement et exécution **réels** des templates du dashboard (counter,
   token, amm, emitter, agent_wallet, pair, router, launchpad, logtree), déterminisme du
   `call_readonly` sur **tous** les nœuds, puis dépôt des modules adverses de `wasmgen.py`.
2. **Passe si** : chaque template s'installe et répond ; la même lecture rend le même octet sur
   les N nœuds ; **aucun module adverse n'est installé**, ni sur le nœud victime ni sur un nœud
   distant ; un `gasLimit` au-dessus de `maxTxGas` est refusé **à l'admission**
   (`GAS_LIMIT_EXCEEDED`) ; un appel vers un contrat inexistant est **débité** malgré tout.
3. **À savoir avant de lire les verdicts** : un DEPLOY portant un module invalide est **admis au
   mempool** — c'est une transaction bien formée et payée. Le refus tombe à l'**exécution**, et
   se lit à l'état d'après-minage (`/contract` → `exists:false`), jamais au statut d'admission.
   Même forme pour un `TOKEN_TRANSFER` d'un non-détenteur : admis, puis annulé en douceur, nonce
   consommé, rien déplacé. **L'admission n'est pas une autorisation.**

### S23 — Batterie « chaîne » : oncles, GHOST, supply *(UNCLE, SUPPLY, POW — `suite-chain.sh`)*
1. `suite-chain.sh` : `chainscan.py` lit chaque bloc d'une fenêtre et vérifie le chaînage
   `lastBlockHash`, la présence d'oncles, l'identité de récompense
   `supply(h) − supply(h−1) == subvention + n × (subvention/2 + subvention/32)`, la difficulté,
   puis l'explorateur (même bloc servi par deux nœuds, transaction retrouvée par `txid`,
   historique d'adresse).
2. **Passe si** : zéro rupture de chaînage ; **au moins un oncle** dans la fenêtre (sinon la
   fenêtre ne prouve rien du GHOST) ; **zéro** bloc dont le delta de supply contredit
   l'identité ; les mineurs d'oncle appartiennent au jeu configuré.
3. Ce scénario existe parce que la campagne 6 a constaté que le plan *affirmait* la production
   d'oncles sans qu'aucune campagne ne l'ait jamais mesurée.

### S24 — Batterie « transport et surface HTTP » *(NET-01/03/04/06/10, API-07/09/12 — `suite-net.sh`)*
1. `suite-net.sh` : lance un nœud **strict** (`base+91`, sans `RHIZOME_ALLOW_PRIVATE_PEERS`, donc
   filtre SSRF actif — les nœuds du testnet l'ont désactivé pour se voir en 127.x) et un **pair
   hostile** réel (`hostile_peer.py`), puis : cibles internes et métadonnées cloud en `/add_peer`,
   schémas dégénérés, quatre orthographes du même pair, pair servant 50 Mo, 40 blocs poubelle sur
   `/submit`, index hors bornes sur dix routes de lecture, 400 lectures en rafale, puis 400 avec
   un `X-Forwarded-For` tournant.
2. **Passe si** : toute cible interne et toute URL dégénérée est refusée par le nœud strict ;
   quatre orthographes ne font qu'un pair ; le nœud survit au pair hostile et continue de
   produire ; les blocs poubelle sont tous refusés **et** une transaction honnête de la même
   source passe encore ; le limiteur mord (429) **et** un `X-Forwarded-For` tournant ne l'esquive
   pas.

### S25 — Batterie « persistance » : arrêt propre puis SIGKILL *(PERS, A6 — `suite-persist.sh`)*
1. `suite-persist.sh <nœud>` : empreinte d'état (tip, racine SMT, sortie du contrat témoin,
   solde, nonce), arrêt **propre** (SIGTERM) → redémarrage → comparaison ; puis **SIGKILL** en
   pleine production → redémarrage → comparaison.
2. **Passe si** : à chaque cycle la base se rouvre sans nouvelle trace de corruption, la chaîne
   n'est **pas tronquée**, le nœud rejoint le tip du témoin et son empreinte est **identique**
   (racine d'état et état de contrat compris), `degraded == null` — et le reste du réseau a
   continué à produire pendant la fenêtre de mort.
3. Complément de S19 (campagne 6), qui prouvait le SIGKILL pour le seul **grand livre** : ici
   l'état de **contrat** et la **racine SMT** sont dans l'empreinte comparée.

### S26 — Batterie « PoW et temps » : retarget, timewarp, bornes *(POW, TIME, RETARGET — `suite-pow.sh`)*

1. `suite-pow.sh <nœud victime>` — quatre volets. (a) `diffscan.py` rejoue tout l'historique du
   réseau de campagne : difficulté de chaque bloc, changements seulement en frontière + 1, pas
   borné à `MAX_STEP_BITS = 4`, bornes `[minDifficulty, maxDifficulty]`, continuité des liens,
   convergence des dernières fenêtres dans la bande morte. (b) Sur la paire isolée, les portes :
   bloc sans travail (`INVALID_NONCE`), difficulté déclarée trop faible **et** trop forte
   (`INVALID_DIFFICULTY`), horodatage au-delà de la fenêtre future (`BLOCK_TIMESTAMP_IN_FUTURE`)
   puis **dedans** (accepté — une borne est une borne, pas un interdit), horodatage à la médiane
   du passé (`BLOCK_TIMESTAMP_TOO_OLD`), horodatage antérieur au parent
   (`BLOCK_TIMESTAMP_TOO_CLOSE`), chaque fois encadré d'un rejeu honnête témoin. (c) **Timewarp** :
   une frontière de fenêtre, et une seule, est gonflée du maximum légal ; on mesure quelle règle
   la chaîne a suivie. (d) **Balayage** : calendrier serré jusqu'au sommet, puis calendrier très
   lâche jusqu'au plancher, avec un redémarrage au sommet pour vérifier que la difficulté est
   **reconstruite** depuis les horodatages et non mise en cache.
2. **Passe si** : zéro divergence entre la chaîne et la réimplémentation indépendante, zéro pas
   hors borne, zéro changement hors frontière ; chaque rejet porte le **statut exact** attendu et
   le témoin qui l'encadre est accepté ; la frontière gonflée **sépare** les deux règles et la
   chaîne suit la médiane ; le balayage revient au plancher et s'y arrête ; après redémarrage la
   difficulté et le tip sont inchangés.
3. Ce que la batterie ne teste **pas** : une vraie dérive d'**horloge machine**. Sans `faketime`
   (absent de la machine) on ne décale pas l'horloge d'un nœud ; seule la **règle d'en-tête** est
   mesurée, des deux côtés de la borne. Un nœud dont l'horloge dérive au-delà de la fenêtre voit
   ses blocs refusés par ses pairs — c'est ce que TIME-01a prouve — mais la conséquence
   systémique (le nœud décroche du réseau) reste extrapolée.

### S27 — Batterie « bootstrap » : snap-sync et élagage *(BOOT — `suite-bootstrap.sh`)*

1. `suite-bootstrap.sh <nœud victime>` — un nœud neuf rejoint par `RHIZOME_SYNC=snap` depuis un
   fournisseur qui matérialise ses instantanés (`RHIZOME_SNAPSHOT_EVERY`), puis un nœud élagué
   (`RHIZOME_PRUNE`) rejoint le réseau de campagne, et une rétention sous le plancher de sûreté
   est refusée au démarrage.
2. **Passe si** : le pivot annoncé est **enterré** sous `maxReorgDepth` ; le nœud snap annonce
   `prunedBelow = pivot + 1` ; la racine d'état du premier bloc **au-dessus** du pivot est
   identique chez les deux (un état adopté faux aurait échoué en `INVALID_STATE_ROOT`) ; le
   suffixe est rattrapé et le nœud suit ensuite le tip **et** la racine du fournisseur ; sous le
   filigrane `/sync` répond **410 GONE** en portant le filigrane et la vue JSON refuse de même ;
   au-dessus, les corps sont servis normalement ; `RHIZOME_PRUNE` sous le plancher **refuse au
   démarrage** en nommant le plancher, et rien n'écoute ensuite ; un nœud d'archive, lui, sert
   toujours le bloc 1 et n'annonce aucun filigrane.
3. Deux pièges d'opérateur mesurés, pas déduits : l'instantané **ne survit pas au redémarrage**
   (le fournisseur repart à `snapshotPivot = 0` et doit re-matérialiser), et le pivot n'est
   adoptable que s'il est enterré sous `maxReorgDepth` — donc un `RHIZOME_SNAPSHOT_EVERY`
   inférieur ou égal à `maxReorgDepth` n'offre **jamais** d'instantané utilisable, le pivot suivant
   le tip de trop près.

### S28 — Le profil `testnet` lui-même, sous cadence mal calibrée *(observation, pas batterie)*

1. Trois nœuds `RHIZOME_NETWORK=testnet` (ports 4420-4422, 2 mineurs), producteurs cadencés à 2 s
   alors que le profil vise **90 s** : c'est l'erreur de calibrage qu'un opérateur commet
   naturellement en réutilisant les réglages d'un devnet. Le javadoc de `NetworkParameters.devnet()`
   la décrit ; la campagne la **mesure**.
2. **À retenir** : ce profil hérite de `maxDifficulty = 255` (mainnet), il n'a donc **pas** le
   garde-fou à 24 du devnet ; sa fenêtre de retarget est de 100 blocs et sa fenêtre future de
   120 s. Un testnet public doit être cadencé sur `desiredBlockTimeSec`, sinon la difficulté monte
   de 4 bits par fenêtre jusqu'à ce que la cadence rejoigne la cible — ce qui est le comportement
   **correct**, mais transforme un réseau de test en réseau lent pendant plusieurs fenêtres.

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

## Plan — campagne 11 : clôture des sept manques listés en campagnes 9/10

Les journaux de campagne 9 et 10 répètent, à l'identique, sept lacunes. Ce plan les ordonne par
dépendance plutôt que par ordre d'apparition : certaines sont prérequises à d'autres, et deux
d'entre elles sont des décisions déjà assumées ailleurs dans ce dépôt, pas des manques à combler
avant un testnet public.

### Phase 0 — Durcir l'outillage avant de le rejouer en vrai (ferme #6, prérequis de #1/#2)

`tunnels.sh` et `partition.sh` portent chacun, dans leur propre en-tête, la mention qu'ils n'ont
**jamais tourné contre du matériel réel** — la campagne 10 a validé la *technique* (tunnel SSH
bidirectionnel) mais via des scripts ad hoc (`fix-private-peers.sh`, `reset-chain-data.sh`,
`add-local-peers.sh`), pas ces fichiers. Les rejouer sans premier essai à blanc serait imprudent :
`partition.sh` se qualifie lui-même de « script le plus dangereux de ce harnais » (une règle non
révoquée retire silencieusement et définitivement un seed d'un réseau public).

1. Peupler un `inventory.tsv` réel (gitignored — jamais commité, seul `.example` l'est) à partir
   de la topologie de campagne 10 (3 VM OVH en `role=seed` + 2 bancs d'essai locaux en `role=peer`).
2. `tunnels.sh up` puis `check` contre cet inventaire — vérifier la résolution 13000+index et
   `/stats` à travers chaque tunnel. Puis **injecter une panne** : `kill -9` un process `ssh -N`
   en cours de route et vérifier ce que le script documente comme non vérifié — la détection d'un
   tunnel mort (`ServerAliveInterval`/`CountMax`) plutôt qu'un blocage silencieux qui se lirait
   comme « nœud DOWN » côté harnais.
3. `partition.sh apply` sur un **hôte jetable**, pas les 3 seeds — vérifier séparément les trois
   garde-fous : le watchdog `systemd-run` supprime bien la table si on tue ce script en plein
   milieu (`kill -9` du process appelant) ; le `trap EXIT` guérit sur `Ctrl-C` ; `apply` refuse
   catégoriquement un index `role=seed`.
4. Une fois les deux essais concluants, retirer le bandeau « NON EXERCÉ CONTRE DU MATÉRIEL RÉEL »
   des en-têtes et documenter les résultats ici avant de passer à la phase 1.

Cette phase ne touche à aucun hôte de production ni aux 3 seeds de campagne — elle peut se faire
sur un hôte jetable indépendant.

#### État (2026-09-24) — partiellement exercée depuis une session sans accès infra

Cette session n'a **ni clé SSH ni agent** (`ssh-add -l` échoue, aucune `id_*` sous `~/.ssh/`) et ce
devbox n'a **ni `nft` ni `systemd-run` installés** — les deux volets réellement « matériel réel »
de cette phase (tunnel SSH vers un hôte distant, pose de règles nftables sur un hôte jetable) sont
donc restés hors de portée et le bandeau des deux scripts n'a **pas** été retiré. Ce qui a pu être
exercé pour de vrai, sans toucher à aucune infrastructure externe :

- `tunnels.sh check` — chemin `local`/direct (pas le chemin tunnel SSH) exercé contre un serveur
  HTTP jetable sur `127.0.0.1:18801` : échec correctement rapporté port fermé, succès correctement
  rapporté port servi, sur la même invocation sans redémarrage du script.
- `partition.sh apply` — garde-fou n°3 (refus catégorique d'un index `role=seed`) confirmé, erreur
  levée avant tout appel système.
- `partition.sh apply` — garde-fou n°1 (watchdog `systemd-run`) confirmé *fail-closed* même dans le
  pire cas : sur un hôte sans `systemd-run` du tout, `apply` abandonne proprement avec « aucune
  règle posée » plutôt que de poser des règles non protégées. Découvert involontairement (ce
  devbox n'a pas l'utilitaire), mais c'est exactement le cas que le garde-fou doit couvrir.
- Petit constat d'outillage, à garder à l'œil au premier run réel : `heal_host` avale toute erreur
  (`2>/dev/null || true`) et annonce « table supprimée (ou déjà absente) » même quand `nft`
  lui-même est absent de l'hôte — idempotence voulue, mais qui masquerait aussi un hôte mal
  provisionné plutôt que de le signaler.

**Reste à faire, par un opérateur avec accès SSH réel et un hôte jetable doté de `nft`+`systemd`** :
les points 2 et 3 de la liste ci-dessus (tunnel SSH réel + panne injectée ; coupure nftables réelle
sur un hôte jetable) n'ont toujours pas tourné. Une fois faits, retirer les bandeaux d'en-tête et
compléter cette section plutôt que la réécrire.

#### État (2026-09-28) — les deux volets réels ont tourné ; un rejeu ciblé a dû réparer le harnais d'abord

Le « Reste à faire » ci-dessus est clos. Les points 2 et 3 ont tourné contre du matériel réel :
hôte jetable (l'hyperviseur Proxmox du staging lui-même — jamais les VM seeds), deux nœuds
`staging` co-hébergés (127.0.0.2:4500 / 127.0.0.3:4501, garde nftables `rhizome_phase0_guard`
n'ayant rien laissé sortir de `lo`), pilotés depuis un devbox avec clé SSH dédiée. Deux runs,
le second ne reprenant que ce que le premier n'avait pas mesuré honnêtement.

**Run 1 (2026-09-25, `phase0.sh`) — 12 PASS / 4 FAIL, dont cinq résultats invalides à cause du
harnais lui-même, pas des scripts.** Un tunnel SSH résiduel de campagne 10 occupait encore le
port de contrôle local 13001 : toutes les lectures « nœud B via tunnel » interrogeaient seed-1.
Sont donc sans valeur les FAIL T1.1, T1.3, T1.4 (tests de la ligne 1 du tunnel) et T2.2d
(reconvergence lue contre seed-1), ainsi que le PASS T2.2b (« divergence » lue contre seed-1) —
exactement le piège « un tunnel mort se lit comme un nœud différent » contre quoi le pré-vol
`check` existe. Ce que ce run a prouvé pour de vrai (lectures côté hôte, non faussées) : les
règles nftables passent bien `ssh "sudo nft …"` et la coupure est réelle — à la levée, le journal
du nœud A montre `Synced from http://127.0.0.3:4501: REORGED -> height 18` et A/B reviennent au
même tip ; garde-fou n°1, le watchdog `systemd-run` lève seul la table ~62 s après un `kill -9`
du porteur ; garde-fou n°2, le `trap EXIT` guérit sur SIGINT comme sur SIGTERM ; garde-fou n°3,
un index `role=seed` est refusé sans aucune règle posée ; enfin le tunnel 0 sert le bon nœud et
`check` signale un nœud gelé derrière un tunnel vivant.

Le run a de plus confirmé deux défauts de `partition.sh` et un de `tunnels.sh`, corrigés dans la
foulée :

1. `apply` guérissait dans son propre `trap EXIT` dès son retour — la partition ne durait pas
   (OBS T2.1 du run 1). `apply` bloque maintenant `<durée_s>` ; pour garder la main pendant la
   coupure, le lancer en arrière-plan puis le `kill` (un `kill -9` laisse le watchdog distant
   guérir à l'échéance).
2. Un second `apply` refusait de s'armer tant que le watchdog du premier courait : nom d'unité
   systemd fixe par index (OBS T2.1b du run 1). Le nom embarque maintenant un suffixe par apply
   (`rhizome-partition-heal-<RUN>-<idx>`) et `heal_host` désarme aussi les minuteurs restants
   puis vérifie que la table a bien disparu — ce qui ferme aussi le « petit constat
   d'outillage » listé en 2026-09-24 : `heal` n'annonce plus « supprimée » sans vérification.
3. `tunnels.sh up` annonçait « ouvert » un tunnel mort au démarrage (port local déjà pris). Il
   vérifie désormais chaque tunnel 3 s après lancement et renvoie un code d'échec ; la base des
   ports de contrôle est surchargeable (`RHIZOME_TUNNELS_BASE`) pour ne jamais retomber sur un
   port résiduel.

**Run 2 (2026-09-28, `phase0b.sh`, rejeu ciblé) — 12 PASS / 0 FAIL.** Ports de contrôle dédiés
23000/23001 avec pré-vol de disponibilité locale, donc lectures par tunnels prouvées cette fois
(T1.2 : tip lu par le tunnel == tip lu en direct, pour A comme pour B) :

- T1.0–T1.4 : `up` n'annonce plus de tunnel mort au démarrage ; `check` OK sur les deux tunnels ;
  `kill -9` d'un `ssh -N` signalé (« aucun tunnel actif ») pendant que l'autre reste OK ; `up`
  relancé ne rouvre que le tunnel mort et `check` repasse.
- T2.1 : `apply` bloque et la partition tient (table PRESENT 10 s après la pose, process vivant).
- T2.2 : divergence réelle — tips distincts dès +30 s (h=8 des deux côtés, hashes différents) ;
  A et B minent chacun sa branche jusqu'à la levée (A h=17 / B h=13), lus par les tunnels
  vérifiés, l'observation passant par 127.0.0.1 que les règles ne visent pas.
- T2.3 : à l'échéance des 150 s, `apply` sort (rc=0), table ABSENT et zéro watchdog armé.
- T2.4 : reconvergence en ≤ 20 s après la levée (même tip h=20 des deux côtés),
  `REORGED -> height 20` dans le journal de B.
- T2.5 : second `apply` lancé juste après un `kill -9` du premier — table PRESENT et 4 watchdogs
  armés (2 orphelins + 2 neufs) : le cas T2.1b qui échouait au run 1 est fermé.
- T2.6 : SIGTERM sur `apply` — table levée et les 4 watchdogs désarmés, orphelins compris (le
  glob de `heal_host` ne distingue pas les siens).

Les garde-fous T3.x du run 1 n'ont pas été rejoués : ils mesuraient l'hôte, pas les tunnels, et
restent valables tels quels.

**Reste non couvert, assumé** : la mort CÔTÉ DISTANT d'un tunnel (`ServerAliveInterval`/
`ServerAliveCountMax` quand le sshd distant disparaît). La provoquer demanderait de tuer un sshd
de l'hyperviseur — exclu du périmètre. Les bandeaux « NON EXERCÉ CONTRE DU MATÉRIEL RÉEL » sont
retirés des deux en-têtes ; chacun liste maintenant ce qui reste non vérifié. Phase 0 considérée
fermée pour l'ouverture publique ; la phase 1 (soak multi-jours sur les seeds) attend son feu
vert.

### Phase 1 — Campagne 11 elle-même : soak + charge + coupure réelle (ferme #1, #2, #3, prépare #4)

Reprend la topologie de campagne 10 (3 VM OVH + 2 bancs d'essai locaux), pilotée cette fois par
l'outillage versionné validé en phase 0.

**J0 — mise en place.**
- Purger les 3 VM depuis la genèse avec `RHIZOME_ALLOW_PRIVATE_PEERS=true` déjà posé dans
  `node.env` dès le premier démarrage (le correctif était découvert *pendant* la campagne 10 ;
  cette fois il est connu d'avance).
- `tunnels.sh up` + `check` (pré-vol obligatoire).
- Démarrer `sim-tx.sh` et `sim-contract.sh` en continu contre au moins deux nœuds distincts —
  jamais fait contre un déploiement réel jusqu'ici (campagne 10 n'avait que du minage organique).
- Démarrer `monitor.sh` (alarmes StaleTip/DiskLow/SeedDisagreement) pointé sur les 5 nœuds via les
  ports de contrôle des tunnels.

**J1 à J3–J7 — soak réel (ferme #3).** Aucune intervention hors incident. Mesurer croissance
RocksDB/jour et décroissance des scores de ban sur plusieurs jours — la mesure que le chantier 3
vise et qu'une fenêtre de quelques dizaines de minutes ne peut pas donner.

**Un jour choisi en cours de soak — coupure réelle (ferme #1).** Avec le garde-fou n°3 de
`partition.sh` (jamais de seed partitionné), l'axe disponible avec 5 hôtes est locaux-vs-VM —
exactement l'axe WAN que la campagne 10 a mesuré *sain* ; cette fois on le coupe puis on le guérit,
deux essais distincts :
- une coupure **courte** (sous ~13 min au rythme observé de campagne 10, donc sous
  `maxReorgDepth`=120 blocs) : la guérison doit être automatique dès la levée de la règle ;
- une coupure **longue** (délibérément au-dessus du seuil) : `REORG_TOO_DEEP` attendu côté camp
  isolé, guérison par la procédure déjà rodée en campagne 10 (purge + relance) — cette fois
  documentée comme procédure plutôt que découverte par accident, à verser dans
  `docs/operations/runbooks.md` RB-01.
- `partition.sh status` avant/après chaque coupure pour confirmer pose et absence de règle
  résiduelle.

**Fin de soak — checkpoint (ferme #4, chantier 0.4).** Avant de planifier la publication elle-même,
vérifier où ce mécanisme est censé vivre dans ce dépôt — rien trouvé pour l'instant qui aille
au-delà de `verify-genesis.sh` (qui vérifie la genèse, pas un checkpoint de hauteur) : **chantier
0.4 reste à spécifier**, pas seulement à exécuter. Ne pas improviser un format ici ; le poser
d'abord comme question de conception séparée une fois la hauteur/l'historique de cette campagne
disponibles comme donnée d'entrée.

**Sortie attendue.** Nouvelle section journal (« campagne 11 ») dans ce fichier ; en-têtes
`tunnels.sh`/`partition.sh` mis à jour ; `runbooks.md` RB-01 gagne sa première coupure réseau
physique réelle documentée (pas seulement single-host).

### Phase 2 — Multi-région (ferme #5)

Ne dépend d'aucun nouvel outillage — `inventory.tsv`/`tunnels.sh`/`partition.sh` sont déjà
génériques par hôte, seules de nouvelles lignes d'inventaire (région/hébergeur différents)
seraient nécessaires. Dépend en revanche d'un provisioning hors de portée de cette session
(≥1 hôte dans une région/chez un hébergeur distinct des 3 VM OVH actuelles) : **à demander**,
pas à planifier plus finement tant que l'accès n'existe pas. Une fois obtenu, la mesure elle-même
est simple : écart de propagation d'un même bloc entre un seed EU et un seed hors-EU.

### Phase 3 — BURN/DECAY en réseau réel (ferme #7 — priorité basse, décision de chantier 0.5)

Structurellement hors d'atteinte sur `devnet`/`staging` tels que calibrés aujourd'hui (supply de
départ loin de `S*`, donc `debt` toujours nul). Deux options pour plus tard, à trancher séparément
de ce plan — toucher au calibrage de genèse d'un profil est consensus-critique
(cf. CLAUDE.md, WHITEPAPER.md) et ne doit pas être décidé comme sous-produit d'une campagne réseau :
(a) un profil dédié dont la genèse démarre proche de `S*`, ou (b) accepter la couverture JUnit
(`TestNetwork.CURVE_ACTIVE`) comme suffisante jusqu'à l'approche de mainnet. **Recommandation : ne
pas bloquer un testnet public là-dessus** — ce dépôt le traite déjà comme une lacune assumée de
chantier 0.5, pas une régression.

### Ce qui gate réellement un testnet public

Phases 0 et 1 sont les seules qui conditionnent l'ouverture à du trafic réel : un réseau public
subira de vraies coupures et de la vraie charge dès le premier jour, et ni l'une ni l'autre n'a
encore été exercée pour de vrai. Phases 2 et 3 sont des risques à porter, déjà nommés comme tels
ailleurs dans ce dépôt (`docs/operations/spec.md`, « Known limits ») — pas des conditions
bloquantes.

## Journal de résultats — campagne 11 (staging, 3 VM OVH + 2 bancs locaux, J0 + coupures 2026-09-28, soak en cours)

**Contexte.** Phase 1 du plan ci-dessus, pilotée par l'outillage versionné validé en phase 0 :
`inventory.tsv` réel (3 VM OVH `role=seed`, port 3000, LAN privé 10.10.10.11/12/13, accès par
hôte de saut + 2 bancs `role=peer` sur le devbox), purge des 3 seeds depuis la genèse sur le
binaire HEAD, puis charge continue — `sim-tx.sh` (8 workers) et `sim-contract.sh` (compteur +
token WASM déployés en chaîne, appels en boucle) contre les 5 nœuds, et `monitor.sh` dessus.
Les trois « jamais fait contre un déploiement réel » du plan (ferme #2, #3, et la supervision
sur déploiement réel) tournent depuis le J0 ; les deux coupures de ferme #1 ont été exécutées le
jour même. Le soak lui-même est multi-jours par construction : cette entrée documente J0, les
coupures, et l'état dans lequel le soak est laissé tourner (handover en fin d'entrée).

**J0 — la topologie `-L` seule ne suffit pas : la leçon PEX de campagne 10, re-démontrée par son
absence.** Premier câblage avec les seuls tunnels `tunnels.sh` (un `-L` par VM) : les bancs
joignent les seeds, les seeds ne peuvent pas joindre les bancs, et le PEX empoisonne les deux
camps — les bancs annoncent leur URL self (`localhost:13003`, joignable par personne) et
apprennent les URL LAN `10.10.10.x` des seeds (non routables depuis le devbox) ; les seeds
apprennent `localhost:13003/13004` et tentent de les joindre sur leur propre loopback.
`PeerDiscovery` jette les pairs des deux côtés, chaque banc mine sa branche : le réseau se scinde
en 3 camps en ~10 min — exactement le schéma qui avait imposé le tunnel bidirectionnel à la
campagne 10, cette fois comme contre-expérience contrôlée (l'alerte `RÉSEAU SCINDÉ` du monitor a
déclenché puis se résolu à la réparation). Fix, pattern campagne 10 : un tunnel `-R` dédié vers
seed-1 (forwards 127.0.0.1:14003/14004), `RHIZOME_PEERS` de seed-1 étendu à ces deux URL,
`RHIZOME_ADVERTISE=http://127.0.0.1:1400x` sur chaque banc (l'URL que seed-1 sait joindre ;
les tentatives de seed-2/3 échouent sur leur propre loopback, bruit inoffensif comme en 10).
Convergence 5/5 au même tip, stable depuis. Les `-R` restent un complément de campagne —
`tunnels.sh` n'exprime pas encore la direction inverse (aucune colonne d'inventaire pour ça) ;
c'est le restant de l'« à reporter dans l'outillage versionné » de campagne 10.

**Trouvailles d'outillage (J0).**

1. `tunnels.sh` visait `127.0.0.1:<port>` côté distant : les seeds staging bindent leur IP LAN,
   le tunnel montait « vert » (processus vivant) et `/stats` ne répondait jamais à travers.
   Corrigé et commité : le forward vise `p2p_ip` (colonne 3, l'adresse de BIND du nœud) ;
   les inventaires en loopback gardent le comportement d'avant.
2. Le garde DNS-rebinding de l'API est sensible au port : `Host: 127.0.0.1:13000` reçoit
   `{"error":"host not allowed"}` alors que `127.0.0.1:3000` est autorisé. Chaque seed porte
   donc `RHIZOME_ALLOWED_HOSTS=127.0.0.1:1300x` — l'autorité de contrôle locale que lui donne la
   convention 13000+index. Sans ça, tout le harnais « curl sur loopback » est muet à travers les
   tunnels (monitor, sims, wallet CLI), et ce sans aucun rapport avec l'état du nœud.
3. Un `Penalized peer http://127.0.0.1:13000 +34 (served an invalid chain)` one-off du banc A
   contre seed-1, pendant la fenêtre où le banc frais se synchronisait alors que les seeds
   finissaient eux-mêmes de converger après leur redémarrage. Jamais reproduit sur le maillage
   sain (zéro pénalité depuis, plusieurs heures) ; à réexaminer si ça revient. La convergence
   s'est faite quand même au relancement suivant.
4. Financement des sims : dotés AVANT le lancement des simulateurs depuis la clé de genèse
   staging `hot` (allocation : 10M PDN) — 8×50 PDN (workers) + 500 PDN (portefeuille de
   contrats), frais `MIN_FEE`, nonce attendu entre chaque envoi. Les `fund_workers`/`fund_owner`
   des sims détectent « déjà doté » et passent : ni attente de maturité des coinbases, ni
   dépendance aux clés de mineurs, ni faucet.

**Coupure courte — guérison automatique (ferme #1, volet consensus).** 10:03:38 UTC : kill des
4 processus ssh (3 `-L` + le `-R`). Sur cette topologie, TOUT le trafic locaux↔VM passe par là —
c'est la coupure physique disponible, et elle est totale. Fenêtre 5,5 min : trois camps (seeds
~6,6 s/bloc, banc A ~7,4, banc B ~7,8 — les bancs ne se voient pas entre eux sans les seeds),
fourches ~50-60 blocs, très sous `maxReorgDepth`=120. Tunnels restaurés : même tip sur les 5
nœuds en ≤ 45 s, `REORGED` dans les journaux des bancs. **Réserve honnête** : coupure TRANSPORT
(SSH), pas nftables — `partition.sh` reste inutilisable sur l'axe locaux-vs-VM (garde-fou n°3 :
jamais de règle sur un seed, délibéré et maintenu ; et le devbox n'a ni nft ni systemd-run, donc
pas de camp rule-bearing local non plus). Le comportement de consensus visé par ferme #1 —
fourche réelle entre machines distinctes, guérison automatique sous l'horizon, sans intervention —
est prouvé ; le vecteur nftables de la coupure reste celui de la phase 0 (hôte jetable, camps
sans seed).

**Coupure longue — `REORG_TOO_DEEP` en vrai, puis RB-01 de bout en bout.** 10:11:03 UTC, même
vecteur, 30 min : profondeurs de fourche ~200-260 blocs (point de fourche ~h=353), délibérément
au-delà de l'horizon des deux côtés. À la restauration : les bancs loggent
`past the reorg horizon; nothing to adopt` (12 et 14 lignes), refusent la chaîne seed plus lourde
et CONTINUENT de miner leur branche — `degraded=null`, `syncPeersBanned=0` pendant tout
l'incident : exactement la signature documentée de RB-01 (une branche au-delà de l'horizon n'est
pas une faute ; pas de ban, par design). **Nuance opérationnelle versée au runbook** :
`syncRoundsWithoutProgress` est resté à 0 chez les nœuds isolés — ils comptent leurs PROPRES
blocs comme progrès de hauteur ; le symptôme fiable est la ligne « past the reorg horizon » plus
l'écart de tip persistant, pas le compteur de stall. RB-01 déroulé tel qu'écrit : CONFIRM (les 3
seeds unanimes h=647, tw=172544 ; bancs tw=164608/162816, minoritaires en travail cumulé, lu sur
chemins indépendants), ACT (purge destructive des deux bancs, relance, resync complet), VERIFY
(même tip h=675 que les seeds ~90 s après relance, `stall=0`, `degraded=null`). Première
exécution réelle de la procédure sur une coupure multi-machines ; la récupération a coûté ~90 s.

**Comportement observé hors incidents.** À 5 mineurs (3 seeds + 2 bancs) et difficulté au
plancher 8, la cadence agrégée oscille dans 4,4-7,6 s/bloc — contre 29 s mesurés à 3 mineurs
avant la campagne : la cible de 5 s du profil est tenue à ce hashrate, la marge du plan
« ~13 min pour 120 blocs » était donc pessimiste, les durées de coupure ont été recalculées sur
le rythme réel. Des fourches courtes organiques (2 branches, 6-30 s) surviennent et se résolvent
seules ; le seuil « 3 cycles consécutifs » du monitor les laisse la plupart du temps sous le
radar et n'a alerté que sur les vrais événements (les 2 coupures, la scission à 3 camps de J0).

**Volet smart contracts (exercé le 2026-09-28, pendant le soak).** Trois terrains, produit sans
défaut trouvé. (a) JUnit `:lib-vm:test` vert à HEAD. (b) La batterie `suite-contract.sh` rejouée
sur un devnet loopback frais : **43 PASS, 0 FAIL** (templates déployés et appelés, modules
adverses refusés à l'exécution, gaz, déterminisme) — même verdict que la campagne 9. (c) Les 9
templates déployés et exercés sur le staging RÉEL (5 nœuds, sous charge sims+monitor) — les 7
jamais sortis du loopback y passent pour la première fois (amm, launchpad avec valeur attachée
via Forge — le wallet CLI code value=0 en dur, agent_wallet avec flux session complet, router,
emitter, logtree, pair avec approve/transfer_from/LP). Onze contrôles de déterminisme
`/call_readonly` : sorties identiques 5/5 nœuds au même tip à chaque fois, valeurs exactes
vérifiées (réserves AMM après swap avec fee 0,3 % : 362 644 calculé = trouvé). Les chemins
d'échec sont prouvés à l'EXÉCUTION via le champ `error` de `/call_readonly` (trap sur
sur-budget session, session révoquée, trap après sous-appel) et le rollback on-chain est
prouvé par l'état : `router.call_then_trap` vers un `token.transfer` laisse le solde du
destinataire inchangé sur les 5 nœuds. Note de méthode, worth keeping : le `status:` du wallet
CLI reflète l'ADMISSION (mempool), pas le résultat d'exécution — un appel qui trappe revient
SUCCESS à l'admission (comportement documenté de la batterie pour les DEPLOY invalides, ici
généralisé aux CALL qui trapent) ; toute assertion sur un trap doit lire l'état ou
`/call_readonly`, jamais le statut d'admission. Revert et valeur attachée : une tx trappée
débite le gaz consommé (17 754 u vers le mineur) et JAMAIS la valeur attachée
(`Executor.applyContract` : la valeur ne bouge que sur succès ; solde natif du launchpad resté
à 100 = seul le buy réussi) — les « coins ne bougent que si les tokens bougent » du launchpad
tiennent structurellement. Pas de route receipts exposée pour relire le gasUsed exact d'une tx
minée : seule lacune d'observabilité rencontrée.

**Volet « reste à tester » (exécuté le 2026-09-28 après-midi, soak toujours en cours).** Chaque
item de la liste « testable maintenant » a été couvert, produit sans défaut trouvé :

- **Logs en réseau réel** : `/logs?height=` et `/logs/stream` (SSE) lus sur staging — topics
  décodés (`before`→`after` de logtree en ordre causal au même bloc, `count` d'emitter avec le
  compteur en data, `swap` de l'AMM), et le cas qui compte : le frame **trappé** (logtree sel 1)
  n'a laissé **aucun** log dans son bloc — son `before` a été supprimé avec lui. Le SSE connecte,
  annonce son retry et pousse un événement par bloc en direct.
- **Join d'un 4ᵉ nœud** (port 13005, `RHIZOME_SYNC=snap RHIZOME_PRUNE=248`) : synchro complète
  de ~3 500 blocs via les tunnels (~15 blocs/s, ~5 min), convergence EXACTE au tip du réseau.
  Le snap a basculé silencieusement en full-sync — les seeds ne servaient aucun instantané :
  `RHIZOME_SNAPSHOT_EVERY` = 17 280 blocs (~1 jour à 5 s) et les seeds redémarrent de genèse ce
  matin-là ; comportement conforme, mais à retenir pour le testnet public (un join le premier
  jour est un full-sync). Élagage actif : `prunedBelow` suit le tip (plancher 248 respecté),
  `/block?blockId=1` → `{"error":"pruned"}`, `/sync?start=…` sous filigrane → **410 GONE en
  portant le filigrane**, 200 au-dessus. Nœud arrêté et purgé après le test.
- **Boxes** : flux complet sur staging (create avec registres i64/bytes/bool, show, list par
  propriétaire, update topup+registres, spend) — cohérence `/box` **5/5 nœuds identiques** au
  même tip, comptabilité exacte (spend libère 7 PDN pile, frais décomptés), livre de loyer
  vivant (`rentPaidHeight`, `expiresAtHeight`). Au passage : `box-update` **remplace** la liste
  entière des registres (perte des non-mentionnés, 103→93 octets) — sémantique à connaître.
- **Tokens natifs** : mint (TSTN, 1 M, déc. 4), transfer, burn — tous minés, soldes exacts
  (999 700/300/999 600) et `totalSupply` décrémenté par le burn (999 900), identiques sur les
  nœuds interrogés. Piège de mesure rencontré (encore) : lire un solde avant le minage de la tx
  suivante fausse le verdict — l'attente de nonce doit être re-ancrée avant CHAQUE envoi.
- **Faucet contre le réseau réel** : drip nominal miné à travers le maillage multi-hôtes (1 PDN
  confirmé on-chain), cooldown 429, adresse malformée 400, challenge à usage unique 400 au
  rejeu, budget quotidien décompté. Un 503 sur envoi très rapproché = course de nonce entre
  drips (le faucet ne sérialise pas ses `wallet send`) — à usage humain, sans effet ; noté.
- **Alarmes monitor en vrai** (mini-réseau local isolé, sans seed) : StaleTip déclenché au seuil
  EXACT (50 s = 10×5 s) après arrêt du mineur, résolu à la reprise ; alertes stall par nœud
  dans les deux sens ; **webhook livré pour chaque transition** (triggered ET resolved, 5
  événements). Mini-réseau démonté après.
- **Burst DoS borné** (`suite-dos.sh` contre le banc A, nœud réel du soak) : **6 PASS, 0 FAIL** —
  268 req/s soutenus 30 s (7 944 requêtes, 7 255 délestées en 429 : la borne
  `AdmissionControl.SUBMIT_POW_MAX_PER_SEC`=25/s mord), nœud jamais `degraded`, **hauteur
  4464→4469 pendant l'inondation** (production honnête continue, à 5,9 s/bloc vs 3,9 s de
  témoin — le coût est mesurable et borné), sain et réactif après.
- **Suites tx/wallet contre staging : bloquées, chantier identifié.** `suite-tx.sh` forge avec
  `chain=3` (devnet) en dur et des frais à 0 (rejetés par `MIN_FEE`=10 de staging) ; les clés du
  dépôt sont TOFU-épinglées au devnet. `common.sh` gagne `RHIZOME_TESTNET_KEYS_DIR` (surcharge
  du trousseau, même pattern que `RHIZOME_SIM_MINER_KEYS_DIR`) — le port complet des suites vers
  un profil arbitraire (chainId dérivé du profil, frais profilés, re-vérification des attentes
  devnet) reste à faire et est documenté ici comme tel.

**Port des suites vers un profil arbitraire + première exécution tx/wallet/net/contract sur le
réseau réel (2026-09-28 soir).** Le port identifié plus haut a été fait et prouvé dans les deux
sens. Ce qui a changé : `suite_fee_pdn()` dans suite-common (frais minimaux du profil au format
PDN ; devnet = 0.0000 → comportement inchangé), `fund_from_miners` paie ses envois, les défauts
de `sign_send` (suite-tx) et les littéraux de site dérivent de `profile_get CHAIN_ID/MIN_FEE`
(les valeurs DÉLIBÉRÉES restent : chaîne étrangère 999, frais 5000 du TX-02, débordements),
suite-net/contract forgent avec le chainId du profil, suite-wallet paie ses `send`/`box-*`/
`token-*`. **Non-régression devnet : 48/35/38/43 PASS, 0 FAIL — baselines campagne 8 exactes**
(le prérequis du nœud étranger sur :3090 pour WALLET-04 doit être démarré à part, sinon 33/1
faute d'environnement). **Sur staging réel : 162/164** — tx 48/48, wallet 35/35 (avec le nœud
étranger sur BASE+90=13090), net 37/38, contract 42/43. Les deux écarts, classés sans défaut
produit : `API-12-honest-still-served` — la table de strikes PAR CLIENT (second palier documenté
par la batterie elle-même) déleste la source juste après le flot de blocs poubelle dans le
timing réel, là où le loopback l'évitait (reproduction isolée : l'envoi honnête passe
parfaitement une fois la fenêtre passée) ; `VM-T04-counter-state` — l'état relu avant
propagation de l'appel (~5 s/bloc réel vs instantané loopback). Leçons de harnais versées : ne
jamais partager un `RHIZOME_TESTNET_DIR` entre profils (le nœud strict auxiliaire de suite-net
plante sur les données de l'autre chaîne et sa version devnet survit en fuyard —
arrêt par scan d'environ, pas par `pkill -f` qui s'auto-tue) ; une dérive de nonce à travers
tunnel fait sonder `exists` à une MAUVAISE adresse (un « module installé » pouvait être un
contrat de 1039 octets là où le module incriminé en fait 40 — toujours recouper par codeHash) ;
les attentes de propagation restent l'unique calibration à généraliser pour réseau non-loopback.

**État du soak laissé tourner — handover.** Seeds : `systemd` `rhizome-node.service` sur
seed-1/2/3 (ssh `rhizome@10.10.10.1x` via le saut ; env `/etc/rhizome/node.env`, dont la nouvelle
`RHIZOME_ALLOWED_HOSTS` ; données `/var/lib/rhizome-node`, baseline 374 MB à h=280 le 2026-09-28
10:03 UTC). Devbox, tous détachés (`setsid`, survivent à la session) : les 3 tunnels `-L`
(`RHIZOME_TUNNELS_DIR=<scratchpad>/campagne11/tunnels`, base 13000), le tunnel `-R`
(pid `state/reverse-tunnel.pid`), les 2 bancs (ports 13003/13004, pids `state/bench-*.pid`),
`sim-tx.sh` 8 workers et `sim-contract.sh` (intervalle 5 s), `monitor.sh`
(`RHIZOME_TESTNET_DIR=<scratchpad>/campagne11/state` → `monitor.csv`, alertes dans
`logs/monitor.log`, seuil StaleTip porté à 60×5 s car la cadence réelle dépasse la cible).
À mesurer par la suite : `du -s /var/lib/rhizome-node` par VM (croissance/jour vs baseline),
`syncPeersBanned` et les lignes de ban des journaux (décroissance des scores), alertes du
monitor. Pour arrêter proprement : `sim-tx.sh stop`, `sim-contract.sh stop`, kill du monitor et
des pids ci-dessus, `tunnels.sh down`. À ne JAMAIS faire : pointer `partition.sh` vers un
inventaire contenant les IP de seeds, arrêter un seed hors incident, toucher à `ip rhizome_nat`.
Dotation des sims (50 PDN/worker, 0,002 PDN brûlé par tx envoyée) : tenante plusieurs semaines à
la cadence observée ; re-doter depuis `hot` si un soak très long épuise un worker
(`BALANCE_TOO_LOW` dans `sim/tx.csv`).

## Journal de résultats — campagne 9 (staging, exécutée 2026-09-22)

**Contexte.** Première campagne sur le profil `staging` (chainId 4, `rhizome-staging`, Pufferfish2,
genesis pinné, cible réelle de 5 s) plutôt que `devnet`/`testnet` — la répétition générale visée par
le plan de mise en testnet public (chantiers 0 à 8). **Infrastructure : un seul hôte**, pas les
≥3 VM/machines réelles que ce plan exige pour un lancement effectif : aucun accès multi-VM n'était
disponible pendant cette campagne (la seule VM déjà utilisée, `seed-1`, ne répondait plus —
`Permission denied (publickey)`). Ce qui suit est donc une répétition **single-host**, présentée
comme telle, pas la campagne multi-machines réelle ; le chantier 2 (harnais SSH, partitions
nftables) reste entièrement ouvert.

**Conditions.** HEAD `f55e879` + les scripts de cette campagne
(`scripts/local-testnet/staging-rehearsal.sh`, nouveau : lance des processus natifs séparés plutôt
que `start.sh`, dont le forçage de `RHIZOME_BLOCK_INTERVAL_MS` casserait la cadence Pufferfish2 réelle
que ce profil doit justement exercer). Binaire natif. 6 nœuds sur les ports 4500-4505, 3 mineurs
(une clé par mineur, un seul thread de minage par nœud comme en production), 3 relais.

**Calibrage (chantier 1), re-mesuré sur cette machine.** `Pufferfish2Benchmark` :
55,0 H/s/thread (18,176 ms/hash) ; 468,4 H/s à 16 threads (speedup 8,5×, 53 % d'efficacité — scaling
sous-linéaire attendu, PF2 est memory-hard). Cohérent avec la valeur épinglée dans le javadoc de
`staging()`/`StagingCalibrationTest` (54,6 H/s) : `minDifficulty = 8` reste valide sur ce matériel.

### La trouvaille de cette campagne : le filtre SSRF ne connaît pas les seeds

La première tentative de lancement (mêmes scripts, sans le correctif ci-dessous) a tourné ~45
minutes sans que rien ne l'indique : les 6 nœuds minaient chacun sa **propre chaîne isolée**
(`/peers` vide partout, hauteurs et tips tous différents, hauteur grimpant normalement sur chaque
nœud pris isolément — c'est précisément ce qui rend la panne silencieuse). Logs :
`SecurityException: peer host 127.0.0.1 resolves to a non-routable address`.

Cause, trouvée par lecture de `PeerHosts.pin` (`lib-net/.../PeerHosts.java`) et de
`RhizomeNode.java:121-123` : `blockPrivate = !config.allowPrivatePeers()` s'applique **à toute
tentative de connexion**, sans distinction d'origine — qu'un pair vienne de `RHIZOME_PEERS` (seed
configuré) ou du PEX. **Ceci contredit une hypothèse écrite dans le plan lui-même** (chantier 2 :
« les seeds de `RHIZOME_PEERS` échappent au filtre »). Ce qui est vrai : un seed injoignable reste
inscrit comme « ancre de confiance » dans `PeerDiscovery` (`seed ... unreachable; keeping trusted
anchor`) — mais l'inscription n'ouvre aucune connexion réelle tant que `blockPrivate` la refuse.
Conséquence générale, pas spécifique à ce profil : **toute mise en réseau multi-nœuds sur un seul
hôte exige `RHIZOME_ALLOW_PRIVATE_PEERS=true`**, quel que soit le profil — ce n'est pas une commodité
de `start.sh`, c'est une nécessité fonctionnelle dès que tous les nœuds sont en loopback. Cela veut
aussi dire que **les huit campagnes précédentes** (toutes en loopback, toutes avec ce drapeau posé)
n'ont, elles non plus, jamais exercé le chemin filtré par défaut — seul un vrai déploiement
multi-hôtes routables (chantier 2) le peut.

Corrigé dans `staging-rehearsal.sh` (ajout de `RHIZOME_ALLOW_PRIVATE_PEERS=true` dans les variables
d'environnement par nœud, en-tête de script mis à jour avec la correction) ; réseau arrêté, données
et état du faucet purgés (clés conservées), relancé proprement. Convergence immédiate et confirmée :
les 6 nœuds rapportent la même hauteur, le même `tipHash` et `peers=5` à chaque cycle depuis.
Vérifié en direct au moment d'écrire cette entrée : hauteur 70, tip `F0F0B4ED74E9`, difficulté 8
(plancher), `peers=5`, `avgBlockIntervalMs≈5235` — proche de la cible de 5 s — identique sur les 6
nœuds.

**Genesis.** `verify-genesis.sh` contre un mineur (`:4500`) et un relais (`:4504`) : chainId 4,
network `rhizome-staging`, `2 OK, 0 FAIL` sur les deux — même résolution de genesis quel que soit le
rôle du nœud. Hash de genesis lu via `GET /block?blockId=1` :
`8CB7AD090F912D3C051B1C3FBB3FF187918843DF06301339D84C91305DF46590`.

### Faucet (chantier 5), exercé pour la première fois

Clé de répétition générale dédiée (jamais celle du genesis, inaccessible), financée depuis la
coinbase d'un mineur (3 PDN, frais 0,5 PDN ≥ `minFee`). `faucet.py` lancé contre le réseau réel
(`--pow-difficulty-bits 8` pour un temps de résolution raisonnable en test) :

| cas | résultat |
|---|---|
| drip nominal (challenge résolu, adresse fraîche) | `200 SUCCESS`, tx minée, solde du destinataire confirmé à 1 PDN |
| second drip, même adresse, immédiat | `429 address in cooldown` |
| adresse malformée | `400 invalid address`, aucune transaction émise |
| challenge invalide/rejoué | `400 invalid, expired or already-used challenge` |
| `/status` | budget quotidien suivi correctement (`dailyBudgetSpentBaseUnits` incrémenté d'un drip, `dailyBudgetRemainingBaseUnits` cohérent) |

### Télémétrie et alerte (chantier 6)

`/metrics` (déjà présent dans l'arbre, jamais vérifié en direct avant cette campagne) répond avec
les 15 jauges Prometheus attendues (`rhizome_height`, `rhizome_difficulty`, `rhizome_total_work`,
`rhizome_peers`, `rhizome_mempool_size`, `rhizome_avg_block_interval_ms`,
`rhizome_last_block_timestamp_seconds`, `rhizome_reorg_in_progress`, `rhizome_degraded`,
`rhizome_sync_rounds_without_progress`, `rhizome_sync_peers_banned`, `rhizome_sync_eclipsed`,
`rhizome_pruned_below`, `rhizome_supply_base_units`, `rhizome_max_reorg_depth`), coût identique à
`/stats` comme conçu.

`monitor.sh` étendu avec les trois alertes que le plan nommait manquantes — **StaleTip** (aucun bloc
depuis 10× le temps de bloc visé), **DiskLow** (espace libre < 10 % sur le répertoire de données d'un
nœud), **SeedDisagreement** (chainId, hauteur d'activation de courbe, hauteur de décroissance ou hash
de genesis non unanimes) — et converti en alertes **par transition** (déclenchement/résolution
seulement, pas à chaque cycle de 2 s) avec livraison webhook optionnelle
(`RHIZOME_MONITOR_WEBHOOK_URL`, best-effort). Tourné en continu depuis le redémarrage post-correctif :
aucune fausse alerte, ce qui est le résultat attendu sur un réseau convergé et sain.

### CI (chantier 7)

`.github/workflows/ci.yml` activé (copié depuis `.github/ci-workflow.yml.example`, resté inactif
faute de permission `workflows` sur le compte qui l'avait généré) : build + suite complète sur
push/PR vers `master`, plus revue de dépendances sur PR.

### E2E-89 (chantier 4.5) — déjà fermé

Vérifié avant toute autre action cette campagne : `StagingGenesisTest` contient déjà
`nativeImageReachabilityMetadataStillCoversGenesisResources`, qui asserte que
`reachability-metadata.json` déclare toujours `"glob": "genesis/*.json"` — exactement ce que le plan
demandait, déjà présent dans `f55e879`. Aucune modification nécessaire.

### Chantier 4 (JUnit/E2E) — l'essentiel déjà fermé, vérifié cette campagne

Une entrée précédente de ce journal marquait le chantier 4 « non investigué cette campagne » ; faux,
corrigé ici après lecture directe du code plutôt que du seul journal :

- **4.1 (Pufferfish2 au niveau réseau)** — fermé : `TestNetwork.PUFFERFISH2`
  (`app-node/.../e2e/TestNetwork.java:95`) est le jumeau réel-PoW de `FAST`, déjà utilisé par des
  tests qui minent et vérifient en PF2.
- **4.2 (sceau d'horloge)** — fermé, et par la voie recommandée par le plan (pas de sceau ajouté à
  `RhizomeNode`) : `ClockDriftAttackTest#aDriftedClockAcceptsWhatAnAlignedClockRejectsUntilRealTimeCatchesUp`
  (`lib-core/.../adversarial/ClockDriftAttackTest.java`) boote deux `ChainEngine` sur
  `NetworkParameters.staging()`, l'un à `t`, l'autre à `t+20s`, et prouve le rejet côté horloge alignée
  puis la reconvergence quand le temps réel rattrape l'horloge dérivée. Catalogué `TIME-06`
  (`docs/adversarial/spec.md:199`, famille `TIME`, `BOUNDED`).
- **4.4 (état de contrat WASM à travers un reorg réseau)** — fermé : `E2EContractTest` porte
  désormais `aReorgReversesDeployedContractCodeAndAccumulatedStorageExactlyOnARealNode` (E2E-94,
  `DEFENDED` dans `docs/adversarial/spec.md`), le déploiement/appel de la campagne 7 rejoué sur une
  branche perdante.
- **4.3 (réseau hétérogène en versions)** reste, comme prévu par le plan lui-même, hors JUnit — un
  scénario de campagne multi-binaires, pas encore joué (bloqué sur le même manque de matériel
  multi-machines que le chantier 2).

### La deuxième trouvaille de cette campagne : `suite-pow.sh` n'avait jamais tourné jusqu'au bout

Trois défauts distincts, empilés dans le même fichier, découverts en rejouant `suite-pow.sh` sous le
vrai PF2 de `staging` pour la première fois (premier point du chantier 3) :

1. **Lanceur de source manquant.** Les parties 2 à 4 (paire source/victime isolée, ports
   4406/4407) dépendent de `Anvil --source http://127.0.0.1:4406`, mais rien dans le fichier ne
   lançait jamais de nœud à cette adresse — un défaut présent depuis la réécriture du fichier en
   `f55e879`, jamais joué jusqu'au bout avant cette campagne. `Anvil.waitForSourceBlock` reçoit une
   connexion refusée, le process JVM crashe aussitôt, et le wrapper bash `anvil() { ... | tail -1; }`
   capture une ligne de trace au lieu d'un `code|body` — d'où une cascade de ~15 échecs
   « obtenu ... <vide> » qui se lit comme autant de bugs de consensus indépendants alors qu'il n'y en
   a qu'un. Corrigé par l'ajout de `source_pid()`/`reset_source()`, qui lance et garde vivant un
   mineur solo dédié (`solo-src.key`, nouvellement généré dans `keys/`) pour toute la durée de la
   batterie. `suite-bootstrap.sh` partageait très exactement le même défaut sur le même port (voir
   plus bas) — corrigé de la même façon, plus `RHIZOME_SNAPSHOT_EVERY=200` que `suite-pow.sh` n'a
   aucune raison de poser.
2. **Frontière de timewarp câblée en dur.** La partie 3 (TIME-03, défense médiane-contre-brut)
   plaçait l'horodatage gonflé à la hauteur littérale 20 — correcte seulement sous
   `DIFFICULTY_LOOKBACK = 20` (devnet). Sous `staging` (`DIFFICULTY_LOOKBACK = 60`), aucune fenêtre
   ne se ferme à h=20 : le scénario entier devenait silencieusement vide (`divergentBoundaries: []`),
   un FAIL bruyant mais pour une raison sans rapport avec la défense testée. Corrigé en
   paramétrant la frontière sur `$LOOKBACK` au lieu du littéral.
3. **Coût réel de `Anvil` sous PF2, non budgété.** La partie 4 (balayage complet montée/plancher)
   imposait un calendrier assez serré pour forcer le plafond `MAX_STEP_BITS` (+4 bits, 8→12) dès la
   première fenêtre. Sous devnet (PoW quasi gratuit, lookback 20) c'était sans conséquence. Sous
   `staging` (PF2 réel, lookback 60), cela impose de miner pour de vrai jusqu'à 59 blocs à la
   difficulté relevée (2¹² hachages, ~54,5 s/bloc à 13,3 ms/hachage) avant que la fenêtre suivante ne
   corrige — plus d'une heure, très au-delà des 1800 s alloués à la batterie, et exactement le risque
   que le plan de mise en testnet nommait déjà (« Coût horloge d'Anvil sous PF2 »). Corrigé en
   desserrant `CLIMB_STEP` (310 → 2000 ms) pour ne franchir qu'un seul pas de +1 bit (8→9) : la
   propriété testée (la difficulté a bougé, et revient exactement au plancher) reste prouvée, à un
   coût réel de quelques minutes au lieu d'une heure. **Affaiblissement assumé** : cette partie ne
   démontre plus le plafond `MAX_STEP_BITS` en une seule fenêtre sous charge réelle — documenté ici
   plutôt que découvert à la lecture, comme le plan le demande explicitement pour ce cas.

Résultat après les trois correctifs : POW-CTRL-\* et TIME-\* rendent de vrais verdicts (codes HTTP
réels) au lieu de chaînes vides. Détail par cas et tally final, ci-dessous — premier passage complet
de `suite-pow.sh` sous `staging`, section 4 comprise (`.testnet-staging-pow`, log complet conservé en
tant que preuve). Le trajet jusqu'à ce résultat propre a lui-même coûté six lancements : le 4ᵉ a
crashé en silence au milieu de la boucle de descente de la section 4 (deux process orphelins,
`PPID=1`, aucune trace d'erreur — `suite-common.sh` pose `set +e`, ce qui exclut un échec de
commande normal ; cause la plus probable une frontière de session externe) ; le 5ᵉ lancement a
d'abord oublié `RHIZOME_TESTNET_BASE_PORT=4500` (repli silencieux sur le port 3000 par défaut de
`common.sh`, `diffscan.py` refusant alors la connexion) puis, une fausse lecture de `ps -p "$!"` sur
un process lancé par `setsid` (le PID suivi est celui du wrapper, qui sort dès qu'il a forké — pas
celui du script réellement détaché) a fait croire que ce lancement était mort alors qu'il tournait
toujours, d'où un 6ᵉ lancement accidentellement concurrent au même sur les mêmes ports 4406/4407.
Nettoyé (tous les process orphelins/dupliqués tués, `.testnet-staging-pow` effacé) avant le
relancement propre ci-dessous.

| Cas | Verdict | Détail |
|---|---|---|
| RETARGET-01 | PASS | difficulté de chaque bloc = repli indépendant des fenêtres (847 blocs) |
| RETARGET-02 | PASS | changements hors frontière + pas > MAX_STEP_BITS — 0 |
| RETARGET-03 | PASS | difficulté toujours dans [8, 255] |
| RETARGET-04 | PASS | continuité des liens parent/enfant sur toute la chaîne scannée |
| RETARGET-05 | FAIL (résiduel matériel déclaré) | la difficulté n'a pas quitté son plancher sous cadence trop rapide — hashrate de campagne insuffisant pour y forcer un franchissement, cf. « couverture non atteinte » |
| RETARGET-06 | PASS | échelle observée (final 8) |
| RETARGET-12 | FAIL (résiduel matériel déclaré) | aucun palier descendant après la coupure de hashrate (0 observés) — même cause que RETARGET-05 |
| RETARGET-07 | PASS | fenêtres récentes dans la bande morte (observé vs cible) : 5/5 |
| POW-CTRL-01 | PASS | victime repartie de la genèse |
| POW-CTRL-02 | PASS | rejeu honnête de 5 blocs source — 200 SUCCESS |
| POW-CTRL-03 | PASS | hauteur de la victime après rejeu — 6 |
| POW-01 | PASS | bloc revendiquant une difficulté qu'il n'a pas payée — 400 INVALID_NONCE |
| POW-02a | PASS | difficulté déclarée plus faible que celle imposée par l'historique — 400 INVALID_DIFFICULTY |
| POW-02b | PASS | difficulté déclarée plus forte que celle imposée par l'historique — 400 INVALID_DIFFICULTY |
| TIME-01a | PASS | pré-minage au-delà de la fenêtre future (+25 s) — 400 BLOCK_TIMESTAMP_IN_FUTURE |
| TIME-01b | PASS | pré-minage DANS la fenêtre future (+5 s), la borne est une borne pas un interdit — 200 SUCCESS |
| TIME-02 | PASS | horodatage à la médiane du passé — 400 BLOCK_TIMESTAMP_TOO_OLD |
| TIME-04 | PASS | horodatage antérieur au parent — 400 BLOCK_TIMESTAMP_TOO_CLOSE |
| POW-CTRL-04 | PASS | témoin après la série de rejets — 200 SUCCESS |
| POW-CTRL-05 | PASS | nœud de campagne intact après la série — degraded=null reorg=false |
| TIME-03a | PASS | frontière h=60 (=`$LOOKBACK`) gonflée de +600 s, dans la fenêtre future — 200 SUCCESS |
| TIME-03b | PASS | la chaîne suit la règle MÉDIANE sur toute sa hauteur |
| TIME-03c | PASS | difficulté retenue en h=61 : 10 (médiane) contre 8 (brut à la frontière) — la défense timewarp tient sous le vrai lookback staging (60), pas seulement sous le 20 de devnet |
| POW-03a | PASS | difficulté reconstruite depuis les horodatages après redémarrage (chaîne de 61 blocs) — 9 |
| POW-03b | PASS | tip identique après redémarrage — `DB4BD309C6908B205C51F0795F13F2AB19DB74BF244AC2AAC690B1FB00B2923B` |
| RETARGET-08 | PASS | balayage montée/descente conforme au repli indépendant |
| RETARGET-09 | PASS | aucun pas > 4 bits, aucun changement hors frontière — échelle observée 61:8→9, 121:9→8 |
| RETARGET-10 | PASS | descente ramenée au plancher et clampée (pic atteint : 9) |
| RETARGET-11 | PASS | montée sous calendrier serré (pic 9 > genèse 8) |

**pow: 27 PASS, 2 FAIL** — les deux FAIL sont RETARGET-05/RETARGET-12, résiduels matériels déjà
déclarés (hashrate de campagne insuffisant pour forcer un franchissement de plancher sous cadence
serrée), pas des régressions. C'est la première fois que cette batterie rend un verdict complet —
section 4 (POW-03\*, RETARGET-08 à 11) comprise — sous du vrai Pufferfish2.

### `suite-tls.sh` contre `staging` — un faux positif de tare matérielle, un vrai piège d'outillage

Première exécution de cette batterie contre `staging` (chantier 3). Convergence immédiate sur les
26 premiers cas (TLS-00 à AUTH-07) ; deux cas ont échoué de façon identique et reproductible sur
trois lancements successifs : `NET-08-configured-reached` et `NET-08-configured-gets-token`
(0 requête reçue par le pair TLS **configuré** — c.-à-d. celui de `RHIZOME_PEERS`, alors que le pair
**appris par gossip**, même AC, même infrastructure cible, en recevait 12).

Deux hypothèses posées et écartées avant la vraie cause :

1. **Ordonnancement du démarrage.** Hypothèse que les cibles `tls-peer-configured`/`tls-peer-gossip`
   démarraient après les nœuds, laissant les premiers rounds PEX pruner le seed via
   `PeerDiscovery.MAX_FAILURES`. Fausse : lecture de `PeerDiscovery.java` — un seed est explicitement
   **exempté** de l'éviction par échecs (`"seed {} unreachable; keeping the trusted anchor"`), c'est
   l'inverse de l'hypothèse. Le réordonnancement appliqué par précaution (cibles démarrées avant les
   nœuds) reste dans le fichier — défendable, mais n'a rien corrigé.
2. **Course de port auto-infligée.** Le 2ᵉ lancement s'est révélé confondu par un vrai problème de
   méthode : relancé immédiatement après la fin du 1ᵉʳ, avant que le port du 1ᵉʳ `tls-peer-configured`
   ne soit relâché (`SIGKILL` ne libère pas un socket instantanément) — `Address already in use`,
   trafic capté par un process périmé. Un 3ᵉ lancement, vérifié "propre" via `ss`/`ps` avant
   démarrage, a produit **exactement le même résultat**.

Cette dernière vérification était elle-même un faux négatif : **`ss` n'est pas installé dans ce
bac à sable** (`bash: ss: command not found` — silencieux dans un pipeline `| grep`, donc lu à tort
comme "rien trouvé"). La batterie le savait déjà et le documente en interne
(`XFF-03-ss-visibility`, cas `METRIC` : *« pas de visibilité réseau fiable dans ce bac à sable,
indicatif seulement »*) — relu trop tard. Un test de connexion socket Python direct
(`socket.connect(('127.0.0.1', 4600))`) a confirmé le port occupé, et les logs du process
`tls-peer-configured.py` lui-même montraient, à chaque tentative, un `OSError: [Errno 98] Address
already in use` **dès le démarrage** — jamais visible en tête de log parce que rien ne le faisait
échouer bruyamment ailleurs.

**Cause réelle** : `TLSPEER_CONFIGURED_PORT` (`BASE_PORT + 100`, soit 4600 sous `staging`) collidait
avec le service **faucet** (chantier 5, `faucet/faucet.py`), en service de longue durée contre le
même réseau, lancé à la main sur `--port 4600` — un choix qui tombait par coïncidence dans la plage
que `suite-tls.sh` réserve pour ses cibles pair-TLS isolées. Le port était donc **ouvert, mais muet**
pour ce protocole : `wait_port` réussissait (TCP accepte), `tls_peer.py` lui-même ne démarrait
jamais, et chaque requête `/peers`/`/add_peer`/`/total_work` du nœud atterrissait sur les gestionnaires
HTTP du faucet plutôt que sur la cible attendue — un échec pour `PeerDiscovery`
(`"seed ... unreachable; keeping the trusted anchor"`), sans trace côté nœud puisque la connexion
TCP elle-même réussissait. `PeerDiscovery.java` est entièrement innocent ; aucune ligne de code
produit n'a été touchée.

**Correctif** : `TLSPEER_CONFIGURED_PORT`/`TLSPEER_GOSSIP_PORT` déplacés à `BASE_PORT + 110/111`
(4610/4611 sous `staging`), vérifié libre par sondage direct (aucune autre batterie ni service
connu n'occupe cette plage) avant de relancer. Le faucet, lui, n'a pas été touché — il sert
correctement le réseau vivant (`/status` répond, budget quotidien intact), c'est le plan de ports de
`suite-tls.sh` qui devait céder, pas l'inverse.

Quatrième lancement, propre, avec le correctif :

| Cas | Verdict | Détail |
|---|---|---|
| TLS-00-certs/truststore/nodes/proxies | PASS | AC jetables + magasin de confiance + 5 nœuds auxiliaires + 2 relais debout |
| TLS-01-status/network/chainid | PASS | via relais de confiance, identique au direct — `rhizome-staging`, chainId 4 |
| TLS-02-untrusted-cert-rejected | PASS | sans `--cacert` ni `-k` : échec TLS pur, certificat auto-signé non approuvé |
| AUTH-01/02/03/03-status-ok | PASS | `/add_peer` : 401 sans jeton, 401 jeton faux, 200 + jeton correct |
| AUTH-04-peer-protocol-open | PASS | `/peers` reste ouvert sans jeton — protocole-pair, pas de surface opérateur |
| AUTH-05/06 | PASS | `RHIZOME_PROTECT_READS` étend le jeton à `/stats` puis le redonne avec le jeton correct |
| AUTH-07-spa-shell-open | PASS | coquille SPA exemptée (`Guard.SPA_SHELL`) |
| NET-08-gossip-peer-presented | PASS | pair appris par gossip présenté via `/add_peer` — 200 |
| **NET-08-configured-reached** | **PASS** | pair configuré (`RHIZOME_PEERS`, https) contacté — 11 requêtes (0 avant le correctif) |
| **NET-08-configured-gets-token** | **PASS** | 11/11 de ces requêtes portaient le jeton porteur |
| NET-08-gossip-reached | PASS | pair gossip contacté (11 requêtes) — la négative suivante porte sur une vraie tentative |
| NET-08-gossip-never-gets-token | PASS | 0/11 vers le pair gossip ne portait le jeton (`PeerTokenPolicy.tokenFor` : https configuré seulement) |
| XFF-01-spoofing-does-not-evade | PASS | `RHIZOME_TRUST_XFF=false` : la clé du limiteur reste l'adresse socket, l'en-tête usurpé n'a aucun effet — 500/1500 refusées |
| XFF-02-spoofing-evades | PASS | `RHIZOME_TRUST_XFF=true` : 0/1500 refusées sous charge identique — l'en-tête est cru, piège opérationnel délibéré et démontré |
| XFF-03-unreachable-from-other-interface | PASS | connexion directe sur une autre interface refusée — le relais est le seul point d'entrée effectif |
| TLS-FINAL-healthy-a/b/peertok | PASS | les trois nœuds auxiliaires intacts après les rafales XFF — `degraded=null reorg=false` |

**tls: 27 PASS, 0 FAIL.** Le résidu méthodologique à retenir n'est pas un défaut du produit : c'est
que **`ss` ne doit plus être utilisé pour vérifier un port libre sur cette machine** — préférer un
test de connexion socket direct (Python) ou lire `/proc/net/tcp`, comme fait ici.

### `suite-clock.sh` contre `staging` — première exécution, propre du premier coup

La moitié « horloge PROCESSUS » a pu s'exécuter : `faketime` obtenu par la voie sûre documentée en
tête de fichier (`apt-get download` + `dpkg-deb -x` dans un répertoire à nous, aucune mutation de
`/var/lib/dpkg`, horloge système de la machine partagée jamais touchée). Un seul lancement, aucun
défaut d'outillage rencontré cette fois — contraste net avec les sagas pow/bootstrap/tls.

| Cas | Verdict | Détail |
|---|---|---|
| CLOCK-01 | PASS | horloge SKEW décalée de -19 s (visé ±15 s) — `faketime` agit bien sur le PROCESSUS |
| CLOCK-02 | PASS | bloc RÉELLEMENT miné par un processus à l'horloge avancée de +15 s (< borne 15 s) — 200 SUCCESS |
| CLOCK-03 | PASS | RocksDB reconstruit le même bloc #2 après redémarrage, horloge toujours faussée — tip identique |
| CLOCK-04 | PASS | latence `/stats` sous horloge décalée (ActiveJ non bloqué par le décalage) — 18 ms ≤ 5000 |
| CLOCK-05 | PASS | horloge SKEW décalée de +25 s (visé +30 s) — `faketime` agit bien sur le PROCESSUS |
| CLOCK-06 | PASS | bloc RÉELLEMENT miné par un processus à l'horloge avancée de +30 s (> borne 15 s) — 400 `BLOCK_TIMESTAMP_IN_FUTURE` |
| CLOCK-07 | PASS | REF reste à la genèse, non dégradé, après le refus — `degraded=null reorg=false` |

**clock: 7 PASS, 0 FAIL.** Première preuve que la borne future de 15 s tient contre un processus
`rhizome-node` réel dont `clock_gettime()` lui-même ment — pas seulement contre un champ
d'horodatage forgé sur un processus à l'horloge intacte (ce que `suite-pow.sh`/E2E-92/93 prouvent
déjà). Le repli CLOCK-\* METRIC/SKIP documenté dans l'en-tête du fichier (pas de réseau/cache apt
→ moitié « attaquant » seulement) n'a pas eu à se déclencher cette fois.

### `suite-bootstrap.sh` contre `staging` — exécutée de bout en bout, deux échecs de budget-temps sous contention CPU

Premier passage complet de cette batterie contre `staging` depuis le correctif du défaut n°1
(fournisseur jamais démarré, même défaut de séquencement que `suite-pow.sh`, budget porté à 3000 s
dans `run-campaign.sh`). Les douze premiers cas (BOOT-01 à BOOT-11 : snap-sync au pivot 200,
filigrane, 410 sous le filigrane, convergence de tip du nœud snap, refus au démarrage d'une
rétention sous plancher) passent sans réserve.

| Cas | Verdict | Détail |
|---|---|---|
| BOOT-01 | PASS | instantané matérialisé au pivot 200 (1 morceau(x), racine 7062D09A7FCA92A8) |
| BOOT-02 | PASS | pivot enterré de 120 blocs sous le tip 320 (exigé: 120) |
| BOOT-03 | PASS | filigrane = pivot + 1 : l'état adopté au pivot 200 — 201 |
| BOOT-04 | PASS | racine d'état du bloc 201, appliquée sur l'état adopté |
| BOOT-05 | PASS | suffixe rattrapé jusqu'à 324 (pivot 200 + 120 blocs) |
| BOOT-06 | PASS | filigrane annoncé sous le pivot: prunedBelow=201 |
| BOOT-07 / BOOT-07b | PASS | corps sous le filigrane: 410 GONE, y compris côté JSON |
| BOOT-08 / BOOT-09 | PASS | même tip et même racine d'état que le fournisseur après 326 blocs |
| BOOT-10 / BOOT-11 | PASS | `RHIZOME_PRUNE=100` refusé au démarrage (sous le plancher de 248 blocs), aucun service n'écoute ensuite |
| **BOOT-12** | **FAIL** | nœud élagué frais à 2548, réseau à 2904 — échéance `wait_height` (300 s) atteinte avant rattrapage |
| BOOT-13 | PASS | filigrane d'élagage annoncé: prunedBelow=2304 (rétention 248) |
| BOOT-14 | PASS | bloc 1 jeté: 410 GONE |
| **BOOT-15** | **FAIL** | le 410 devait porter `prunedBelow=2304` (capturé ~1,3 s plus tôt), portait `2306` |
| BOOT-16 | PASS | au-dessus du filigrane, le corps est servi normalement (bloc 2314) |
| BOOT-17 | PASS | le nœud élagué **converge** sur le tip du réseau — boucle de reconvergence à échéance 120 s, atteint le même tip après 119,7 s (donc a failli, lui aussi, manquer son budget) |
| BOOT-18 / BOOT-19 | PASS | nœud d'archive (`RHIZOME_PRUNE` absent) : bloc 1 toujours servi, aucun filigrane |

**bootstrap: 18 PASS, 2 FAIL.** Les deux échecs partagent une seule cause, et ce n'est pas un défaut
de pruning. La section 3b lance le nœud élagué avec un répertoire de données **vide** contre un
réseau `staging` déjà à la hauteur ~2900 (des heures de minage réel accumulées avant cette section
du run), sur une machine qui hébergeait au même moment `suite-soak.sh` et un second réseau `staging`
isolé — voir [[shared-box-load-spikes]]. BOOT-12 capture la hauteur réseau une fois (`NET_H`) puis
attend au plus 300 s que le nœud élagué la rattrape à 2 blocs près ; sous cette contention, le
rattrapage n'était pas fini à l'échéance (2548 contre 2904, soit 356 blocs de retard). BOOT-15
hérite du même rattrapage encore en cours : `prunedBelow` (= tip − 248) a avancé de 2304 à 2306
dans l'intervalle, à peine plus d'une seconde, qui sépare sa capture (juste avant BOOT-13) de la
requête `/sync?start=1&end=1` de BOOT-15 — le nœud validait encore des blocs de rattrapage à un
rythme bien supérieur à la cadence de minage. La preuve que ce n'est qu'un budget de temps, pas un
défaut : BOOT-17, qui interroge en boucle pendant 120 s au lieu d'une seule fois (le commentaire du
fichier l'explique déjà — « la cible bouge, une comparaison instantanée mesurerait la latence de
gossip, pas la conformité ») — **passe**, mais en utilisant 119,7 des 120 s, confirmant que le
rattrapage était réellement lent ce jour-là, pas un aléa isolé.

**Corrigé dans le fichier** (même style que `suite-tls.sh`/`suite-pow.sh` : défaut de méthode de
test, pas de produit) : l'échéance de BOOT-12 passe de 300 à 600 s, et BOOT-15/16 relisent
`prunedBelow` juste avant de l'utiliser au lieu de réutiliser la valeur capturée pour BOOT-13.
**Pas encore rejoué de bout en bout** cette campagne — la correction se vérifiera au prochain
passage complet de la batterie, idéalement sur une machine moins contendue.

### `suite-soak.sh` contre `staging` — trois lancements, un seul défaut de produit inexistant : tout venait du harnais

Première exécution de cette batterie contre `staging` (chantier 3). Les deux premiers lancements ont
buté sur des défauts du harnais de charge, pas du produit — chacun découvert et corrigé en cours de
campagne :

1. **Mineurs financeurs introuvables.** `sim-tx.sh`/`sim-contract.sh` supposent la convention
   `start.sh` (mineurs en anneau, clés sous `$KEYS_DIR` partagé) ; le réseau `staging-rehearsal` a sa
   propre convention (K premiers nœuds, clés sous son propre `keys/`). Corrigé par
   `RHIZOME_SIM_MINERS`/`RHIZOME_SIM_MINER_KEYS_DIR`, deux variables d'environnement optionnelles —
   absentes, le comportement `start.sh` existant est inchangé à l'identique.
2. **Collision TOFU de chainId.** `WalletCli` épingle au premier usage le chainId du nœud contacté
   par une clé donnée, et refuse ensuite de signer si le nœud change de chainId (protection anti-rejeu
   inter-chaînes, volontaire). `sim-contract.key` puis les quatre `sim-N.key` des workers de
   `sim-tx.sh` vivaient sous le répertoire de clés **partagé entre toutes les campagnes locales** de
   ce dépôt, et avaient déjà été épinglés à `chainId 3` par une campagne `devnet` antérieure — refus
   systématique de signer contre `staging` (`chainId 4`). Corrigé en réutilisant
   `RHIZOME_SIM_MINER_KEYS_DIR` pour aussi scoper les clés du simulateur lui-même (aucune nouvelle
   variable) : sous `staging`, ces clés sont fraîches, épinglées proprement dès le premier envoi.
3. **Marge de frais omise dans les gardes de solde.** `staging` a `minFee = 10` (base units), contre
   0 sur `devnet` d'où viennent ces scripts ; les boucles d'attente « le mineur a-t-il assez pour
   doter ce worker » comparaient au montant brut de la dotation sans ajouter les frais du `send` qui
   suit, laissant une fenêtre étroite où le solde passait la garde puis se faisait rejeter au vrai
   envoi. Corrigé en ajoutant les frais comme marge des deux côtés de la garde.

Troisième lancement, propre, avec les trois correctifs :

| Cas | Verdict | Détail |
|---|---|---|
| SOAK-00-baseline | PASS | instantané pris sur 6 nœuds (6 répondants) avant la fenêtre de charge |
| SOAK-00-monitor-started | PASS | `monitor.sh` lancé |
| SOAK-00-tx-started / -contract-started | PASS | `sim-tx.sh` (4 workers) et `sim-contract.sh` démarrés |
| SOAK-01-window | PASS | fenêtre de charge de 180 s écoulée sans interruption |
| SOAK-02-stopped | PASS | `monitor.sh` et les simulateurs arrêtés proprement |
| SOAK-03-no-degraded-episode | PASS | 0 ligne `degraded` non nulle sur 395 lignes de `monitor.csv` |
| SOAK-GROWTH-node0..5 | METRIC | 4805–5390 o/bloc sur 23 blocs (mesure `du -sbL`, symlink RocksDB suivi correctement) |
| SOAK-GROWTH-BOUND-node0..5 | PASS | borne large de 5 000 000 o/bloc — métrique à suivre dans le temps, pas un seuil réglé |
| SOAK-TX-SUBMITTED / SOAK-TX-RATIO | METRIC / PASS | 28 envois, 28/28 confirmés (100 % ≥ 90) |
| SOAK-CONTRACT-SUBMITTED / SOAK-CONTRACT-RATIO | METRIC / PASS | 6 appels, 6/6 confirmés (100 % ≥ 90) |
| SOAK-MEMPOOL-DRAIN | PASS | mempool cumulé (tous nœuds) à 0 dans les 60 s suivant l'arrêt des simulateurs |
| SOAK-FINAL-healthy | PASS | `degraded=null reorg=false` après la fenêtre de charge |

**soak: 17 PASS, 0 FAIL.** Une seule fenêtre de 180 s — loin des jours visés par le chantier 3 pour
la croissance RocksDB et la décroissance des scores de ban — mais premier passage propre de bout
en bout : croissance par bloc mesurée (pas seulement bornée), confirmation de charge réelle à 100 %
une fois le harnais correctement scopé à ce réseau, aucun épisode dégradé, pas de fuite de mempool
apparente. Les trois défauts corrigés ci-dessus sont des défauts de harnais de test générique
partagé entre campagnes, jamais rencontrés sur `devnet` (frais nuls, une seule campagne à la fois) —
ils resteront latents pour quiconque relance ces scripts contre un réseau non-`devnet` sans les
correctifs.

### `suite-deep-reorg.sh` contre `staging` — `REORG_TOO_DEEP` atteint en vrai pour la première fois

Aucune campagne précédente n'avait fait dépasser `maxReorgDepth` (120 blocs) aux deux camps d'une
partition — la partition de `start.sh -p` guérit trop vite, et campagne 7/8 ne l'ont jamais tentée.
`suite-deep-reorg.sh` tourne sur son propre réseau éphémère (`.testnet-deep-reorg`, 4 nœuds, 2
mineurs, un par camp, cadence accélérée à 3 s — la batterie teste la **logique** de profondeur, pas
la fidélité de cadence à 5 s de mainnet, exactement comme documenté dans son en-tête et le plan).
Deux phases : un contrôle négatif (partition courte de 15 blocs, doit guérir), puis la partition
réelle jusqu'à 128 blocs par camp (au-delà de l'horizon 120), suivie d'une tentative de pont
croisé qui **doit** échouer pour être correcte, et enfin une étape meilleur-effort de récupération
d'un nœud vidé.

| Cas | Verdict | Détail |
|---|---|---|
| REORG-DEEP-00-campaign-up / -fork-recorded / -partitioned | PASS | 4 nœuds up, fork commun h=3, partition étanche (aucun pair hors-camp) |
| REORG-DEEP-NEG-01/02/03 (contrôle négatif) | PASS | partition de 15 blocs/camp guérit — reconvergence sur un tip unique à h=21, les deux camps `degraded=null reorg=false` après guérison |
| REORG-DEEP-02-fork-recorded | PASS | second point de fork h=21, juste avant la partition profonde |
| REORG-DEEP-03-camp-a/b-depth | METRIC | 128 blocs sur chaque camp (cible 128, horizon 120) |
| REORG-DEEP-03-camp-a/b-exceeds-horizon | PASS | 128 ≥ 121, les deux camps ont dépassé l'horizon avant la tentative de guérison |
| REORG-DEEP-04-heal-attempted | PASS | pont croisé posé entre les deux camps à profondeur 128/128 |
| REORG-DEEP-04-no-reconvergence | PASS | les deux camps restent scindés 120 s après le pont (tips distincts) — **résultat correct : la finalité tient au-delà de l'horizon** |
| REORG-DEEP-04-camp-a/b-not-rewound | PASS | hauteur camp A 168→176, camp B 149→157 pendant la tentative — aucun recul |
| REORG-DEEP-04-no-bans-camp-a/b | PASS | 0 ban de chaque côté — un refus `REORG_TOO_DEEP` répété n'accumule **aucun** score de ban, conforme à `RB-01`/`SyncDriver.PENALTY_INVALID` |
| REORG-DEEP-04-not-degraded-a/b | PASS | `degraded=null reorg=false` sur les deux camps après le refus |
| REORG-DEEP-04-camp-a/b-still-mining | PASS | les deux camps continuent de produire après le refus (176→177, 157→161) |
| REORG-DEEP-05-recovery-node-up | METRIC | nœud 1 vidé puis relancé avec `RHIZOME_SYNC=snap` — up en moins de 90 s |
| REORG-DEEP-05-recovery-synced | METRIC | **non** — h=208 (nœud reconstruit) vs h=191 (pair « gagnant » désigné) après 300 s |
| REORG-DEEP-05-recovery-pruned-below | METRIC | vide — repli attendu sur resynchronisation complète (aucun fournisseur d'instantané dans cette mini-campagne) |

**deep-reorg: 21 PASS, 0 FAIL, 5 METRIC.** La règle de profondeur elle-même est pleinement statuée
aux phases 2/3/4, PASS sans réserve : partition courte guérit, partition profonde ne guérit **pas**,
sans dégradation, sans faux ban, sans que le minage s'arrête d'un côté ou de l'autre — exactement le
comportement que `docs/operations/runbooks.md` RB-01 décrit, maintenant corroboré par une exécution
réelle plutôt que déduit du seul code. La phase 5 (récupération) est explicitement meilleur-effort
et enregistrée en `METRIC`, jamais en `PASS`/`FAIL`, pour une raison documentée dans le script
lui-même : le pont croisé de la phase 4 a déjà introduit chaque nœud du camp adverse dans le
registre PEX du nœud reconstruit, qui peut donc se resynchroniser avec le camp **non désigné**
gagnant s'il répond en premier — ce qui s'est produit ici (h=208 contre le camp A plutôt que h=191
du camp B désigné), une preuve incidente supplémentaire de la même règle de profondeur, pas
l'échec d'une resynchronisation ciblée. Aucune campagne locale n'a de fournisseur de snapshot
(`RHIZOME_SNAPSHOT_EVERY` absent partout), donc `RHIZOME_SYNC=snap` retombe silencieusement sur une
resynchronisation complète — chemin réel et pertinent puisqu'un nœud vide n'a pas de fenêtre de
reorg à respecter, mais pas la démonstration ciblée d'adoption d'un instantané que viserait un
suivi dédié avec un fournisseur configuré dès le départ.

### Couverture non atteinte (mise à jour)

- **Le multi-VM réel.** Un seul hôte, pas ≥3 machines routables entre elles — le chemin SSRF filtré
  par défaut (`RHIZOME_ALLOW_PRIVATE_PEERS=false`) n'a donc **toujours pas** tourné pour de vrai
  malgré la trouvaille ci-dessus : elle démontre qu'il bloque tout en loopback, pas qu'il se comporte
  correctement entre hôtes distincts. Chantier 2 (tunnels SSH, inventaire, partitions nftables) reste
  entièrement à faire sur du matériel réel.
- **`suite-pow.sh` rejouée sous PUFFERFISH2** — fait cette campagne, voir la trouvaille ci-dessus ;
  trois défauts trouvés et corrigés dans le fichier lui-même. `RETARGET-05`/`RETARGET-12` (la
  difficulté ne quitte jamais son plancher sous la rampe de hashrate de la partie 1, faute de cœurs
  disponibles pour dépasser durablement la cible sur cette machine partagée) restent un résidu
  matériel déclaré, pas un défaut du harnais.
- **`suite-bootstrap.sh`** exercée de bout en bout cette campagne, voir ci-dessus —
  `bootstrap: 18 PASS, 2 FAIL`. Les deux échecs (BOOT-12, BOOT-15) sont un budget-temps trop court
  pour un rattrapage réel sous contention CPU partagée, pas un défaut de pruning — la preuve étant
  que BOOT-17 observe la convergence complète deux minutes plus tard. Corrigés dans le fichier
  (échéance 300→600 s, relecture du filigrane juste avant usage). **Tentative de reconfirmation
  menée, non concluante, pour une raison elle-même instructive** : un premier rejeu, lancé sans
  fixer `RHIZOME_TESTNET_DIR`, a écrit dans le répertoire par défaut pendant que trois processus
  `rhizome-node` d'une tentative précédente (ports 4406/4411/4412, répertoire de données déjà
  supprimé sous leurs pieds) étaient encore vivants et squattaient ces mêmes ports fixes — le
  nouveau lancement n'a donc pas pu s'y lier, et les vieux processus zombies ont continué à
  répondre avec un état périmé, produisant cinq échecs inédits (BOOT-03, BOOT-07b, BOOT-17,
  BOOT-18, BOOT-19) qui n'ont rien à voir avec le pruning : de la contamination de harnais, pas
  une régression. Un second rejeu, cette fois avec `RHIZOME_TESTNET_DIR` correctement aligné sur
  le répertoire des zombies pour que le `kill_node` propre à la batterie les nettoie, a bien
  éliminé le zombie du port 4406 — mais deux autres (ports 4411/4412) survivent jusqu'à leur point
  de nettoyage plus tardif dans le script, et surtout la machine porte au même moment **quinze**
  processus `rhizome-node` vivants issus de batteries antérieures de cette même campagne
  (`suite-deep-reorg.sh`, `staging-rehearsal.sh`) jamais éteints. Sous cette charge, le nœud
  fournisseur du second rejeu minait à ~45 s/bloc au lieu des 5 s visés — atteindre le pivot
  demanderait plusieurs heures, ce qui n'est pas rejouable dans cette session. Cette session ne
  peut pas non plus nettoyer ces processus orphelins elle-même : `kill` sur un PID choisi à la
  main y est refusé (`[Interfere With Workloads]`), seul le nettoyage interne propre à chaque
  batterie (par correspondance de `RHIZOME_DATA`) fonctionne, et seulement pour ses **propres**
  processus. **Verdict honnête** : le correctif reste non rejoué proprement ; ce que cette
  tentative a établi à la place, c'est que la contention CPU **observée** dans le run original
  (l'hypothèse retenue pour BOOT-12/BOOT-15) est réelle et mesurable, pas une supposition — et
  qu'elle s'aggrave avec chaque batterie lancée sans extinction, un problème d'hygiène de session
  distinct du code testé.
- **`suite-tls.sh`** exercée cette campagne, voir ci-dessus — `tls: 27 PASS, 0 FAIL` après correction
  d'une collision de port avec le faucet (chantier 5), aucun défaut produit trouvé.
- **`suite-clock.sh`** exercée cette campagne, voir ci-dessus — `clock: 7 PASS, 0 FAIL`, aucun défaut
  trouvé, `faketime` obtenu sans toucher l'horloge système de la machine partagée.
- **`suite-soak.sh`** exercée cette campagne, voir ci-dessus — `soak: 17 PASS, 0 FAIL` au troisième
  lancement, après correction de trois défauts de harnais (mineurs financeurs, collision TOFU de
  chainId, marge de frais). Une seule fenêtre de 180 s : la croissance RocksDB et la décroissance des
  scores de ban sur plusieurs jours, visées par le chantier 3, restent extrapolées.
- **`suite-deep-reorg.sh`** exercée cette campagne une fois les deux suites précédentes terminées,
  voir ci-dessus — `deep-reorg: 21 PASS, 0 FAIL, 5 METRIC`. Reste hors d'atteinte : le multi-VM réel
  (une partition sur un seul hôte n'exerce pas de coupure réseau physique) et une démonstration
  ciblée d'adoption d'instantané en récupération (aucune campagne locale n'a de fournisseur de
  snapshot configuré).
- **`suite-dos.sh`** exercée cette campagne, résultats réels : `dos: 6 PASS, 0 FAIL`. Le flot d'un
  seul poste (489 req/s, 21728 requêtes/44,4 s) fait mordre `AdmissionControl.SUBMIT_POW_MAX_PER_SEC`
  (20663/21728 délestées en 429), la cadence honnête ralentit (7851 → 11099 ms/bloc) sans jamais
  dégrader ni geler le nœud (`degraded=null`, `reorg=false`, hauteur 258→262 pendant l'inondation) —
  la mesure que le chantier 1 demandait sur ce cap, jamais faite sous PF2 réel avant cette campagne.
- **`REORG_TOO_DEEP` en vrai.** Atteint cette campagne (voir `suite-deep-reorg.sh` ci-dessus) : les
  deux camps d'une partition dépassent 120 blocs, le refus tient, sans dégradation ni faux ban.
  Ce qui reste manquant est la même limite que partout ailleurs dans ce journal — une **vraie**
  coupure réseau entre machines distinctes plutôt qu'une partition logicielle sur un seul hôte.
- **Le point de fonctionnement de mainnet** (difficulté 16) reste hors d'atteinte sur ce matériel et
  à cette échelle — cette campagne prouve la boucle de retarget à `minDifficulty = 8`, pas la
  difficulté cible de mainnet.
- **La durée.** Quelques dizaines de minutes, pas les jours du chantier 3 (`suite-soak.sh`) :
  croissance RocksDB, décroissance des scores de ban, cadence des snapshots restent extrapolées.
- **BURN et DECAY** restent inertes (supply de départ loin de `S*`), comme sur `devnet` — décision
  assumée du chantier 0.5, pas une lacune de cette campagne.
- **Checkpoint publié** (chantier 0.4) : pas encore posé, la chaîne n'a pas encore tourné assez
  longtemps pour qu'un checkpoint ait un sens.
- **Documentation opérateur (chantier 8).** Contrairement à l'état constaté en tête de ce plan,
  déjà largement en place dans l'arbre au moment de rédiger cette section : la table complète des
  20 variables `RHIZOME_*` lues par `NodeConfig` est réconciliée dans `README.md`, avec sa section
  « Join the public staging testnet » ; `docs/operations/runbooks.md` RB-01 couvre `REORG_TOO_DEEP`
  en détail et cite maintenant une exécution réelle plutôt qu'une déduction du seul code ; les
  quatre résiduels assumés (`REORG-01`, `POOL-08`, `PERS-06`, `E2E-60`) sont publiés côté opérateur
  dans `docs/operations/spec.md` (Known limits), pas seulement dans le catalogue adverse interne.
  Ce qui reste : documenter *cette* campagne 9 elle-même dans le corps de ce plan une fois classée.
- **Déploiement reproductible (chantier 7).** `app-node/Dockerfile` et `scripts/local-testnet/deploy/`
  (units systemd, template nginx, `verify-genesis.sh`) existent déjà dans l'arbre — une affirmation
  du plan de mise en testnet (« rien n'existe : ni Dockerfile, ni unit systemd ») désormais fausse ;
  seule la CI restait à activer, fait cette campagne (voir plus haut).

## Journal de résultats — campagne 10 (staging, 3 VM OVH réelles + 2 nœuds locaux, 2026-09-23)

**Contexte.** Ce que la campagne 9 déclarait hors d'atteinte à répétition (« le multi-VM réel »,
« aucun accès multi-VM n'était disponible ») : cette campagne dispose de 3 VM Proxmox OVH
(`rhizome-seed-1/2/3`, LAN privé `10.10.10.11/12/13`, un hôte de saut) tournant le binaire natif du
profil `staging` (chainId 4, `rhizome-staging`, Pufferfish2), plus, nouveauté demandée pour cette
campagne, **2 nœuds supplémentaires sur ce devbox lui-même**, reliés au maillage privé par un unique
tunnel SSH bidirectionnel plutôt que par une ouverture réseau — but explicite : vérifier que la
convergence tient malgré la latence WAN réelle entre le devbox et l'infrastructure OVH, pas
seulement entre machines du même LAN.

**Incident 1 — le filtre SSRF, en vrai cette fois.** Les 3 VM minaient chacune sa propre chaîne
isolée malgré `RHIZOME_PEERS` renseigné (`peers:2` annoncé, mais aucun bloc ne traversait) :
`journalctl` a montré `SecurityException: peer host ... resolves to a non-routable address` — exactement
la trouvaille de la campagne 9, mais cette fois sur un LAN privé routable entre machines réelles, pas
en loopback sur un seul hôte, donc la première confirmation que le comportement décrit là-bas
généralise à un déploiement multi-hôtes. Corrigé en ajoutant `RHIZOME_ALLOW_PRIVATE_PEERS=true` aux
trois `node.env` puis en redémarrant (`fix-private-peers.sh`).

**Incident 2 — `REORG_TOO_DEEP`, atteint par accident.** La correction de l'incident 1 n'a *pas*
reconvergé les 3 VM : chacune avait déjà miné plus de `maxReorgDepth` (120) blocs de sa propre
branche isolée pendant que le filtre bloquait toute synchronisation, donc chaque nœud refusait
désormais la chaîne des deux autres (« past the reorg horizon; nothing to adopt ») — la garde
fonctionnant exactement comme prévu, mais rendant la guérison automatique impossible. Comme
`RHIZOME_DATA` sur staging ne contient aucune clé (`RHIZOME_MINER` est une adresse publique), la
procédure a été : arrêter les 3 services, purger `/var/lib/rhizome-node/*` sur chacun, relancer avec
le correctif de l'incident 1 déjà en place (`reset-chain-data.sh`). Reconvergence immédiate et propre
depuis la genèse — hauteur, `tipHash` et `totalWork` identiques sur les 3 VM à chaque relevé depuis.

**Adressage des nœuds locaux.** Un seul tunnel SSH vers `seed-1` (au travers de l'hôte de saut déjà
utilisé pour l'accès), avec un `-L` (les nœuds locaux tirent depuis `seed-1`) et deux `-R` (`seed-1`
peut rappeler chaque nœud local sur son propre loopback). Seule `seed-1` a eu besoin d'un changement
de configuration (`RHIZOME_PEERS` étendu aux deux adresses forwardées) — `seed-2`/`seed-3` n'ont pas
été touchées, exactement le schéma « un seul hôte à reconfigurer » visé par le chantier 2 pour ce
genre d'adressage. Aucune ouverture de pare-feu entrant, aucun `GatewayPorts` requis côté VM.

**Résultat — convergence à 5 nœuds, latence WAN comprise.** Relevé direct sur les 3 VM (SSH, pas via
le tunnel, pour écarter tout artefact du tunnel lui-même) et sur les 2 nœuds locaux au même instant :
hauteur 554 (VM) / 554-555 (locaux, écart d'un bloc — latence de propagation normale sur une chaîne
vivante, pas une divergence), même `tipHash` sur les 3 VM. `peers` : 4 sur `seed-1` (2 VM + 2 locaux),
2 sur `seed-2`/`seed-3`, 1-2 sur chaque nœud local.

**La preuve qui compte : contribution bidirectionnelle, pas seulement pull-sync.** `BlockProducer` ne
journalise pas de ligne dédiée au succès d'un minage local ; la vérification s'est donc faite en
relisant le champ `to` de la transaction coinbase de chaque bloc entre les hauteurs 400 et 538 et en
le comparant aux deux adresses des nœuds locaux
(`00E35103…9`, `0029AD38…6`). Sur 139 blocs : **3 minés par le nœud local A** (h=475, 497, 498),
**4 par le nœud local B** (h=496, 505, 506, 508), les 132 restants par les 3 VM. Ces 7 blocs sont
toujours dans la chaîne canonique au dernier relevé (h≥554) — donc non seulement les nœuds locaux
ont gagné des courses de PoW malgré la latence WAN vers les 3 VM, mais leurs blocs ont été adoptés et
sont restés adoptés par l'ensemble du maillage. C'est la démonstration directe demandée pour cette
campagne : la convergence tient malgré la latence, dans les deux sens.

**Ce qui reste ouvert.**
- **Reconciliation avec l'outillage du dépôt.** Cette campagne a été pilotée par des scripts ad hoc
  (`fix-private-peers.sh`, `reset-chain-data.sh`, `add-local-peers.sh`) plutôt que par
  `scripts/local-testnet/tunnels.sh`/`inventory.tsv` déjà prévus à cet effet par le chantier 2 — à
  reporter dans l'outillage versionné plutôt que de rester dans un scratchpad de session.
- **Partition physique réelle (nftables, chantier 2).** Non exercée cette campagne — seule la
  convergence a été testée, pas la coupure. `suite-deep-reorg.sh` reste donc, comme en campagne 9,
  validée sur partition logicielle single-host, pas sur une coupure réseau entre machines distinctes.
- **Checkpoint publié (chantier 0.4)** : toujours pas posé.
- **Durée et charge.** Quelques dizaines de minutes de convergence observée, pas les jours du
  chantier 3 ; aucun générateur de charge (`sim-tx.sh`/`sim-contract.sh`) exécuté contre ce
  déploiement réel — seul le minage organique a produit des blocs.
- **Le point de fonctionnement de mainnet** (difficulté 16) reste hors d'atteinte : `minDifficulty=8`
  observé (`avgBlockIntervalMs≈6486` au dernier relevé), calibré pour un hashrate agrégé bien en deçà
  de mainnet.
- **Trois régions distinctes (recommandation du plan, risques à porter)** : non fait — les 3 VM sont
  chez le même hébergeur, dans ce qui semble être la même zone ; aucune mesure de propagation
  inter-régions n'en découle.

## Journal de résultats — campagne 8 (exécutée 2026-09-04)

**Conditions.** Même HEAD que la campagne 7 (009-native-coin-burn), même machine (16 cœurs, 32 Go),
binaire natif. Quatre terrains simultanés, délibérément séparés :

| terrain | ports | profil | rôle |
|---|---|---|---|
| réseau de campagne | 4400-4405 | devnet, 6 nœuds / 4 mineurs | dynamique du retarget en hashrate réel, puis rejeu des six batteries de la campagne 7 |
| paire isolée | 4406/4407 | devnet, source minant seule + victime sans mineur | contrôle exact des horodatages (S26), fournisseur d'instantanés (S27) |
| nœuds d'appoint | 4411/4412/4413 | devnet | snap-syncé, élagué, et refusé au démarrage (S27) |
| trio profil-testnet | 4420-4422 | **testnet**, 2 mineurs | l'erreur de calibrage d'un opérateur, mesurée (S28) |

**Résultats.**

| batterie | cas | verdict |
|---|---|---|
| `suite-pow.sh` (S26, **nouvelle**) | 29 | **29 PASS, 0 FAIL** |
| `suite-bootstrap.sh` (S27, **nouvelle**) | 20 | **20 PASS, 0 FAIL** |
| rejeu campagne 7 à difficulté 22-24 | 190 | **190 PASS, 0 FAIL** (tx 48, wallet 35, contract 43, chain 11, net 38, persist 15) |
| **total** | **239** | **239 PASS, 0 FAIL** |

Le rejeu compte autant que les nouveautés : les 190 cas de la campagne 7 n'avaient jamais tourné
ailleurs qu'à la difficulté plancher 6. Ils sont rejoués ici sur une chaîne dont la difficulté vaut
22 à 24, c'est-à-dire de 65 000 à 260 000 fois plus de travail par bloc — le PoW cesse d'être instantané,
les blocs se disputent réellement, et rien ne bouge dans les verdicts.

### Le retarget, mesuré sur 920 blocs et 46 fenêtres

`diffscan.py` rejoue le repli des fenêtres à côté du nœud et compare bloc à bloc (verdict complet
conservé dans `.testnet/results/diffscan-devnet-campagne.json`). Sur le réseau de campagne, trois
régimes se succèdent sans qu'on touche à autre chose que le hashrate et la cadence
du producteur :

```
montée    (4 mineurs, 2 s)   6 → 7 → 9 → 11 → 14 → 16 → 18 → 20 → 22 → 23 → 24   (plafond du profil)
descente  (1 mineur, 20 s)   24 → 23 → 21 → 19 → 17 → 15 → 14
remontée  (4 mineurs, 2 s)   14 → 16 → 18 → 20 → 22 → 23
régulation (à l'équilibre)   23 ⇄ 24, la difficulté oscillant d'un bit autour de la cadence cible
```

Quinze niveaux de difficulté distincts visités, vingt-trois paliers, et **zéro divergence** : aucun bloc dont la
difficulté ne soit celle qu'impose le repli indépendant, aucun changement hors frontière, aucun pas
au-delà des 4 bits de `MAX_STEP_BITS`, aucune sortie de `[6, 24]`, aucune rupture de lien. La
convergence est nette : sur les cinq dernières fenêtres de la phase de montée, la durée observée
tient dans la bande morte (77 à 111 s pour une cible de 95 s), c'est-à-dire que la chaîne s'est
elle-même ramenée de 1,2 s/bloc à ~5 s/bloc, la cible du profil. Le plafond `maxDifficulty = 24`
a tenu : la difficulté s'y est arrêtée au lieu de continuer à monter sous une cadence encore trop
rapide.

**Ce que la descente prouve en propre.** C'est le sens qui compte pour un réseau public : un
testnet perd du hashrate bien plus souvent qu'il n'en gagne, et une difficulté qui ne redescend pas
fige la chaîne. Trois mineurs sur quatre coupés, la fenêtre suivante a mesuré 253 s pour une cible
de 95 s et la difficulté est retombée — puis a continué de retomber, palier par palier, jusqu'à ce
que la cadence rejoigne la cible. Le plancher, lui, n'est pas atteignable en réseau (il faudrait
supprimer presque tout le hashrate pendant des heures) : il est mesuré sur la victime isolée, où le
calendrier imposé fait descendre la difficulté 18 → 15 → 11 → 7 → **6, où elle s'arrête**.

**Redémarrage.** Au sommet de la montée du balayage (difficulté 18, valeur non triviale), la victime
est redémarrée : elle revient avec la **même** difficulté et le **même** tip. La difficulté est donc
bien reconstruite depuis les horodatages stockés, jamais lue dans un cache — le défaut Pandanite qui
avait forcé une exception codée en dur sur les blocs 536100-536200.

### Timewarp : la médiane de 3, prise sur le fait

Une frontière de fenêtre, et une seule, gonflée du maximum légal (+600 s, dans la fenêtre future
donc **acceptée** par le nœud). Les deux règles divergent alors franchement :

| borne de fenêtre | difficulté imposée en h=21 |
|---|---|
| médiane de 3 (règle du protocole) | **8** |
| horodatage brut (règle naïve) | 6 |

La chaîne a suivi la médiane. C'est la première mesure *positive* de cette défense : jusqu'ici on
constatait qu'aucune manipulation n'était présente, ce qui ne prouve rien ; ici la manipulation est
présente, elle est légale, et elle est sans effet. À noter le corollaire qui rend l'attaque coûteuse
et que la batterie mesure aussi : après avoir gonflé un horodatage, le bloc suivant doit être **au
moins aussi tardif** (`BLOCK_TIMESTAMP_TOO_CLOSE` sinon) — on ne revient pas en arrière.

### Les portes temporelles, des deux côtés de la borne

| cas | soumission | verdict du nœud |
|---|---|---|
| TIME-01a | horodatage à maintenant + 130 s | `BLOCK_TIMESTAMP_IN_FUTURE` |
| TIME-01b | horodatage à maintenant + 110 s | **`SUCCESS`** — une borne est une borne, pas un interdit |
| TIME-02 | horodatage à la médiane du passé | `BLOCK_TIMESTAMP_TOO_OLD` |
| TIME-04 | horodatage antérieur au parent | `BLOCK_TIMESTAMP_TOO_CLOSE` |
| POW-01 | travail non payé | `INVALID_NONCE` |
| POW-02a/b | difficulté déclarée trop faible / trop forte | `INVALID_DIFFICULTY` |

Chaque rejet est encadré d'un rejeu honnête accepté (`SUCCESS`) : sans ce témoin, un rejet ne
prouve rien — il pourrait venir d'un corps mal formé refusé bien avant la règle visée.

### Bootstrap : deux pièges d'opérateur, mesurés

Le snap-sync fonctionne de bout en bout : pivot 593 enterré de 172 blocs, filigrane annoncé à
`pivot + 1`, racine d'état du premier bloc au-dessus du pivot **identique** chez le fournisseur et
chez le nouveau venu, suffixe rattrapé, puis même tip et même racine que le fournisseur. Sous le
filigrane, `/sync` répond **410 GONE** en portant le filigrane et la vue JSON refuse de même
(`{"error":"pruned","prunedBelow":594}`) ; au-dessus, les corps sont servis normalement. L'élagage
fonctionne pareillement : `RHIZOME_PRUNE=248` synchronise puis jette, `RHIZOME_PRUNE=100` **refuse
au démarrage** en nommant le plancher (`below the safe floor of 248 blocks`) et rien n'écoute
ensuite. Un nœud d'archive, lui, sert toujours le bloc 1.

Deux pièges apparaissent, qu'aucun test unitaire ne pouvait montrer :

1. **L'instantané ne survit pas au redémarrage du fournisseur.** Après relance, `snapshotPivot`
   repart à 0 et `/state/snapshot/info` répond `no snapshot materialized` jusqu'à la prochaine
   matérialisation. Un réseau dont tous les fournisseurs redémarrent en même temps n'offre plus de
   snap-sync tant qu'aucun n'a re-matérialisé.
2. **`RHIZOME_SNAPSHOT_EVERY` doit dépasser `maxReorgDepth`.** La matérialisation capture le tip
   *courant*, et un pivot n'est adoptable que s'il est enterré sous `maxReorgDepth`. Avec un
   intervalle inférieur ou égal à cette profondeur, le pivot suit le tip de trop près et le nœud
   n'offre **jamais** d'instantané utilisable — silencieusement. Le défaut (~1 jour de blocs) est
   très au-dessus de la profondeur mainnet : le piège n'existe que pour l'opérateur qui « règle »
   cette variable à la baisse.

### S28 — le profil `testnet` sous cadence mal calibrée

Trois nœuds `RHIZOME_NETWORK=testnet`, producteurs à 2 s pour une cible de 90 s. Le javadoc de
`devnet()` annonçait +4 bits par fenêtre ; c'est exactement ce qui se produit, cinq fenêtres de
suite, et `diffscan.py` (constantes du profil : fenêtre 100, cible 90 s, plafond 255) est d'accord
avec la chaîne sur les 508 blocs (`.testnet/results/diffscan-testnet-profile.json`) :

| fenêtre | observé | cible | s/bloc | difficulté |
|---|---|---|---|---|
| 100 | 194 s | 8 820 s | 1,98 | 6 → 10 |
| 200 | 198 s | 8 910 s | 2,00 | 10 → 14 |
| 300 | 198 s | 8 910 s | 2,00 | 14 → 18 |
| 400 | 191 s | 8 910 s | 1,93 | 18 → 22 |
| 500 | 431 s | 8 910 s | 4,35 | 22 → **26** |

Trois enseignements. (a) Le comportement est **correct** — la difficulté fait exactement ce qu'on
lui demande — mais un opérateur qui recopie les réglages d'un devnet transforme son testnet en
réseau lent pendant plusieurs fenêtres. (b) Ce profil hérite de `maxDifficulty = 255` (mainnet) : il
n'a **pas** le garde-fou à 24 du devnet, donc rien n'arrête la montée avant que la cadence rejoigne
90 s. (c) La montée **résout le fork** : à difficulté 6-14 le trio vivait en égalité permanente à
deux tips (mêmes hauteur et travail total, tip alterné à chaque bloc) et 12 blocs sur 58 portaient
un oncle ; à partir de la difficulté 22 les trois nœuds tiennent un tip unique. Le retarget est donc
aussi le mécanisme qui éteint la tempête de forks — et GHOST, entre-temps, créditait le travail
orphelin.

### Constats d'outillage (à ne pas rejouer)

- **Un bloc forgé de toutes pièces est irrecevable.** Première tentative : construire un bloc
  complet (coinbase exact, merkle, supply plafond moins brûlage, nonce miné) et le poster. Réponse :
  `INVALID_STATE_ROOT` — la racine d'état engagée dans l'en-tête n'est calculable que par un nœud qui
  détient l'accumulateur. D'où le principe de `Anvil.java` : **prendre** un bloc réel, le re-parenter
  sur le tip de la victime, muter le seul champ visé, ré-miner. Licite parce que la source mine
  seule (aucun oncle) : la supply d'en-tête ne dépend de la difficulté que par les termes
  oncle/neveu.
- **Toute mutation acceptée fait diverger la victime de la source**, d'où le re-parentage
  systématique — sans lui, tous les cas suivants tombaient en `INVALID_LASTBLOCK_HASH` et ne
  prouvaient rien. Même leçon que la campagne 7 sur `set -e` : un rejet à la mauvaise porte est un
  faux positif.
- **`/info.snapshotPivot` décrit ce que le nœud SERT, pas ce dont il est parti.** Un nœud
  snap-syncé sans `RHIZOME_SNAPSHOT_EVERY` affiche 0 alors qu'il a bel et bien adopté un pivot ; la
  preuve d'adoption est le filigrane (`prunedBelow = pivot + 1`). Trois assertions de la première
  version de S27 étaient mal posées pour cette raison (et une quatrième comparait deux tips d'une
  cible mouvante) ; corrigées, elles passent.
- **La casse des empreintes hexadécimales n'est pas uniforme entre routes** : `/stats.stateRoot` est
  en minuscules, `/block.stateRoot` et `/state/snapshot/info.stateRoot` en majuscules. Même piège
  que la campagne 7 sur les identifiants de token : normaliser des deux côtés avant de comparer.
- **`avgBlockIntervalMs` est ininterprétable sur une chaîne neuve** : l'horodatage de la genèse
  valant 0, l'indicateur vaut ~85 000 000 000 ms tant que la genèse est dans la fenêtre. Il redevient
  juste ensuite. À savoir quand on regarde le tableau de bord d'un réseau qui vient de démarrer.
- **Ne pas éditer un script pendant qu'il tourne** : bash relit le fichier par offset, et le
  récapitulatif final de `run-campaign.sh` est mort sur une erreur de syntaxe fantôme alors que le
  fichier était valide. Les TSV, eux, étaient intacts.
- Le tableau d'environnement du README liste **14** variables ; le nœud en lit **20**.
  `RHIZOME_PRUNE`, `RHIZOME_SYNC`, `RHIZOME_SNAPSHOT_EVERY`, `RHIZOME_VOTE`,
  `RHIZOME_ALLOW_PRIVATE_PEERS` et `RHIZOME_ALLOW_OPEN_API` ne sont documentées que dans les
  `docs/*/spec.md` servis par le nœud. Ce sont précisément les leviers de bootstrap et de rétention
  qu'un opérateur tiers cherche en premier.

### Couverture non atteinte (mise à jour)

Ce que la campagne 8 **ne** ferme **pas**, et qu'il faut donc encore considérer comme non éprouvé :

- **La dérive d'horloge machine.** `faketime` est absent et on n'installe rien : seule la règle
  d'en-tête est mesurée, des deux côtés de la borne. Un nœud dont l'horloge dérive au-delà de la
  fenêtre verra ses blocs refusés (TIME-01a le prouve), mais la conséquence systémique — le nœud
  décroche, puis raccroche après resynchronisation NTP — reste extrapolée. Sur mainnet la fenêtre
  est de **15 s**, pas 120 : c'est là que le sujet devient sérieux.
- **TLS et `RHIZOME_PEER_TOKEN`.** Le jeton pair n'est émis qu'en `https://` ; tout ce terrain est
  en clair sur loopback. Un déploiement public à ingestion gatée reste non monté de bout en bout.
- **La durée.** ~900 blocs en 1 h 15 ne disent rien de la croissance RocksDB sur des jours,
  de la décroissance des scores de ban sur des horizons longs, ni de `REORG_TOO_DEEP` (profondeur
  120) et des checkpoints, toujours jamais atteints.
- **Le PoW de mainnet.** Tout ceci tourne en SHA256. `PUFFERFISH2`, son coût mémoire et son
  plancher de difficulté 16 restent prouvés en JUnit seulement.
- **BURN et DECAY** restent structurellement inatteignables (supply très loin de `S*`), comme en
  campagne 6-7.

## Journal de résultats — campagne 7

Campagne exécutée le 2026-09-03 (base 4400), **12 nœuds natifs devnet, 4 mineurs** (0, 3, 6, 9),
`-Xmx128m` par nœud, `RHIZOME_TESTNET_BLOCK_MS=15000` (~4 s/bloc agrégés), sur le même HEAD
009-native-coin-burn que la campagne 6 — binaires natifs inchangés et plus récents que toute
source Java, donc aucun rebuild. `.testnet` purgé avant lancement.

Axe : non plus l'échelle du réseau mais **l'étendue des scénarios** — transactions, portefeuilles,
contrats. Les six batteries S20-S25 remplacent la batterie *ad hoc* de la campagne 6 par de
l'outillage rejouable (`run-campaign.sh`).

### Pourquoi 12 nœuds et non 30

Ce que 30 nœuds achètent — partition en deux camps égaux, saturation du cap `MAX_PER_SUBNET` — est
déjà pinné par S3/S7/S15 et n'est pas l'objet ici. À 12 nœuds le maillage est **complet** (11 pairs
par nœud, sous le cap de 16), ce qui rend le critère de convergence plus net, et la RAM libérée
sert aux JVM du wallet CLI que les batteries lancent par centaines. La cadence à 15 s par mineur
est assez lente pour tenir un tip unique en continu, assez rapide pour qu'un scénario qui attend
une confirmation ne coûte pas une minute.

### Résultats

| Batterie | Périmètre | Résultat |
|---|---|---|
| pré-vol | convergence, maillage | **PASS** — 12/12 nœuds, écart de hauteur 0, **tip unique**, 11 pairs par nœud, `degraded` null partout |
| S20 transactions | INFL, SIG, REPLAY, POOL, CODEC, API — nominal + exploits | **PASS** — 48/48 cas |
| S21 portefeuilles | WALLET-01..06, clé chiffrée, TOFU, boîtes, tokens | **PASS** — 35/35 cas |
| S22 contrats | 9 templates déployés/appelés + 10 modules adverses | **PASS** — 43/43 cas |
| S23 chaîne | chaînage, oncles/GHOST, supply, explorateur | **PASS** — 11/11 cas |
| S24 transport | SSRF, pair hostile, blocs poubelle, limiteur, XFF | **PASS** — 38/38 cas |
| S25 persistance | SIGTERM puis SIGKILL, empreinte d'état | **PASS** — 15/15 cas |
| | | **190 PASS, 0 FAIL** |

Les verdicts détaillés (un par cas) sont dans `.testnet/results/*.tsv`. Trois cas ont dû être
**récrits** avant d'être verts, et dans les trois cas c'est l'assertion qui était fausse, pas le
nœud — voir « Constats d'outillage » : ils sont la vraie matière de cette campagne.

### Le constat central : l'admission n'est pas une autorisation

Quatre formes du même piège de lecture, toutes mesurées cette campagne :

1. **Nonce futur** (POOL-03, déjà connu) — admis (`SUCCESS`), garé, jamais minable tant que le trou
   n'est pas comblé. Vérifié : ni le solde ni le nonce ne bougent sur 3 blocs, et la garée sort dès
   la séquence complétée.
2. **DEPLOY d'un module WASM invalide** (nouveau) — admis (`SUCCESS`) : c'est une transaction bien
   formée et payée. Le module est refusé à l'**exécution** ; `/contract` répond `exists:false` sur
   le nœud victime **et** sur un nœud distant. Le gaz, lui, est bien débité.
3. **TOKEN_TRANSFER par un non-détenteur** (nouveau) — admis (`SUCCESS`), puis annulé en douceur :
   nonce consommé, **zéro token déplacé** (le destinataire reste à 250, l'attaquant à 0).
4. **`POST /add_peer`** (nouveau) — répond **toujours** `200 {"status":"OK"}` : c'est une annonce,
   pas une admission. Le filtre SSRF tourne dans `node.addPeer(url)` et laisse tomber la cible en
   silence. La preuve d'un refus est le **registre** (`/peers`), jamais le code HTTP.

Conséquence méthodologique, inscrite dans S22 et S24 : un scénario qui n'observe que le statut
d'admission ne prouve rien. La preuve est l'effet sur le grand livre — ou sur le registre — après
coup.

### GHOST mesuré pour la première fois

La campagne 6 avait écrit que le plan *affirmait* la production d'oncles « mais aucune campagne n'a
jamais inspecté un `/block` pour le confirmer ni vérifié la comptabilité des récompenses en
direct ». `chainscan.py` le fait : sur **728 blocs**, **146 oncles répartis sur 107 blocs**, **zéro**
rupture de chaînage, et l'identité

    supply(h) − supply(h−1) == subvention + n × (subvention/2 + subvention/32)

vérifiée **sur chaque bloc, sans une seule exception**. Avec la subvention devnet de 131 697 unités,
un oncle vaut exactement 69 963 unités = 65 848 (récompense d'oncle, `uncleRewardNum/Den` = 1/2)
+ 4 115 (prime de neveu, `nephewRewardDivisor` = 32). Les mineurs d'oncle relevés sont les quatre
mineurs configurés, aucun tiers. Détail notable : ces récompenses ne passent par **aucune
transaction coinbase** — le coinbase vaut 131 697 avec ou sans oncle ; elles n'apparaissent que dans
la supply engagée dans l'en-tête, ce qui est exactement ce qui rend leur réversion structurelle.

### Ce que les batteries ferment (manques listés par la campagne 6)

- **Dépôt d'un `.wasm` malveillant sur un nœud vivant.** Les dix modules de `wasmgen.py` sont postés
  en direct ; aucun n'est installé, sur aucun nœud. Les deux contrôles (module minimal valide, type
  à **exactement** 1000 paramètres — la borne du cap) le sont. Le gaz débité sépare deux familles de
  refus : ~830 à 1 100 unités quand la garde structurelle mord avant l'instanciation (pas d'export
  `call`, compteur démesuré, import hors ABI, mémoire importée, flottants, mémoire hors cap), 10 900
  pour le type à 1001 paramètres, et la **totalité du plafond** (200 000) pour les deux modules qui
  forcent le parse à travailler d'abord (4 097 globals, 20 000 fonctions).
- **Portefeuille chiffré et épinglage `chainId`.** Tout WALLET-01..06 en direct : enveloppe AES-GCM
  sans clé en clair, fichier en `600`, mauvaise passphrase et enveloppe falsifiée refusées, pas
  d'écrasement sans `--overwrite`, et surtout l'épingle TOFU testée contre un **vrai nœud d'une
  autre chaîne** (`RHIZOME_NETWORK=testnet`, chainId 2) : l'envoi abandonne **avant de signer** en
  nommant les deux chainId, alors que `balance` y reste permis.
- **Racine d'état authentifiée.** `/state` comparé entre les 12 nœuds, **groupé par tip** : une seule
  racine par tip. `/state/proof?domain=ledger&key=<adresse>` sert une preuve sur deux nœuds
  différents ; une clé absente rend 404 plutôt qu'une preuve fabriquée.
- **Limiteur de débit sous flot réel.** 400 lectures en rafale → 281 refusées en 429 ; 400 de plus
  avec un `X-Forwarded-For` tournant → 276 refusées : l'en-tête n'est pas cru, donc il n'esquive pas
  le limiteur. La vanne de gaz des dry-runs mord encore plus tôt : 58 des 60 `call_readonly` en
  rafale sont délestés.
- **Comptabilité oncle/neveu** — ci-dessus.

### Deux observations à porter au catalogue

**1. Les segments-point ne sont pas normalisés dans l'identité de pair.** `PeerUrls.canonicalize`
préserve délibérément un chemin non-racine (« deux montages distincts ne doivent pas se confondre
silencieusement ») et ne résout pas les segments `.`/`..` de la RFC 3986. Mesuré sur un nœud vivant :
`http://localhost:4402`, `…:4402/.` et `…:4402/././.` sont **trois identités de registre pour un
seul point d'accès**. Ce n'est **pas** une évasion de ban (le ban est clé par point d'accès,
`PeerBanListTest#banIsKeyedByEndpointNotAddress`) et l'inflation reste bornée par le cap
anti-éclipse par sous-réseau (mesuré : le registre plafonne à 18 = 16 découverts + 2 seeds). C'est
de l'inflation de registre et des connexions redondantes vers un même pair, pas une primitive
d'éviction. À arbitrer : normaliser les segments-point dans `canonicalize`, ou documenter que
l'identité est l'URL de montage et non le point d'accès.

**2. Un port hors bornes passe la validation d'URL.** `http://example.com:99999` (au-delà de 65535)
n'est pas rejeté comme `ftp://` ou `not a url` : l'hôte est public, le schéma correct, l'entrée est
**admise** puis évincée quand elle se révèle injoignable. Observé dans les deux états selon
l'instant de la mesure — d'où un cas qui accepte les deux et n'exige que le retour à un registre
vide. Impact : un créneau de registre transitoire, qui se répare seul.

### Constats d'outillage (à ne pas rejouer)

**1. Chaque déploiement réserve `gasLimit × gasPrice`.** Une batterie qui déploie vingt-cinq modules
vide un portefeuille doté une seule fois ; les cas suivants sont refusés en `BALANCE_TOO_LOW` et
chacun paie une attente de nonce qui n'arrivera jamais — la batterie a stagné quinze minutes sur ce
mode d'échec avant correction. `suite-contract.sh` recharge donc avant chaque envoi et n'attend le
minage **que si** la transaction a été admise.

**2. La forme du refus d'un corps surdimensionné n'est pas assertable.** Selon la vitesse à laquelle
le nœud coupe par rapport à l'envoi, `curl` voit un 400 **ou** une connexion fermée en cours
d'écriture (code 000) — observé dans les deux sens sur un même corps de 3 Mo. L'invariant assertable
est « refusé, jamais accepté, nœud vivant juste après », pas un code précis.

**3. Une substitution de commande est un sous-shell.** Un `deploy()` qui rend son adresse par
`stdout` et son statut par une variable globale perd le statut : `$(deploy …)` s'exécute dans un
sous-shell. Les deux sorties passent par des globales.

**4. `set -e` est le mauvais réglage pour une batterie.** `common.sh` le pose (correct pour
start/stop) ; une batterie dont la moitié des cas provoquent délibérément un refus mourrait au
milieu et masquerait tout ce qui suit. `suite-common.sh` fait `set +e` et chaque cas porte son
propre verdict.

**5. Un voisin peut fausser toute une campagne.** Une compilation `native-image` d'un autre
workspace de la même machine (16 Go de RSS, charge moyenne **399**) a fait expirer des appels du
wallet CLI et produit des échecs qui n'étaient pas ceux du nœud. À vérifier avant de conclure :
`uptime`. Fait notable au passage — **les douze nœuds natifs ont traversé l'épisode sans broncher** :
12/12 répondants, écart de hauteur 0, tip unique, `degraded` null, à ~50 Mo de RSS chacun.

### Couverture non atteinte (mise à jour)

Restent hors de portée de ce testnet, inchangé depuis la campagne 6 — mais désormais **vérifié plutôt
qu'affirmé** : **BURN/DECAY/SUPPLY/FLOOR** (la batterie `chain` mesure `burnDebt=0`, `burned=0` : la
dette ne peut pas naître sous une cible de ~300 M PDN), **POW/TIME** (difficulté relevée à 6 sur les
728 blocs balayés, donc retarget, timewarp et bornes de difficulté inertes). Restent non exercés :
**snap-sync** et **pruning**, **REORG_TOO_DEEP** et les checkpoints, le **minage égoïste/grinding**
(exige un mineur tricheur), https/`RHIZOME_PEER_TOKEN`/`RHIZOME_PROTECT_READS`, et la **reversal
d'un reorg à travers l'état de contrat** (S25 prouve la survie au crash, pas la réversion — S7 la
prouve pour le grand livre). S4 et S10-S17 n'ont pas été rejoués cette campagne ; ils le restent
tels quels.

---

## Journal de résultats — campagne 6

Campagne exécutée le 2026-09-02 (base 4300), 30 nœuds **natifs** devnet, 10 mineurs
(0, 3, 6, 9, 12, 15, 18, 21, 24, 27), `-Xmx128m` par nœud, sur le HEAD 009-native-coin-burn.
Objectif : rejouer la campagne sur la revue adverse *après* l'atterrissage des features 008/009
(cible de supply décroissante, burn natif), et pousser en réseau réel une batterie d'exploits
tirée du catalogue (`docs/adversarial/spec.md`) au lieu de s'appuyer uniquement sur le harnais
JUnit `E2E`. Le plancher composant `./gradlew adversarial` était **vert** avant de commencer
(BUILD SUCCESSFUL, 2 min 30), image native reconstruite (62 Mo).

> **Données d'août incompatibles — purge obligatoire.** Le premier lancement a échoué :
> `BufferUnderflowException` dans `HeaderWire.readPrefix` au boot du nœud 0, et les autres nœuds
> ressuscitaient l'ancienne chaîne (h≈466) depuis `.testnet/node-*`. Les répertoires RocksDB de
> la campagne 5 (2026-08-20) datent d'avant 008/009 : le schéma de bloc persisté a changé, donc un
> testnet **vraiment neuf** exige `rm -rf .testnet/node-*` (les clés de `scripts/local-testnet/keys/`
> sont, elles, indépendantes de la chaîne et réutilisables). C'est un nouveau constat : les
> campagnes précédentes rejouaient sur un schéma stable. Une fois purgé, les 30 nœuds ont démarré
> et convergé normalement.

| Scénario | Résultat | Détail |
|---|---|---|
| S0 convergence | **PASS** | 30/30 `/stats`, h=35 écart=0 tip unique après le premier retarget (la bouffée de fork post-genesis — 8 tips à h=10 — se résorbe une fois la difficulté sortie du plancher) |
| S1 gossip tx | **PASS** | mempool ~7 sur les 30 nœuds à h=48, une rafale du simulateur atteint tout le réseau |
| S2 propagation blocs | **PASS** | observateurs au même tip/hauteur que les mineurs sans pull manuel |
| S3 PEX | **PASS** | 18 pairs par nœud (cap anti-éclipse), sans doublon |
| S5 panne mineur | **PASS** | miner-9 arrêté, observateur node10 croît 296→302 |
| S6 resync | **PASS** | miner-9 relancé → h=311, degraded null, reorgInProgress false |
| S7 partition 15/15 + guérison | **PASS** | partition **étanche** (0 pair cross-camp mesuré via `/peers`) ; camp A travail=27776 vs camp B=27584 ; après **2 ponts** `/add_peer` croisés, retour à tip unique en **~40 s** ; camp B a `REORGED` sur la branche A la plus lourde ; solde miner-27 **identique** (299,1364 PDN) vu des deux ex-camps (annulation par journaux d'undo) ; **aucun ban** (`syncPeersBanned=0` partout), degraded null tout du long ; convergence finale h=274 écart=0 tip unique sur 30 |
| S8 wallet E2E | **PASS** | 2,5 PDN via node 3 → solde identique lu sur node 29 distant ; token natif (mint 1e6 RHZT, transfert 250, soldes 999750/250 sur node 20 distant) et box natif (valeur 1 PDN, registre str, visible node 25 distant) exercés en plus |
| S9 contrats distribués | **PASS** | `sim-contract.sh check` : 1 état par groupe de tip (compteur + token WASM), 0 divergence d'exécution |
| **S18** burn natif *(BURN/SUPPLY/FLOOR, nouveau)* | **PASS** | supply amorcée au-dessus de la cible par snapshot ; burn capturé en direct (bloc 121 `burned=35000`, bloc 122 `burned=15000`) ; supply tirée sous son genesis ; 3 nœuds bit-à-bit + 4ᵉ nœud neuf convergent sur la chaîne brûlée. Détail plus bas |
| **S19** crash `kill -9` + recovery *(PERS/A6, nouveau)* | **PASS** | SIGKILL d'un mineur en pleine prod ×3 : base rouverte sans corruption, non tronquée, nonce non régressé, témoin bit-à-bit (17500/0), survivants continuent. Détail plus bas |

### Batterie d'exploits en réseau réel (revue adverse, nouveau pour cette campagne)

Toutes les transactions sont **signées** (petit forgeur sur le classpath du wallet, `signedSend`/
`signedContract` avec des champs arbitraires) et postées sur `/add_transaction_json` d'un nœud
vivant, pour atteindre la vraie porte de consensus plutôt que d'être rejetées comme corps
malformé (règle 2 du protocole). Après chaque rejet on vérifie que **le refus est gratuit** : le
nœud victime a miné 156 → 176 pendant toute la batterie, l'attaquant n'a **pas** été banni (une tx
valide nonce-0 ensuite = `SUCCESS`), et `degraded` est resté null.

| Famille | Attaque | Statut renvoyé | Verdict |
|---|---|---|---|
| INFL | montant négatif (−100000) | `INVALID_TRANSACTION_AMOUNT` (400) | rejeté |
| INFL | montant `Long.MAX` | `BALANCE_TOO_LOW` (400) | rejeté |
| INFL | dépense > solde (100 PDN d'un compte 5 PDN) | `BALANCE_TOO_LOW` (400) | rejeté |
| SIG | chainId=999 (rejeu inter-réseau) | `INVALID_CHAIN_ID` (400) | rejeté |
| SIG | montant altéré sous signature (abordable) | `INVALID_SIGNATURE` (400) | rejeté |
| SIG | destinataire altéré sous signature | `INVALID_SIGNATURE` (400) | rejeté |
| VM | appel poison `gasLimit`=1e11 (> `maxTxGas` 5e7) | `GAS_LIMIT_EXCEEDED` (400) | rejeté |
| POOL | nonce futur (gap, 9) | `SUCCESS` (admis mempool) | policy **sûre** : cap 1024/expéditeur, TTL parked, jamais minable tant que le trou n'est pas comblé, 0 mouvement de solde/nonce (vérifié : solde et nextNonce inchangés) |
| CODEC/API | corps malformé sur les 5 routes POST | 400 partout | pas de crash, degraded null |
| API (E2E-06) | POST cross-site (Origin étranger, avec **et** sans marqueur) | 403 `cross-origin request refused` | rejeté |
| API (E2E-07) | forme DNS-rebinding (Origin==Host, sans marqueur) | 403 | rejeté |
| API | contrôle same-origin + marqueur (dashboard légitime) | 200 `OK` | accepté |
| CODEC | corps 12 Mo sur `/submit` | 400, coupé à ~5,5 Mo par le cap corps | borné, pas d'OOM |

Note d'ordonnancement (armure DoS, WHITEPAPER §3.5) : le premier essai d'altération de montant
(5 → 999999) est ressorti `BALANCE_TOO_LOW`, pas `INVALID_SIGNATURE` — le contrôle de solde
précède le contrôle de signature (le moins cher d'abord). Refait avec une altération *abordable*
(5 → 6), il atteint bien la porte de signature et ressort `INVALID_SIGNATURE`.

### Constats de campagne

**1. Purge des données d'août obligatoire (voir encadré ci-dessus).** Nouveau : le schéma de bloc
persisté a changé avec 008/009, donc les répertoires RocksDB des campagnes antérieures ne se
rejouent plus — `rm -rf .testnet/node-*` avant de lancer une campagne postérieure à un changement
de schéma. Le conflit de port 3000 des campagnes 4/5 ne s'est **pas** manifesté cette fois, mais
la base 4300 a été utilisée par précaution comme les deux campagnes précédentes.

**2. Régime de fork transitoire à cadence rapide (déjà caractérisé, pas un défaut).** À difficulté
plancher (6) avec 10 mineurs, le réseau vit avec 4-5 tips distincts, écart ≤ 3-4, qui se résorbent
en continu ; il atteint le tip unique de façon **répétée** (h=35, h=176, et 2× pendant la guérison
S7) sans le tenir en permanence sous production plancher. Jamais de divergence durable, jamais
`REORG_TOO_DEEP`, jamais `degraded`. Les frappes `+34 (served an invalid chain)` observées sous
charge sont la pénalité d'une branche qui perd une course de fork ; elles décroissent et ne
composent **jamais** en ban pour un nœud honnête (`syncPeersBanned=0` en fin de campagne sur les
30 nœuds) — l'interlock déjà documenté par S16/NET-11 et `BanDiscoveryPartitionAttackTest`.

**3. La batterie d'exploits confirme en réseau réel ce que le catalogue prouve au composant.**
Chaque rejet observé porte le **statut exact** attendu (pas un « refusé » générique) et le refus
est gratuit (le nœud victime a continué à miner, l'attaquant n'a pas été pénalisé). Le seul
`SUCCESS` — le nonce futur (POOL) — est l'admission mempool bornée d'une tx non-minable, pas un
vol : solde et nonce confirmés inchangés. C'est le complément « network » des preuves « component »
INFL/SIG/VM/CODEC/API et « E2E » du harnais JUnit.

### Couverture non atteinte — ce que cette campagne (et ce plan) ne teste PAS

Analyse post-campagne, classée par nature du manque. Elle existe pour que la prochaine campagne
sache où se trouve la frontière, et pour ne pas laisser « 30 nœuds, tout PASS » se lire comme une
couverture qu'il n'a pas. Deux propositions de scénarios (S18, S19) en sortent, listées en fin.

**A. Le burn 008/009 sur le binaire natif — était un trou, désormais couvert par S18.**
`devnet()` hérite `supplyTarget = 2 997 924 580 000` (≈300M PDN) et démarre à une supply ~0, donc
`debt = max(0, supply − S*(h))` reste **0 pour toujours** en régime normal (il faudrait miner ~100M
blocs pour croiser S\*) : le burn/décroissance ne se déclenche jamais sur un devnet *ordinaire*.
**Correction d'une affirmation trop forte d'une version antérieure de cette section** : le burn
*est* prouvé au niveau réseau — `E2EBurnTest` (E2E-88) fait tourner de **vrais** `RhizomeNode`
(RocksDB sur disque, sockets loopback, threads producteur/sync, in-JVM) prémine au-dessus de la
cible et vérifie `burned > 0` puis un pair neuf convergeant bit-à-bit. Ce qui manquait vraiment,
c'était la preuve sur le **binaire natif** (SubstrateVM) et sur des **processus OS** séparés — fermé
par **S18** (voir ci-dessous) en amorçant la supply au-dessus de la cible par un snapshot genesis.
Reste hors testnet : **DECAY-01..04** (la décroissance par époque exige `decayStartHeight > 0`, que
devnet met à 0 ; testable seulement via un profil dédié) et la reversal d'un reorg à travers un bloc
brûlant en direct (BURN-05, structurelle, prouvée au composant).

**B. Intestable par construction sur devnet (difficulté au plancher).**
- **POW / TIME / difficulté** : devnet colle la difficulté à 6, donc le retarget, la défense
  timewarp (median-time-past), les bornes de difficulté et la fenêtre `maxFutureBlockTimeSec` ne
  sont jamais sollicités en direct. Toute la famille POW/TIME vit en JUnit.
- **UNCLE / GHOST** : le plan *prétend* que la répartition régulière des mineurs produit des oncles,
  mais aucune campagne n'a jamais inspecté un `/block` pour confirmer des références d'oncle ni
  vérifié la comptabilité des récompenses oncle/neveu en direct. Assertion non mesurée à ce jour.

**C. Dans le plan, mais non rejoués cette campagne (S0-S3, S5-S9 seuls exécutés).**
Non exécutés : **S4** (churn 31ᵉ nœud), **S10** (sémantique des alertes monitor), **S11-S15**
(P1-P5 : pair perdu en reorg, éclipse observable, hôte partagé, dashboard 503 en reorg, départage
strict), **S16/S17** (pair confirmé menteur NET-11 / API-token multi-nœuds API-13, remplacés cette
fois par la batterie d'exploits). Rejouables tels quels — voir campagnes 4/5 pour le détail.

**D. Absent du plan entièrement (aucun scénario ne les touche).**
- **PERS / cohérence au crash (adversaire A6)** : ~~aucun `kill -9`~~ **fermé par S19** (voir
  ci-dessous). Un harnais de kill dur existait déjà (`ProcessHarness.destroyForcibly`) mais servait
  au boot genesis ; E2E-17 ne prouve qu'un restart *gracieux*. S19 ajoute le SIGKILL d'un mineur
  **en pleine production** ×3, avec preuve de recovery (base rouverte sans corruption, non tronquée,
  nonce non régressé, témoin bit-à-bit).
- **Pruning** (`RHIZOME_PRUNE`) : jamais lancé ; pas de vérif qu'un nœud élagué sert le watermark et
  **refuse** l'historique jeté (E2E-31).
- **Snap-sync** (`RHIZOME_SYNC=snap`) : hors périmètre assumé, mais c'est un vrai chemin de bootstrap
  (E2E-24/32/40/41) prouvé seulement en JUnit.
- **Racine d'état authentifiée (STATE)** : la campagne compare l'état *applicatif* des contrats
  (`statecheck.py`), jamais la racine SMT `/state` ni les preuves `/state/proof` entre nœuds.
- **Dépôt d'un `.wasm` malveillant sur un nœud vivant** : en direct, seul l'over-gas a été tenté.
  Les rejets float/SIMD/GC/table/mémoire (`WasmAdversarialTest`) sont deploy-time et ne passent
  jamais par un vrai `/add_transaction` ici.
- **Rate-limiting / flood (NET)** : `RateLimiter` et caps par sous-réseau sous flot réel
  multi-adresses — E2E-16 est JUnit ; aucun scénario réseau.
- **REORG_TOO_DEEP / checkpoints** : la partition S7 est peu profonde ; `maxReorgDepth` et le rejet
  par checkpoint ne sont pas exercés en direct.
- **Wallet chiffré / TOFU chain-id (WALLET)** : tout est fait en `--plaintext`. Le pinning chain-id
  (trust-on-first-use), le prompt de passphrase, le warning URL non-sécurisée — non testés en direct.

**E. Un finding de cette campagne rend un GAP du catalogue obsolète.**
`docs/adversarial/spec.md` déclare **E2E-89 (GAP)** avec pour raison « native-image n'est pas
installé dans cet environnement ». C'est **faux depuis cette campagne** : l'image native a été
construite (62 Mo) et 30 nœuds natifs ont tourné. La résolution de la ressource genesis pinnée *sur
le binaire natif* (lien E2E-37/48) est désormais *fermable* mais reste non testée (devnet a un
genesis non-pinné, sans ressource). À réévaluer dans le catalogue — non modifié ici : reclasser un
GAP machine-vérifié est une décision qui mérite son propre commit, pas un effet de bord de campagne.

**F. Hors périmètre, assumé et correct (rappel).**
https/TLS, `RHIZOME_PEER_TOKEN` (https-only), `RHIZOME_PROTECT_READS`, multi-machines, minage
égoïste/grinding REORG-11/12 (exige un mineur tricheur que le binaire stock n'est pas — documenté).

### S18 — Burn natif en réseau réel *(BURN, SUPPLY, FLOOR — exécuté 2026-09-02)*

**But** : prouver que le burn de 009 se déclenche sur le **binaire natif** (SubstrateVM), pas
seulement dans le harnais JUnit `E2EBurnTest`. `devnet` ne croise jamais S\* par lui-même (voir
l'encadré du Périmètre), donc on **amorce la supply au-dessus de la cible** par un snapshot genesis :
`devnet` a un `genesisSupply` non-pinné, donc son contrôle de boot n'exige aucun total particulier.

1. Forger une clé `premine`, écrire un snapshot devnet (chainId 3) où cette adresse détient
   `supplyTarget + 1 000 000 000` base units (2 998 924 580 000), lancer 3 nœuds natifs
   (`RHIZOME_SNAPSHOT=<ce fichier>`, node 0 mineur, `RHIZOME_BLOCK_INTERVAL_MS=2000`).
2. Le tip démarre au-dessus de la cible : `emission.burnDebt > 0` (mesuré 1 000 000 800), `burned=0`
   tant qu'aucun flux de frais ne remplit le pool.
3. Créer le pool : soumettre en rafale des transactions **payant des frais** depuis le portefeuille
   premine (nonces contigus, admis en un bloc), le mineur crédite les frais → le bloc brûle
   `min(⌊pool × 1/2⌋, debt)`.

**Résultat (PASS)** : burn **capturé en direct** — bloc à hauteur 121 `burned=35000` (7 tx × frais
10000 ÷ 2), bloc 122 `burned=15000` ; le champ API `burned` porte bien le montant **du bloc-tip**
(par-bloc, jamais cumulatif) ; la supply a été tirée **sous** son genesis (2 998 924 578 400 <
2 998 924 580 000) sous pression de frais — le mécanisme de destruction réduit la supply native, sur
le binaire d'opérateur. Les 3 nœuds convergent **bit-à-bit** sur la chaîne brûlée (même tip, même
supply committée, même `burnDebt`), et un **4ᵉ nœud neuf** reconstruit l'historique brûlé à
l'identique (tip et supply égaux au mineur) — l'assertion d'`E2E-88` rejouée sur processus natifs.
*Non couvert ici* : la reversal d'un reorg **à travers** un bloc brûlant (BURN-05) — orchestrer une
égalité de fork sur un réseau mono-mineur n'est pas déterministe ; elle reste prouvée par
`BurnAttackTest#aReorgAcrossABurningBlockRestoresSupplyAndLedgerExactly` et est structurelle
(`burned` re-dérivable de deux en-têtes, aucun code de rollback).

### S19 — Crash `kill -9` + recovery *(PERS, adversaire A6 — exécuté 2026-09-02)*

**But** : la seule classe d'adversaire du catalogue (A6, *process kill*) que ce plan ne touchait pas
en réel. `stop.sh` est un arrêt *gracieux* (flush propre) ; E2E-17 prouve un restart gracieux. Ni
l'un ni l'autre ne teste un SIGKILL **en pleine production** (recovery d'une écriture potentiellement
déchirée : WriteBatch atomique + WAL RocksDB).

1. Réseau natif 6 nœuds / 3 mineurs, chaîne à hauteur réelle (≈350, données persistées).
2. Doter un portefeuille **témoin** qui reçoit une fois et n'émet jamais (solde et nonce doivent
   être invariants à travers les crashs).
3. `kill -9` le PID d'un **mineur** en pleine production, ×3 à des hauteurs/nœuds différents ;
   après chaque mort, relancer par `start.sh -n <i>` sur son propre répertoire de données.

**Résultat (PASS)** : sur les 3 crashs, chaque nœud a **rouvert sa base RocksDB sans corruption**
(0 ligne `corrupt`/`BufferUnderflow`/`FATAL`/`failed to open`), est revenu **non tronqué**
(hauteur ≥ hauteur pré-crash, puis rattrapage), `degraded == null`, `reorgInProgress == false` ;
le nonce n'a **jamais régressé** (pas de rejeu possible) ; le solde du témoin est resté
**bit-à-bit identique** (17500 base units, nonce 0) lu depuis le nœud ressuscité ; et les 5 nœuds
survivants ont continué à croître pendant chaque fenêtre de mort (la chaîne survit à la perte d'un
mineur). L'atomicité WriteBatch + WAL de RocksDB tient sous SIGKILL au pire moment.

---

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
