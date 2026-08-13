package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests application-level database format detection and compatibility checks. */
class DataFormatManagerTest {

    @Test
    void treatsExistingDatabaseWithoutMetadataAsLegacyFormatOne() throws Exception {
        try (Connection connection = connection()) {
            connection.createStatement().executeUpdate("CREATE TABLE legacy_table (id INTEGER)");

            assertThatThrownBy(() -> DataFormatManager.checkAndInitialize(connection, true))
                    .isInstanceOf(DataMigrationRequiredException.class)
                    .hasMessageContaining("must be migrated");
            assertThat(DataFormatManager.detect(connection)).isEqualTo(1);
            assertThat(metadataTableExists(connection)).isFalse();
        }
    }

    @Test
    void initializesMetadataForANewDatabase() throws Exception {
        try (Connection connection = connection()) {
            int version = DataFormatManager.checkAndInitialize(connection, false);

            assertThat(version).isEqualTo(DataFormatManager.CURRENT_DATA_FORMAT_VERSION);
            assertThat(DataFormatManager.detect(connection))
                    .isEqualTo(DataFormatManager.CURRENT_DATA_FORMAT_VERSION);
        }
    }

    @Test
    void rejectsANewerDataFormatBeforeNormalStartup() throws Exception {
        try (Connection connection = connection()) {
            DataFormatManager.writeForTesting(connection,
                    DataFormatManager.CURRENT_DATA_FORMAT_VERSION + 1);

            assertThatThrownBy(() -> DataFormatManager.checkAndInitialize(connection, true))
                    .isInstanceOf(DataFormatException.class)
                    .hasMessageContaining("newer than supported");
        }
    }

    private Connection connection() throws Exception {
        Class.forName("org.hsqldb.jdbcDriver");
        Connection connection = DriverManager.getConnection(
                "jdbc:hsqldb:mem:data_format_" + UUID.randomUUID(), "sa", "");
        connection.setAutoCommit(false);
        return connection;
    }

    private boolean metadataTableExists(Connection connection) throws Exception {
        try (var tables = connection.getMetaData().getTables(
                null, null, "BOKFRI_METADATA", new String[]{"TABLE"})) {
            return tables.next();
        }
    }
}
