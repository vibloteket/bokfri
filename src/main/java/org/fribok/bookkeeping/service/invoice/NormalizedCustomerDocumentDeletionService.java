package org.fribok.bookkeeping.service.invoice;

import org.fribok.bookkeeping.dataformat.CanonicalFingerprintWriter;
import org.fribok.bookkeeping.dataformat.NormalizedInvoiceStore;
import org.fribok.bookkeeping.dataformat.NormalizedSaleDocumentStore;
import java.sql.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Read-only deletion plans and guarded execution; caller owns transaction, commit and exclusive gate. */
public final class NormalizedCustomerDocumentDeletionService {
    public enum Kind { INVOICE, CREDIT }
    public static final class Plan {
        private final int company;
        private final Kind kind;
        private final long id;
        private final Integer number;
        private final String customer;
        private final LocalDate date;
        private final List<Long> orders;
        private final String signature;
        private Plan(int company, Kind kind, long id, Integer number, String customer, LocalDate date, List<Long> orders, String signature) {
            this.company = company; this.kind = kind; this.id = id; this.number = number; this.customer = customer;
            this.date = date; this.orders = List.copyOf(orders); this.signature = signature;
        }
        public int companyId() { return company; }
        public Kind kind() { return kind; }
        public long id() { return id; }
        public Integer number() { return number; }
        public String customer() { return customer; }
        public LocalDate date() { return date; }
        public List<Long> detachedOrderIds() { return orders; }
    }
    public record Deleted(int companyId, Kind kind, long id, List<Long> detachedOrderIds) {
        public Deleted { detachedOrderIds = List.copyOf(detachedOrderIds); }
    }
    private final Connection connection;
    public NormalizedCustomerDocumentDeletionService(Connection connection) { this.connection = Objects.requireNonNull(connection); }

    public Plan plan(int company, Kind kind, long id) throws SQLException {
        Objects.requireNonNull(kind, "kind");
        var store = new NormalizedInvoiceStore(connection);
        se.swedsoft.bookkeeping.data.SSInvoice document = kind == Kind.INVOICE
                ? store.findInvoice(company, id).orElseThrow(() -> new SQLException("Missing or foreign invoice")).document()
                : store.findCreditInvoice(company, id).orElseThrow(() -> new SQLException("Missing or foreign credit")).document();
        if (document.isEntered()) throw new SQLException("Booked documents cannot be deleted");
        List<Long> orders = List.of();
        if (kind == Kind.INVOICE && document.getNumber() != null) {
            int number = document.getNumber();
            if (referenced(company, number, "inpayment_row", "invoice_number", true)
                    || referenced(company, number, "customer_credit_invoice", "crediting_invoice_number", false))
                throw new SQLException("Invoice is referenced by a payment or credit");
            orders = new NormalizedSaleDocumentStore(connection).getOrders(company).stream()
                    .filter(row -> Objects.equals(row.document().getInvoiceNr(), number)).map(row -> row.id()).toList();
            if (!orders.isEmpty() && store.listInvoices(company).stream().filter(row -> Objects.equals(row.document().getNumber(), number)).count() != 1)
                throw new SQLException("Ambiguous invoice number owns linked orders");
        }
        var writer = new CanonicalFingerprintWriter();
        String prefix = kind == Kind.INVOICE ? "customer_invoice" : "customer_credit_invoice";
        String owner = kind == Kind.INVOICE ? "invoice_id" : "credit_invoice_id";
        signature(writer, prefix, "id", id);
        for (String suffix : List.of("_address", "_default_account", "_row", "_voucher", "_voucher_row")) signature(writer, prefix + suffix, owner, id);
        writer.writeBoundary("orders"); for (long order : orders) writer.writeLong(order);
        return new Plan(company, kind, id, document.getNumber(), document.getCustomerName(), document.getLocalDate(), orders, writer.finish());
    }
    public Deleted delete(Plan expected) throws SQLException {
        Objects.requireNonNull(expected, "plan");
        if (connection.getAutoCommit()) throw new SQLException("Deletion requires a caller-owned transaction");
        Savepoint savepoint = connection.setSavepoint();
        try {
            var fresh = plan(expected.company, expected.kind, expected.id);
            if (!expected.signature.equals(fresh.signature)) throw new SQLException("Stale deletion confirmation; reload before deleting");
            List<Long> detached = List.of();
            if (expected.kind == Kind.INVOICE) detached = new NormalizedCustomerDocumentService(connection)
                    .deleteInvoiceWithOrderDetachment(expected.company, expected.id).detachedOrderIds();
            else new NormalizedInvoiceStore(connection).deleteCreditInvoice(expected.company, expected.id);
            if (!fresh.orders.equals(detached)) throw new SQLException("Order links changed during deletion");
            connection.releaseSavepoint(savepoint);
            return new Deleted(expected.company, expected.kind, expected.id, detached);
        } catch (SQLException | RuntimeException | Error failure) {
            try { connection.rollback(savepoint); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }
    private boolean referenced(int company, int number, String table, String field, boolean payment) throws SQLException {
        String scope = payment ? "inpayment_id IN (SELECT id FROM inpayment WHERE company_id=(SELECT id FROM company WHERE legacy_id=?))"
                : "company_id=(SELECT id FROM company WHERE legacy_id=?)";
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE " + field + "=? AND " + scope)) {
            statement.setInt(1, number); statement.setInt(2, company);
            try (var rows = statement.executeQuery()) { rows.next(); return rows.getLong(1) > 0; }
        }
    }
    private void signature(CanonicalFingerprintWriter writer, String table, String owner, long id) throws SQLException {
        writer.writeBoundary(table);
        // Sort per-row fingerprints to retain multiplicity without relying on JDBC row order.
        try (var statement = connection.prepareStatement("SELECT * FROM " + table + " WHERE " + owner + "=?")) {
            statement.setLong(1, id);
            try (var rows = statement.executeQuery()) {
                var metadata = rows.getMetaData(); var signatures = new java.util.ArrayList<String>();
                while (rows.next()) {
                    var row = new CanonicalFingerprintWriter();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        int type = metadata.getColumnType(i);
                        if (!List.of(Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.TINYINT, Types.DECIMAL, Types.NUMERIC,
                                Types.BOOLEAN, Types.BIT, Types.VARCHAR, Types.CHAR, Types.LONGVARCHAR, Types.DATE,
                                Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE).contains(type)) throw new SQLException("Unsupported deletion SQL type " + type);
                        row.writeString(metadata.getColumnName(i)).writeInteger(type).writeString(rows.getString(i));
                    }
                    signatures.add(row.finish());
                }
                java.util.Collections.sort(signatures); for (String row : signatures) writer.writeString(row);
            }
        }
    }
}
