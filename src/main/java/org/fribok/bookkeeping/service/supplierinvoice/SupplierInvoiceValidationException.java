package org.fribok.bookkeeping.service.supplierinvoice;
public class SupplierInvoiceValidationException extends RuntimeException {
    private final SupplierInvoiceValidationResult result;
    public SupplierInvoiceValidationException(SupplierInvoiceValidationResult r){super(r.issues().isEmpty()?"Invalid supplier invoice":r.issues().get(0).message());result=r;}
    public SupplierInvoiceValidationResult getResult(){return result;}
}
