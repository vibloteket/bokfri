package org.fribok.bookkeeping.service.supplierinvoice;

import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDatesService.Kind;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Payment-term link only; caller owns transaction, commit and exclusive writer gate. */
public final class NormalizedSupplierPaymentTermService {
    /** Exact global catalogue values, without parsing names into dates or applying defaults. */
    public record Term(String name, String description) {}

    /** Explicit term name; NULL explicitly clears the link without changing dates. */
    public record Values(String paymentTerm) {}

    /** Complete document source and immutable payment-term catalogue. */
    public static final class Snapshot {
        private final NormalizedSupplierDatesService.Snapshot document;
        private final List<Term> terms;

        private Snapshot(NormalizedSupplierDatesService.Snapshot document, List<Term> terms) {
            this.document = document;
            this.terms = List.copyOf(terms);
        }

        public int companyId() {
            return document.companyId();
        }

        public Kind kind() {
            return document.kind();
        }

        public long id() {
            return document.id();
        }

        public Integer number() {
            return document.number();
        }

        public String supplier() {
            return document.supplier();
        }

        public NormalizedSupplierDatesService.Values dates() {
            return document.values();
        }

        public Values values() {
            return new Values(document.paymentTerm());
        }

        public List<Term> terms() {
            return terms;
        }
    }

    /** Private source-bound proposal, including the complete original catalogue. */
    public static final class Plan {
        private final Snapshot source;
        private final Values after;

        private Plan(Snapshot source, Values after) {
            this.source = source;
            this.after = after;
        }

        public Snapshot source() {
            return source;
        }

        public Values before() {
            return source.values();
        }

        public Values after() {
            return after;
        }

        public boolean changed() {
            return !before().equals(after);
        }
    }

    /** Saved identity; caller must still commit its outer transaction. */
    public record Updated(int companyId, Kind kind, long id) {}

    private final Connection connection;

    /** Binds caller-owned connection without SQL access or migration. */
    public NormalizedSupplierPaymentTermService(Connection connection) {
        this.connection = Objects.requireNonNull(connection);
    }

    /** Reads raw stored term/dates and the exact live catalogue, without legacy constructors. */
    public Snapshot load(int company, Kind kind, long id) throws SQLException {
        return new Snapshot(
                new NormalizedSupplierDatesService(connection).load(company, kind, id), terms());
    }

    /** Revalidates complete document and catalogue; never recalculates or repairs dates. */
    public Plan preview(Snapshot expected, Values proposed) throws SQLException {
        Objects.requireNonNull(expected, "snapshot");
        Objects.requireNonNull(proposed, "values");
        new NormalizedSupplierDatesService(connection)
                .preview(expected.document, expected.document.values());
        var fresh = terms();
        if (!fresh.equals(expected.terms)) {
            throw new SQLException("Payment-term catalogue changed; reload");
        }
        if (!expected.values().equals(proposed) && proposed.paymentTerm() != null) {
            String name = proposed.paymentTerm();
            if (name.isBlank()
                    || name.length() > 255
                    || name.codePoints().anyMatch(Character::isISOControl)
                    || fresh.stream().noneMatch(term -> name.equals(term.name()))) {
                throw new IllegalArgumentException(
                        "Choose an exact registered payment term or explicit NULL");
            }
        }
        return new Plan(expected, proposed);
    }

    /** Updates only the changed term link under a savepoint; dates and all snapshots stay raw. */
    public Updated confirm(Plan expected) throws SQLException {
        Objects.requireNonNull(expected, "plan");
        if (connection.getAutoCommit()) {
            throw new SQLException("Payment-term editing requires a caller-owned transaction");
        }
        Savepoint savepoint = connection.setSavepoint();
        try {
            var fresh = preview(expected.source, expected.after);
            if (fresh.changed()) {
                String table =
                        fresh.source.kind() == Kind.INVOICE
                                ? "supplier_invoice"
                                : "supplier_credit_invoice";
                try (var statement =
                        connection.prepareStatement(
                                "UPDATE "
                                        + table
                                        + " SET payment_term_name=? WHERE id=? AND"
                                        + " company_id=(SELECT id FROM company WHERE"
                                        + " legacy_id=?)")) {
                    statement.setString(1, fresh.after.paymentTerm());
                    statement.setLong(2, fresh.source.id());
                    statement.setInt(3, fresh.source.companyId());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException(
                                "Missing or foreign supplier payment-term update target");
                    }
                }
            }
            connection.releaseSavepoint(savepoint);
            return new Updated(fresh.source.companyId(), fresh.source.kind(), fresh.source.id());
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                connection.rollback(savepoint);
            } catch (SQLException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }

    private List<Term> terms() throws SQLException {
        List<Term> values = new ArrayList<>();
        try (var statement =
                        connection.prepareStatement(
                                "SELECT name,description FROM payment_term ORDER BY name");
                var row = statement.executeQuery()) {
            while (row.next()) values.add(new Term(row.getString(1), row.getString(2)));
        }
        return List.copyOf(values);
    }
}
