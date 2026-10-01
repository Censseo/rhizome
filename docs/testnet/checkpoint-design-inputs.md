# Chantier 0.4 — inputs de conception « checkpoint publié », issus du soak campagne 11

> Statut : **note d'inputs, pas une décision**. Le plan de campagne (`scripts/local-testnet/
> TEST-PLAN.md`, phase 1 « fin de soak — checkpoint ») exige que le format ne soit pas improvisé
> ici mais posé comme question de conception une fois une vraie chaîne disponible comme donnée
> d'entrée. Ce document est cette donnée d'entrée : tout ce que le soak staging 2026-09-28 →
> 10-01 (h≈28 400, 3 seeds OVH + 2 bancs, snapshots vivants) a mesuré qui contraint un design
> de checkpoint. Chaque affirmation est adossée à une mesure ou un journal cité dans TEST-PLAN.md.

## Ce qui existe déjà et a tourné en production

- Snapshots d'état récurrents : `RHIZOME_SNAPSHOT_EVERY=17280` (~1 jour à 5 s/bloc). Le premier
  snapshot naturel est apparu à h=17 280 (88 Ko, 6 chunks, `rhizome-snapshot-*.chunks`).
- Adoption snap (`RHIZOME_SYNC=snap` sur répertoire vide) : pivot enterré ≥ `maxReorgDepth`,
  racine d'état reconstruite exactement depuis les chunks, **PoW revalidé de la genèse au pivot**.
- Quatre récupérations réelles par purge+snap pendant le soak (seed-3 ×2, bancs ×2), une
  adhésion à froid (joiner), un retour de seed complète (seed-1 : 3 h de validation silencieuse).

## Économie mesurée (les nombres qui contraignent le design)

| Chemin | Coût mesuré | Empreinte |
|---|---|---|
| Rejeu intégral (plain sync, joiner) | ~9 h à h≈17,5k (murs PoW memory-hard + wasm) | RSS < 1 Go |
| Purge + snap | **10-11 min** au même âge | **< 170 Mo** |
| Boot d'un store plein (replayé) | minutes | **5-15 Go RSS** — OOM déterministe sur VM 6 Go dès ~15k blocs ; 7,85 Go « portés par le swap » à 27,8k |
| Boot d'un store snap-adopté | ~2 min | 105-160 Mo |
| Validation PoW du bootstrap snap seule | 23 min JVM/1 cœur (17,8k en-têtes) ; **3 h sur seed 2 vCPU** | silencieuse (aucun log) |

## Contraintes dures découvertes (un design de checkpoint doit y répondre)

1. **Le snapshot est une dépendance, pas une optimisation.** Un réseau dont tous les anciens
   nœuds ont snap-adopté ne sert plus les corps sous le pivot (« pruned the bodies we need ») :
   un joiner plain n'a **aucune** source. Le checkpoint hérite de cette question : qui porte
   l'archive complète, et pour combien de temps ?
2. **Le coût de confiance d'un pivot est le PoW.** L'adoption actuelle revalide toute la
   chaîne jusqu'au pivot — c'est ce qui rend le snapshot gratuit en confiance et cher en CPU
   (memory-hard). Un « checkpoint publié » qui économise cette revalidation fait un choix de
   modèle de confiance (ancrage social/téléchargeable vs preuve de travail) : c'est LA question
   de fond du 0.4, pas un détail de format.
3. **Servir ne doit pas affamer l'API.** Le service des corps/chunks sérialise sur la boucle
   d'événements : gel d'API d'une seed pendant ~1 h sous service peer (seed-2, 2026-09-30), et
   les passes de suites affament les petits nœuds. Un checkpoint servi doit vivre hors de la
   boucle (pool borné), sinon publier un checkpoint DoS le nœud qui le sert.
4. **Un nœud en rattrapage doit rester observable.** Les phases silencieuses (validation pré-
   écouteur sans un log) ont fait tuer des boots vivants à trois reprises par l'opérateur.
   Progression/périodicité de phase = exigence, pas cosmétique.
5. **Le boot d'un store long explose la mémoire native** (dimensionnement heap de l'image
   native ≈ 80 % de la RAM physique ; plafond `-R:MaxHeapSize=512m` posé, mais les stores
   replayés ≥15k ballonnent quand même en natif — anomalie VM ouverte). Les stores snap-adoptés
   y échappent. Un checkpoint doit être le chemin de boot **normal** des petits nœuds.

## Questions ouvertes pour le mainteneur

- Que certifie un checkpoint publié : racine d'état seule, ou racine + chaîne d'en-têtes
  signée/quorum ? Quel modèle de menace (joiner paresseux vs nœud malveillant) ?
- Où vit l'archive des corps pré-pivot, et avec quelle politique de rétention minimale ?
- Format/lieu de publication (fichier statique téléchargeable vs servi par le protocole pair),
  et cadence — 17 280 est-il le bon pas une fois la publication externalisée ?
- Faut-il distinguer « checkpoint de boot rapide » (PoW revalidé) et « checkpoint de confiance »
  (PoW tronqué) comme deux modes, plutôt qu'un seul format compromis ?
