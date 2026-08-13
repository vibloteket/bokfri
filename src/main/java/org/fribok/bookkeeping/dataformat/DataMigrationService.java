package org.fribok.bookkeeping.dataformat;

import org.fribok.bookkeeping.service.backup.BackupService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;

/** Creates a verified pre-migration backup and advances supported legacy databases. */
public final class DataMigrationService {
    private DataMigrationService() {}

    /**
     * Migrates the database in {@code dataDirectory} to the current format.
     * Format 1 rows are already readable through the model's serialization bridge;
     * recording format 2 enables decimal quantities on subsequent writes.
     *
     * @param dataDirectory Bokfri data directory
     * @return verified pre-migration backup path
     * @throws IOException if backup creation or verification fails
     * @throws SQLException if metadata cannot be updated
     */
    public static Path migrate(Path dataDirectory) throws IOException, SQLException {
        Path data = dataDirectory.toAbsolutePath().normalize();
        Path backups = data.resolve("backups");
        Files.createDirectories(backups);
        String timestamp = java.time.LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path backup = backups.resolve("bokfri-before-format-"
                + DataFormatManager.CURRENT_DATA_FORMAT_VERSION + "-" + timestamp + ".zip");
        BackupService backupService = new BackupService(data);
        backupService.create(backup, false, DataFormatManager.LEGACY_DATA_FORMAT_VERSION);
        if (backupService.verify(backup).dataFormatVersion()
                != DataFormatManager.LEGACY_DATA_FORMAT_VERSION) {
            throw new IOException("Pre-migration backup did not record legacy data format 1");
        }

        try {
            Class.forName("org.hsqldb.jdbcDriver");
        } catch (ClassNotFoundException exception) {
            throw new IOException("HSQLDB driver is unavailable", exception);
        }
        String database = data.resolve("db/JFSDB").toString();
        try (Connection connection = DriverManager.getConnection(
                "jdbc:hsqldb:file:" + database, "sa", "")) {
            connection.setAutoCommit(false);
            try {
                int version = DataFormatManager.checkSupported(connection);
                if (version != DataFormatManager.LEGACY_DATA_FORMAT_VERSION) {
                    throw new SQLException("Expected data format 1, found " + version);
                }
                DataFormatManager.recordCurrentVersion(connection);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                try (var statement = connection.createStatement()) {
                    statement.execute("SHUTDOWN");
                }
            }
        }
        return backup;
    }
}
