package org.fribok.bookkeeping.service.product;

import java.util.List;

/** Complete product validation result. */
public record ProductValidationResult(boolean valid, List<ProductValidationIssue> issues) {
    public ProductValidationResult { issues = List.copyOf(issues); }
}
