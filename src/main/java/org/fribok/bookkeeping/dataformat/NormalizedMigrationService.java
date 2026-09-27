package org.fribok.bookkeeping.dataformat;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;

/**
 * The complete opt-in normalized migration pipeline.
 *
 * <p>Order: engine upgrade check, recovery-state rejection, staging creation with all converters
 * and fingerprint verification, activation with rollback artifacts, then data-format 3 recording.
 * The caller must hold exclusive application ownership of the data directory; this service never
 * runs during normal startup yet.
 */
public final class NormalizedMigrationService {
    private final Clock clock;

    public NormalizedMigrationService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public MigrationResult migrate(Path dataDirectory) throws IOException, SQLException {
        Path data = dataDirectory.toAbsolutePath().normalize();
        try {
            Class.forName("org.hsqldb.jdbcDriver");
            HsqlEngineMigrationService.migrateIfRequired(data);
        } catch (ClassNotFoundException exception) {
            throw new IOException("HSQLDB driver is unavailable", exception);
        }
        MigrationRecoveryInspector.requireClean(data);

        Path database = data.resolve("db/JFSDB");
        AccountingCoreStagingCatalogService.StagingCatalogResult staged;
        try (Connection legacy = DriverManager.getConnection(
                "jdbc:hsqldb:file:" + database, "sa", "")) {
            legacy.setAutoCommit(false);
            int format = DataFormatManager.detect(legacy);
            if (format > DataFormatManager.CURRENT_DATA_FORMAT_VERSION) {
                throw new IOException("Database format " + format + " is newer than supported");
            }
            staged = new AccountingCoreStagingCatalogService(clock).create(data, legacy);
            legacy.rollback();
            // HSQLDB caches file catalogs per path in the JVM; shut down the source so the
            // activation swap is followed by a clean reopen of the normalized catalog.
            try (var statement = legacy.createStatement()) { statement.execute("SHUTDOWN"); }
        }

        NormalizedActivationService.ActivationResult activated =
                new NormalizedActivationService(clock).activate(data, staged.stagingDirectory());

        try (Connection normalized = DriverManager.getConnection(
                "jdbc:hsqldb:file:" + database, "sa", "")) {
            normalized.setAutoCommit(false);
            SemanticFingerprint reopened =
                    new AccountingCoreStagingConverter().fingerprintNormalized(normalized);
            var differences = staged.conversion().destinationFingerprint().differences(reopened);
            if (!differences.isEmpty()) {
                throw new IOException("Activated catalog fingerprint mismatch: "
                        + String.join("; ", differences));
            }
            DataFormatManager.recordVersion(normalized,
                    DataFormatManager.NORMALIZED_DATA_FORMAT_VERSION);
            try (var statement = normalized.createStatement()) { statement.execute("SHUTDOWN"); }
        }
        return new MigrationResult(staged, activated, clock.instant());
    }

    public record MigrationResult(AccountingCoreStagingCatalogService.StagingCatalogResult staging,
                                  NormalizedActivationService.ActivationResult activation,
                                  java.time.Instant completedAt) {}
}
