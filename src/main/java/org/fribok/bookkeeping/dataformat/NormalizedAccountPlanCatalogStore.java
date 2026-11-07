package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Typed global account-plan templates; never reads or changes accounting-year copies or legacy storage. */
public final class NormalizedAccountPlanCatalogStore {
    private final Connection connection;

    /** @param connection caller-owned normalized connection; caller controls commit/rollback */
    public NormalizedAccountPlanCatalogStore(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * Checks for the recorded V19 migration and both catalogue tables without executing any DDL.
     * @return whether the catalogue is available (not a substitute for recovering older missing data)
     * @throws SQLException if metadata cannot be read
     */
    public boolean isAvailable() throws SQLException {
        for (String table : List.of("BOKFRI_SCHEMA_HISTORY", "ACCOUNT_PLAN_CATALOG", "ACCOUNT_PLAN_CATALOG_ACCOUNT")) {
            try (var tables = connection.getMetaData().getTables(null, "PUBLIC", table, new String[]{"TABLE"})) {
                if (!tables.next()) return false;
            }
        }
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM bokfri_schema_history WHERE version=19")) {
            return result.next() && result.getInt(1) == 1;
        }
    }

    private void requireCatalog() throws SQLException {
        if (!isAvailable()) {
            throw new SchemaMigrationException("Normalized account-plan catalogue requires schema V19; "
                    + "older normalized data needs explicit recovery from its retained legacy source/backup");
        }
    }

    /**
     * Lists templates by ID, preserving each template's stored account order and every flag/code.
     * @return detached templates, including empty templates and duplicate names
     * @throws SQLException if the catalogue is absent or cannot be read
     */
    public List<SSAccountPlan> getAccountPlans() throws SQLException {
        requireCatalog();
        Map<Integer, SSAccountPlan> plans = new LinkedHashMap<>();
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT legacy_id,name,base_name,assessment_year,plan_type "
                     + "FROM account_plan_catalog ORDER BY legacy_id")) {
            while (result.next()) {
                SSAccountPlan plan = new SSAccountPlan();
                plan.setId(result.getInt(1));
                plan.setName(result.getString(2));
                plan.setBaseName(result.getString(3));
                plan.setAssessementYear(result.getString(4));
                String type = result.getString(5);
                if (type != null && se.swedsoft.bookkeeping.data.SSAccountPlanType.get(type) == null) {
                    throw new SQLException("Unsupported account-plan catalogue type: " + type);
                }
                plan.setType(type);
                plans.put(plan.getId(), plan);
            }
        }
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT plan_legacy_id,number,description,sru_code,vat_code,"
                     + "report_code,active,project_required,result_unit_required "
                     + "FROM account_plan_catalog_account ORDER BY plan_legacy_id,row_number")) {
            while (result.next()) {
                SSAccountPlan plan = plans.get(result.getInt(1));
                if (plan == null) throw new SQLException("Catalogue account has no owning template");
                SSAccount account = new SSAccount(result.getInt(2));
                account.setDescription(result.getString(3));
                account.setSRUCode(result.getString(4));
                account.setVATCode(result.getString(5));
                account.setReportCode(result.getString(6));
                account.setActive(result.getBoolean(7));
                account.setProjectRequired(result.getBoolean(8));
                account.setResultUnitRequired(result.getBoolean(9));
                plan.getAccounts().add(account);
            }
        }
        return List.copyOf(plans.values());
    }

    /**
     * Appends a template with a fresh catalogue ID. The caller must commit or roll back all rows together.
     * @param plan template to save; its ID is assigned after all inserts succeed
     * @throws SQLException if the catalogue is absent or an insert fails
     */
    public void addAccountPlan(SSAccountPlan plan) throws SQLException {
        requireCatalog();
        int id;
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT MAX(legacy_id) FROM account_plan_catalog")) {
            result.next();
            int maximum = result.getInt(1);
            id = result.wasNull() ? 0 : Math.addExact(maximum, 1);
        }
        try (var statement = connection.prepareStatement("INSERT INTO account_plan_catalog "
                + "(legacy_id,name,base_name,assessment_year,plan_type) VALUES (?,?,?,?,?)")) {
            statement.setInt(1, id);
            statement.setString(2, plan.getName());
            statement.setString(3, plan.getBaseName());
            statement.setString(4, plan.getAssessementYear());
            statement.setString(5, plan.getType() == null ? null : plan.getType().getName());
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("INSERT INTO account_plan_catalog_account "
                + "(plan_legacy_id,row_number,number,description,sru_code,vat_code,report_code,active,"
                + "project_required,result_unit_required) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            int row = 0;
            for (SSAccount account : plan.getAccounts()) {
                statement.setInt(1, id);
                statement.setInt(2, row++);
                statement.setObject(3, account.getNumber());
                statement.setString(4, account.getDescription());
                statement.setString(5, account.getSRUCode());
                statement.setString(6, account.getVATCode());
                statement.setString(7, account.getReportCode());
                statement.setBoolean(8, account.isActive());
                statement.setBoolean(9, account.isProjectRequired());
                statement.setBoolean(10, account.isResultUnitRequired());
                statement.executeUpdate();
            }
        }
        plan.setId(id);
    }
}
