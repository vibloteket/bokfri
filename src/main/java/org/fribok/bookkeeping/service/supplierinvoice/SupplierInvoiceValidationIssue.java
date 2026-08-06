package org.fribok.bookkeeping.service.supplierinvoice;
public record SupplierInvoiceValidationIssue(String code, String field, Integer row, String message) {}
