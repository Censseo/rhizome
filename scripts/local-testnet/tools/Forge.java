import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import rhizome.core.common.Utils;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionKind;
import rhizome.wallet.Wallet;
import rhizome.wallet.WalletClient;

/**
 * Forgeur de transactions signées pour les batteries d'exploits du testnet local.
 *
 * <p>Le wallet CLI refuse par construction ce qu'une attaque doit produire (montant négatif,
 * chainId étranger, gasLimit hors bornes, nonce arbitraire) : ses garde-fous CLIENT masqueraient
 * la porte de consensus qu'on veut atteindre. Ce forgeur signe EXACTEMENT ce qu'on lui demande,
 * avec les mêmes primitives que le wallet ({@link Wallet#signedSend} et consorts), et imprime la
 * transaction en JSON — la forme que {@code POST /add_transaction_json} accepte. Falsifier un
 * champ APRÈS signature (l'attaque « altéré sous signature ») se fait alors en éditant ce JSON,
 * sans code Java.
 *
 * <pre>
 *   Forge send     key=&lt;keyfile&gt; to=&lt;hex&gt; amount=&lt;u&gt; [fee=&lt;u&gt;] [node=&lt;url&gt;|chain=&lt;n&gt; nonce=&lt;n&gt;] [ts=&lt;ms&gt;]
 *   Forge contract key=... kind=DEPLOY|CALL [to=&lt;hex&gt;] data=&lt;hex&gt;|dataFile=&lt;path&gt; [value=] [gasLimit=] [gasPrice=]
 *   Forge box      key=... kind=BOX_CREATE|BOX_UPDATE|BOX_SPEND to=&lt;hex&gt; data=&lt;hex&gt; [value=] [fee=]
 *   Forge token    key=... kind=TOKEN_MINT|TOKEN_TRANSFER|TOKEN_BURN to=&lt;hex&gt; data=&lt;hex&gt; [fee=]
 *   Forge info     key=... node=&lt;url&gt;          -&gt; {"address":..,"chainId":..,"nextNonce":..,"balance":..}
 * </pre>
 *
 * <p>{@code node=} résout chainId et nonce sur le réseau ; {@code chain=}/{@code nonce=} les
 * forcent (c'est ce qui permet le rejeu inter-réseau et le nonce arbitraire).
 */
public final class Forge {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: Forge <send|contract|box|token|info> k=v ...");
            System.exit(2);
        }
        Map<String, String> a = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("argument attendu sous la forme clé=valeur: " + args[i]);
            }
            a.put(args[i].substring(0, eq), args[i].substring(eq + 1));
        }

        char[] passphrase = a.containsKey("pass")
            ? new String(Files.readAllBytes(Path.of(a.get("pass")))).trim().toCharArray() : null;
        Wallet wallet = Wallet.load(Path.of(req(a, "key")), passphrase);
        WalletClient client = a.containsKey("node") ? new WalletClient(a.get("node")) : null;

        if ("info".equals(args[0])) {
            var info = client.walletInfo(wallet.address());
            System.out.println("{\"address\":\"" + wallet.address().toHexString()
                + "\",\"chainId\":" + client.chainId()
                + ",\"nextNonce\":" + info.nextNonce()
                + ",\"balance\":" + info.balance() + "}");
            return;
        }

        int chainId = a.containsKey("chain") ? Integer.parseInt(a.get("chain")) : client.chainId();
        long nonce = a.containsKey("nonce")
            ? Long.parseLong(a.get("nonce")) : client.walletInfo(wallet.address()).nextNonce();
        long ts = num(a, "ts", System.currentTimeMillis());
        PublicAddress to = a.containsKey("to") ? PublicAddress.of(a.get("to")) : PublicAddress.empty();
        byte[] data = data(a);

        Transaction tx = switch (args[0]) {
            case "send" -> wallet.signedSend(to, new TransactionAmount(num(a, "amount", 0)),
                new TransactionAmount(num(a, "fee", 0)), chainId, nonce, ts);
            case "contract" -> wallet.signedContract(TransactionKind.valueOf(req(a, "kind")), to, data,
                num(a, "value", 0), num(a, "gasLimit", 100_000), num(a, "gasPrice", 1), chainId, nonce, ts);
            case "box" -> wallet.signedBox(TransactionKind.valueOf(req(a, "kind")), to, data,
                num(a, "value", 0), num(a, "fee", 0), chainId, nonce, ts);
            case "token" -> wallet.signedToken(TransactionKind.valueOf(req(a, "kind")), to, data,
                num(a, "fee", 0), chainId, nonce, ts);
            default -> throw new IllegalArgumentException("commande inconnue: " + args[0]);
        };
        System.out.println(tx.toJson().toString());
    }

    private static byte[] data(Map<String, String> a) throws Exception {
        if (a.containsKey("dataFile")) {
            return Files.readAllBytes(Path.of(a.get("dataFile")));
        }
        String hex = a.getOrDefault("data", "");
        return hex.isEmpty() ? new byte[0] : Utils.hexStringToByteArray(hex);
    }

    private static String req(Map<String, String> a, String k) {
        String v = a.get(k);
        if (v == null) {
            throw new IllegalArgumentException("argument requis manquant: " + k);
        }
        return v;
    }

    private static long num(Map<String, String> a, String k, long dflt) {
        return a.containsKey(k) ? Long.parseLong(a.get(k)) : dflt;
    }
}
