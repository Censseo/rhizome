package rhizome.vm;

/**
 * Records a native-coin (PDN) payout a contract requests via the {@code transfer_value} host
 * function, after checking it against the contract's spendable balance. Returns {@code 0} on
 * success or {@code -1} if the transfer is rejected (unaffordable, malformed recipient, or the
 * host wired no balance source). Supplied per call frame by the contract processor.
 */
@FunctionalInterface
public interface NativeTransferHandler {
    int transfer(byte[] to, long amount);
}
