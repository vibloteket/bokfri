package org.fribok.bookkeeping.service.voucher;

/** One stable, machine-readable voucher validation problem. */
public record VoucherValidationIssue(String code, String message, Integer row) {}
