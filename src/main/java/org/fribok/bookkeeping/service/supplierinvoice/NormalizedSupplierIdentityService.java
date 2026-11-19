package org.fribok.bookkeeping.service.supplierinvoice;

import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDatesService.Kind;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Supplier-number link only; caller owns transaction, commit and exclusive writer gate. */
public final class NormalizedSupplierIdentityService {
    /** Exact company-scoped supplier identities; no defaults or register values are copied. */
    public record Supplier(long id, int legacyId, String number, String name) {}

    /** Explicit supplier number; real changes require exactly one company-scoped supplier. */
    public record Values(String supplierNumber) {}

    /** Complete document source and immutable supplier-number catalogue. */
    public static final class Snapshot {
        private final NormalizedSupplierDatesService.Snapshot document;
        private final List<Supplier> suppliers;
        private final String number;

        private Snapshot(
                NormalizedSupplierDatesService.Snapshot document,
                List<Supplier> suppliers,
                String number) {
            this.document = document;
            this.number = number;
            this.suppliers = List.copyOf(suppliers);
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
            return new Values(number);
        }

        public List<Supplier> suppliers() {
            return suppliers;
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
    public NormalizedSupplierIdentityService(Connection connection) {
        this.connection = Objects.requireNonNull(connection);
    }

    /** Reads the raw supplier number, stored dates and exact company catalogue without defaults. */
    public Snapshot load(int company, Kind kind, long id) throws SQLException {
        var document = new NormalizedSupplierDatesService(connection).load(company, kind, id);
        String table = kind == Kind.INVOICE ? "supplier_invoice" : "supplier_credit_invoice";
        try (var statement =
                connection.prepareStatement(
                        "SELECT supplier_number FROM "
                                + table
                                + " WHERE id=? AND company_id=(SELECT id FROM company WHERE"
                                + " legacy_id=?)")) {
            statement.setLong(1, id);
            statement.setInt(2, company);
            try (var row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("Missing or foreign supplier document");
                }
                return new Snapshot(document, suppliers(company), row.getString(1));
            }
        }
    }

    /** Revalidates complete document and catalogue; never recalculates or repairs dates. */
    public Plan preview(Snapshot expected, Values proposed) throws SQLException {
        Objects.requireNonNull(expected, "snapshot");
        Objects.requireNonNull(proposed, "values");
        new NormalizedSupplierDatesService(connection)
                .preview(expected.document, expected.document.values());
        var fresh = suppliers(expected.companyId());
        if (!fresh.equals(expected.suppliers)) {
            throw new SQLException("Supplier-number catalogue changed; reload");
        }
        if (!expected.values().equals(proposed)) {
            String number = proposed.supplierNumber();
            if (number == null
                    || number.isBlank()
                    || number.length() > 255
                    || number.codePoints().anyMatch(Character::isISOControl)
                    || fresh.stream().filter(supplier -> number.equals(supplier.number())).count()
                            != 1) {
                throw new IllegalArgumentException(
                        "Choose an exact unique supplier number in this company");
            }
        }
        return new Plan(expected, proposed);
    }

    /** Updates only the changed supplier number under a savepoint; name and snapshots stay raw. */
    public Updated confirm(Plan expected) throws SQLException {
        Objects.requireNonNull(expected, "plan");
        if (connection.getAutoCommit()) {
            throw new SQLException("Supplier-number editing requires a caller-owned transaction");
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
                                        + " SET supplier_number=? WHERE id=? AND"
                                        + " company_id=(SELECT id FROM company WHERE"
                                        + " legacy_id=?)")) {
                    statement.setString(1, fresh.after.supplierNumber());
                    statement.setLong(2, fresh.source.id());
                    statement.setInt(3, fresh.source.companyId());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Missing or foreign supplier-number update target");
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

    private List<Supplier> suppliers(int company) throws SQLException {
        List<Supplier> values = new ArrayList<>();
        try (var statement =
                connection.prepareStatement(
                        "SELECT id,legacy_id,number,name FROM supplier WHERE company_id=(SELECT id"
                                + " FROM company WHERE legacy_id=?) ORDER BY id")) {
            statement.setInt(1, company);
            try (var row = statement.executeQuery()) {
                while (row.next()) {
                    values.add(
                            new Supplier(
                                    row.getLong(1),
                                    row.getInt(2),
                                    row.getString(3),
                                    row.getString(4)));
                }
            }
        }
        return List.copyOf(values);
    }
}
