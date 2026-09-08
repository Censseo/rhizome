// Vidage des constantes de consensus d'un profil réseau — la source unique dont dérivent les
// fichiers profiles/*.env CHECKÉS (voir common.sh) et que TestnetProfileMirrorTest (app-node)
// confronte à NetworkParameters.<profil>() pour détecter toute dérive entre l'artefact checké et
// le profil réel.
//
// Ce binaire n'est PAS invoqué en direct par les batteries — voir la note dans common.sh : lire
// live NetworkParameters via ce binaire laisserait une batterie « s'adapter » silencieusement à
// un profil non revu, ce qui est exactement la propriété qu'on refuse (« changer de profil doit
// FAIRE ÉCHOUER la batterie, pas s'ajuster »). Il sert uniquement à (RE)GÉNÉRER
// profiles/<réseau>.env, un artefact revu et versionné :
//
//   javac -cp app-node/build/install/app-node/lib/'*' -d <out> tools/ProfileDump.java
//   java  -cp <out>:app-node/build/install/app-node/lib/'*' ProfileDump --network devnet \
//       > profiles/devnet.env
//
// Sortie : une ligne "CLÉ=VALEUR" par constante, directement source-able par bash (`source
// profiles/devnet.env`) ou lisible avec `profile_get CLÉ` (common.sh).
import rhizome.core.blockchain.NetworkParameters;

public final class ProfileDump {

    // Miroir de NodeConfig.PRUNE_MARGIN (app-node) : la marge de sûreté au-dessus de la plus
    // profonde histoire que le moteur peut relire. Doit rester synchrone avec cette constante —
    // si NodeConfig.PRUNE_MARGIN change, ce miroir doit changer avec elle (les deux sont exercés
    // ensemble par NodeConfigParsingTest côté Java ; ce fichier n'a pas d'accès à une constante
    // non publique d'app-node depuis un module tiers, d'où la copie plutôt qu'un import).
    private static final int PRUNE_MARGIN = 128;

    public static void main(String[] args) {
        String network = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--network" -> network = args[++i];
                default -> throw new IllegalArgumentException("option inconnue: " + args[i]);
            }
        }
        if (network == null) {
            System.err.println("usage: ProfileDump --network <mainnet|testnet|devnet|staging>");
            System.exit(2);
        }
        NetworkParameters p = NetworkParameters.byName(network);

        // Même formule que NodeConfig.parseKeepBlocks : le plancher de rétention sûr, dérivé de
        // la plus profonde histoire que le moteur peut relire (reorg, oncle, difficulté, MTP).
        int pruneFloor = Math.max(Math.max(p.maxReorgDepth(), p.uncleMaxDepth()),
            Math.max(p.difficultyLookback(), p.medianTimeWindow())) + PRUNE_MARGIN;

        System.out.println("CHAIN_ID=" + p.chainId());
        System.out.println("NETWORK_NAME=" + p.networkName());
        System.out.println("POW_ALGORITHM=" + p.powAlgorithm());
        System.out.println("DIFFICULTY_LOOKBACK=" + p.difficultyLookback());
        System.out.println("MIN_DIFFICULTY=" + p.minDifficulty());
        System.out.println("MAX_DIFFICULTY=" + p.maxDifficulty());
        System.out.println("GENESIS_DIFFICULTY=" + p.genesisDifficulty());
        System.out.println("DESIRED_BLOCK_TIME_SEC=" + p.desiredBlockTimeSec());
        System.out.println("MAX_FUTURE_BLOCK_TIME_SEC=" + p.maxFutureBlockTimeSec());
        System.out.println("MEDIAN_TIME_WINDOW=" + p.medianTimeWindow());
        System.out.println("MIN_FEE=" + p.minFee());
        System.out.println("MAX_REORG_DEPTH=" + p.maxReorgDepth());
        System.out.println("MAX_UNCLES_PER_BLOCK=" + p.maxUnclesPerBlock());
        System.out.println("UNCLE_MAX_DEPTH=" + p.uncleMaxDepth());
        System.out.println("PRUNE_FLOOR=" + pruneFloor);
    }
}
