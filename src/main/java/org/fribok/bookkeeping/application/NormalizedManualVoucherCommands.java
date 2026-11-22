package org.fribok.bookkeeping.application;

import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.fribok.bookkeeping.service.voucher.VoucherValidator;

import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Lease-owned creation of plain, dimension-free manual vouchers. Never edits existing journals. */
public final class NormalizedManualVoucherCommands implements AutoCloseable {
    /** Immutable explicit account choice; only known-active, dimension-free accounts are listed. */
    public record Account(int number, String description) {}

    /** One new row; exactly one positive, cent-exact side is required. */
    public record Row(int account, BigDecimal debit, BigDecimal credit) {}

    /** Explicit immutable input with no date, description or account defaults. */
    public record Draft(LocalDate date, String description, List<Row> rows) {
        public Draft {
            rows = List.copyOf(rows);
        }
    }

    /** Opaque command-bound, single-use confirmation token. */
    public final class Preview {
        private final NormalizedManualVoucherCommands owner = NormalizedManualVoucherCommands.this;
        private final Draft draft;
        private final int number;
        private final BigDecimal total;
        private final List<Account> accounts;
        private final List<List<Object>> snapshot;
        private boolean consumed;

        private Preview(
                Draft draft,
                int number,
                BigDecimal total,
                List<Account> accounts,
                List<List<Object>> snapshot) {
            this.draft = draft;
            this.number = number;
            this.total = total;
            this.accounts = List.copyOf(accounts);
            this.snapshot = snapshot;
        }

        public Draft draft() {
            return draft;
        }

        public int number() {
            return number;
        }

        public BigDecimal total() {
            return total;
        }

        /** Current immutable account presentation captured in the same preview transaction. */
        public List<Account> accounts() {
            return accounts;
        }

        public int companyId() {
            return selection.company().id();
        }

        public int yearId() {
            return selection.year().id();
        }
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private final NormalizedApplicationOwner.Connections connections;
    private final Object gate;
    private final Clock clock;
    private final NormalizedApplicationOwner.Selection selection;
    private volatile boolean active = true;

    NormalizedManualVoucherCommands(
            NormalizedApplicationOwner.Connections connections,
            Object gate,
            Clock clock,
            NormalizedApplicationOwner.Selection selection) {
        this.connections = connections;
        this.gate = gate;
        this.clock = clock;
        this.selection = selection;
    }

    /** Reads choices without writing or consulting legacy storage. Run off the EDT. */
    public List<Account> accounts() throws SQLException {
        return run(
                false,
                connection -> {
                    context(connection);
                    return accounts(connection);
                });
    }

    /** Validates a balanced new voucher and captures its exact scope/catalogue/numbering inputs. */
    public Preview preview(Draft draft) throws SQLException {
        Objects.requireNonNull(draft, "voucher draft");
        return run(
                false,
                connection -> {
                    var store = context(connection);
                    int number = nextNumber(connection);
                    var voucher = build(connection, store, draft, number);
                    BigDecimal total =
                            voucher.getRows().stream()
                                    .map(SSVoucherRow::getDebet)
                                    .filter(Objects::nonNull)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                    return new Preview(
                            draft, number, total, accounts(connection), snapshot(connection));
                });
    }

    /** Atomically posts a single-use preview; stale inputs require a fresh preview. Run off EDT. */
    public int post(Preview preview) throws SQLException {
        Objects.requireNonNull(preview, "voucher preview");
        synchronized (gate) {
            requireAvailable();
            if (preview.owner != this) {
                throw new SQLException("Voucher preview belongs to another context");
            }
            if (preview.consumed) throw new SQLException("Voucher preview was already consumed");
            preview.consumed = true;
            return run(
                    true,
                    connection -> {
                        if (!preview.snapshot.equals(snapshot(connection))
                                || preview.number != nextNumber(connection)) {
                            throw new SQLException("Voucher inputs changed; preview again");
                        }
                        var store = context(connection);
                        var voucher = build(connection, store, preview.draft, preview.number);
                        store.addVoucher(voucher);
                        return preview.number;
                    });
        }
    }

    private NormalizedAccountingStore context(Connection connection) throws SQLException {
        var store = new NormalizedAccountingStore(connection, clock);
        var company =
                store.getCompanies().stream()
                        .filter(value -> value.getId() == selection.company().id())
                        .findFirst()
                        .orElseThrow(() -> new SQLException("Selected company no longer exists"));
        store.setCurrentCompany(company);
        var year =
                store.getYears().stream()
                        .filter(
                                value ->
                                        value.getId() == selection.year().id()
                                                && Objects.equals(
                                                        value.getLocalFrom(),
                                                        selection.year().from())
                                                && Objects.equals(
                                                        value.getLocalTo(), selection.year().to()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new SQLException(
                                                "Selected year changed or no longer belongs to"
                                                        + " company"));
        store.setCurrentYear(year);
        return store;
    }

    private SSVoucher build(
            Connection connection, NormalizedAccountingStore store, Draft draft, int number)
            throws SQLException {
        SSNewAccountingYear year = store.getCurrentYear();
        if (draft.date() == null
                || year.getLocalFrom() == null
                || year.getLocalTo() == null
                || draft.description() == null
                || draft.description().isBlank()
                || draft.description().length() > 4096
                || draft.rows().size() < 2
                || draft.rows().size() > 200) {
            throw new IllegalArgumentException(
                    "Explicit date/description and 2 to 200 plain voucher rows required");
        }
        var allowed = accounts(connection);
        var accountModels = store.getAccounts();
        List<SSVoucherRow> rows = new ArrayList<>();
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (var row : draft.rows()) {
            if (allowed.stream().noneMatch(value -> value.number() == row.account())) {
                throw new IllegalArgumentException(
                        "Account is missing, inactive, unknown or requires dimensions");
            }
            if ((row.debit() == null) == (row.credit() == null)) {
                throw new IllegalArgumentException(
                        "Exactly one positive amount side required per row");
            }
            BigDecimal amount = row.debit() == null ? row.credit() : row.debit();
            var normalized = amount.stripTrailingZeros();
            if (amount.signum() <= 0
                    || normalized.scale() > 2
                    || normalized.precision() - (long) normalized.scale() > 70) {
                throw new IllegalArgumentException(
                        "Voucher amounts must be positive, cent-exact and within storage range");
            }
            var account =
                    accountModels.stream()
                            .filter(value -> value.getNumber() == row.account())
                            .findFirst()
                            .orElseThrow(() -> new SQLException("Selected account changed"));
            var newRow =
                    new SSVoucherRow(
                            account,
                            row.debit() == null ? null : amount.setScale(2),
                            row.credit() == null ? null : amount.setScale(2),
                            null,
                            null);
            rows.add(newRow);
            if (row.debit() != null) debit = debit.add(amount);
            else credit = credit.add(amount);
        }
        if (debit.compareTo(credit) != 0) {
            throw new IllegalArgumentException("Voucher must balance exactly");
        }
        if (debit.precision() - (long) debit.scale() > 70) {
            throw new IllegalArgumentException("Voucher total exceeds storage range");
        }
        var voucher = new SSVoucher(number, draft.date());
        voucher.setDescription(draft.description());
        voucher.getRows().addAll(rows);
        var validation =
                VoucherValidator.validate(voucher, year, accountModels, List.of(), List.of());
        if (!validation.valid()) {
            throw new IllegalArgumentException("Voucher validation failed: " + validation.issues());
        }
        return voucher;
    }

    private List<Account> accounts(Connection connection) throws SQLException {
        List<Account> result = new ArrayList<>();
        try (var query =
                connection.prepareStatement(
                        "SELECT number,description FROM account WHERE accounting_year_id = (SELECT"
                            + " id FROM accounting_year WHERE legacy_id=?) AND active IS TRUE AND"
                            + " project_required IS FALSE AND result_unit_required IS FALSE ORDER"
                            + " BY number")) {
            query.setInt(1, selection.year().id());
            try (var rows = query.executeQuery()) {
                while (rows.next()) result.add(new Account(rows.getInt(1), rows.getString(2)));
            }
        }
        return List.copyOf(result);
    }

    private int nextNumber(Connection connection) throws SQLException {
        try (var query =
                connection.prepareStatement(
                        "SELECT MAX(number) FROM voucher WHERE accounting_year_id=(SELECT id FROM"
                                + " accounting_year WHERE legacy_id=?)")) {
            query.setInt(1, selection.year().id());
            try (var row = query.executeQuery()) {
                row.next();
                return Math.addExact(Math.max(0, row.getInt(1)), 1);
            }
        }
    }

    private List<List<Object>> snapshot(Connection connection) throws SQLException {
        String[] queries = {
            "SELECT * FROM company WHERE legacy_id=?",
            "SELECT * FROM accounting_year WHERE legacy_id=?",
            "SELECT * FROM account WHERE accounting_year_id=(SELECT id FROM accounting_year WHERE"
                    + " legacy_id=?) ORDER BY number",
            "SELECT id,number FROM voucher WHERE accounting_year_id=(SELECT id FROM accounting_year"
                    + " WHERE legacy_id=?) ORDER BY id"
        };
        List<List<Object>> result = new ArrayList<>();
        for (int index = 0; index < queries.length; index++) {
            try (var query = connection.prepareStatement(queries[index])) {
                query.setInt(1, index == 0 ? selection.company().id() : selection.year().id());
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        List<Object> row = new ArrayList<>();
                        row.add(index);
                        for (int column = 1;
                                column <= rows.getMetaData().getColumnCount();
                                column++) {
                            row.add(rows.getObject(column));
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
            var connection =
                    connections.open(); // Owner validates schema and observes every failed commit.
            boolean committed = false;
            Throwable failure = null;
            try {
                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                connection.setAutoCommit(false);
                T result = work.run(connection);
                if (write) {
                    connection.commit();
                    committed = true;
                } else {
                    requireAvailable();
                    connection.rollback();
                }
                return result;
            } catch (SQLException | RuntimeException | Error error) {
                failure = error;
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    error.addSuppressed(rollback);
                }
                throw error;
            } finally {
                try {
                    connection.close();
                } catch (SQLException close) {
                    if (failure != null) failure.addSuppressed(close);
                    else if (committed) {
                        org.slf4j.LoggerFactory.getLogger(NormalizedManualVoucherCommands.class)
                                .warn(
                                        "Manual voucher committed but connection close failed; do"
                                                + " not retry",
                                        close);
                    } else throw close;
                }
            }
        }
    }

    private void requireAvailable() throws SQLException {
        if (!active || !selection.isActive() || selection.isBlocked()) {
            throw new SQLException("Manual voucher context is closed, replaced or blocked");
        }
    }

    /** Retires future commands without cancelling an already accepted transaction. */
    @Override
    public void close() {
        active = false;
    }
}
