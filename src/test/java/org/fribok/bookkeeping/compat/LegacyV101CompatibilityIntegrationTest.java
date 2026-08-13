package org.fribok.bookkeeping.compat;

import org.fribok.bookkeeping.cli.BokfriRuntime;
import org.fribok.bookkeeping.dataformat.DataFormatManager;
import org.fribok.bookkeeping.dataformat.DataMigrationRequiredException;
import org.fribok.bookkeeping.service.backup.BackupService;
import org.fribok.bookkeeping.service.backup.BackupVerification;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** End-to-end compatibility checks against artifacts produced by released Bokfri v1.0.1. */
@Tag("integration")
class LegacyV101CompatibilityIntegrationTest {
    private static final String RESOURCE_ROOT = "/compat/v1.0.1/";

    @Test
    void requiresApprovalThenMigratesReleasedV101Database(@TempDir Path tempDir) throws Exception {
        Path dataDirectory = tempDir.resolve("direct");
        extract("database-v1.0.1.zip", dataDirectory.resolve("db"));

        assertThatThrownBy(() -> BokfriRuntime.open(dataDirectory))
                .isInstanceOf(DataMigrationRequiredException.class);
        assertLegacyFormat(dataDirectory);

        try (BokfriRuntime runtime = BokfriRuntime.open(dataDirectory, true)) {
            assertThat(runtime.database().getCompanies())
                    .extracting(company -> company.getName())
                    .containsExactly("Exempelföretag");
        }
        assertCurrentFormat(dataDirectory);
        assertThat(Files.list(dataDirectory.resolve("backups")))
                .singleElement().satisfies(path -> assertThat(path).exists());
    }

    @Test
    void verifiesRestoresAndOpensReleasedV101Backup(@TempDir Path tempDir) throws Exception {
        Path backup = copyResource("backup-v1.0.1.zip", tempDir.resolve("backup-v1.0.1.zip"));
        BackupService service = new BackupService(tempDir.resolve("source"));

        BackupVerification verification = service.verify(backup);

        assertThat(verification.legacy()).isTrue();
        assertThat(verification.dataFormatVersion()).isEqualTo(1);
        assertThat(verification.applicationVersion()).isNull();
        assertThat(verification.entries()).contains("backup.info", "JFSDB.properties", "JFSDB.script");

        Path restored = tempDir.resolve("restored");
        service.restore(backup, restored, false, true);
        assertThatThrownBy(() -> BokfriRuntime.open(restored))
                .isInstanceOf(DataMigrationRequiredException.class);
        try (BokfriRuntime runtime = BokfriRuntime.open(restored, true)) {
            assertThat(runtime.database().getCompanies())
                    .extracting(company -> company.getName())
                    .containsExactly("Exempelföretag");
        }
        assertCurrentFormat(restored);
    }

    private void assertLegacyFormat(Path dataDirectory) throws Exception {
        Class.forName("org.hsqldb.jdbcDriver");
        String database = dataDirectory.resolve("db/JFSDB").toAbsolutePath().toString();
        try (var connection = java.sql.DriverManager.getConnection(
                "jdbc:hsqldb:file:" + database, "sa", "")) {
            assertThat(DataFormatManager.detect(connection))
                    .isEqualTo(DataFormatManager.LEGACY_DATA_FORMAT_VERSION);
            connection.createStatement().execute("SHUTDOWN");
        }
    }

    private void assertCurrentFormat(Path dataDirectory) throws Exception {
        Class.forName("org.hsqldb.jdbcDriver");
        String database = dataDirectory.resolve("db/JFSDB").toAbsolutePath().toString();
        try (var connection = java.sql.DriverManager.getConnection(
                "jdbc:hsqldb:file:" + database, "sa", "")) {
            assertThat(DataFormatManager.detect(connection))
                    .isEqualTo(DataFormatManager.CURRENT_DATA_FORMAT_VERSION);
            connection.createStatement().execute("SHUTDOWN");
        }
    }

    private void extract(String resource, Path destination) throws IOException {
        Files.createDirectories(destination);
        try (InputStream input = resource(resource); ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) {
                    continue;
                }
                Path target = destination.resolve(entry.getName()).normalize();
                if (!target.getParent().equals(destination)) {
                    throw new IOException("Fixture entry escapes destination: " + entry.getName());
                }
                Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private Path copyResource(String resource, Path target) throws IOException {
        try (InputStream input = resource(resource)) {
            Files.copy(input, target);
        }
        return target;
    }

    private InputStream resource(String name) throws IOException {
        InputStream input = getClass().getResourceAsStream(RESOURCE_ROOT + name);
        if (input == null) {
            throw new IOException("Missing compatibility fixture: " + name);
        }
        return input;
    }
}
