package rhizome.crypto;

/**
 * Proof-of-work hash function used by a chain.
 *
 * The clean Rhizome chain uses {@link #PUFFERFISH2} from genesis. {@link #SHA256}
 * exists for tests and for replaying/validating pre-Pufferfish Pandanite history.
 */
public enum PowAlgorithm {
    SHA256,
    PUFFERFISH2
}
