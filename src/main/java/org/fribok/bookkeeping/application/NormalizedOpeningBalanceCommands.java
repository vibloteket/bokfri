package org.fribok.bookkeeping.application;

import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.fribok.bookkeeping.service.openingbalance.OpeningBalancePlan;
import org.fribok.bookkeeping.service.openingbalance.OpeningBalanceService;

import se.swedsoft.bookkeeping.calc.math.SSAccountMath;
import se.swedsoft.bookkeeping.data.SSAccountPlanType;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Initial opening balances only: never replaces existing balances, journals or year settings. */
public final class NormalizedOpeningBalanceCommands implements AutoCloseable {
    public record Account(int number, String description) {}

    /** Positive debit / negative credit; no zero or inferred amount. */
    public record Entry(int account, BigDecimal amount) {}

    public record Draft(List<Entry> entries) {
        public Draft {
            entries = List.copyOf(entries);
        }
    }

    public final class Preview {
        private final NormalizedOpeningBalanceCommands owner =
                NormalizedOpeningBalanceCommands.this;
        private final Draft draft;
        private final OpeningBalancePlan plan;
        private final List<List<Object>> snapshot;
        private final String planType;
        private boolean consumed;

        private Preview(
                Draft draft,
                OpeningBalancePlan plan,
                List<List<Object>> snapshot,
                String planType) {
            this.draft = draft;
            this.plan = plan;
            this.snapshot = snapshot;
            this.planType = planType;
        }

        public Draft draft() {
            return draft;
        }

        public OpeningBalancePlan plan() {
            return plan;
        }

        public String planType() {
            return planType;
        }

        public int companyId() {
            return selection.company().id();
        }

        public int yearId() {
            return selection.year().id();
        }

        public java.time.LocalDate startsOn() {
            return selection.year().from();
        }

        public java.time.LocalDate endsOn() {
            return selection.year().to();
        }
    }

    private record Context(
            NormalizedAccountingStore store,
            SSNewAccountingYear year,
            long sqlYear,
            String planType) {}

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private final NormalizedApplicationOwner.Connections connections;
    private final Object gate;
    private final Clock clock;
    private final NormalizedApplicationOwner.Selection selection;
    private volatile boolean active = true;

    NormalizedOpeningBalanceCommands(
            NormalizedApplicationOwner.Connections connections,
            Object gate,
            Clock clock,
            NormalizedApplicationOwner.Selection selection) {
        this.connections = connections;
        this.gate = gate;
        this.clock = clock;
        this.selection = selection;
    }

    /** Explicit active balance-account choices in an eligible empty year. Run off EDT. */
    public List<Account> accounts() throws SQLException {
        return run(
                false,
                c -> {
                    var context = context(c);
                    requireEmpty(c, context.sqlYear());
                    return accounts(c, context);
                });
    }

    /** Read-only preview using exact cent balance, not the legacy rounded-difference tolerance. */
    public Preview preview(Draft draft) throws SQLException {
        Objects.requireNonNull(draft, "opening-balance draft");
        return run(
                false,
                c -> {
                    var context = context(c);
                    requireEmpty(c, context.sqlYear());
                    return new Preview(
                            draft, validate(c, context, draft), snapshot(c), context.planType());
                });
    }

    /** Single-use atomic initial insert. Does not call the service's replacement/commit method. */
    public int create(Preview preview) throws SQLException {
        Objects.requireNonNull(preview, "opening-balance preview");
        synchronized (gate) {
            requireAvailable();
            if (preview.owner != this) {
                throw new SQLException("Opening-balance preview belongs to another context");
            }
            if (preview.consumed) {
                throw new SQLException("Opening-balance preview was already consumed");
            }
            preview.consumed = true;
            return run(
                    true,
                    c -> {
                        var context = context(c);
                        requireEmpty(c, context.sqlYear());
                        if (!preview.snapshot.equals(snapshot(c))) {
                            throw new SQLException("Opening-balance inputs changed; preview again");
                        }
                        var plan = validate(c, context, preview.draft);
                        try (var insert =
                                c.prepareStatement(
                                        "INSERT INTO opening_balance"
                                            + " (accounting_year_id,account_id,amount) SELECT"
                                            + " a.accounting_year_id,a.id,? FROM account a WHERE"
                                            + " a.accounting_year_id=? AND a.number=? AND a.active"
                                            + " IS TRUE")) {
                            for (var row : plan.balances()) {
                                insert.setBigDecimal(1, row.amount());
                                insert.setLong(2, context.sqlYear());
                                insert.setInt(3, row.account());
                                if (insert.executeUpdate() != 1) {
                                    throw new SQLException(
                                            "Opening-balance account changed; preview again");
                                }
                            }
                        }
                        return plan.balances().size();
                    });
        }
    }

    private OpeningBalancePlan validate(Connection c, Context context, Draft draft)
            throws SQLException {
        if (draft.entries().size() < 2 || draft.entries().size() > 200) {
            throw new IllegalArgumentException(
                    "2 to 200 explicit nonzero opening-balance rows required");
        }
        var allowed = accounts(c, context);
        var values = new LinkedHashMap<Integer, BigDecimal>();
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal debit = BigDecimal.ZERO;
        for (var entry : draft.entries()) {
            if (allowed.stream().noneMatch(a -> a.number() == entry.account())) {
                throw new IllegalArgumentException(
                        "Account is not an active selected-year balance account");
            }
            if (entry.amount() == null || entry.amount().signum() == 0) {
                throw new IllegalArgumentException("Explicit nonzero signed amount required");
            }
            var n = entry.amount().stripTrailingZeros();
            if (n.scale() > 2 || n.precision() - (long) n.scale() > 70) {
                throw new IllegalArgumentException(
                        "Opening amounts must be cent-exact and within storage range");
            }
            var amount = entry.amount().setScale(2);
            if (values.putIfAbsent(entry.account(), amount) != null) {
                throw new IllegalArgumentException("Duplicate opening-balance account");
            }
            sum = sum.add(amount);
            if (amount.signum() > 0) {
                debit = debit.add(amount);
            }
        }
        if (sum.signum() != 0) {
            throw new IllegalArgumentException("Opening balances must balance exactly");
        }
        if (debit.precision() - (long) debit.scale() > 70) {
            throw new IllegalArgumentException("Opening-balance total exceeds storage range");
        }
        // Validation/classification only. Never use replace(), which owns a different commit
        // boundary.
        return new OpeningBalanceService(context.store()).validate(context.year(), values);
    }

    private List<Account> accounts(Connection c, Context context) throws SQLException {
        List<Integer> activeNumbers = new ArrayList<>();
        try (var q =
                c.prepareStatement(
                        "SELECT number FROM account WHERE accounting_year_id=? AND active IS TRUE"
                                + " ORDER BY number")) {
            q.setLong(1, context.sqlYear());
            try (var rows = q.executeQuery()) {
                while (rows.next()) activeNumbers.add(rows.getInt(1));
            }
        }
        return context.year().getAccounts().stream()
                .filter(
                        a ->
                                activeNumbers.contains(a.getNumber())
                                        && SSAccountMath.isBalanceAccount(a, context.year()))
                .sorted(java.util.Comparator.comparing(a -> a.getNumber()))
                .map(a -> new Account(a.getNumber(), a.getDescription()))
                .toList();
    }

    private Context context(Connection c) throws SQLException {
        var store = new NormalizedAccountingStore(c, clock);
        var company =
                store.getCompanies().stream()
                        .filter(v -> v.getId() == selection.company().id())
                        .findFirst()
                        .orElseThrow(() -> new SQLException("Selected company missing"));
        store.setCurrentCompany(company);
        var year =
                store.getYears().stream()
                        .filter(
                                v ->
                                        v.getId() == selection.year().id()
                                                && Objects.equals(
                                                        v.getLocalFrom(), selection.year().from())
                                                && Objects.equals(
                                                        v.getLocalTo(), selection.year().to()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new SQLException(
                                                "Selected year changed or no longer belongs to"
                                                        + " company"));
        if (year.getLocalFrom() == null
                || year.getLocalTo() == null
                || year.getAccountPlan() == null) {
            throw new SQLException("Known year bounds and stored account plan required");
        }
        long id;
        String type;
        try (var q =
                c.prepareStatement(
                        "SELECT y.id,p.plan_type FROM accounting_year y JOIN account_plan p ON"
                                + " p.accounting_year_id=y.id WHERE y.legacy_id=?")) {
            q.setInt(1, selection.year().id());
            try (var row = q.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("Stored account plan missing");
                }
                id = row.getLong(1);
                type = row.getString(2);
            }
        }
        // Read raw SQL metadata BEFORE getType(), whose legacy getter defaults null to BAS95.
        if (type == null || SSAccountPlanType.get(type) == null) {
            throw new SQLException(
                    "Known explicit stored account-plan type required; no BAS default inferred");
        }
        year.getAccountPlan().setType(SSAccountPlanType.get(type));
        year.getAccountPlan().setAccounts(store.getAccounts(year));
        store.setCurrentYear(year);
        return new Context(store, year, id, type);
    }

    private static void requireEmpty(Connection c, long year) throws SQLException {
        for (String table : List.of("opening_balance", "voucher")) {
            try (var q =
                    c.prepareStatement("SELECT 1 FROM " + table + " WHERE accounting_year_id=?")) {
                q.setLong(1, year);
                try (var row = q.executeQuery()) {
                    if (row.next()) {
                        throw new SQLException(
                                "Initial opening balances require no existing balances or"
                                        + " journals");
                    }
                }
            }
        }
    }

    private List<List<Object>> snapshot(Connection c) throws SQLException {
        String[] queries = {
            "SELECT * FROM company WHERE legacy_id=?",
            "SELECT * FROM accounting_year WHERE legacy_id=?",
            "SELECT * FROM account_plan WHERE accounting_year_id=(SELECT id FROM accounting_year"
                    + " WHERE legacy_id=?)",
            "SELECT * FROM account WHERE accounting_year_id=(SELECT id FROM accounting_year WHERE"
                    + " legacy_id=?) ORDER BY number",
            "SELECT * FROM opening_balance WHERE accounting_year_id=(SELECT id FROM accounting_year"
                    + " WHERE legacy_id=?) ORDER BY account_id",
            "SELECT * FROM voucher WHERE accounting_year_id=(SELECT id FROM accounting_year WHERE"
                    + " legacy_id=?) ORDER BY id"
        };
        List<List<Object>> result = new ArrayList<>();
        for (int i = 0; i < queries.length; i++) {
            try (var q = c.prepareStatement(queries[i])) {
                q.setInt(1, i == 0 ? selection.company().id() : selection.year().id());
                try (var rows = q.executeQuery()) {
                    while (rows.next()) {
                        List<Object> row = new ArrayList<>();
                        row.add(i);
                        for (int col = 1; col <= rows.getMetaData().getColumnCount(); col++) {
                            row.add(rows.getObject(col));
                        }
                        result.add(row);
                    }
                }
            }
        }
        return result;
    }

    private <T> T run(boolean write, Work<T> work) throws SQLException {
        requireAvailable();
        synchronized (gate) {
            requireAvailable();
            var c = connections.open();
            boolean committed = false;
            Throwable failure = null;
            try {
                c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                c.setAutoCommit(false);
                T result = work.run(c);
                if (write) {
                    c.commit();
                    committed = true;
                } else {
                    requireAvailable();
                    c.rollback();
                }
                return result;
            } catch (SQLException | RuntimeException | Error e) {
                failure = e;
                try {
                    c.rollback();
                } catch (SQLException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            } finally {
                try {
                    c.close();
                } catch (SQLException close) {
                    if (failure != null) {
                        failure.addSuppressed(close);
                    } else if (committed) {
                        org.slf4j.LoggerFactory.getLogger(NormalizedOpeningBalanceCommands.class)
                                .warn(
                                        "Opening balances committed but close failed; do not retry",
                                        close);
                    } else throw close;
                }
            }
        }
    }

    private void requireAvailable() throws SQLException {
        if (!active || !selection.isActive() || selection.isBlocked()) {
            throw new SQLException("Opening-balance context is closed, replaced or blocked");
        }
    }

    @Override
    public void close() {
        active = false;
    }
}
