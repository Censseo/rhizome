// Forge de BLOCS pour le testnet local — l'équivalent, côté chaîne, de ce que Forge.java fait
// côté transaction. Principe : ne PAS fabriquer un bloc de zéro (un bloc valide engage une
// racine d'état que rien hors du nœud ne sait calculer — mesuré : INVALID_STATE_ROOT), mais
// PRENDRE un bloc réel produit par un nœud source, muter EXACTEMENT le champ visé, puis RÉ-MINER
// le nonce. Le bloc arrive donc à la porte visée en portant un vrai travail : sans ce ré-minage
// toute mutation d'un champ engagé dans le hash serait rejetée au dernier contrôle
// (INVALID_NONCE) et le scénario « passerait » sans rien prouver — c'est la leçon de BlockForge
// (testFixtures), dont ce fichier est la transposition HTTP.
//
// L'horodatage n'entre pas dans l'état (le crédit de coinbase ne dépend que de la hauteur et de
// la supply parente), donc muter le temps préserve la racine d'état du bloc source : le nœud
// victime juge alors la règle temporelle, et rien d'autre.
//
// Le rejeu SANS mutation est le TÉMOIN de la batterie : s'il n'est pas accepté, aucun rejet
// mesuré ensuite n'est attribuable à la règle visée.
//
//   Anvil --source http://127.0.0.1:4406 --url http://127.0.0.1:4407 [options]
//     --ts-offset <ms>   horodatage = maintenant + ms   (fenêtre future / dérive d'horloge)
//     --ts-parent <ms>   horodatage = parent + ms        (recul contrôlé)
//     --ts-abs <ms>      horodatage absolu               (timewarp : calendrier imposé)
//     --ts-base <ms>     horodatage = base + hauteur*pas (calendrier régulier, cf. --ts-step)
//     --ts-step <ms>     pas du calendrier (défaut 1000)
//     --diff-delta <n>   difficulté déclarée = attendue + n
//     --no-pow           ne ré-mine pas (travail non payé)
//     --count <n>        enchaîne n blocs (défaut 1)
//     --quiet            n'imprime que le statut du nœud
//     --dump <fichier>   n'ENVOIE rien : écrit le bloc forgé (octets bruts, BlockCodec.encode) dans
//                        <fichier> et s'arrête après le premier bloc — pour une batterie qui veut
//                        rejouer les MÊMES octets à haute fréquence par elle-même (curl), plutôt que
//                        de payer une JVM par soumission. `--count` est sans effet avec `--dump`.
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.json.JSONObject;

import rhizome.core.block.Block;
import rhizome.core.block.BlockCodec;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.crypto.SHA256Hash;

public final class Anvil {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    public static void main(String[] args) throws Exception {
        String source = null, url = null, network = "devnet";
        Long tsOffset = null, tsParent = null, tsAbs = null, tsBase = null;
        long tsStep = 1000;
        int diffDelta = 0, count = 1;
        boolean pow = true, quiet = false;
        String dump = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--source" -> source = args[++i];
                case "--url" -> url = args[++i];
                case "--network" -> network = args[++i];
                case "--ts-offset" -> tsOffset = Long.parseLong(args[++i]);
                case "--ts-parent" -> tsParent = Long.parseLong(args[++i]);
                case "--ts-abs" -> tsAbs = Long.parseLong(args[++i]);
                case "--ts-base" -> tsBase = Long.parseLong(args[++i]);
                case "--ts-step" -> tsStep = Long.parseLong(args[++i]);
                case "--diff-delta" -> diffDelta = Integer.parseInt(args[++i]);
                case "--no-pow" -> pow = false;
                case "--count" -> count = Integer.parseInt(args[++i]);
                case "--quiet" -> quiet = true;
                case "--dump" -> dump = args[++i];
                default -> throw new IllegalArgumentException("option inconnue: " + args[i]);
            }
        }
        if (source == null || url == null) {
            System.err.println("usage: Anvil --source <nœud source> --url <nœud victime> [options]");
            System.exit(2);
        }
        NetworkParameters params = NetworkParameters.byName(network);

        for (int n = 0; n < count; n++) {
            JSONObject stats = getJson(url + "/stats");
            long height = stats.getLong("height");
            int expected = stats.getInt("difficulty");
            long id = height + 1;

            BlockImpl block = (BlockImpl) waitForSourceBlock(source, id);
            // Re-parentage sur le tip DE LA VICTIME : dès qu'une mutation est acceptée, la victime
            // diverge de la source et tout bloc suivant serait rejeté en INVALID_LASTBLOCK_HASH,
            // avant la règle visée. Le corps reste celui de la source, donc la racine d'état reste
            // valide : à hauteur égale les deux chaînes créditent exactement la même coinbase, et
            // l'horodatage n'entre pas dans l'état.
            block.lastBlockHash(SHA256Hash.of(stats.getString("tipHash")));
            long parentTs = Long.parseLong(getJson(url + "/block?blockId=" + height)
                .get("timestamp").toString());
            long now = System.currentTimeMillis();

            // Sans consigne explicite, on garde l'horodatage de la source mais on le force au-delà
            // du parent DE LA VICTIME : une mutation temporelle acceptée plus tôt a pu stamper le
            // tip loin devant, et le rejeu honnête suivant serait alors rejeté en
            // BLOCK_TIMESTAMP_TOO_CLOSE — un rejet correct, mais qui ne prouve rien du scénario.
            long timestamp = Math.max(block.timestamp(), parentTs + 1);
            if (tsBase != null) timestamp = tsBase + id * tsStep;
            if (tsAbs != null) timestamp = tsAbs;
            if (tsOffset != null) timestamp = now + tsOffset;
            if (tsParent != null) timestamp = parentTs + tsParent;
            block.timestamp(timestamp);

            int declared = expected + diffDelta;
            block.difficulty(declared);

            if (pow) {
                block.nonce(Miner.mineNonce(block.hash(), block.difficulty(),
                    params.powAlgorithm(), params.powCostsAt(block.id())));
            } else {
                block.nonce(SHA256Hash.empty());
            }

            if (dump != null) {
                // Écrit les octets et s'arrête là : pas de soumission, donc rien ne fait avancer la
                // victime — enchaîner sur `count` n'aurait aucun sens (même hauteur cible à chaque
                // tour). L'appelant décide seul du rythme auquel il rejoue ce fichier.
                java.nio.file.Files.write(java.nio.file.Path.of(dump), BlockCodec.encode(block));
                if (quiet) {
                    System.out.println("DUMPED " + dump);
                } else {
                    System.out.println(new JSONObject()
                        .put("id", id).put("declaredDifficulty", declared).put("expectedDifficulty", expected)
                        .put("timestamp", timestamp).put("parentTimestamp", parentTs)
                        .put("deltaNowMs", timestamp - now).put("pow", pow)
                        .put("hash", block.hash().toHexString()).put("dump", dump));
                }
                return;
            }

            String status = submit(url, block);
            if (quiet) {
                System.out.println(status);
            } else {
                System.out.println(new JSONObject()
                    .put("id", id).put("declaredDifficulty", declared).put("expectedDifficulty", expected)
                    .put("timestamp", timestamp).put("parentTimestamp", parentTs)
                    .put("deltaNowMs", timestamp - now).put("pow", pow)
                    .put("hash", block.hash().toHexString()).put("status", status));
            }
            if (!status.startsWith("200")) {
                break; // le rejet est le résultat : ne pas enchaîner sur une chaîne qui n'a pas bougé
            }
        }
    }

    /** Le bloc de hauteur {@code id} chez la source, en attendant qu'elle l'ait miné. */
    private static Block waitForSourceBlock(String source, long id) throws Exception {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            HttpRequest req = HttpRequest.newBuilder(
                URI.create(source + "/sync?start=" + id + "&end=" + id)).GET().build();
            HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() == 200 && res.body().length > 0) {
                return BlockCodec.decode(res.body());
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("la source n'a pas produit le bloc " + id);
    }

    private static String submit(String url, Block block) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url + "/submit"))
            .header("X-Rhizome-Request", "1")
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(BlockCodec.encode(block)))
            .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return res.statusCode() + " " + res.body().trim();
    }

    private static JSONObject getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        return new JSONObject(new String(res.body(), StandardCharsets.UTF_8));
    }
}
