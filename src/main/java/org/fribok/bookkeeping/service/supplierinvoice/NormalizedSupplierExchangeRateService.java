package org.fribok.bookkeeping.service.supplierinvoice;

import org.fribok.bookkeeping.dataformat.CanonicalFingerprintWriter;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierTaxRoundingService.Totals;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Explicit stored exchange-rate and currency-code workflows; caller owns transaction, commit and
 * exclusive writer gate.
 */
public final class NormalizedSupplierExchangeRateService {
    /** Supplier invoice or credit family. */
    public enum Kind {
        INVOICE,
        CREDIT
    }

    /** Nullable stored direct multiplier; no catalogue rate lookup. */
    public record Values(BigDecimal rate) {}

    /** Immutable document source plus the company currency used by the same-currency rule. */
    public static final class Snapshot {
        private final Kind kind;
        private final NormalizedSupplierTaxRoundingService.Snapshot amounts;
        private final String companyCurrency;
        private final String signature;

        private Snapshot(
                Kind kind,
                NormalizedSupplierTaxRoundingService.Snapshot amounts,
                String companyCurrency,
                String signature) {
            this.kind = kind;
            this.amounts = amounts;
            this.companyCurrency = companyCurrency;
            this.signature = signature;
        }

        public int companyId() {
            return amounts.companyId();
        }

        public Kind kind() {
            return kind;
        }

        public long id() {
            return amounts.id();
        }

        public Integer number() {
            return amounts.number();
        }

        public String supplier() {
            return amounts.supplier();
        }

        public String currency() {
            return amounts.currency();
        }

        public String companyCurrency() {
            return companyCurrency;
        }

        public Values values() {
            return new Values(amounts.rate());
        }

        public Totals totals() {
            return amounts.totals();
        }
    }

    /** Complete-source request; only privately constructed tokens may be confirmed. */
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
            return !equal(before().rate(), after.rate());
        }

        public Totals beforeTotals() {
            return source.totals();
        }

        public Totals afterTotals() {
            var before = source.totals();
            return new Totals(
                    before.net(),
                    before.tax(),
                    before.rounding(),
                    before.payable(),
                    after.rate() == null ? null : before.payable().multiply(after.rate()));
        }
    }

    /** Saved identity; caller must still commit. */
    public record Updated(int companyId, Kind kind, long id) {}

    private final Connection connection;

    /** Binds caller-owned connection without accessing SQL. */
    public NormalizedSupplierExchangeRateService(Connection connection) {
        this.connection = Objects.requireNonNull(connection);
    }

    /** Reads raw amounts via the existing pure projection and binds the company currency. */
    public Snapshot load(int company, Kind kind, long id) throws SQLException {
        Objects.requireNonNull(kind, "kind");
        var amounts =
                new NormalizedSupplierTaxRoundingService(connection)
                        .load(
                                company,
                                kind == Kind.CREDIT
                                        ? NormalizedSupplierTaxRoundingService.Kind.CREDIT
                                        : NormalizedSupplierTaxRoundingService.Kind.INVOICE,
                                id);
        try (var statement =
                connection.prepareStatement(
                        "SELECT currency_code FROM company WHERE legacy_id=?")) {
            statement.setInt(1, company);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Missing supplier exchange-rate company");
                }
                String companyCurrency = result.getString(1);
                var writer = new CanonicalFingerprintWriter();
                writer.writeInteger(company)
                        .writeBoolean(kind == Kind.CREDIT)
                        .writeLong(id)
                        .writeString(companyCurrency);
                SupplierDocumentFingerprint.write(writer, connection, kind == Kind.CREDIT, id);
                return new Snapshot(kind, amounts, companyCurrency, writer.finish());
            }
        }
    }

    /** Rechecks complete source and previews direct multiplication without repricing. */
    public Plan preview(Snapshot expected, Values proposed) throws SQLException {
        Objects.requireNonNull(expected, "snapshot");
        Objects.requireNonNull(proposed, "rate");
        var fresh = load(expected.companyId(), expected.kind, expected.id());
        if (!fresh.signature.equals(expected.signature)) {
            throw new SQLException("Stale supplier rate or company currency; reload");
        }
        var plan = new Plan(expected, proposed);
        if (plan.changed()) {
            if (proposed.rate() == null || proposed.rate().signum() <= 0) {
                throw new IllegalArgumentException(
                        "Changed exchange rate must be explicitly positive");
            }
            exact(proposed.rate());
            if (expected.currency() == null
                    || expected.currency().isBlank()
                    || expected.companyCurrency == null
                    || expected.companyCurrency.isBlank()) {
                throw new IllegalArgumentException(
                        "Explicit stored document and company currency codes are required");
            }
            if (expected.currency().equals(expected.companyCurrency)
                    && proposed.rate().compareTo(BigDecimal.ONE) != 0) {
                throw new IllegalArgumentException(
                        "Same-currency documents require exchange rate one");
            }
            var equivalent = plan.afterTotals().accountingEquivalent();
            if (equivalent.signum() < 0) {
                throw new IllegalArgumentException(
                        "Changed exchange rate cannot produce a negative accounting equivalent");
            }
            exact(equivalent.setScale(2, RoundingMode.HALF_UP));
        }
        return plan;
    }

    /** Updates currency_rate only, after fresh eligibility/source checks under a savepoint. */
    public Updated confirm(Plan expected) throws SQLException {
        Objects.requireNonNull(expected, "plan");
        if (connection.getAutoCommit()) {
            throw new SQLException("Rate editing requires a caller-owned transaction");
        }
        Savepoint savepoint = connection.setSavepoint();
        try {
            var fresh = preview(expected.source, expected.after);
            if (fresh.changed()) {
                try (var statement =
                        connection.prepareStatement(
                                "UPDATE "
                                        + (fresh.source.kind == Kind.CREDIT
                                                ? "supplier_credit_invoice"
                                                : "supplier_invoice")
                                        + " SET currency_rate=? WHERE id=? AND company_id=(SELECT"
                                        + " id FROM company WHERE legacy_id=?)")) {
                    statement.setBigDecimal(1, fresh.after.rate());
                    statement.setLong(2, fresh.source.id());
                    statement.setInt(3, fresh.source.companyId());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Missing or foreign supplier rate target");
                    }
                }
            }
            connection.releaseSavepoint(savepoint);
            return new Updated(fresh.source.companyId(), fresh.source.kind, fresh.source.id());
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                connection.rollback(savepoint);
            } catch (SQLException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }

    /** Explicit code and direct rate together; no catalogue defaults or implicit rate changes. */
    public record CurrencyValues(String currency, BigDecimal rate) {}

    /** Separate source-bound token for currency editing; the rate-only API remains unchanged. */
    public static final class CurrencyPlan {
        private final Snapshot source;
        private final CurrencyValues after;

        private CurrencyPlan(Snapshot source, CurrencyValues after) {
            this.source = source;
            this.after = after;
        }

        public Snapshot source() {
            return source;
        }

        public CurrencyValues before() {
            return new CurrencyValues(source.currency(), source.values().rate());
        }

        public CurrencyValues after() {
            return after;
        }

        public boolean changed() {
            return !Objects.equals(before().currency(), after.currency())
                    || !equal(before().rate(), after.rate());
        }

        public Totals beforeTotals() {
            return source.totals();
        }

        public Totals afterTotals() {
            var before = source.totals();
            return new Totals(
                    before.net(),
                    before.tax(),
                    before.rounding(),
                    before.payable(),
                    after.rate() == null ? null : before.payable().multiply(after.rate()));
        }
    }

    /**
     * Immutable catalogue code choices only; descriptions and default exchange rates are not read.
     */
    public List<String> currencyCodes() throws SQLException {
        List<String> codes = new ArrayList<>();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT code FROM currency ORDER BY code")) {
            while (rows.next()) {
                codes.add(rows.getString(1));
            }
        }
        return List.copyOf(codes);
    }

    /**
     * Rechecks full source/company currency and validates the explicit code/rate without writes.
     */
    public CurrencyPlan previewCurrency(Snapshot expected, CurrencyValues proposed)
            throws SQLException {
        Objects.requireNonNull(expected, "snapshot");
        Objects.requireNonNull(proposed, "currency and rate");
        var fresh = load(expected.companyId(), expected.kind, expected.id());
        if (!fresh.signature.equals(expected.signature)) {
            throw new SQLException("Stale supplier currency, rate or company currency; reload");
        }
        var plan = new CurrencyPlan(expected, proposed);
        if (plan.changed()) {
            String code = proposed.currency();
            if (code == null
                    || code.isBlank()
                    || code.length() > 255
                    || code.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(
                        "An explicit valid catalogue currency code is required");
            }
            try (var statement =
                    connection.prepareStatement("SELECT code FROM currency WHERE code=?")) {
                statement.setString(1, code);
                try (var row = statement.executeQuery()) {
                    if (!row.next() || !code.equals(row.getString(1))) {
                        throw new IllegalArgumentException(
                                "Unknown or non-exact catalogue currency code");
                    }
                }
            }
            if (proposed.rate() == null || proposed.rate().signum() <= 0) {
                throw new IllegalArgumentException(
                        "An explicit positive exchange rate is required");
            }
            exact(proposed.rate());
            if (expected.companyCurrency == null || expected.companyCurrency.isBlank()) {
                throw new IllegalArgumentException("A stored company currency code is required");
            }
            if (code.equals(expected.companyCurrency)
                    && proposed.rate().compareTo(BigDecimal.ONE) != 0) {
                throw new IllegalArgumentException(
                        "Same-currency documents require exchange rate one");
            }
            var equivalent = plan.afterTotals().accountingEquivalent();
            if (equivalent.signum() < 0) {
                throw new IllegalArgumentException(
                        "Currency editing cannot produce a negative accounting equivalent");
            }
            exact(equivalent.setScale(2, RoundingMode.HALF_UP));
        }
        return plan;
    }

    /**
     * Updates only explicitly changed code/rate columns; never rewrites document monetary values.
     */
    public Updated confirmCurrency(CurrencyPlan expected) throws SQLException {
        Objects.requireNonNull(expected, "plan");
        if (connection.getAutoCommit()) {
            throw new SQLException("Currency editing requires a caller-owned transaction");
        }
        Savepoint savepoint = connection.setSavepoint();
        try {
            var fresh = previewCurrency(expected.source, expected.after);
            boolean currency = !Objects.equals(fresh.before().currency(), fresh.after.currency());
            boolean rate = !equal(fresh.before().rate(), fresh.after.rate());
            List<String> columns = new ArrayList<>();
            if (currency) {
                columns.add("currency_code=?");
            }
            if (rate) {
                columns.add("currency_rate=?");
            }
            if (!columns.isEmpty()) {
                try (var statement =
                        connection.prepareStatement(
                                "UPDATE "
                                        + (fresh.source.kind == Kind.CREDIT
                                                ? "supplier_credit_invoice"
                                                : "supplier_invoice")
                                        + " SET "
                                        + String.join(",", columns)
                                        + " WHERE id=? AND company_id=(SELECT id FROM company WHERE"
                                        + " legacy_id=?)")) {
                    int parameter = 1;
                    if (currency) {
                        statement.setString(parameter++, fresh.after.currency());
                    }
                    if (rate) {
                        statement.setBigDecimal(parameter++, fresh.after.rate());
                    }
                    statement.setLong(parameter++, fresh.source.id());
                    statement.setInt(parameter, fresh.source.companyId());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Missing or foreign supplier currency target");
                    }
                }
            }
            connection.releaseSavepoint(savepoint);
            return new Updated(fresh.source.companyId(), fresh.source.kind, fresh.source.id());
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                connection.rollback(savepoint);
            } catch (SQLException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }

    private static boolean equal(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static void exact(BigDecimal value) {
        try {
            if (value.setScale(30, RoundingMode.UNNECESSARY).precision() > 100) {
                throw new ArithmeticException();
            }
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(
                    "Exchange rate or accounting amount exceeds exact DECIMAL(100,30)", failure);
        }
    }
}
