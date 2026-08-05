package org.fribok.bookkeeping.service.invoice;

import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSVoucher;

import java.time.LocalDate;
import java.util.List;

/** Planned invoice journal before it is committed. */
public record InvoiceJournalPlan(int journalNumber, LocalDate from, LocalDate to,
                                 List<SSInvoice> invoices, SSVoucher voucher) {
    public InvoiceJournalPlan {
        invoices = List.copyOf(invoices);
    }
}
