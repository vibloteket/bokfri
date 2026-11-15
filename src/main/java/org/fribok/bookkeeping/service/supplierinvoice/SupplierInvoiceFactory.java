package org.fribok.bookkeeping.service.supplierinvoice;

import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** Explicit defaults for new supplier invoices; never used when loading stored snapshots. */
public final class SupplierInvoiceFactory {
    private SupplierInvoiceFactory() {}

    /**
     * Creates an unnumbered supplier invoice from explicit company/date inputs without legacy
     * globals. The caller supplies the supplier, due date and rows; insertion owns numbering.
     */
    public static SSSupplierInvoice create(SSNewCompany company, LocalDate date) {
        Objects.requireNonNull(company, "company");
        SSSupplierInvoice invoice = SSSupplierInvoice.forPersistence();
        invoice.setLocalDate(date);
        invoice.setLocalDueDate(date);
        invoice.setCurrency(company.getCurrency());
        invoice.setCurrencyRate(BigDecimal.ONE);
        invoice.setTaxSum(BigDecimal.ZERO);
        invoice.setRoundingSum(BigDecimal.ZERO);
        invoice.setStockInfluencing(true);
        invoice.getDefaultAccounts().putAll(company.getDefaultAccounts());
        return invoice;
    }
}
