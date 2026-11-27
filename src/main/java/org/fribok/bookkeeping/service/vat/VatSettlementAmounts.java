package org.fribok.bookkeeping.service.vat;

import se.swedsoft.bookkeeping.data.SSVoucher;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Exact amount observations only: no rounding, repairs, SQL or authorization to post. */
public final class VatSettlementAmounts {
    private VatSettlementAmounts() {}

    public enum Kind {
        MISSING_AMOUNT_SIDE,
        BOTH_AMOUNT_SIDES,
        NON_POSITIVE_AMOUNT,
        FRACTIONAL_CENT,
        STORAGE_RANGE,
        TOTAL_ENTRY_RANGE
    }

    public enum Side {
        DEBIT,
        CREDIT
    }

    /**
     * Frozen exact value. Row is one-based PROPOSED position, never an invented source row. Total
     * observations have no row/account. Side/value are absent for row-shape observations.
     */
    public record Finding(
            Kind kind, Integer proposedRow, Integer account, Side side, BigDecimal amount) {}

    /**
     * Observes exact proposed sides, SQL DECIMAL(100,30) range and existing manual-entry total
     * range. Does not validate accounts, dimensions, source history, periods or all bookability.
     *
     * @param proposal explicit proposal, read only through raw account/debit/credit getters
     * @return immutable findings in row order (shape, debit, credit), then debit/credit totals
     */
    public static List<Finding> inspect(SSVoucher proposal) {
        Objects.requireNonNull(proposal, "explicit proposal");
        var result = new ArrayList<Finding>();
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        int index = 0;
        for (var row : proposal.getRows()) {
            index++;
            Objects.requireNonNull(row, "proposed row");
            Integer account = row.getAccountNr();
            BigDecimal d = row.getDebet();
            BigDecimal c = row.getCredit();
            if (d == null && c == null) {
                result.add(new Finding(Kind.MISSING_AMOUNT_SIDE, index, account, null, null));
            } else if (d != null && c != null) {
                result.add(new Finding(Kind.BOTH_AMOUNT_SIDES, index, account, null, null));
            }
            observe(result, index, account, Side.DEBIT, d);
            observe(result, index, account, Side.CREDIT, c);
            if (d != null) debit = debit.add(d);
            if (c != null) credit = credit.add(c);
        }
        if (integerDigits(debit) > 70) {
            result.add(new Finding(Kind.TOTAL_ENTRY_RANGE, null, null, Side.DEBIT, debit));
        }
        if (integerDigits(credit) > 70) {
            result.add(new Finding(Kind.TOTAL_ENTRY_RANGE, null, null, Side.CREDIT, credit));
        }
        return List.copyOf(result);
    }

    private static void observe(
            List<Finding> result, int row, Integer account, Side side, BigDecimal value) {
        if (value == null) return;
        if (value.signum() <= 0) {
            result.add(new Finding(Kind.NON_POSITIVE_AMOUNT, row, account, side, value));
        }
        if (!exactScale(value, 2)) {
            result.add(new Finding(Kind.FRACTIONAL_CENT, row, account, side, value));
        }
        if (integerDigits(value) > 70 || !exactScale(value, 30)) {
            result.add(new Finding(Kind.STORAGE_RANGE, row, account, side, value));
        }
    }

    private static long integerDigits(BigDecimal value) {
        return value.signum() == 0 ? 0 : value.precision() - (long) value.scale();
    }

    private static boolean exactScale(BigDecimal value, int scale) {
        if (value.signum() == 0 || value.scale() <= scale) return true;
        try {
            value.setScale(scale, RoundingMode.UNNECESSARY);
            return true;
        } catch (ArithmeticException notExact) {
            return false;
        }
    }
}
