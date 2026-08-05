package org.fribok.bookkeeping.service.invoice;

import java.util.List;

/** Complete customer invoice validation result. */
public record InvoiceValidationResult(boolean valid, List<InvoiceValidationIssue> issues) {
    public InvoiceValidationResult {
        issues = List.copyOf(issues);
    }
}
