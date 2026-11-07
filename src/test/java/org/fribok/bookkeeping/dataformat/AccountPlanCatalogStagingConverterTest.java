package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountPlanCatalogStagingConverterTest {
    @TempDir Path temporaryDirectory;

    @Test
    void retainsUnattachedTemplatesEmptyPlansAndAccountOrderAfterReopen() throws Exception {
        String target = "jdbc:hsqldb:file:" + temporaryDirectory.resolve("catalog");
        AccountPlanCatalogStagingConverter.ConversionResult converted;
        try (Connection legacy = legacy(); Connection normalized = open(target)) {
            migrate(normalized);
            insert(legacy, 7, plan(7));
            SSAccountPlan empty = new SSAccountPlan("Same template name");
            empty.setId(8);
            empty.setType((String) null);
            insert(legacy, 8, empty);
            converted = new AccountPlanCatalogStagingConverter().convert(legacy, normalized);
            assertThat(converted.plans()).isEqualTo(2);
            assertThat(converted.accounts()).isEqualTo(2);
            assertThat(converted.sourceFingerprint()).isEqualTo(converted.destinationFingerprint());
            assertThat(count(legacy, "tbl_accountplan")).isEqualTo(2);
            assertThat(count(normalized, "company")).isZero();
            assertThat(count(normalized, "account_plan")).isZero();
            try (var statement = normalized.createStatement()) { statement.execute("SHUTDOWN"); }
        }
        try (Connection normalized = open(target)) {
            var converter = new AccountPlanCatalogStagingConverter();
            assertThat(converter.fingerprintNormalized(normalized)).isEqualTo(converted.sourceFingerprint());
            try (var statement = normalized.createStatement();
                 var rows = statement.executeQuery("SELECT number,description,active,project_required,"
                         + "result_unit_required FROM account_plan_catalog_account "
                         + "WHERE plan_legacy_id=7 ORDER BY row_number")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(3010);
                assertThat(rows.getString(2)).isEqualTo("Åäö catalogue revenue");
                assertThat(rows.getBoolean(3)).isFalse();
                assertThat(rows.getBoolean(4)).isTrue();
                assertThat(rows.getBoolean(5)).isTrue();
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(1930);
                assertThat(rows.getString(2)).isNull();
                assertThat(rows.next()).isFalse();
            }
            for (String update : List.of(
                    "UPDATE account_plan_catalog SET name='changed' WHERE legacy_id=7",
                    "UPDATE account_plan_catalog SET base_name='changed' WHERE legacy_id=7",
                    "UPDATE account_plan_catalog SET assessment_year='changed' WHERE legacy_id=7",
                    "UPDATE account_plan_catalog SET plan_type='changed' WHERE legacy_id=7",
                    "UPDATE account_plan_catalog_account SET row_number=9 WHERE plan_legacy_id=7 AND row_number=0",
                    "UPDATE account_plan_catalog_account SET number=9999 WHERE number=3010",
                    "UPDATE account_plan_catalog_account SET description='changed' WHERE number=3010",
                    "UPDATE account_plan_catalog_account SET sru_code='changed' WHERE number=3010",
                    "UPDATE account_plan_catalog_account SET vat_code='changed' WHERE number=3010",
                    "UPDATE account_plan_catalog_account SET report_code='changed' WHERE number=3010",
                    "UPDATE account_plan_catalog_account SET active=TRUE WHERE number=3010",
                    "UPDATE account_plan_catalog_account SET project_required=FALSE WHERE number=3010",
                    "UPDATE account_plan_catalog_account SET result_unit_required=FALSE WHERE number=3010")) {
                try (var statement = normalized.createStatement()) { statement.executeUpdate(update); }
                assertThat(converted.sourceFingerprint().differences(converter.fingerprintNormalized(normalized)))
                        .as(update).isNotEmpty();
                normalized.rollback();
                assertThat(converter.fingerprintNormalized(normalized)).isEqualTo(converted.sourceFingerprint());
            }
            try (var statement = normalized.createStatement()) { statement.execute("SHUTDOWN"); }
        }
    }

    @Test
    void rejectsMismatchedObjectKeysAndInvalidAccountsWithoutChangingTheSource() throws Exception {
        for (String scenario : List.of("key", "number", "null-account", "wrong-type")) {
            try (Connection legacy = legacy(); Connection normalized = normalized()) {
                SSAccountPlan plan = plan(scenario.equals("key") ? 99 : 7);
                if (scenario.equals("number")) plan.getAccounts().add(new SSAccount());
                if (scenario.equals("null-account")) plan.getAccounts().add(null);
                insert(legacy, 7, scenario.equals("wrong-type") ? "not an account plan" : plan);
                assertThatThrownBy(() -> new AccountPlanCatalogStagingConverter().convert(legacy, normalized))
                        .as(scenario).isInstanceOf(SQLException.class);
                assertThat(count(normalized, "account_plan_catalog")).isZero();
                assertThat(count(normalized, "account_plan_catalog_account")).isZero();
                assertThat(count(legacy, "tbl_accountplan")).isEqualTo(1);
            }
        }
    }

    @Test
    void duplicateAccountNumbersRollBackBothTemplateAndAlreadyInsertedRows() throws Exception {
        try (Connection legacy = legacy(); Connection normalized = normalized()) {
            SSAccountPlan plan = plan(7);
            plan.getAccounts().add(new SSAccount(3010));
            insert(legacy, 7, plan);
            assertThatThrownBy(() -> new AccountPlanCatalogStagingConverter().convert(legacy, normalized))
                    .isInstanceOf(SQLException.class);
            assertThat(count(normalized, "account_plan_catalog")).isZero();
            assertThat(count(normalized, "account_plan_catalog_account")).isZero();
            try (var statement = legacy.createStatement();
                 var rows = statement.executeQuery("SELECT accountplan FROM tbl_accountplan WHERE id=7")) {
                assertThat(rows.next()).isTrue();
                assertThat(((SSAccountPlan) rows.getObject(1)).getAccounts()).hasSize(3);
            }
        }
    }

    @Test
    void emptyCatalogueIsVerifiedWithoutManufacturingDefaultPlans() throws Exception {
        try (Connection legacy = legacy(); Connection normalized = normalized()) {
            var result = new AccountPlanCatalogStagingConverter().convert(legacy, normalized);
            assertThat(result.plans()).isZero();
            assertThat(result.accounts()).isZero();
            assertThat(result.sourceFingerprint()).isEqualTo(result.destinationFingerprint());
            assertThat(count(normalized, "account_plan_catalog")).isZero();
        }
    }

    @Test
    void enforcesTemplateOwnershipPositiveNumbersAndDistinctRowsAndAccounts() throws Exception {
        try (Connection normalized = normalized(); var statement = normalized.createStatement()) {
            statement.executeUpdate("INSERT INTO account_plan_catalog (legacy_id,name) VALUES (7,'Template')");
            statement.executeUpdate("INSERT INTO account_plan_catalog_account "
                    + "(plan_legacy_id,row_number,number,active,project_required,result_unit_required) "
                    + "VALUES (7,0,1930,TRUE,FALSE,FALSE)");
            normalized.commit();
            for (String values : List.of("(99,1,3010,TRUE,FALSE,FALSE)", "(7,0,3010,TRUE,FALSE,FALSE)",
                    "(7,1,1930,TRUE,FALSE,FALSE)", "(7,-1,3010,TRUE,FALSE,FALSE)",
                    "(7,1,0,TRUE,FALSE,FALSE)", "(7,1,3010,NULL,FALSE,FALSE)")) {
                assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO account_plan_catalog_account "
                        + "(plan_legacy_id,row_number,number,active,project_required,result_unit_required) VALUES "
                        + values)).as(values).isInstanceOf(SQLException.class);
                normalized.rollback();
            }
            assertThat(count(normalized, "account_plan_catalog_account")).isEqualTo(1);
        }
    }

    private static SSAccountPlan plan(int id) {
        SSAccountPlan plan = new SSAccountPlan("Same template name");
        plan.setId(id);
        plan.setBaseName("Base plan");
        plan.setAssessementYear("2026");
        SSAccount revenue = new SSAccount(3010);
        revenue.setDescription("Åäö catalogue revenue");
        revenue.setSRUCode("SRU");
        revenue.setVATCode("VAT");
        revenue.setReportCode("REPORT");
        revenue.setActive(false);
        revenue.setProjectRequired(true);
        revenue.setResultUnitRequired(true);
        plan.getAccounts().add(revenue);
        plan.getAccounts().add(new SSAccount(1930));
        return plan;
    }

    private static void insert(Connection connection, int id, Object value) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO tbl_accountplan VALUES (?,?)")) {
            statement.setInt(1, id);
            statement.setObject(2, value);
            statement.executeUpdate();
            connection.commit();
        }
    }

    private static Connection legacy() throws Exception {
        Connection connection = open("jdbc:hsqldb:mem:catalog_legacy_" + UUID.randomUUID());
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE tbl_accountplan(id INTEGER PRIMARY KEY,accountplan OTHER)");
        }
        connection.commit();
        return connection;
    }

    private static Connection normalized() throws Exception {
        Connection connection = open("jdbc:hsqldb:mem:catalog_normalized_" + UUID.randomUUID());
        migrate(connection);
        return connection;
    }

    private static void migrate(Connection connection) throws Exception {
        new SchemaMigrationRunner(Clock.systemUTC()).migrate(connection, NormalizedSchemaMigrations.load());
    }

    private static Connection open(String url) throws Exception {
        Class.forName("org.hsqldb.jdbcDriver");
        Connection connection = DriverManager.getConnection(url, "sa", "");
        connection.setAutoCommit(false);
        return connection;
    }

    private static long count(Connection connection, String table) throws Exception {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }
}
