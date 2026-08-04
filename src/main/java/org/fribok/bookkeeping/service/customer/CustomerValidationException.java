package org.fribok.bookkeeping.service.customer;

/** Prevents an invalid customer from being persisted. */
public class CustomerValidationException extends RuntimeException {
    private final CustomerValidationResult result;

    public CustomerValidationException(CustomerValidationResult result) {
        super(result.issues().isEmpty() ? "Invalid customer" : result.issues().get(0).message());
        this.result = result;
    }

    public CustomerValidationResult getResult() { return result; }
}
