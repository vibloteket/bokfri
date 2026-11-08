package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Types;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizedCompanyDeletionTest {
    @Test
    void deletesEveryOwnedTableWithForeignKeysEnabledAndPreservesAllOtherRows() throws Exception {
        try (Connection connection = database()) {
            List<String> tables = tables();
            seed(connection, tables, 101);
            Map<String, List<String>> first = rows(connection, tables);
            seed(connection, tables, 202);
            Map<String, List<String>> both = rows(connection, tables);
            Set<String> owned = ownedTables(connection);
            assertThat(owned).contains("CUSTOMER_INVOICE_VOUCHER_ROW", "OWN_REPORT_ACCOUNT_BUDGET",
                    "PERIODIC_INVOICE_INSTANCE_ROW", "PRODUCT_COMPONENT", "VOUCHER_ROW");
            assertThat(owned.size()).isGreaterThan(75);
            new NormalizedAccountingWriter(Clock.systemUTC()).deleteCompany(connection, 101);
            connection.commit();
            Map<String, List<String>> remaining = rows(connection, tables);
            for (String table : tables) {
                if (owned.contains(table)) {
                    List<String> other = new ArrayList<>(both.get(table));
                    other.removeAll(first.get(table));
                    assertThat(remaining.get(table)).as(table).containsExactlyElementsOf(other);
                } else {
                    assertThat(remaining.get(table)).as("global " + table).isEqualTo(both.get(table));
                }
            }
        }
    }

    @Test
    void removalIsFullyRollbackableWithoutDisablingConstraints() throws Exception {
        try (Connection connection = database()) {
            List<String> tables = tables();
            seed(connection, tables, 101);
            seed(connection, tables, 202);
            var before = rows(connection, tables);
            new NormalizedAccountingWriter(Clock.systemUTC()).deleteCompany(connection, 101);
            connection.rollback();
            assertThat(rows(connection, tables)).isEqualTo(before);
        }
    }

    private Connection database() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:hsqldb:mem:delete_" + UUID.randomUUID(), "sa", "");
        connection.setAutoCommit(false);
        new SchemaMigrationRunner(Clock.systemUTC()).migrate(connection, NormalizedSchemaMigrations.load());
        return connection;
    }

    private List<String> tables() throws Exception {
        List<String> tables = new ArrayList<>();
        Pattern create = Pattern.compile("CREATE TABLE (?:IF NOT EXISTS )?([a-z_]+)");
        for (SchemaMigration migration : NormalizedSchemaMigrations.load()) {
            var matcher = create.matcher(migration.sql());
            while (matcher.find()) tables.add(matcher.group(1).toUpperCase(java.util.Locale.ROOT));
        }
        assertThat(tables).hasSize(91);
        return tables;
    }

    /** Minimal rows in EVERY schema table, including every required composite FK and both owners. */
    private void seed(Connection connection, List<String> tables, int owner) throws Exception {
        for (String table : tables) {
            Set<String> primary = new HashSet<>();
            try (var keys = connection.getMetaData().getPrimaryKeys(null, "PUBLIC", table)) {
                while (keys.next()) primary.add(keys.getString("COLUMN_NAME"));
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            try (var columns = connection.getMetaData().getColumns(null, "PUBLIC", table, "%")) {
                while (columns.next()) {
                    String name = columns.getString("COLUMN_NAME");
                    if (columns.getInt("NULLABLE") != 0 && !primary.contains(name)) continue;
                    int type = columns.getInt("DATA_TYPE");
                    Object value = switch (type) {
                        case Types.BIGINT, Types.INTEGER, Types.SMALLINT -> owner;
                        case Types.DECIMAL, Types.NUMERIC -> java.math.BigDecimal.ONE;
                        case Types.BOOLEAN, Types.BIT -> false;
                        case Types.DATE -> java.time.LocalDate.of(2026, 1, 1);
                        case Types.TIMESTAMP_WITH_TIMEZONE -> java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z");
                        default -> "P" + owner;
                    };
                    if (name.equals("ADDRESS_TYPE")) {
                        value = table.equals("COMPANY_ADDRESS") ? "postal"
                                : table.equals("PURCHASE_ORDER_ADDRESS") ? "delivery" : "invoice";
                    }
                    if (name.equals("VOUCHER_KIND")) value = "main";
                    fields.put(name, value);
                }
            }
            String placeholders = String.join(",", java.util.Collections.nCopies(fields.size(), "?"));
            try (var insert = connection.prepareStatement("INSERT INTO " + table + " ("
                    + String.join(",", fields.keySet()) + ") VALUES (" + placeholders + ")")) {
                int index = 1;
                for (Object value : fields.values()) insert.setObject(index++, value);
                insert.executeUpdate();
            }
        }
        connection.commit();
    }

    private Set<String> ownedTables(Connection connection) throws Exception {
        Set<String> owned = new HashSet<>(Set.of("COMPANY"));
        boolean changed;
        do {
            changed = false;
            for (String parent : List.copyOf(owned)) {
                try (var keys = connection.getMetaData().getExportedKeys(null, "PUBLIC", parent)) {
                    while (keys.next()) changed |= owned.add(keys.getString("FKTABLE_NAME"));
                }
            }
        } while (changed);
        return owned;
    }

    private Map<String, List<String>> rows(Connection connection, List<String> tables) throws Exception {
        Map<String, List<String>> snapshot = new TreeMap<>();
        for (String table : tables) {
            List<String> records = new ArrayList<>();
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT * FROM " + table)) {
                while (result.next()) {
                    CanonicalFingerprintWriter row = new CanonicalFingerprintWriter();
                    for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                        row.writeString(result.getString(column));
                    }
                    records.add(row.finish());
                }
            }
            records.sort(String::compareTo);
            snapshot.put(table, records);
        }
        return snapshot;
    }
}
