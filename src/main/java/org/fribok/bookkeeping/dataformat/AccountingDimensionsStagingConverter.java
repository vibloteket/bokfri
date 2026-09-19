package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.SSNewProject;
import se.swedsoft.bookkeeping.data.SSNewResultUnit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Converts company-scoped projects and result units into normalized staging tables. */
public final class AccountingDimensionsStagingConverter {

    public ConversionResult convert(Connection legacy, Connection normalized) throws SQLException {
        Objects.requireNonNull(legacy, "legacy");
        Objects.requireNonNull(normalized, "normalized");
        Snapshot source = readLegacy(legacy);
        SemanticFingerprint sourceFingerprint = fingerprint(source);
        try {
            writeNormalized(normalized, source);
            Snapshot destination = readNormalized(normalized);
            SemanticFingerprint destinationFingerprint = fingerprint(destination);
            List<String> differences = sourceFingerprint.differences(destinationFingerprint);
            if (!differences.isEmpty()) {
                normalized.rollback();
                throw new SQLException("Accounting-dimensions staging fingerprint mismatch: "
                        + String.join("; ", differences));
            }
            normalized.commit();
            return new ConversionResult(sourceFingerprint, destinationFingerprint,
                    source.projects.size(), source.resultUnits.size());
        } catch (SQLException | RuntimeException exception) {
            normalized.rollback();
            throw exception;
        }
    }

    public SemanticFingerprint fingerprintNormalized(Connection connection) throws SQLException {
        return fingerprint(readNormalized(connection));
    }

    private static Snapshot readLegacy(Connection connection) throws SQLException {
        Snapshot snapshot = new Snapshot();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT companyid,number,project FROM tbl_project ORDER BY companyid,number")) {
            while (result.next()) {
                int company = result.getInt(1);
                String key = result.getString(2);
                SSNewProject project = (SSNewProject) result.getObject(3);
                if (!Objects.equals(key, project.getNumber())) {
                    throw new SQLException("Project key/object mismatch for company " + company
                            + ": table=" + key + ", object=" + project.getNumber());
                }
                snapshot.projects.add(new ProjectRow(company, key, project.getName(),
                        project.getDescription(), project.getConcluded(),
                        project.getLocalConcludedDate()));
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT companyid,number,resultunit FROM tbl_resultunit ORDER BY companyid,number")) {
            while (result.next()) {
                int company = result.getInt(1);
                String key = result.getString(2);
                SSNewResultUnit unit = (SSNewResultUnit) result.getObject(3);
                if (!Objects.equals(key, unit.getNumber())) {
                    throw new SQLException("Result-unit key/object mismatch for company " + company
                            + ": table=" + key + ", object=" + unit.getNumber());
                }
                snapshot.resultUnits.add(new ResultUnitRow(company, key, unit.getName(),
                        unit.getDescription()));
            }
        }
        snapshot.sort();
        return snapshot;
    }

    private static void writeNormalized(Connection connection, Snapshot snapshot) throws SQLException {
        for (ProjectRow row : snapshot.projects) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO project (company_id,number,name,description,concluded,concluded_on)
                    SELECT id,?,?,?,?,? FROM company WHERE legacy_id=?
                    """)) {
                statement.setString(1, row.number); statement.setString(2, row.name);
                statement.setString(3, row.description); statement.setBoolean(4, row.concluded);
                statement.setObject(5, row.concludedOn); statement.setInt(6, row.companyLegacyId);
                requireOne(statement, "project", row.companyLegacyId, row.number);
            }
        }
        for (ResultUnitRow row : snapshot.resultUnits) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO result_unit (company_id,number,name,description)
                    SELECT id,?,?,? FROM company WHERE legacy_id=?
                    """)) {
                statement.setString(1, row.number); statement.setString(2, row.name);
                statement.setString(3, row.description); statement.setInt(4, row.companyLegacyId);
                requireOne(statement, "result unit", row.companyLegacyId, row.number);
            }
        }
    }

    private static Snapshot readNormalized(Connection connection) throws SQLException {
        Snapshot snapshot = new Snapshot();
        query(connection, "SELECT c.legacy_id,p.number,p.name,p.description,p.concluded,p.concluded_on "
                + "FROM project p JOIN company c ON c.id=p.company_id "
                + "ORDER BY c.legacy_id,p.number", r -> snapshot.projects.add(new ProjectRow(
                        r.getInt(1), r.getString(2), r.getString(3), r.getString(4),
                        r.getBoolean(5), r.getObject(6, LocalDate.class))));
        query(connection, "SELECT c.legacy_id,u.number,u.name,u.description "
                + "FROM result_unit u JOIN company c ON c.id=u.company_id "
                + "ORDER BY c.legacy_id,u.number", r -> snapshot.resultUnits.add(
                        new ResultUnitRow(r.getInt(1), r.getString(2), r.getString(3),
                                r.getString(4))));
        snapshot.sort();
        return snapshot;
    }

    private static SemanticFingerprint fingerprint(Snapshot snapshot) {
        SemanticFingerprintBuilder builder = new SemanticFingerprintBuilder();
        var projects = builder.domain("projects");
        for (ProjectRow row : snapshot.projects) {
            projects.startRecord(row.companyLegacyId + "/" + row.number)
                    .writeInteger(row.companyLegacyId).writeString(row.number)
                    .writeString(row.name).writeString(row.description)
                    .writeBoolean(row.concluded).writeDate(row.concludedOn);
        }
        projects.finish();
        var units = builder.domain("result-units");
        for (ResultUnitRow row : snapshot.resultUnits) {
            units.startRecord(row.companyLegacyId + "/" + row.number)
                    .writeInteger(row.companyLegacyId).writeString(row.number)
                    .writeString(row.name).writeString(row.description);
        }
        units.finish();
        return builder.finish();
    }

    private static void requireOne(PreparedStatement statement, String type, int company,
                                   String number) throws SQLException {
        if (statement.executeUpdate() != 1) {
            throw new SQLException("Missing normalized company " + company + " for " + type
                    + " " + number);
        }
    }

    private static void query(Connection connection, String sql, SqlRow consumer)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                consumer.accept(result);
            }
        }
    }

    public record ConversionResult(SemanticFingerprint sourceFingerprint,
                                   SemanticFingerprint destinationFingerprint,
                                   long projects, long resultUnits) {}

    @FunctionalInterface
    private interface SqlRow {
        void accept(ResultSet result) throws SQLException;
    }

    private record ProjectRow(int companyLegacyId, String number, String name,
                              String description, boolean concluded, LocalDate concludedOn) {}
    private record ResultUnitRow(int companyLegacyId, String number, String name,
                                 String description) {}

    private static final class Snapshot {
        private final List<ProjectRow> projects = new ArrayList<>();
        private final List<ResultUnitRow> resultUnits = new ArrayList<>();

        private void sort() {
            projects.sort(Comparator.comparingInt(ProjectRow::companyLegacyId)
                    .thenComparing(ProjectRow::number));
            resultUnits.sort(Comparator.comparingInt(ResultUnitRow::companyLegacyId)
                    .thenComparing(ResultUnitRow::number));
        }
    }
}
