package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Converts global reusable account-plan templates without confusing them with accounting-year copies. */
public final class AccountPlanCatalogStagingConverter {
    /**
     * Copies and verifies the catalogue in a separate normalized staging database.
     * @param legacy read-only legacy source
     * @param normalized staging destination; caller owns its lifecycle
     * @return source/destination fingerprints and template/account counts
     * @throws SQLException if source keys are inconsistent or conversion cannot be verified
     */
    public ConversionResult convert(Connection legacy, Connection normalized) throws SQLException {
        Snapshot source = readLegacy(Objects.requireNonNull(legacy, "legacy"));
        SemanticFingerprint sourceFingerprint = fingerprint(source);
        Objects.requireNonNull(normalized, "normalized");
        try {
            writeNormalized(normalized, source);
            Snapshot destination = readNormalized(normalized);
            SemanticFingerprint destinationFingerprint = fingerprint(destination);
            List<String> differences = sourceFingerprint.differences(destinationFingerprint);
            if (!differences.isEmpty()) {
                throw new SQLException("Account-plan catalogue fingerprint mismatch: "
                        + String.join("; ", differences));
            }
            normalized.commit();
            return new ConversionResult(sourceFingerprint, destinationFingerprint,
                    source.plans.size(), source.accounts.size());
        } catch (SQLException | RuntimeException exception) {
            normalized.rollback();
            throw exception;
        }
    }

    /**
     * Reads the typed catalogue only, including account ordering and all persisted flags/codes.
     * @param connection normalized database
     * @return deterministic semantic fingerprint
     * @throws SQLException if the catalogue cannot be read
     */
    public SemanticFingerprint fingerprintNormalized(Connection connection) throws SQLException {
        return fingerprint(readNormalized(connection));
    }

    private static Snapshot readLegacy(Connection connection) throws SQLException {
        Snapshot snapshot = new Snapshot();
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT id,accountplan FROM tbl_accountplan ORDER BY id")) {
            while (result.next()) {
                int id = result.getInt(1);
                Object value = result.getObject(2);
                if (!(value instanceof SSAccountPlan plan) || !Objects.equals(id, plan.getId())) {
                    throw new SQLException("Account-plan catalogue key/object mismatch for id " + id);
                }
                snapshot.plans.add(new Plan(id, plan.getName(), plan.getBaseName(), plan.getAssessementYear(),
                        plan.getType() == null ? null : plan.getType().getName()));
                int row = 0;
                for (SSAccount account : plan.getAccounts()) {
                    if (account == null || account.getNumber() == null) {
                        throw new SQLException("Invalid account in catalogue plan " + id + " at row " + row);
                    }
                    snapshot.accounts.add(new Account(id, row++, account.getNumber(), account.getDescription(),
                            account.getSRUCode(), account.getVATCode(), account.getReportCode(), account.isActive(),
                            account.isProjectRequired(), account.isResultUnitRequired()));
                }
            }
        }
        return snapshot;
    }

    private static void writeNormalized(Connection connection, Snapshot snapshot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO account_plan_catalog "
                + "(legacy_id,name,base_name,assessment_year,plan_type) VALUES (?,?,?,?,?)")) {
            for (Plan plan : snapshot.plans) {
                statement.setInt(1, plan.id);
                statement.setString(2, plan.name);
                statement.setString(3, plan.baseName);
                statement.setString(4, plan.assessmentYear);
                statement.setString(5, plan.type);
                statement.executeUpdate();
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO account_plan_catalog_account "
                + "(plan_legacy_id,row_number,number,description,sru_code,vat_code,report_code,active,"
                + "project_required,result_unit_required) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            for (Account account : snapshot.accounts) {
                statement.setInt(1, account.planId);
                statement.setInt(2, account.row);
                statement.setInt(3, account.number);
                statement.setString(4, account.description);
                statement.setString(5, account.sru);
                statement.setString(6, account.vat);
                statement.setString(7, account.report);
                statement.setBoolean(8, account.active);
                statement.setBoolean(9, account.projectRequired);
                statement.setBoolean(10, account.resultUnitRequired);
                statement.executeUpdate();
            }
        }
    }

    private static Snapshot readNormalized(Connection connection) throws SQLException {
        Snapshot snapshot = new Snapshot();
        try (var statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT legacy_id,name,base_name,assessment_year,plan_type "
                     + "FROM account_plan_catalog ORDER BY legacy_id")) {
            while (result.next()) {
                snapshot.plans.add(new Plan(result.getInt(1), result.getString(2), result.getString(3),
                        result.getString(4), result.getString(5)));
            }
        }
        try (var statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT plan_legacy_id,row_number,number,description,"
                     + "sru_code,vat_code,report_code,active,project_required,result_unit_required "
                     + "FROM account_plan_catalog_account ORDER BY plan_legacy_id,row_number")) {
            while (result.next()) {
                snapshot.accounts.add(new Account(result.getInt(1), result.getInt(2), result.getInt(3),
                        result.getString(4), result.getString(5), result.getString(6), result.getString(7),
                        result.getBoolean(8), result.getBoolean(9), result.getBoolean(10)));
            }
        }
        return snapshot;
    }

    private static SemanticFingerprint fingerprint(Snapshot snapshot) {
        SemanticFingerprintBuilder builder = new SemanticFingerprintBuilder();
        var plans = builder.domain("account-plan-catalog");
        for (Plan plan : snapshot.plans) {
            plans.startRecord(Integer.toString(plan.id)).writeInteger(plan.id).writeString(plan.name)
                    .writeString(plan.baseName).writeString(plan.assessmentYear).writeString(plan.type);
        }
        plans.finish();
        var accounts = builder.domain("account-plan-catalog-accounts");
        for (Account account : snapshot.accounts) {
            accounts.startRecord(account.planId + "/" + account.row).writeInteger(account.planId)
                    .writeInteger(account.row).writeInteger(account.number).writeString(account.description)
                    .writeString(account.sru).writeString(account.vat).writeString(account.report)
                    .writeBoolean(account.active).writeBoolean(account.projectRequired)
                    .writeBoolean(account.resultUnitRequired);
        }
        accounts.finish();
        return builder.finish();
    }

    /** Verified catalogue conversion result. */
    public record ConversionResult(SemanticFingerprint sourceFingerprint,
            SemanticFingerprint destinationFingerprint, long plans, long accounts) { }

    private record Plan(int id, String name, String baseName, String assessmentYear, String type) { }
    private record Account(int planId, int row, int number, String description, String sru, String vat,
            String report, boolean active, boolean projectRequired, boolean resultUnitRequired) { }

    private static final class Snapshot {
        private final List<Plan> plans = new ArrayList<>();
        private final List<Account> accounts = new ArrayList<>();
    }
}
