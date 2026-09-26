package org.fribok.bookkeeping.dataformat;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Canonical semantic values for fingerprint comparison at the staging storage contract. */
public final class CanonicalValues {
    /** Fractional digits retained by staging DECIMAL columns. */
    public static final int DECIMAL_SCALE = 30;

    private CanonicalValues() {}

    /**
     * Normalizes a decimal to the value the staging schema can store: at most 30 fractional
     * digits (half-up), trailing zeros removed, and all zero forms collapsed to {@code 0}.
     * Values read back from DECIMAL(100,30) columns are already normalized, so reapplying this
     * is idempotent.
     */
    public static BigDecimal amount(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal scaled = value.setScale(DECIMAL_SCALE, RoundingMode.HALF_UP);
        return scaled.signum() == 0 ? BigDecimal.ZERO : scaled.stripTrailingZeros();
    }
}
