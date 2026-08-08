package org.fribok.bookkeeping.service.creditinvoice;

import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSVoucher;

import java.time.LocalDate;
import java.util.List;

/** Preview of a customer credit-invoice journal. */
public record CreditInvoiceJournalPlan(int journalNumber, LocalDate from, LocalDate to,
                                       List<SSCreditInvoice> invoices, SSVoucher voucher) {
    public CreditInvoiceJournalPlan {
        invoices = List.copyOf(invoices);
    }
}
