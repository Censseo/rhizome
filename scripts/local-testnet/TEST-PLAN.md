# Plan de test — testnet local (10 nœuds)

## Objectif

Valider le comportement d'un petit réseau Rhizome de **10 nœuds sur une seule machine**
(loopback) : convergence, gossip de transactions et de blocs, découverte de pairs (PEX),
tolérance aux pannes, reorgs et reprise après redémarrage. Ce plan est exécutable avec les
scripts de `scripts/local-testnet/`.

## Périmètre

- 10 nœuds complets (RocksDB), réseau `devnet` (PoW SHA256 à faible difficulté, cible réelle
  5 s — voir `NetworkParameters.devnet()` ; ne pas remplacer par `testnet` pacingé, la
  difficulté s'emballerait, cf. README).
- 2 mineurs, 8 nœuds observateurs purs.
- P2P HTTP sur loopback, sans jeton : pas de `RHIZOME_API_TOKEN`/`RHIZOME_PEER_TOKEN` (le
  jeton pair n'est envoyé qu'en `https://`, il est hors sujet sur un testnet local en
  `http://`).

Hors périmètre : chiffrement (https), `RHIZOME_PROTECT_READS`, snap-sync
(`RHIZOME_SYNC=snap`), testnet multi-machines, coût de validation.

## Topologie

| Rôle | Nœuds | Ports | `RHIZOME_MINER` |
|---|---|---|---|
| Mineurs | 0, 1 | 3000, 3001 | adresse dédiée (clé générée par le wallet CLI) |
| Observateurs | 2–9 | 3002–3009 | — |

- Peering initial en **anneau** : le nœud `i` se seed sur `(i−1) mod 10` et `(i+1) mod 10`.
  Le reste du maillage est découvert par PEX.
- `RHIZOME_ALLOW_PRIVATE_PEERS=true` sur **tous** les nœuds : le filtre SSRF est actif par
  défaut et refuserait les pairs 127.0.0.1 appris via PEX (les seeds `RHIZOME_PEERS`
  contournent le filtre, pas les pairs PEX — `RhizomeNode` L164-175).
- Fuseaux : nœuds `i` et `i+5` forment une paire (même heure de démarrage), utile pour les
  scénarios de partition.
- Données : `.testnet/node-<i>` (répertoire propre, nettoyable à volonté).
- Cadence réelle observée : avec le plancher de difficulté 6 de devnet, le PoW SHA256 est
  quasi instantané — les blocs arrivent en continu (hauteurs de l'ordre de la centaine en
  quelques minutes sur 2 mineurs). Ne jamais poser d'assertion sur une cadence absolue de
  5 s ; toutes les tolérances sont relatives (écart entre nœuds, convergence).

> **Note machine de dev** : les ports 3000/3002 peuvent être pris par des outils externes
> (opencode, VS Code…). `start.sh` fait un pré-vol qui refuse de lancer les nœuds sur un
> port occupé ; dans ce cas relancer avec `RHIZOME_TESTNET_BASE_PORT=4100` (base libre).

## Prérequis

1. JDK 21 (`java -version`), vérifié par le toolchain Gradle.
2. `./gradlew build` — la suite passe avant de tester le réseau.
3. `./gradlew :app-node:installDist :app-wallet:installDist` — un seul build, les 10 nœuds
   tournent en direct via `app-node/build/install/app-node/bin/app-node` (pas 10 daemons
   Gradle). `start.sh` le fait lui-même si le binaire manque (et force `JAVA_HOME` vers un
   JDK 21 trouvé dans sdkman si `JAVA_HOME` pointe sur un JDK > 24 que Gradle refuse).
4. Clés mineurs : `./gradlew :app-wallet:run --args="keygen scripts/local-testnet/keys/miner-0.key"`
   (et `miner-1.key`), puis `address` pour récupérer les adresses. `start.sh` fait tout cela
   automatiquement si les clés n'existent pas (`--plaintext`, non interactif).

## Procédure

```bash
scripts/local-testnet/start.sh      # build + clés + lance les 10 nœuds, attend /stats
scripts/local-testnet/status.sh     # une ligne par nœud : hauteur, difficulté, pairs, dégradé
scripts/local-testnet/monitor.sh    # boucle de supervision, CSV dans .testnet/monitor.csv
scripts/local-testnet/stop.sh       # arrêt propre de tous les nœuds
```

Variables d'override : `RHIZOME_TESTNET_DIR` (données, défaut `.testnet/`),
`RHIZOME_TESTNET_BASE_PORT` (défaut 3000 — si un port de la plage est occupé, `start.sh`
refuse de démarrer et le message indique de changer de base).

Lancer `status.sh` dans un autre terminal dès que le réseau est up : la convergence est
visible en direct.

## Critères de réussite généraux

- Tous les nœuds : `degraded == null`, `reorgInProgress == false` en régime stable.
- Les 10 nœuds atteignent la même hauteur et le même `tipHash` ; les écarts > 2 blocs
  pendant > 30 s sont des anomalies.
- Chaque nœud a ≥ 3 pairs connues (PEX), y compris après l'arrêt du seed d'origine.
- Un bloc miné par nœud 0 arrive chez tous les pairs en < 10 s (gossip push).

## Scénarios

Notation : hauteur du nœud `i` = `h_i` (via `curl -s http://127.0.0.1:300<i>/stats`).

### S0 — Lancement et convergence
1. `start.sh` ; attendre ~2 min (PEX + premiers blocs).
2. **Passe si** : 10 réponses `/stats` ; `max(h_i) − min(h_i) ≤ 2` ; la hauteur croît en
   continu (cadence rapide, cf. topologie).

### S1 — Propagation de transactions (gossip)
1. Sur le nœud 3 : `POST /add_transaction` avec une tx valide (ou le wallet, S8) depuis
   l'adresse d'un mineur riche en solde.
2. **Passe si** : la tx apparaît dans `GET /mempool` des 10 nœuds en < 10 s ; elle est minée
   dans un bloc ; plus aucun nœud ne la garde en mempool (elle est passée dans la chaîne).

### S2 — Propagation de blocs (push)
1. Laisser miner les nœuds 0 et 1 pendant 1 min en observant `/stats` des observateurs.
2. **Passe si** : les 8 observateurs suivent à ≤ 2 blocs sans jamais interroger (aucun pull
   déclenché manuellement) ; `avgBlockIntervalMs` cohérent entre observateurs (tous bas et
   proches les uns des autres — pas d'assertion sur la valeur absolue).

### S3 — Découverte de pairs (PEX)
1. `status.sh` après 5 min de fonctionnement.
2. **Passe si** : `peers ≥ 3` sur chaque nœud (anneau + PEX) ; aucun nœud ne se liste
   lui-même (refus d'auto-pairing).

### S4 — Ajout d'un nœud sur réseau vivant (churn)
1. Lancer un 11ᵉ nœud (port 3010, `RHIZOME_PEERS=http://127.0.0.1:3000`,
   `RHIZOME_DATA=.testnet/node-10`) avec le binaire installé.
2. **Passe si** : il rattrape la hauteur commune en < 60 s (headers-first) ; il apparaît
   dans les `peers` des autres ; `degraded == null`.

### S5 — Panne du seed d'origine
1. `stop.sh` n'ajoute rien : ici on tue manuellement le nœud 0 (`kill <pid>`).
2. Observer les 9 restants pendant 2 min.
3. **Passe si** : hauteurs continuent de croître ; les pairs continuent de se découvrir
   (PEX entre eux) ; aucun `degraded`. Les nœuds 1, 2, 9 (voisins d'anneau) voient leurs
   connexions sortantes échouer et les ban/écartent — c'est attendu, surveiller qu'aucun
   autre nœud ne se fige.

### S6 — Redémarrage d'un nœud (resync)
1. `stop.sh`, puis relancer uniquement le nœud 5 (`scripts/local-testnet/start.sh -n 5`
   si implémenté, sinon la commande manuelle du S0).
2. **Passe si** : il rattrape la hauteur courante < 60 s ; `reorgInProgress` reste `false`
   (pas de reorg : il était en retard, pas en divergence).

### S7 — Partition réseau / reorg (le test le plus important)
1. Couper la moitié du réseau : mettre hors réseau les nœuds 0–4 (kill) et les relancer
   avec `RHIZOME_PEERS` pointant uniquement vers leurs 4 semblables (les 5–9 font de même
   entre eux). Chaque moitié garde 1 mineur.
2. Laisser chaque moitié miner 2–3 min (chaînes divergentes de ~20 blocs chacune).
3. Rétablir le maillage complet : relancer tous les nœuds avec l'anneau complet.
4. **Passe si** : chaque nœud reorg sa chaîne vers la branche à plus de travail en
   < 2 min ; `reorgInProgress` vrai pendant la fenêtre puis `false` ; **aucun** `degraded` ;
   soldes des mineurs cohérents (la récompense de la branche abandonnée a été annulée via
   les journaux d'undo) ; les transactions incluses dans la branche perdue et encore
   valides reviennent en mempool.

### S8 — De bout en bout via le wallet CLI
1. `app-wallet` : `balance http://127.0.0.1:3003 <adresse-mineur-0>` (solde attendu =
   récompenses cumulées) ; `send http://127.0.0.1:3006 <clé> <adresse-cible> 100`.
2. **Passe si** : la tx est minée < 30 s ; `balance` de la cible +100 sur **n'importe quel**
   nœud (nœud 9) — prouve l'exécution déterministe du ledger partout.

### S9 — Contrat (validation optionnelle du VM distribué)
1. Depuis le dashboard du nœud 0 (`http://127.0.0.1:3000/`), déployer le template
   « token » ; appeler `mint` ; lire via `POST /contract/query`.
2. **Passe si** : l'état du contrat (lecture `/contract/query` sur le nœud 9) est identique
   partout ; un `call` de mint sur 2 nœuds différents aboutit au même solde de token.

### S10 — Supervision et indicateurs opérateur
1. Lancer `monitor.sh` pendant toute la campagne.
2. **Passe si** : `degraded` est resté `null` sur les 10 nœuds ; les transitions
   `reorgInProgress true→false` sont tracées dans le CSV (pendant S7) ; aucune hauteur
   figée plus de 60 s hors des fenêtres de partition volontaires.

## Supervision & alertes

`monitor.sh` (boucle 2 s) : interroge `/stats` des 10 nœuds, imprime un tableau et écrit
`monitor.csv` (horodatage, port, hauteur, difficulté, pairs, mempool, avgBlockIntervalMs,
reorgInProgress, degraded). Avertir immédiatement si :

- `degraded` ≠ `null` (barrière dure : le nœud refuse tout nouveau bloc tip et cesse de
  miner — README « Node health signals ») ;
- `reorgInProgress` ouvert > 5 min ;
- `peers == 0` sur un nœud ;
- un écart de hauteur > 5 blocs persistant hors S7.

## Arrêt et nettoyage

```bash
scripts/local-testnet/stop.sh
rm -rf .testnet          # données RocksDB + logs + CSV
```

Un arrêt sale (kill -9) est testé une fois : au redémarrage, les spools de snapshot
(`$RHIZOME_DATA/snapshots`) sont balayés et l'état relu sans corruption — signaler tout
`degraded` ou refus de démarrage.

## Journal de résultats

Campagne du 2026-08-06, base 4100 (ports 3000/3002 occupés par des outils de dev), 10 nœuds
devnet, 2 mineurs (0, 1) + mineur d'appoint 5 en S7. `degraded` est resté `null` sur les 10
nœuds pendant toute la campagne.

| Scénario | Résultat | Détail |
|---|---|---|
| S0 Lancement/convergence | **PASS** | 10/10 up, hauteurs identiques en < 60 s ; écart max transitoire 5 blocs pendant 14 s au démarrage (catch-up initial) |
| S1 Gossip de transactions | **PASS** | Tx acceptée sur le nœud 3, présente dans le mempool des **10 nœuds en < 1 s**, minée à t+4 s (mempool vidé partout) |
| S2 Propagation de blocs | **PASS** | Les 8 observateurs suivent à ≤ 2 blocs en régime établi ; `avgBlockIntervalMs` du dashboard inutilisable < 32 blocs (la fenêtre inclut le genesis à timestamp 0 — quirk dashboard, pas réseau) |
| S3 PEX | **PASS** | Maillage complet : 10-12 pairs par nœud, pas d'auto-pairing |
| S4 Churn (11ᵉ nœud) | **PASS** | Nœud 10 rattrape la hauteur en 25 s, 11 pairs, intégré au maillage |
| S5 Panne du seed | **ÉCHEC** | Voir bug 1 ci-dessous |
| S6 Redémarrage | **PASS** | Redémarrage avec DEBUG : resync complète + reorg propre en < 30 s |
| S7 Partition/reorg | **ÉCHEC partiel** | Voir bug 2 ci-dessous ; la reorg elle-même (quand elle se déclenche) fonctionne parfaitement : ~250 blocs, soldes identiques post-reorg, journaux d'undo corrects |
| S8 Wallet E2E | **PASS** | Envoi 50 PDN via nœud 3, confirmé +50 sur le nœud 8 (autre moitié de l'ancienne partition) |
| S9 Contrat distribué | **PASS** | Déploiement counter via nœud 2, 2 appels, état identique (compteur 3) sur les nœuds 1/5/8 — déterminisme VM à travers l'ancienne frontière |
| S10 Supervision | **PASS partiel** | `degraded` jamais déclenché ; fenêtres de reorg **jamais observées** (reorg de 250 blocs en < 2 s, plus courtes que le pas de sondage 2 s) ; les 2 grands écarts de hauteur (80 et 140) correspondent exactement aux injections de panne S5 et S7 |

### Bug 1 — S5 : blocage permanent d'un nœud après un ban « invalid chain » (résilience)

Symptôme : le nœud 9, synché sur la branche du mineur 0 (tué), reste **figé à h=39 pendant
12 min** avec 7-9 pairs sains à h=67+ (dont son seed 8, exempt de ban), `degraded=null`,
`reorgInProgress=false`. Reprise uniquement par redémarrage.

Enchaînement observé (19:29:31, à la seconde près des deux côtés) :
- le nœud 9 synce depuis le nœud 2 ; le nœud 2 est lui-même en train de reorg (le nœud 8
  l'a reorg depuis le nœud 2 à 19:29:16, au moment de la mort du nœud 0) ;
- le nœud 9 reçoit une chaîne incohérente du nœud 2 en cours de reorg → `PEER_INVALID` ;
- le nœud 8 reçoit simultanément une chaîne incohérente du nœud 9 (lui-même en reorg) →
  pénalité +100 sur le nœud 9 (non banni : seed exempt — `PeerRegistry.penalize`) ;
- **le nœud 9 banni le nœud 2** : `PENALTY_INVALID = 100 = BAN_THRESHOLD` → ban 1 h à la
  première frappe (`RhizomeNode` L103-105, L528) ;
- après le ban, plus **aucune** activité de sync : aucun « Peer unavailable » (DEBUG), aucun
  « Synced from », thread scheduler idle, rounds PEX normaux → la tâche `syncRound`
  (fixe-délai 10 s) ne produit plus rien, sans erreur loguée (le mécanisme exact du gel
  reste non expliqué : les gates du `HeaderSynchronizer` passent pour le seed 8 d'après le
  code et les données mesurées).

Questions ouvertes : servir une chaîne en cours de reorg local ne devrait pas valoir un ban
immédiat (ban à la première frappe) ; un round de sync figé doit être observable.

### Bug 2 — S7 : égalité de travail de base = scission métastable (fork choice)

Symptôme : partition 5+5 (chaque moitié avec son mineur), reprise du maillage par
`/add_peer` — le mesh se ré-uni (12 pairs partout) mais la moitié B **refuse de reorg**
vers la branche A, plus lourde, pendant 6+ min. L'écart de travail reste constant à 64.

Cause (vérifiée par lecture d'en-têtes) : les deux branches ont **exactement le même travail
de base** (mêmes hauteurs, difficulté 6 partout) ; l'unique avantage de A est **1 oncle**
(2 oncles vs 1, +64). Dans `HeaderSynchronizer.headersFirstSync`, la porte
`validated.work() <= localWorkAboveFork(...) → NO_CHANGE` (L136-141) ne compte que le
travail de base (règle M4 anti-inflation) et traite l'égalité comme une défaite — le vote
GHOST phase 3, qui compterait les oncles réels (`engine.totalWork() > localTotal`, L399),
n'est jamais atteint. Le préfiltre L80 (qui compare `peer.totalWork()` — oncles inclus —
au travail de base local) laisse passer le pair, mais la porte le bloque. Un `<=` en
égalité devrait laisser passer vers la phase 3, qui restaurera proprement si le total ne
gagne pas. La scission s'est résorbée uniquement quand une asymétrie de hauteur est apparue
(meurtre du mineur 0 → B prend l'avantage → reorg parfaite de ~250 blocs).

Conséquence : avec un plancher de difficulté et des mineurs synchronisés, l'égalité de
travail de base est auto-entretenue (hauteurs et difficultés bougent ensemble) et la scission
peut durer indéfiniment, silencieusement — ni `degraded` ni `reorgInProgress` ne la signale.

## Corrections appliquées (commits 1-5)

| Fix | Fichiers | Effet |
|---|---|---|
| **1. Round de sync observable** | `RhizomeNode.syncRound`, `NodeService.SyncHealth`, `DashboardApi./stats`, `monitor.sh`/`status.sh` | `/stats` expose `syncRoundsWithoutProgress` (rounds consécutifs sans progrès de sync **ni avance de hauteur** — un nœud nourri par gossip reste à 0) et `syncPeersBanned` ; WARN « sync eclipsed » à la transition + ré-émission, WARN de stall à 6 rounds (~1 min). Le gel d'un nœud se voit en secondes au lieu de 12 min de logs |
| **2. Bans par endpoint + escalade adresse** | `PeerBanList` (clés endpoint/adresse/miroir de nom port-scopés), `PeerRegistry` (cooldowns port-scopés), `RhizomeNode` (`PENALTY_INVALID=34`, seeds exemptés de `isBanned` en sync) | Bannir `localhost:4102` ne bannit plus `:4108` (S5) ; la rotation de ports accumule vers un ban d'adresse au seuil escaladé (3×) ; le miroir DNS reste port-scopé pour ne pas recréer l'éclipse sur hôte partagé |
| **3. 503 pendant un reorg** | `SyncApi` (/sync, /headers), `NodeApi` (/blocks, /block, /block_count, /total_work), `PeerUnavailableException` (déplacée en lib-core, propagée par les deux synchroniseurs) | Un nœud en fenêtre de reorg (chaîne tronquée) répond 503 + Retry-After au lieu de servir une vue incohérente ; le pair appelant lit une panne transport (retry, jamais `PEER_INVALID`) ; javadoc `NodeService.isReorgInProgress` corrigée |
| **4. Départage du travail égal** | `HeaderSynchronizer` (préfiltre strict, porte, phase 3 GHOST), `ChainSynchronizer` (idem, chemin fallback), `HeaderChain` javadoc | Égalité de travail de base → descente en phase 3 si le total pair (oncles inclus) bat le nôtre ; **égalité stricte de base ET de total → départage déterministe par tip hash** (le plus petit gagne) — les camps convergent en un round, sans oscillation, M4 inchangée |
| **5. REORG_TOO_DEEP sans ban** | `RhizomeNode` | Une branche au-delà de l'horizon de finalité n'est pas une malveillance : plus aucun score de ban (DEBUG « past the reorg horizon »), les camps fourchus ne se verrouillent plus mutuellement |

Tests de régression : `PeerBanListTest` (endpoint ≠ adresse, rotation de ports → escalade,
miroir port-scopé, escalade abandonnée si table pleine), `PeerRegistrySecurityTest`
(cooldown par endpoint), `RhizomeNodeTest` (éclipse observable + WARN ; fourche profonde
jamais bannie), `NodeSyncIntegrationTest` (503 pendant reorg → `PeerUnavailable`, pas
`PEER_INVALID`), `HeaderSynchronizerTest` (oncle plus lourd gagne à base égale ; égalité
totale → convergence déterministe des deux côtés + anti-thrash 0 corps téléchargé après
convergence ; chemin legacy identique).

## Rejeu (2026-08-07)

Trois exécutions, binaire corrigé à partir de la 3ᵉ (`installDist` obligatoire après chaque
fix — la 2ᵉ exécution tournait sans le fix 5 et a re-démontré le verrou de ban).

### Exécution 2 (7 h, binaire SANS fix 5) — découverte du défaut 5

Réseau sain 6 h (hauteurs identiques à chaque échantillon 5 min), puis fourche persistante
entre deux camps : {0,7,8} (mineur 0) vs {1,2,3,4,5,6,9} (mineur 1). Les camps minent à
cadence égale → base ET total égaux (les oncles se compensent) → le Fix 4 asymétrique ne
tire jamais. Une fois `hauteur - fork > maxReorgDepth` (120), chaque sync croisée retourne
`REORG_TOO_DEEP` → +25 × 4 = **ban 1 h mutuel, renouvelé à l'heure exacte** (04:57, 05:58,
06:59 dans les logs). Après le kill du mineur 0, le camp sans mineur est gelé (h figée,
`syncPeersBanned=7/9`) et ne peut plus jamais rattraper : le ban verrouille la guérison
naturelle. → Fix 5. Preuves conservées dans `/tmp/opencode/split-evidence/`.

### Exécution 3 (binaire complet) — S5

- Convergence : 10/10 à h≈400 en 3 min, `stallR=0` partout (0 fausse alerte pendant 4,7 h,
  alors que la 1ʳᵉ version du compteur en produisait une par minute sur réseau sain).
- **Réseau resté unifié 4,7 h** (h=5850, difficulté montée à 16, `stall=0`, `banned=0`) —
  les deux exécutions pré-fix se scindaient en 1-2 h.
- Kill -9 du nœud 0 (mineur) : **PASS** — les 9 nœuds restants avancent 5854 → 5893
  (Δ38-39) pendant 201 s, `stallMax=0`, `bannedMax=0` sur tous. Aucun éclipse, aucun ban,
  aucun nœud figé.
- Le départage déterministe a empêché la scission de camps (le scénario qui avait gelé le
  nœud 9 dans les exécutions précédentes).

### Exécution 3 — S7 (tentative, non concluante par l'environnement)

Partition {0-4} vs {5-9} réalisée (kill de 5-9, 3 min de divergence — moitié A à h≈6130),
puis pont par relance. Deux bugs de `start.sh` découverts et corrigés au passage :
`check_ports_free` vérifiait les 10 ports en mode `-n` et la boucle de convergence aussi —
le mode nœud unique est désormais porté sur le seul nœud lancé (nécessaire pour les
redémarrages en partition). La relance de la moitié B a été sabotée par l'environnement de
la campagne (kills de processus par l'outil d'orchestration sur les commandes au premier
plan — les lancements doivent passer par `setsid`) ; le rejeu S7 end-to-end reste à
exécuter dans un environnement stable. La convergence du départage est couverte par les
tests unitaires (`HeaderSynchronizerTest` : camps à totaux égaux → convergence en un round,
un seul côté reorg, anti-thrash vérifié).
