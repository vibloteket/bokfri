package org.fribok.bookkeeping.service.invoice;

/** Prevents an invalid customer invoice from being persisted. */
public class InvoiceValidationException extends RuntimeException {
    private final InvoiceValidationResult result;

    public InvoiceValidationException(InvoiceValidationResult result) {
        super(result.issues().isEmpty() ? "Invalid invoice" : result.issues().get(0).message());
        this.result = result;
    }

    public InvoiceValidationResult getResult() { return result; }
}
