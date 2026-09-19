package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;
import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.data.common.SSDeliveryTerm;
import se.swedsoft.bookkeeping.data.common.SSDeliveryWay;
import se.swedsoft.bookkeeping.data.common.SSPaymentTerm;
import se.swedsoft.bookkeeping.data.common.SSUnit;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountingCoreStagingConverterTest {
    private static final Instant INSTALLED_AT = Instant.parse("2026-09-19T07:30:00Z");

    @Test
    void convertsLegacyObjectsAndMatchesIndependentFingerprints() throws Exception {
        try (Connection legacy = connection("legacy"); Connection target = connection("target")) {
            createLegacySchema(legacy);
            createNormalizedSchema(target);
            populateLegacy(legacy);

            AccountingCoreStagingConverter.ConversionResult result =
                    new AccountingCoreStagingConverter().convert(legacy, target);

            assertThat(result.sourceFingerprint()).isEqualTo(result.destinationFingerprint());
            assertThat(result.companies()).isEqualTo(1);
            assertThat(result.years()).isEqualTo(1);
            assertThat(result.accounts()).isEqualTo(2);
            assertThat(result.vouchers()).isEqualTo(2);
            assertThat(result.voucherRows()).isEqualTo(3);
            assertThat(result.daylightSavingGapAdjustments()).isEqualTo(1);
            assertThat(result.daylightSavingOverlapResolutions()).isEqualTo(1);
            assertThat(count(target, "company")).isEqualTo(1);
            assertThat(count(target, "currency")).isEqualTo(2);
            assertThat(count(target, "unit_definition")).isEqualTo(1);
            assertThat(count(target, "payment_term")).isEqualTo(1);
            assertThat(count(target, "delivery_term")).isEqualTo(1);
            assertThat(count(target, "delivery_way")).isEqualTo(1);
            assertThat(count(target, "voucher_row")).isEqualTo(3);
        }
    }

    private static void populateLegacy(Connection connection) throws Exception {
        SSNewCompany company = new SSNewCompany();
        company.setId(7);
        company.setName("Ångström & Söner AB");
        company.setCorporateID("556000-0001");
        company.setVATNumber("SE556000000101");
        company.setCurrency(new SSCurrency("SEK", "Svenska kronor"));
        company.setStandardUnit(new SSUnit("st", "styck"));
        company.setPaymentTerm(new SSPaymentTerm("30", "30 dagar netto"));
        company.setDeliveryTerm(new SSDeliveryTerm("FK", "Fritt kund"));
        company.setDeliveryWay(new SSDeliveryWay("P", "Post"));
        try (var statement = connection.prepareStatement(
                "INSERT INTO tbl_currency (code,currency) VALUES (?,?)")) {
            SSCurrency eur = new SSCurrency("EUR", "Euro");
            eur.setExchangeRate(new BigDecimal("11.125"));
            statement.setString(1, eur.getName()); statement.setObject(2, eur);
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement(
                "INSERT INTO tbl_company (id,company) VALUES (?,?)")) {
            statement.setInt(1, 7);
            statement.setObject(2, company);
            statement.executeUpdate();
        }

        SSAccount bank = account(1930, "Bank");
        SSAccount sales = account(3010, "Försäljning");
        SSAccountPlan plan = new SSAccountPlan("BAS 2026");
        plan.setId(4);
        plan.setAssessementYear("2026");
        plan.addAccount(bank);
        plan.addAccount(sales);
        SSNewAccountingYear year = new SSNewAccountingYear();
        year.setId(11);
        year.setLocalFrom(LocalDate.of(2026, 1, 1));
        year.setLocalTo(LocalDate.of(2026, 12, 31));
        year.setAccountPlan(plan);
        year.setInBalance(bank, new BigDecimal("1000.00"));
        year.getBudget().setYear(year);
        var month = year.getBudget().getMonths().get(0);
        year.getBudget().setSaldoForAccountAndMonth(sales, month, new BigDecimal("250.50"));
        try (var statement = connection.prepareStatement(
                "INSERT INTO tbl_accountingyear (id,accountingyear,companyid) VALUES (?,?,?)")) {
            statement.setInt(1, 11);
            statement.setObject(2, year);
            statement.setInt(3, 7);
            statement.executeUpdate();
        }

        SSVoucher first = new SSVoucher(1);
        first.setLocalDate(LocalDate.of(2026, 3, 29));
        first.setDescription("Första");
        SSVoucherRow debit = new SSVoucherRow(bank, new BigDecimal("125.00"), null);
        debit.setLocalEditedDate(LocalDateTime.of(2026, 3, 29, 2, 30));
        debit.setEditedSignature("vb");
        first.addVoucherRow(debit);
        first.addVoucherRow(new SSVoucherRow(sales, null, new BigDecimal("125.0")));

        SSVoucher second = new SSVoucher(2);
        second.setLocalDate(LocalDate.of(2026, 10, 25));
        second.setDescription("");
        second.setCorrects(first);
        first.setCorrectedBy(second);
        SSVoucherRow empty = new SSVoucherRow();
        empty.setLocalEditedDate(LocalDateTime.of(2026, 10, 25, 2, 30));
        second.addVoucherRow(empty);

        insertVoucher(connection, 101, 11, first);
        insertVoucher(connection, 102, 11, second);
        connection.commit();
    }

    private static SSAccount account(int number, String description) {
        SSAccount account = new SSAccount(number);
        account.setDescription(description);
        return account;
    }

    private static void insertVoucher(Connection connection, int id, int year, SSVoucher voucher)
            throws Exception {
        try (var statement = connection.prepareStatement(
                "INSERT INTO tbl_voucher (id,number,voucher,yearid) VALUES (?,?,?,?)")) {
            statement.setInt(1, id);
            statement.setInt(2, voucher.getNumber());
            statement.setObject(3, voucher);
            statement.setInt(4, year);
            statement.executeUpdate();
        }
    }

    private static void createLegacySchema(Connection connection) throws Exception {
        String schema;
        try (InputStream input = AccountingCoreStagingConverterTest.class
                .getResourceAsStream("/sql/create_tables.sql")) {
            assertThat(input).isNotNull();
            schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (var statement = connection.createStatement()) {
            for (String sql : schema.split(";")) {
                if (!sql.isBlank()) statement.execute(sql.trim());
            }
        }
        connection.commit();
    }

    private static void createNormalizedSchema(Connection connection) throws Exception {
        new SchemaMigrationRunner(Clock.fixed(INSTALLED_AT, ZoneOffset.UTC))
                .migrate(connection, NormalizedSchemaMigrations.load());
    }

    private static Connection connection(String name) throws Exception {
        Class.forName("org.hsqldb.jdbcDriver");
        Connection connection = DriverManager.getConnection(
                "jdbc:hsqldb:mem:" + name + "_" + UUID.randomUUID(), "sa", "");
        connection.setAutoCommit(false);
        return connection;
    }

    private static long count(Connection connection, String table) throws Exception {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }
}
