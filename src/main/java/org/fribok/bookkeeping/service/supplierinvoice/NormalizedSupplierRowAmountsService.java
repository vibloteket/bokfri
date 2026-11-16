package org.fribok.bookkeeping.service.supplierinvoice;

import org.fribok.bookkeeping.dataformat.CanonicalFingerprintWriter;
import org.fribok.bookkeeping.dataformat.NormalizedSupplierInvoiceStore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Existing supplier-row amount drafts with explicit preview/confirm; caller owns transaction and
 * writer gate.
 */
public final class NormalizedSupplierRowAmountsService {
    /** Supplier invoice or supplier credit family. */
    public enum Kind {
        INVOICE,
        CREDIT
    }

    /**
     * Stored row key and proposed values only; no insertion, deletion, reordering or accounting
     * edits.
     */
    public record Values(int key, Integer quantity, BigDecimal unitPrice, BigDecimal unitFreight) {}

    /** Immutable stored row presentation; all non-amount fields are read-only snapshots. */
    public record Row(
            int key,
            String description,
            String product,
            String unit,
            Integer account,
            String project,
            String resultUnit,
            Integer quantity,
            BigDecimal unitPrice,
            BigDecimal unitFreight) {
        /** Existing editable fields bound to the persisted row key. */
        public Values values() {
            return new Values(key, quantity, unitPrice, unitFreight);
        }
    }

    /**
     * Business net/payable and unrounded accounting equivalent; VAT/rounding retain stored NULL
     * values.
     */
    public record Totals(
            BigDecimal net,
            BigDecimal tax,
            BigDecimal rounding,
            BigDecimal payable,
            BigDecimal accountingEquivalent) {}

    /** Immutable complete-source draft; no mutable domain hydration/defaults. */
    public static final class Snapshot {
        private final int company;
        private final Kind kind;
        private final long id;
        private final Integer number;
        private final String supplier;
        private final String currency;
        private final BigDecimal rate;
        private final BigDecimal tax;
        private final BigDecimal rounding;
        private final boolean stockInfluencing;
        private final List<Row> rows;
        private final String signature;

        private Snapshot(
                int company,
                Kind kind,
                long id,
                Integer number,
                String supplier,
                String currency,
                BigDecimal rate,
                BigDecimal tax,
                BigDecimal rounding,
                boolean stockInfluencing,
                List<Row> rows,
                String signature) {
            this.company = company;
            this.kind = kind;
            this.id = id;
            this.number = number;
            this.supplier = supplier;
            this.currency = currency;
            this.rate = rate;
            this.tax = tax;
            this.rounding = rounding;
            this.stockInfluencing = stockInfluencing;
            this.rows = List.copyOf(rows);
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

        public String currency() {
            return currency;
        }

        public BigDecimal rate() {
            return rate;
        }

        public boolean stockInfluencing() {
            return stockInfluencing;
        }

        public List<Row> rows() {
            return rows;
        }

        public Totals totals() {
            return calculate(rows.stream().map(Row::values).toList(), tax, rounding, rate);
        }
    }

    /** Exact changed row values for confirmation; persisted keys never change. */
    public record Change(Values before, Values after) {}

    /** Private source/request token and immutable before/after presentation. */
    public static final class Plan {
        private final Snapshot source;
        private final List<Values> proposed;
        private final List<Change> changes;
        private final Totals after;

        private Plan(Snapshot source, List<Values> proposed, List<Change> changes, Totals after) {
            this.source = source;
            this.proposed = List.copyOf(proposed);
            this.changes = List.copyOf(changes);
            this.after = after;
        }

        public Snapshot source() {
            return source;
        }

        public List<Change> changes() {
            return changes;
        }

        public Totals before() {
            return source.totals();
        }

        public Totals after() {
            return after;
        }
    }

    /** Saved identity; caller must still commit the outer transaction. */
    public record Updated(int companyId, Kind kind, long id) {}

    private final Connection connection;

    /** Binds a caller-owned connection without SQL access or migration. */
    public NormalizedSupplierRowAmountsService(Connection connection) {
        this.connection = Objects.requireNonNull(connection);
    }

    /**
     * Reads immutable raw rows/header and complete SQL token after current unbooked/unlinked
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
        String table = table(kind);
        List<Row> rows = new ArrayList<>();
        try (var statement =
                connection.prepareStatement(
                        "SELECT"
                            + " row_number,description,product_number,unit_name,account_number," // Stored metadata.
                                + "project_number,result_unit_number,quantity,unit_price,unit_freight"
                                + " FROM "
                                + table
                                + "_row WHERE "
                                + table
                                + "_id=? ORDER BY row_number")) {
            statement.setLong(1, id);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(
                            new Row(
                                    result.getInt(1),
                                    result.getString(2),
                                    result.getString(3),
                                    result.getString(4),
                                    result.getObject(5, Integer.class),
                                    result.getString(6),
                                    result.getString(7),
                                    result.getObject(8, Integer.class),
                                    result.getBigDecimal(9),
                                    result.getBigDecimal(10)));
                }
            }
        }
        try (var statement =
                connection.prepareStatement(
                        "SELECT number,supplier_name,currency_code,currency_rate,tax_sum,"
                                + "rounding_sum,stock_influencing FROM "
                                + table
                                + " WHERE id=? AND company_id=(SELECT id FROM company WHERE"
                                + " legacy_id=?)")) {
            statement.setLong(1, id);
            statement.setInt(2, company);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Missing or foreign supplier row target");
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
                        result.getBigDecimal(4),
                        result.getBigDecimal(5),
                        result.getBigDecimal(6),
                        result.getBoolean(7),
                        rows,
                        writer.finish());
            }
        }
    }

    /** Rechecks complete source and produces exact before/after values without SQL writes. */
    public Plan preview(Snapshot expected, List<Values> proposed) throws SQLException {
        Objects.requireNonNull(expected, "snapshot");
        Objects.requireNonNull(proposed, "proposed values");
        Snapshot fresh = load(expected.company, expected.kind, expected.id);
        if (!fresh.signature.equals(expected.signature)) {
            throw new SQLException("Stale row snapshot; reload before editing");
        }
        if (proposed.size() != expected.rows.size()) {
            throw new IllegalArgumentException("Row insertion/removal is not supported");
        }
        Map<Integer, Values> values = new HashMap<>();
        for (Values row : proposed) {
            if (row == null || values.put(row.key(), row) != null) {
                throw new IllegalArgumentException("Duplicate or missing row key");
            }
        }
        List<Values> ordered = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        for (Row original : expected.rows) {
            Values next = values.remove(original.key());
            if (next == null) {
                throw new IllegalArgumentException("Foreign or missing stored row key");
            }
            Values old = original.values();
            if (!same(old, next)) {
                if (next.quantity() == null
                        || next.quantity() <= 0
                        || next.unitPrice() == null
                        || next.unitPrice().signum() < 0
                        || (next.unitFreight() != null && next.unitFreight().signum() < 0)) {
                    throw new IllegalArgumentException(
                            "Changed rows require positive integer quantity and non-negative"
                                    + " price/freight");
                }
                exact(next.unitPrice());
                exact(next.unitFreight());
                changes.add(new Change(old, next));
            }
            ordered.add(next);
        }
        if (!values.isEmpty()) {
            throw new IllegalArgumentException("Foreign row key");
        }
        Totals after = calculate(ordered, expected.tax, expected.rounding, expected.rate);
        if (!changes.isEmpty()) {
            if (expected.rate == null || expected.rate.signum() <= 0) {
                throw new IllegalArgumentException(
                        "A positive stored exchange rate is required before amount editing");
            }
            exact(after.net());
            exact(after.payable());
        }
        return new Plan(expected, ordered, changes, after);
    }

    /**
     * Revalidates source/references and updates only changed existing-row columns under a
     * savepoint.
     */
    public Updated confirm(Plan expected) throws SQLException {
        Objects.requireNonNull(expected, "plan");
        if (connection.getAutoCommit()) {
            throw new SQLException("Amount editing requires a caller-owned transaction");
        }
        Savepoint savepoint = connection.setSavepoint();
        try {
            Plan fresh = preview(expected.source, expected.proposed);
            String table = table(fresh.source.kind);
            for (Change change : fresh.changes) {
                boolean quantity =
                        !Objects.equals(change.before.quantity(), change.after.quantity());
                boolean price = !decimalEqual(change.before.unitPrice(), change.after.unitPrice());
                boolean freight =
                        !decimalEqual(change.before.unitFreight(), change.after.unitFreight());
                List<String> columns = new ArrayList<>();
                if (quantity) {
                    columns.add("quantity=?");
                }
                if (price) {
                    columns.add("unit_price=?");
                }
                if (freight) {
                    columns.add("unit_freight=?");
                }
                try (var statement =
                        connection.prepareStatement(
                                "UPDATE "
                                        + table
                                        + "_row SET "
                                        + String.join(",", columns)
                                        + " WHERE "
                                        + table
                                        + "_id=? AND row_number=? AND company_id=(SELECT id FROM"
                                        + " company WHERE legacy_id=?)")) {
                    int parameter = 1;
                    if (quantity) {
                        statement.setInt(parameter++, change.after.quantity());
                    }
                    if (price) {
                        statement.setBigDecimal(parameter++, change.after.unitPrice());
                    }
                    if (freight) {
                        statement.setBigDecimal(parameter++, change.after.unitFreight());
                    }
                    statement.setLong(parameter++, fresh.source.id);
                    statement.setInt(parameter++, change.after.key());
                    statement.setInt(parameter, fresh.source.company);
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Missing or foreign supplier row update target");
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

    private static Totals calculate(
            List<Values> rows, BigDecimal tax, BigDecimal rounding, BigDecimal rate) {
        BigDecimal net = BigDecimal.ZERO;
        for (Values row : rows) {
            // Legacy supplier row sums exclude freight and omit incomplete quantity/price pairs.
            if (row.quantity() != null && row.unitPrice() != null) {
                net = net.add(row.unitPrice().multiply(BigDecimal.valueOf(row.quantity())));
            }
        }
        BigDecimal payable =
                net.add(tax == null ? BigDecimal.ZERO : tax)
                        .add(rounding == null ? BigDecimal.ZERO : rounding);
        return new Totals(
                net, tax, rounding, payable, rate == null ? null : payable.multiply(rate));
    }

    private static void exact(BigDecimal value) {
        if (value == null) {
            return;
        }
        try {
            if (value.setScale(30, RoundingMode.UNNECESSARY).precision() > 100) {
                throw new ArithmeticException();
            }
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(
                    "Amount cannot be stored exactly in DECIMAL(100,30)", failure);
        }
    }

    private static boolean decimalEqual(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static boolean same(Values a, Values b) {
        return Objects.equals(a.quantity(), b.quantity())
                && decimalEqual(a.unitPrice(), b.unitPrice())
                && decimalEqual(a.unitFreight(), b.unitFreight());
    }

    private static String table(Kind kind) {
        return kind == Kind.INVOICE ? "supplier_invoice" : "supplier_credit_invoice";
    }
}
