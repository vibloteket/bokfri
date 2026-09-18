package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationRecoveryInspectorTest {

    @Test
    void reportsCleanDirectory(@TempDir Path data) throws Exception {
        assertThat(MigrationRecoveryInspector.inspect(data).kind())
                .isEqualTo(MigrationRecoveryInspector.RecoveryKind.CLEAN);
        MigrationRecoveryInspector.requireClean(data);
    }

    @Test
    void reportsActiveDatabaseWithSortedLeftovers(@TempDir Path data) throws Exception {
        Files.createDirectories(data.resolve("db"));
        Files.writeString(data.resolve("db/JFSDB.properties"), "version=2.5.0");
        Files.createDirectories(data.resolve(".bokfri-normalized-staging-z"));
        Files.createDirectories(data.resolve(".bokfri-normalized-staging-a"));
        Files.createDirectories(data.resolve("backups/bokfri-normalized-source-1"));

        MigrationRecoveryInspector.RecoveryState state =
                MigrationRecoveryInspector.inspect(data);
        assertThat(state.kind()).isEqualTo(
                MigrationRecoveryInspector.RecoveryKind.ACTIVE_DATABASE_WITH_LEFTOVERS);
        assertThat(state.stagingDirectories()).extracting(path -> path.getFileName().toString())
                .containsExactly(".bokfri-normalized-staging-a", ".bokfri-normalized-staging-z");
        assertThatThrownBy(() -> MigrationRecoveryInspector.requireClean(data))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("Unresolved normalized database migration state");
    }

    @Test
    void distinguishesRecoverableSourceFromStagingOnlyWhenActiveDatabaseIsMissing(
            @TempDir Path data) throws Exception {
        Files.createDirectories(data.resolve(".bokfri-normalized-staging-1"));
        assertThat(MigrationRecoveryInspector.inspect(data).kind()).isEqualTo(
                MigrationRecoveryInspector.RecoveryKind.ACTIVE_DATABASE_MISSING_STAGING_ONLY);

        Files.createDirectories(data.resolve("backups/bokfri-normalized-source-1"));
        assertThat(MigrationRecoveryInspector.inspect(data).kind()).isEqualTo(
                MigrationRecoveryInspector.RecoveryKind.ACTIVE_DATABASE_MISSING_SOURCE_RETAINED);
    }

    @Test
    void ignoresUnownedTemporaryDirectories(@TempDir Path data) throws Exception {
        Files.createDirectories(data.resolve(".hsqldb-2.5-staging-old"));
        Files.createDirectories(data.resolve(".bokfri-restore-old"));
        assertThat(MigrationRecoveryInspector.inspect(data).kind())
                .isEqualTo(MigrationRecoveryInspector.RecoveryKind.CLEAN);
    }
}
