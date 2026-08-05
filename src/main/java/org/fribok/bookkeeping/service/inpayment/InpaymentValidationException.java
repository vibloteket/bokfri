package org.fribok.bookkeeping.service.inpayment;

/** Prevents an invalid customer inpayment from being persisted. */
public class InpaymentValidationException extends RuntimeException {
    private final InpaymentValidationResult result;

    public InpaymentValidationException(InpaymentValidationResult result) {
        super(result.issues().isEmpty() ? "Invalid inpayment" : result.issues().get(0).message());
        this.result = result;
    }

    public InpaymentValidationResult getResult() { return result; }
}
