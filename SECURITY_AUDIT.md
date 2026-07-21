# Rhizome — Audit de sécurité & exploitation blockchain

**Dernière mise à jour :** 2026-07-21
**Périmètre :** intégralité du dépôt `censseo/rhizome` (consensus/PoW, transactions/mempool/signatures,
réseau P2P, VM de contrats/gas, sérialisation/état-Merkle/snapshots, API node/dashboard, wallet/keystore,
persistance, dépendances).
**Méthode :** deux passes de revue de code manuelle adverse, traçant chaque chemin depuis l'entrée réseau
(HTTP `/submit`, `/add_transaction`, `/add_peer`, `/sync`, `/headers`, `/call_readonly`) jusqu'aux mutations
de ledger/état, avec vérification adverse des chemins d'acceptation de blocs, de récompense et d'exécution de
contrats. La 2ᵉ passe a re-vérifié dans le code réel que les correctifs de la 1ʳᵉ passe tenaient, et a cherché
ce qu'ils avaient manqué.

> **✅ Statut de remédiation.** **Tous les findings ci-dessous sont corrigés** sur la branche
> `claude/security-audit-hardening-vo5b9g`, sauf : (a) une poignée de **suivis plus structurels** listés en fin
> de rapport (budget mémoire/table VM agrégé, service de blocs hors event-loop en streaming, chiffrement wallet
> par défaut) ; et (b) l'**activation de la CI** (`I1`/`I7`), qui reste manuelle faute de permission `workflows`
> sur le compte d'automatisation. Chaque correctif porte un commentaire référençant son identifiant d'audit
> dans le code, et est couvert par des tests de régression. **Suite complète : 380 tests, tous verts.**
>
> **État général.** Les invariants centraux tiennent : liaison signature↔émetteur, anti-rejeu
> (chainId + nonces séquentiels + hash de contenu sans signature), arithmétique de ledger vérifiée
> (`Math.addExact`), bornage des entrées réseau, intégrité du snapshot-sync, séparation de domaine du
> Sparse Merkle Tree, coinbase épinglée à l'émission. Les deux vulnérabilités les plus graves restantes après la
> 1ʳᵉ passe — **frappe de monnaie par récompense d'oncle disproportionnée** et **contrefaçon de token par
> auto-transfert** — ont été trouvées et fermées en 2ᵉ passe.

---

## Résumé exécutif — tous les findings

Les identifiants `C/H/M/L/I` viennent de la 1ʳᵉ passe ; les `N*` de la 2ᵉ passe (findings nouveaux ou
correctifs révélés incomplets). Sévérité au moment de la découverte.

### Consensus / PoW

| # | Sévérité | Titre | Statut |
|---|----------|-------|--------|
| **C1 / N1** | 🔴 Critique | Récompense d'oncle disproportionnée ⇒ frappe de monnaie. 1ʳᵉ passe : difficulté 0 acceptée (travail nul). 2ᵉ passe : le correctif laissait une récompense **forfaitaire** plancher `minDifficulty` ⇒ un oncle à difficulté 16 sur une chaîne à haute difficulté rapportait ~½ bloc (~2× émission). | ✅ Récompense **proportionnelle au travail** (`base >> (nephewDiff − uncleDiff)`) + difficulté 0 rejetée |
| **C2 / N4** | 🔴 Critique | Fork de consensus par auto-récursion WASM (StackOverflow dépendant de la JVM). 2ᵉ passe : le cap déterministe était **par-instance** et se réinitialisait à chaque `call_contract` (pire cas 8×). | ✅ Cap de profondeur **global à l'arbre d'appel** (thread-local) + `ChicoryException` normalisée en OOG plein-gas |
| **H2** | 🟠 Élevée | Work-gate headers-first contournable via travail d'oncle non prouvé. | ✅ Travail d'oncle borné `[minDifficulty, nephewDifficulty]` sur les 3 chemins de fork-choice |
| **L15** | ⚪ Faible | Fork-choice omettait le travail d'oncle dans un des 3 chemins. | ✅ Les 3 chemins alignés |
| **N7** | ⚪ Faible | Difficulté propre du bloc/en-tête **non bornée au décodage** ⇒ AIOOBE dans `checkLeadingZeroBits`, `BigInteger.pow(2^31)`. | ✅ Bornée `[0, MAX_DIFFICULTY]` au décodage + `checkLeadingZeroBits` fail-closed pour `>256` |
| **N8** | ⚪ Faible | `uncleWorkByHeight` croît sans borne (fuite d'état dérivé). | ✅ Éviction sous `tip − maxReorgDepth` |

### VM de contrats (WASM)

| # | Sévérité | Titre | Statut |
|---|----------|-------|--------|
| **H4** | 🟠 Élevée | Parse WASM + re-scan float non tarifés par appel, pas de taille max de code. | ✅ `MAX_CODE_SIZE` au deploy + cache LRU de modules |
| **M3** | 🟡 Moyenne | Rejet float incomplet (opcodes SIMD flottants passaient le garde). | ✅ Rejet élargi `F32*/F64*/V128*` + lane-shapes |
| **L11 / N-VM** | ⚪→🟡 | OOM asymétrique (fork succès/échec selon `-Xmx`). Aggravé par l'absence de budget mémoire **agrégé** sur l'arbre d'appel (8×64 MiB). | ⚠️ Partiel — OOM normalisé ; budget agrégé = suivi (voir fin) |
| **L12** | ⚪ Faible | Asymétrie gas `box_read`. | ✅ Facturé sur `serializedSize()` |
| **N12** | ⚪ Faible | `table.copy`/`table.init` non tarifés (O(N) au prix de 1 gas). | ✅ Facturés par l'opérande de comptage |

### P2P / réseau

| # | Sévérité | Titre | Statut |
|---|----------|-------|--------|
| **H3 / N6** | 🟠→🟡 | DoS event-loop par DNS bloquant sur `/add_peer`. 2ᵉ passe : l'offload utilisait une file **non bornée, sans dédup** ⇒ OOM + famine d'admission. | ✅ DNS hors loop **et** file bornée + dédup des URLs en vol |
| **M1** | 🟡 Moyenne | SSRF DNS-rebinding dans `PeerBroadcaster` (broadcast non épinglé). | ✅ IP épinglée avant chaque envoi |
| **N5 (résidu M2)** | 🟠 Élevée | `/sync` & `/headers` (endpoints les plus lourds, jusqu'à ~800 MiB bufferisés) **non pondérés** dans le rate-limiter. | ✅ Pondérés par le range servi |
| **L1/L2/L14** | ⚪ Faible | TOCTOU DNS ban ; filtre SSRF hors-mainnet ; `FloodDiscovery` non borné. | ✅ Corrigés (L14 : cap présent, code non câblé) |

### API / dashboard / wallet

| # | Sévérité | Titre | Statut |
|---|----------|-------|--------|
| **M2** | 🟡 Moyenne | Amplification de travail non authentifiée (scans, dry-run). | ✅ Pondération rate-limiter (+ N5 pour sync/headers) |
| **H5** | 🟠 Élevée | Clé privée wallet en clair, world-readable. | ✅ Écriture atomique `0600` + chiffrement opt-in AES-256-GCM |
| **N9** | ⚪ Faible | Pool SSE global sans cap par IP ⇒ déni du flux live par une IP. | ✅ Cap par clé client |
| **N10** | ⚪ Faible | `WalletKeystore` fait confiance à un champ `iter` non borné ⇒ DoS local PBKDF2. | ✅ Borné `[100k, 10M]` |
| **L3/L4/L9** | ⚪ Faible | Divulgation d'exception ; KDF/secret par env ; `ScanPredicate` récursion. | ✅ Message générique ; PBKDF2 600k (passphrase env = résidu) ; profondeur/parts bornées |
| **I8** | ℹ️ Info | Régression M2 : dashboard demandait `depth=2000` alors que le cap est 1000. | ✅ Aligné à 1000 |

### Crypto / codec / Merkle

| # | Sévérité | Titre | Statut |
|---|----------|-------|--------|
| **H1 / I6** | 🟠 Élevée | BouncyCastle 1.76 (CVE-2024-30172, boucle infinie Ed25519). 2ᵉ passe : le module mort `lib-crypto` épinglait toujours 1.76. | ✅ `lib-core` **et** `lib-crypto` en 1.78.1 |
| **M4** | 🟡 Moyenne | NPE dans `checkSignature` sur clé nulle/vide. | ✅ Renvoie `false` |
| **M5** | 🟡 Moyenne | `MerkleTree` sans séparation de domaine feuille/nœud. | ✅ Préfixes `0x00`/`0x01` |
| **N11** | ⚪ Faible | `PublicKey.of(String)` n'aligne pas la clé tout-zéro sur `empty()` (divergence JSON/binaire du binding). | ✅ Routé via `of(byte[])` |
| **L6/L7/L8/L10/L13** | ⚪ Faible | Codec 64-bit ; Merkle non canonique/vide/NPE ; `SnapshotChunk` alloc ; `RIPEMD160Hash.toBytes`. | ✅ Tous corrigés |

### Persistance / mempool / tokens

| # | Sévérité | Titre | Statut |
|---|----------|-------|--------|
| **N2** | 🔴 Critique | **Contrefaçon de token** : l'auto-transfert (`to==from`) calculait le crédit sur le solde pré-débit et écrasait le débit ⇒ solde doublé. | ✅ Auto-transfert = no-op ; chemin général débite d'abord |
| **N3** | 🟠 Élevée | **Arrêt de production** : une tx no-op signée d'un compte inexistant (frais 0) était admise/sélectionnée, faisait rejeter le bloc, n'était jamais purgée ⇒ minage figé réseau. | ✅ `AccountView.senderExists` garde admission **et** sélection |
| **L5** | ⚪ Faible | Pas de plancher de frais. | ✅ `params.minFee()` câblé (0 par défaut) |

### Infra / dette

| # | Sévérité | Titre | Statut |
|---|----------|-------|--------|
| **I1 / I7** | ℹ️ Info | Aucune CI ; le workflow n'existait qu'en `.example` (inerte). | ⚠️ Workflow prêt ; **activation par un mainteneur requise** (permission `workflows`) |
| **I2–I5** | ℹ️ Info | ActiveJ beta ; couche message `lib-net` non implémentée ; deploy non capé (→ H4) ; `ContractExecutor` legacy. | ℹ️ I4 fermé (H4) ; I3/I5 = code non câblé, à ne pas livrer sans caps ; I2 à évaluer |

---

## Détail des findings critiques et élevés

### 🔴 C1 / N1 — Frappe de monnaie par récompense d'oncle disproportionnée

**Chemin.** `Executor.payUncleRewards`/`scaleRewardToWork`, `ChainEngine.uncleEligible`, `NetworkParameters`,
atteignable via `/submit` → `registerOrphan`.
La 1ʳᵉ passe a fermé le chemin **travail-nul** (difficulté 0 : `checkLeadingZeroBits(≤0)=false`,
`registerOrphan` plancher `minDifficulty`). Mais la récompense est restée **forfaitaire** (`miningReward/2` par
oncle) avec éligibilité `[minDifficulty, nephewDifficulty]`. Sur un réseau vivant la difficulté dépasse vite
`minDifficulty` (16) ; un mineur attache alors à un vrai bloc à haute difficulté des oncles à difficulté 16
(~2^16 hachages) et empoche une demi-récompense chacun — **~2× d'émission** pour un travail négligeable.
**Correctif.** Récompense **proportionnelle au travail prouvé** : `scaleRewardToWork(base, deficit) =
base >> (nephewDifficulty − uncleDifficulty)`. Un oncle de même difficulté garde la pleine récompense (aucun
changement pour les oncles légitimes) ; chaque bit de difficulté manquant la divise par deux ; un oncle très
sous-difficulté rapporte 0. Entier pur ⇒ déterministe entre nœuds, et le scaling ne lit que les difficultés
committées ⇒ reorg exacte. Tests `ExecutorRewardScalingTest`.

### 🔴 C2 / N4 — Fork de consensus par récursion WASM

`WasmVm`, `DepthLimitedInterpreterMachine`, `WasmContractProcessor`. La 1ʳᵉ passe a introduit un cap de
profondeur déterministe (`MAX_WASM_CALL_DEPTH=1024`) sur un thread à pile fixe, remplaçant le `StackOverflowError`
dépendant de `-Xss`. La 2ᵉ passe a montré que ce cap était **par-instance** : un nouvel `Instance` (donc un
`callStack` neuf) est créé à chaque `call_contract`, si bien qu'une chaîne de 8 contrats pouvait empiler
`8 × 1024` frames sur l'unique thread — réintroduisant le fork de pile.
**Correctif.** Le compteur de profondeur est désormais **global à tout l'arbre d'appel** via un thread-local
(l'arbre entier s'exécute sur le thread `rhizome-wasm`), donc le pire cas reste 1024 frames — exactement ce que
la pile fixe est dimensionnée pour tenir. En défense en profondeur, une `ChicoryException "call stack exhausted"`
résiduelle est normalisée en out-of-gas **plein-gas déterministe** (jamais le `gas.used()` local au nœud).

### 🔴 N2 — Contrefaçon de token par auto-transfert

`DefaultTokenProcessor.transfer` calculait le crédit destinataire à partir du solde **avant** débit, puis
l'écrivait. Quand `to == from` (même clé de solde), le crédit écrasait le débit et le solde final devenait
`B + montant` : un `TOKEN_TRANSFER` vers soi-même doublait le solde à chaque appel, quasi gratuitement, brisant
`Σ soldes == totalSupply` — contrefaçon illimitée. **Correctif.** `from.equals(to)` traité en no-op explicite ;
chemin général débite d'abord dans la session puis relit avant de créditer. Test
`TokenOpTest.selfTransferIsNoOpAndDoesNotMint`.

### 🟠 N3 — Arrêt de production par transaction empoisonnée

Le mempool admettait une tx no-op signée (`amount=0, fee=0`) d'un **compte inexistant** (`confirmedBalance=0`,
check cumulé `0 > 0` faux). Sélectionnée dans chaque bloc candidat, elle faisait rejeter tout le bloc à
l'exécution (`SENDER_DOES_NOT_EXIST`), n'était jamais purgée (pas de TTL) ⇒ **production figée sur tout le
réseau** + saturation du pool par clés jetables. **Correctif.** `AccountView.senderExists` (miroir du check de
l'Executor) garde l'**admission** et la **sélection** dans `MemPool`. Aucune transaction de valeur légitime
n'est perdue (toute tx déplaçant de la valeur d'un émetteur non financé échouait déjà au check de solde). Test
`MemPoolTest.rejectsAndNeverSelects…`.

### 🟠 H1 / I6 — BouncyCastle 1.76 (CVE-2024-30172)

Boucle infinie Ed25519 déclenchable par signature forgée ⇒ DoS réseau distant. `lib-core` a été monté en 1.78.1
en 1ʳᵉ passe ; la 2ᵉ passe a trouvé que le module `lib-crypto` (mort, mais scanné par tout outil de dépendances)
épinglait toujours **1.76** — aligné en 1.78.1.

### 🟠 H3 / N6 — DoS event-loop + OOM d'admission de pairs

La 1ʳᵉ passe a déporté la résolution DNS d'`/add_peer` hors de l'event-loop ActiveJ (sinon un hôte à DNS lent
gèle tout le nœud). La 2ᵉ passe a montré que l'offload utilisait un `newSingleThreadExecutor` à file **non
bornée, sans dédup** : un flux d'`/add_peer` grossissait la file ~1000/s (chacun ~5 s de DNS) ⇒ OOM et famine
d'admission des pairs honnêtes. **Correctif.** File bornée (`ArrayBlockingQueue` + `AbortPolicy`) et dédup des
URLs en vol (nettoyée sur complétion **et** sur rejet, donc jamais fuyante).

### 🟠 N5 — Amplification `/sync` & `/headers`

Le correctif M2 a pondéré les scans mais a laissé `/sync` (jusqu'à `BLOCKS_PER_FETCH` blocs pleins, ~800 MiB
bufferisés sur la loop) et `/headers` au coût 1 ⇒ 1000 req/s/IP. **Correctif.** Coût pondéré par le range servi
(un range hors-limite, rejeté sans lecture, reste au coût 1). *Suivi structurel : servir ces endpoints hors
event-loop en streaming — la pondération borne le débit, pas le buffering par-requête.*

### 🟠 H4 — Parse/scan WASM non tarifé par appel

Chaque `CALL` re-parsait le module et re-scannait chaque opcode, sans cache ni taille max. **Correctif.**
`MAX_CODE_SIZE` au deploy + cache LRU de modules par hash. *Résidu : le parse d'un cache-miss n'est pas facturé
au gas (cache-busting sur >256 contrats) — suivi mineur.*

### 🟠 H5 — Clé privée wallet en clair

`save()` créait le fichier selon l'umask (0644). **Correctif.** Écriture atomique owner-only (`0600` + tmp +
`ATOMIC_MOVE`) et chiffrement AES-256-GCM opt-in (PBKDF2 600k). *Résidu L4 : le chiffrement reste opt-in et la
passphrase vient d'une variable d'env — suivi : chiffrement par défaut, passphrase depuis fichier/prompt.*

---

## Points vérifiés et jugés sains (résultats négatifs)

Ces classes d'attaque ont été explicitement recherchées **sur les deux passes** et trouvées correctement
défendues :

- **PoW sur chaque bloc de chaîne principale** ; aucun chemin n'accepte un bloc principal sans `verifyNonce`.
- **Liaison signature↔émetteur** (`PublicAddress.of(signingKey).equals(from)` au mempool **et** à l'Executor) ;
  signature sur `hashContents()` liant `to,from,fee,amount,timestamp,chainId,nonce`.
- **Anti-rejeu / double-dépense** : `chainId` dans le préimage + nonces séquentiels par émetteur + hash de
  contenu sans signature (immunise contre la malléabilité txid). Dédup permanente via txindex.
- **Arithmétique ledger** : `Math.addExact`/`multiplyExact` avec catch-and-rollback ; négatifs/overflow rejetés.
- **Coinbase / émission** : exactement une coinbase, montant épinglé à `miningReward(height)`.
- **Retarget de difficulté** : entier pur, clamp `MAX_STEP_BITS`, moteur et `HeaderChain` en miroir ;
  timestamps bornés (MTP + `parent+minBlockTime` + `now+maxFuture`).
- **Snapshot-sync** : `StateSnapshotImporter` reconstruit le SMT et exige `root == expectedRoot` **avant** tout
  `put` ; `expectedRoot` vient d'un en-tête pivot PoW-validé localement.
- **Sparse Merkle Tree** : séparation de domaine `0x00`/`0x01`, canonique, profondeur 256, `verify` sûre.
- **Tokens/box** : `addExact`, montants `<= 0` rejetés, checks de propriétaire, ids déterministes ; la seule
  faille (auto-transfert, N2) est fermée.
- **Rate-limiter** : clé sur l'adresse socket réelle (non `X-Forwarded-For`), IPv6 par /64, table bornée
  fail-closed.
- **SSRF admission/sync** : loopback/privé/link-local/CGNAT/ULA/metadata rejetés sur mainnet, IP épinglée.
- **Eclipse/Sybil** : cap par sous-réseau, `maxPeers`, PEX borné, seeds réservés.
- **CSRF** : contrôle `Origin`-vs-`Host` sur chaque POST. **Path traversal** : allow-list `DashboardAssets`.
  **XSS dashboard** : insertion via `createTextNode` uniquement + CSP `script-src 'self'`.
- **Sandbox WASM & déterminisme** : pas d'horloge/RNG/timestamp/ordre d'itération exposés ; gas saturant ;
  itération de maps non exposée au root (SMT ordonné par clé) ; rollback session atomique et reorg-safe.

---

## Suivis restants (recommandations, non implémentés)

Plus structurels, à traiter selon la fenêtre de release :

1. **Budget mémoire & taille de table WASM agrégé sur tout l'arbre d'appel** (L11/N-VM). Le cap mémoire de
   64 MiB est **par-instance** : une chaîne de 8 `call_contract` peut allouer ~512 MiB, d'où un fork OOM
   asymétrique selon `-Xmx`. Ajouter un budget déterministe agrégé (mémoire + éléments de table) pré-facturé,
   et un cap de taille de table à l'instanciation.
2. **Service des blocs/headers et `/call_readonly` hors event-loop, en streaming** (N5, API). La pondération
   borne le débit mais le buffering intégral (~800 MiB) et l'exécution WASM jusqu'à `MAX_READONLY_GAS` restent
   sur la loop ; les déporter sur un pool borné avec réponse en flux.
3. **Chiffrement wallet par défaut** + passphrase lue depuis fichier/prompt plutôt que variable d'env (résidu
   L4) ; envisager scrypt/argon2id.
4. **Tarification gas du parse WASM par taille de code** avant exécution, indépendante du cache (résidu H4).
5. **Activation de la CI** (`I1`/`I7`) : le workflow est prêt en `.github/ci-workflow.yml.example` ; un
   mainteneur doit le copier vers `.github/workflows/ci.yml` (permission GitHub `workflows` requise, dont le
   compte d'automatisation ne dispose pas). Il build, lance les 380 tests et exécute une revue de dépendances
   qui aurait signalé la classe H1/CVE avant merge.
6. **Ne pas livrer la couche message `lib-net`** (I3) ni le `ContractExecutor` legacy (I5) sans caps de taille
   de corps, allow-list de types stricte et null-safety : c'est là qu'atterriront les attaques « message
   malformé ». Évaluer une version stable d'ActiveJ (I2).
