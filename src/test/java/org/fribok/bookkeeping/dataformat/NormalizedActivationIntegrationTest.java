package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end: v1.0.1 fixture → staging → activation → reopened normalized catalog is functional. */
@Tag("integration")
class NormalizedActivationIntegrationTest {
    private static final String FIXTURE = "/compat/v1.0.1/database-v1.0.1.zip";

    @Test
    void activatesARealConvertedFixtureAndReopensIt(@TempDir Path tempDir) throws Exception {
        Path data = tempDir.resolve("data");
        extractFixture(data.resolve("db"));
        HsqlEngineMigrationService.migrateIfRequired(data);

        Class.forName("org.hsqldb.jdbcDriver");
        AccountingCoreStagingCatalogService.StagingCatalogResult staged;
        try (Connection legacy = DriverManager.getConnection(
                "jdbc:hsqldb:file:" + data.resolve("db/JFSDB"), "sa", "")) {
            legacy.setAutoCommit(false);
            staged = new AccountingCoreStagingCatalogService(
                    Clock.fixed(Instant.parse("2026-09-26T15:10:00Z"), ZoneOffset.UTC))
                    .create(data, legacy);
            legacy.rollback();
            // HSQLDB caches file catalogs per path in the JVM; shut down the source catalog so
            // reopening the same path after activation reads the normalized files from disk.
            try (var statement = legacy.createStatement()) { statement.execute("SHUTDOWN"); }
        }

        NormalizedActivationService.ActivationResult activated =
                new NormalizedActivationService(
                        Clock.fixed(Instant.parse("2026-09-26T15:11:00Z"), ZoneOffset.UTC))
                        .activate(data, staged.stagingDirectory());

        try (Connection normalized = DriverManager.getConnection(
                "jdbc:hsqldb:file:" + data.resolve("db/JFSDB"), "sa", "")) {
            normalized.setReadOnly(true);
            try (var statement = normalized.createStatement();
                 var tables = statement.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                         + "WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME='COMPANY'")) {
                assertThat(tables.next()).isTrue();
                assertThat(tables.getLong(1)).isEqualTo(1);
            }
            try (var statement = normalized.createStatement();
                 var count = statement.executeQuery("SELECT COUNT(*) FROM company")) {
                assertThat(count.next()).isTrue();
                assertThat(count.getLong(1)).isGreaterThanOrEqualTo(1);
            }
            try (var columns = normalized.getMetaData().getColumns(null, null, "%", "%")) {
                while (columns.next()) {
                    assertThat(columns.getInt("DATA_TYPE"))
                            .as(columns.getString("TABLE_NAME") + "." + columns.getString("COLUMN_NAME"))
                            .isNotEqualTo(java.sql.Types.JAVA_OBJECT);
                }
            }
            try (var statement = normalized.createStatement()) { statement.execute("SHUTDOWN"); }
        }

        assertThat(activated.rollbackArchive()).isRegularFile();
        assertThat(activated.retainedSource().resolve("JFSDB.script")).isRegularFile();
        assertThat(staged.stagingDirectory()).doesNotExist();
    }

    private static void extractFixture(Path destination) throws IOException {
        Files.createDirectories(destination);
        try (InputStream input = NormalizedActivationIntegrationTest.class
                .getResourceAsStream(FIXTURE)) {
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
