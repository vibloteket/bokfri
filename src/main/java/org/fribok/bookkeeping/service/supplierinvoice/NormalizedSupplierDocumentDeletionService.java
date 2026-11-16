package org.fribok.bookkeeping.service.supplierinvoice;

import org.fribok.bookkeeping.dataformat.CanonicalFingerprintWriter;
import org.fribok.bookkeeping.dataformat.NormalizedSupplierInvoiceStore;

import se.swedsoft.bookkeeping.calc.math.SSSupplierInvoiceMath;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Read-only supplier deletion plans and stale-guarded execution; caller owns transaction and
 * exclusive gate.
 */
public final class NormalizedSupplierDocumentDeletionService {
    /** Supplier invoice or supplier credit family. */
    public enum Kind {
        INVOICE,
        CREDIT
    }

    /**
     * Immutable deletion presentation and a private complete-source token; cannot retarget a
     * deletion.
     */
    public static final class Plan {
        private final int company;
        private final Kind kind;
        private final long id;
        private final Integer number;
        private final String supplier;
        private final LocalDate date;
        private final String currency;
        private final BigDecimal total;
        private final boolean stockInfluencing;
        private final String signature;

        private Plan(
                int company,
                Kind kind,
                long id,
                Integer number,
                String supplier,
                LocalDate date,
                String currency,
                BigDecimal total,
                boolean stockInfluencing,
                String signature) {
            this.company = company;
            this.kind = kind;
            this.id = id;
            this.number = number;
            this.supplier = supplier;
            this.date = date;
            this.currency = currency;
            this.total = total;
            this.stockInfluencing = stockInfluencing;
            this.signature = signature;
        }

        /** Company migration identity. */
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

        /**
         * Stored business number; historical absent/ambiguous numbers do not replace SQL identity.
         */
        public Integer number() {
            return number;
        }

        /** Stored supplier name. */
        public String supplier() {
            return supplier;
        }

        /** Stored document date. */
        public LocalDate date() {
            return date;
        }

        /** Stored currency code, if present. */
        public String currency() {
            return currency;
        }

        /** Existing business total for explicit confirmation, not a converted/repriced amount. */
        public BigDecimal total() {
            return total;
        }

        /** Whether removal can affect calculated stock. */
        public boolean stockInfluencing() {
            return stockInfluencing;
        }
    }

    /** Deleted identity; no linked documents are detached or modified. */
    public record Deleted(int companyId, Kind kind, long id) {}

    private final Connection connection;

    /** Binds a caller-owned connection; construction does not read or change SQL. */
    public NormalizedSupplierDocumentDeletionService(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * Validates the exact deletion policy and fingerprints the complete source without any writes.
     */
    public Plan plan(int company, Kind kind, long id) throws SQLException {
        Objects.requireNonNull(kind, "kind");
        var store = new NormalizedSupplierInvoiceStore(connection);
        if (kind == Kind.INVOICE) {
            store.validateSupplierInvoiceDeletion(company, id);
        } else {
            store.validateSupplierCreditInvoiceDeletion(company, id);
        }
        var document =
                kind == Kind.INVOICE
                        ? store.findSupplierInvoice(company, id).orElseThrow().document()
                        : store.findSupplierCreditInvoice(company, id).orElseThrow().document();
        var writer = new CanonicalFingerprintWriter();
        writer.writeInteger(company).writeBoolean(kind == Kind.CREDIT).writeLong(id);
        SupplierDocumentFingerprint.write(writer, connection, kind == Kind.CREDIT, id);
        return new Plan(
                company,
                kind,
                id,
                document.getNumber(),
                document.getSupplierName(),
                document.getLocalDate(),
                document.getCurrency() == null ? null : document.getCurrency().getName(),
                SSSupplierInvoiceMath.getTotalSum(document),
                document.isStockInfluencing(),
                writer.finish());
    }

    /**
     * Rechecks live references and the entire source graph, then deletes under a savepoint. Never
     * commits or detaches linked orders/payments/credits; prior caller work survives failure.
     */
    public Deleted delete(Plan expected) throws SQLException {
        Objects.requireNonNull(expected, "plan");
        if (connection.getAutoCommit()) {
            throw new SQLException("Deletion requires a caller-owned transaction");
        }
        Savepoint savepoint = connection.setSavepoint();
        try {
            var fresh = plan(expected.company, expected.kind, expected.id);
            if (!fresh.signature.equals(expected.signature)) {
                throw new SQLException("Stale deletion confirmation; reload before deleting");
            }
            var store = new NormalizedSupplierInvoiceStore(connection);
            if (expected.kind == Kind.INVOICE) {
                store.deleteSupplierInvoice(expected.company, expected.id);
            } else {
                store.deleteSupplierCreditInvoice(expected.company, expected.id);
            }
            connection.releaseSavepoint(savepoint);
            return new Deleted(expected.company, expected.kind, expected.id);
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                connection.rollback(savepoint);
            } catch (SQLException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }
}
