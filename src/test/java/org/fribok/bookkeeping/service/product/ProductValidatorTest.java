package org.fribok.bookkeeping.service.product;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;
import se.swedsoft.bookkeeping.data.common.SSTaxCode;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for UI-independent product validation. */
class ProductValidatorTest {
    @Test
    void acceptsMinimalProductWithInheritedDefaults() {
        SSProduct product = product("CONSULTING", "Konsultarbete");

        ProductValidationResult result = ProductValidator.validate(product, List.of());

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsMissingFieldsAndDuplicateNumber() {
        SSProduct product = product("CONSULTING", " ");
        product.setSellingPrice(new BigDecimal("-1"));
        SSProduct existing = product("CONSULTING", "Befintlig");

        ProductValidationResult result = ProductValidator.validate(product, List.of(existing));

        assertThat(result.issues()).extracting(ProductValidationIssue::code)
                .contains("PRODUCT_NUMBER_EXISTS", "PRODUCT_DESCRIPTION_REQUIRED",
                        "PRODUCT_PRICE_INVALID");
    }

    private static SSProduct product(String number, String description) {
        SSProduct product = new SSProduct();
        product.setNumber(number);
        product.setDescription(description);
        product.setSellingPrice(BigDecimal.ZERO);
        product.setTaxCode(SSTaxCode.TAXRATE_1);
        product.setDefaultAccount(SSDefaultAccount.Sales, 3001);
        return product;
    }
}
