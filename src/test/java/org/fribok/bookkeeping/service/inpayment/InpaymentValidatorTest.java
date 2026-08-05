package org.fribok.bookkeeping.service.inpayment;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSInpayment;
import se.swedsoft.bookkeeping.data.SSInpaymentRow;
import se.swedsoft.bookkeeping.data.SSInvoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for UI-independent customer inpayment validation. */
class InpaymentValidatorTest {
    @Test
    void acceptsPaymentOfBookedInvoice() {
        SSInvoice invoice = invoice();
        SSInpayment item = inpayment(invoice, "100");
        assertThat(InpaymentValidator.validate(item, List.of(invoice)).valid()).isTrue();
    }

    @Test
    void rejectsUnbookedInvoiceAndInvalidAmount() {
        SSInvoice invoice = invoice();
        invoice.setEntered(false);
        SSInpayment item = inpayment(invoice, "0");
        InpaymentValidationResult result = InpaymentValidator.validate(item, List.of(invoice));
        assertThat(result.issues()).extracting(InpaymentValidationIssue::code)
                .contains("INPAYMENT_INVOICE_NOT_BOOKED", "INPAYMENT_AMOUNT_INVALID");
    }

    private static SSInvoice invoice() {
        SSInvoice invoice = new SSInvoice();
        invoice.setNumber(42);
        invoice.setCustomerNr("1001");
        invoice.setCustomerName("Exempel AB");
        invoice.setEntered(true);
        return invoice;
    }

    private static SSInpayment inpayment(SSInvoice invoice, String amount) {
        SSInpayment item = new SSInpayment();
        item.setLocalDate(LocalDate.of(2026, 8, 5));
        item.setText("Betalning");
        SSInpaymentRow row = new SSInpaymentRow();
        row.setInvoiceNr(invoice.getNumber());
        row.setValue(new BigDecimal(amount));
        row.setCurrencyRate(BigDecimal.ONE);
        item.setRows(List.of(row));
        return item;
    }
}
