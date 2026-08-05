package org.fribok.bookkeeping.service.invoice;

/** One stable, machine-readable invoice validation problem. */
public record InvoiceValidationIssue(String code, String field, Integer row, String message) {}
