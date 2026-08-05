package org.fribok.bookkeeping.service.product;

/** One stable, machine-readable product validation problem. */
public record ProductValidationIssue(String code, String field, String message) {}
