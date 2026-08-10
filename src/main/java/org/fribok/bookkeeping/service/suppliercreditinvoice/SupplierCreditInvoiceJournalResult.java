package org.fribok.bookkeeping.service.suppliercreditinvoice;

/** Result of a committed supplier credit-invoice journal. */
public record SupplierCreditInvoiceJournalResult(int journalNumber, int voucherNumber,
                                                  int invoiceCount) {}
