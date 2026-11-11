package org.fribok.bookkeeping.service.invoice;

import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.common.SSInvoiceType;
import se.swedsoft.bookkeeping.data.SSStandardText;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** Explicit new-invoice defaults, separate from lossless persisted snapshot hydration. */
public final class CustomerInvoiceFactory {
    private CustomerInvoiceFactory() { }

    /**
     * Creates an unbooked normal invoice using only the supplied company/date. The caller
     * applies the customer, due date and rows; numbering belongs to the insertion workflow.
     * No legacy singleton, clock or voucher generation is involved.
     */
    public static SSInvoice create(SSNewCompany company, LocalDate date) {
        Objects.requireNonNull(company, "company");
        SSInvoice invoice = SSInvoice.forPersistence();
        invoice.setType(SSInvoiceType.NORMAL);
        invoice.setLocalDate(date);
        invoice.setCurrencyRate(BigDecimal.ONE);
        invoice.setStockInfluencing(true);
        invoice.setOrderNumbers("Fakturan har inga ordrar");
        invoice.setPaymentTerm(company.getPaymentTerm());
        invoice.setDeliveryTerm(company.getDeliveryTerm());
        invoice.setDeliveryWay(company.getDeliveryWay());
        invoice.setCurrency(company.getCurrency());
        invoice.setDelayInterest(company.getDelayInterest());
        invoice.setText(company.getStandardText(SSStandardText.Customerinvoice).orElse(null));
        invoice.setStoredTaxRates(company.getTaxRate1(), company.getTaxRate2(), company.getTaxRate3());
        invoice.setDefaultAccounts(company.getDefaultAccounts());
        invoice.setOurContactPerson(company.getContactPerson());
        return invoice;
    }
}
