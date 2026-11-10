package org.fribok.bookkeeping.dataformat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.fribok.bookkeeping.dataformat.NormalizedCatalogRecoveryService.catalogHashes;
import static org.fribok.bookkeeping.dataformat.NormalizedCatalogRecoveryService.copyCatalog;
import static org.fribok.bookkeeping.dataformat.NormalizedCatalogRecoveryService.extractCatalog;
import static org.fribok.bookkeeping.dataformat.NormalizedCatalogRecoveryService.open;
import static org.fribok.bookkeeping.dataformat.NormalizedCatalogRecoveryService.requireClosedCatalog;
import static org.fribok.bookkeeping.dataformat.NormalizedCatalogRecoveryService.sha256;
import static org.fribok.bookkeeping.dataformat.NormalizedCatalogRecoveryService.shutdown;

/** Explicit offline V19-to-V20 recovery of missing periodic snapshots, never a legacy database replacement. */
public final class NormalizedPeriodicRecoveryService {
    private final Clock clock;

    /** Uses the supplied clock for migration and rollback artifacts. */
    public NormalizedPeriodicRecoveryService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Recovers only nested snapshots for periodic records still present in a clean V19 normalized database.
     * The operator must verify backup provenance and exclusively own the closed data directory. A SHA match
     * proves integrity, not provenance. Every selected legacy identity/company/header/ordered row/instance
     * and effective added flag must exactly match the existing projection before any V20 data is written.
     * Deleted records are not restored, changed/missing records are rejected, and all pre-existing SQL rows
     * are verified unchanged. Failure before activation retains staging; there is no automatic retry/repair.
     * @param dataDirectory closed current normalized data directory
     * @param trustedBackup operator-trusted legacy rollback ZIP
     * @param expectedSha256 expected ZIP SHA-256
     * @return verified recovery and physical rollback artifacts
     */
    public RecoveryResult recover(Path dataDirectory, Path trustedBackup, String expectedSha256)
            throws IOException, SQLException {
        Path data = dataDirectory.toAbsolutePath().normalize();
        Path backup = trustedBackup.toAbsolutePath().normalize();
        if (expectedSha256 == null || !expectedSha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IOException("A 64-character SHA-256 of the trusted legacy backup is required");
        }
        var recovery = MigrationRecoveryInspector.inspect(data);
        if (!recovery.stagingDirectories().isEmpty() || !recovery.activeDatabaseExists()) {
            throw new IOException("Unresolved migration state or missing active database: " + recovery.kind());
        }
        requireClosedCatalog(data.resolve("db"));
        Map<String, String> activeFiles = catalogHashes(data.resolve("db"));
        if (!sha256(backup).equalsIgnoreCase(expectedSha256)) throw new IOException("Legacy backup SHA-256 mismatch");
        Path staging = Files.createTempDirectory(data, MigrationRecoveryInspector.NORMALIZED_STAGING_PREFIX);
        Path frozenBackup = staging.resolve("trusted-legacy.zip");
        Files.copy(backup, frozenBackup);
        if (!sha256(frozenBackup).equalsIgnoreCase(expectedSha256)) {
            throw new IOException("Legacy backup changed while copying");
        }
        Path stagedDb = staging.resolve("db");
        copyCatalog(data.resolve("db"), stagedDb);
        if (!activeFiles.equals(catalogHashes(stagedDb))) {
            throw new IOException("Active catalogue changed while copying; recovery was not activated");
        }
        Path source = staging.resolve("legacy");
        Files.createDirectory(source);
        Path sourceDb = source.resolve("db");
        extractCatalog(frozenBackup, sourceDb);
        requireClosedCatalog(sourceDb);

        Map<String, String> original;
        SemanticFingerprint expected;
        long periodics;
        long snapshots;
        try (Connection target = open(stagedDb, false)) {
            target.setAutoCommit(false);
            try {
                requireV19(target);
                original = existingTables(target);
                Set<Integer> selected = new HashSet<>();
                try (var statement = target.createStatement(); var rows = statement.executeQuery(
                        "SELECT legacy_id FROM periodic_invoice")) {
                    while (rows.next()) selected.add(rows.getInt(1));
                }
                SemanticFingerprint projection = PeriodicInvoiceStagingConverter.projectionNormalized(target);
                try {
                    HsqlEngineMigrationService.migrateIfRequired(source);
                } catch (ClassNotFoundException exception) {
                    throw new IOException("HSQLDB driver is unavailable", exception);
                }
                List<PeriodicInvoiceSnapshots.Entry> nested;
                // Disposable writable source handles archives with CACHED tables and no .data file.
                // Only selected periodic rows are deserialized, never the active normalized database.
                try (Connection legacy = open(sourceDb, false)) {
                    int format = DataFormatManager.detect(legacy);
                    if (format != DataFormatManager.LEGACY_DATA_FORMAT_VERSION
                            && format != DataFormatManager.CURRENT_DATA_FORMAT_VERSION) {
                        throw new IOException("The trusted backup must be supported legacy storage, not format "
                                + format);
                    }
                    var differences = projection.differences(
                            PeriodicInvoiceStagingConverter.projectionLegacy(legacy, selected));
                    if (!differences.isEmpty()) throw new IOException(
                            "Periodic projection differs from trusted source; missing, changed or ambiguous records: "
                                    + differences);
                    nested = PeriodicInvoiceSnapshots.readLegacy(legacy, selected);
                }
                // Exactly V19 -> V20, not permission to install unrelated future migrations.
                new SchemaMigrationRunner(clock).migrate(target, NormalizedSchemaMigrations.load().subList(0, 20));
                PeriodicInvoiceSnapshots.write(target, nested);
                expected = PeriodicInvoiceSnapshots.combine(projection, PeriodicInvoiceSnapshots.fingerprint(nested));
                requireSameRows(original, existingTables(target));
                requirePeriodic(target, expected);
                periodics = selected.size();
                snapshots = nested.stream().filter(entry -> entry.table() == PeriodicInvoiceSnapshots.Table.SNAPSHOT)
                        .count();
                target.commit();
                shutdown(target);
            } catch (IOException | SQLException | RuntimeException | Error failure) {
                try {
                    target.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
        verify(stagedDb, original, expected);
        requireClosedCatalog(data.resolve("db"));
        if (!activeFiles.equals(catalogHashes(data.resolve("db")))) {
            throw new IOException("Active catalogue changed during recovery; recovery was not activated");
        }
        var activation = new NormalizedActivationService(clock).activate(data, staging);
        verify(data.resolve("db"), original, expected);
        return new RecoveryResult(activation, expected, periodics, snapshots, expectedSha256.toLowerCase(Locale.ROOT));
    }

    private static void requireV19(Connection connection) throws SQLException, IOException {
        if (DataFormatManager.detect(connection) != DataFormatManager.NORMALIZED_DATA_FORMAT_VERSION) {
            throw new IOException("Periodic recovery requires normalized format 3 at schema V19");
        }
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT COUNT(*), MAX(version) FROM bokfri_schema_history")) {
            rows.next();
            if (rows.getInt(1) != 19 || rows.getInt(2) != 19) {
                throw new IOException("Periodic recovery requires exactly schema V19; no V20 overwrite is allowed");
            }
        }
        if (PeriodicInvoiceSnapshots.hasSchema(connection)) {
            throw new IOException("Existing periodic snapshot tables require manual recovery, not overwrite");
        }
    }

    private static Map<String, String> existingTables(Connection connection) throws SQLException {
        Set<String> excluded = java.util.Arrays.stream(PeriodicInvoiceSnapshots.Table.values())
                .map(table -> table.name.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        return NormalizedCatalogRecoveryService.fingerprintExistingTables(connection, excluded, 19);
    }

    private static void requireSameRows(Map<String, String> expected, Map<String, String> actual) throws IOException {
        if (!expected.equals(actual)) throw new IOException("Periodic recovery changed pre-existing normalized tables");
    }

    private static void requirePeriodic(Connection connection, SemanticFingerprint expected)
            throws SQLException, IOException {
        var differences = expected.differences(new PeriodicInvoiceStagingConverter().fingerprintNormalized(connection));
        if (!differences.isEmpty()) throw new IOException("Recovered periodic fingerprint mismatch: " + differences);
    }

    private static void verify(Path db, Map<String, String> original, SemanticFingerprint expected)
            throws SQLException, IOException {
        try (Connection connection = open(db, true)) {
            requireSameRows(original, existingTables(connection));
            requirePeriodic(connection, expected);
        }
    }

    /** Verified result; the retained source and archive contain the original current normalized database. */
    public record RecoveryResult(NormalizedActivationService.ActivationResult activation,
                                 SemanticFingerprint periodicFingerprint, long periodics, long snapshots,
                                 String legacyBackupSha256) {}
}
