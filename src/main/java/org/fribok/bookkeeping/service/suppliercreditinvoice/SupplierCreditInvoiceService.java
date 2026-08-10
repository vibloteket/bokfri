package org.fribok.bookkeeping.service.suppliercreditinvoice;

import se.swedsoft.bookkeeping.calc.math.SSSupplierCreditInvoiceMath;
import se.swedsoft.bookkeeping.calc.math.SSSupplierInvoiceMath;
import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSSupplierCreditInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoiceRow;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Supplier credit-invoice use cases shared by Swing and CLI. */
public final class SupplierCreditInvoiceService {
    private final SSDB database;

    public SupplierCreditInvoiceService(SSDB database) {
        this.database = database;
    }

    public List<SSSupplierCreditInvoice> list() {
        return database.getSupplierCreditInvoices().stream()
                .sorted(Comparator.comparing(SSSupplierCreditInvoice::getNumber,
                        Comparator.nullsLast(Integer::compareTo)))
                .toList();
    }

    public Optional<SSSupplierCreditInvoice> find(int number) {
        return list().stream().filter(invoice -> invoice.getNumber() != null
                && invoice.getNumber() == number).findFirst();
    }

    public SSSupplierCreditInvoice preview(SSSupplierInvoice original, LocalDate date,
            BigDecimal amount) {
        validateOriginal(original, amount);
        SSSupplierCreditInvoice credit = new SSSupplierCreditInvoice(original);
        credit.setNumber(nextNumber());
        credit.setEntered(false);
        credit.setLocalDate(date == null ? LocalDate.now() : date);
        if (amount != null) {
            BigDecimal total = SSSupplierInvoiceMath.getTotalSum(original);
            BigDecimal factor = amount.divide(total, 12, RoundingMode.HALF_UP);
            for (SSSupplierInvoiceRow row : credit.getRows()) {
                row.setUnitprice(row.getUnitprice().multiply(factor));
            }
            credit.setTaxSum(original.getTaxSum().multiply(factor));
            credit.setRoundingSum(original.getRoundingSum().multiply(factor));
        }
        credit.generateVoucher();
        return credit;
    }

    public SSSupplierCreditInvoice create(SSSupplierInvoice original, LocalDate date,
            BigDecimal amount) {
        SSSupplierCreditInvoice credit = preview(original, date, amount);
        credit.setNumber(null);
        database.addSupplierCreditInvoice(credit);
        return credit;
    }

    public SupplierCreditInvoiceJournalPlan planJournal(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("Journal period is invalid");
        }
        List<SSSupplierCreditInvoice> invoices = list().stream()
                .filter(invoice -> !invoice.isEntered()
                        && SSSupplierCreditInvoiceMath.inPeriod(invoice, from, to))
                .toList();
        int number = database.getCurrentCompany().getAutoIncrement()
                .getNumber("suppliercreditinvoicejournal") + 1;
        SSVoucher voucher = new SSVoucher(0);
        voucher.setDescription("Leverantörskreditfakturajournal nr " + number);
        voucher.setLocalDate(to);
        for (SSSupplierCreditInvoice invoice : invoices) {
            for (SSVoucherRow row : invoice.generateVoucher().getRows()) {
                voucher.addVoucherRow(new SSVoucherRow(row));
            }
        }
        return new SupplierCreditInvoiceJournalPlan(number, from, to, invoices,
                SSVoucherMath.compress(voucher));
    }

    public SupplierCreditInvoiceJournalResult commitJournal(
            SupplierCreditInvoiceJournalPlan plan) {
        if (plan.invoices().isEmpty()) {
            throw new IllegalArgumentException("Supplier credit-invoice journal has no invoices");
        }
        for (SSSupplierCreditInvoice invoice : plan.invoices()) {
            if (invoice.isEntered()) {
                throw new IllegalStateException("Supplier credit invoice " + invoice.getNumber()
                        + " is already entered");
            }
            invoice.setEntered();
            database.updateSupplierCreditInvoice(invoice);
        }
        SSNewCompany company = database.getCurrentCompany();
        company.getAutoIncrement().doAutoIncrement("suppliercreditinvoicejournal");
        database.updateCompany(company);
        database.addVoucher(plan.voucher(), false);
        return new SupplierCreditInvoiceJournalResult(plan.journalNumber(),
                plan.voucher().getNumber(), plan.invoices().size());
    }

    public int nextNumber() {
        return list().stream().map(SSSupplierCreditInvoice::getNumber)
                .filter(java.util.Objects::nonNull).max(Integer::compareTo)
                .orElse(database.getCurrentCompany().getAutoIncrement()
                        .getNumber("suppliercreditinvoice")) + 1;
    }

    private void validateOriginal(SSSupplierInvoice original, BigDecimal amount) {
        if (original == null) {
            throw new IllegalArgumentException("Original supplier invoice is required");
        }
        if (!original.isEntered()) {
            throw new IllegalArgumentException(
                    "Original supplier invoice must be entered before crediting");
        }
        BigDecimal balance = SSSupplierInvoiceMath.getSaldo(original);
        if (balance.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Original supplier invoice has no remaining balance to credit");
        }
        if (amount != null && (amount.signum() <= 0 || amount.compareTo(balance) > 0)) {
            throw new IllegalArgumentException(
                    "Credit amount must be positive and not exceed supplier invoice balance");
        }
    }
}
