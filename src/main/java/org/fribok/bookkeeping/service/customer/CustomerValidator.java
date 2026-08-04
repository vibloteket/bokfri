package org.fribok.bookkeeping.service.customer;

import se.swedsoft.bookkeeping.data.SSCustomer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** UI-independent customer validation shared by Swing and automation. */
public final class CustomerValidator {
    private CustomerValidator() {}

    public static CustomerValidationResult validate(SSCustomer customer, List<SSCustomer> existing) {
        List<CustomerValidationIssue> issues = new ArrayList<>();
        if (customer == null) {
            issues.add(issue("CUSTOMER_REQUIRED", null, "Kunden saknas."));
            return new CustomerValidationResult(false, issues);
        }
        if (blank(customer.getNumber())) {
            issues.add(issue("CUSTOMER_NUMBER_REQUIRED", "number", "Kundnummer saknas."));
        } else if (existing.stream().anyMatch(item -> customer.getNumber().equals(item.getNumber()))) {
            issues.add(issue("CUSTOMER_NUMBER_EXISTS", "number",
                    "Kundnummer " + customer.getNumber() + " används redan."));
        }
        if (blank(customer.getName())) {
            issues.add(issue("CUSTOMER_NAME_REQUIRED", "name", "Kundnamn saknas."));
        }
        BigDecimal discount = customer.getDiscount();
        if (discount != null && (discount.signum() < 0
                || discount.compareTo(new BigDecimal("100")) > 0)) {
            issues.add(issue("CUSTOMER_DISCOUNT_INVALID", "discount",
                    "Rabatten måste vara mellan 0 och 100 procent."));
        }
        return new CustomerValidationResult(issues.isEmpty(), issues);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static CustomerValidationIssue issue(String code, String field, String message) {
        return new CustomerValidationIssue(code, field, message);
    }
}
