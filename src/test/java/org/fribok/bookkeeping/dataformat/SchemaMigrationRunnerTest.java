package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaMigrationRunnerTest {
    private static final Instant INSTALLED_AT = Instant.parse("2026-09-18T17:30:00.123456Z");

    @Test
    void appliesConsecutiveMigrationsOnceAndStoresUtcTimestamp() throws Exception {
        try (Connection connection = connection()) {
            SchemaMigrationRunner runner = runner();
            List<SchemaMigration> migrations = List.of(
                    migration(1, "V1__create_example.sql",
                            "CREATE TABLE example (id INTEGER PRIMARY KEY)"),
                    migration(2, "V2__add_name.sql",
                            "ALTER TABLE example ADD COLUMN name VARCHAR(50)"));

            assertThat(runner.migrate(connection, migrations)).containsExactly(1, 2);
            assertThat(runner.migrate(connection, migrations)).isEmpty();

            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT version, installed_at FROM "
                         + SchemaMigrationRunner.HISTORY_TABLE + " ORDER BY version")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
                assertThat(result.getObject(2, OffsetDateTime.class).toInstant())
                        .isEqualTo(INSTALLED_AT);
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(2);
                assertThat(result.next()).isFalse();
            }
        }
    }

    @Test
    void rejectsChangedInstalledMigration() throws Exception {
        try (Connection connection = connection()) {
            SchemaMigrationRunner runner = runner();
            runner.migrate(connection, List.of(migration(1, "V1__first.sql",
                    "CREATE TABLE first_table (id INTEGER)")));

            assertThatThrownBy(() -> runner.migrate(connection,
                    List.of(migration(1, "V1__first.sql", "CREATE TABLE changed (id INTEGER)"))))
                    .isInstanceOf(SchemaMigrationException.class)
                    .hasMessageContaining("does not match");
        }
    }

    @Test
    void rejectsMissingAndNonConsecutiveMigrations() throws Exception {
        try (Connection connection = connection()) {
            SchemaMigrationRunner runner = runner();
            assertThatThrownBy(() -> runner.migrate(connection,
                    List.of(migration(2, "V2__second.sql", "CREATE TABLE second_table (id INTEGER)"))))
                    .isInstanceOf(SchemaMigrationException.class)
                    .hasMessageContaining("expected 1 but found 2");

            runner.migrate(connection, List.of(migration(1, "V1__first.sql",
                    "CREATE TABLE first_table (id INTEGER)")));
            assertThatThrownBy(() -> runner.migrate(connection, List.of()))
                    .isInstanceOf(SchemaMigrationException.class)
                    .hasMessageContaining("is missing");
        }
    }

    @Test
    void rejectsGapInInstalledHistory() throws Exception {
        try (Connection connection = connection()) {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE " + SchemaMigrationRunner.HISTORY_TABLE + " ("
                        + "version INTEGER PRIMARY KEY, description VARCHAR(255) NOT NULL, "
                        + "script_name VARCHAR(255) NOT NULL UNIQUE, script_sha256 CHAR(64) NOT NULL, "
                        + "installed_at TIMESTAMP WITH TIME ZONE NOT NULL, "
                        + "application_version VARCHAR(64) NOT NULL)");
            }
            SchemaMigration second = migration(2, "V2__second.sql",
                    "CREATE TABLE second_table (id INTEGER)");
            try (var statement = connection.prepareStatement("INSERT INTO "
                    + SchemaMigrationRunner.HISTORY_TABLE + " VALUES (?,?,?,?,?,?)")) {
                statement.setInt(1, 2);
                statement.setString(2, second.description());
                statement.setString(3, second.scriptName());
                statement.setString(4, second.scriptSha256());
                statement.setObject(5, OffsetDateTime.ofInstant(INSTALLED_AT, ZoneOffset.UTC));
                statement.setString(6, "test");
                statement.executeUpdate();
                connection.commit();
            }

            assertThatThrownBy(() -> runner().migrate(connection, List.of(
                    migration(1, "V1__first.sql", "CREATE TABLE first_table (id INTEGER)"), second)))
                    .isInstanceOf(SchemaMigrationException.class)
                    .hasMessageContaining("history has a gap at version 1");
        }
    }

    @Test
    void splitsCommentsAndSemicolonsInsideSqlStrings() throws Exception {
        assertThat(SchemaMigrationRunner.splitStatements("""
                -- first table
                CREATE TABLE example (text VARCHAR(50));
                /* a semicolon ; in a comment */
                INSERT INTO example VALUES ('one;two');
                INSERT INTO example VALUES ('it''s valid');
                """))
                .containsExactly(
                        "CREATE TABLE example (text VARCHAR(50))",
                        "INSERT INTO example VALUES ('one;two')",
                        "INSERT INTO example VALUES ('it''s valid')");
    }

    @Test
    void failedMigrationIsNotRecorded() throws Exception {
        try (Connection connection = connection()) {
            SchemaMigrationRunner runner = runner();
            List<SchemaMigration> migrations = List.of(
                    migration(1, "V1__first.sql", "CREATE TABLE first_table (id INTEGER)"),
                    migration(2, "V2__broken.sql", "INSERT INTO missing_table VALUES (1)"));

            assertThatThrownBy(() -> runner.migrate(connection, migrations))
                    .isInstanceOf(SchemaMigrationException.class)
                    .hasMessageContaining("V2__broken.sql failed");
            // HSQLDB commits DDL implicitly. The disposable staging catalog can therefore
            // contain partial structure, but the failed migration must never be recorded.
            assertThat(tableExists(connection, "FIRST_TABLE")).isTrue();
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT COUNT(*) FROM "
                         + SchemaMigrationRunner.HISTORY_TABLE)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            }
        }
    }

    private static SchemaMigrationRunner runner() {
        return new SchemaMigrationRunner(Clock.fixed(INSTALLED_AT, ZoneOffset.UTC));
    }

    private static SchemaMigration migration(int version, String name, String sql) throws Exception {
        String checksum = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return new SchemaMigration(version, name, name, checksum, sql);
    }

    private static Connection connection() throws Exception {
        Class.forName("org.hsqldb.jdbcDriver");
        Connection connection = DriverManager.getConnection(
                "jdbc:hsqldb:mem:schema_migration_" + UUID.randomUUID(), "sa", "");
        connection.setAutoCommit(false);
        return connection;
    }

    private static boolean tableExists(Connection connection, String name) throws Exception {
        try (var tables = connection.getMetaData().getTables(null, null, name,
                new String[]{"TABLE"})) {
            return tables.next();
        }
    }
}
