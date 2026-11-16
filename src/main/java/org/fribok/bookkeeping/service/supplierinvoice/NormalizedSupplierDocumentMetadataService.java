package org.fribok.bookkeeping.service.supplierinvoice;

import org.fribok.bookkeeping.dataformat.CanonicalFingerprintWriter;
import org.fribok.bookkeeping.dataformat.NormalizedSupplierInvoiceStore;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
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
