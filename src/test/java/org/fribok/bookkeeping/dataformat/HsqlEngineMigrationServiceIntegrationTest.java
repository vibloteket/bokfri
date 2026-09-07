package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Integration tests for staged HSQLDB 1.8 to 2.5 engine migration. */
@Tag("integration")
class HsqlEngineMigrationServiceIntegrationTest {
    private static final String FIXTURE = "/compat/v1.0.1/database-v1.0.1.zip";

    @Test
    void upgradesAStagedCopyAndRetainsTheOriginal(@TempDir Path tempDir) throws Exception {
        Path data = tempDir.resolve("data");
        extractFixture(data.resolve("db"));

        HsqlEngineMigrationService.EngineMigrationResult result =
                HsqlEngineMigrationService.migrateIfRequired(data);

        assertThat(result.migrated()).isTrue();
        assertThat(result.sourceVersion()).startsWith("1.");
        assertThat(result.rollbackArchive()).isRegularFile();
        assertThat(readVersion(data.resolve("db/JFSDB.properties"))).startsWith("2.5.");
        try (ZipFile archive = new ZipFile(result.rollbackArchive().toFile())) {
            assertThat(archive.getEntry("JFSDB.properties")).isNotNull();
            assertThat(archive.getEntry("JFSDB.script")).isNotNull();
        }
        try (var retained = Files.list(data.resolve("backups"))) {
            assertThat(retained.filter(Files::isDirectory)
                    .anyMatch(path -> path.getFileName().toString()
                            .startsWith("hsqldb-1.8-source-"))).isTrue();
        }
    }

    @Test
    void leavesAnAlreadyUpgradedCatalogUntouched(@TempDir Path tempDir) throws Exception {
        Path data = tempDir.resolve("data");
        extractFixture(data.resolve("db"));
        HsqlEngineMigrationService.migrateIfRequired(data);
        byte[] properties = Files.readAllBytes(data.resolve("db/JFSDB.properties"));

        HsqlEngineMigrationService.EngineMigrationResult second =
                HsqlEngineMigrationService.migrateIfRequired(data);

        assertThat(second.migrated()).isFalse();
        assertThat(second.sourceVersion()).startsWith("2.5.");
        assertThat(Files.readAllBytes(data.resolve("db/JFSDB.properties")))
                .containsExactly(properties);
    }

    private static String readVersion(Path file) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        return properties.getProperty("version");
    }

    private static void extractFixture(Path destination) throws IOException {
        Files.createDirectories(destination);
        try (InputStream input = HsqlEngineMigrationServiceIntegrationTest.class
                .getResourceAsStream(FIXTURE)) {
            if (input == null) {
                throw new IOException("Missing compatibility fixture " + FIXTURE);
            }
            try (ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) {
                        continue;
                    }
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
