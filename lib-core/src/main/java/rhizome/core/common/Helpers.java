package rhizome.core.common;


import rhizome.core.transaction.TransactionAmount;

public class Helpers {

    private Helpers() {}

    public static TransactionAmount PDN(double amount) {
        return new TransactionAmount((long) (amount * Constants.DECIMAL_SCALE_FACTOR));
    }

    /** Formats a base-unit amount as a decimal PDN string (e.g. 505000 -> "50.5"). */
    public static String toPDN(long baseUnits) {
        return java.math.BigDecimal.valueOf(baseUnits)
            .divide(java.math.BigDecimal.valueOf(Constants.DECIMAL_SCALE_FACTOR))
            .stripTrailingZeros()
            .toPlainString();
    }
}
