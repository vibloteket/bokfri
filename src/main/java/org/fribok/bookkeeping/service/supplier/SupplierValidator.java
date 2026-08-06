package org.fribok.bookkeeping.service.supplier;

import se.swedsoft.bookkeeping.data.SSSupplier;

import java.util.ArrayList;
import java.util.List;

/** UI-independent supplier validation shared by Swing and automation. */
public final class SupplierValidator {
    private SupplierValidator() {}

    public static SupplierValidationResult validate(SSSupplier supplier, List<SSSupplier> existing) {
        List<SupplierValidationIssue> issues = new ArrayList<>();
        if (supplier == null) {
            issues.add(issue("SUPPLIER_REQUIRED", null, "Leverantören saknas."));
            return new SupplierValidationResult(false, issues);
        }
        if (blank(supplier.getNumber())) {
            issues.add(issue("SUPPLIER_NUMBER_REQUIRED", "number", "Leverantörsnummer saknas."));
        } else if (existing.stream().anyMatch(item -> supplier.getNumber().equals(item.getNumber()))) {
            issues.add(issue("SUPPLIER_NUMBER_EXISTS", "number",
                    "Leverantörsnummer " + supplier.getNumber() + " används redan."));
        }
        if (blank(supplier.getName())) {
            issues.add(issue("SUPPLIER_NAME_REQUIRED", "name", "Leverantörsnamn saknas."));
        }
        if (supplier.getOutpaymentNumber() == null || supplier.getOutpaymentNumber() <= 0) {
            issues.add(issue("SUPPLIER_OUTPAYMENT_NUMBER_REQUIRED", "outpaymentNumber",
                    "Utbetalningsnummer måste vara positivt."));
        } else if (existing.stream().anyMatch(item -> supplier.getOutpaymentNumber()
                .equals(item.getOutpaymentNumber()))) {
            issues.add(issue("SUPPLIER_OUTPAYMENT_NUMBER_EXISTS", "outpaymentNumber",
                    "Utbetalningsnumret används redan."));
        }
        return new SupplierValidationResult(issues.isEmpty(), issues);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static SupplierValidationIssue issue(String code, String field, String message) {
        return new SupplierValidationIssue(code, field, message);
    }
}
