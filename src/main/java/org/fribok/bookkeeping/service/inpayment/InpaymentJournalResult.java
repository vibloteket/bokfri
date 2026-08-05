package org.fribok.bookkeeping.service.inpayment;

/** Persisted customer inpayment journal identifiers. */
public record InpaymentJournalResult(int journalNumber, int voucherNumber, int inpaymentCount) {}
