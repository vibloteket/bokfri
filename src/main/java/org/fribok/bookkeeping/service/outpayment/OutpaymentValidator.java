package org.fribok.bookkeeping.service.outpayment;

import se.swedsoft.bookkeeping.data.SSOutpayment;
import se.swedsoft.bookkeeping.data.SSOutpaymentRow;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** UI-independent customer outpayment validation. */
public final class OutpaymentValidator {
    private OutpaymentValidator() {}

    public static OutpaymentValidationResult validate(SSOutpayment outpayment, List<SSSupplierInvoice> invoices) {
        return validate(outpayment, invoices, null);
    }

    public static OutpaymentValidationResult validate(SSOutpayment outpayment, List<SSSupplierInvoice> invoices,
            java.util.function.Function<SSSupplierInvoice, BigDecimal> balanceProvider) {
        List<OutpaymentValidationIssue> issues = new ArrayList<>();
        if (outpayment == null) {
            issues.add(issue("OUTPAYMENT_REQUIRED", null, null, "Utbetalningen saknas."));
            return new OutpaymentValidationResult(false, issues);
        }
        if (outpayment.getLocalDate() == null) {
            issues.add(issue("OUTPAYMENT_DATE_REQUIRED", "date", null, "Utbetalningsdatum saknas."));
        }
        if (outpayment.getText() == null || outpayment.getText().isBlank()) {
            issues.add(issue("OUTPAYMENT_TEXT_REQUIRED", "text", null, "Utbetalningstext saknas."));
        }
        if (outpayment.getRows().isEmpty()) {
            issues.add(issue("OUTPAYMENT_ROWS_REQUIRED", "rows", null, "Utbetalningen saknar rader."));
        }
        for (int index = 0; index < outpayment.getRows().size(); index++) {
            SSOutpaymentRow row = outpayment.getRows().get(index);
            int rowNumber = index + 1;
            SSSupplierInvoice invoice = row == null ? null : row.getSupplierInvoice(invoices);
            if (invoice == null) {
                issues.add(issue("OUTPAYMENT_INVOICE_REQUIRED", "invoiceNumber", rowNumber,
                        "Utbetalningsraden saknar en giltig faktura."));
            } else if (!invoice.isEntered()) {
                issues.add(issue("OUTPAYMENT_INVOICE_NOT_BOOKED", "invoiceNumber", rowNumber,
                        "Fakturan måste vara bokförd före betalning."));
            }
            if (row == null || row.getValue() == null || row.getValue().signum() <= 0) {
                issues.add(issue("OUTPAYMENT_AMOUNT_INVALID", "amount", rowNumber,
                        "Utbetalt belopp måste vara positivt."));
            } else if (invoice != null && balanceProvider != null
                    && row.getValue().compareTo(balanceProvider.apply(invoice)) > 0) {
                issues.add(issue("OUTPAYMENT_AMOUNT_EXCEEDS_BALANCE", "amount", rowNumber,
                        "Utbetalt belopp överstiger fakturans saldo."));
            }
            if (row == null || row.getCurrencyRate() == null || row.getCurrencyRate().signum() <= 0) {
                issues.add(issue("OUTPAYMENT_CURRENCY_RATE_INVALID", "currencyRate", rowNumber,
                        "Valutakurs måste vara positiv."));
            }
        }
        return new OutpaymentValidationResult(issues.isEmpty(), issues);
    }

    private static OutpaymentValidationIssue issue(String code, String field, Integer row, String message) {
        return new OutpaymentValidationIssue(code, field, row, message);
    }
}
