package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSAccountPlan;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NormalizedAccountPlanCatalogStoreTest {
    @Test
    void anEmptyCatalogueIsValidAndAcceptsTheFirstEmptyTemplateWithoutManufacturingDefaults() throws Exception {
        try (Connection connection = connection()) {
            var store = new NormalizedAccountPlanCatalogStore(connection);
            assertThat(store.isAvailable()).isTrue();
            assertThat(store.getAccountPlans()).isEmpty();
            SSAccountPlan plan = new SSAccountPlan("Empty template");
            store.addAccountPlan(plan);
            connection.commit();
            assertThat(plan.getId()).isZero();
            assertThat(store.getAccountPlans()).hasSize(1);
            assertThat(store.getAccountPlans().get(0).getAccounts()).isEmpty();
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT COUNT(*) FROM accounting_year")) {
                result.next();
                assertThat(result.getInt(1)).isZero();
            }
        }
    }

    @Test
    void requiresTheRecordedMigrationAndRejectsUnknownTypesWithoutSilentlyReplacingThem() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            var store = new NormalizedAccountPlanCatalogStore(connection);
            statement.executeUpdate("DELETE FROM bokfri_schema_history WHERE version=19");
            assertThat(store.isAvailable()).isFalse();
            assertThatThrownBy(store::getAccountPlans).isInstanceOf(SchemaMigrationException.class);
            assertThatThrownBy(() -> store.addAccountPlan(new SSAccountPlan("Blocked")))
                    .isInstanceOf(SchemaMigrationException.class);
            connection.rollback();
            assertThat(store.isAvailable()).isTrue();
            statement.executeUpdate("INSERT INTO account_plan_catalog (legacy_id,name,plan_type) "
                    + "VALUES (7,'Unknown type','NOT-A-KNOWN-TYPE')");
            connection.commit();
            assertThatThrownBy(store::getAccountPlans).isInstanceOf(SQLException.class)
                    .hasMessageContaining("Unsupported account-plan catalogue type");
            try (var result = statement.executeQuery("SELECT plan_type FROM account_plan_catalog WHERE legacy_id=7")) {
                result.next();
                assertThat(result.getString(1)).isEqualTo("NOT-A-KNOWN-TYPE");
            }
        }
    }

    private static Connection connection() throws Exception {
        Class.forName("org.hsqldb.jdbcDriver");
        Connection connection = DriverManager.getConnection(
                "jdbc:hsqldb:mem:catalog_store_" + UUID.randomUUID(), "sa", "");
        connection.setAutoCommit(false);
        new SchemaMigrationRunner(Clock.systemUTC()).migrate(connection, NormalizedSchemaMigrations.load());
        return connection;
    }
}
