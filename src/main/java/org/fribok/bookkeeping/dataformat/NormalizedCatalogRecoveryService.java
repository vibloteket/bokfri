package org.fribok.bookkeeping.dataformat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;
import java.util.zip.ZipFile;

/** Explicit, offline V18 catalogue recovery; the current normalized database is never rebuilt. */
public final class NormalizedCatalogRecoveryService {
    private final Clock clock;

    public NormalizedCatalogRecoveryService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Adds only V19 and templates from an operator-trusted, SHA-256 verified legacy rollback ZIP.
     * The caller must have exclusive ownership of a cleanly shut down data directory. Legacy
     * deserialization happens only in an isolated extracted copy, never in the active database.
     * All pre-existing SQL rows (including migration history) are verified before activation.
     * Pre-activation failures retain staging for inspection; no automatic retry or source selection
     * is attempted. Activation retains the pre-recovery normalized database and a rollback ZIP.
     */
    public RecoveryResult recover(Path dataDirectory, Path trustedBackup, String expectedSha256)
            throws IOException, SQLException {
        Path data = dataDirectory.toAbsolutePath().normalize();
        Path backup = trustedBackup.toAbsolutePath().normalize();
        if (expectedSha256 == null || !expectedSha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IOException("A 64-character SHA-256 of the trusted legacy backup is required");
        }
        MigrationRecoveryInspector.RecoveryState recovery = MigrationRecoveryInspector.inspect(data);
        if (!recovery.stagingDirectories().isEmpty() || !recovery.activeDatabaseExists()) {
            throw new IOException("Unresolved migration state or missing active database: " + recovery.kind());
        }
        requireClosedCatalog(data.resolve("db"));
        Map<String, String> activeFiles = catalogHashes(data.resolve("db"));
        if (!sha256(backup).equalsIgnoreCase(expectedSha256)) {
            throw new IOException("Legacy backup SHA-256 mismatch");
        }
        // Freeze and hash the source again: never deserialize bytes other than those verified.
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
        AccountPlanCatalogStagingConverter.ConversionResult conversion;
        try (Connection target = open(stagedDb, false)) {
            requireV18(target);
            original = fingerprintExistingTables(target);
            // Recovery is specifically V18 -> V19, not permission to apply future migrations.
            new SchemaMigrationRunner(clock).migrate(target, NormalizedSchemaMigrations.load().subList(0, 19));
            try {
                HsqlEngineMigrationService.migrateIfRequired(source);
            } catch (ClassNotFoundException exception) {
                throw new IOException("HSQLDB driver is unavailable", exception);
            }
            // A disposable writable copy also handles SHUTDOWN SCRIPT archives containing
            // CACHED tables: HSQLDB must recreate their .data file while opening the script.
            try (Connection legacy = open(sourceDb, false)) {
                int sourceFormat = DataFormatManager.detect(legacy);
                if (sourceFormat != DataFormatManager.LEGACY_DATA_FORMAT_VERSION
                        && sourceFormat != DataFormatManager.CURRENT_DATA_FORMAT_VERSION) {
                    throw new IOException("The trusted backup must be supported legacy storage, not format "
                            + sourceFormat);
                }
                conversion = new AccountPlanCatalogStagingConverter().convert(legacy, target);
            }
            requireSameRows(original, fingerprintExistingTables(target));
            shutdown(target);
        }
        // Verify disk round-trip before any active file is moved.
        verify(stagedDb, original, conversion.destinationFingerprint());
        requireClosedCatalog(data.resolve("db"));
        if (!activeFiles.equals(catalogHashes(data.resolve("db")))) {
            throw new IOException("Active catalogue changed during recovery; recovery was not activated");
        }
        NormalizedActivationService.ActivationResult activation =
                new NormalizedActivationService(clock).activate(data, staging);
        verify(data.resolve("db"), original, conversion.destinationFingerprint());
        return new RecoveryResult(activation, conversion, expectedSha256.toLowerCase(java.util.Locale.ROOT));
    }

    private static void verify(Path db, Map<String, String> original, SemanticFingerprint catalogue)
            throws SQLException, IOException {
        try (Connection connection = open(db, true)) {
            requireSameRows(original, fingerprintExistingTables(connection));
            var differences = catalogue.differences(
                    new AccountPlanCatalogStagingConverter().fingerprintNormalized(connection));
            if (!differences.isEmpty()) {
                throw new IOException("Recovered catalogue fingerprint mismatch: " + differences);
            }
        }
    }

    private static Connection open(Path db, boolean readOnly) throws SQLException {
        return DriverManager.getConnection("jdbc:hsqldb:file:" + db.resolve("JFSDB")
                + ";ifexists=true" + (readOnly ? ";readonly=true;shutdown=true" : ";shutdown=true"), "sa", "");
    }

    private static void shutdown(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }

    private static void requireV18(Connection connection) throws SQLException, IOException {
        if (DataFormatManager.detect(connection) != DataFormatManager.NORMALIZED_DATA_FORMAT_VERSION) {
            throw new IOException("Catalogue recovery requires normalized format 3 at schema V18");
        }
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*), MAX(version) FROM BOKFRI_SCHEMA_HISTORY")) {
            rows.next();
            if (rows.getInt(1) != 18 || rows.getInt(2) != 18) {
                throw new IOException("Catalogue recovery requires exactly schema V18; no V19 overwrite is allowed");
            }
        }
        try (var tables = connection.getMetaData().getTables(null, "PUBLIC", "ACCOUNT_PLAN_CATALOG%", null)) {
            if (tables.next()) {
                throw new IOException("Partial or existing catalogue requires manual recovery");
            }
        }
    }

    /** Hashes every existing PUBLIC table, not just the original migration's accounting subset. */
    static Map<String, String> fingerprintExistingTables(Connection connection) throws SQLException {
        Map<String, String> result = new TreeMap<>();
        List<String> tables = new ArrayList<>();
        try (var rows = connection.getMetaData().getTables(null, "PUBLIC", "%", new String[]{"TABLE"})) {
            while (rows.next()) {
                String name = rows.getString("TABLE_NAME");
                if (!name.equals("ACCOUNT_PLAN_CATALOG") && !name.equals("ACCOUNT_PLAN_CATALOG_ACCOUNT")) {
                    tables.add(name);
                }
            }
        }
        for (String table : tables) {
            String query = "SELECT * FROM \"" + table.replace("\"", "\"\"") + "\""
                    + (table.equals("BOKFRI_SCHEMA_HISTORY") ? " WHERE version <= 18" : "");
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
                var metadata = rows.getMetaData();
                CanonicalFingerprintWriter hash = new CanonicalFingerprintWriter();
                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                    int type = metadata.getColumnType(column);
                    if (type == Types.OTHER || type == Types.JAVA_OBJECT || type == Types.BLOB
                            || type == Types.CLOB || type == Types.ARRAY) {
                        throw new SchemaMigrationException("Cannot safely fingerprint SQL type in " + table);
                    }
                    hash.writeString(metadata.getColumnName(column));
                    hash.writeInteger(type);
                    hash.writeString(metadata.getColumnTypeName(column));
                    hash.writeInteger(metadata.getPrecision(column));
                    hash.writeInteger(metadata.getScale(column));
                    hash.writeInteger(metadata.isNullable(column));
                }
                List<String> records = new ArrayList<>();
                while (rows.next()) {
                    CanonicalFingerprintWriter record = new CanonicalFingerprintWriter();
                    for (int column = 1; column <= metadata.getColumnCount(); column++) {
                        int type = metadata.getColumnType(column);
                        if (type == Types.BINARY || type == Types.VARBINARY || type == Types.LONGVARBINARY) {
                            byte[] bytes = rows.getBytes(column);
                            record.writeString(bytes == null ? null : HexFormat.of().formatHex(bytes));
                        } else {
                            record.writeString(rows.getString(column));
                        }
                    }
                    records.add(record.finish());
                }
                records.sort(Comparator.naturalOrder());
                for (String record : records) {
                    hash.writeString(record);
                }
                result.put(table, hash.finish());
            }
        }
        return Map.copyOf(result);
    }

    private static void requireSameRows(Map<String, String> expected, Map<String, String> actual)
            throws IOException {
        if (!expected.equals(actual)) {
            throw new IOException("Catalogue recovery changed pre-existing normalized tables");
        }
    }

    private static void requireClosedCatalog(Path db) throws IOException {
        if (!Files.isRegularFile(db.resolve("JFSDB.script"))
                || !Files.isRegularFile(db.resolve("JFSDB.properties"))
                || Files.exists(db.resolve("JFSDB.lck")) || Files.exists(db.resolve("JFSDB.log"))) {
            throw new IOException("Catalogue must be complete and cleanly shut down: " + db);
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(db.resolve("JFSDB.properties"))) {
            properties.load(input);
        }
        if (!"no".equals(properties.getProperty("modified"))) {
            throw new IOException("Catalogue must be cleanly shut down: " + db);
        }
    }

    private static void copyCatalog(Path source, Path destination) throws IOException {
        Files.createDirectory(destination);
        try (var files = Files.list(source)) {
            for (Path file : files.filter(path -> path.getFileName().toString().startsWith("JFSDB.")).toList()) {
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)) {
                    throw new IOException("Unexpected catalogue file: " + file);
                }
                Files.copy(file, destination.resolve(file.getFileName()));
            }
        }
    }

    private static void extractCatalog(Path archive, Path destination) throws IOException {
        Files.createDirectory(destination);
        // Accept clean rollback ZIPs with root or db/ catalogue entries, not arbitrary archives.
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("db/")) {
                    name = name.substring(3);
                }
                if (!name.matches("JFSDB\\.(properties|script|data|backup|log|lck)")) {
                    throw new IOException("Unexpected legacy backup entry: " + entry.getName());
                }
                try (InputStream input = zip.getInputStream(entry)) {
                    Files.copy(input, destination.resolve(name));
                }
            }
        }
    }

    private static Map<String, String> catalogHashes(Path db) throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        try (var paths = Files.list(db)) {
            for (Path path : paths.filter(file -> file.getFileName().toString().startsWith("JFSDB.")).toList()) {
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
                    throw new IOException("Unexpected catalogue file: " + path);
                }
                hashes.put(path.getFileName().toString(), sha256(path));
            }
        }
        return Map.copyOf(hashes);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record RecoveryResult(NormalizedActivationService.ActivationResult activation,
                                 AccountPlanCatalogStagingConverter.ConversionResult catalogue,
                                 String legacyBackupSha256) {}
}
