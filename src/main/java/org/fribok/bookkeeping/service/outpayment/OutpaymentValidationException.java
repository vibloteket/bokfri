package org.fribok.bookkeeping.service.outpayment;

/** Prevents an invalid customer outpayment from being persisted. */
public class OutpaymentValidationException extends RuntimeException {
    private final OutpaymentValidationResult result;

    public OutpaymentValidationException(OutpaymentValidationResult result) {
        super(result.issues().isEmpty() ? "Invalid outpayment" : result.issues().get(0).message());
        this.result = result;
    }

    public OutpaymentValidationResult getResult() { return result; }
}
