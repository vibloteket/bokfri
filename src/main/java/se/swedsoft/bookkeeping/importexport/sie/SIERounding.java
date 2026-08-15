package se.swedsoft.bookkeeping.importexport.sie;

import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Calculates non-persistent balancing adjustments for two-decimal SIE output. */
public final class SIERounding {
    private SIERounding() {}

    /** Returns the signed SIE adjustment, or zero when the rounded voucher is balanced. */
    public static BigDecimal voucherAdjustment(SSVoucher voucher) {
        BigDecimal sum = voucher.getRows().stream()
                .filter(row -> row.isValid() && !row.isCrossed())
                .map(SSVoucherMath::getDebetMinusCredit)
                .map(SIERounding::money)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.negate();
    }

    public static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
