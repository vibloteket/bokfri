package org.fribok.bookkeeping.service.supplierinvoice;

import org.fribok.bookkeeping.dataformat.CanonicalFingerprintWriter;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService.Kind;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService.RowSnapshot;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierDocumentMetadataService.RowValue;
import org.fribok.bookkeeping.service.supplierinvoice.NormalizedSupplierRowAmountsService.Row;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Existing supplier row allocations only; caller owns transaction, commit and exclusive writer
 * gate.
 */
public final class NormalizedSupplierRowAccountingService {
    /** Selected-year account presentation and explicit dimension requirements. */
    public record Account(
            int number,
            String description,
            boolean active,
            boolean projectRequired,
            boolean resultUnitRequired) {}

    /** Exact company code/name; concluded projects are not selectable for changed allocations. */
    public record Dimension(String number, String name, boolean selectable) {}

    /** A persisted row key and explicit allocation tuple; NULL dimensions mean unassigned. */
    public record Values(int key, Integer account, String project, String resultUnit) {}

    private record Context(
            List<Account> accounts,
            List<Dimension> projects,
            List<Dimension> units,
            String signature) {
        private Context {
            accounts = List.copyOf(accounts);
            projects = List.copyOf(projects);
            units = List.copyOf(units);
        }
    }

    /** Complete document source and immutable selected-year/company accounting catalogue. */
    public static final class Snapshot {
        private final RowSnapshot document;
        private final int year;
        private final Context context;

        private Snapshot(RowSnapshot document, int year, Context context) {
            this.document = document;
            this.year = year;
            this.context = context;
        }

        public int companyId() {
            return document.companyId();
        }

        public int yearId() {
            return year;
        }

        public Kind kind() {
            return document.kind();
        }

        public long id() {
            return document.id();
        }

        public Integer number() {
            return document.number();
        }

        public String supplier() {
            return document.supplier();
        }

        public List<Row> rows() {
            return document.rows();
        }

        public List<Account> accounts() {
            return context.accounts();
        }

        public List<Dimension> projects() {
            return context.projects();
        }

        public List<Dimension> resultUnits() {
            return context.units();
        }
    }

    /** Exact before/after tuple for one immutable row key. */
    public record Change(Values before, Values after) {}

    /** Immutable source-bound confirmation request. */
    public static final class Plan {
        private final Snapshot source;
        private final List<Values> proposed;
        private final List<Change> changes;

        private Plan(Snapshot source, List<Values> proposed, List<Change> changes) {
            this.source = source;
            this.proposed = List.copyOf(proposed);
            this.changes = List.copyOf(changes);
        }

        public Snapshot source() {
            return source;
        }

        public List<Change> changes() {
            return changes;
        }
    }

    /** Saved identity; caller still owns the outer commit. */
    public record Updated(int companyId, int yearId, Kind kind, long id) {}

    private final Connection connection;

    /** Binds caller-owned connection without SQL access or migration. */
    public NormalizedSupplierRowAccountingService(Connection connection) {
        this.connection = Objects.requireNonNull(connection);
    }

    /** Reads raw document rows and exact selected-year/company catalogues without defaults. */
    public Snapshot load(int company, int year, Kind kind, long id) throws SQLException {
        var source =
                new NormalizedSupplierDocumentMetadataService(connection)
                        .loadRows(company, kind, id);
        return new Snapshot(source, year, context(company, year));
    }

    /**
     * Rechecks complete source and catalogue; changed tuples must be explicitly valid as a whole.
     */
    public Plan preview(Snapshot expected, List<Values> proposed) throws SQLException {
        Objects.requireNonNull(expected, "snapshot");
        Objects.requireNonNull(proposed, "allocations");
        var metadata = new NormalizedSupplierDocumentMetadataService(connection);
        metadata.previewRows(
                expected.document,
                expected.rows().stream()
                        .map(row -> new RowValue(row.key(), row.description()))
                        .toList());
        var fresh = context(expected.companyId(), expected.year);
        if (!fresh.signature().equals(expected.context.signature())) {
            throw new SQLException("Selected year or company accounting catalogue changed; reload");
        }
        if (proposed.size() != expected.rows().size()) {
            throw new IllegalArgumentException("Row insertion/removal is not supported");
        }
        Map<Integer, Values> values = new HashMap<>();
        for (var next : new ArrayList<>(proposed)) {
            if (next == null || values.put(next.key(), next) != null) {
                throw new IllegalArgumentException("Duplicate or missing row key");
            }
        }
        List<Values> ordered = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        for (var row : expected.rows()) {
            var before = values(row);
            var next = values.remove(row.key());
            if (next == null) {
                throw new IllegalArgumentException("Foreign or missing row key");
            }
            if (!before.equals(next)) {
                var account =
                        fresh.accounts().stream()
                                .filter(
                                        a ->
                                                next.account() != null
                                                        && next.account() > 0
                                                        && a.number() == next.account()
                                                        && a.active())
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "Changed rows require an active"
                                                            + " selected-year account"));
                if ((next.project() != null || account.projectRequired())
                        && !validDimension(fresh.projects(), next.project())) {
                    throw new IllegalArgumentException(
                            "Changed allocation requires a valid non-concluded company project");
                }
                if ((next.resultUnit() != null || account.resultUnitRequired())
                        && !validDimension(fresh.units(), next.resultUnit())) {
                    throw new IllegalArgumentException(
                            "Changed allocation requires a valid company result unit");
                }
                changes.add(new Change(before, next));
            }
            ordered.add(next);
        }
        if (!values.isEmpty()) {
            throw new IllegalArgumentException("Foreign row key");
        }
        return new Plan(expected, ordered, changes);
    }

    /** Revalidates and updates only changed allocation columns under a local savepoint. */
    public Updated confirm(Plan expected) throws SQLException {
        Objects.requireNonNull(expected, "plan");
        if (connection.getAutoCommit()) {
            throw new SQLException("Allocation editing requires a caller-owned transaction");
        }
        Savepoint savepoint = connection.setSavepoint();
        try {
            var fresh = preview(expected.source, expected.proposed);
            String table =
                    fresh.source.kind() == Kind.CREDIT
                            ? "supplier_credit_invoice"
                            : "supplier_invoice";
            for (var change : fresh.changes) {
                boolean account =
                        !Objects.equals(change.before().account(), change.after().account());
                boolean project =
                        !Objects.equals(change.before().project(), change.after().project());
                boolean unit =
                        !Objects.equals(change.before().resultUnit(), change.after().resultUnit());
                List<String> columns = new ArrayList<>();
                if (account) {
                    columns.add("account_number=?");
                }
                if (project) {
                    columns.add("project_number=?");
                }
                if (unit) {
                    columns.add("result_unit_number=?");
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
                    if (account) {
                        statement.setInt(parameter++, change.after().account());
                    }
                    if (project) {
                        statement.setString(parameter++, change.after().project());
                    }
                    if (unit) {
                        statement.setString(parameter++, change.after().resultUnit());
                    }
                    statement.setLong(parameter++, fresh.source.id());
                    statement.setInt(parameter++, change.after().key());
                    statement.setInt(parameter, fresh.source.companyId());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Missing or foreign supplier allocation target");
                    }
                }
            }
            connection.releaseSavepoint(savepoint);
            return new Updated(
                    fresh.source.companyId(),
                    fresh.source.year,
                    fresh.source.kind(),
                    fresh.source.id());
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                connection.rollback(savepoint);
            } catch (SQLException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }

    /** Copies immutable raw allocation values without fixing unrelated historical fields. */
    public static Values values(Row row) {
        return new Values(row.key(), row.account(), row.project(), row.resultUnit());
    }

    private static boolean validDimension(List<Dimension> values, String code) {
        return code != null
                && !code.isBlank()
                && code.length() <= 255
                && !code.codePoints().anyMatch(Character::isISOControl)
                && values.stream().anyMatch(d -> d.selectable() && code.equals(d.number()));
    }

    private Context context(int company, int year) throws SQLException {
        var writer = new CanonicalFingerprintWriter();
        writer.writeInteger(company).writeInteger(year);
        long yearId;
        try (var statement =
                connection.prepareStatement(
                        "SELECT y.* FROM accounting_year y JOIN company c ON c.id=y.company_id"
                            + " WHERE y.legacy_id=? AND c.legacy_id=?")) {
            statement.setInt(1, year);
            statement.setInt(2, company);
            try (var row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("Missing or foreign selected accounting year");
                }
                yearId = row.getLong("id");
                writer.writeBoundary("year");
                writeCurrent(writer, row);
            }
        }
        List<Account> accounts = new ArrayList<>();
        List<Dimension> projects = new ArrayList<>();
        List<Dimension> units = new ArrayList<>();
        writer.writeBoundary("accounts");
        try (var statement =
                connection.prepareStatement(
                        "SELECT * FROM account WHERE accounting_year_id=? ORDER BY number,id")) {
            statement.setLong(1, yearId);
            try (var row = statement.executeQuery()) {
                while (row.next()) {
                    writeCurrent(writer, row);
                    accounts.add(
                            new Account(
                                    row.getInt("number"),
                                    row.getString("description"),
                                    row.getBoolean("active"),
                                    row.getBoolean("project_required"),
                                    row.getBoolean("result_unit_required")));
                }
            }
        }
        for (boolean project : List.of(true, false)) {
            String table = project ? "project" : "result_unit";
            writer.writeBoundary(table);
            try (var statement =
                    connection.prepareStatement(
                            "SELECT * FROM "
                                    + table
                                    + " WHERE company_id=(SELECT id FROM company WHERE legacy_id=?)"
                                    + " ORDER BY number")) {
                statement.setInt(1, company);
                try (var row = statement.executeQuery()) {
                    while (row.next()) {
                        writeCurrent(writer, row);
                        var value =
                                new Dimension(
                                        row.getString("number"),
                                        row.getString("name"),
                                        !project || !row.getBoolean("concluded"));
                        if (project) {
                            projects.add(value);
                        } else {
                            units.add(value);
                        }
                    }
                }
            }
        }
        writer.writeBoundary("end");
        return new Context(accounts, projects, units, writer.finish());
    }

    private static void writeCurrent(CanonicalFingerprintWriter writer, ResultSet row)
            throws SQLException {
        writer.writeBoundary("row");
        int columns = row.getMetaData().getColumnCount();
        writer.writeInteger(columns);
        for (int i = 1; i <= columns; i++) {
            writer.writeString(row.getMetaData().getColumnName(i)).writeString(row.getString(i));
        }
    }
}
