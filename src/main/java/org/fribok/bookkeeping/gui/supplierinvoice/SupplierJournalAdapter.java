package org.fribok.bookkeeping.gui.supplierinvoice;

import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierJournalService;

import se.swedsoft.bookkeeping.calc.math.SSVoucherMath;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Explicit supplier journal preview/confirmation boundary; exposes no mutable domain snapshots. */
public final class SupplierJournalAdapter {
    /** Supplier invoice or supplier credit journal. */
    public enum Kind {
        INVOICE,
        CREDIT
    }

    /** Immutable company/year/document-family binding. */
    public record Target(int companyId, int yearId, Kind kind) {}

    /** Stable SQL identity and display-only supplier document values. */
    public record Document(long id, Integer number, LocalDate date, String supplier) {}

    /** Read-only generated journal row; dimension codes remain strings. */
    public record Row(
            Integer account,
            String project,
            String resultUnit,
            BigDecimal debit,
            BigDecimal credit) {}

    /** Immutable summary with the actual proposed journal/voucher allocation and totals. */
    public record Summary(
            int companyId,
            int yearId,
            Kind kind,
            LocalDate from,
            LocalDate to,
            int journalNumber,
            int voucherNumber,
            LocalDate voucherDate,
            List<Document> documents,
            List<Row> rows,
            BigDecimal debit,
            BigDecimal credit) {
        public Summary {
            documents = List.copyOf(documents);
            rows = List.copyOf(rows);
        }
    }

    /** Private service token bound to one adapter; UI code can read only the immutable summary. */
    public static final class Preview {
        private final SupplierJournalAdapter owner;
        private final NormalizedSupplierJournalService.Plan plan;
        private final Summary summary;
        private Saved confirmed;

        private Preview(
                SupplierJournalAdapter owner,
                NormalizedSupplierJournalService.Plan plan,
                Summary summary) {
            this.owner = owner;
            this.plan = plan;
            this.summary = summary;
        }

        /** Values suitable for tables/confirmation; no hydrated domain objects escape. */
        public Summary summary() {
            return summary;
        }
    }

    /** Confirmed committed identities for explicit owner refresh, not legacy cache mutation. */
    public record Saved(
            int companyId,
            int yearId,
            Kind kind,
            int journalNumber,
            int voucherNumber,
            List<Long> documentIds) {
        public Saved {
            documentIds = List.copyOf(documentIds);
        }
    }

    private final SupplierDocumentGuiSession session;
    private final Kind kind;

    SupplierJournalAdapter(SupplierDocumentGuiSession session, Kind kind) {
        this.session = Objects.requireNonNull(session, "session");
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    /** Explicit selection/family associated with every preview and confirmed result. */
    public Target target() {
        return new Target(session.companyId(), session.yearId(), kind);
    }

    /** Whether selection replacement/closure invalidated future actions. */
    public boolean isActive() {
        return session.isActive();
    }

    /** Unknown commit outcome blocks every journal adapter belonging to this selection. */
    public boolean isBlocked() {
        return session.isBlocked();
    }

    /**
     * Generates read-only immutable presentation in a fresh, explicitly selected normalized
     * session.
     */
    public Preview preview(LocalDate from, LocalDate to) throws SQLException {
        return session.run(
                false,
                (connection, company, year) -> {
                    var service = new NormalizedSupplierJournalService(connection, session.clock);
                    var plan =
                            kind == Kind.INVOICE
                                    ? service.planInvoices(company.getId(), year.getId(), from, to)
                                    : service.planCredits(company.getId(), year.getId(), from, to);
                    var voucher =
                            kind == Kind.INVOICE
                                    ? plan.invoicePlan().voucher()
                                    : plan.creditPlan().voucher();
                    List<? extends SSSupplierInvoice> selected =
                            kind == Kind.INVOICE
                                    ? plan.invoicePlan().invoices()
                                    : plan.creditPlan().invoices();
                    List<Document> documents = new ArrayList<>();
                    var ids = plan.documentIds();
                    for (int i = 0; i < selected.size(); i++) {
                        var document = selected.get(i);
                        documents.add(
                                new Document(
                                        ids.get(i),
                                        document.getNumber(),
                                        document.getLocalDate(),
                                        document.getSupplierName()));
                    }
                    List<Row> rows =
                            voucher.getRows().stream()
                                    .map(
                                            row ->
                                                    new Row(
                                                            row.getAccountNr(),
                                                            row.getProjectNr(),
                                                            row.getResultUnitNr(),
                                                            row.getDebet(),
                                                            row.getCredit()))
                                    .toList();
                    var accounts =
                            new NormalizedAccountingStore(connection, session.clock)
                                    .getAccounts(year);
                    var summary =
                            new Summary(
                                    company.getId(),
                                    year.getId(),
                                    kind,
                                    from,
                                    to,
                                    plan.journalNumber(),
                                    plan.voucherNumber(),
                                    voucher.getLocalDate(),
                                    documents,
                                    rows,
                                    SSVoucherMath.getDebetSum(voucher, accounts),
                                    SSVoucherMath.getCreditSum(voucher, accounts));
                    return new Preview(this, plan, summary);
                });
    }

    /**
     * Revalidates and commits once in a dedicated transaction. Replaying a confirmed preview
     * returns the same result without another connection/write; foreign, stale or blocked requests
     * fail closed.
     */
    public Saved post(Preview preview) throws SQLException {
        synchronized (session.gate) {
            session.requireAvailable();
            if (preview == null || preview.owner != this) {
                throw new SQLException("Foreign supplier journal preview");
            }
            if (preview.confirmed != null) {
                return preview.confirmed;
            }
            var result =
                    session.run(
                            true,
                            (connection, company, year) ->
                                    new NormalizedSupplierJournalService(connection, session.clock)
                                            .post(preview.plan));
            preview.confirmed =
                    new Saved(
                            session.companyId(),
                            session.yearId(),
                            kind,
                            result.journalNumber(),
                            result.voucherNumber(),
                            result.documentIds());
            return preview.confirmed;
        }
    }
}
