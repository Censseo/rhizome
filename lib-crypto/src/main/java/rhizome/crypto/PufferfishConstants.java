package rhizome.crypto;

/**
 * Sizing constants for the Pufferfish2 encoded-hash buffer, matching the
 * reference C macros. Kept for callers that need the buffer length; the actual
 * algorithm lives in {@link Pufferfish2}.
 */
public final class PufferfishConstants {

    public static final int PF_DIGEST_LENGTH = Pufferfish2.PF_DIGEST_LENGTH;
    public static final int PF_SALTSPACE = Pufferfish2.PF_SALTSPACE;
    public static final int PF_HASHSPACE = Pufferfish2.PF_HASHSPACE;

    private PufferfishConstants() {}
}
