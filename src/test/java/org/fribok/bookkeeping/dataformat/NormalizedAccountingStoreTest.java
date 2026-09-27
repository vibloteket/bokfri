package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSVoucher;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NormalizedAccountingStoreTest {

    @Test
    void supportsTheCoreSsdbShapedWorkflow() throws Exception {
        try (Connection connection = connection();
             NormalizedAccountingStore store = store(connection)) {
            migrate(connection);
            SSNewCompany company = new SSNewCompany();
            company.setId(5);
            company.setName("Fasad AB");
            store.addCompany(company);
            store.commit();

            store.setCurrentCompany(store.getCompanies().get(0));
            SSAccountPlan plan = new SSAccountPlan("Bas");
            plan.addAccount(new SSAccount(1930));
            SSNewAccountingYear year = new SSNewAccountingYear();
            year.setId(3);
            year.setLocalFrom(LocalDate.of(2026, 1, 1));
            year.setLocalTo(LocalDate.of(2026, 12, 31));
            year.setAccountPlan(plan);
            store.addAccountingYear(year);
            store.setCurrentYear(store.getYears().get(0));
            store.commit();

            SSVoucher voucher = new SSVoucher(1);
            voucher.setLocalDate(LocalDate.of(2026, 4, 1));
            voucher.addVoucherRow(plan.getAccount(1930), new BigDecimal("25.00"), null);
            store.addVoucher(voucher);
            store.commit();
            assertThat(store.getVouchers()).hasSize(1);
            assertThat(store.getAccounts()).hasSize(1);

            store.deleteVoucher(voucher);
            store.commit();
            assertThat(store.getVouchers()).isEmpty();

            store.deleteCompany(company);
            store.commit();
            assertThat(store.getCurrentCompany()).isNull();
            assertThat(store.getCompanies()).isEmpty();
        }
    }

    @Test
    void requiresSelectionBeforeYearScopedWork() throws Exception {
        try (Connection connection = connection();
             NormalizedAccountingStore store = store(connection)) {
            migrate(connection);
            assertThatThrownBy(store::getYears)
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("No current company");
            SSNewCompany company = new SSNewCompany();
            company.setId(1);
            store.addCompany(company);
            store.setCurrentCompany(company);
            assertThatThrownBy(store::getVouchers)
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("No current accounting year");
            store.rollback();
            assertThat(store.getCompanies()).isEmpty();
        }
    }

    private static NormalizedAccountingStore store(Connection connection) {
        return new NormalizedAccountingStore(connection,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    private static Connection connection() throws Exception {
        Class.forName("org.hsqldb.jdbcDriver");
        Connection connection = DriverManager.getConnection(
                "jdbc:hsqldb:mem:store_" + UUID.randomUUID(), "sa", "");
        connection.setAutoCommit(false);
        return connection;
    }

    private static void migrate(Connection connection) throws Exception {
        new SchemaMigrationRunner(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
                .migrate(connection, NormalizedSchemaMigrations.load());
    }
}
