package org.fribok.bookkeeping.service.inpayment;

import java.util.List;

/** Complete customer inpayment validation result. */
public record InpaymentValidationResult(boolean valid, List<InpaymentValidationIssue> issues) {
    public InpaymentValidationResult { issues = List.copyOf(issues); }
}
