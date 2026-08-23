package rhizome.core.ledger;

import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONObject;

import rhizome.core.transaction.TransactionAmount;

/**
 * An address-to-balance snapshot of a ledger, used to seed the genesis state of a Rhizome
 * network — an operator-supplied file (via {@code RHIZOME_SNAPSHOT}), a network's shipped
 * default allocation artifact (see {@code NetworkParameters#genesisSnapshotResource()}), or the
 * empty default. Not Pandanite-specific: mainnet's shipped genesis is an explicit, authored
 * allocation, not a dump of an existing chain.
 *
 * <p>Balances are stored as unsigned 64-bit values (a legacy {@code uint64} amount
 * representation this format is compatible with, e.g. Pandanite's); the JSON form encodes each
 * as an unsigned decimal string so values above {@code Long.MAX_VALUE} round-trip losslessly.
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

    /**
     * Deterministic commitment to this snapshot's content: SHA-256 over
     * {@code address || amount} (amount big-endian) for every entry, sorted by
     * address bytes. Insertion order, source metadata and formatting do not
     * affect it. The genesis block header commits to this hash, binding the
     * chain to its seeded state.
     */
    public rhizome.crypto.SHA256Hash commitmentHash() {
        var digest = new org.bouncycastle.crypto.digests.SHA256Digest();
        balances.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(
                (a, b) -> java.util.Arrays.compareUnsigned(a.toBytes(), b.toBytes())))
            .forEach(entry -> {
                byte[] address = entry.getKey().toBytes();
                digest.update(address, 0, address.length);
                byte[] amount = rhizome.core.common.Utils.longToBytes(entry.getValue().amount());
                digest.update(amount, 0, amount.length);
            });
        byte[] out = new byte[rhizome.crypto.SHA256Hash.SIZE];
        digest.doFinal(out, 0);
        return rhizome.crypto.SHA256Hash.of(out);
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
            // The ledger treats balances as signed 64-bit (checked arithmetic, negative amounts
            // rejected everywhere in consensus), so an unsigned value with the high bit set parses
            // as a NEGATIVE long and would seed a negative genesis balance (audit F3). Reject it
            // here, at the only ingress point, rather than letting it reach the ledger.
            if (amount < 0) {
                throw new IllegalArgumentException(
                    "snapshot balance out of range (high bit set) for " + addressHex);
            }
            PublicAddress address = PublicAddress.of(addressHex);
            // Hex parsing is case-insensitive on the way IN (java.util.HexFormat) even though
            // toHexString renders uppercase on the way OUT, and org.json does not case-fold
            // object keys — so "AB.." and "ab.." are two textually-distinct JSON keys that
            // decode to the SAME PublicAddress (E2E-54). A plain put() would silently keep one
            // entry's amount and discard the other's (which survives depends on HashMap
            // iteration order, not the file), and for an unpinned profile nothing downstream
            // ever catches the discard. Reject the duplicate here, like the range guard above.
            if (snapshot.balances().containsKey(address)) {
                throw new IllegalArgumentException(
                    "duplicate address in snapshot balances (case-variant hex spellings decode "
                        + "to the same address): " + addressHex);
            }
            snapshot.put(address, new TransactionAmount(amount));
        }
        return snapshot;
    }
}
