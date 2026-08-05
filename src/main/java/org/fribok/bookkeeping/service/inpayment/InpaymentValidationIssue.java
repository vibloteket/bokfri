package org.fribok.bookkeeping.service.inpayment;

/** One stable, machine-readable customer inpayment validation problem. */
public record InpaymentValidationIssue(String code, String field, Integer row, String message) {}
