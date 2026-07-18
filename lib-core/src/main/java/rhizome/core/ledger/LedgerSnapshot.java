package rhizome.core.ledger;

import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONObject;

import rhizome.core.transaction.TransactionAmount;

/**
 * An address-to-balance snapshot of a ledger, used to seed the genesis state of
 * the clean Rhizome chain from the existing Pandanite chain.
 *
 * <p>Balances are stored as unsigned 64-bit values (Pandanite uses {@code uint64}
 * amounts); the JSON form encodes each as an unsigned decimal string so values
 * above {@code Long.MAX_VALUE} round-trip losslessly.
 */
public final class LedgerSnapshot {

    public static final int FORMAT_VERSION = 1;

    private final Map<PublicAddress, TransactionAmount> balances;
    private final String source;
    private final long sourceHeight;
    private final int chainId;

    public LedgerSnapshot(String source, long sourceHeight, int chainId) {
        this(new LinkedHashMap<>(), source, sourceHeight, chainId);
    }

    private LedgerSnapshot(Map<PublicAddress, TransactionAmount> balances,
                           String source, long sourceHeight, int chainId) {
        this.balances = balances;
        this.source = source;
        this.sourceHeight = sourceHeight;
        this.chainId = chainId;
    }

    public void put(PublicAddress address, TransactionAmount amount) {
        balances.put(address, amount);
    }

    public Map<PublicAddress, TransactionAmount> balances() {
        return balances;
    }

    public int size() {
        return balances.size();
    }

    public String source() {
        return source;
    }

    public long sourceHeight() {
        return sourceHeight;
    }

    public int chainId() {
        return chainId;
    }

    /** Sum of all balances, as an unsigned 64-bit total. */
    public long totalSupply() {
        long total = 0;
        for (TransactionAmount amount : balances.values()) {
            total += amount.amount();
        }
        return total;
    }

    public JSONObject toJson() {
        JSONObject balancesJson = new JSONObject();
        for (Map.Entry<PublicAddress, TransactionAmount> entry : balances.entrySet()) {
            balancesJson.put(entry.getKey().toHexString(),
                Long.toUnsignedString(entry.getValue().amount()));
        }
        JSONObject root = new JSONObject();
        root.put("version", FORMAT_VERSION);
        root.put("source", source);
        root.put("sourceHeight", sourceHeight);
        root.put("chainId", chainId);
        root.put("walletCount", balances.size());
        root.put("totalSupply", Long.toUnsignedString(totalSupply()));
        root.put("balances", balancesJson);
        return root;
    }

    public static LedgerSnapshot fromJson(JSONObject root) {
        int version = root.getInt("version");
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported snapshot version: " + version);
        }
        LedgerSnapshot snapshot = new LedgerSnapshot(
            root.optString("source", "unknown"),
            root.optLong("sourceHeight", 0),
            root.optInt("chainId", 0));
        JSONObject balancesJson = root.getJSONObject("balances");
        for (String addressHex : balancesJson.keySet()) {
            long amount = Long.parseUnsignedLong(balancesJson.getString(addressHex));
            snapshot.put(PublicAddress.of(addressHex), new TransactionAmount(amount));
        }
        return snapshot;
    }
}
