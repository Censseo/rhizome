package rhizome.core.blockchain;

/**
 * The subset of consensus parameters that miners can adjust by voting (§ miner voting):
 * the box {@code storageFeeFactor} (rent per byte) and {@code minValuePerByte} (anti-dust
 * deposit per byte). A mutable holder the {@link ChainEngine} updates at each voting-epoch
 * boundary and the box processor reads at execution time, so a block is validated with the
 * values in effect at its height. Everything else in {@link NetworkParameters} is fixed.
 *
 * <p>A header carries an {@code int} vote: {@code 0} abstains, {@code ±1} raises/lowers
 * {@code storageFeeFactor}, {@code ±2} raises/lowers {@code minValuePerByte}. At an epoch
 * boundary a parameter moves one step (bounded) when the net vote exceeds half the epoch.
 */
public final class VoteableParams {

    public static final int ABSTAIN = 0;
    public static final int STORAGE_FEE_FACTOR = 1;
    public static final int MIN_VALUE_PER_BYTE = 2;

    private volatile long storageFeeFactor;
    private volatile long minValuePerByte;

    public VoteableParams(long storageFeeFactor, long minValuePerByte) {
        this.storageFeeFactor = storageFeeFactor;
        this.minValuePerByte = minValuePerByte;
    }

    /** The votable defaults from a network config. */
    public static VoteableParams fromDefaults(NetworkParameters params) {
        return new VoteableParams(params.storageFeeFactor(), params.minValuePerByte());
    }

    public long storageFeeFactor() {
        return storageFeeFactor;
    }

    public long minValuePerByte() {
        return minValuePerByte;
    }

    public void set(long storageFeeFactor, long minValuePerByte) {
        this.storageFeeFactor = storageFeeFactor;
        this.minValuePerByte = minValuePerByte;
    }

    public VoteableParams copy() {
        return new VoteableParams(storageFeeFactor, minValuePerByte);
    }

    @Override
    public String toString() {
        return "VoteableParams[storageFeeFactor=" + storageFeeFactor + ", minValuePerByte=" + minValuePerByte + "]";
    }
}
