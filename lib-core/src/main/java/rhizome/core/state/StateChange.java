package rhizome.core.state;

/**
 * One committed change to authenticated state: set {@code rawKey} in {@code domain} to
 * {@code value}, or delete it when {@code value} is null. The accumulator maps it to an SMT
 * key/value-hash ({@link StateKeys}) and folds it into the state root.
 */
public record StateChange(byte domain, byte[] rawKey, byte[] value) {

    public static StateChange set(byte domain, byte[] rawKey, byte[] value) {
        return new StateChange(domain, rawKey, value);
    }

    public static StateChange delete(byte domain, byte[] rawKey) {
        return new StateChange(domain, rawKey, null);
    }
}
