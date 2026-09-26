package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NormalizedAccountingCoreSchemaTest {
    private static final Instant INSTALLED_AT = Instant.parse("2026-09-18T20:00:00Z");

    @Test
    void createsOnlyExplicitAccountingCoreTables() throws Exception {
        try (Connection connection = migratedConnection();
             var tables = connection.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
            var applicationTables = new java.util.TreeSet<String>();
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME");
                if (!"INFORMATION_SCHEMA".equalsIgnoreCase(tables.getString("TABLE_SCHEM"))) {
                    applicationTables.add(name.toLowerCase(java.util.Locale.ROOT));
                }
            }
            assertThat(applicationTables).containsExactly(
                    "account", "account_plan", "accounting_year", "auto_distribution",
                    "auto_distribution_row", "bokfri_schema_history",
                    "budget_entry", "company", "company_address", "company_auto_increment",
                    "company_default_account", "company_standard_text", "currency", "customer",
                    "customer_address", "customer_credit_invoice", "customer_credit_invoice_address",
                    "customer_credit_invoice_default_account", "customer_credit_invoice_row",
                    "customer_credit_invoice_voucher", "customer_credit_invoice_voucher_row",
                    "customer_invoice", "customer_invoice_address",
                    "customer_invoice_default_account", "customer_invoice_row",
                    "customer_invoice_voucher", "customer_invoice_voucher_row", "customer_order",
                    "customer_order_address", "customer_order_default_account", "customer_order_row",
                    "delivery_term", "delivery_way", "indelivery", "indelivery_row", "inpayment",
                    "inpayment_default_account", "inpayment_row", "inpayment_voucher",
                    "inpayment_voucher_row", "inventory", "inventory_row", "opening_balance",
                    "outdelivery", "outdelivery_row", "outpayment",
                    "outpayment_default_account", "outpayment_row", "outpayment_voucher",
                    "outpayment_voucher_row", "own_report", "own_report_account_budget",
                    "own_report_account_row", "own_report_heading", "payment_term", "periodic_invoice",
                    "periodic_invoice_instance", "periodic_invoice_instance_row",
                    "periodic_invoice_template_row", "product", "product_component",
                    "product_default_account", "product_description", "project",
                    "purchase_order", "purchase_order_address", "purchase_order_default_account",
                    "purchase_order_row", "result_unit",
                    "supplier", "supplier_address", "supplier_credit_invoice",
                    "supplier_credit_invoice_default_account", "supplier_credit_invoice_row",
                    "supplier_credit_invoice_voucher", "supplier_credit_invoice_voucher_row",
                    "supplier_invoice", "supplier_invoice_default_account",
                    "supplier_invoice_row", "supplier_invoice_voucher", "supplier_invoice_voucher_row",
                    "tender", "tender_address", "tender_default_account", "tender_row",
                    "unit_definition", "voucher", "voucher_row", "voucher_template",
                    "voucher_template_row");
            try (var columns = connection.getMetaData().getColumns(null, null, "%", "%")) {
                while (columns.next()) {
                    assertThat(columns.getInt("DATA_TYPE"))
                            .as("%s.%s", columns.getString("TABLE_NAME"),
                                    columns.getString("COLUMN_NAME"))
                            .isNotEqualTo(java.sql.Types.JAVA_OBJECT);
                }
            }
        }
    }

    @Test
    void appliesPackagedSchemaAndRoundTripsCoreTypes() throws Exception {
        try (Connection connection = connection()) {
            assertThat(migrate(connection))
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18);
            assertThat(migrate(connection)).isEmpty();

            long company = insertCompany(connection, 7, null);
            long year = insertYear(connection, 11, company,
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
            long account = insertAccount(connection, year, 1930);
            insertOpeningBalance(connection, year, account,
                    new BigDecimal("12345678901234567890.123456789012345678901234567890"));
            long voucher = insertVoucher(connection, year, 1, null);
            Instant editedAt = Instant.parse("2026-09-18T17:30:00.123456Z");
            insertVoucherRow(connection, voucher, year, account, editedAt,
                    new BigDecimal("125.000000000000000000000000000001"), null);
            connection.commit();

            try (var statement = connection.prepareStatement("""
                    SELECT c.name, y.starts_on, ob.amount, v.voucher_date,
                           r.edited_at, r.debit, r.credit
                    FROM company c
                    JOIN accounting_year y ON y.company_id=c.id
                    JOIN opening_balance ob ON ob.accounting_year_id=y.id
                    JOIN voucher v ON v.accounting_year_id=y.id
                    JOIN voucher_row r ON r.voucher_id=v.id
                    WHERE c.legacy_id=7
                    """)) {
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isNull();
                    assertThat(result.getObject(2, LocalDate.class))
                            .isEqualTo(LocalDate.of(2026, 1, 1));
                    assertThat(result.getBigDecimal(3))
                            .isEqualByComparingTo("12345678901234567890.123456789012345678901234567890");
                    assertThat(result.getObject(4, LocalDate.class)).isNull();
                    assertThat(result.getObject(5, OffsetDateTime.class).toInstant())
                            .isEqualTo(editedAt);
                    assertThat(result.getBigDecimal(6))
                            .isEqualByComparingTo("125.000000000000000000000000000001");
                    assertThat(result.getBigDecimal(7)).isNull();
                    assertThat(result.next()).isFalse();
                }
            }
        }
    }

    @Test
    void enforcesBusinessIdentityAndDateConstraints() throws Exception {
        try (Connection connection = migratedConnection()) {
            long company = insertCompany(connection, 1, "Example AB");
            assertSqlRejected(() -> insertCompany(connection, 1, "Another name"));
            assertSqlRejected(() -> insertYear(connection, 2, company,
                    LocalDate.of(2026, 12, 31), LocalDate.of(2026, 1, 1)));

            long year = insertYear(connection, 2, company,
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
            insertAccount(connection, year, 1930);
            assertSqlRejected(() -> insertAccount(connection, year, 1930));
            long voucher = insertVoucher(connection, year, 1, null);
            assertSqlRejected(() -> insertVoucher(connection, year, 1, null));
            assertSqlRejected(() -> setCorrection(connection, voucher, voucher));
        }
    }

    @Test
    void preventsCrossYearRowsAndInvalidAmountsButPreservesEmptyRows() throws Exception {
        try (Connection connection = migratedConnection()) {
            long company = insertCompany(connection, 1, "Example AB");
            long firstYear = insertYear(connection, 1, company,
                    LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
            long secondYear = insertYear(connection, 2, company,
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
            long firstAccount = insertAccount(connection, firstYear, 1930);
            long secondAccount = insertAccount(connection, secondYear, 1930);
            long voucher = insertVoucher(connection, firstYear, 1, LocalDate.of(2025, 1, 2));
            long otherYearVoucher = insertVoucher(connection, secondYear, 1,
                    LocalDate.of(2026, 1, 2));

            assertSqlRejected(() -> setCorrection(connection, voucher, otherYearVoucher));
            assertSqlRejected(() -> insertVoucherRow(connection, voucher, firstYear,
                    secondAccount, null, BigDecimal.ONE, null));
            assertSqlRejected(() -> insertVoucherRow(connection, voucher, firstYear,
                    firstAccount, null, BigDecimal.ONE, BigDecimal.ONE));
            assertSqlRejected(() -> insertVoucherRow(connection, voucher, firstYear,
                    firstAccount, null, BigDecimal.ONE.negate(), null));

            // Legacy UI semantics explicitly allow a persisted empty row.
            insertVoucherRow(connection, voucher, firstYear, null, null, null, null);
            connection.commit();
            assertThat(count(connection, "voucher_row")).isEqualTo(1);
        }
    }

    private static Connection migratedConnection() throws Exception {
        Connection connection = connection();
        migrate(connection);
        return connection;
    }

    private static java.util.List<Integer> migrate(Connection connection) throws Exception {
        return new SchemaMigrationRunner(Clock.fixed(INSTALLED_AT, ZoneOffset.UTC))
                .migrate(connection, NormalizedSchemaMigrations.load());
    }

    private static Connection connection() throws Exception {
        Class.forName("org.hsqldb.jdbcDriver");
        Connection connection = DriverManager.getConnection(
                "jdbc:hsqldb:mem:normalized_core_" + UUID.randomUUID(), "sa", "");
        connection.setAutoCommit(false);
        return connection;
    }

    private static long insertCompany(Connection connection, int legacyId, String name)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO company (legacy_id,name) VALUES (?,?)",
                java.sql.Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, legacyId);
            statement.setString(2, name);
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private static long insertYear(Connection connection, int legacyId, long company,
                                   LocalDate from, LocalDate to) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO accounting_year (legacy_id,company_id,starts_on,ends_on) VALUES (?,?,?,?)",
                java.sql.Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, legacyId);
            statement.setLong(2, company);
            statement.setObject(3, from);
            statement.setObject(4, to);
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private static long insertAccount(Connection connection, long year, int number)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO account (accounting_year_id,number,active,project_required,
                  result_unit_required) VALUES (?,?,TRUE,FALSE,FALSE)
                """, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, year);
            statement.setInt(2, number);
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private static void insertOpeningBalance(Connection connection, long year, long account,
                                             BigDecimal amount) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO opening_balance VALUES (?,?,?)")) {
            statement.setLong(1, year);
            statement.setLong(2, account);
            statement.setBigDecimal(3, amount);
            statement.executeUpdate();
        }
    }

    private static long insertVoucher(Connection connection, long year, int number, LocalDate date)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO voucher (accounting_year_id,number,voucher_date) VALUES (?,?,?)",
                java.sql.Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, year);
            statement.setInt(2, number);
            statement.setObject(3, date);
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private static void setCorrection(Connection connection, long voucher, long corrects)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE voucher SET corrects_voucher_id=? WHERE id=?")) {
            statement.setLong(1, corrects);
            statement.setLong(2, voucher);
            statement.executeUpdate();
        }
    }

    private static void insertVoucherRow(Connection connection, long voucher, long year,
                                         Long account, Instant editedAt, BigDecimal debit,
                                         BigDecimal credit) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO voucher_row (voucher_id,accounting_year_id,row_number,account_id,
                  debit,credit,edited_at,crossed,added) VALUES (?,?,?,?,?,?,?,FALSE,FALSE)
                """)) {
            statement.setLong(1, voucher);
            statement.setLong(2, year);
            statement.setInt(3, (int) count(connection, "voucher_row"));
            if (account == null) {
                statement.setNull(4, java.sql.Types.BIGINT);
            } else {
                statement.setLong(4, account);
            }
            statement.setBigDecimal(5, debit);
            statement.setBigDecimal(6, credit);
            statement.setObject(7, editedAt == null ? null
                    : OffsetDateTime.ofInstant(editedAt, ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private static long generatedKey(java.sql.PreparedStatement statement) throws SQLException {
        try (var keys = statement.getGeneratedKeys()) {
            assertThat(keys.next()).isTrue();
            return keys.getLong(1);
        }
    }

    private static long count(Connection connection, String table) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static void assertSqlRejected(SqlOperation operation) {
        assertThatThrownBy(operation::run).isInstanceOf(SQLException.class);
    }

    @FunctionalInterface
    private interface SqlOperation {
        void run() throws SQLException;
    }
}
