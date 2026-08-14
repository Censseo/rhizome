package rhizome.core.token;

import static rhizome.crypto.Hex.bytesToHex;

import java.util.Arrays;

/**
 * The 32-byte unique identifier of a native token: the {@code SHA-256} derived id
 * ({@code TokenMeta.deriveId}) that keys token metadata and every token balance.
 *
 * <p>Wrapping the raw {@code byte[]} gives the id a length guarantee (exactly 32
 * bytes — a token id must never be a 31- or 33-byte key in the state root), content
 * equality, and defensive copies in and out, on the {@link
 * rhizome.core.ledger.PublicAddress} model: the backing array is cloned in the
 * constructor and by {@link #toBytes()}, so mutating either side cannot corrupt the
 * value — or the equals/hashCode of every map keyed on it.
 */
public record TokenId(byte[] id) {

    public TokenId {
        if (id == null || id.length != SIZE) {
            throw new IllegalArgumentException("token id must be 32 bytes");
        }
        id = id.clone();
    }

    public static final int SIZE = 32;

    public static TokenId of(byte[] id) {
        return new TokenId(id);
    }

    /** The id bytes — a defensive copy, like the constructor's. */
    public byte[] toBytes() {
        return id.clone();
    }

    /** Lowercase hex of the id bytes — the wire/UI spelling (lowercase, like ApiResponses.hex). */
    public String toHexString() {
        return bytesToHex(id);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TokenId o)) {
            return false;
        }
        // Field access, not o.toBytes(): the accessor defensively clones, equality must not.
        return Arrays.equals(id, o.id);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(id);
    }

    @Override
    public String toString() {
        return bytesToHex(id);
    }
}
