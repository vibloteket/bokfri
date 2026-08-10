package org.fribok.bookkeeping.service.suppliercreditinvoice;

import se.swedsoft.bookkeeping.data.SSSupplierCreditInvoice;
import se.swedsoft.bookkeeping.data.SSVoucher;

import java.time.LocalDate;
import java.util.List;

/** Preview of a supplier credit-invoice journal. */
public record SupplierCreditInvoiceJournalPlan(int journalNumber, LocalDate from, LocalDate to,
                                                List<SSSupplierCreditInvoice> invoices,
                                                SSVoucher voucher) {
    public SupplierCreditInvoiceJournalPlan {
        invoices = List.copyOf(invoices);
    }
}
