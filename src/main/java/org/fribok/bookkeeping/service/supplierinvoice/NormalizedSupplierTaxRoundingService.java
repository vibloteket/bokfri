package org.fribok.bookkeeping.service.supplierinvoice;

import org.fribok.bookkeeping.dataformat.CanonicalFingerprintWriter;
import org.fribok.bookkeeping.dataformat.NormalizedSupplierInvoiceStore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Explicit stored supplier VAT/rounding only; caller owns transaction, commit and writer gate. */
public final class NormalizedSupplierTaxRoundingService {
    /** Supplier invoice or credit family. */
    public enum Kind {
        INVOICE,
        CREDIT
    }

    /** Raw nullable stored header amounts, never inferred from VAT rates or payment terms. */
    public record Values(BigDecimal tax, BigDecimal rounding) {}

    /** Legacy net/payable and unrounded accounting equivalent; raw NULLs remain visible. */
    public record Totals(
            BigDecimal net,
            BigDecimal tax,
            BigDecimal rounding,
            BigDecimal payable,
            BigDecimal accountingEquivalent) {}

    /** Immutable raw source with a complete SQL graph token. */
    public static final class Snapshot {
        private final int company;
        private final Kind kind;
        private final long id;
        private final Integer number;
        private final String supplier;
        private final String currency;
        private final BigDecimal rate;
        private final BigDecimal net;
        private final Values values;
        private final String signature;

        private Snapshot(
                int company,
                Kind kind,
                long id,
                Integer number,
                String supplier,
                String currency,
                BigDecimal rate,
                BigDecimal net,
                Values values,
                String signature) {
            this.company = company;
            this.kind = kind;
            this.id = id;
            this.number = number;
            this.supplier = supplier;
            this.currency = currency;
            this.rate = rate;
            this.net = net;
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

        public String currency() {
            return currency;
        }

        public BigDecimal rate() {
            return rate;
        }

        public Values values() {
            return values;
        }

        public Totals totals() {
            return calculate(net, values, rate);
        }
    }

    /** Complete-source-bound exact before/after request. */
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

        public Totals beforeTotals() {
            return source.totals();
        }

        public Totals afterTotals() {
            return calculate(source.net, after, source.rate);
        }

        public boolean changed() {
            return !same(before(), after);
        }
    }

    /** Saved identity; the caller must still commit the outer transaction. */
    public record Updated(int companyId, Kind kind, long id) {}

    private final Connection connection;

    /** Binds a caller-owned connection without SQL access or migration. */
    public NormalizedSupplierTaxRoundingService(Connection connection) {
        this.connection = Objects.requireNonNull(connection);
    }

    /** Reads raw header amounts and legacy row-net arithmetic after current editing eligibility. */
    public Snapshot load(int company, Kind kind, long id) throws SQLException {
        Objects.requireNonNull(kind, "kind");
        var store = new NormalizedSupplierInvoiceStore(connection);
        if (kind == Kind.INVOICE) {
            store.validateSupplierInvoiceEditing(company, id);
        } else {
            store.validateSupplierCreditInvoiceEditing(company, id);
        }
        String table = table(kind);
        BigDecimal net = BigDecimal.ZERO;
        try (var statement =
                connection.prepareStatement(
                        "SELECT quantity,unit_price FROM "
                                + table
                                + "_row WHERE "
                                + table
                                + "_id=? ORDER BY row_number")) {
            statement.setLong(1, id);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    Integer quantity = rows.getObject(1, Integer.class);
                    BigDecimal price = rows.getBigDecimal(2);
                    if (quantity != null && price != null) {
                        net = net.add(price.multiply(BigDecimal.valueOf(quantity)));
                    }
                }
            }
        }
        try (var statement =
                connection.prepareStatement(
                        "SELECT"
                            + " number,supplier_name,currency_code,currency_rate,tax_sum,rounding_sum"
                            + " FROM "
                                + table
                                + " WHERE id=? AND company_id=(SELECT id FROM company WHERE"
                                + " legacy_id=?)")) {
            statement.setLong(1, id);
            statement.setInt(2, company);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Missing or foreign supplier VAT/rounding target");
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
                        net,
                        new Values(result.getBigDecimal(5), result.getBigDecimal(6)),
                        writer.finish());
            }
        }
    }

    /** Rechecks all source fields and calculates a read-only preview without repairing history. */
    public Plan preview(Snapshot expected, Values proposed) throws SQLException {
        Objects.requireNonNull(expected, "snapshot");
        Objects.requireNonNull(proposed, "amounts");
        var fresh = load(expected.company, expected.kind, expected.id);
        if (!fresh.signature.equals(expected.signature)) {
            throw new SQLException("Stale supplier VAT/rounding; reload before editing");
        }
        var plan = new Plan(expected, proposed);
        if (plan.changed()) {
            if (!equal(expected.values.tax(), proposed.tax())) {
                exact(proposed.tax());
                if (proposed.tax() != null && proposed.tax().signum() < 0) {
                    throw new IllegalArgumentException(
                            "Changed supplier VAT must be non-negative or NULL");
                }
            }
            if (!equal(expected.values.rounding(), proposed.rounding())) {
                exact(proposed.rounding());
            }
            if (expected.rate == null || expected.rate.signum() <= 0) {
                throw new IllegalArgumentException(
                        "A positive stored exchange rate is required before amount editing");
            }
            var totals = plan.afterTotals();
            exact(totals.net());
            exact(totals.payable());
            if (totals.payable().signum() < 0) {
                throw new IllegalArgumentException(
                        "Changed supplier totals must not produce a negative payable");
            }
        }
        return plan;
    }

    /** Revalidates and updates only changed VAT/rounding columns under a savepoint. */
    public Updated confirm(Plan expected) throws SQLException {
        Objects.requireNonNull(expected, "plan");
        if (connection.getAutoCommit()) {
            throw new SQLException("VAT/rounding editing requires a caller-owned transaction");
        }
        Savepoint savepoint = connection.setSavepoint();
        try {
            var fresh = preview(expected.source, expected.after);
            boolean tax = !equal(fresh.before().tax(), fresh.after.tax());
            boolean rounding = !equal(fresh.before().rounding(), fresh.after.rounding());
            List<String> columns = new ArrayList<>();
            if (tax) {
                columns.add("tax_sum=?");
            }
            if (rounding) {
                columns.add("rounding_sum=?");
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
                    if (tax) {
                        statement.setBigDecimal(parameter++, fresh.after.tax());
                    }
                    if (rounding) {
                        statement.setBigDecimal(parameter++, fresh.after.rounding());
                    }
                    statement.setLong(parameter++, fresh.source.id);
                    statement.setInt(parameter, fresh.source.company);
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException(
                                "Missing or foreign supplier VAT/rounding update target");
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

    private static Totals calculate(BigDecimal net, Values values, BigDecimal rate) {
        BigDecimal payable =
                net.add(values.tax() == null ? BigDecimal.ZERO : values.tax())
                        .add(values.rounding() == null ? BigDecimal.ZERO : values.rounding());
        return new Totals(
                net,
                values.tax(),
                values.rounding(),
                payable,
                rate == null ? null : payable.multiply(rate));
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

    private static boolean equal(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static boolean same(Values a, Values b) {
        return equal(a.tax(), b.tax()) && equal(a.rounding(), b.rounding());
    }

    private static String table(Kind kind) {
        return kind == Kind.INVOICE ? "supplier_invoice" : "supplier_credit_invoice";
    }
}
