package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;
import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.SSVoucherRow;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountingCoreStagingCatalogServiceTest {
    private static final Instant COMPLETED_AT = Instant.parse("2026-09-19T13:45:00.123Z");

    @Test
    void createsReopensAndRetainsASeparateDurableCatalog(@TempDir Path data) throws Exception {
        try (Connection legacy = legacyConnection()) {
            populateLegacy(legacy);
            byte[] sourceScript = "legacy-source-marker".getBytes(StandardCharsets.UTF_8);
            Files.createDirectories(data.resolve("db"));
            Files.write(data.resolve("db/JFSDB.script"), sourceScript);
            Files.writeString(data.resolve("db/JFSDB.properties"), "version=source");

            var result = service().create(data, legacy);

            assertThat(result.stagingDirectory()).isDirectory();
            assertThat(result.stagingDirectory().getFileName().toString())
                    .startsWith(MigrationRecoveryInspector.NORMALIZED_STAGING_PREFIX);
            assertThat(result.database()).isEqualTo(
                    result.stagingDirectory().resolve("db").resolve("JFSDB"));
            assertThat(Path.of(result.database() + ".properties")).isRegularFile();
            assertThat(Path.of(result.database() + ".script")).isRegularFile();
            assertThat(result.completedAt()).isEqualTo(COMPLETED_AT);
            assertThat(result.conversion().destinationFingerprint())
                    .isEqualTo(result.durableFingerprint());
            assertThat(Files.readAllBytes(data.resolve("db/JFSDB.script")))
                    .containsExactly(sourceScript);
            assertThat(count(legacy, "tbl_company")).isEqualTo(1);
            assertThat(legacy.isClosed()).isFalse();
            assertThat(MigrationRecoveryInspector.inspect(data).kind()).isEqualTo(
                    MigrationRecoveryInspector.RecoveryKind.ACTIVE_DATABASE_WITH_LEFTOVERS);
        }
    }

    @Test
    void refusesExistingRecoveryStateBeforeCreatingAnotherCatalog(@TempDir Path data)
            throws Exception {
        Files.createDirectories(data.resolve(".bokfri-normalized-staging-existing"));
        try (Connection legacy = legacyConnection()) {
            assertThatThrownBy(() -> service().create(data, legacy))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("Unresolved normalized database migration state");
        }
        try (var paths = Files.list(data)) {
            assertThat(paths.filter(Files::isDirectory).count()).isEqualTo(1);
        }
    }

    @Test
    void removesNewStagingCatalogWhenConversionFails(@TempDir Path data) throws Exception {
        try (Connection legacy = legacyConnection()) {
            SSNewCompany company = new SSNewCompany();
            company.setId(1);
            insertObject(legacy, "INSERT INTO tbl_company (id,company) VALUES (?,?)", 1, company);
            SSNewAccountingYear year = year(2);
            SSAccount missingAccount = new SSAccount(9999);
            year.setInBalance(missingAccount, BigDecimal.ONE);
            try (var statement = legacy.prepareStatement(
                    "INSERT INTO tbl_accountingyear (id,accountingyear,companyid) VALUES (?,?,?)")) {
                statement.setInt(1, 2); statement.setObject(2, year); statement.setInt(3, 1);
                statement.executeUpdate(); legacy.commit();
            }

            assertThatThrownBy(() -> service().create(data, legacy))
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("Missing normalized mapping");
            assertThat(MigrationRecoveryInspector.inspect(data).kind())
                    .isEqualTo(MigrationRecoveryInspector.RecoveryKind.CLEAN);
        }
    }

    private static AccountingCoreStagingCatalogService service() {
        return new AccountingCoreStagingCatalogService(
                Clock.fixed(COMPLETED_AT, ZoneOffset.UTC));
    }

    private static Connection legacyConnection() throws Exception {
        Class.forName("org.hsqldb.jdbcDriver");
        Connection connection = DriverManager.getConnection(
                "jdbc:hsqldb:mem:catalog_source_" + UUID.randomUUID(), "sa", "");
        connection.setAutoCommit(false);
        String schema;
        try (InputStream input = AccountingCoreStagingCatalogServiceTest.class
                .getResourceAsStream("/sql/create_tables.sql")) {
            assertThat(input).isNotNull();
            schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (var statement = connection.createStatement()) {
            for (String sql : schema.split(";")) if (!sql.isBlank()) statement.execute(sql.trim());
        }
        connection.commit();
        return connection;
    }

    private static void populateLegacy(Connection connection) throws Exception {
        SSNewCompany company = new SSNewCompany(); company.setId(1); company.setName("Example AB");
        insertObject(connection, "INSERT INTO tbl_company (id,company) VALUES (?,?)", 1, company);
        SSNewAccountingYear year = year(2);
        try (var statement = connection.prepareStatement(
                "INSERT INTO tbl_accountingyear (id,accountingyear,companyid) VALUES (?,?,?)")) {
            statement.setInt(1, 2); statement.setObject(2, year); statement.setInt(3, 1);
            statement.executeUpdate();
        }
        SSVoucher voucher = new SSVoucher(1); voucher.setLocalDate(LocalDate.of(2026, 1, 2));
        voucher.addVoucherRow(new SSVoucherRow(year.getAccounts().get(0),
                new BigDecimal("10.00"), null));
        try (var statement = connection.prepareStatement(
                "INSERT INTO tbl_voucher (id,number,voucher,yearid) VALUES (?,?,?,?)")) {
            statement.setInt(1, 3); statement.setInt(2, 1); statement.setObject(3, voucher);
            statement.setInt(4, 2); statement.executeUpdate();
        }
        connection.commit();
    }

    private static SSNewAccountingYear year(int id) {
        SSAccount account = new SSAccount(1930); account.setDescription("Bank");
        SSAccountPlan plan = new SSAccountPlan("BAS"); plan.setId(4); plan.addAccount(account);
        SSNewAccountingYear year = new SSNewAccountingYear(); year.setId(id);
        year.setLocalFrom(LocalDate.of(2026, 1, 1)); year.setLocalTo(LocalDate.of(2026, 12, 31));
        year.setAccountPlan(plan); return year;
    }

    private static long count(Connection connection, String table) throws Exception {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static void insertObject(Connection connection, String sql, int id, Object value)
            throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id); statement.setObject(2, value); statement.executeUpdate();
            connection.commit();
        }
    }
}
