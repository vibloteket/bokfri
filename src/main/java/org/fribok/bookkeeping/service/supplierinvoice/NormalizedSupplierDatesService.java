package org.fribok.bookkeeping.service.supplierinvoice;

import org.fribok.bookkeeping.dataformat.CanonicalFingerprintWriter;
import org.fribok.bookkeeping.dataformat.NormalizedSupplierInvoiceStore;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded supplier header dates; caller owns transaction, commit and exclusive writer gate. */
public final class NormalizedSupplierDatesService {
    /** Supplier invoice or credit family. */
    public enum Kind {
        INVOICE,
        CREDIT
    }

    /** Explicit dates only; no payment-term calculation or accounting snapshot changes. */
    public record Values(LocalDate invoiceDate, LocalDate dueDate) {}

    /** Immutable raw SQL source and complete document fingerprint. */
    public static final class Snapshot {
        private final int company;
        private final Kind kind;
        private final long id;
        private final Integer number;
        private final String supplier;
        private final String paymentTerm;
        private final Values values;
        private final String signature;

        private Snapshot(
                int company,
                Kind kind,
                long id,
                Integer number,
                String supplier,
                String paymentTerm,
                Values values,
                String signature) {
            this.company = company;
            this.kind = kind;
            this.id = id;
            this.number = number;
            this.supplier = supplier;
            this.paymentTerm = paymentTerm;
            this.values = values;
            this.signature = signature;
        }

        public int companyId() {
            return company;
        }

        public Kind kind() {
            return kind;
        }

        public long id() {
            return id;
        }

        public Integer number() {
            return number;
        }

        public String supplier() {
            return supplier;
        }

        public String paymentTerm() {
            return paymentTerm;
        }

        public Values values() {
            return values;
        }
    }

    /** Source-bound exact before/after request; construction is private. */
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
            return source.values;
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
    public NormalizedSupplierDatesService(Connection connection) {
        this.connection = Objects.requireNonNull(connection);
    }

    /** Reads raw stored dates after current unbooked/unexported/unlinked eligibility. */
    public Snapshot load(int company, Kind kind, long id) throws SQLException {
        Objects.requireNonNull(kind, "kind");
        var store = new NormalizedSupplierInvoiceStore(connection);
        if (kind == Kind.INVOICE) {
            store.validateSupplierInvoiceEditing(company, id);
        } else {
            store.validateSupplierCreditInvoiceEditing(company, id);
        }
        try (var statement =
                connection.prepareStatement(
                        "SELECT number,supplier_name,payment_term_name,invoice_date,due_date FROM "
                                + table(kind)
                                + " WHERE id=? AND company_id=(SELECT id FROM company WHERE"
                                + " legacy_id=?)")) {
            statement.setLong(1, id);
            statement.setInt(2, company);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Missing or foreign supplier date target");
                }
                var writer = new CanonicalFingerprintWriter();
                writer.writeInteger(company).writeBoolean(kind == Kind.CREDIT).writeLong(id);
                SupplierDocumentFingerprint.write(writer, connection, kind == Kind.CREDIT, id);
                return new Snapshot(
                        company,
                        kind,
                        id,
                        result.getObject(1, Integer.class),
                        result.getString(2),
                        result.getString(3),
                        new Values(
                                result.getObject(4, LocalDate.class),
                                result.getObject(5, LocalDate.class)),
                        writer.finish());
            }
        }
    }

    /** Rechecks the complete source without writes; unchanged historical dates are not repaired. */
    public Plan preview(Snapshot expected, Values proposed) throws SQLException {
        Objects.requireNonNull(expected, "snapshot");
        Objects.requireNonNull(proposed, "dates");
        var fresh = load(expected.company, expected.kind, expected.id);
        if (!fresh.signature.equals(expected.signature)) {
            throw new SQLException("Stale supplier dates; reload before editing");
        }
        if (!expected.values.equals(proposed)) {
            validDate(proposed.invoiceDate());
            validDate(proposed.dueDate());
            if (proposed.dueDate().isBefore(proposed.invoiceDate())) {
                throw new IllegalArgumentException("Due date precedes invoice date");
            }
        }
        return new Plan(expected, proposed);
    }

    /** Revalidates references/source and changes only requested date columns under a savepoint. */
    public Updated confirm(Plan expected) throws SQLException {
        Objects.requireNonNull(expected, "plan");
        if (connection.getAutoCommit()) {
            throw new SQLException("Date editing requires a caller-owned transaction");
        }
        Savepoint savepoint = connection.setSavepoint();
        try {
            var fresh = preview(expected.source, expected.after);
            boolean invoice =
                    !Objects.equals(fresh.before().invoiceDate(), fresh.after.invoiceDate());
            boolean due = !Objects.equals(fresh.before().dueDate(), fresh.after.dueDate());
            List<String> columns = new ArrayList<>();
            if (invoice) {
                columns.add("invoice_date=?");
            }
            if (due) {
                columns.add("due_date=?");
            }
            if (!columns.isEmpty()) {
                try (var statement =
                        connection.prepareStatement(
                                "UPDATE "
                                        + table(fresh.source.kind)
                                        + " SET "
                                        + String.join(",", columns)
                                        + " WHERE id=? AND company_id=(SELECT id FROM company WHERE"
                                        + " legacy_id=?)")) {
                    int parameter = 1;
                    if (invoice) {
                        statement.setObject(parameter++, fresh.after.invoiceDate(), Types.DATE);
                    }
                    if (due) {
                        statement.setObject(parameter++, fresh.after.dueDate(), Types.DATE);
                    }
                    statement.setLong(parameter++, fresh.source.id);
                    statement.setInt(parameter, fresh.source.company);
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Missing or foreign supplier date update target");
                    }
                }
            }
            connection.releaseSavepoint(savepoint);
            return new Updated(fresh.source.company, fresh.source.kind, fresh.source.id);
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                connection.rollback(savepoint);
            } catch (SQLException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }

    private static void validDate(LocalDate date) {
        if (date == null || date.getYear() < 1 || date.getYear() > 9999) {
            throw new IllegalArgumentException(
                    "Changed dates require explicit ISO dates in years 0001..9999");
        }
    }

    private static String table(Kind kind) {
        return kind == Kind.INVOICE ? "supplier_invoice" : "supplier_credit_invoice";
    }
}
