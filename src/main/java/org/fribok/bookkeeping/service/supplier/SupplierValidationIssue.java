package org.fribok.bookkeeping.service.supplier;

/** One stable, machine-readable supplier validation problem. */
public record SupplierValidationIssue(String code, String field, String message) {}
