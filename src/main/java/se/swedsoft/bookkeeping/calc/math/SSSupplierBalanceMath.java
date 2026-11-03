package se.swedsoft.bookkeeping.calc.math;

import se.swedsoft.bookkeeping.data.SSOutpayment;
import se.swedsoft.bookkeeping.data.SSOutpaymentRow;
import se.swedsoft.bookkeeping.data.SSSupplierCreditInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Explicit company-scoped inputs for supplier balance reports; never reads SSDB caches. */
public final class SSSupplierBalanceMath {
    private final Map<Integer, BigDecimal> payments = new HashMap<>();
    private final Map<Integer, BigDecimal> credits = new HashMap<>();

    /** Aggregates dated outpayments and linked credit totals through the inclusive cutoff, in invoice currency. */
    public SSSupplierBalanceMath(List<SSOutpayment> outpayments,
            List<SSSupplierCreditInvoice> creditInvoices, LocalDate date) {
        for (SSOutpayment payment : outpayments) {
            if (included(payment.getLocalDate(), date)) {
                for (SSOutpaymentRow row : payment.getRows()) {
                    if (row.getValue() != null) payments.merge(row.getInvoiceNr(), row.getValue(), BigDecimal::add);
                }
            }
        }
        for (SSSupplierCreditInvoice credit : creditInvoices) {
            if (included(credit.getLocalDate(), date) && credit.getCreditingNr() != null) {
                credits.merge(credit.getCreditingNr(), SSSupplierInvoiceMath.getTotalSum(credit), BigDecimal::add);
            }
        }
    }

    /** Matches the legacy report's invoice-date inclusion rule. */
    public static boolean included(SSSupplierInvoice invoice, LocalDate date) {
        return included(invoice.getLocalDate(), date);
    }

    private static boolean included(LocalDate value, LocalDate date) {
        return value != null && date != null && !value.isAfter(date);
    }

    /** Returns a defensive copy of dated outpayment sums keyed by invoice number. */
    public HashMap<Integer, BigDecimal> paymentSums() { return new HashMap<>(payments); }

    /** Returns a defensive copy of dated linked credit totals keyed by invoice number. */
    public HashMap<Integer, BigDecimal> creditSums() { return new HashMap<>(credits); }

    /** Unrounded balance including the invoice's stored tax and rounding amounts. */
    public BigDecimal saldo(SSSupplierInvoice invoice) {
        return SSSupplierInvoiceMath.getTotalSum(invoice)
                .subtract(payments.getOrDefault(invoice.getNumber(), BigDecimal.ZERO))
                .subtract(credits.getOrDefault(invoice.getNumber(), BigDecimal.ZERO));
    }
}
