package org.fribok.bookkeeping.service.creditinvoice;

import se.swedsoft.bookkeeping.calc.math.SSInvoiceMath;
import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.data.SSCreditInvoice;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Customer credit-invoice use cases shared by Swing and CLI. */
public final class CreditInvoiceService {
    private final SSDB database;

    public CreditInvoiceService(SSDB database) {
        this.database = database;
    }

    public List<SSCreditInvoice> list() {
        return database.getCreditInvoices().stream()
                .sorted(Comparator.comparing(SSCreditInvoice::getNumber,
                        Comparator.nullsLast(Integer::compareTo)))
                .toList();
    }

    public Optional<SSCreditInvoice> find(int number) {
        return list().stream().filter(invoice -> invoice.getNumber() != null
                && invoice.getNumber() == number).findFirst();
    }

    public SSCreditInvoice preview(SSInvoice original, LocalDate date, BigDecimal amount) {
        validateOriginal(original, amount);
        SSCreditInvoice credit = new SSCreditInvoice(original);
        credit.setNumber(nextNumber());
        credit.setLocalDate(date == null ? LocalDate.now() : date);
        if (amount != null) {
            BigDecimal total = SSInvoiceMath.getTotalSum(original);
            BigDecimal factor = amount.divide(total, 12, java.math.RoundingMode.HALF_UP);
            credit.getRows().forEach(row -> row.setUnitprice(row.getUnitprice().multiply(factor)));
        }
        credit.generateVoucher();
        return credit;
    }

    public SSCreditInvoice create(SSInvoice original, LocalDate date, BigDecimal amount) {
        SSCreditInvoice credit = preview(original, date, amount);
        credit.setNumber(null);
        database.addCreditInvoice(credit);
        return credit;
    }

    public CreditInvoiceJournalPlan planJournal(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("Journal period is invalid");
        }
        List<SSCreditInvoice> invoices = list().stream()
                .filter(invoice -> !invoice.isEntered() && SSInvoiceMath.inPeriod(invoice, from, to))
                .toList();
        int number = database.getCurrentCompany().getAutoIncrement()
                .getNumber("creditinvoicejournal") + 1;
        SSVoucher voucher = new SSVoucher(0);
        voucher.setDescription("Kreditfakturajournal nr " + number);
        voucher.setLocalDate(to);
        for (SSCreditInvoice invoice : invoices) {
            for (SSVoucherRow row : invoice.generateVoucher().getRows()) {
                voucher.addVoucherRow(new SSVoucherRow(row));
            }
        }
        return new CreditInvoiceJournalPlan(number, from, to, invoices,
                SSVoucherMath.compress(voucher));
    }

    public CreditInvoiceJournalResult commitJournal(CreditInvoiceJournalPlan plan) {
        if (plan.invoices().isEmpty()) {
            throw new IllegalArgumentException("Credit invoice journal has no invoices");
        }
        for (SSCreditInvoice invoice : plan.invoices()) {
            if (invoice.isEntered()) {
                throw new IllegalStateException("Credit invoice " + invoice.getNumber()
                        + " is already entered");
            }
            invoice.setEntered();
            database.updateCreditInvoice(invoice);
        }
        SSNewCompany company = database.getCurrentCompany();
        company.getAutoIncrement().doAutoIncrement("creditinvoicejournal");
        database.updateCompany(company);
        database.addVoucher(plan.voucher(), false);
        return new CreditInvoiceJournalResult(plan.journalNumber(), plan.voucher().getNumber(),
                plan.invoices().size());
    }

    public int nextNumber() {
        return list().stream().map(SSCreditInvoice::getNumber).filter(java.util.Objects::nonNull)
                .max(Integer::compareTo).orElse(database.getCurrentCompany().getAutoIncrement()
                        .getNumber("creditinvoice")) + 1;
    }

    private void validateOriginal(SSInvoice original, BigDecimal amount) {
        if (original == null) {
            throw new IllegalArgumentException("Original invoice is required");
        }
        if (!original.isEntered()) {
            throw new IllegalArgumentException("Original invoice must be entered before crediting");
        }
        BigDecimal balance = SSInvoiceMath.getSaldo(original);
        if (balance.signum() <= 0) {
            throw new IllegalArgumentException("Original invoice has no remaining balance to credit");
        }
        if (amount != null && (amount.signum() <= 0 || amount.compareTo(balance) > 0)) {
            throw new IllegalArgumentException("Credit amount must be positive and not exceed invoice balance");
        }
    }
}
