package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NormalizedActivationServiceTest {
    private static final Instant AT = Instant.parse("2026-09-26T15:00:00Z");

    @Test
    void activatesStagingWithBackupAndRetainedSource(@TempDir Path data) throws Exception {
        Files.createDirectories(data.resolve("db"));
        writeCatalog(data.resolve("db"), "legacy-data");
        Path staging = data.resolve(".bokfri-normalized-staging-x");
        Files.createDirectories(staging.resolve("db"));
        writeCatalog(staging.resolve("db"), "normalized-data");

        NormalizedActivationService.ActivationResult result =
                new NormalizedActivationService(Clock.fixed(AT, ZoneOffset.UTC)).activate(data, staging);

        assertThat(Files.readString(data.resolve("db/JFSDB.script"))).isEqualTo("normalized-data");
        assertThat(Files.readString(result.retainedSource().resolve("JFSDB.script")))
                .isEqualTo("legacy-data");
        try (ZipFile zip = new ZipFile(result.rollbackArchive().toFile())) {
            assertThat(zip.getEntry("JFSDB.properties")).isNotNull();
            assertThat(zip.getEntry("JFSDB.script")).isNotNull();
        }
        assertThat(staging).doesNotExist();
        assertThat(result.activatedAt()).isEqualTo(AT);
        assertThat(MigrationRecoveryInspector.inspect(data).kind())
                .isEqualTo(MigrationRecoveryInspector.RecoveryKind.ACTIVE_DATABASE_WITH_LEFTOVERS);
    }

    @Test
    void rejectsMissingCatalogsAndForeignStaging(@TempDir Path data) throws Exception {
        NormalizedActivationService service =
                new NormalizedActivationService(Clock.fixed(AT, ZoneOffset.UTC));
        Path staging = data.resolve(".bokfri-normalized-staging-x");
        Files.createDirectories(staging.resolve("db"));
        writeCatalog(staging.resolve("db"), "normalized-data");

        assertThatThrownBy(() -> service.activate(data, staging))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("Active");

        Files.createDirectories(data.resolve("db"));
        writeCatalog(data.resolve("db"), "legacy-data");
        Path outside = Files.createTempDirectory("outside");
        Files.createDirectories(outside.resolve("db"));
        writeCatalog(outside.resolve("db"), "normalized-data");
        assertThatThrownBy(() -> service.activate(data, outside))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("inside");
        assertThat(Files.readString(data.resolve("db/JFSDB.script"))).isEqualTo("legacy-data");
    }

    private static void writeCatalog(Path dir, String marker) throws Exception {
        Files.writeString(dir.resolve("JFSDB.properties"), "version=2.5.0");
        Files.writeString(dir.resolve("JFSDB.script"), marker);
        Files.writeString(dir.resolve("JFSDB.data"), marker + "-data");
    }
}
