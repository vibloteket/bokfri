package org.fribok.bookkeeping.service.outpayment;

import java.util.List;

/** Complete customer outpayment validation result. */
public record OutpaymentValidationResult(boolean valid, List<OutpaymentValidationIssue> issues) {
    public OutpaymentValidationResult { issues = List.copyOf(issues); }
}
