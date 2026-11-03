package se.swedsoft.bookkeeping.calc.math;

import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSInpayment;
import se.swedsoft.bookkeeping.data.SSInpaymentRow;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.common.SSInvoiceType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Explicit company-scoped inputs for customer outstanding-balance reports; never reads SSDB caches. */
public final class SSCustomerBalanceMath {
    private final boolean roundingOff;
    private final Map<Integer, BigDecimal> payments = new HashMap<>();
    private final Map<Integer, BigDecimal> credits = new HashMap<>();

    /** Aggregates payments and linked credit totals dated on or before the cutoff, in sale currency. */
    public SSCustomerBalanceMath(boolean roundingOff, List<SSInpayment> inpayments,
            List<SSCreditInvoice> creditInvoices, LocalDate date) {
        this.roundingOff = roundingOff;
        for (SSInpayment payment : inpayments) {
            if (included(payment.getLocalDate(), date)) {
                for (SSInpaymentRow row : payment.getRows()) {
                    if (row.getValue() != null) payments.merge(row.getInvoiceNr(), row.getValue(), BigDecimal::add);
                }
            }
        }
        for (SSCreditInvoice credit : creditInvoices) {
            if (included(credit.getLocalDate(), date) && credit.getCreditingNr() != null) {
                credits.merge(credit.getCreditingNr(), total(credit), BigDecimal::add);
            }
        }
    }

    /** Matches the legacy dated report inclusion rule (cash invoices are excluded). */
    public static boolean included(SSInvoice invoice, LocalDate date) {
        return included(invoice.getLocalDate(), date) && invoice.getType() != SSInvoiceType.CASH;
    }

    private static boolean included(LocalDate value, LocalDate date) {
        return value != null && date != null && !value.isAfter(date);
    }

    /** Calculates tax and company rounding using explicit context. */
    public BigDecimal total(SSInvoice invoice) {
        return SSInvoiceMath.getTotalSum(invoice, roundingOff);
    }

    /** Returns a defensive copy of the dated payment sums keyed by invoice number. */
    public HashMap<Integer, BigDecimal> paymentSums() { return new HashMap<>(payments); }

    /** Returns a defensive copy of the dated linked credit sums keyed by invoice number. */
    public HashMap<Integer, BigDecimal> creditSums() { return new HashMap<>(credits); }

    /** Unrounded balance in invoice currency; report-specific rounding remains with the caller. */
    public BigDecimal saldo(SSInvoice invoice) {
        return total(invoice).subtract(payments.getOrDefault(invoice.getNumber(), BigDecimal.ZERO))
                .subtract(credits.getOrDefault(invoice.getNumber(), BigDecimal.ZERO));
    }
}
