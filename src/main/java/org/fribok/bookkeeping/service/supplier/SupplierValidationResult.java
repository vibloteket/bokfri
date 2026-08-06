package org.fribok.bookkeeping.service.supplier;

import java.util.List;

/** Complete supplier validation result. */
public record SupplierValidationResult(boolean valid, List<SupplierValidationIssue> issues) {
    public SupplierValidationResult { issues = List.copyOf(issues); }
}
