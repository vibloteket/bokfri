package org.fribok.bookkeeping.service.supplierinvoice;

import org.fribok.bookkeeping.dataformat.CanonicalFingerprintWriter;
import org.fribok.bookkeeping.dataformat.NormalizedSupplierInvoiceStore;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierRowAmountsService.Row;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded raw supplier metadata reads/updates; caller owns transaction, commit and exclusive writer
 * gate.
 */
public final class NormalizedSupplierDocumentMetadataService {
    /** Supplier invoice or supplier credit family; never inferred from a GUI row position. */
    public enum Kind {
        INVOICE,
        CREDIT
    }

    /**
     * Immutable raw stored values and private full-source token, without domain hydration/defaults.
     */
    public static final class Snapshot {
        private final int company;
        private final Kind kind;
        private final long id;
        private final Integer number;
        private final String supplierName;
        private final String reference;
        private final String signature;

        private Snapshot(
                int company,
                Kind kind,
                long id,
                Integer number,
                String supplierName,
                String reference,
                String signature) {
            this.company = company;
            this.kind = kind;
            this.id = id;
            this.number = number;
            this.supplierName = supplierName;
            this.reference = reference;
            this.signature = signature;
        }

        /** Explicit company migration identity. */
        public int companyId() {
            return company;
        }

        /** Document family. */
        public Kind kind() {
            return kind;
        }

        /** Stable SQL document identity. */
        public long id() {
            return id;
        }

        /** Stored business number, possibly null/ambiguous; not used as update identity. */
        public Integer number() {
            return number;
        }

        /** Stored display name, including null/blank/whitespace distinctions. */
        public String supplierName() {
            return supplierName;
        }

        /** Stored invoice reference, not an automatically generated OCR or supplier identity. */
        public String reference() {
            return reference;
        }
    }

    /** Successfully updated identity; the caller must still commit its transaction. */
    public record Updated(int companyId, Kind kind, long id) {}

    private final Connection connection;

    /** Binds a caller-owned connection without SQL access or migration. */
    public NormalizedSupplierDocumentMetadataService(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * Reads only raw header values and the complete source token after validating current edit
     * eligibility.
     */
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
                        "SELECT number,supplier_name,reference_number FROM "
                                + table(kind)
                                + " WHERE id=? AND company_id=(SELECT id FROM company WHERE"
                                + " legacy_id=?)")) {
            statement.setLong(1, id);
            statement.setInt(2, company);
            try (var row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("Missing or foreign supplier metadata target");
                }
                var writer = new CanonicalFingerprintWriter();
                writer.writeInteger(company).writeBoolean(kind == Kind.CREDIT).writeLong(id);
                SupplierDocumentFingerprint.write(writer, connection, kind == Kind.CREDIT, id);
                return new Snapshot(
                        company,
                        kind,
                        id,
                        row.getObject(1, Integer.class),
                        row.getString(2),
                        row.getString(3),
                        writer.finish());
            }
        }
    }

    /**
     * Revalidates eligibility and the full source, then updates only explicitly changed metadata
     * columns. Unchanged historical values are never validated into defaults, normalized or
     * rewritten.
     */
    public Updated update(Snapshot expected, String supplierName, String reference)
            throws SQLException {
        Objects.requireNonNull(expected, "snapshot");
        if (connection.getAutoCommit()) {
            throw new SQLException("Metadata editing requires a caller-owned transaction");
        }
        boolean nameChanged = !Objects.equals(supplierName, expected.supplierName);
        boolean referenceChanged = !Objects.equals(reference, expected.reference);
        if (nameChanged) {
            validate(supplierName);
        }
        if (referenceChanged) {
            validate(reference);
        }
        Savepoint savepoint = connection.setSavepoint();
        try {
            var fresh = load(expected.company, expected.kind, expected.id);
            if (!fresh.signature.equals(expected.signature)) {
                throw new SQLException("Stale metadata snapshot; reload before editing");
            }
            if (nameChanged || referenceChanged) {
                String columns =
                        nameChanged && referenceChanged
                                ? "supplier_name=?,reference_number=?"
                                : nameChanged ? "supplier_name=?" : "reference_number=?";
                try (var statement =
                        connection.prepareStatement(
                                "UPDATE "
                                        + table(expected.kind)
                                        + " SET "
                                        + columns
                                        + " WHERE id=? AND company_id=(SELECT id FROM company WHERE"
                                        + " legacy_id=?)")) {
                    int parameter = 1;
                    if (nameChanged) {
                        statement.setString(parameter++, supplierName);
                    }
                    if (referenceChanged) {
                        statement.setString(parameter++, reference);
                    }
                    statement.setLong(parameter++, expected.id);
                    statement.setInt(parameter, expected.company);
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Missing or foreign metadata update target");
                    }
                }
            }
            connection.releaseSavepoint(savepoint);
            return new Updated(expected.company, expected.kind, expected.id);
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                connection.rollback(savepoint);
            } catch (SQLException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }

    /** Stored row key and proposed description only; no row identity or financial edits. */
    public record RowValue(int key, String description) {}

    /** Raw immutable row presentation plus the private complete-document metadata source. */
    public static final class RowSnapshot {
        private final Snapshot source;
        private final List<Row> rows;

        private RowSnapshot(Snapshot source, List<Row> rows) {
            this.source = source;
            this.rows = List.copyOf(rows);
        }

        public int companyId() {
            return source.companyId();
        }

        public Kind kind() {
            return source.kind();
        }

        public long id() {
            return source.id();
        }

        public Integer number() {
            return source.number();
        }

        public String supplier() {
            return source.supplierName();
        }

        public List<Row> rows() {
            return rows;
        }
    }

    /** Exact raw before/after values for one persisted key. */
    public record RowChange(RowValue before, RowValue after) {}

    /** Complete-source-bound immutable request; construction is private. */
    public static final class RowPlan {
        private final RowSnapshot source;
        private final List<RowValue> proposed;
        private final List<RowChange> changes;

        private RowPlan(RowSnapshot source, List<RowValue> proposed, List<RowChange> changes) {
            this.source = source;
            this.proposed = List.copyOf(proposed);
            this.changes = List.copyOf(changes);
        }

        public RowSnapshot source() {
            return source;
        }

        public List<RowChange> changes() {
            return changes;
        }
    }

    /** Loads raw rows through the existing immutable projection; no defaulting domain getters. */
    public RowSnapshot loadRows(int company, Kind kind, long id) throws SQLException {
        var source = load(company, kind, id);
        var rows =
                new NormalizedSupplierRowAmountsService(connection)
                        .load(
                                company,
                                kind == Kind.CREDIT
                                        ? NormalizedSupplierRowAmountsService.Kind.CREDIT
                                        : NormalizedSupplierRowAmountsService.Kind.INVOICE,
                                id)
                        .rows();
        return new RowSnapshot(source, rows);
    }

    /**
     * Rechecks full source and immutable keys without writes; unchanged history is never repaired.
     */
    public RowPlan previewRows(RowSnapshot expected, List<RowValue> proposed) throws SQLException {
        Objects.requireNonNull(expected, "snapshot");
        Objects.requireNonNull(proposed, "descriptions");
        var fresh = loadRows(expected.companyId(), expected.kind(), expected.id());
        if (!fresh.source.signature.equals(expected.source.signature)) {
            throw new SQLException("Stale supplier row descriptions; reload");
        }
        if (proposed.size() != expected.rows.size()) {
            throw new IllegalArgumentException("Row insertion/removal is not supported");
        }
        Map<Integer, RowValue> values = new HashMap<>();
        for (var value : new ArrayList<>(proposed)) {
            if (value == null || values.put(value.key(), value) != null) {
                throw new IllegalArgumentException("Duplicate or missing row key");
            }
        }
        List<RowValue> ordered = new ArrayList<>();
        List<RowChange> changes = new ArrayList<>();
        for (var row : expected.rows) {
            var next = values.remove(row.key());
            if (next == null) {
                throw new IllegalArgumentException("Foreign or missing row key");
            }
            var before = new RowValue(row.key(), row.description());
            if (!Objects.equals(before.description(), next.description())) {
                String description = next.description();
                if (description == null
                        || description.isBlank()
                        || description.length() > 4096
                        || description.codePoints().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException(
                            "Changed descriptions require nonblank text, at most 4096 characters,"
                                    + " without control characters");
                }
                changes.add(new RowChange(before, next));
            }
            ordered.add(next);
        }
        if (!values.isEmpty()) {
            throw new IllegalArgumentException("Foreign row key");
        }
        return new RowPlan(expected, ordered, changes);
    }

    /** Revalidates eligibility/source and updates only changed descriptions under a savepoint. */
    public Updated confirmRows(RowPlan expected) throws SQLException {
        Objects.requireNonNull(expected, "plan");
        if (connection.getAutoCommit()) {
            throw new SQLException("Description editing requires a caller-owned transaction");
        }
        Savepoint savepoint = connection.setSavepoint();
        try {
            var fresh = previewRows(expected.source, expected.proposed);
            String table = table(fresh.source.kind());
            for (var change : fresh.changes) {
                try (var statement =
                        connection.prepareStatement(
                                "UPDATE "
                                        + table
                                        + "_row SET description=? WHERE "
                                        + table
                                        + "_id=? AND row_number=? AND company_id=(SELECT id FROM"
                                        + " company WHERE legacy_id=?)")) {
                    statement.setString(1, change.after().description());
                    statement.setLong(2, fresh.source.id());
                    statement.setInt(3, change.after().key());
                    statement.setInt(4, fresh.source.companyId());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Missing or foreign supplier description target");
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

    private static void validate(String value) {
        if (value != null
                && (value.length() > 255 || value.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException(
                    "Changed metadata must have at most 255 characters and no control characters");
        }
    }

    private static String table(Kind kind) {
        return kind == Kind.INVOICE ? "supplier_invoice" : "supplier_credit_invoice";
    }
}
