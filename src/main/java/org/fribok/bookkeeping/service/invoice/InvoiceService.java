package org.fribok.bookkeeping.service.invoice;

import se.swedsoft.bookkeeping.data.SSInvoice;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Read operations for customer invoices, independent of the user interface. */
public final class InvoiceService {
    private final SSDB database;

    public InvoiceService(SSDB database) {
        this.database = database;
    }

    public List<SSInvoice> list(LocalDate from, LocalDate to) {
        return database.getInvoices().stream()
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
                .filter(invoice -> invoice.getNumber() != null && invoice.getNumber() == number)
                .findFirst();
    }
}
