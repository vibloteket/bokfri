package org.fribok.bookkeeping.gui.creditinvoice;

import org.fribok.bookkeeping.service.invoice.NormalizedCustomerJournalService;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Explicit invoice/credit journal preview and commit; no mutable domain snapshots escape to the GUI. */
public final class CustomerJournalAdapter {
    public enum Kind { INVOICE, CREDIT }
    public record Target(int companyId, int yearId, Kind kind) { }
    public Target target() { return new Target(company, year, kind); }
    public record Document(long id, Integer number, LocalDate date, String customer) { }
    public record Row(Integer account, String project, String resultUnit, BigDecimal debit, BigDecimal credit) { }
    public record Summary(int companyId, int yearId, Kind kind, LocalDate from, LocalDate to,
                          int journalNumber, int voucherNumber, LocalDate voucherDate,
                          List<Document> documents, List<Row> rows, BigDecimal debit, BigDecimal credit) {
        public Summary { documents = List.copyOf(documents); rows = List.copyOf(rows); }
    }
    public static final class Preview {
        private final CustomerJournalAdapter owner;
        private final NormalizedCustomerJournalService.Plan plan;
        private final Summary summary;
        private Saved confirmed;
        private Preview(CustomerJournalAdapter owner, NormalizedCustomerJournalService.Plan plan, Summary summary) {
            this.owner = owner; this.plan = plan; this.summary = summary;
        }
        public Summary summary() { return summary; }
    }
    public record Saved(int companyId, int yearId, Kind kind, int journalNumber, int voucherNumber, List<Long> documentIds) {
        public Saved { documentIds = List.copyOf(documentIds); }
    }
    private final CustomerDocumentTransactions transactions;
    private final Object gate;
    private final int company, year;
    private final Kind kind;
    private final Clock clock;
    CustomerJournalAdapter(CustomerCreditDialogAdapter.Connections connections, Object gate, int company, int year, Kind kind, Clock clock) {
        this.gate = gate; this.company = company; this.year = year; this.kind = java.util.Objects.requireNonNull(kind);
        this.clock = java.util.Objects.requireNonNull(clock); transactions = new CustomerDocumentTransactions(connections, gate, company, year, clock);
    }
    public boolean isBlocked() { return transactions.isBlocked(); }
    public Preview preview(LocalDate from, LocalDate to) throws SQLException {
        synchronized (gate) {
            return transactions.run(false, connection -> {
                var service = new NormalizedCustomerJournalService(connection, clock);
                var plan = kind == Kind.INVOICE ? service.planInvoices(company, year, from, to) : service.planCredits(company, year, from, to);
                var voucher = kind == Kind.INVOICE ? plan.invoicePlan().voucher() : plan.creditPlan().voucher();
                List<? extends se.swedsoft.bookkeeping.data.SSInvoice> selected = kind == Kind.INVOICE
                        ? plan.invoicePlan().invoices() : plan.creditPlan().invoices();
                List<Document> documents = new ArrayList<>(); var ids = plan.documentIds();
                for (int i = 0; i < selected.size(); i++) {
                    var document = selected.get(i);
                    documents.add(new Document(ids.get(i), document.getNumber(), document.getLocalDate(), document.getCustomerName()));
                }
                List<Row> rows = voucher.getRows().stream().map(row -> new Row(row.getAccountNr(), row.getProjectNr(),
                        row.getResultUnitNr(), row.getDebet(), row.getCredit())).toList();
                var summary = new Summary(company, year, kind, from, to, plan.journalNumber(), plan.voucherNumber(), voucher.getLocalDate(),
                        documents, rows, se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getDebetSum(voucher),
                        se.swedsoft.bookkeeping.calc.math.SSVoucherMath.getCreditSum(voucher));
                return new Preview(this, plan, summary);
            });
        }
    }
    public Saved post(Preview preview) throws SQLException {
        synchronized (gate) {
            if (preview == null || preview.owner != this) throw new SQLException("Foreign journal preview");
            if (preview.confirmed != null) return preview.confirmed;
            var result = transactions.run(true, connection -> new NormalizedCustomerJournalService(connection, clock).post(preview.plan));
            preview.confirmed = new Saved(company, year, kind, result.journalNumber(), result.voucherNumber(), result.documentIds());
            return preview.confirmed;
        }
    }
}
