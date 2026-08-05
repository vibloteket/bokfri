package org.fribok.bookkeeping.service.product;

import se.swedsoft.bookkeeping.data.SSProduct;

import java.util.ArrayList;
import java.util.List;

/** UI-independent product validation shared by Swing and automation. */
public final class ProductValidator {
    private ProductValidator() {}

    public static ProductValidationResult validate(SSProduct product, List<SSProduct> existing) {
        List<ProductValidationIssue> issues = new ArrayList<>();
        if (product == null) {
            issues.add(issue("PRODUCT_REQUIRED", null, "Produkten saknas."));
            return new ProductValidationResult(false, issues);
        }
        if (blank(product.getNumber())) {
            issues.add(issue("PRODUCT_NUMBER_REQUIRED", "number", "Produktnummer saknas."));
        } else if (existing.stream().anyMatch(item -> product.getNumber().equals(item.getNumber()))) {
            issues.add(issue("PRODUCT_NUMBER_EXISTS", "number",
                    "Produktnummer " + product.getNumber() + " används redan."));
        }
        if (blank(product.getDescription())) {
            issues.add(issue("PRODUCT_DESCRIPTION_REQUIRED", "description", "Produktbeskrivning saknas."));
        }
        if (product.getSellingPrice() == null || product.getSellingPrice().signum() < 0) {
            issues.add(issue("PRODUCT_PRICE_INVALID", "sellingPrice",
                    "Försäljningspriset får inte vara negativt."));
        }
        if (product.getTaxCode() == null) {
            issues.add(issue("PRODUCT_VAT_REQUIRED", "vatRate", "Momssats saknas."));
        }
        Integer salesAccount = product.getDefaultAccount(
                se.swedsoft.bookkeeping.data.common.SSDefaultAccount.Sales);
        if (salesAccount == null || salesAccount <= 0) {
            issues.add(issue("PRODUCT_SALES_ACCOUNT_REQUIRED", "salesAccount",
                    "Försäljningskonto saknas."));
        }
        return new ProductValidationResult(issues.isEmpty(), issues);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ProductValidationIssue issue(String code, String field, String message) {
        return new ProductValidationIssue(code, field, message);
    }
}
