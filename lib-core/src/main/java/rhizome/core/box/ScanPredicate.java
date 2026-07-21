package rhizome.core.box;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import rhizome.core.common.Utils;

/**
 * A declarative filter over {@link Box data boxes} (EIP-1 style), so an app or agent can
 * ask the node "track/return the boxes matching this shape" without bespoke node code.
 * Composable: leaf predicates on the owner or a register, combined with {@code and}/{@code or}.
 *
 * <p>JSON forms:
 * <pre>
 *   {"type":"owner","owner":"&lt;hex25&gt;"}
 *   {"type":"registerEquals","index":0,"value":"&lt;hex&gt;"}
 *   {"type":"registerContains","index":0,"value":"&lt;hex&gt;"}
 *   {"type":"and","parts":[ ... ]}
 *   {"type":"or","parts":[ ... ]}
 * </pre>
 */
public sealed interface ScanPredicate {

    boolean test(Box box);

    JSONObject toJson();

    /** The owner this predicate constrains a match to, if any (enables the owner-index fast path). */
    default byte[] ownerAnchor() {
        return null;
    }

    /** Max nesting of and/or predicates accepted from untrusted JSON, so a deeply nested
     *  predicate cannot blow the Java stack at parse or evaluation time (audit L9). */
    int MAX_PREDICATE_DEPTH = 32;
    /** Max children of a single and/or, bounding a wide (non-deep) predicate. */
    int MAX_PREDICATE_PARTS = 64;

    static ScanPredicate fromJson(JSONObject o) {
        return fromJson(o, 0);
    }

    private static ScanPredicate fromJson(JSONObject o, int depth) {
        if (depth > MAX_PREDICATE_DEPTH) {
            throw new IllegalArgumentException(
                "scan predicate nested too deep (max " + MAX_PREDICATE_DEPTH + ")");
        }
        String type = o.getString("type");
        return switch (type) {
            case "owner" -> new OwnerEquals(Utils.hexStringToByteArray(o.getString("owner")));
            case "registerEquals" -> new RegisterEquals(o.getInt("index"),
                Utils.hexStringToByteArray(o.getString("value")));
            case "registerContains" -> new RegisterContains(o.getInt("index"),
                Utils.hexStringToByteArray(o.getString("value")));
            case "and" -> new And(parts(o, depth));
            case "or" -> new Or(parts(o, depth));
            default -> throw new IllegalArgumentException("unknown scan predicate: " + type);
        };
    }

    private static List<ScanPredicate> parts(JSONObject o, int depth) {
        JSONArray arr = o.getJSONArray("parts");
        if (arr.isEmpty()) {
            throw new IllegalArgumentException("and/or requires at least one part");
        }
        if (arr.length() > MAX_PREDICATE_PARTS) {
            throw new IllegalArgumentException(
                "and/or has too many parts (max " + MAX_PREDICATE_PARTS + ")");
        }
        List<ScanPredicate> parts = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            parts.add(fromJson(arr.getJSONObject(i), depth + 1));
        }
        return parts;
    }

    /** Matches boxes owned by a specific address. */
    record OwnerEquals(byte[] owner) implements ScanPredicate {
        @Override
        public boolean test(Box box) {
            return Arrays.equals(box.owner().toBytes(), owner);
        }

        @Override
        public byte[] ownerAnchor() {
            return owner;
        }

        @Override
        public JSONObject toJson() {
            return new JSONObject().put("type", "owner").put("owner", Utils.bytesToHex(owner));
        }
    }

    /** Matches boxes whose register at {@code index} equals {@code value} exactly. */
    record RegisterEquals(int index, byte[] value) implements ScanPredicate {
        @Override
        public boolean test(Box box) {
            return index >= 0 && index < box.registers().size()
                && Arrays.equals(box.registers().get(index).payload(), value);
        }

        @Override
        public JSONObject toJson() {
            return new JSONObject().put("type", "registerEquals")
                .put("index", index).put("value", Utils.bytesToHex(value));
        }
    }

    /** Matches boxes whose register at {@code index} contains {@code value} as a sub-sequence. */
    record RegisterContains(int index, byte[] value) implements ScanPredicate {
        @Override
        public boolean test(Box box) {
            if (index < 0 || index >= box.registers().size()) {
                return false;
            }
            return indexOf(box.registers().get(index).payload(), value) >= 0;
        }

        @Override
        public JSONObject toJson() {
            return new JSONObject().put("type", "registerContains")
                .put("index", index).put("value", Utils.bytesToHex(value));
        }
    }

    /** Conjunction: a box matches when every part matches. */
    record And(List<ScanPredicate> parts) implements ScanPredicate {
        @Override
        public boolean test(Box box) {
            return parts.stream().allMatch(p -> p.test(box));
        }

        @Override
        public byte[] ownerAnchor() {
            for (ScanPredicate p : parts) {
                byte[] a = p.ownerAnchor();
                if (a != null) {
                    return a;
                }
            }
            return null;
        }

        @Override
        public JSONObject toJson() {
            return combinator("and", parts);
        }
    }

    /** Disjunction: a box matches when any part matches. */
    record Or(List<ScanPredicate> parts) implements ScanPredicate {
        @Override
        public boolean test(Box box) {
            return parts.stream().anyMatch(p -> p.test(box));
        }

        @Override
        public JSONObject toJson() {
            return combinator("or", parts);
        }
    }

    private static JSONObject combinator(String type, List<ScanPredicate> parts) {
        JSONArray arr = new JSONArray();
        parts.forEach(p -> arr.put(p.toJson()));
        return new JSONObject().put("type", type).put("parts", arr);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (needle.length == 0) {
            return 0;
        }
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
