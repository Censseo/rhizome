package rhizome.core.box;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import rhizome.core.ledger.PublicAddress;

/**
 * A data box: an on-chain, addressable state object that stores information for
 * accounts and contracts (§ data-boxes spec). Unlike Ergo's content-addressed
 * UTXO, a Rhizome box has a <em>stable</em> id for its whole life — created once,
 * mutated in place by its owner — so an agent can reference "its memory" or "the
 * oracle box" permanently.
 *
 * <p>Canonical serialization (the size charged for min-value and rent, and — once
 * the state root lands — the basis of the leaf commitment):
 * <pre>
 * id(32) || owner(25) || value(8) || createdHeight(8) || rentPaidHeight(8)
 *   || regCount(1) || reg[0] || ... || reg[n-1]
 * reg[i] = typeTag(1) || len(2, BE unsigned) || payload(len)
 * </pre>
 * All integers big-endian, matching the rest of the core codec.
 */
public final class Box {

    /** Fixed header size before the registers: id + owner + value + created + rentPaid + regCount. */
    public static final int HEADER_SIZE = 32 + PublicAddress.SIZE + 8 + 8 + 8 + 1;

    /** Domain tag mixed into id derivation so a box id can never collide with a contract address. */
    private static final byte[] ID_DOMAIN = {'r', 'z', 'b', 'o', 'x'};

    private final byte[] id;
    private final PublicAddress owner;
    private final long value;
    private final long createdHeight;
    private final long rentPaidHeight;
    private final List<BoxRegister> registers;

    public Box(byte[] id, PublicAddress owner, long value, long createdHeight,
               long rentPaidHeight, List<BoxRegister> registers) {
        if (id == null || id.length != 32) {
            throw new IllegalArgumentException("box id must be 32 bytes");
        }
        this.id = id.clone();
        this.owner = owner;
        this.value = value;
        this.createdHeight = createdHeight;
        this.rentPaidHeight = rentPaidHeight;
        this.registers = List.copyOf(registers);
    }

    /**
     * Deterministic box id: {@code SHA-256(creator || nonce || "rzbox")}. The
     * account nonce is strictly increasing, so a creator's box ids never repeat;
     * the domain suffix separates them from {@code Contracts.deriveAddress}.
     */
    public static byte[] deriveId(PublicAddress creator, long nonce) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(creator.toBytes());
            sha.update(ByteBuffer.allocate(Long.BYTES).putLong(nonce).array());
            sha.update(ID_DOMAIN);
            return sha.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public byte[] id() {
        return id.clone();
    }

    public PublicAddress owner() {
        return owner;
    }

    public long value() {
        return value;
    }

    public long createdHeight() {
        return createdHeight;
    }

    public long rentPaidHeight() {
        return rentPaidHeight;
    }

    public List<BoxRegister> registers() {
        return registers;
    }

    /** Height at and after which this box may be charged storage rent. */
    public long expiryHeight(long storagePeriodBlocks) {
        return rentPaidHeight + storagePeriodBlocks;
    }

    /** A copy with a new value and register set, {@code rentPaidHeight} reset to {@code height} (an update). */
    public Box updated(long newValue, List<BoxRegister> newRegisters, long height) {
        return new Box(id, owner, newValue, createdHeight, height, newRegisters);
    }

    /** A copy with reduced value and the rent clock reset to {@code height} (a rent charge). */
    public Box afterRent(long newValue, long height) {
        return new Box(id, owner, newValue, createdHeight, height, registers);
    }

    public int serializedSize() {
        int size = HEADER_SIZE;
        for (BoxRegister r : registers) {
            size += r.serializedSize();
        }
        return size;
    }

    public byte[] serialize() {
        ByteBuffer buffer = ByteBuffer.allocate(serializedSize());
        buffer.put(id);
        buffer.put(owner.toBytes());
        buffer.putLong(value);
        buffer.putLong(createdHeight);
        buffer.putLong(rentPaidHeight);
        buffer.put((byte) registers.size());
        for (BoxRegister r : registers) {
            buffer.put(r.type().code());
            buffer.putShort((short) r.payload().length);
            buffer.put(r.payload());
        }
        return buffer.array();
    }

    public static Box deserialize(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        try {
            byte[] id = new byte[32];
            buffer.get(id);
            byte[] ownerBytes = new byte[PublicAddress.SIZE];
            buffer.get(ownerBytes);
            long value = buffer.getLong();
            long createdHeight = buffer.getLong();
            long rentPaidHeight = buffer.getLong();
            int regCount = buffer.get() & 0xFF;
            List<BoxRegister> registers = new ArrayList<>(regCount);
            for (int i = 0; i < regCount; i++) {
                BoxRegisterType type = BoxRegisterType.fromCode(buffer.get());
                int len = buffer.getShort() & 0xFFFF;
                byte[] payload = new byte[len];
                buffer.get(payload);
                if (type == null) {
                    throw new IllegalArgumentException("unknown box register tag");
                }
                registers.add(new BoxRegister(type, payload));
            }
            return new Box(id, PublicAddress.of(ownerBytes), value, createdHeight, rentPaidHeight, registers);
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("truncated box", e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Box other
            && Arrays.equals(id, other.id)
            && value == other.value
            && createdHeight == other.createdHeight
            && rentPaidHeight == other.rentPaidHeight
            && owner.equals(other.owner)
            && registers.equals(other.registers);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(id);
    }

    @Override
    public String toString() {
        return "Box[" + rhizome.core.common.Utils.bytesToHex(id) + ", value=" + value
            + ", registers=" + registers.size() + "]";
    }
}
