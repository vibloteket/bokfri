package org.fribok.bookkeeping.service.supplier;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSSupplier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for UI-independent supplier validation. */
class SupplierValidatorTest {
    @Test
    void acceptsMinimalSupplier() {
        SSSupplier supplier = supplier("S100", "Leverantör AB", 1);
        assertThat(SupplierValidator.validate(supplier, List.of()).valid()).isTrue();
    }

    @Test
    void rejectsDuplicatesAndMissingName() {
        SSSupplier supplier = supplier("S100", " ", 1);
        SSSupplier existing = supplier("S100", "Befintlig", 1);
        SupplierValidationResult result = SupplierValidator.validate(supplier, List.of(existing));
        assertThat(result.issues()).extracting(SupplierValidationIssue::code)
                .contains("SUPPLIER_NUMBER_EXISTS", "SUPPLIER_NAME_REQUIRED",
                        "SUPPLIER_OUTPAYMENT_NUMBER_EXISTS");
    }

    private static SSSupplier supplier(String number, String name, int outpaymentNumber) {
        SSSupplier supplier = new SSSupplier();
        supplier.setNumber(number);
        supplier.setName(name);
        supplier.setOutpaymentNumber(outpaymentNumber);
        return supplier;
    }
}
