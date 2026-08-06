package org.fribok.bookkeeping.service.supplierinvoice;
import java.util.List;
public record SupplierInvoiceValidationResult(boolean valid, List<SupplierInvoiceValidationIssue> issues) {
    public SupplierInvoiceValidationResult { issues=List.copyOf(issues); }
}
