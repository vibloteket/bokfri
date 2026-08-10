package org.fribok.bookkeeping.service.invoice;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.base.SSSaleRow;
import se.swedsoft.bookkeeping.data.common.SSTaxCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for UI-independent customer invoice validation. */
class InvoiceValidatorTest {
    @Test
    void acceptsMinimalProductLikeInvoice() {
        SSInvoice invoice = validInvoice();

        InvoiceValidationResult result = InvoiceValidator.validate(invoice);

        assertThat(result.valid()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void reportsRequiredHeaderAndRowFields() {
        SSInvoice invoice = new SSInvoice();
        invoice.setLocalDate(null);
        invoice.setLocalDueDate(null);
        invoice.setRows(List.of(new SSSaleRow()));

        InvoiceValidationResult result = InvoiceValidator.validate(invoice);

        assertThat(result.issues()).extracting(InvoiceValidationIssue::code)
                .contains("INVOICE_CUSTOMER_REQUIRED", "INVOICE_DATE_REQUIRED",
                        "INVOICE_DUE_DATE_REQUIRED", "INVOICE_ROW_DESCRIPTION_REQUIRED",
                        "INVOICE_ROW_QUANTITY_INVALID", "INVOICE_ROW_PRICE_INVALID",
                        "INVOICE_ROW_ACCOUNT_REQUIRED", "INVOICE_ROW_VAT_REQUIRED");
    }

    @Test
    void rejectsMissingSalesAccount() {
        SSInvoice invoice = validInvoice();
        invoice.getRows().get(0).setAccount(null);

        InvoiceValidationResult result = InvoiceValidator.validate(invoice,
                List.of(new SSAccount(3001)));

        assertThat(result.issues()).extracting(InvoiceValidationIssue::code)
                .containsExactly("INVOICE_ROW_ACCOUNT_REQUIRED");
    }

    @Test
    void rejectsSalesAccountOutsideSelectedAccountingYear() {
        SSInvoice invoice = validInvoice();
        invoice.getRows().get(0).setAccountNr(9999);

        InvoiceValidationResult result = InvoiceValidator.validate(invoice,
                List.of(new SSAccount(3001)));

        assertThat(result.issues()).extracting(InvoiceValidationIssue::code)
                .containsExactly("INVOICE_ROW_ACCOUNT_NOT_FOUND");
    }

    @Test
    void rejectsDueDateBeforeInvoiceDate() {
        SSInvoice invoice = validInvoice();
        invoice.setLocalDueDate(invoice.getLocalDate().minusDays(1));

        InvoiceValidationResult result = InvoiceValidator.validate(invoice);

        assertThat(result.issues()).extracting(InvoiceValidationIssue::code)
                .containsExactly("INVOICE_DUE_DATE_INVALID");
    }

    private static SSInvoice validInvoice() {
        SSInvoice invoice = new SSInvoice();
        invoice.setCustomerNr("1001");
        invoice.setCustomerName("Exempel AB");
        invoice.setLocalDate(LocalDate.of(2026, 8, 5));
        invoice.setLocalDueDate(LocalDate.of(2026, 9, 4));
        SSSaleRow row = new SSSaleRow();
        row.setDescription("Konsultarbete");
        row.setQuantity(1);
        row.setUnitprice(new BigDecimal("1000"));
        row.setAccountNr(3001);
        row.setTaxCode(SSTaxCode.TAXRATE_1);
        invoice.setRows(List.of(row));
        return invoice;
    }
}
