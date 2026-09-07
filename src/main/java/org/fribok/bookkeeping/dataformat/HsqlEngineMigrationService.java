package org.fribok.bookkeeping.dataformat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Safely upgrades legacy HSQLDB catalogs on a staging copy before normal startup. */
public final class HsqlEngineMigrationService {
    private static final String DATABASE_NAME = "JFSDB";
    private static final String LEGACY_VERSION_PREFIX = "1.";
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private HsqlEngineMigrationService() {}

    /**
     * Upgrades a legacy HSQLDB catalog in {@code dataDirectory}, if present.
     * The source is archived and retained before a staged catalog replaces it.
     *
     * @param dataDirectory Bokfri data directory
     * @return result describing whether an upgrade was performed
     * @throws IOException if staging, backup, verification or activation fails
     * @throws SQLException if HSQLDB cannot upgrade or reopen the staged catalog
     * @throws ClassNotFoundException if the HSQLDB driver is unavailable
     */
    public static EngineMigrationResult migrateIfRequired(Path dataDirectory)
            throws IOException, SQLException, ClassNotFoundException {
        Path data = dataDirectory.toAbsolutePath().normalize();
        Path databaseDirectory = data.resolve("db");
        Path propertiesFile = databaseDirectory.resolve(DATABASE_NAME + ".properties");
        if (!Files.isRegularFile(propertiesFile)) {
            return new EngineMigrationResult(false, null, null);
        }

        String sourceVersion = readVersion(propertiesFile);
        if (sourceVersion == null || !sourceVersion.startsWith(LEGACY_VERSION_PREFIX)) {
            return new EngineMigrationResult(false, sourceVersion, null);
        }

        Path backups = data.resolve("backups");
        Files.createDirectories(backups);
        String timestamp = LocalDateTime.now().format(TIMESTAMP);
        Path rollbackArchive = backups.resolve("bokfri-before-hsqldb-2.5-" + timestamp + ".zip");
        createAndVerifyArchive(databaseDirectory, rollbackArchive);

        Path staging = data.resolve(".hsqldb-2.5-staging-" + timestamp);
        Path stagedDatabaseDirectory = staging.resolve("db");
        Path retainedSource = backups.resolve("hsqldb-1.8-source-" + timestamp);
        boolean sourceMoved = false;
        try {
            copyDirectory(databaseDirectory, stagedDatabaseDirectory);
            upgradeAndVerify(stagedDatabaseDirectory.resolve(DATABASE_NAME));

            move(databaseDirectory, retainedSource);
            sourceMoved = true;
            try {
                move(stagedDatabaseDirectory, databaseDirectory);
            } catch (IOException activationFailure) {
                move(retainedSource, databaseDirectory);
                sourceMoved = false;
                throw activationFailure;
            }
            deleteTree(staging);
            return new EngineMigrationResult(true, sourceVersion, rollbackArchive);
        } catch (IOException | SQLException | ClassNotFoundException exception) {
            if (sourceMoved && !Files.exists(databaseDirectory) && Files.exists(retainedSource)) {
                try {
                    move(retainedSource, databaseDirectory);
                } catch (IOException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
            }
            try {
                deleteTree(staging);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    private static String readVersion(Path propertiesFile) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(propertiesFile)) {
            properties.load(input);
        }
        return properties.getProperty("version");
    }

    private static void upgradeAndVerify(Path database)
            throws ClassNotFoundException, SQLException, IOException {
        Class.forName("org.hsqldb.jdbcDriver");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:hsqldb:file:" + database.toAbsolutePath(), "sa", "")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SHUTDOWN SCRIPT");
            }
        }
        try (Connection connection = DriverManager.getConnection(
                "jdbc:hsqldb:file:" + database.toAbsolutePath(), "sa", "")) {
            try (Statement statement = connection.createStatement()) {
                statement.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES");
                statement.execute("SHUTDOWN");
            }
        }
        String upgradedVersion = readVersion(Path.of(database + ".properties"));
        if (upgradedVersion == null || upgradedVersion.startsWith(LEGACY_VERSION_PREFIX)) {
            throw new IOException("HSQLDB staging catalog did not upgrade from version "
                    + upgradedVersion);
        }
    }

    private static void createAndVerifyArchive(Path databaseDirectory, Path archive)
            throws IOException {
        Path temporary = Files.createTempFile(archive.getParent(), ".hsqldb-rollback-", ".zip");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary));
                 var files = Files.list(databaseDirectory)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().startsWith(DATABASE_NAME + "."))
                        .sorted().toList()) {
                    zip.putNextEntry(new ZipEntry(file.getFileName().toString()));
                    Files.copy(file, zip);
                    zip.closeEntry();
                }
            }
            try (ZipFile zip = new ZipFile(temporary.toFile())) {
                if (zip.getEntry(DATABASE_NAME + ".properties") == null
                        || zip.getEntry(DATABASE_NAME + ".script") == null) {
                    throw new IOException("HSQLDB rollback archive is incomplete");
                }
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    try (InputStream input = zip.getInputStream(entries.nextElement())) {
                        input.transferTo(OutputStream.nullOutputStream());
                    }
                }
            }
            move(temporary, archive);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void copyDirectory(Path source, Path destination) throws IOException {
        Files.createDirectories(destination);
        try (var files = Files.list(source)) {
            for (Path file : files.toList()) {
                Path target = destination.resolve(file.getFileName().toString());
                if (Files.isDirectory(file)) {
                    copyDirectory(file, target);
                } else {
                    Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
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

    /** Result of checking and optionally upgrading the HSQLDB engine format. */
    public record EngineMigrationResult(boolean migrated, String sourceVersion,
                                        Path rollbackArchive) {}
}
