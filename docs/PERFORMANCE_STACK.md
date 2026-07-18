# Choix techniques de la couche infrastructure (performance)

> Analyse des options pour stockage, sérialisation, réseau/RPC et compilation
> native, pour la couche « vivante » de Rhizome (ChainStore persistant, mempool,
> API, sync P2P). Objectif : performance, sans compromettre le déterminisme du
> consensus ni fermer la porte à la compilation native.

---

## 0. Trois principes directeurs

**(A) Séparer trois surfaces de sérialisation.** Elles ont des contraintes
opposées et ne doivent PAS partager la même techno :

| Surface | Contrainte dominante | Peut évoluer ? |
|---|---|---|
| **Consensus** (préimages de hash/signature) | déterminisme absolu, stable *à vie* | **jamais** |
| **Stockage** (ledger, blockstore sur disque) | débit, compacité, migration versionnée | oui, avec migration |
| **Réseau** (messages entre pairs) | débit, compat ascendante entre versions de nœud | oui, négociée |

La surface consensus est **déjà résolue et figée** (`BlockImpl.hash()`,
`TransactionImpl.hashContents()` : layout binaire fait main, big-endian, champs
explicites). **Aucun sérialiseur générique ne doit jamais toucher un préimage** :
un changement de layout entre deux versions de la lib forkerait la chaîne. Ce
document ne parle donc que des surfaces *stockage* et *réseau*.

**(B) Le fil rouge, c'est GraalVM.** Viser la compilation native contraint tous
les autres choix, car native-image interdit (ou complique lourdement) deux
choses : la **génération de bytecode à l'exécution** et le **JNI non déclaré**.
Or le sérialiseur ActiveJ *est* du codegen runtime (c'est lui qui a cassé sur
ASM/Java 21) et RocksDB *est* du JNI. On ne peut pas « décider GraalVM plus
tard » sans risquer de tout réoutiller — c'est une contrainte de conception, pas
une étape finale.

**(C) Savoir où est réellement la performance.** Un nœud à bloc de ~90 s n'est
pas limité par le débit de sérialisation ni par le RPC. Ses deux vrais goulots :
1. **La synchronisation initiale** = valider N blocs = surtout **vérifier des
   signatures Ed25519** et faire des **I/O disque** sur le ledger.
2. **L'empreinte mémoire et le démarrage** des nœuds légers / seeders / CLI.

Conséquence : le budget perf va au **stockage (I/O, batching)** et à la **crypto
(vérification de signatures par lots)**, pas à un sérialiseur exotique ni à un
transport RPC clinquant. Optimiser la sérialisation générique quand le goulot est
la vérif de signature serait un contresens d'ingénierie.

---

## 1. Stockage : RocksDB vs LevelDB (iq80) vs LMDB

**Existant :** `org.iq80.leveldb` (LevelDB réécrit en Java pur), derrière les
abstractions déjà en place (`ChainStore`, `BlockPersistence`, `Ledger`).

| Option | Perf | Native-image | Remarques |
|---|---|---|---|
| **iq80 leveldb** (pur Java) | moyenne ; GC-sensible sur gros volumes | **trivial** (0 JNI) | figé depuis 2019, mono-thread compaction |
| **RocksDB** (`rocksdbjni`) | **élevée** ; column families, write batches, bloom filters, compaction réglable | possible mais **config JNI requise** | JNI natif (~15 Mo), la référence des nœuds de chaîne |
| **LMDB** (`lmdbjava`) | **lecture** excellente (mmap, zéro-copie) ; écritures mono-writer | JNI/`Unsafe`, config native lourde | modèle B-tree mmap, taille de map à préallouer |

**Recommandation : RocksDB pour le ledger et le blockstore, iq80 conservé comme
implémentation de repli pur-Java.** L'abstraction existe déjà — le coût de
migration est une nouvelle classe derrière `ChainStore`/`BlockPersistence`, pas
une refonte. RocksDB apporte exactement les leviers dont un nœud a besoin :
- **column families** : séparer `blocks` / `ledger` / `txindex` / `nonces` dans
  une seule base transactionnelle (au lieu de 4 dossiers LevelDB indépendants,
  d'où venaient les incohérences à l'arrêt chez Pandanite, issue #54) ;
- **`WriteBatch`** : appliquer un bloc (ledger + index + hauteur) de façon
  **atomique** — ce qui sert directement l'exécution transactionnelle du moteur
  et la cohérence au crash ;
- **snapshots** natifs pour les lectures cohérentes pendant la sync.

Garder iq80 comme impl alternative a une vraie valeur : c'est le chemin **100 %
pur-Java** pour une image native sans souci JNI (nœud léger / wallet), et c'est
idéal en test (pas de binaire natif à charger). LMDB : excellent en lecture seule
mmap, mais son modèle mono-writer et la préallocation de map apportent peu face à
RocksDB pour notre charge écriture-lourde de sync. À écarter sauf besoin
spécifique de lectures massives concurrentes.

**Point GraalVM :** RocksDB + native-image fonctionne mais demande d'enregistrer
les classes JNI et d'embarquer la lib native. D'où la stratégie : **profil
« full node » = JVM + RocksDB** (perf max), **profil « léger/CLI » = native +
iq80** (démarrage/empreinte). La même interface sert les deux.

---

## 2. Sérialisation stockage & réseau : manuel vs ActiveJ vs Fory

**Existant :** DTO ActiveJ (`@Serialize`, `SerializerFactory`) avec un cache de
sérialiseurs générés par codegen. C'est rapide **mais** : (a) codegen runtime →
hostile à native-image, (b) c'est la source du bug ASM/Java 21 déjà rencontré,
(c) le layout dépend de la lib, donc **inutilisable pour le consensus**.

| Option | Débit | Native-image | Déterminisme | Verdict |
|---|---|---|---|---|
| **Manuel à layout fixe** (ByteBuffer) | très bon | **parfait** | **total** (on contrôle chaque octet) | ✅ objets cœur |
| **ActiveJ serializer** | excellent | codegen runtime → **problématique** (mode interprété possible mais lent) | dépend de la lib | ⚠️ à sortir du chemin cœur |
| **Apache Fory** (ex-Fury) | **excellent** (zéro-copie, JIT), **mode AOT/GraalVM officiel** | **oui** (codegen build-time) | non garanti entre versions | ✅ messages réseau riches |

**Recommandation :**
- **Objets cœur** (en-tête de bloc, transaction) : **sérialisation manuelle à
  layout fixe**. Ils sont petits, à champs fixes, déjà décrits par des tailles
  constantes (`BlockDto.BUFFER_SIZE`, `TransactionDto.BUFFER_SIZE`). Un
  `ByteBuffer` big-endian écrit à la main est **plus rapide** que n'importe quel
  sérialiseur générique sur ces structures (pas de réflexion, pas de schéma),
  **déterministe**, **sans dépendance codegen** donc natif-compatible. Ça aligne
  en plus le format stockage/réseau sur le format déjà utilisé pour les hashes.
  → concrètement : remplacer les DTO ActiveJ par des `writeTo(ByteBuffer)` /
  `readFrom(ByteBuffer)` sur les types cœur. Bonus : ça **retire ActiveJ
  serializer du chemin critique** et supprime le hack ASM 9.7.
- **Messages réseau composites/évolutifs** (handshake, annonces, réponses batch)
  où on veut de la souplesse de schéma : **Apache Fory** si le besoin se
  matérialise, précisément parce qu'il a un **mode AOT compatible GraalVM** (là
  où ActiveJ serializer force le mode interprété en natif). Tant que les messages
  restent simples, un cadrage binaire manuel suffit et évite une dépendance.

En clair : **on n'a probablement pas besoin d'un « sérialiseur performant »** ;
on a besoin d'un **format binaire fixe fait main** pour le cœur (le plus rapide
et le seul sûr pour le consensus), et Fory seulement si des messages réseau
riches apparaissent.

---

## 3. Réseau / RPC : HTTP vs ActiveJ RPC vs ZeroMQ vs Netty

**Existant :** ActiveJ HTTP (fonctionnel, l'API « hello » tourne) ; les forks
ActiveJ **RPC** ont été **supprimés** (non fonctionnels, 100 % de stubs).

Deux besoins distincts :
- **API publique / RPC client** (wallet, explorateur, mineur → nœud) : requête/
  réponse, cache-friendly, observable. **→ HTTP**, sans hésiter. ActiveJ HTTP est
  déjà là, GraalVM le supporte, c'est ce que tout l'outillage attend, et c'est ce
  que faisait Pandanite (donc outils réutilisables). Pas de RPC binaire ici.
- **P2P entre nœuds** (gossip de blocs/tx, sync d'en-têtes puis de blocs) :
  débit et latence comptent.

| Option P2P | Perf | Native | Adéquation gossip P2P | Remarque |
|---|---|---|---|---|
| **HTTP** (comme Pandanite) | correcte | ✅ | moyenne | simple, déjà là ; la sync parallèle marche très bien en HTTP |
| **ActiveJ RPC** | élevée | codegen → **hostile** | bonne | dépend du serializer codegen (cf. §2) ; forks déjà retirés |
| **ZeroMQ** (`jeromq` pur Java) | élevée | ✅ (pur Java) | patterns broker/pubsub **inadaptés** au gossip P2P authentifié | ajoute une lib + un modèle mental ; peu de gain vs TCP framé |
| **Netty** (TCP framé maison) | **élevée** | ✅ **excellent** (métadonnées GraalVM fournies) | **bonne** (contrôle total du protocole) | mature, base de gRPC, la référence réseau JVM |

**Recommandation :**
- **API/RPC client : HTTP (ActiveJ HTTP existant).** Aucune raison d'un transport
  binaire pour du requête/réponse à faible fréquence ; on gagne l'écosystème
  d'outils et la compat native gratuitement.
- **P2P : commencer en HTTP** (parité Pandanite, sync parallèle des en-têtes puis
  des blocs — c'est là qu'est le vrai gain, pas dans le protocole de transport),
  puis **basculer la couche gossip sur un cadrage binaire TCP via Netty** si le
  profilage le justifie. **Éviter ActiveJ RPC** (le codegen le rend hostile au
  natif et les forts ont déjà été retirés) et **éviter ZeroMQ** (ses patterns ne
  correspondent pas à un gossip authentifié pair-à-pair ; il faudrait de toute
  façon reconstruire l'authentification et le contrôle de flux par-dessus).

Le point de perf réseau qui compte n'est pas le transport mais la **stratégie de
sync** : téléchargement **parallèle et pipeliné** des en-têtes puis des blocs,
avec cache et reprise — c'est ce qui a fait passer la sync Pandanite de 20 h à
gérable (PR #83/#91/#109). À protocole égal, une bonne stratégie de sync bat un
transport plus rapide.

---

## 4. Compilation native GraalVM

**Existant :** devcontainer avec GraalVM disponible ; rien n'est encore compilé
en natif.

**Gains** : démarrage en millisecondes (vs secondes JVM), empreinte mémoire
divisée par ~3-5, binaire autonome sans JRE. **Très pertinent** pour : la **CLI
wallet**, le **dnsseeder**, un **nœud léger**. Moins critique pour un **full node**
qui tourne en continu (le démarrage compte peu, et le JIT peut au contraire mieux
optimiser le hot path de validation sur la durée).

**Coûts / contraintes induites** (d'où le fil rouge) :
- **pas de codegen runtime** → écarte ActiveJ serializer du chemin cœur (§2) ;
- **JNI à déclarer** → RocksDB nécessite une config native (d'où le profil
  léger sur iq80 pur-Java, §1) ;
- **réflexion à enregistrer** → lombok (compile-time, OK), org.json et ActiveJ
  inject (réflexion, à cadrer ou remplacer par de l'injection statique) ;
- Pufferfish2, lui, est **pur Java sur BouncyCastle** (pas de codegen, pas de
  JNI) → **nativement compatible sans effort**. Bon signe : le composant
  consensus-critique le plus lourd n'oppose aucune résistance à GraalVM.

**Recommandation : adopter GraalVM comme contrainte *dès maintenant*, la valider
tôt sur une cible simple.** Concrètement :
1. Faire de la **CLI wallet le banc d'essai natif** (petite, pas de RocksDB, pas
   d'ActiveJ) : elle prouve la chaîne de build native et cadre les règles
   (réflexion, ressources) avant que la surface ne grossisse.
2. Établir **deux profils de build** dès la conception :
   - **full node** : JVM + RocksDB + HTTP (perf max, démarrage secondaire) ;
   - **léger / CLI / seeder** : native + iq80 pur-Java (démarrage/empreinte).
3. **Bannir dès aujourd'hui** le codegen runtime du chemin cœur (donc §2 :
   sérialisation manuelle) et préférer l'**injection statique** à la réflexion là
   où ActiveJ inject l'autoriserait — sinon on paiera la dette au moment du
   passage natif.

---

## 5. Synthèse : décisions recommandées

| Composant | Recommandation | Raison courte |
|---|---|---|
| Sérialisation **consensus** | Format manuel figé (**déjà fait**) | déterminisme à vie, jamais un sérialiseur générique |
| Sérialisation **cœur** (bloc/tx, stockage+wire) | **Manuel `ByteBuffer` fixe** ; retirer ActiveJ serializer | le plus rapide sur champs fixes, natif-compatible, aligne sur les préimages |
| Sérialisation **messages réseau riches** | **Apache Fory** *si le besoin apparaît* | mode AOT GraalVM, sinon cadrage binaire manuel |
| **Stockage** full node | **RocksDB** (column families + WriteBatch atomique) | perf + application de bloc atomique + cohérence crash |
| **Stockage** léger/tests | **iq80 leveldb** (conservé) | pur Java, natif sans JNI, idéal tests |
| **API / RPC client** | **HTTP** (ActiveJ HTTP existant) | écosystème, natif-ok, parité Pandanite |
| **P2P** | **HTTP d'abord**, **Netty (TCP framé) ensuite** si profilage ; pas d'ActiveJ RPC, pas de ZeroMQ | éviter codegen (RPC) et patterns inadaptés (ZMQ) ; le gain est dans la *stratégie* de sync |
| **Native** | GraalVM comme contrainte **dès maintenant** ; 2 profils (JVM full / native léger) ; wallet CLI en banc d'essai | valider tôt, éviter la dette codegen/réflexion/JNI |
| **Vrai budget perf** | **I/O disque (RocksDB, batch)** + **vérif signatures Ed25519 par lots** | ce sont les goulots réels de la sync, pas le transport |

### Tension centrale à retenir
On peut avoir **perf maximale** (RocksDB + ActiveJ codegen) **ou** **native
partout** (iq80 + tout sans codegen), pas les deux dans le même binaire. La sortie
est de **découpler par profil de build derrière les interfaces déjà en place**
(`ChainStore`, `BlockPersistence`, `Ledger`) — ce qui est peu coûteux *parce que*
ces abstractions existent déjà, et impossible à rattraper à moindres frais si on
laisse le codegen s'installer sur le chemin cœur. D'où la première action, non
perf mais structurante : **remplacer la sérialisation cœur ActiveJ par un format
manuel**, ce qui débloque à la fois le déterminisme, la vitesse et le natif.
