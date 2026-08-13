package org.fribok.bookkeeping.dataformat;

import java.sql.SQLException;

/** Signals that an older supported database must be backed up and migrated before use. */
public final class DataMigrationRequiredException extends SQLException {
    private final int foundVersion;
    private final int requiredVersion;

    public DataMigrationRequiredException(int foundVersion, int requiredVersion) {
        super("Database data format " + foundVersion + " must be migrated to format "
                + requiredVersion + " before it can be opened.");
        this.foundVersion = foundVersion;
        this.requiredVersion = requiredVersion;
    }

    public int getFoundVersion() {
        return foundVersion;
    }

    public int getRequiredVersion() {
        return requiredVersion;
    }
}
