package org.fribok.bookkeeping.service;

import se.swedsoft.bookkeeping.data.SSMonth;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Read-only row-value projection for the monthly sales-values report, not editable sale documents. */
public record SaleValuesData(List<Row> tenders, List<Row> orders,
        List<Row> invoices, List<Row> credits) {
    /** Snapshot list membership so later caller changes cannot change a report. */
    public SaleValuesData {
        tenders = List.copyOf(tenders);
        orders = List.copyOf(orders);
        invoices = List.copyOf(invoices);
        credits = List.copyOf(credits);
    }

    /** A stored discounted row amount with the document date and effective currency rate. */
    public record Row(LocalDate date, BigDecimal amount, BigDecimal currencyRate) {}

    /** Retains the legacy report's full-calendar-month filtering and double arithmetic. */
    public static double total(List<Row> rows, SSMonth month) {
        double total = 0.0;
        for (Row row : rows) {
            if (month.isDateInMonth(row.date()) && row.amount() != null) {
                total += row.amount().doubleValue() * row.currencyRate().doubleValue();
            }
        }
        return total;
    }
}
