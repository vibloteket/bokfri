package org.fribok.bookkeeping.service.customer;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSCustomer;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for UI-independent customer validation. */
class CustomerValidatorTest {
    @Test
    void acceptsMinimalCustomer() {
        SSCustomer customer = customer("1001", "Exempel AB");

        CustomerValidationResult result = CustomerValidator.validate(customer, List.of());

        assertThat(result.valid()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void rejectsMissingRequiredFieldsAndDuplicateNumber() {
        SSCustomer customer = customer("1001", " ");
        SSCustomer existing = customer("1001", "Befintlig kund");

        CustomerValidationResult result = CustomerValidator.validate(customer, List.of(existing));

        assertThat(result.issues()).extracting(CustomerValidationIssue::code)
                .containsExactly("CUSTOMER_NUMBER_EXISTS", "CUSTOMER_NAME_REQUIRED");
    }

    @Test
    void rejectsDiscountOutsidePercentageRange() {
        SSCustomer customer = customer("1001", "Exempel AB");
        customer.setDiscount(new BigDecimal("100.01"));

        CustomerValidationResult result = CustomerValidator.validate(customer, List.of());

        assertThat(result.issues()).extracting(CustomerValidationIssue::code)
                .containsExactly("CUSTOMER_DISCOUNT_INVALID");
    }

    private static SSCustomer customer(String number, String name) {
        SSCustomer customer = new SSCustomer();
        customer.setNumber(number);
        customer.setName(name);
        return customer;
    }
}
