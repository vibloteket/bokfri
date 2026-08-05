package org.fribok.bookkeeping.service.invoice;

/** Persisted invoice journal identifiers. */
public record InvoiceJournalResult(int journalNumber, int voucherNumber, int invoiceCount) {}
