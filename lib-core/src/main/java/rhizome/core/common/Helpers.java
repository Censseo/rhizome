package rhizome.core.common;


import rhizome.core.transaction.TransactionAmount;

public class Helpers {

    private Helpers() {}

    public static TransactionAmount PDN(double amount) {
        // Reject inputs that would silently produce a negative or nonsensical base-unit amount: a
        // negative double, NaN, or a magnitude past the long range would truncate/overflow through the
        // (long) cast into a value the caller never intended (e.g. send(-5.0) building a negative-amount
        // tx that only fails much later at admission). Fail fast at construction instead (audit V6g).
        double scaled = amount * Constants.DECIMAL_SCALE_FACTOR;
        if (Double.isNaN(amount) || amount < 0 || scaled > Long.MAX_VALUE) {
            throw new IllegalArgumentException("invalid PDN amount: " + amount);
        }
        return new TransactionAmount((long) scaled);
    }

    /** Formats a base-unit amount as a decimal PDN string (e.g. 505000 -> "50.5"). */
    public static String toPDN(long baseUnits) {
        return java.math.BigDecimal.valueOf(baseUnits)
            .divide(java.math.BigDecimal.valueOf(Constants.DECIMAL_SCALE_FACTOR))
            .stripTrailingZeros()
            .toPlainString();
    }
}
