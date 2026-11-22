package org.fribok.bookkeeping.application;

import org.fribok.bookkeeping.dataformat.NormalizedAccountingStore;
import org.fribok.bookkeeping.dataformat.NormalizedRegisterStore;
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

/**
 * Lease-owned manual vouchers and guarded standalone reversals with explicit company dimensions.
 * Original rows are never rewritten; reversals link journal headers atomically.
 */
public final class NormalizedManualVoucherCommands implements AutoCloseable {
    /** Immutable active account choice, including its dimension requirements. */
    public record Account(
            int number, String description, boolean projectRequired, boolean unitRequired) {
        public Account(int number, String description) {
            this(number, description, false, false);
        }
    }

    /** Company-scoped project with exact stored closure metadata. */
    public record Project(
            String number,
            String name,
            String description,
            boolean concluded,
            LocalDate concludedOn) {}

    /** Company-scoped result unit. */
    public record Unit(String number, String name, String description) {}

    /** Immutable choices loaded together in one validated read transaction. */
    public record Catalogue(List<Account> accounts, List<Project> projects, List<Unit> units) {
        public Catalogue {
            accounts = List.copyOf(accounts);
            projects = List.copyOf(projects);
            units = List.copyOf(units);
        }
    }

    /** One new row; dimensions are exact company-scoped keys or explicit absence. */
    public record Row(
            int account, BigDecimal debit, BigDecimal credit, String project, String unit) {
        public Row(int account, BigDecimal debit, BigDecimal credit) {
            this(account, debit, credit, null, null);
        }
    }

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
        private final Catalogue catalogue;
        private final List<List<Object>> snapshot;
        private boolean consumed;

        private Preview(
                Draft draft,
                int number,
                BigDecimal total,
                Catalogue catalogue,
                List<List<Object>> snapshot) {
            this.draft = draft;
            this.number = number;
            this.total = total;
            this.catalogue = catalogue;
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
            return catalogue.accounts();
        }

        public List<Project> projects() {
            return catalogue.projects();
        }

        public List<Unit> units() {
            return catalogue.units();
        }

        public int companyId() {
            return selection.company().id();
        }

        public int yearId() {
            return selection.year().id();
        }
    }

    /** Immutable original rows, before swapping sides; source row contents are never edited. */
    public record ReversalSource(
            long id, int number, LocalDate date, String description, List<Row> rows) {
        public ReversalSource {
            rows = List.copyOf(rows);
        }
    }

    /**
     * Opaque command-bound single-use reversal confirmation; cannot be posted as a plain voucher.
     */
    public final class ReversalPreview {
        private final NormalizedManualVoucherCommands owner = NormalizedManualVoucherCommands.this;
        private final ReversalSource source;
        private final Preview posting;
        private final List<List<Object>> sourceSnapshot;

        private ReversalPreview(
                ReversalSource source, Preview posting, List<List<Object>> snapshot) {
            this.source = source;
            this.posting = posting;
            this.sourceSnapshot = snapshot;
        }

        public ReversalSource source() {
            return source;
        }

        public Draft draft() {
            return posting.draft();
        }

        public int number() {
            return posting.number();
        }

        public BigDecimal total() {
            return posting.total();
        }

        public List<Account> accounts() {
            return posting.accounts();
        }

        public List<Project> projects() {
            return posting.projects();
        }

        public List<Unit> units() {
            return posting.units();
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
                    return activeAccounts(connection).stream()
                            .filter(
                                    account ->
                                            !account.projectRequired() && !account.unitRequired())
                            .toList();
                });
    }

    /**
     * Loads active accounts and exact company-scoped dimension choices without defaults. Run off
     * EDT.
     */
    public Catalogue catalogue() throws SQLException {
        return run(
                false,
                connection -> {
                    context(connection);
                    return catalogue(connection);
                });
    }

    /** Validates a balanced new voucher and captures its exact scope/catalogue/numbering inputs. */
    public Preview preview(Draft draft) throws SQLException {
        Objects.requireNonNull(draft, "voucher draft");
        return run(false, connection -> preview(connection, draft));
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

    private Preview preview(Connection connection, Draft draft) throws SQLException {
        var store = context(connection);
        int number = nextNumber(connection);
        var voucher = build(connection, store, draft, number);
        BigDecimal total =
                voucher.getRows().stream()
                        .map(SSVoucherRow::getDebet)
                        .filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Preview(draft, number, total, catalogue(connection), snapshot(connection));
    }

    /**
     * Preview an exact reversal of an eligible standalone journal; explicit date/description only.
     */
    public ReversalPreview previewReversal(long sourceId, LocalDate date, String description)
            throws SQLException {
        return run(
                false,
                connection -> {
                    context(connection);
                    long year = sqlYear(connection);
                    var source = reversalSource(connection, year, sourceId);
                    if (date == null || date.isBefore(source.date())) {
                        throw new IllegalArgumentException(
                                "Reversal date must not precede the source date");
                    }
                    var draft =
                            new Draft(
                                    date,
                                    description,
                                    source.rows().stream()
                                            .map(
                                                    row ->
                                                            new Row(
                                                                    row.account(),
                                                                    row.credit(),
                                                                    row.debit(),
                                                                    row.project(),
                                                                    row.unit()))
                                            .toList());
                    return new ReversalPreview(
                            source,
                            preview(connection, draft),
                            sourceSnapshot(connection, year, sourceId));
                });
    }

    /** Atomically insert reversal rows and both correction links. Never rewrites original rows. */
    public int postReversal(ReversalPreview token) throws SQLException {
        Objects.requireNonNull(token, "reversal preview");
        synchronized (gate) {
            requireAvailable();
            if (token.owner != this) {
                throw new SQLException("Reversal preview belongs to another context");
            }
            var posting = token.posting;
            if (posting.consumed) throw new SQLException("Reversal preview was already consumed");
            posting.consumed = true;
            return run(
                    true,
                    connection -> {
                        var store = context(connection);
                        long year = sqlYear(connection);
                        if (!posting.snapshot.equals(snapshot(connection))
                                || posting.number != nextNumber(connection)
                                || !token.sourceSnapshot.equals(
                                        sourceSnapshot(connection, year, token.source.id()))) {
                            throw new SQLException("Reversal inputs changed; preview again");
                        }
                        reversalSource(
                                connection,
                                year,
                                token.source.id()); // Recheck references/eligibility too.
                        store.addVoucher(build(connection, store, posting.draft, posting.number));
                        long created;
                        try (var query =
                                connection.prepareStatement(
                                        "SELECT id FROM voucher WHERE accounting_year_id=? AND"
                                                + " number=?")) {
                            query.setLong(1, year);
                            query.setInt(2, posting.number);
                            try (var row = query.executeQuery()) {
                                if (!row.next()) {
                                    throw new SQLException("Inserted reversal not found");
                                }
                                created = row.getLong(1);
                            }
                        }
                        link(connection, year, created, token.source.id(), "corrects_voucher_id");
                        link(
                                connection,
                                year,
                                token.source.id(),
                                created,
                                "corrected_by_voucher_id");
                        return posting.number;
                    });
        }
    }

    private static void link(Connection connection, long year, long id, long target, String column)
            throws SQLException {
        // Column is one of the two internal constants above, never caller input.
        try (var query =
                connection.prepareStatement(
                        "UPDATE voucher SET "
                                + column
                                + "=? WHERE id=? AND accounting_year_id=? AND corrects_voucher_id"
                                + " IS NULL AND corrected_by_voucher_id IS NULL")) {
            query.setLong(1, target);
            query.setLong(2, id);
            query.setLong(3, year);
            if (query.executeUpdate() != 1) {
                throw new SQLException("Correction link changed; preview again");
            }
        }
    }

    private long sqlYear(Connection connection) throws SQLException {
        try (var query =
                connection.prepareStatement("SELECT id FROM accounting_year WHERE legacy_id=?")) {
            query.setInt(1, selection.year().id());
            try (var row = query.executeQuery()) {
                if (!row.next()) throw new SQLException("Selected year missing");
                return row.getLong(1);
            }
        }
    }

    private ReversalSource reversalSource(Connection connection, long year, long id)
            throws SQLException {
        int number;
        LocalDate date;
        String description;
        try (var query =
                connection.prepareStatement(
                        "SELECT"
                            + " number,voucher_date,description,corrects_voucher_id,corrected_by_voucher_id"
                            + " FROM voucher WHERE accounting_year_id=? AND id=?")) {
            query.setLong(1, year);
            query.setLong(2, id);
            try (var row = query.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("Source voucher missing from selected year");
                }
                number = row.getInt(1);
                date = row.getObject(2, LocalDate.class);
                description = row.getString(3);
                if (date == null || row.getObject(4) != null || row.getObject(5) != null) {
                    throw new SQLException("Source has unknown date or existing correction links");
                }
            }
        }
        try (var query =
                connection.prepareStatement(
                        "SELECT id FROM voucher WHERE accounting_year_id=? AND"
                                + " (corrects_voucher_id=? OR corrected_by_voucher_id=?)")) {
            query.setLong(1, year);
            query.setLong(2, id);
            query.setLong(3, id);
            try (var row = query.executeQuery()) {
                if (row.next()) {
                    throw new SQLException("Source is referenced by a correction graph");
                }
            }
        }
        requireStandalone(connection, number);
        List<Row> result = new ArrayList<>();
        try (var query =
                connection.prepareStatement(
                        "SELECT a.number, r.debit, r.credit, r.project_number,"
                                + " r.result_unit_number, r.crossed, r.added, r.edited_at,"
                                + " r.edited_signature FROM voucher_row r LEFT JOIN account a ON"
                                + " a.id=r.account_id AND a.accounting_year_id=r.accounting_year_id"
                                + " WHERE r.accounting_year_id=? AND r.voucher_id=? ORDER BY"
                                + " r.row_number")) {
            query.setLong(1, year);
            query.setLong(2, id);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    Integer account = rows.getObject(1, Integer.class);
                    if (account == null
                            || rows.getBoolean(6)
                            || rows.getBoolean(7)
                            || rows.getObject(8) != null
                            || rows.getString(9) != null) {
                        throw new SQLException(
                                "Source has unsupported account or historical row edits/flags");
                    }
                    result.add(
                            new Row(
                                    account,
                                    rows.getBigDecimal(2),
                                    rows.getBigDecimal(3),
                                    rows.getString(4),
                                    rows.getString(5)));
                }
            }
        }
        return new ReversalSource(id, number, date, description, result);
    }

    private void requireStandalone(Connection connection, int number) throws SQLException {
        // Document snapshots do not reliably identify an accounting year. Conservatively refuse
        // ANY same-company snapshot number, including another year or an ambiguous old snapshot.
        String[][] families = {
            {"customer_invoice", "invoice_id"},
            {"customer_credit_invoice", "credit_invoice_id"},
            {"supplier_invoice", "supplier_invoice_id"},
            {"supplier_credit_invoice", "supplier_credit_invoice_id"},
            {"inpayment", "inpayment_id"},
            {"outpayment", "outpayment_id"},
            {"periodic_invoice", "periodic_invoice_id"}
        };
        for (var family : families) {
            try (var query =
                    connection.prepareStatement(
                            "SELECT s.number FROM "
                                    + family[0]
                                    + "_voucher s JOIN "
                                    + family[0]
                                    + " p ON p.id=s."
                                    + family[1]
                                    + " WHERE p.company_id=(SELECT id FROM company WHERE"
                                    + " legacy_id=?) AND s.number=?")) {
                query.setInt(1, selection.company().id());
                query.setInt(2, number);
                try (var row = query.executeQuery()) {
                    if (row.next()) {
                        throw new SQLException(
                                "Document-linked or ambiguous voucher number; document-specific"
                                        + " reconciliation required");
                    }
                }
            }
        }
    }

    private static List<List<Object>> sourceSnapshot(Connection connection, long year, long id)
            throws SQLException {
        String[] queries = {
            "SELECT * FROM voucher WHERE accounting_year_id=? AND id=?",
            "SELECT * FROM voucher_row WHERE accounting_year_id=? AND voucher_id=? ORDER BY"
                    + " row_number"
        };
        List<List<Object>> result = new ArrayList<>();
        for (int index = 0; index < queries.length; index++) {
            try (var query = connection.prepareStatement(queries[index])) {
                query.setLong(1, year);
                query.setLong(2, id);
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
                    "Explicit date/description and 2 to 200 voucher rows required");
        }
        var allowed = activeAccounts(connection);
        var accountModels = store.getAccounts();
        var registers = new NormalizedRegisterStore(connection);
        var projects = registers.getProjects(selection.company().id());
        var units = registers.getResultUnits(selection.company().id());
        List<SSVoucherRow> rows = new ArrayList<>();
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (var row : draft.rows()) {
            if (allowed.stream().noneMatch(value -> value.number() == row.account())) {
                throw new IllegalArgumentException(
                        "Account is missing, inactive or has unknown requirements");
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
            var project =
                    row.project() == null
                            ? null
                            : projects.stream()
                                    .filter(value -> value.getNumber().equals(row.project()))
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    new IllegalArgumentException(
                                                            "Project does not belong to selected"
                                                                    + " company"));
            if (row.project() != null && row.project().isBlank()) {
                throw new IllegalArgumentException("Project identity must not be blank");
            }
            if (project != null
                    && project.getConcluded()
                    && (project.getLocalConcludedDate() == null
                            || project.isConcluded(draft.date()))) {
                throw new IllegalArgumentException(
                        "Project is closed on the voucher date or has unknown closure date");
            }
            var unit =
                    row.unit() == null
                            ? null
                            : units.stream()
                                    .filter(value -> value.getNumber().equals(row.unit()))
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    new IllegalArgumentException(
                                                            "Result unit does not belong to"
                                                                    + " selected company"));
            if (row.unit() != null && row.unit().isBlank()) {
                throw new IllegalArgumentException("Result-unit identity must not be blank");
            }
            var newRow =
                    new SSVoucherRow(
                            account,
                            row.debit() == null ? null : amount.setScale(2),
                            row.credit() == null ? null : amount.setScale(2),
                            project,
                            unit);
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
        var validation = VoucherValidator.validate(voucher, year, accountModels, projects, units);
        if (!validation.valid()) {
            throw new IllegalArgumentException("Voucher validation failed: " + validation.issues());
        }
        return voucher;
    }

    private Catalogue catalogue(Connection connection) throws SQLException {
        var registers = new NormalizedRegisterStore(connection);
        var projects =
                registers.getProjects(selection.company().id()).stream()
                        .map(
                                project ->
                                        new Project(
                                                project.getNumber(),
                                                project.getName(),
                                                project.getDescription(),
                                                project.getConcluded(),
                                                project.getLocalConcludedDate()))
                        .toList();
        var units =
                registers.getResultUnits(selection.company().id()).stream()
                        .map(
                                unit ->
                                        new Unit(
                                                unit.getNumber(),
                                                unit.getName(),
                                                unit.getDescription()))
                        .toList();
        return new Catalogue(activeAccounts(connection), projects, units);
    }

    private List<Account> activeAccounts(Connection connection) throws SQLException {
        List<Account> result = new ArrayList<>();
        try (var query =
                connection.prepareStatement(
                        "SELECT number,description,project_required,result_unit_required FROM"
                            + " account WHERE accounting_year_id = (SELECT id FROM accounting_year"
                            + " WHERE legacy_id=?) AND active IS TRUE AND project_required IS NOT"
                            + " NULL AND result_unit_required IS NOT NULL ORDER BY number")) {
            query.setInt(1, selection.year().id());
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    result.add(
                            new Account(
                                    rows.getInt(1),
                                    rows.getString(2),
                                    rows.getBoolean(3),
                                    rows.getBoolean(4)));
                }
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
                    + " WHERE legacy_id=?) ORDER BY id",
            "SELECT * FROM project WHERE company_id=(SELECT id FROM company WHERE legacy_id=?)"
                    + " ORDER BY number",
            "SELECT * FROM result_unit WHERE company_id=(SELECT id FROM company WHERE legacy_id=?)"
                    + " ORDER BY number"
        };
        List<List<Object>> result = new ArrayList<>();
        for (int index = 0; index < queries.length; index++) {
            try (var query = connection.prepareStatement(queries[index])) {
                query.setInt(
                        1,
                        index == 0 || index >= 4
                                ? selection.company().id()
                                : selection.year().id());
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
