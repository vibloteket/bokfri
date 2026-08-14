package org.fribok.bookkeeping.service.backup;

import org.fribok.bookkeeping.dataformat.DataFormatManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests backup manifests and current backup verification. */
class BackupServiceTest {

    @Test
    void newBackupContainsVersionedJsonManifest(@TempDir Path tempDir) throws Exception {
        Path dataDirectory = tempDir.resolve("data");
        Path databaseDirectory = dataDirectory.resolve("db");
        Files.createDirectories(databaseDirectory);
        Files.writeString(databaseDirectory.resolve("JFSDB.properties"), "version=1.8.0\n");
        Files.writeString(databaseDirectory.resolve("JFSDB.script"), "-- test\n");
        Path backup = tempDir.resolve("current.zip");

        BackupService service = new BackupService(dataDirectory);
        service.create(backup, false);
        BackupVerification verification = service.verify(backup);

        assertThat(verification.legacy()).isFalse();
        assertThat(verification.backupFormatVersion()).isEqualTo(1);
        assertThat(verification.dataFormatVersion())
                .isEqualTo(DataFormatManager.CURRENT_DATA_FORMAT_VERSION);
        assertThat(verification.applicationVersion()).isNotBlank();
        assertThat(verification.createdAt()).isBeforeOrEqualTo(LocalDateTime.now());
        assertThat(verification.entries()).contains("manifest.json", "backup.info");
        try (ZipFile zip = new ZipFile(backup.toFile())) {
            String manifest = new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes());
            assertThat(manifest).contains("\"format\" : \"bokfri-backup\"")
                    .contains("\"dataFormatVersion\" : 2")
                    .contains("\"applicationVersion\"");
        }
    }

    @Test
    void overwriteRestoreReplacesWholeDatabaseDirectory(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("source");
        Path sourceDatabase = source.resolve("db");
        Files.createDirectories(sourceDatabase);
        Files.writeString(sourceDatabase.resolve("JFSDB.properties"), "new-properties");
        Files.writeString(sourceDatabase.resolve("JFSDB.script"), "new-script");
        Path backup = tempDir.resolve("backup.zip");
        BackupService service = new BackupService(source);
        service.create(backup, false);

        Path target = tempDir.resolve("target");
        Path targetDatabase = target.resolve("db");
        Files.createDirectories(targetDatabase);
        Files.writeString(targetDatabase.resolve("JFSDB.properties"), "old-properties");
        Files.writeString(targetDatabase.resolve("JFSDB.script"), "old-script");
        Files.writeString(targetDatabase.resolve("JFSDB.data"), "stale-data");
        Files.writeString(targetDatabase.resolve("unrelated.txt"), "stale-file");

        BackupRestorePlan plan = service.restore(backup, target, true, true);

        assertThat(plan.replacesExistingDatabase()).isTrue();
        assertThat(targetDatabase.resolve("JFSDB.properties")).hasContent("new-properties");
        assertThat(targetDatabase.resolve("JFSDB.script")).hasContent("new-script");
        assertThat(targetDatabase.resolve("JFSDB.data")).doesNotExist();
        assertThat(targetDatabase.resolve("unrelated.txt")).doesNotExist();
    }

    @Test
    void restoreRejectsExistingDatabaseWhenBackupHasNoMatchingOptionalFiles(
            @TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("source");
        Path sourceDatabase = source.resolve("db");
        Files.createDirectories(sourceDatabase);
        Files.writeString(sourceDatabase.resolve("JFSDB.properties"), "new-properties");
        Files.writeString(sourceDatabase.resolve("JFSDB.script"), "new-script");
        Path backup = tempDir.resolve("backup.zip");
        BackupService service = new BackupService(source);
        service.create(backup, false);

        Path target = tempDir.resolve("target");
        Path targetDatabase = target.resolve("db");
        Files.createDirectories(targetDatabase);
        Files.writeString(targetDatabase.resolve("JFSDB.data"), "existing-data");

        assertThatThrownBy(() -> service.restore(backup, target, false, true))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(targetDatabase.resolve("JFSDB.data")).hasContent("existing-data");
    }

    @Test
    void restoreRejectsBackupFromNewerDataFormat(@TempDir Path tempDir) throws Exception {
        Path backup = tempDir.resolve("future.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(backup))) {
            add(zip, "JFSDB.properties", "version=1.8.0\n".getBytes());
            add(zip, "JFSDB.script", "-- test\n".getBytes());
            Path info = Files.createTempFile(tempDir, "backup-info-", ".bin");
            se.swedsoft.bookkeeping.data.backup.SSBackup metadata =
                    new se.swedsoft.bookkeeping.data.backup.SSBackup(
                            se.swedsoft.bookkeeping.data.backup.util.SSBackupType.FULL);
            metadata.setLocalDateTime(LocalDateTime.now());
            se.swedsoft.bookkeeping.data.backup.SSBackup.storeBackup(info.toFile(), metadata);
            add(zip, "backup.info", Files.readAllBytes(info));
            String manifest = """
                    {
                      "format": "bokfri-backup",
                      "backupFormatVersion": 1,
                      "dataFormatVersion": 999,
                      "applicationVersion": "future",
                      "createdAt": "2026-08-13T17:00:00"
                    }
                    """;
            add(zip, "manifest.json", manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        BackupService service = new BackupService(tempDir.resolve("source"));

        assertThatThrownBy(() -> service.restore(
                backup, tempDir.resolve("restored"), false, true))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("newer than supported");
        assertThat(tempDir.resolve("restored/db/JFSDB.properties")).doesNotExist();
    }

    private void add(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }
}
