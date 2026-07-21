# Rhizome — Audit de sécurité & exploitation blockchain

**Date :** 2026-07-21
**Périmètre :** intégralité du dépôt `censseo/rhizome` (consensus/PoW, transactions/mempool/signatures,
réseau P2P, VM de contrats/gas, sérialisation/état-Merkle/snapshots, API node/dashboard, wallet/keystore,
persistance, dépendances).
**Méthode :** revue de code manuelle des 194 fichiers source de production, traçage des chemins depuis
l'entrée réseau (HTTP `/submit`, `/add_transaction`, `/add_peer`, `/sync`, `/headers`) jusqu'aux mutations
de ledger/état, et vérification adverse des chemins d'acceptation de blocs, de récompense, et d'exécution
de contrats. Les deux findings critiques ont été reconfirmés directement dans le code.

> **État général.** Le code a déjà subi plusieurs passes de durcissement (audits antérieurs C1–C3, H1–H7,
> L2, L4 ; commentaires `audit M5/M8/M11` dans les décodeurs). Les invariants centraux — liaison
> signature↔émetteur, anti-rejeu (chainId + nonces séquentiels + hash de contenu sans signature),
> arithmétique de ledger vérifiée (`Math.addExact`), bornage des entrées réseau, intégrité du snapshot-sync,
> séparation de domaine du Sparse Merkle Tree — **tiennent**. Les vulnérabilités restantes se concentrent sur
> le **sous-système oncle/GHOST** (frappe de monnaie sans preuve de travail), le **déterminisme de la VM**
> (fork de consensus), et une **dépendance cryptographique vulnérable**.

---

## Résumé exécutif

| # | Sévérité | Titre | Zone | Impact |
|---|----------|-------|------|--------|
| **C1** | 🔴 Critique | Inflation de récompense via oncles sous-difficulté (jusqu'à difficulté 0, zéro travail) | Consensus / GHOST | Frappe de monnaie illimitée |
| **C2** | 🔴 Critique | Fork de consensus par auto-récursion WASM profonde (StackOverflow dépendant de la JVM) | VM contrats | Split de chaîne |
| **H1** | 🟠 Élevée | BouncyCastle 1.76 — CVE-2024-30172 : boucle infinie Ed25519 sur signature forgée | Dépendance / crypto | DoS réseau distant |
| **H2** | 🟠 Élevée | Work-gate headers-first contournable via travail d'oncle non prouvé | Consensus / sync | DoS d'amplification |
| **H3** | 🟠 Élevée | DoS distant par résolution DNS bloquante sur l'event-loop (`/add_peer`) | P2P / API | Gel total du nœud |
| **H4** | 🟠 Élevée | Parse WASM + re-scan float non tarifés par appel (DoS CPU) | VM contrats | Amplification CPU |
| **H5** | 🟠 Élevée | Clé privée du wallet écrite en clair, permissions world-readable | Wallet | Vol de fonds |
| **M1** | 🟡 Moyenne | SSRF par DNS-rebinding dans `PeerBroadcaster` (broadcast non épinglé) | P2P | SSRF interne |
| **M2** | 🟡 Moyenne | Amplification de travail non authentifiée (`/transaction`, `/address_txs`, `/call_readonly`) | API | DoS |
| **M3** | 🟡 Moyenne | Rejet float incomplet — les opcodes SIMD flottants contournent le garde-déterminisme | VM contrats | Fork (latent) |
| **M4** | 🟡 Moyenne | NPE dans la primitive de signature sur clé publique nulle/vide | Crypto | Fragilité |
| **M5** | 🟡 Moyenne | `MerkleTree` sans séparation de domaine feuille/nœud (seconde-préimage) | Merkle | Faux proof SPV (latent) |
| **L1–L15** | ⚪ Faible | 15 findings de robustesse (détaillés plus bas) | Divers | Limité |
| **I1–I5** | ℹ️ Info | Infra & dette (pas de CI, ActiveJ beta, couche message non implémentée…) | Divers | — |

**Priorité absolue : C1, C2, H1** — les trois seuls écarts pouvant provoquer une frappe de monnaie, un
split de chaîne ou un DoS réseau distant sur le réseau en production.

---

## Findings critiques

### 🔴 C1 — Inflation de récompense via oncles sous-difficulté (jusqu'à travail nul)

**Localisation :**
- `lib-core/.../blockchain/ChainEngine.java:1128-1143` — `uncleEligible` : aucun contrôle de plancher de difficulté
- `lib-core/.../blockchain/ChainEngine.java:1033-1070` — `validateUncles` : la difficulté committée n'est vérifiée que contre elle-même
- `lib-core/.../blockchain/ChainEngine.java:1013-1022` — `registerOrphan` : accepte tout bloc passant `verifyNonce`
- `lib-core/.../common/Crypto.java:171-185` — `checkLeadingZeroBits(hash, 0)` renvoie `true` **inconditionnellement**
- `lib-core/.../blockchain/Executor.java:320-338` — `payUncleRewards` : récompense **forfaitaire**
- `lib-core/.../blockchain/NetworkParameters.java:184-186` — `uncleReward = miningReward/2`
- Atteignable via `app-node/.../NodeService.java` (`submitBlock` → `registerOrphan`)

**Description.** Un oncle inclus paie à son mineur une récompense **forfaitaire** de `miningReward/2` (+ un bonus
de neveu `miningReward/32`), mais **rien n'exige que la difficulté de l'oncle atteigne, ni même approche, la
difficulté canonique de la chaîne**. `uncleEligible` ne vérifie que `verifyNonce` à la difficulté
*auto-déclarée* de l'oncle ; `validateUncles` ne vérifie que l'égalité `ref.difficulty() == uncle.difficulty()`
— c'est-à-dire le nombre de l'attaquant contre lui-même, pas contre le consensus.

Pire : la difficulté `0` passe de bout en bout. `checkLeadingZeroBits(hash, 0)` calcule `bytes = 0`
(la boucle ne s'exécute pas), `remainingBits = 0`, et **renvoie `true` sans examiner un seul bit du hash**.
Un « bloc » de difficulté 0 satisfait donc `verifyNonce` avec **zéro travail**, et `registerOrphan` le place
dans le pool d'oncles gratuitement — contredisant directement son propre commentaire (« Only blocks with valid
proof of work are retained ») et l'invariant du whitepaper (`NetworkParameters.java:115-118`, « no reward is
ever minted without matching work »).

**Scénario d'exploit** (opérationnel dès le bloc 2, atteignable par le réseau) :
1. L'attaquant est mineur (il produit quelques blocs de chaîne principale — seul prérequis).
2. Il fabrique un faux bloc frère `O` : `id = tipHeight`, `lastBlockHash = header[tipHeight-1].hash()`
   (public), `difficulty = 0`, coinbase payant sa propre adresse. **Aucun PoW effectué.**
3. Il envoie `O` à n'importe quel nœud. `addBlock` le rejette (`INVALID_DIFFICULTY`), puis
   `NodeService.submitBlock` appelle `engine.registerOrphan(O)` ; `verifyNonce` réussit trivialement → `O`
   est mis en pool **sur chaque nœud**.
4. Quand l'attaquant mine un vrai bloc-neveu à `tipHeight+1`, il y attache `O` (et un second faux oncle).
   `validateUncles` passe tous les contrôles.
5. `payUncleRewards` frappe `miningReward/2` (mineur d'oncle) + `miningReward/32` (bonus neveu) par oncle —
   **2 oncles ≈ +106 % d'émission par bloc, pour zéro travail supplémentaire.**

Chaque mineur peut le faire à chaque bloc, doublant à peu près le calendrier d'émission et brisant l'invariant
de masse monétaire que toute la conception économique (et le contrôle coinbase `Executor.java:222`) est censée
garantir.

**Correctif.** Dans `uncleEligible`/`validateUncles`, exiger que la difficulté de chaque oncle **égale la
difficulté canonique de la chaîne à la hauteur de l'oncle** (recalculée via `computeDifficultyFromChain`), ou
au minimum `uncle.difficulty() >= params.minDifficulty()` **et** dans un delta borné de la difficulté
contemporaine. Rendre la récompense d'oncle **proportionnelle au travail réellement prouvé** plutôt que
forfaitaire. Faire rejeter par `registerOrphan` tout bloc sous `minDifficulty` pour que le pool ne puisse
être ensemencé de blocs à travail nul. (Corollaire recommandé : faire renvoyer `false` par
`checkLeadingZeroBits` quand `challengeSize <= 0`, en défense en profondeur.)

---

### 🔴 C2 — Fork de consensus par auto-récursion WASM profonde

**Localisation :** `lib-vm/.../vm/WasmVm.java:57-102` (bloc `execute`, capture SO/OOM lignes 90-95) ;
absence de limite déterministe de profondeur d'appel WASM. `WasmContractProcessor.java:99`
(`MAX_CALL_DEPTH = 8`) ne borne que le host-function `call_contract` (inter-contrats), **pas** l'opcode
`CALL` intra-contrat.

**Description.** Les fonctions WASM d'un contrat peuvent s'auto-récurser sans borne. Dans le
`InterpreterMachine` de Chicory 1.7.5, chaque frame d'appel WASM est une récursion sur la **pile Java** :
`call()` invoque `eval()`, qui invoque `call()` pour l'opcode `CALL`. Une récursion profonde déborde donc
la **pile du thread JVM**. Chicory capture le `StackOverflowError` en interne et **le ré-emballe en
`ChicoryException("call stack exhausted")`**.

Conséquence dans `WasmVm.execute` :
- Le `catch (StackOverflowError | OutOfMemoryError)` (lignes 90-95), qui vise précisément à normaliser ce cas,
  **ne se déclenche jamais** pour la récursion WASM, car Chicory a déjà converti l'erreur en
  `ChicoryException`. L'exécution tombe dans le `catch (Throwable)` générique (lignes 96-101) →
  `ExecResult.reverted(gas.used(), "call stack exhausted")`, avec un `gas.used()` **local au nœud**.
- La profondeur à laquelle la pile Java déborde dépend de `-Xss`, de la version JVM et de l'état JIT —
  **non déterministe entre nœuds**.

Deux forks distincts en résultent :
1. **Fork frais/état sur revert** : le nœud A (petite pile) déborde à la profondeur `D_A` avec
   `gas.used() = G_A` ; le nœud B (grande pile) à `D_B` avec `G_B ≠ G_A`. Les deux revert, mais le frais
   `gasUsed * gasPrice` diffère → soldes mineur/émetteur différents → racine de ledger différente → **fork**.
2. **Fork succès/échec** : si la profondeur voulue par le contrat se situe entre le seuil d'un nœud à petite
   pile et celui d'un nœud à grande pile, le nœud à grande pile **réussit et committe état/sortie** tandis
   que l'autre revert. États totalement différents → **fork**.

**Scénario d'exploit.** Déployer un contrat dont l'export `call` invoque une fonction auto-récursive
(`local.get; call $self; …`, ~2-3 gas/niveau). L'appeler avec un `gasLimit` généreux. Les frames lourdes de
l'interpréteur débordent une pile de thread typique après quelques milliers de niveaux — bien en dessous de
ce que le budget gas finance — donc le SO gagne toujours la course. Sur revert, l'émetteur ne paie que
`gas.used()` (les quelques milliers d'instructions bon marché) : l'attaque est **quasi gratuite**. Une seule
transaction de ce type incluse dans un bloc scinde le réseau selon les lignes `-Xss`.

**Correctif.** Imposer une **limite déterministe de profondeur d'appel WASM indépendante de la pile JVM** :
maintenir un compteur de profondeur dans le listener de metering (incrément sur `CALL`/`CALL_INDIRECT`/
`RETURN_CALL*`, décrément au retour), et lever `OutOfGasException` à un maximum fixe, atteint identiquement
sur chaque nœud, choisi bien en dessous de la plus petite pile de thread supportée. Traiter explicitement la
`ChicoryException("call stack exhausted")` comme un out-of-gas plein-gas normalisé. Exécuter la VM sur un
thread à `-Xss` fixe et documenté. **Note : corriger le seul `catch` ne suffit pas** — un nœud à grande pile
réussirait encore là où un petit revert (fork #2) ; la limite de profondeur déterministe est le correctif
requis.

---

## Findings élevés

### 🟠 H1 — BouncyCastle 1.76 : CVE-2024-30172 (boucle infinie Ed25519)

**Localisation :** `lib-core/build.gradle` (`org.bouncycastle:bcprov-jdk18on:1.76`) ;
`lib-core/.../common/Crypto.java:47-52` (`checkSignature` → `Ed25519Signer.verifySignature`).

**Description.** La chaîne signe et vérifie **toutes** les transactions avec Ed25519 de BouncyCastle. La
version 1.76 est antérieure au correctif de **CVE-2024-30172** : une **boucle infinie** dans le code de
vérification Ed25519 déclenchable via une signature et une clé publique forgées (corrigé en 1.78). Toute
vérification de signature — sur le chemin d'admission mempool `/add_transaction` et sur le chemin de consensus
`Executor` — invoque `Ed25519Signer.verifySignature`.

**Scénario d'exploit.** L'attaquant soumet une transaction dont la signature/clé publique déclenche le cas de
boucle infinie. Le thread qui vérifie tourne indéfiniment à 100 % CPU. Comme le nœud tourne sur un unique
event-loop ActiveJ, cela gèle l'ensemble du front-end ; propagée par gossip, la transaction empoisonnée frappe
chaque nœud qui tente de la valider → **DoS réseau distant, une seule transaction**.

**Correctif.** Monter `bcprov-jdk18on` à **≥ 1.78** (idéalement la dernière 1.x). Vérifier aussi les autres
CVE couvertes entre 1.76 et la version cible. Ajouter un scan de dépendances en CI (voir I1).

### 🟠 H2 — Work-gate headers-first contournable via travail d'oncle non prouvé

**Localisation :** `lib-core/.../blockchain/HeaderChain.java:169-193` (`uncleWork` crédite `2^uncleDifficulty`
sans vérifier le PoW de l'oncle ; borné seulement par `maxDifficulty = 255`), `:112` (travail d'oncle fictif
plié dans le travail cumulé) ; `HeaderSynchronizer.java:44,75-82` (le gate ainsi défait).

**Description.** Le design headers-first repose sur la garantie : « un pair qui *prétend* un énorme travail ne
coûte qu'un téléchargement d'en-têtes borné, car la validation d'en-tête prouve le travail ». Mais `uncleWork`
ajoute `2^ref.difficulty()` par oncle committé **sans vérifier que l'oncle a le moindre PoW** (impossible :
seul le hash de l'oncle est dans l'en-tête). L'invariant affirmé (Javadoc `HeaderChain.java:27-29`) est donc
faux.

**Scénario d'exploit.** L'attaquant forke à faible profondeur, mine une courte série d'en-têtes à
`minDifficulty` (bon marché, timestamps contrôlés). Chaque en-tête référence 2 oncles à `difficulty = 255`.
`HeaderChain.validate` crédite `2 × 2^255` de travail par en-tête alors que l'attaquant n'a payé que `~2^16`.
Le pair annonce un `totalWork` gigantesque, passe le gate bon marché, et force la victime à faire un pop
jusqu'à `maxReorgDepth` (120) puis à télécharger/exécuter des corps — exactement le travail coûteux que le
gate d'en-têtes existe pour éviter, répétable par pair malveillant et par round. (La chaîne n'est pas corrompue
durablement — les corps sont revalidés — d'où Élevée et non Critique.)

**Correctif.** Ne pas créditer le travail d'oncle au stade de validation d'en-tête, ou borner chaque
contribution d'oncle à au plus `2^difficulté` du neveu. Corriger la règle de difficulté d'oncle de C1 et la
refléter ici (rejeter `uncleDifficulty` hors `[minDifficulty, chainDifficulty+δ]`) ferme les deux.

### 🟠 H3 — DoS distant par DNS bloquant sur l'event-loop (`/add_peer`)

**Localisation :** `app-node/.../NodeApi.java:115-119` (`/add_peer`) → `NodeService.java:248-250` →
`PeerRegistry.java:102,109` → `PeerHosts.java:31,72` / `PeerBanList.java:64` (`InetAddress.getByName`).

**Description.** Le nœud tourne sur un unique thread event-loop ActiveJ. Le continuation `.map` de `/add_peer`
s'exécute **sur ce thread** et effectue une **résolution DNS synchrone bloquante** de l'hôte fourni par
l'attaquant (`isBanned` → `getByName`, `subnetKey` → `getByName`, plus `isPubliclyRoutable` → `getAllByName`
sur mainnet). Contrairement à `PeerDiscovery` (dont le DNS est déporté sur l'exécuteur `rhizome-net`), ce
chemin d'admission ne l'est pas.

**Scénario d'exploit.** L'attaquant enregistre `slow.attacker.tld` dont le serveur de noms *drop* silencieux
les requêtes (chaque résolution bloque ~5 s). Il envoie `POST /add_peer {"url":"http://slow.attacker.tld:3000"}`
(client non-navigateur → pas d'`Origin`, le garde CSRF passe). Le thread event-loop bloque dans `getByName` :
pendant ce temps le nœud ne sert **aucune** requête (`/sync`, `/submit`, gossip, SSE). En pipelinant un flux
de tels hôtes, la pile event-loop reste figée indéfiniment → perte totale de disponibilité.

**Correctif.** Ne jamais résoudre le DNS sur l'event-loop : déporter `node.addPeer` sur l'exécuteur
`rhizome-net`, ou stocker l'URL brute et différer toute résolution `InetAddress` aux rounds de sync/discovery
hors-loop, ou utiliser `AsyncDnsClient` avec timeout strict.

### 🟠 H4 — Parse WASM + re-scan float non tarifés par appel (DoS CPU)

**Localisation :** `lib-vm/.../vm/WasmVm.java:64-71` (`Parser.parse` + `rejectFloatingPoint` à chaque appel) ;
`WasmContractProcessor.java:88-96` (deploy ne borne pas la taille du code).

**Description.** Chaque `CALL` re-parse le module complet et re-scanne **chaque instruction** pour les opcodes
flottants (allouant une `String` par opcode), en O(taille du code), **avant que le gas ne couvre quoi que ce
soit**. Le module n'est **pas mis en cache** entre appels, et il n'y a **aucune taille max de code** : le
deploy ne borne le code que par le gas une seule fois.

**Scénario d'exploit.** Déployer un gros module (fonctions/octets inaccessibles, multi-Mo abordable via le gas
de deploy unique). Spammer des `CALL` dont l'export `call` retourne quasi immédiatement (gas d'exécution
proche de zéro). Chaque appel force un parse multi-Mo + scan complet sur chaque nœud validant, non tarifé →
forte amplification, y compris sur le chemin RPC `dryRun`.

**Correctif.** Cap dur `MAX_CODE_SIZE` au deploy ; facturer un coût gas proportionnel à la taille du code
avant exécution (modèle EVM) ; mettre en cache les `WasmModule` parsés/validés par adresse de contrat.

### 🟠 H5 — Clé privée du wallet en clair, permissions world-readable

**Localisation :** `app-wallet/.../wallet/Wallet.java:74-79` (écriture), `54-59` (JSON en clair),
`62-77` (chiffrement opt-in seulement) ; `WalletKeystore.java`.

**Description.** `save()` fait `Files.writeString(keyFile, …)` qui crée le fichier selon l'umask du process
(0022 → mode **0644**, lisible par tous). Aucun `PosixFilePermissions`/`0600` nulle part. Le chiffrement
(PBKDF2 + AES-256-GCM) n'est activé que si `RHIZOME_WALLET_PASSPHRASE` est défini ; par défaut le fichier
contient `"privateKey"` en clair. `keygen` n'affiche que l'adresse, sans indice que le fichier est sensible.

**Scénario d'exploit.** Sur tout hôte multi-utilisateurs/conteneur partagé, un utilisateur local (ou un
service compromis peu privilégié) lit `~/wallet.json` et obtient le seed Ed25519 → **vol irréversible** de
tous les fonds. Le fichier fuite aussi dans les backups, répertoires synchronisés et couches d'image.

**Correctif.** Créer le fichier atomiquement en owner-only : `Files.createFile(path,
PosixFilePermissions.asFileAttribute(EnumSet.of(OWNER_READ, OWNER_WRITE)))` (ou temp `0600` + `ATOMIC_MOVE`).
Envisager de rendre le chiffrement par défaut (refuser le clair sans `--insecure` explicite).

---

## Findings moyens

### 🟡 M1 — SSRF par DNS-rebinding dans `PeerBroadcaster`
`app-node/.../PeerBroadcaster.java:93-110`. À la différence de `HttpPeerSource` et `PeerDiscovery` (qui
épinglent l'IP via `PeerHosts.pin`), le broadcast fait `URI.create(peer + path)` et laisse le JDK `HttpClient`
re-résoudre l'hôte à l'envoi, **sans re-contrôle de routabilité**. Un pair admis avec une IP publique puis
re-pointé (DNS court TTL) vers `127.0.0.1`/`169.254.169.254`/RFC1918 reçoit alors des `POST /submit` et
`/add_transaction` du nœud → SSRF aveugle vers l'infra interne. **Correctif :** router les envois de broadcast
par `PeerHosts.pin(peer, blockPrivateHosts)` comme les autres chemins.

### 🟡 M2 — Amplification de travail non authentifiée (scans & dry-run)
`app-node/.../NodeApi.java:304-352` (scans), `500-530` (dry-run). `?depth=` jusqu'à `SCAN_DEPTH_MAX = 2000` :
sur un txid/adresse inexistant, lecture + désérialisation de **chaque** bloc de la fenêtre (jusqu'à 2000 blocs
de 4 MiB). Les caps de résultat bornent la *réponse*, pas le *travail*. À 1000 req/s/IP, ~2M lectures de blocs
par seconde depuis une seule IP. `/call_readonly` exécute la VM WASM gratuitement jusqu'à `MAX_READONLY_GAS =
50M` gas/appel. **Correctif :** pondérer le coût des scans dans le rate-limiter (par `depth`), abaisser
`SCAN_DEPTH_MAX`, budget gas par fenêtre pour `/call_readonly`.

### 🟡 M3 — Rejet float incomplet : les opcodes SIMD flottants passent le garde
`lib-vm/.../vm/WasmVm.java:152-166`. `rejectFloatingPoint` ne rejette que les opcodes préfixés `"F32_"`/
`"F64_"`. Les opcodes SIMD flottants de Chicory se nomment `F32x4_ADD`, `F64x2_MUL`, `F32x4_SQRT`… :
`"F32x4_ADD".startsWith("F32_")` est **false**, donc toute l'arithmétique vectorielle flottante — précisément
les ops à NaN/arrondi implémentation-défini que le garde vise à exclure — passe. Non exploitable *aujourd'hui*
(l'interpréteur Chicory 1.7.5 laisse ces opcodes non implémentés → `ChicoryException` → revert déterministe),
mais devient un split de consensus dès que Chicory implémente le SIMD, passe au compilateur AOT, ou que des
nœuds mélangent les builds. **Correctif :** passer d'une denylist à une **allowlist** d'opcodes, ou rejeter
explicitement tout `V128_*`/SIMD au chargement.

### 🟡 M4 — NPE dans la primitive de signature sur clé nulle/vide
`lib-core/.../common/Crypto.java:47-52`. `PublicKey.of` mappe une clé 32×0x00 vers `PublicKey.empty()`, dont
`.get()` renvoie `null` ; `checkSignature` fait `signer.init(false, null)` → **NPE** dans BouncyCastle, au
lieu de renvoyer `false`. Atteignable via un `TransactionDto` binaire à `signingKey = 32×0x00` (from vide ==
from vide, montant/frais 0). Contenu aujourd'hui par le `guardedResponse` (→ HTTP 400), donc pas de crash
live, mais une primitive de sécurité qui lève une exception au lieu de « signature invalide » est fragile sous
tout refactor futur. **Correctif :** renvoyer `false` quand `publicKey == null || publicKey.get() == null`, et
faire renvoyer `false` à `signatureValid()` quand la clé est absente.

### 🟡 M5 — `MerkleTree` sans séparation de domaine feuille/nœud (seconde-préimage)
`lib-core/.../merkletree/MerkleTree.java:44`. Les feuilles sont des hash de transaction bruts, les nœuds
internes `SHA-256(a‖b)` **sans octet de préfixe** : feuilles et nœuds partagent un domaine de hash — condition
exacte d'une attaque de seconde-préimage Merkle. Le `SparseMerkleTree` du projet, lui, sépare correctement
(`0x00`/`0x01`). Latent : `getMerkleProof` n'a aucun appelant HTTP et la validation de bloc recalcule la
racine entière ; **exploitable dès l'ajout d'un endpoint de preuve d'inclusion** (un client SPV pourrait se
voir servir une preuve forgée où un nœud interne est présenté comme une transaction-feuille). **Correctif :**
préfixer feuilles et nœuds internes avec des tags de domaine distincts, comme le SMT.

---

## Findings faibles

| # | Titre & localisation | Note |
|---|----------------------|------|
| **L1** | **Évasion de ban par TOCTOU DNS** — `PeerBanList.java:56-73`. Le ban est clé par IP re-résolue à l'écriture et au contrôle ; un attaquant à DNS court TTL fait résoudre vers IP-A au ban puis IP-B à la ré-admission. Impact borné (évasion IP inhérente au Sybil). | Clé le ban par URL/hôte soumis + IP épinglée. |
| **L2** | **Filtre SSRF désactivé hors mainnet** — `RhizomeNode.java:122-124`. `blockPrivatePeers` n'est vrai que si `networkName` contient `"mainnet"` ; testnets exposés → SSRF interne via `/add_peer`. | Garder le filtre par défaut, opt-out explicite `RHIZOME_ALLOW_PRIVATE_PEERS`. |
| **L3** | **Divulgation via messages d'exception reflétés** — `NodeApi.java:868-874`. `guardedResponse` renvoie `e.getClass().getSimpleName() + ": " + e.getMessage()` dans le corps 400. | Message générique côté client, détail loggé serveur uniquement. |
| **L4** | **Force KDF / secret par env** — `WalletKeystore.java:29-34`. PBKDF2-HMAC-SHA256 200k itérations (modeste en 2026) ; passphrase via `RHIZOME_WALLET_PASSPHRASE` (lisible `/proc/pid/environ`, fuit dans l'historique shell/CI). | scrypt/argon2id ; lecture depuis fichier/prompt. |
| **L5** | **Pas de plancher de frais** — `MemPool.java:110-171`. `fee == 0` admis ; `ExecutionStatus.TRANSACTION_FEE_TOO_LOW` défini mais **jamais référencé**. Anti-spam faible (borné par solde + `maxPerSender`). | Câbler un `params.minFee()`. |
| **L6** | **Codec JSON tronque `amount`/`fee` 64-bit en `int`** — `Transaction.java:227,241,246` (`getInt`) vs `TransactionDto` (`long`). Une tx > `Integer.MAX_VALUE` ne round-trip pas via JSON. Pas de forgerie (préimage signée = `long` mémoire), mais incohérence de double-encodage. | `getLong(AMOUNT)`/`getLong(FEE)`. |
| **L7** | **`MerkleTree` non canonique (style CVE-2012-2459)** — `MerkleTree.java:37-52`. Le padding duplique la **première** feuille et la file unique mélange les niveaux : `[a,b,c]` et `[a,b,c,a]` collisionnent sur la racine. Atténué (le préimage d'en-tête committe `numTransactions` → PoW invalidé ; feuilles dupliquées rejetées par les checks de nonce). | Build niveau-par-niveau, dupliquer le **dernier** nœud, interdire les feuilles dupliquées. |
| **L8** | **`MerkleTree` liste vide → NPE** — `MerkleTree.java:52,55`. `setItems([])` laisse `root == null` ; `getRootHash()` NPE. Gardé à l'entrée consensus (`ChainEngine.addBlock:223`) mais pas par la classe (ex. `BlockAssembler:92`). | Racine d'arbre vide définie (32 octets zéro). |
| **L9** | **`ScanPredicate` récursion non bornée (DoS)** — `ScanPredicate.java:37-61,116,140`. `and`/`or` imbriqués sans limite de profondeur au parse et à l'évaluation → `StackOverflowError` (un `Error`, non capté par `catch (Exception)`) via l'endpoint de scan `NodeApi.java:137`. | Profondeur/nœuds max au `fromJson`. |
| **L10** | **`SnapshotChunk.decode` longueur de clé non bornée avant alloc** — `SnapshotChunk.java:51`. `new byte[getShort()]` (≤64 KiB) alloue avant de vérifier `remaining()` (la valeur, elle, est vérifiée) ; `count` borné par `bytes.length` (amplification ~8×). Bénin (alloc unique, `BufferUnderflow` capté). | Vérifier `keyLen > remaining()` ; caper `count` par `bytes.length/6`. |
| **L11** | **Normalisation OOM — même forme de fork succès-asymétrique** — `WasmVm.java:90-95`. Un nœud à plus grand tas peut réussir/committer là où un nœud à petit tas OOM/revert. Peu contrôlable (mémoire linéaire capée 64 MiB). | Comptabilité mémoire déterministe pré-facturée ; halt sur OOM en validation. |
| **L12** | **Asymétrie gas `box_read`** — `WasmVm.java:258-269`. `box.serialize()` fait O(taille du box) mais seul `BOX_READ_BASE = 100` est facturé avant ; boucle `box_read(id, ptr, 0)` force la sérialisation répétée d'un gros box (~16 Mo) pour ~100 gas/itération. | Facturer sur `box.serializedSize()`. |
| **L13** | **`RIPEMD160Hash.toBytes()` lève inconditionnellement** — `RIPEMD160Hash.java:29-33`. Hors chemin consensus aujourd'hui (`PublicAddress.of` dérive directement), mais mine dans le package crypto. | Implémenter (`return hash.getArray()`) ou supprimer le type. |
| **L14** | **`FloodDiscovery.discovered` croît sans borne** — `gossip/FloodDiscovery.java:25,72-91`. `addAll` sans dédup ni purge ; logique de merge incorrecte. Non câblé (`GossipSystem.getPeers` lève `UnsupportedOperationException`). | Borner + dédupliquer avant câblage du transport. |
| **L15** | **`ChainSynchronizer` fork-choice omet le travail d'oncle** — `ChainSynchronizer.java:163-177`. Ne somme que `2^blockDifficulty`, alors que `ChainEngine.totalWork` et `HeaderSynchronizer` incluent le travail d'oncle → notion de « plus lourd » divergente. Non exploitable (travail d'oncle minime), mais incohérence latente. | Aligner les trois chemins sur une définition unique. |

---

## Informationnel & dette technique

- **I1 — Aucune CI.** `.github/` ne contient que `dependabot.yml` : ni build, ni tests (369+ tests existants
  ne tournent pas automatiquement), ni scan de dépendances/SAST à chaque PR. Une CI aurait signalé H1
  (dépendance CVE). **Recommandé :** workflow GitHub Actions build + `./gradlew test` + scan de dépendances
  (OWASP Dependency-Check / `gradle dependencyCheckAnalyze`).
- **I2 — ActiveJ 6.0-beta2.** Pile HTTP/RPC/eventloop *beta* dans un composant critique. Évaluer une version
  stable ou verrouiller/auditer la beta.
- **I3 — Couche message `lib-net` non implémentée.** `MessageHandler` (handlers `data -> null`),
  `Message.data` (`Object` non borné), `TransportChannels.create` (renvoie `null`). C'est là qu'atterriront
  les attaques « message malformé / longueur en overflow / désérialisation non fiable » : **ne pas livrer sans
  caps de taille de corps, allow-list de types stricte, et null-safety.**
- **I4 — Deploy stocke du code non validé/non capé** — `WasmContractProcessor.java:88-96`. Alimente H4 ;
  valider + caper la taille au deploy.
- **I5 — `ContractExecutor` chemin legacy** sans `ContractCallHandler` ni garde de profondeur/réentrance
  (`call_contract` renvoie toujours -1). Confirmer qu'il n'est pas atteignable depuis la validation de bloc,
  sinon il divergera de `WasmContractProcessor`.

---

## Points vérifiés et jugés sains (résultats négatifs)

Ces classes d'attaque ont été explicitement recherchées et trouvées **correctement défendues** :

- **PoW sur chaque bloc de chaîne principale** : seuls `store.append` de genèse et post-validation existent ;
  aucun chemin n'accepte un bloc principal sans `verifyNonce`.
- **Liaison signature↔émetteur** : `PublicAddress.of(signingKey).equals(from)` exigé au mempool **et** à
  l'Executor ; signature vérifiée sur `hashContents()` liant `to,from,fee,amount,timestamp,chainId,nonce`.
- **Anti-rejeu / double-dépense** : `chainId` dans le préimage + nonces séquentiels par émetteur + hash de
  contenu sans signature (immunise contre la malléabilité).
- **Malléabilité txid** : identité et dédup via `hashContents()` ; cache `SignatureVerifier` clé sur
  `(contentHash, signatureHex, signingKeyHex)`.
- **Arithmétique ledger** : `Math.addExact`/`multiplyExact` avec catch-and-rollback ; montants/frais négatifs
  et overflow `amount+fee` / `gasLimit*gasPrice` rejetés.
- **Coinbase / émission** : exactement une coinbase, montant épinglé à `miningReward(height)`, décroissance
  entière.
- **Retarget de difficulté** : entier pur, clamp `MAX_STEP_BITS=4`, moteur et `HeaderChain` en miroir (incl.
  exclusion de l'intervalle de genèse) ; timestamps bornés (MTP + `parent+minBlockTime` + `now+maxFuture`).
- **Snapshot-sync** : `StateSnapshotImporter` reconstruit le SMT et exige `root == expectedRoot` **avant** tout
  `put` ; `expectedRoot` vient d'un en-tête pivot PoW-validé localement, jamais de l'annonce du pair.
- **Sparse Merkle Tree** : séparation de domaine `0x00`/`0x01`, canonique, profondeur bornée à 256, `verify`
  infalsifiable sans casser SHA-256.
- **Tokens/box** : `addExact`, montants `<= 0` rejetés, checks de propriétaire, ids déterministes non
  répétables ; pas d'inflation/overflow/négatif.
- **Rate-limiter** : clé sur l'adresse socket réelle (non `X-Forwarded-For`), IPv6 par /64, table bornée
  fail-closed.
- **SSRF admission/sync** : `PeerRegistry.add` + `HttpPeerSource`/`PeerDiscovery` rejettent
  loopback/privé/link-local/CGNAT/ULA/metadata sur mainnet et épinglent l'IP.
- **Eclipse/Sybil** : cap par sous-réseau /16, `maxPeers=128`, PEX borné, seeds réservés.
- **CSRF** : contrôle `Origin`-vs-`Host` sur chaque POST. **Path traversal** : `DashboardAssets` = allow-list
  `HashMap`. **Injection DB** : clés binaires fixes. **Gadgets désérialisation / exec** : absents.
- **Sandbox WASM & déterminisme host** : pas d'horloge/RNG/timestamp/ordre d'itération exposés ; lectures/
  écritures mémoire bornées par Chicory ; gas-meter saturant ; rollback session/undo atomique et reorg-safe.

---

## Recommandations priorisées

1. **C1 (frappe de monnaie)** — plancher + plafond de difficulté d'oncle liés à la difficulté de chaîne,
   récompense proportionnelle au travail, `registerOrphan` rejette sous `minDifficulty`, `checkLeadingZeroBits`
   renvoie `false` pour `challengeSize <= 0`. **Bloquant avant mainnet.**
2. **C2 (fork VM)** — limite déterministe de profondeur d'appel WASM dans le listener de metering. **Bloquant.**
3. **H1 (DoS crypto)** — monter BouncyCastle à ≥ 1.78. **Trivial, à faire immédiatement.**
4. **H2/H3/H4/H5** — ne pas créditer le travail d'oncle en validation d'en-tête ; déporter le DNS hors
   event-loop ; caper/tarifer/cacher le code WASM ; permissions `0600` sur la clé wallet.
5. **I1** — ajouter une CI (build + tests + scan de dépendances) pour capter la prochaine H1 automatiquement.
6. Traiter les M1–M5 et L1–L15 selon la fenêtre de release.

*Le fil rouge des deux findings sérieux de consensus (C1, H2) est unique : **la difficulté d'oncle n'est
jamais validée contre le consensus**. Le corriger à une seule règle partagée ferme les deux.*
