package org.fribok.bookkeeping.service.product;

/** Prevents an invalid product from being persisted. */
public class ProductValidationException extends RuntimeException {
    private final ProductValidationResult result;

    public ProductValidationException(ProductValidationResult result) {
        super(result.issues().isEmpty() ? "Invalid product" : result.issues().get(0).message());
        this.result = result;
    }

    public ProductValidationResult getResult() { return result; }
}
