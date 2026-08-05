package org.fribok.bookkeeping.service.inpayment;

import se.swedsoft.bookkeeping.data.SSInpayment;
import se.swedsoft.bookkeeping.data.SSInpaymentRow;
import se.swedsoft.bookkeeping.data.SSInvoice;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** UI-independent customer inpayment validation. */
public final class InpaymentValidator {
    private InpaymentValidator() {}

    public static InpaymentValidationResult validate(SSInpayment inpayment, List<SSInvoice> invoices) {
        return validate(inpayment, invoices, null);
    }

    public static InpaymentValidationResult validate(SSInpayment inpayment, List<SSInvoice> invoices,
            java.util.function.Function<SSInvoice, BigDecimal> balanceProvider) {
        List<InpaymentValidationIssue> issues = new ArrayList<>();
        if (inpayment == null) {
            issues.add(issue("INPAYMENT_REQUIRED", null, null, "Inbetalningen saknas."));
            return new InpaymentValidationResult(false, issues);
        }
        if (inpayment.getLocalDate() == null) {
            issues.add(issue("INPAYMENT_DATE_REQUIRED", "date", null, "Inbetalningsdatum saknas."));
        }
        if (inpayment.getText() == null || inpayment.getText().isBlank()) {
            issues.add(issue("INPAYMENT_TEXT_REQUIRED", "text", null, "Inbetalningstext saknas."));
        }
        if (inpayment.getRows().isEmpty()) {
            issues.add(issue("INPAYMENT_ROWS_REQUIRED", "rows", null, "Inbetalningen saknar rader."));
        }
        for (int index = 0; index < inpayment.getRows().size(); index++) {
            SSInpaymentRow row = inpayment.getRows().get(index);
            int rowNumber = index + 1;
            SSInvoice invoice = row == null ? null : row.getInvoice(invoices);
            if (invoice == null) {
                issues.add(issue("INPAYMENT_INVOICE_REQUIRED", "invoiceNumber", rowNumber,
                        "Inbetalningsraden saknar en giltig faktura."));
            } else if (!invoice.isEntered()) {
                issues.add(issue("INPAYMENT_INVOICE_NOT_BOOKED", "invoiceNumber", rowNumber,
                        "Fakturan måste vara bokförd före betalning."));
            }
            if (row == null || row.getValue() == null || row.getValue().signum() <= 0) {
                issues.add(issue("INPAYMENT_AMOUNT_INVALID", "amount", rowNumber,
                        "Inbetalt belopp måste vara positivt."));
            } else if (invoice != null && balanceProvider != null
                    && row.getValue().compareTo(balanceProvider.apply(invoice)) > 0) {
                issues.add(issue("INPAYMENT_AMOUNT_EXCEEDS_BALANCE", "amount", rowNumber,
                        "Inbetalt belopp överstiger fakturans saldo."));
            }
            if (row == null || row.getCurrencyRate() == null || row.getCurrencyRate().signum() <= 0) {
                issues.add(issue("INPAYMENT_CURRENCY_RATE_INVALID", "currencyRate", rowNumber,
                        "Valutakurs måste vara positiv."));
            }
        }
        return new InpaymentValidationResult(issues.isEmpty(), issues);
    }

    private static InpaymentValidationIssue issue(String code, String field, Integer row, String message) {
        return new InpaymentValidationIssue(code, field, row, message);
    }
}
