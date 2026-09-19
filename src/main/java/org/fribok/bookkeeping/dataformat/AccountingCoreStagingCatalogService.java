package org.fribok.bookkeeping.dataformat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Objects;

/** Creates and durably verifies a normalized accounting-core staging catalog without activation. */
public final class AccountingCoreStagingCatalogService {
    private static final DateTimeFormatter DIRECTORY_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private final Clock clock;

    public AccountingCoreStagingCatalogService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Converts an already-open legacy source into a new file-backed staging catalog.
     *
     * <p>The source connection is never committed, rolled back, shut down, or closed here. The
     * returned staging directory remains separate from {@code data/db}; no activation is attempted.
     */
    public StagingCatalogResult create(Path dataDirectory, Connection legacy)
            throws IOException, SQLException {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(legacy, "legacy");
        Path data = dataDirectory.toAbsolutePath().normalize();
        MigrationRecoveryInspector.requireClean(data);
        Files.createDirectories(data);
        String suffix = DIRECTORY_TIME.format(clock.instant());
        Path staging = uniqueStagingDirectory(data, suffix);
        Path databaseDirectory = staging.resolve("db");
        Files.createDirectories(databaseDirectory);
        Path database = databaseDirectory.resolve("JFSDB");
        String url = "jdbc:hsqldb:file:" + database;
        boolean success = false;
        try {
            try {
                Class.forName("org.hsqldb.jdbcDriver");
            } catch (ClassNotFoundException exception) {
                throw new IOException("HSQLDB driver is unavailable", exception);
            }
            AccountingCoreStagingConverter.ConversionResult conversion;
            try (Connection normalized = DriverManager.getConnection(url, "sa", "")) {
                normalized.setAutoCommit(false);
                new SchemaMigrationRunner(clock).migrate(
                        normalized, NormalizedSchemaMigrations.load());
                conversion = new AccountingCoreStagingConverter().convert(legacy, normalized);
                shutdown(normalized, "SHUTDOWN SCRIPT");
            }

            SemanticFingerprint reopened;
            try (Connection verification = DriverManager.getConnection(url, "sa", "")) {
                verification.setReadOnly(true);
                reopened = new AccountingCoreStagingConverter()
                        .fingerprintNormalized(verification);
                shutdown(verification, "SHUTDOWN");
            }
            var differences = conversion.destinationFingerprint().differences(reopened);
            if (!differences.isEmpty()) {
                throw new IOException("Durable staging fingerprint mismatch after reopen: "
                        + String.join("; ", differences));
            }
            requireCatalogFiles(database);
            success = true;
            return new StagingCatalogResult(staging, database,
                    conversion, reopened, clock.instant());
        } finally {
            if (!success) {
                deleteTree(staging);
            }
        }
    }

    private static Path uniqueStagingDirectory(Path data, String suffix) throws IOException {
        Path candidate = data.resolve(MigrationRecoveryInspector.NORMALIZED_STAGING_PREFIX + suffix);
        for (int attempt = 0; Files.exists(candidate); attempt++) {
            candidate = data.resolve(MigrationRecoveryInspector.NORMALIZED_STAGING_PREFIX
                    + suffix + "-" + (attempt + 1));
        }
        return candidate;
    }

    private static void shutdown(Connection connection, String command) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(command);
        }
    }

    private static void requireCatalogFiles(Path database) throws IOException {
        Path properties = Path.of(database + ".properties");
        Path script = Path.of(database + ".script");
        if (!Files.isRegularFile(properties) || !Files.isRegularFile(script)) {
            throw new IOException("Normalized staging catalog is incomplete: " + database);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public record StagingCatalogResult(Path stagingDirectory, Path database,
                                       AccountingCoreStagingConverter.ConversionResult conversion,
                                       SemanticFingerprint durableFingerprint,
                                       Instant completedAt) {}
}
