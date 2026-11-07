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
    void companyCreationRetainsRegisteredLookupValuesInsteadOfOverwritingThem() throws Exception {
        try (Connection connection = connection();
             NormalizedAccountingStore store = store(connection)) {
            migrate(connection);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO currency VALUES ('XTS','Stored currency',2.125)");
                statement.executeUpdate("INSERT INTO unit_definition VALUES ('custom','Stored unit')");
                statement.executeUpdate("INSERT INTO payment_term VALUES ('45','Stored payment term')");
            }
            SSNewCompany company = new SSNewCompany();
            company.setId(5);
            company.setName("Lookup company");
            var currency = new se.swedsoft.bookkeeping.data.common.SSCurrency("XTS", "Must not overwrite");
            currency.setExchangeRate(new BigDecimal("9.99"));
            company.setCurrency(currency);
            company.setStandardUnit(new se.swedsoft.bookkeeping.data.common.SSUnit("custom", "Must not overwrite"));
            company.setPaymentTerm(new se.swedsoft.bookkeeping.data.common.SSPaymentTerm("45", "Must not overwrite"));
            store.addCompany(company);
            store.commit();
            SSNewCompany stored = store.getCompanies().get(0);
            assertThat(stored.getCurrency().getDescription()).isEqualTo("Stored currency");
            assertThat(stored.getCurrency().getExchangeRate()).isEqualByComparingTo("2.125");
            assertThat(stored.getStandardUnit().getDescription()).isEqualTo("Stored unit");
            assertThat(stored.getPaymentTerm().getDescription()).isEqualTo("Stored payment term");
        }
    }

    @Test
    void readsAllSalesReportCompanyFieldsAndCompletePostalAddress() throws Exception {
        try (Connection connection = connection();
             NormalizedAccountingStore store = store(connection)) {
            migrate(connection);
            SSNewCompany company = new SSNewCompany();
            company.setId(5);
            company.setName("Sales company");
            store.addCompany(company);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        UPDATE company SET phone='phone',telefax='fax',residence='residence',
                        email='mail@example.test',web_address='https://example.test',reminder_fee=42.5,
                        delay_interest=8.5,plus_account_number='765-4321',bank_account_number='123-4567',
                        swift_bic='BICCODE',iban='SE123456',bank='Bank name',tax_registered=TRUE,
                        weight_unit='kg',volume_unit='m3',logotype_path='company-logo.png'
                        """);
                statement.executeUpdate("""
                        INSERT INTO company_address (company_id,address_type,name,address_line_1,
                        address_line_2,postal_code,city,country)
                        SELECT id,'postal','Postal name','Line one','Line two','12345','City','Country'
                        FROM company
                        """);
            }
            SSNewCompany actual = store.getCompanies().get(0);
            assertThat(actual.getPhone()).isEqualTo("phone");
            assertThat(actual.getTelefax()).isEqualTo("fax");
            assertThat(actual.getResidence()).isEqualTo("residence");
            assertThat(actual.getEMail()).isEqualTo("mail@example.test");
            assertThat(actual.getHomepage()).isEqualTo("https://example.test");
            assertThat(actual.getReminderfee()).isEqualByComparingTo("42.5");
            assertThat(actual.getDelayInterest()).isEqualByComparingTo("8.5");
            assertThat(actual.getPlusGiroNumber()).isEqualTo("765-4321");
            assertThat(actual.getBankGiroNumber()).isEqualTo("123-4567");
            assertThat(actual.getBIC()).isEqualTo("BICCODE");
            assertThat(actual.getIBAN()).isEqualTo("SE123456");
            assertThat(actual.getBank()).isEqualTo("Bank name");
            assertThat(actual.getTaxRegistered()).isTrue();
            assertThat(actual.getWeightUnit()).isEqualTo("kg");
            assertThat(actual.getVolumeUnit()).isEqualTo("m3");
            assertThat(actual.getLogotype()).isEqualTo("company-logo.png");
            assertThat(actual.getAddress().getName()).isEqualTo("Postal name");
            assertThat(actual.getAddress().getAddress1()).isEqualTo("Line one");
            assertThat(actual.getAddress().getAddress2()).isEqualTo("Line two");
            assertThat(actual.getAddress().getZipCode()).isEqualTo("12345");
            assertThat(actual.getAddress().getCity()).isEqualTo("City");
            assertThat(actual.getAddress().getCountry()).isEqualTo("Country");
            connection.rollback();
        }
    }

    @Test
    void readsStandardTextsFromOnlyTheRequestedCompany() throws Exception {
        try (Connection connection = connection();
             NormalizedAccountingStore store = store(connection)) {
            migrate(connection);
            for (int id : new int[] {5, 6}) {
                SSNewCompany company = new SSNewCompany();
                company.setId(id);
                company.setName("Company " + id);
                store.addCompany(company);
            }
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO company_standard_text (company_id,text_type,text_value)
                        SELECT id,'Reminder','Reminder five' FROM company WHERE legacy_id=5
                        """);
                statement.executeUpdate("""
                        INSERT INTO company_standard_text (company_id,text_type,text_value)
                        SELECT id,'Reminder','Reminder six' FROM company WHERE legacy_id=6
                        """);
            }
            var companies = store.getCompanies();
            assertThat(companies.get(0).getStandardText(se.swedsoft.bookkeeping.data.SSStandardText.Reminder))
                    .contains("Reminder five");
            assertThat(companies.get(1).getStandardText(se.swedsoft.bookkeeping.data.SSStandardText.Reminder))
                    .contains("Reminder six");
            assertThat(companies.get(0).getStandardTexts()).hasSize(1);
            connection.rollback();
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
