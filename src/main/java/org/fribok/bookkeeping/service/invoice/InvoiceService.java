package org.fribok.bookkeeping.service.invoice;

import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.system.SSDB;
import se.swedsoft.bookkeeping.print.ReportService;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Read operations for customer invoices, independent of the user interface. */
public final class InvoiceService {
    private final SSDB database;

    public InvoiceService(SSDB database) {
        this.database = database;
    }

    public List<SSInvoice> list(LocalDate from, LocalDate to) {
        return database.getInvoices().stream()
                .filter(java.util.Objects::nonNull)
                .filter(invoice -> from == null || invoice.getLocalDate() == null
                        || !invoice.getLocalDate().isBefore(from))
                .filter(invoice -> to == null || invoice.getLocalDate() == null
                        || !invoice.getLocalDate().isAfter(to))
                .sorted(Comparator.comparing(SSInvoice::getNumber,
                        Comparator.nullsLast(Integer::compareTo)))
                .toList();
    }

    public Optional<SSInvoice> find(int number) {
        return database.getInvoices().stream()
                .filter(java.util.Objects::nonNull)
                .filter(invoice -> invoice.getNumber() != null && invoice.getNumber() == number)
                .findFirst();
    }

    public InvoiceValidationResult validate(SSInvoice invoice) {
        return InvoiceValidator.validate(invoice, database.getAccounts());
    }

    public SSInvoice create(SSInvoice invoice) {
        InvoiceValidationResult validation = validate(invoice);
        if (!validation.valid()) {
            throw new InvoiceValidationException(validation);
        }
        database.addInvoice(invoice);
        return invoice;
    }

    public SSInvoice update(SSInvoice invoice) {
        InvoiceValidationResult validation = validate(invoice);
        if (!validation.valid()) {
            throw new InvoiceValidationException(validation);
        }
        database.updateInvoice(invoice);
        return invoice;
    }

    public InvoiceJournalPlan planJournal(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("Journal period is invalid");
        }
        List<SSInvoice> invoices = list(from, to).stream()
                .filter(invoice -> !invoice.isEntered())
                .toList();
        int journalNumber = database.getCurrentCompany().getAutoIncrement()
                .getNumber("invoicejournal") + 1;
        SSVoucher combined = new SSVoucher(0);
        combined.setDescription("Fakturajournal nr " + journalNumber);
        combined.setLocalDate(to);
        for (SSInvoice invoice : invoices) {
            SSVoucher current = invoice.generateVoucher();
            for (SSVoucherRow row : current.getRows()) {
                combined.addVoucherRow(new SSVoucherRow(row));
            }
        }
        return new InvoiceJournalPlan(journalNumber, from, to, invoices,
                SSVoucherMath.compress(combined));
    }

    public InvoiceJournalResult commitJournal(InvoiceJournalPlan plan) {
        if (plan.invoices().isEmpty()) {
            throw new IllegalArgumentException("Invoice journal has no invoices");
        }
        for (SSInvoice invoice : plan.invoices()) {
            if (invoice.isEntered()) {
                throw new IllegalStateException("Invoice " + invoice.getNumber() + " is already entered");
            }
        }
        for (SSInvoice invoice : plan.invoices()) {
            invoice.setEntered();
            database.updateInvoice(invoice);
        }
        SSNewCompany company = database.getCurrentCompany();
        company.getAutoIncrement().doAutoIncrement("invoicejournal");
        database.updateCompany(company);
        database.addVoucher(plan.voucher(), false);
        return new InvoiceJournalResult(plan.journalNumber(), plan.voucher().getNumber(),
                plan.invoices().size());
    }

    public Path exportPdf(SSInvoice invoice, Path output, Locale locale, boolean overwrite)
            throws IOException {
        ReportService reports = new ReportService();
        return reports.exportPdf(reports.renderInvoice(invoice, locale), output, overwrite);
    }

    public int nextNumber() {
        return database.getInvoices().stream()
                .map(SSInvoice::getNumber)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(database.getCurrentCompany().getAutoIncrement().getNumber("invoice")) + 1;
    }
}
