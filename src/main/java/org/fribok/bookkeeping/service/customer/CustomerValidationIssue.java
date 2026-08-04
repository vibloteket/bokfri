package org.fribok.bookkeeping.service.customer;

/** One stable, machine-readable customer validation problem. */
public record CustomerValidationIssue(String code, String field, String message) {}
