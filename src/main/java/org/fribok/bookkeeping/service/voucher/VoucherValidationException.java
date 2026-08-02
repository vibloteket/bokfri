package org.fribok.bookkeeping.service.voucher;

/** Prevents an invalid voucher from being persisted. */
public class VoucherValidationException extends RuntimeException {
    private final VoucherValidationResult result;

    public VoucherValidationException(VoucherValidationResult result) {
        super(result.issues().isEmpty() ? "Invalid voucher" : result.issues().get(0).message());
        this.result = result;
    }

    public VoucherValidationResult getResult() {
        return result;
    }
}
