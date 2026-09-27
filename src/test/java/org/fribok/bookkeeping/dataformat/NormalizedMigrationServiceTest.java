package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The full opt-in pipeline on the real v1.0.1 fixture: migrate → format 3 → readable normalized DB. */
@Tag("integration")
class NormalizedMigrationServiceTest {
    private static final String FIXTURE = "/compat/v1.0.1/database-v1.0.1.zip";

    @Test
    void migratesTheFixtureEndToEnd(@TempDir Path tempDir) throws Exception {
        Path data = tempDir.resolve("data");
        extractFixture(data.resolve("db"));

        NormalizedMigrationService.MigrationResult result =
                new NormalizedMigrationService(
                        Clock.fixed(Instant.parse("2026-09-27T08:30:00Z"), ZoneOffset.UTC))
                        .migrate(data);

        assertThat(result.staging().stagingDirectory()).doesNotExist();
        assertThat(result.activation().rollbackArchive()).isRegularFile();
        assertThat(result.activation().retainedSource()).isDirectory();

        DataFormatManager.DataFormatStatus status = DataFormatManager.inspect(data);
        assertThat(status.format()).isEqualTo(DataFormatManager.NORMALIZED_DATA_FORMAT_VERSION);

        Class.forName("org.hsqldb.jdbcDriver");
        try (var connection = DriverManager.getConnection(
                "jdbc:hsqldb:file:" + data.resolve("db/JFSDB"), "sa", "")) {
            connection.setReadOnly(true);
            try (var statement = connection.createStatement();
                 var count = statement.executeQuery("SELECT COUNT(*) FROM company")) {
                assertThat(count.next()).isTrue();
                assertThat(count.getLong(1)).isGreaterThanOrEqualTo(1);
            }
            try (var statement = connection.createStatement();
                 var legacyTables = statement.executeQuery("SELECT COUNT(*) FROM "
                         + "INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME LIKE 'TBL_%'")) {
                assertThat(legacyTables.next()).isTrue();
                assertThat(legacyTables.getLong(1)).isZero();
            }
            try (var columns = connection.getMetaData().getColumns(null, null, "%", "%")) {
                while (columns.next()) {
                    assertThat(columns.getInt("DATA_TYPE"))
                            .as(columns.getString("TABLE_NAME"))
                            .isNotEqualTo(java.sql.Types.JAVA_OBJECT);
                }
            }
            try (var statement = connection.createStatement()) { statement.execute("SHUTDOWN"); }
        }
    }

    @Test
    void rejectsUnresolvedRecoveryState(@TempDir Path tempDir) throws Exception {
        Path data = tempDir.resolve("data");
        extractFixture(data.resolve("db"));
        HsqlEngineMigrationService.migrateIfRequired(data);
        Files.createDirectories(data.resolve(".bokfri-normalized-staging-old"));

        NormalizedMigrationService service =
                new NormalizedMigrationService(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.migrate(data))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unresolved normalized database migration state");
    }

    private static void extractFixture(Path destination) throws IOException {
        Files.createDirectories(destination);
        try (InputStream input = NormalizedMigrationServiceTest.class.getResourceAsStream(FIXTURE)) {
            if (input == null) throw new IOException("Missing fixture " + FIXTURE);
            try (ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) continue;
                    Path target = destination.resolve(entry.getName()).normalize();
                    if (!target.getParent().equals(destination)) {
                        throw new IOException("Fixture entry escapes destination");
                    }
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
