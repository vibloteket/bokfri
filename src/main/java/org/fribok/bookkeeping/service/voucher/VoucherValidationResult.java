package org.fribok.bookkeeping.service.voucher;

import java.math.BigDecimal;
import java.util.List;

/** Complete validation result, including totals useful for previews. */
public record VoucherValidationResult(boolean valid, List<VoucherValidationIssue> issues,
                                      BigDecimal debitTotal, BigDecimal creditTotal) {
    public VoucherValidationResult {
        issues = List.copyOf(issues);
    }
}
