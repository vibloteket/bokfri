package org.fribok.bookkeeping.dataformat;

import org.fribok.bookkeeping.app.Version;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Reads, validates, and initializes Bokfri's application-level database format metadata. */
public final class DataFormatManager {
    public static final int LEGACY_DATA_FORMAT_VERSION = 1;
    public static final int CURRENT_DATA_FORMAT_VERSION = 2;

    private static final String TABLE_NAME = "BOKFRI_METADATA";
    private static final String FORMAT_KEY = "data_format_version";
    private static final String APPLICATION_KEY = "application_version";

    private DataFormatManager() {}

    /**
     * Checks the data format before normal database startup and records metadata for new databases.
     * Existing databases without metadata are treated as legacy format 1 and are not modified.
     *
     * @param connection open database connection
     * @param databaseExisted whether database files existed before the connection was opened
     * @return the detected data format version
     * @throws SQLException if metadata cannot be read or the format is unsupported
     */
    public static int checkAndInitialize(Connection connection, boolean databaseExisted)
            throws SQLException {
        int version = checkSupported(connection);
        if (databaseExisted && version < CURRENT_DATA_FORMAT_VERSION) {
            throw new DataMigrationRequiredException(version, CURRENT_DATA_FORMAT_VERSION);
        }
        if (!databaseExisted) {
            createMetadataTable(connection);
            put(connection, FORMAT_KEY, Integer.toString(CURRENT_DATA_FORMAT_VERSION));
            put(connection, APPLICATION_KEY, Version.APP_VERSION);
            connection.commit();
            return CURRENT_DATA_FORMAT_VERSION;
        }
        return version;
    }

    /**
     * Rejects formats newer than this Bokfri version before normal data loading.
     *
     * @param connection open database connection
     * @return the detected supported version
     * @throws SQLException if metadata is invalid or the format is too new
     */
    public static int checkSupported(Connection connection) throws SQLException {
        int version = detect(connection);
        if (version > CURRENT_DATA_FORMAT_VERSION) {
            throw new DataFormatException(version, CURRENT_DATA_FORMAT_VERSION);
        }
        return version;
    }

    /** Returns the stored format, or legacy format 1 when metadata is absent. */
    public static int detect(Connection connection) throws SQLException {
        if (!tableExists(connection)) {
            return LEGACY_DATA_FORMAT_VERSION;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT metadata_value FROM " + TABLE_NAME + " WHERE metadata_key=?")) {
            statement.setString(1, FORMAT_KEY);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return LEGACY_DATA_FORMAT_VERSION;
                }
                String value = result.getString(1);
                try {
                    int version = Integer.parseInt(value);
                    if (version < 1) {
                        throw new SQLException("Invalid Bokfri data format version: " + value);
                    }
                    return version;
                } catch (NumberFormatException exception) {
                    throw new SQLException("Invalid Bokfri data format version: " + value, exception);
                }
            }
        }
    }

    /** Records a completed migration after its backup and data checks have succeeded. */
    public static void recordCurrentVersion(Connection connection) throws SQLException {
        createMetadataTable(connection);
        put(connection, FORMAT_KEY, Integer.toString(CURRENT_DATA_FORMAT_VERSION));
        put(connection, APPLICATION_KEY, Version.APP_VERSION);
        connection.commit();
    }

    static void writeForTesting(Connection connection, int version) throws SQLException {
        createMetadataTable(connection);
        put(connection, FORMAT_KEY, Integer.toString(version));
        connection.commit();
    }

    private static boolean tableExists(Connection connection) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(
                null, null, TABLE_NAME, new String[]{"TABLE"})) {
            return tables.next();
        }
    }

    private static void createMetadataTable(Connection connection) throws SQLException {
        if (tableExists(connection)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE " + TABLE_NAME
                    + " (metadata_key VARCHAR(64) PRIMARY KEY, metadata_value VARCHAR(255) NOT NULL)");
        }
    }

    private static void put(Connection connection, String key, String value) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM " + TABLE_NAME + " WHERE metadata_key=?")) {
            delete.setString(1, key);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + TABLE_NAME + " (metadata_key, metadata_value) VALUES (?,?)")) {
            insert.setString(1, key);
            insert.setString(2, value);
            insert.executeUpdate();
        }
    }
}
