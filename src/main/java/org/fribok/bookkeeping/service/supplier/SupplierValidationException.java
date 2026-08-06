package org.fribok.bookkeeping.service.supplier;

/** Prevents an invalid supplier from being persisted. */
public class SupplierValidationException extends RuntimeException {
    private final SupplierValidationResult result;

    public SupplierValidationException(SupplierValidationResult result) {
        super(result.issues().isEmpty() ? "Invalid supplier" : result.issues().get(0).message());
        this.result = result;
    }

    public SupplierValidationResult getResult() { return result; }
}
