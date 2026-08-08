package org.fribok.bookkeeping.service.creditinvoice;

/** Result of a committed customer credit-invoice journal. */
public record CreditInvoiceJournalResult(int journalNumber, int voucherNumber, int invoiceCount) {}
