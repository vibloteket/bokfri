package org.fribok.bookkeeping.service.outpayment;

/** One stable, machine-readable customer outpayment validation problem. */
public record OutpaymentValidationIssue(String code, String field, Integer row, String message) {}
