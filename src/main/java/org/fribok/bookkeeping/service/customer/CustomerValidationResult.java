package org.fribok.bookkeeping.service.customer;

import java.util.List;

/** Complete customer validation result. */
public record CustomerValidationResult(boolean valid, List<CustomerValidationIssue> issues) {
    public CustomerValidationResult {
        issues = List.copyOf(issues);
    }
}
