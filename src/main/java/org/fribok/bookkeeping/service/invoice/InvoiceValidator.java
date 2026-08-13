package org.fribok.bookkeeping.service.invoice;

import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** UI-independent customer invoice validation. */
public final class InvoiceValidator {
    private InvoiceValidator() {}

    public static InvoiceValidationResult validate(SSInvoice invoice) {
        return validate(invoice, null);
    }

    public static InvoiceValidationResult validate(SSInvoice invoice, List<SSAccount> accounts) {
        List<InvoiceValidationIssue> issues = new ArrayList<>();
        if (invoice == null) {
            issues.add(issue("INVOICE_REQUIRED", null, null, "Fakturan saknas."));
            return new InvoiceValidationResult(false, issues);
        }
        if (blank(invoice.getCustomerNr()) || blank(invoice.getCustomerName())) {
            issues.add(issue("INVOICE_CUSTOMER_REQUIRED", "customerNumber", null,
                    "Fakturan saknar en giltig kund."));
        }
        if (invoice.getLocalDate() == null) {
            issues.add(issue("INVOICE_DATE_REQUIRED", "date", null, "Fakturadatum saknas."));
        }
        if (invoice.getLocalDueDate() == null) {
            issues.add(issue("INVOICE_DUE_DATE_REQUIRED", "dueDate", null, "Förfallodatum saknas."));
        } else if (invoice.getLocalDate() != null
                && invoice.getLocalDueDate().isBefore(invoice.getLocalDate())) {
            issues.add(issue("INVOICE_DUE_DATE_INVALID", "dueDate", null,
                    "Förfallodatum får inte ligga före fakturadatum."));
        }
        if (invoice.getRows().isEmpty()) {
            issues.add(issue("INVOICE_ROWS_REQUIRED", "rows", null, "Fakturan saknar rader."));
        }
        for (int index = 0; index < invoice.getRows().size(); index++) {
            SSSaleRow row = invoice.getRows().get(index);
            int rowNumber = index + 1;
            if (row == null || blank(row.getDescription())) {
                issues.add(issue("INVOICE_ROW_DESCRIPTION_REQUIRED", "description", rowNumber,
                        "Fakturaraden saknar beskrivning."));
            }
            if (row == null || row.getQuantity() == null || row.getQuantity().signum() <= 0
                    || row.getQuantity().stripTrailingZeros().scale() > 6) {
                issues.add(issue("INVOICE_ROW_QUANTITY_INVALID", "quantity", rowNumber,
                        "Fakturaradens antal måste vara positivt och ha högst sex decimaler."));
            } else if (row.getProduct() != null && row.getProduct().isStockProduct()
                    && row.getQuantity().stripTrailingZeros().scale() > 0) {
                issues.add(issue("INVOICE_ROW_STOCK_QUANTITY_FRACTIONAL", "quantity", rowNumber,
                        "Antalet för en lagerförd produkt måste vara ett heltal."));
            }
            if (row == null || row.getUnitprice() == null || row.getUnitprice().signum() < 0) {
                issues.add(issue("INVOICE_ROW_PRICE_INVALID", "unitPrice", rowNumber,
                        "Fakturaradens enhetspris får inte vara negativt."));
            }
            BigDecimal discount = row == null ? null : row.getDiscount();
            if (discount != null && (discount.signum() < 0
                    || discount.compareTo(new BigDecimal("100")) > 0)) {
                issues.add(issue("INVOICE_ROW_DISCOUNT_INVALID", "discount", rowNumber,
                        "Fakturaradens rabatt måste vara mellan 0 och 100 procent."));
            }
            if (row == null || row.getAccountNr() == null || row.getAccountNr() <= 0) {
                issues.add(issue("INVOICE_ROW_ACCOUNT_REQUIRED", "salesAccount", rowNumber,
                        "Fakturaraden saknar försäljningskonto."));
            } else if (accounts != null && accounts.stream()
                    .noneMatch(account -> row.getAccountNr().equals(account.getNumber()))) {
                issues.add(issue("INVOICE_ROW_ACCOUNT_NOT_FOUND", "salesAccount", rowNumber,
                        "Fakturaradens försäljningskonto finns inte i valt räkenskapsår."));
            }
            if (row == null || row.getTaxCode() == null) {
                issues.add(issue("INVOICE_ROW_VAT_REQUIRED", "vatRate", rowNumber,
                        "Fakturaraden saknar momssats."));
            }
        }
        return new InvoiceValidationResult(issues.isEmpty(), issues);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static InvoiceValidationIssue issue(String code, String field, Integer row, String message) {
        return new InvoiceValidationIssue(code, field, row, message);
    }
}
