package org.fribok.bookkeeping.dataformat;

import org.fribok.bookkeeping.app.Version;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies ordered SQL resources and verifies their recorded SHA-256 checksums. */
public final class SchemaMigrationRunner {
    public static final String HISTORY_TABLE = "BOKFRI_SCHEMA_HISTORY";
    private static final Pattern RESOURCE_NAME = Pattern.compile(
            "V([1-9][0-9]*)__([a-z0-9][a-z0-9_-]*)\\.sql");

    private final Clock clock;

    public SchemaMigrationRunner(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Loads a migration from a classpath resource and validates its canonical name. */
    public static SchemaMigration loadResource(Class<?> owner, String resourceName)
            throws IOException {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(resourceName, "resourceName");
        String filename = resourceName.substring(resourceName.lastIndexOf('/') + 1);
        Matcher matcher = RESOURCE_NAME.matcher(filename);
        if (!matcher.matches()) {
            throw new IOException("Invalid schema migration resource name: " + resourceName);
        }
        byte[] bytes;
        try (InputStream input = owner.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Missing schema migration resource: " + resourceName);
            }
            bytes = input.readAllBytes();
        }
        String description = matcher.group(2).replace('_', ' ');
        return new SchemaMigration(Integer.parseInt(matcher.group(1)), description, filename,
                sha256(bytes), new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * Verifies installed checksums and applies all pending migrations in order.
     *
     * <p>The caller controls the connection lifecycle. HSQLDB may commit DDL implicitly, so this
     * runner is intended for a disposable staging catalog: a failure leaves no successful history
     * record but may leave partial structure in staging, which must then be discarded.
     */
    public List<Integer> migrate(Connection connection, List<SchemaMigration> migrations)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        List<SchemaMigration> ordered = validateSequence(migrations);
        boolean originalAutoCommit = connection.getAutoCommit();
        if (originalAutoCommit) {
            connection.setAutoCommit(false);
        }
        try {
            createHistoryTable(connection);
            Map<Integer, InstalledMigration> installed = readInstalled(connection);
            verifyInstalled(ordered, installed);
            List<Integer> applied = new ArrayList<>();
            for (SchemaMigration migration : ordered) {
                if (installed.containsKey(migration.version())) {
                    continue;
                }
                executeScript(connection, migration);
                record(connection, migration, clock.instant());
                applied.add(migration.version());
            }
            connection.commit();
            return List.copyOf(applied);
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            if (originalAutoCommit) {
                connection.setAutoCommit(true);
            }
        }
    }

    private static List<SchemaMigration> validateSequence(List<SchemaMigration> migrations)
            throws SchemaMigrationException {
        Objects.requireNonNull(migrations, "migrations");
        List<SchemaMigration> ordered = migrations.stream()
                .sorted(Comparator.comparingInt(SchemaMigration::version)).toList();
        int previous = 0;
        for (SchemaMigration migration : ordered) {
            if (migration.version() != previous + 1) {
                throw new SchemaMigrationException("Schema migrations must be consecutive from 1; "
                        + "expected " + (previous + 1) + " but found " + migration.version());
            }
            Matcher name = RESOURCE_NAME.matcher(migration.scriptName());
            if (!name.matches() || Integer.parseInt(name.group(1)) != migration.version()) {
                throw new SchemaMigrationException("Migration name does not match version "
                        + migration.version() + ": " + migration.scriptName());
            }
            String actualChecksum = sha256(
                    migration.sql().getBytes(StandardCharsets.UTF_8));
            if (!actualChecksum.equals(migration.scriptSha256())) {
                throw new SchemaMigrationException("Migration checksum does not match SQL for "
                        + migration.scriptName());
            }
            previous = migration.version();
        }
        return ordered;
    }

    private static void createHistoryTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + HISTORY_TABLE + " ("
                    + "version INTEGER PRIMARY KEY,"
                    + "description VARCHAR(255) NOT NULL,"
                    + "script_name VARCHAR(255) NOT NULL UNIQUE,"
                    + "script_sha256 CHAR(64) NOT NULL,"
                    + "installed_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                    + "application_version VARCHAR(64) NOT NULL)");
        }
    }

    private static Map<Integer, InstalledMigration> readInstalled(Connection connection)
            throws SQLException {
        Map<Integer, InstalledMigration> installed = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT version, script_name, script_sha256 "
                     + "FROM " + HISTORY_TABLE + " ORDER BY version")) {
            while (result.next()) {
                int version = result.getInt(1);
                InstalledMigration replaced = installed.put(version,
                        new InstalledMigration(result.getString(2), result.getString(3)));
                if (replaced != null) {
                    throw new SchemaMigrationException("Duplicate installed migration " + version);
                }
            }
        }
        return installed;
    }

    private static void verifyInstalled(List<SchemaMigration> migrations,
                                        Map<Integer, InstalledMigration> installed)
            throws SchemaMigrationException {
        Map<Integer, SchemaMigration> packaged = new HashMap<>();
        for (SchemaMigration migration : migrations) {
            packaged.put(migration.version(), migration);
        }
        int highestInstalled = installed.keySet().stream().mapToInt(Integer::intValue)
                .max().orElse(0);
        for (int version = 1; version <= highestInstalled; version++) {
            if (!installed.containsKey(version)) {
                throw new SchemaMigrationException("Installed schema migration history has a gap "
                        + "at version " + version);
            }
        }
        for (Map.Entry<Integer, InstalledMigration> entry : installed.entrySet()) {
            SchemaMigration migration = packaged.get(entry.getKey());
            if (migration == null) {
                throw new SchemaMigrationException("Installed schema migration " + entry.getKey()
                        + " is missing from this Bokfri version");
            }
            InstalledMigration actual = entry.getValue();
            if (!actual.scriptName().equals(migration.scriptName())
                    || !actual.scriptSha256().equals(migration.scriptSha256())) {
                throw new SchemaMigrationException("Installed schema migration " + entry.getKey()
                        + " does not match packaged resource " + migration.scriptName());
            }
        }
    }

    private static void executeScript(Connection connection, SchemaMigration migration)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : splitStatements(migration.sql())) {
                String trimmed = sql.trim();
                if (!trimmed.isEmpty()) {
                    try {
                        statement.execute(trimmed);
                    } catch (SQLException exception) {
                        throw new SchemaMigrationException("Schema migration "
                                + migration.scriptName() + " failed", exception);
                    }
                }
            }
        }
    }

    private static void record(Connection connection, SchemaMigration migration, Instant installedAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO "
                + HISTORY_TABLE + " (version, description, script_name, script_sha256, "
                + "installed_at, application_version) VALUES (?,?,?,?,?,?)")) {
            statement.setInt(1, migration.version());
            statement.setString(2, migration.description());
            statement.setString(3, migration.scriptName());
            statement.setString(4, migration.scriptSha256());
            statement.setObject(5, OffsetDateTime.ofInstant(installedAt, ZoneOffset.UTC));
            statement.setString(6, Version.APP_VERSION);
            statement.executeUpdate();
        }
    }

    static List<String> splitStatements(String script) throws SchemaMigrationException {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < script.length(); index++) {
            char character = script.charAt(index);
            char next = index + 1 < script.length() ? script.charAt(index + 1) : '\0';
            if (lineComment) {
                if (character == '\n') {
                    lineComment = false;
                    current.append(character);
                }
                continue;
            }
            if (blockComment) {
                if (character == '*' && next == '/') {
                    blockComment = false;
                    index++;
                }
                continue;
            }
            if (!quoted && character == '-' && next == '-') {
                lineComment = true;
                index++;
                continue;
            }
            if (!quoted && character == '/' && next == '*') {
                blockComment = true;
                index++;
                continue;
            }
            if (character == '\'') {
                current.append(character);
                if (quoted && next == '\'') {
                    current.append(next);
                    index++;
                } else {
                    quoted = !quoted;
                }
                continue;
            }
            if (!quoted && character == ';') {
                if (!current.toString().trim().isEmpty()) {
                    statements.add(current.toString().trim());
                }
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        if (quoted || blockComment) {
            throw new SchemaMigrationException("Unterminated SQL quote or block comment");
        }
        if (!current.toString().trim().isEmpty()) {
            statements.add(current.toString().trim());
        }
        return List.copyOf(statements);
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record InstalledMigration(String scriptName, String scriptSha256) {}
}
