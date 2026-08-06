package org.fribok.bookkeeping.service.outpayment;

/** Persisted customer outpayment journal identifiers. */
public record OutpaymentJournalResult(int journalNumber, int voucherNumber, int outpaymentCount) {}
